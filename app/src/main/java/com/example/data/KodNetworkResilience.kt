package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure, testable network & sync resilience primitives addressing KodBox real-world edge cases:
 * 1. Cross-host object storage (OSS/COS/MinIO/S3) redirect header stripping.
 * 2. Cloudflare Tunnel 100 MB upload limit protection + SQLite `database is locked` backoff & concurrency gate.
 * 3. Per-account LAN / WAN dual-endpoint auto-switching.
 * 4. Synology Drive-style 3-way snapshot conflict resolution.
 */
object KodNetworkResilience {

    val SENSITIVE_AUTH_HEADERS: Set<String> = setOf(
        "cookie",
        "authorization",
        "csrf-token",
        "x-csrf-token",
        "x-requested-with",
        "referer"
    )

    val REDIRECT_STATUS_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)

    /**
     * Returns true when a redirect crosses scheme, host, or port boundaries (e.g., KodBox server
     * redirecting a download or media stream to Aliyun OSS, Tencent COS, MinIO, or AWS S3).
     */
    fun isCrossOriginRedirect(fromUrl: String, toUrl: String): Boolean {
        val from = runCatching { URI(fromUrl) }.getOrNull() ?: return true
        val to = runCatching { URI(toUrl) }.getOrNull() ?: return true
        val fromScheme = from.scheme?.lowercase(Locale.ROOT).orEmpty()
        val toScheme = to.scheme?.lowercase(Locale.ROOT).orEmpty()
        val fromHost = from.host?.lowercase(Locale.ROOT).orEmpty()
        val toHost = to.host?.lowercase(Locale.ROOT).orEmpty()
        val fromPort = effectivePort(fromScheme, from.port)
        val toPort = effectivePort(toScheme, to.port)
        return fromScheme != toScheme || fromHost != toHost || fromPort != toPort
    }

    fun isCrossOriginRedirect(from: HttpUrl, to: HttpUrl): Boolean {
        return !from.scheme.equals(to.scheme, ignoreCase = true) ||
            !from.host.equals(to.host, ignoreCase = true) ||
            from.port != to.port
    }

    private fun effectivePort(scheme: String, port: Int): Int {
        if (port > 0) return port
        return when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    /**
     * Strips KodBox authentication/session headers from a map or OkHttp [Headers] so third-party
     * object storage endpoints never receive leaked cookies or CSRF tokens (which also cause S3/OSS 400/403).
     */
    fun stripSensitiveHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { it.lowercase(Locale.ROOT) !in SENSITIVE_AUTH_HEADERS }

    fun stripSensitiveHeadersFromBuilder(builder: Request.Builder) {
        builder.removeHeader("Cookie")
        builder.removeHeader("Authorization")
        builder.removeHeader("CSRF-Token")
        builder.removeHeader("X-CSRF-Token")
        builder.removeHeader("X-Requested-With")
        builder.removeHeader("Referer")
    }

    /**
     * Executes an HTTP request with manual redirect handling (max [maxRedirects] hops) that
     * strips KodBox session/auth headers and switches to a cookie-free client whenever a redirect
     * hops to a third-party host (such as OSS/COS/MinIO/S3 presigned URLs).
     */
    fun executeWithCrossHostSafeRedirects(
        authedClient: OkHttpClient,
        anonymousNoRedirectClient: OkHttpClient,
        initialRequest: Request,
        maxRedirects: Int = 6
    ): Response {
        var currentRequest = initialRequest
        val originUrl = initialRequest.url
        var crossedOrigin = false

        for (hop in 0..maxRedirects) {
            val client = if (crossedOrigin) anonymousNoRedirectClient else authedClient
            val response = client.newCall(currentRequest).execute()
            if (response.code !in REDIRECT_STATUS_CODES) {
                return response
            }
            val location = response.header("Location")?.trim().orEmpty()
            if (location.isBlank()) {
                return response
            }
            val nextUrl = currentRequest.url.resolve(location) ?: return response
            val isCrossHost = isCrossOriginRedirect(originUrl, nextUrl) ||
                isCrossOriginRedirect(currentRequest.url, nextUrl)

            response.close()

            val nextBuilder = currentRequest.newBuilder().url(nextUrl)
            if (response.code == 303 || ((response.code == 301 || response.code == 302) && currentRequest.method != "GET" && currentRequest.method != "HEAD")) {
                nextBuilder.method("GET", null)
                nextBuilder.removeHeader("Content-Length")
                nextBuilder.removeHeader("Content-Type")
            }
            if (isCrossHost) {
                crossedOrigin = true
                stripSensitiveHeadersFromBuilder(nextBuilder)
            }
            currentRequest = nextBuilder.build()
        }
        throw IOException("重定向次数过多 (>$maxRedirects)")
    }

    /**
     * OkHttp application interceptor for Coil image loading & Media3 ExoPlayer streaming:
     * only injects KodBox Cookie/CSRF headers when the request host matches the KodBox server host,
     * and strips them automatically if a cross-host redirect occurs.
     */
    class OriginScopedAuthInterceptor(
        private val allowedHosts: Set<String>,
        private val cookieHeader: String,
        private val csrfToken: String,
        private val userAgent: String,
        private val extraHeaders: Map<String, String> = emptyMap()
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val reqHost = original.url.host.lowercase(Locale.ROOT)
            val isSameKodHost = allowedHosts.isEmpty() || reqHost in allowedHosts
            val builder = original.newBuilder()
            if (userAgent.isNotBlank() && original.header("User-Agent").isNullOrBlank()) {
                builder.header("User-Agent", userAgent)
            }
            if (isSameKodHost) {
                extraHeaders.forEach { (k, v) -> builder.header(k, v) }
                if (cookieHeader.isNotBlank() && original.header("Cookie").isNullOrBlank()) {
                    builder.header("Cookie", cookieHeader)
                }
                if (csrfToken.isNotBlank() && original.header("CSRF-Token").isNullOrBlank()) {
                    builder.header("CSRF-Token", csrfToken)
                }
            } else {
                stripSensitiveHeadersFromBuilder(builder)
            }
            return chain.proceed(builder.build())
        }
    }
}

