package com.example

import com.example.data.KodEndpointResolver
import com.example.data.KodNetworkResilience
import com.example.data.KodUploadResilience
import com.example.data.SyncConflictResolver
import com.example.data.SyncFileMeta
import com.example.data.SyncSnapshotMeta
import com.example.data.ThreeWaySyncAction
import com.example.model.SyncConflictPolicy
import com.example.model.SyncDirection
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure JVM unit tests validating v0.10 architectural hardening:
 * 1. Cross-host object storage redirect detection & sensitive header stripping (OSS/COS/MinIO/S3).
 * 2. Cloudflare Tunnel 100 MB upload chunk safety, SQLite lock backoff + jitter, and concurrency limiter.
 * 3. LAN / WAN dual-endpoint URL normalization & routing fallback.
 * 4. Synology Drive-style 3-way snapshot reconciliation & conflict copy filename generation.
 */
class KodV010ArchitectureHardeningTest {

    @Test
    fun crossOriginRedirectDetection_detectsDifferentHostSchemeOrPort() {
        assertFalse(
            KodNetworkResilience.isCrossOriginRedirect(
                "https://cloud.example.com/?explorer/index/fileDownload",
                "https://cloud.example.com:443/data/file.pdf"
            )
        )
        assertTrue(
            KodNetworkResilience.isCrossOriginRedirect(
                "https://cloud.example.com/?explorer/index/fileDownload",
                "https://my-bucket.oss-cn-hangzhou.aliyuncs.com/path/file.pdf?OSSAccessKeyId=abc"
            )
        )
        assertTrue(
            KodNetworkResilience.isCrossOriginRedirect(
                "https://cloud.example.com/a",
                "http://cloud.example.com/a"
            )
        )
        assertTrue(
            KodNetworkResilience.isCrossOriginRedirect(
                "http://192.168.1.10:8080/a",
                "http://192.168.1.10:9000/minio/bucket/a"
            )
        )
    }

    @Test
    fun stripSensitiveHeaders_removesKodAuthHeadersWhileKeepingRangeAndUserAgent() {
        val original = mapOf(
            "Cookie" to "KOD_SESSION_ID=secret; CSRF_TOKEN=xyz",
            "Authorization" to "Bearer secret",
            "CSRF-Token" to "xyz",
            "X-CSRF-Token" to "xyz",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://cloud.example.com/",
            "Range" to "bytes=1024-",
            "User-Agent" to "kodCloud-System=Android"
        )
        val cleaned = KodNetworkResilience.stripSensitiveHeaders(original)
        assertEquals(setOf("Range", "User-Agent"), cleaned.keys)
        assertEquals("bytes=1024-", cleaned["Range"])
    }

    @Test
    fun uploadChunkSize_enforcesCloudflareSafeBoundsAndServerCap() {
        val defaultBytes = KodUploadResilience.resolveEffectiveChunkSizeBytes()
        assertEquals(4L * 1024L * 1024L, defaultBytes)

        // Never exceeds 20 MB ceiling (well below Cloudflare's 100 MB request cap)
        val clampedHigh = KodUploadResilience.resolveEffectiveChunkSizeBytes(preferredChunkMb = 128)
        assertEquals(20L * 1024L * 1024L, clampedHigh)
        assertTrue(clampedHigh < KodUploadResilience.CLOUDFLARE_HARD_CAP_BYTES)

        // Never below 1 MB floor
        val clampedLow = KodUploadResilience.resolveEffectiveChunkSizeBytes(preferredChunkMb = 0)
        assertEquals(1L * 1024L * 1024L, clampedLow)

        // Respects tighter server-advertised chunk size
        val serverCapped = KodUploadResilience.resolveEffectiveChunkSizeBytes(
            preferredChunkMb = 8,
            serverAdvertisedChunkBytes = 2L * 1024L * 1024L
        )
        assertEquals(2L * 1024L * 1024L, serverCapped)
    }

