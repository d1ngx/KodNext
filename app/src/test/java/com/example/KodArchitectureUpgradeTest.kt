package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.KodAccountStore
import com.example.data.KodBoxApiClient
import com.example.data.local.CachedDirectoryEntity
import com.example.data.local.KodDatabase
import com.example.data.local.SyncRuleEntity
import com.example.data.local.TransferTaskEntity
import com.example.data.security.AccountSecret
import com.example.data.security.KodCredentialVault
import com.example.model.BreadcrumbNode
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodTag
import com.example.model.PathListResult
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.TransferStatus
import com.example.model.TransferTask
import com.example.model.TransferType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KodArchitectureUpgradeTest {

    private lateinit var db: KodDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, KodDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun credentialVault_encryptsAndDecryptsRoundTripWithUniqueIv() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vault = KodCredentialVault(context, keyAlias = "test_key_v1")
        val secret = AccountSecret(
            accessToken = "tok_secret_987654",
            cookiesHeader = "KOD_SESSION_ID=abc123; CSRF_TOKEN=xyz789",
            csrfToken = "xyz789"
        )

        val enc1 = vault.encryptSecret(secret)
        val enc2 = vault.encryptSecret(secret)

        // Random GCM IV ensures identical plaintext produces different ciphertexts
        assertNotEquals(enc1, enc2)
        assertFalse(enc1.contains("tok_secret_987654"))
        assertFalse(enc1.contains("KOD_SESSION_ID"))

        val decrypted = vault.decryptSecret(enc1)
        assertNotNull(decrypted)
        assertEquals(secret, decrypted)
    }

    @Test
    fun accountStore_metadataEncodingOmitsSensitiveCredentials() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vault = KodCredentialVault(context, keyAlias = "test_key_v2")
        val store = KodAccountStore(context, vault)

        val account = KodAccount(
            id = "acc_1",
            serverUrl = "https://demo.box.kodcloud.com",
            username = "hongshan",
            nickName = "Hongshan",
            accessToken = "SUPER_SECRET_TOKEN",
            cookiesHeader = "KOD_SESSION_ID=TOP_SECRET_COOKIE",
            csrfToken = "SECRET_CSRF_999"
        )

        val metadataJson = store.encodeAccountsMetadataOnly(listOf(account))
        assertTrue(metadataJson.contains("hongshan"))
        assertFalse("accessToken must never be serialized to plaintext DataStore", metadataJson.contains("SUPER_SECRET_TOKEN"))
        assertFalse("cookiesHeader must never be serialized to plaintext DataStore", metadataJson.contains("TOP_SECRET_COOKIE"))
        assertFalse("csrfToken must never be serialized to plaintext DataStore", metadataJson.contains("SECRET_CSRF_999"))
    }

    @Test
    fun roomDaos_persistSyncRulesTransfersAndDirectoryCache() = runTest {
        // 1. SyncRuleDao
        val rule = SyncRule(
            id = "rule_1",
            accountId = "acc_1",
            name = "文档双向同步",
            localTreeUri = "content://tree/docs",
            localFolderLabel = "Documents",
            remotePath = "{source:1}/Docs/",
            remoteFolderLabel = "Docs",
            direction = SyncDirection.TWO_WAY,
            frequency = SyncFrequency.HOURLY
        )
        db.syncRuleDao().upsert(SyncRuleEntity.fromModel(rule))
        val loadedRules = db.syncRuleDao().observeByAccount("acc_1").first()
        assertEquals(1, loadedRules.size)
        assertEquals(rule.name, loadedRules.first().toModel().name)

        // 2. TransferTaskDao
        val task = TransferTask(
            id = "task_1",
            fileName = "design.pdf",
            remotePath = "{source:1}/design.pdf",
            localPath = "/tmp/design.pdf",
            type = TransferType.DOWNLOAD,
            progress = 0.25f,
            bytesTransferred = 250L,
            totalBytes = 1000L,
            status = TransferStatus.RUNNING,
            accountId = "acc_1"
        )
        db.transferTaskDao().upsert(TransferTaskEntity.fromModel(task, "work-uuid-1"))
        db.transferTaskDao().updateProgress("task_1", 0.75f, 750L, 1000L)
        val updated = db.transferTaskDao().getById("task_1")?.toModel()
        assertNotNull(updated)
        assertEquals(750L, updated!!.bytesTransferred)
        assertEquals(0.75f, updated.progress, 0.001f)

        // 3. DirectoryCacheDao + DirectoryCacheCodec
        val listing = PathListResult(
            currentPath = "{source:1}/",
            currentTitle = "个人空间",
            items = listOf(
                KodFileItem(
                    path = "{source:1}/hello.md",
                    name = "hello.md",
                    isFolder = false,
                    size = 2048L,
                    tags = listOf(KodTag("1", "重要", "label-red-normal", 0xFFE53935L))
                )
            ),
            tags = listOf(KodTag("1", "重要", "label-red-normal", 0xFFE53935L)),
            breadcrumbs = listOf(BreadcrumbNode("个人空间", "{source:1}/"))
        )
        db.directoryCacheDao().upsert(
            CachedDirectoryEntity.fromPathListResult("acc_1", "{source:1}/", "name_up", listing)
        )
        val cached = db.directoryCacheDao().get("acc_1", "{source:1}/", "name_up")?.toPathListResult()
        assertNotNull(cached)
        assertEquals(1, cached!!.items.size)
        assertEquals("hello.md", cached.items.first().name)
        assertEquals("重要", cached.items.first().tags.first().name)

        // 4. Direct sync transfer tracking + terminal-state progress guard
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val transferRepo = com.example.data.repository.TransferRepository(context, db.transferTaskDao())
        val syncUploadId = transferRepo.recordDirectTransferStart(
            accountId = "acc_1",
            fileName = "docs/report.pdf",
            remotePath = "{source:1}/Docs/",
            type = TransferType.UPLOAD,
            totalBytes = 2048L,
            sourceUri = "content://tree/docs/report.pdf",
            mimeType = "application/pdf"
        )
        transferRepo.updateProgress(syncUploadId, 0.5f, 1024L, 2048L)
        val running = db.transferTaskDao().getById(syncUploadId)?.toModel()
        assertNotNull(running)
        assertEquals(TransferStatus.RUNNING, running!!.status)
        assertEquals(1024L, running.bytesTransferred)

        transferRepo.markTerminalState(
            taskId = syncUploadId,
            status = TransferStatus.COMPLETED,
            progress = 1f,
            bytesTransferred = 2048L,
            localPath = "",
            errorMessage = ""
        )
        // Late throttled progress update must NOT revert COMPLETED back to RUNNING
        transferRepo.updateProgress(syncUploadId, 0.8f, 1600L, 2048L)
        val finalized = db.transferTaskDao().getById(syncUploadId)?.toModel()
        assertNotNull(finalized)
        assertEquals(TransferStatus.COMPLETED, finalized!!.status)
        assertEquals(2048L, finalized.bytesTransferred)
    }

    @Test
    fun apiClient_chunkPlanAndAuthErrorDetection() {
        assertEquals(1L * 1024L * 1024L, KodBoxApiClient.UPLOAD_CHUNK_SIZE_BYTES)

        val smallPlan = KodBoxApiClient.computeChunkPlan(512L * 1024L)
        assertEquals(1, smallPlan.first)

        val exactPlan = KodBoxApiClient.computeChunkPlan(1L * 1024L * 1024L)
        assertEquals(1, exactPlan.first)

        val fourMbPlan = KodBoxApiClient.computeChunkPlan(4L * 1024L * 1024L)
        assertEquals(4, fourMbPlan.first)
        assertEquals(KodBoxApiClient.UPLOAD_CHUNK_SIZE_BYTES, fourMbPlan.second)

        val largePlan = KodBoxApiClient.computeChunkPlan(10L * 1024L * 1024L + 123L)
        assertEquals(11, largePlan.first)
        assertEquals(KodBoxApiClient.UPLOAD_CHUNK_SIZE_BYTES, largePlan.second)

        assertTrue(KodBoxApiClient.isAuthFailure(IllegalStateException("服务器请求错误 (HTTP 401)")))
        assertTrue(KodBoxApiClient.isAuthFailure(IllegalStateException("登录状态已过期，请重新登录")))
        assertFalse(KodBoxApiClient.isAuthFailure(IllegalStateException("SocketTimeoutException: timeout")))
    }

    @Test
    fun apiClient_uploadRoutesStrictValidationAndDirectoryGuards() {
        // 1. Official KodBox v2 routes (preventing explorer/index/fileUpload 200-20 silent failure)
        assertEquals("explorer/upload/fileUpload", KodBoxApiClient.ROUTE_FILE_UPLOAD)
        assertEquals("explorer/upload/serverDownload", KodBoxApiClient.ROUTE_SERVER_DOWNLOAD)
        assertEquals("explorer/editor/fileSave", KodBoxApiClient.ROUTE_EDITOR_FILE_SAVE)

        // 2. Strict JSON response validation never allows false success
        val ok = KodBoxApiClient.requireKodSuccessJson(
            """{"code":true,"data":"上传成功","info":"{source:123}"}""",
            "上传失败"
        )
        assertEquals("{source:123}", ok.optString("info"))

        val invalidBodies = listOf(
            "",
            "   ",
            "action not exist!", // 17-20 byte plain text like the observed HTTP 200 20 log
            "<html><body>404 Not Found</body></html>",
            """{"code":false,"data":"没有权限"}""",
            """{"code":0,"info":"空间不足"}"""
        )
        invalidBodies.forEach { body ->
            val result = runCatching { KodBoxApiClient.requireKodSuccessJson(body, "上传失败") }
            assertTrue("Expected failure for body: '$body'", result.isFailure)
        }

        // 3. Writable directory check blocks virtual views
        assertTrue(KodBoxApiClient.isWritableKodDirectory("{source:17}/"))
        assertTrue(KodBoxApiClient.isWritableKodDirectory("{source:17}/Docs/"))
        assertTrue(KodBoxApiClient.isWritableKodDirectory("/workspace/docs"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory(""))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{block:enterprise}/"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{userFav}/"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{userRecycle}/"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{userFileType:doc}/"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{userFileTag:1}/"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{userRencent}/"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{userShare}/"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{shareToMe}/"))
        assertFalse(KodBoxApiClient.isWritableKodDirectory("{search}/"))

        // 4. Deterministic upload errors fail fast instead of blindly retrying
        assertTrue(KodBoxApiClient.isFatalTransferError(IllegalStateException("上传失败：服务器返回非 JSON 数据 (action not exist!)")))
        assertTrue(KodBoxApiClient.isFatalTransferError(IllegalStateException("上传失败：服务器返回空响应")))
        assertTrue(KodBoxApiClient.isFatalTransferError(IllegalStateException("没有权限")))
        assertTrue(KodBoxApiClient.isFatalTransferError(IllegalStateException("HTTP 413 Request Entity Too Large")))
        assertFalse(KodBoxApiClient.isFatalTransferError(IllegalStateException("SocketTimeoutException: timeout")))
    }

    @Test
    fun apiClient_officialAndroidProtocolAndSiteNameExtraction() {
        // 1. Official Android UA format
        val ua = KodBoxApiClient.buildKodCloudAppUserAgent()
        assertTrue(ua.startsWith("kodCloud-System=Android;AppVersion="))
        assertTrue(ua.contains(";Language="))

        // 2. Official HTTP_X_PLATFORM_1 Base64 JSON payload
        val platformB64 = KodBoxApiClient.buildPlatformHeaderPayload()
        val decodedJson = String(android.util.Base64.decode(platformB64, android.util.Base64.NO_WRAP), Charsets.UTF_8)
        val platformObj = org.json.JSONObject(decodedJson)
        assertTrue(platformObj.optString("appVersion").isNotBlank())
        assertTrue(platformObj.optString("system").startsWith("Android "))
        assertTrue(platformObj.optString("Language").isNotBlank())

        // 3. Site name extraction from options.system.systemName ("FastCube")
        val fastCubeData = org.json.JSONObject(
            """{"options":{"system":{"systemName":"FastCube"}},"kod":{"version":"1.71"}}"""
        )
        assertEquals("FastCube", KodBoxApiClient.extractSiteName(fastCubeData, "demo.box.kodcloud.com"))

        // 4. Authenticated user detection rejects guest payloads and accepts logged-in user
        val guestJson = org.json.JSONObject("""{"code":true,"data":{"options":{"system":{"systemName":"FastCube"}}}}""")
        assertFalse(KodBoxApiClient.hasAuthenticatedUser(guestJson))

        val authedJson = org.json.JSONObject(
            """{"code":true,"data":{"user":{"info":{"userID":"101","name":"hongshan"}}}}"""
        )
        assertTrue(KodBoxApiClient.hasAuthenticatedUser(authedJson))
    }
}