/**
 * Cloudflare Tunnel 100 MB safety, configurable chunk sizing, SQLite lock exponential backoff
 * with jitter, and per-site upload concurrency throttling.
 */
object KodUploadResilience {
    const val MIN_CHUNK_MB: Int = 1
    const val DEFAULT_CHUNK_MB: Int = 4
    const val MAX_SAFE_CHUNK_MB: Int = 20 // Strictly below Cloudflare's 100 MB request cap
    const val CLOUDFLARE_HARD_CAP_BYTES: Long = 95L * 1024L * 1024L

    const val MIN_CONCURRENCY: Int = 1
    const val DEFAULT_CONCURRENCY: Int = 2 // Optimal for lightweight SQLite KodBox deployments
    const val MAX_CONCURRENCY: Int = 4

    const val MAX_CHUNK_RETRY_ATTEMPTS: Int = 4

    private data class GateHolder(
        val limit: Int,
        val semaphore: Semaphore
    )

    private val siteGates = ConcurrentHashMap<String, GateHolder>()

    /**
     * Resolves an effective chunk size in bytes honoring user preference, server-advertised limit
     * (if > 0), and strict Cloudflare Tunnel safety bounds (`[1 MB, 20 MB]`).
     */
    fun resolveEffectiveChunkSizeBytes(
        preferredChunkMb: Int = DEFAULT_CHUNK_MB,
        serverAdvertisedChunkBytes: Long = 0L
    ): Long {
        val clampedMb = preferredChunkMb.coerceIn(MIN_CHUNK_MB, MAX_SAFE_CHUNK_MB)
        val userBytes = clampedMb.toLong() * 1024L * 1024L
        val safeMax = min(MAX_SAFE_CHUNK_MB.toLong() * 1024L * 1024L, CLOUDFLARE_HARD_CAP_BYTES)
        val merged = if (serverAdvertisedChunkBytes > 0L) {
            min(userBytes, serverAdvertisedChunkBytes)
        } else {
            userBytes
        }
        return merged.coerceIn(MIN_CHUNK_MB.toLong() * 1024L * 1024L, safeMax)
    }

    /**
     * Detects KodBox SQLite lock contention (`database is locked` / `SQLITE_BUSY` / lock wait).
     */
    fun isSqliteLockContention(message: String?): Boolean {
        val lower = message.orEmpty().lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        return lower.contains("database is locked") ||
            lower.contains("sqlite_busy") ||
            lower.contains("sqlite_locked") ||
            lower.contains("lock wait timeout") ||
            lower.contains("deadlock") ||
            lower.contains("database table is locked") ||
            lower.contains("sqlstate[hy000]: general error: 5") ||
            lower.contains("系统繁忙") ||
            lower.contains("数据库忙")
    }