    @Test
    fun sqliteLockAndRetryableClassifier_distinguishesTransientVsAuthErrors() {
        assertTrue(KodUploadResilience.isSqliteLockContention("SQLSTATE[HY000]: General error: 5 database is locked"))
        assertTrue(KodUploadResilience.isSqliteLockContention("SQLITE_BUSY while writing io_source"))
        assertTrue(KodUploadResilience.isRetryableUploadFailure(httpCode = 503, errorMessage = "Service Unavailable"))
        assertTrue(KodUploadResilience.isRetryableUploadFailure(httpCode = 429, errorMessage = "Too Many Requests"))
        assertTrue(KodUploadResilience.isRetryableUploadFailure(cause = IOException("Connection reset")))

        // Auth failures & 4xx client errors must fail fast without wasteful retries
        assertFalse(KodUploadResilience.isRetryableUploadFailure(httpCode = 401, errorMessage = "Unauthorized"))
        assertFalse(KodUploadResilience.isRetryableUploadFailure(httpCode = 403, errorMessage = "Forbidden"))
        assertFalse(KodUploadResilience.isRetryableUploadFailure(httpCode = 500, errorMessage = "accessToken 已过期，请重新登录"))
    }

    @Test
    fun backoffWithJitter_growsExponentiallyAndRespectsCap() {
        val d0 = KodUploadResilience.computeBackoffDelayMs(attempt = 0, isSqliteLock = false, jitterUnit = 0.0)
        val d1 = KodUploadResilience.computeBackoffDelayMs(attempt = 1, isSqliteLock = false, jitterUnit = 0.0)
        val d2 = KodUploadResilience.computeBackoffDelayMs(attempt = 2, isSqliteLock = false, jitterUnit = 0.0)
        assertEquals(500L, d0)
        assertEquals(1000L, d1)
        assertEquals(2000L, d2)

        val sqliteDelay = KodUploadResilience.computeBackoffDelayMs(attempt = 0, isSqliteLock = true, jitterUnit = 0.5)
        assertEquals(900L + 450L, sqliteDelay)

        val capped = KodUploadResilience.computeBackoffDelayMs(attempt = 10, isSqliteLock = true, jitterUnit = 0.9)
        assertEquals(12_000L, capped)
    }