    /**
     * Classifies whether a chunk upload failure should be retried with exponential backoff + jitter.
     */
    fun isRetryableUploadFailure(
        httpCode: Int? = null,
        errorMessage: String? = null,
        cause: Throwable? = null
    ): Boolean {
        if (KodBoxApiClient.isAuthFailureMessage(errorMessage.orEmpty())) return false
        if (httpCode != null) {
            if (httpCode in setOf(408, 429, 500, 502, 503, 504)) return true
            if (httpCode in 400..499) return false
        }
        if (isSqliteLockContention(errorMessage) || isSqliteLockContention(cause?.message)) return true
        if (cause is SocketTimeoutException || cause is IOException) return true
        val lower = (errorMessage ?: cause?.message).orEmpty().lowercase(Locale.ROOT)
        return lower.contains("timeout") ||
            lower.contains("timed out") ||
            lower.contains("connection reset") ||
            lower.contains("broken pipe") ||
            lower.contains("http 502") ||
            lower.contains("http 503") ||
            lower.contains("http 504") ||
            lower.contains("http 429")
    }

    /**
     * Computes exponential backoff with random jitter (`[0.0, 1.0)`).
     * Uses a slightly longer base delay for SQLite lock contention so PHP workers finish committing.
     */
    fun computeBackoffDelayMs(
        attempt: Int,
        isSqliteLock: Boolean = false,
        jitterUnit: Double = Math.random()
    ): Long {
        val safeAttempt = attempt.coerceIn(0, 6)
        val baseMs = if (isSqliteLock) 900L else 500L
        val expMs = (baseMs * 2.0.pow(safeAttempt.toDouble())).toLong()
        val jitterMs = (jitterUnit.coerceIn(0.0, 0.999) * baseMs).toLong()
        return (expMs + jitterMs).coerceAtMost(12_000L)
    }

    /**
     * Limits concurrent uploads per KodBox host to prevent SQLite lock contention.
     */
    suspend fun <T> withSiteUploadPermit(
        serverUrl: String,
        maxConcurrent: Int = DEFAULT_CONCURRENCY,
        block: suspend () -> T
    ): T {
        val hostKey = runCatching { URI(serverUrl).host?.lowercase(Locale.ROOT) }
            .getOrNull()
            .orEmpty()
            .ifBlank { serverUrl.trim().lowercase(Locale.ROOT) }
        val safeLimit = maxConcurrent.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
        val holder = siteGates.compute(hostKey) { _, existing ->
            if (existing != null && existing.limit == safeLimit) {
                existing
            } else {
                GateHolder(safeLimit, Semaphore(safeLimit))
            }
        }!!
        return holder.semaphore.withPermit { block() }
    }
}

enum class EndpointMode(val label: String) {
    WAN("公网线路"),
    LAN("局域网直连")
}

data class AccountEndpointStatus(
    val activeBaseUrl: String,
    val mode: EndpointMode = EndpointMode.WAN,
    val latencyMs: Long = -1L,
    val lastCheckedAtMs: Long = 0L
)

/**
 * Per-account LAN / WAN dual-address resolver.
 * When Wi-Fi/Ethernet is connected and an account has a configured `lanServerUrl` with `preferLan = true`,
 * runs a fast low-timeout probe and routes traffic via LAN; automatically falls back to WAN when off-LAN.
 */
class KodEndpointResolver {

    private val _routeStatusMap = MutableStateFlow<Map<String, AccountEndpointStatus>>(emptyMap())
    val routeStatusMap: StateFlow<Map<String, AccountEndpointStatus>> = _routeStatusMap.asStateFlow()

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .build()

    fun getEffectiveBaseUrl(
        accountId: String,
        wanServerUrl: String,
        lanServerUrl: String,
        preferLan: Boolean
    ): String {
        val normalizedWan = normalizeBaseUrl(wanServerUrl)
        val normalizedLan = normalizeOptionalLanUrl(lanServerUrl)
        if (!preferLan || normalizedLan.isBlank()) {
            return normalizedWan
        }
        val status = _routeStatusMap.value[accountId]
        return if (status != null && status.mode == EndpointMode.LAN && status.activeBaseUrl == normalizedLan) {
            normalizedLan
        } else {
            normalizedWan
        }
    }

    fun getStatusForAccount(accountId: String, wanServerUrl: String): AccountEndpointStatus {
        return _routeStatusMap.value[accountId]
            ?: AccountEndpointStatus(activeBaseUrl = normalizeBaseUrl(wanServerUrl), mode = EndpointMode.WAN)
    }

    fun clearAccount(accountId: String) {
        _routeStatusMap.update { it - accountId }
    }

    /**
     * Probes the account's LAN URL (only when on Wi-Fi/Ethernet) and updates the live routing table.
     * Never throws; falls back smoothly to WAN if LAN is unreachable.
     */
    fun probeAndSelectEndpoint(
        context: Context?,
        accountId: String,
        wanServerUrl: String,
        lanServerUrl: String,
        preferLan: Boolean
    ): AccountEndpointStatus {
        val normalizedWan = normalizeBaseUrl(wanServerUrl)
        val normalizedLan = normalizeOptionalLanUrl(lanServerUrl)

        if (!preferLan || normalizedLan.isBlank()) {
            val status = AccountEndpointStatus(
                activeBaseUrl = normalizedWan,
                mode = EndpointMode.WAN,
                lastCheckedAtMs = System.currentTimeMillis()
            )
            _routeStatusMap.update { it + (accountId to status) }
            return status
        }

        val onLocalNetwork = context == null || isOnWifiOrEthernet(context)
        if (!onLocalNetwork) {
            val status = AccountEndpointStatus(
                activeBaseUrl = normalizedWan,
                mode = EndpointMode.WAN,
                lastCheckedAtMs = System.currentTimeMillis()
            )
            _routeStatusMap.update { it + (accountId to status) }
            return status
        }

        val startedAt = System.currentTimeMillis()
        val lanReachable = runCatching {
            val req = Request.Builder()
                .url("$normalizedLan/?user/view/options")
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                resp.code in 200..499
            }
        }.getOrDefault(false)
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)

        val status = if (lanReachable) {
            AccountEndpointStatus(
                activeBaseUrl = normalizedLan,
                mode = EndpointMode.LAN,
                latencyMs = elapsed,
                lastCheckedAtMs = System.currentTimeMillis()
            )
        } else {
            AccountEndpointStatus(
                activeBaseUrl = normalizedWan,
                mode = EndpointMode.WAN,
                latencyMs = -1L,
                lastCheckedAtMs = System.currentTimeMillis()
            )
        }
        _routeStatusMap.update { it + (accountId to status) }
        return status
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        /**
         * Normalizes an optional LAN URL (defaults bare LAN IPs like `192.168.1.10:8080` to `http://`).
         */
        fun normalizeOptionalLanUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return if (withScheme.toHttpUrlOrNull() != null) withScheme else ""
        }

        fun isOnWifiOrEthernet(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }
}

enum class ThreeWaySyncAction {
    SKIP,
    UPLOAD_LOCAL,
    DOWNLOAD_REMOTE,
    CONFLICT_KEEP_BOTH
}

data class SyncFileMeta(
    val size: Long,
    val modifiedMs: Long,
    val hashMd5: String = ""
)

data class SyncSnapshotMeta(
    val localSize: Long,
    val localModifiedMs: Long,
    val remoteSize: Long,
    val remoteModifiedMs: Long,
    val remoteHashMd5: String = ""
)

/**
 * Pure, deterministic Synology Drive-style 3-way snapshot reconciliation & conflict resolver.
 */
object SyncConflictResolver {
    const val TIMESTAMP_TOLERANCE_MS: Long = 5_000L