    @Test
    fun perSiteConcurrencyGate_enforcesMaxParallelPermits() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val jobs = (1..6).map {
            async {
                KodUploadResilience.withSiteUploadPermit(
                    serverUrl = "https://sqlite-nas.local:8080",
                    maxConcurrent = 2
                ) {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { prev -> maxOf(prev, now) }
                    delay(40)
                    active.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }
        assertTrue("Expected peak concurrency <= 2 but was ${peak.get()}", peak.get() <= 2)
    }

    @Test
    fun lanEndpointNormalization_defaultsBareLanIpToHttpAndRejectsInvalidUrls() {
        assertEquals("http://192.168.1.10:8080", KodEndpointResolver.normalizeOptionalLanUrl("192.168.1.10:8080/"))
        assertEquals("https://nas.lan", KodEndpointResolver.normalizeOptionalLanUrl("https://nas.lan/"))
        assertEquals("", KodEndpointResolver.normalizeOptionalLanUrl("   "))
        assertEquals("", KodEndpointResolver.normalizeOptionalLanUrl("not a valid url :::"))
    }

    @Test
    fun threeWaySyncResolver_distinguishesSingleSideEditsAndDualConflicts() {
        val snapshot = SyncSnapshotMeta(
            localSize = 100L,
            localModifiedMs = 1_000_000L,
            remoteSize = 100L,
            remoteModifiedMs = 1_000_000L,
            remoteHashMd5 = "aaa111"
        )

        // Only local changed -> UPLOAD_LOCAL
        assertEquals(
            ThreeWaySyncAction.UPLOAD_LOCAL,
            SyncConflictResolver.decide(
                direction = SyncDirection.TWO_WAY,
                conflictPolicy = SyncConflictPolicy.KEEP_BOTH_CONFLICT_COPY,
                local = SyncFileMeta(size = 120L, modifiedMs = 1_060_000L),
                remote = SyncFileMeta(size = 100L, modifiedMs = 1_000_000L, hashMd5 = "aaa111"),
                snapshot = snapshot
            )
        )

        // Only remote changed (even via MD5 change) -> DOWNLOAD_REMOTE
        assertEquals(
            ThreeWaySyncAction.DOWNLOAD_REMOTE,
            SyncConflictResolver.decide(
                direction = SyncDirection.TWO_WAY,
                conflictPolicy = SyncConflictPolicy.KEEP_BOTH_CONFLICT_COPY,
                local = SyncFileMeta(size = 100L, modifiedMs = 1_000_000L),
                remote = SyncFileMeta(size = 100L, modifiedMs = 1_050_000L, hashMd5 = "bbb222"),
                snapshot = snapshot
            )
        )

        // Both sides modified since snapshot -> KEEP_BOTH creates conflict copy!
        assertEquals(
            ThreeWaySyncAction.CONFLICT_KEEP_BOTH,
            SyncConflictResolver.decide(
                direction = SyncDirection.TWO_WAY,
                conflictPolicy = SyncConflictPolicy.KEEP_BOTH_CONFLICT_COPY,
                local = SyncFileMeta(size = 140L, modifiedMs = 1_080_000L),
                remote = SyncFileMeta(size = 155L, modifiedMs = 1_090_000L, hashMd5 = "ccc333"),
                snapshot = snapshot
            )
        )

        // Both sides modified + SERVER_WINS -> DOWNLOAD_REMOTE
        assertEquals(
            ThreeWaySyncAction.DOWNLOAD_REMOTE,
            SyncConflictResolver.decide(
                direction = SyncDirection.TWO_WAY,
                conflictPolicy = SyncConflictPolicy.SERVER_WINS,
                local = SyncFileMeta(size = 140L, modifiedMs = 1_120_000L),
                remote = SyncFileMeta(size = 155L, modifiedMs = 1_090_000L, hashMd5 = "ccc333"),
                snapshot = snapshot
            )
        )

        // Both sides modified + LOCAL_WINS -> UPLOAD_LOCAL
        assertEquals(
            ThreeWaySyncAction.UPLOAD_LOCAL,
            SyncConflictResolver.decide(
                direction = SyncDirection.TWO_WAY,
                conflictPolicy = SyncConflictPolicy.LOCAL_WINS,
                local = SyncFileMeta(size = 140L, modifiedMs = 1_080_000L),
                remote = SyncFileMeta(size = 155L, modifiedMs = 1_120_000L, hashMd5 = "ccc333"),
                snapshot = snapshot
            )
        )
    }

    @Test
    fun conflictCopyNameAndHiddenFilter_preserveExtensionsAndSkipTempFiles() {
        val copyName = SyncConflictResolver.buildConflictCopyName("年度报表.xlsx", timestampMs = 1_763_899_200_000L)
        assertTrue(copyName.startsWith("年度报表 (冲突副本 "))
        assertTrue(copyName.endsWith(").xlsx"))

        val noExtCopy = SyncConflictResolver.buildConflictCopyName("README", timestampMs = 1_763_899_200_000L)
        assertTrue(noExtCopy.startsWith("README (冲突副本 "))

        assertTrue(SyncConflictResolver.shouldIgnoreEntry(".DS_Store", ignoreHiddenFiles = true))
        assertFalse(SyncConflictResolver.shouldIgnoreEntry(".env", ignoreHiddenFiles = false))
        assertTrue(SyncConflictResolver.shouldIgnoreEntry("~$" + "budget.docx", ignoreHiddenFiles = false))
        assertTrue(SyncConflictResolver.shouldIgnoreEntry("archive.zip.part", ignoreHiddenFiles = false))
    }
}