    fun shouldIgnoreEntry(name: String, ignoreHiddenFiles: Boolean): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return true
        if (ignoreHiddenFiles && trimmed.startsWith(".")) return true
        // Always skip common temporary/partial files created by editors or interrupted downloads
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower.endsWith(".tmp") ||
            lower.endsWith(".part") ||
            lower.endsWith(".crdownload") ||
            lower.startsWith("~$")
    }

    /**
     * Generates a readable Synology Drive-style conflict copy filename, e.g.
     * `report.docx` -> `report (冲突副本 20261123-143012).docx`.
     */
    fun buildConflictCopyName(fileName: String, timestampMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMs))
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < fileName.lastIndex) {
            val base = fileName.substring(0, dotIndex)
            val ext = fileName.substring(dotIndex)
            "$base (冲突副本 $stamp)$ext"
        } else {
            "$fileName (冲突副本 $stamp)"
        }
    }

    fun decide(
        direction: com.example.model.SyncDirection,
        conflictPolicy: com.example.model.SyncConflictPolicy,
        local: SyncFileMeta?,
        remote: SyncFileMeta?,
        snapshot: SyncSnapshotMeta?
    ): ThreeWaySyncAction {
        if (local == null && remote == null) return ThreeWaySyncAction.SKIP

        // Only exists locally
        if (local != null && remote == null) {
            return if (direction == com.example.model.SyncDirection.TWO_WAY ||
                direction == com.example.model.SyncDirection.UPLOAD_ONLY
            ) {
                ThreeWaySyncAction.UPLOAD_LOCAL
            } else {
                ThreeWaySyncAction.SKIP
            }
        }

        // Only exists remotely
        if (local == null && remote != null) {
            return if (direction == com.example.model.SyncDirection.TWO_WAY ||
                direction == com.example.model.SyncDirection.DOWNLOAD_ONLY
            ) {
                ThreeWaySyncAction.DOWNLOAD_REMOTE
            } else {
                ThreeWaySyncAction.SKIP
            }
        }

        // Both exist
        val l = local!!
        val r = remote!!
        val sameSize = l.size == r.size
        val withinTolerance = abs(l.modifiedMs - r.modifiedMs) <= TIMESTAMP_TOLERANCE_MS

        if (sameSize && withinTolerance) {
            return ThreeWaySyncAction.SKIP
        }

        // One-way rules apply directional override cleanly
        if (direction == com.example.model.SyncDirection.UPLOAD_ONLY) {
            val localChangedSinceSnap = snapshot == null ||
                l.size != snapshot.localSize ||
                abs(l.modifiedMs - snapshot.localModifiedMs) > TIMESTAMP_TOLERANCE_MS
            return if (localChangedSinceSnap && (l.modifiedMs > r.modifiedMs + TIMESTAMP_TOLERANCE_MS || !sameSize)) {
                ThreeWaySyncAction.UPLOAD_LOCAL
            } else {
                ThreeWaySyncAction.SKIP
            }
        }
        if (direction == com.example.model.SyncDirection.DOWNLOAD_ONLY) {
            val remoteChangedSinceSnap = snapshot == null ||
                r.size != snapshot.remoteSize ||
                abs(r.modifiedMs - snapshot.remoteModifiedMs) > TIMESTAMP_TOLERANCE_MS ||
                (r.hashMd5.isNotBlank() && snapshot.remoteHashMd5.isNotBlank() && !r.hashMd5.equals(snapshot.remoteHashMd5, ignoreCase = true))
            return if (remoteChangedSinceSnap && (r.modifiedMs > l.modifiedMs + TIMESTAMP_TOLERANCE_MS || !sameSize)) {
                ThreeWaySyncAction.DOWNLOAD_REMOTE
            } else {
                ThreeWaySyncAction.SKIP
            }
        }

        // TWO_WAY with 3-way snapshot detection
        if (snapshot != null) {
            val localChanged = l.size != snapshot.localSize ||
                abs(l.modifiedMs - snapshot.localModifiedMs) > TIMESTAMP_TOLERANCE_MS
            val remoteChanged = r.size != snapshot.remoteSize ||
                abs(r.modifiedMs - snapshot.remoteModifiedMs) > TIMESTAMP_TOLERANCE_MS ||
                (r.hashMd5.isNotBlank() && snapshot.remoteHashMd5.isNotBlank() && !r.hashMd5.equals(snapshot.remoteHashMd5, ignoreCase = true))

            if (!localChanged && !remoteChanged) return ThreeWaySyncAction.SKIP
            if (localChanged && !remoteChanged) return ThreeWaySyncAction.UPLOAD_LOCAL
            if (!localChanged && remoteChanged) return ThreeWaySyncAction.DOWNLOAD_REMOTE
            // Both sides modified since last snapshot -> genuine dual-side conflict!
            return resolveConflictPolicy(conflictPolicy, l, r)
        }

        // No prior snapshot (initial sync of pre-existing pair with divergent content)
        return resolveConflictPolicy(conflictPolicy, l, r)
    }

    private fun resolveConflictPolicy(
        policy: com.example.model.SyncConflictPolicy,
        local: SyncFileMeta,
        remote: SyncFileMeta
    ): ThreeWaySyncAction {
        return when (policy) {
            com.example.model.SyncConflictPolicy.KEEP_BOTH_CONFLICT_COPY -> ThreeWaySyncAction.CONFLICT_KEEP_BOTH
            com.example.model.SyncConflictPolicy.SERVER_WINS -> ThreeWaySyncAction.DOWNLOAD_REMOTE
            com.example.model.SyncConflictPolicy.LOCAL_WINS -> ThreeWaySyncAction.UPLOAD_LOCAL
            com.example.model.SyncConflictPolicy.NEWER_WINS -> {
                when {
                    local.modifiedMs > remote.modifiedMs + TIMESTAMP_TOLERANCE_MS -> ThreeWaySyncAction.UPLOAD_LOCAL
                    remote.modifiedMs > local.modifiedMs + TIMESTAMP_TOLERANCE_MS -> ThreeWaySyncAction.DOWNLOAD_REMOTE
                    local.size != remote.size -> ThreeWaySyncAction.CONFLICT_KEEP_BOTH
                    else -> ThreeWaySyncAction.SKIP
                }
            }
        }
    }
}
