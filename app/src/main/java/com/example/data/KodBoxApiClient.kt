package com.example.data

import android.content.Context
import android.os.Build
import coil.ImageLoader
import com.example.BuildConfig
import okio.ByteString.Companion.encodeUtf8
import java.util.Locale
import com.example.model.BreadcrumbNode
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodPluginApp
import com.example.model.KodShareItem
import com.example.model.KodTag
import com.example.model.KodWorkspaceApp
import com.example.model.OfficePluginViewer
import com.example.model.PathListResult
import com.example.model.ServerProbeInfo
import com.example.model.ShareAccessMode
import com.example.model.SortField
import com.example.model.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SessionExpiredEvent(
    val accountId: String,
    val serverUrl: String,
    val siteName: String,
    val username: String,
    val reason: String
)

/**
 * Real KodBox HTTP API client communicating directly with self-hosted KodBox instances
 * via standard index.php?<controller>/<action> endpoints, session cookies, CSRF_TOKEN,
 * and accessToken support.
 */
class KodBoxApiClient {

    companion object {
        const val UPLOAD_CHUNK_SIZE_BYTES: Long = 1L * 1024L * 1024L // 1 MB chunks safe for PHP upload_max_filesize=2M & proxies
        const val ROUTE_FILE_UPLOAD: String = "explorer/upload/fileUpload"
        const val ROUTE_SERVER_DOWNLOAD: String = "explorer/upload/serverDownload"
        const val ROUTE_EDITOR_FILE_SAVE: String = "explorer/editor/fileSave"

        /**
         * Computes (totalChunks, chunkSize) for KodBox chunked upload (`chunks` / `chunk` / `chunkSize`).
         */
        fun computeChunkPlan(totalBytes: Long, chunkSize: Long = UPLOAD_CHUNK_SIZE_BYTES): Pair<Int, Long> {
            if (totalBytes <= 0L || totalBytes <= chunkSize) return 1 to totalBytes.coerceAtLeast(0L)
            val totalChunks = ((totalBytes + chunkSize - 1L) / chunkSize).toInt().coerceAtLeast(1)
            return totalChunks to chunkSize
        }

        /**
         * Checks whether a KodBox path is a real writable directory rather than a virtual aggregation view
         * (such as `{block:...}`, `{userFav}`, `{userRecycle}`, `{userFileType:...}`, `{search}`, etc.).
         */
        fun isWritableKodDirectory(path: String): Boolean {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) return false
            val lower = trimmed.lowercase(Locale.ROOT)
            val virtualPrefixes = listOf(
                "{block:",
                "{userfav",
                "{userrecycle",
                "{userfiletype",
                "{userfiletag",
                "{userrencent",
                "{usershare",
                "{sharetome",
                "{search"
            )
            return virtualPrefixes.none { lower.startsWith(it) }
        }

        /**
         * Strictly validates that a KodBox API response is a non-empty JSON object with a truthy `code`.
         * Never silently accepts empty bodies, HTML pages, or plain-text errors (e.g. `action not exist!`).
         */
        fun requireKodSuccessJson(rawBody: String, fallbackError: String): JSONObject {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) {
                error("$fallbackError：服务器返回空响应")
            }
            if (!trimmed.startsWith("{")) {
                val snippet = trimmed.replace(Regex("\\s+"), " ").take(80)
                error("$fallbackError：服务器返回非 JSON 数据 ($snippet)")
            }
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
                ?: error("$fallbackError：服务器 JSON 解析失败")
            if (!isKodSuccessJson(json)) {
                error(extractKodErrorJson(json, fallbackError))
            }
            return json
        }

        internal fun isKodSuccessJson(json: JSONObject): Boolean {
            val code = json.opt("code")
            return when (code) {
                is Boolean -> code
                is Number -> code.toInt() == 1 || code.toInt() == 200
                is String -> code.equals("true", ignoreCase = true) || code == "1" || code == "200"
                else -> false
            }
        }

        internal fun extractKodErrorJson(json: JSONObject, fallback: String): String {
            val dataStr = (json.opt("data") as? String)?.trim()
            if (!dataStr.isNullOrBlank() && !dataStr.equals("false", true) && !dataStr.equals("null", true)) {
                return dataStr
            }
            val infoStr = (json.opt("info") as? String)?.trim()
            if (!infoStr.isNullOrBlank() && !infoStr.equals("false", true) && !infoStr.equals("null", true)) {
                return infoStr
            }
            val msgStr = (json.opt("msg") as? String)?.trim()
            if (!msgStr.isNullOrBlank() && !msgStr.equals("false", true) && !msgStr.equals("null", true)) {
                return msgStr
            }
            return fallback
        }

        /**
         * Detects KodBox JSON auth/session expiration responses (e.g. `code: 10001`, `code: 10002`,
         * or `code: false` with a login-expired message).
         */
        fun isKodAuthErrorJson(json: JSONObject?): Boolean {
            if (json == null || isKodSuccessJson(json)) return false
            val codeStr = json.opt("code")?.toString()?.trim().orEmpty()
            if (codeStr == "10001" || codeStr == "10002" || codeStr == "401") return true
            val detail = extractKodErrorJson(json, "")
            return isAuthFailureMessage(detail)
        }

        /**
         * Detects authentication/session expiration messages across KodBox versions and HTTP layers.
         * Intentionally avoids generic 403 / file-permission errors so read-only folders do not
         * falsely trigger a session-expired dialog.
         */
        fun isAuthFailureMessage(message: String): Boolean {
            val msg = message.trim()
            if (msg.isEmpty()) return false
            val lower = msg.lowercase(Locale.ROOT)
            if (lower.contains("http 401") ||
                lower.contains("unauthorized") ||
                lower.contains("not login") ||
                lower.contains("login required") ||
                lower.contains("please login") ||
                lower.contains("token expired") ||
                lower.contains("token invalid") ||
                lower.contains("invalid token") ||
                lower.contains("code 10001") ||
                lower.contains("10001")
            ) {
                return true
            }
            val mentionsToken = lower.contains("accesstoken") || lower.contains("access_token") || lower.contains("token")
            if (mentionsToken && (lower.contains("invalid") || lower.contains("expired") || msg.contains("失效") || msg.contains("过期") || msg.contains("无效"))) {
                return true
            }
            if (msg.contains("未登录") || msg.contains("请先登录") || msg.contains("请重新登录")) {
                return true
            }
            if (msg.contains("登录") && (msg.contains("过期") || msg.contains("失效") || msg.contains("超时") || msg.contains("异常"))) {
                return true
            }
            if ((msg.contains("凭证") || msg.contains("会话")) && (msg.contains("失效") || msg.contains("过期") || msg.contains("无效") || msg.contains("未生效"))) {
                return true
            }
            return false
        }

        /**
         * Detects authentication/session expiration errors so WorkManager workers fail fast
         * and UI immediately notifies the user to re-authenticate.
         */
        fun isAuthFailure(error: Throwable?): Boolean = isAuthFailureMessage(error?.message.orEmpty())

        /**
         * Detects whether a WebView URL has redirected to the KodBox web login page on the same host.
         */
        fun isWebLoginRedirectUrl(url: String, serverUrl: String): Boolean {
            if (url.isBlank() || serverUrl.isBlank()) return false
            return runCatching {
                val target = URI(url)
                val server = URI(serverUrl)
                val targetHost = target.host?.lowercase(Locale.ROOT).orEmpty()
                val serverHost = server.host?.lowercase(Locale.ROOT).orEmpty()
                if (targetHost.isBlank() || targetHost != serverHost) return false

                val path = target.path.orEmpty().lowercase(Locale.ROOT)
                val query = target.rawQuery.orEmpty().lowercase(Locale.ROOT)
                val fragment = target.fragment.orEmpty().lowercase(Locale.ROOT)

                if (query.contains("plugin/") || path.contains("/plugins/") || path.contains("/static/")) {
                    return false
                }
                if (fragment.startsWith("user/login") ||
                    fragment == "login" ||
                    fragment.startsWith("login/") ||
                    fragment.startsWith("login?")
                ) {
                    return true
                }
                if (path.contains("/user/login")) {
                    return true
                }
                query.startsWith("user/login") ||
                    query.contains("&user/login") ||
                    query.startsWith("user/view/login") ||
                    query.contains("&user/view/login") ||
                    query.startsWith("user/index/login") ||
                    query.contains("&user/index/login")
            }.getOrDefault(false)
        }

        /**
         * Heuristic check for raw HTML bodies returned instead of JSON/files when a KodBox session expires
         * and redirects to the web login page.
         */
        fun looksLikeHtmlLoginPage(rawBody: String): Boolean {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) return false
            val lower = trimmed.lowercase(Locale.ROOT)
            val looksHtml = lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.contains("<form") || lower.contains("<title")
            if (!looksHtml) return false
            return lower.contains("user/login") ||
                lower.contains("loginsubmit") ||
                lower.contains("#user/login") ||
                lower.contains("login-box") ||
                trimmed.contains("请先登录") ||
                trimmed.contains("登录失效")
        }

        /**
         * Detects deterministic transfer errors that will not succeed on blind retry
         * (permissions, quota, invalid route / non-JSON response, HTTP 4xx, unreadable local file).
         */
        fun isFatalTransferError(error: Throwable?): Boolean {
            if (error == null) return false
            if (isAuthFailure(error) || error is SecurityException || error is IllegalArgumentException) return true
            val msg = error.message.orEmpty()
            return msg.contains("无法读取本地文件流") ||
                msg.contains("非 JSON") ||
                msg.contains("返回空响应") ||
                msg.contains("没有权限") ||
                msg.contains("无权") ||
                msg.contains("空间不足") ||
                msg.contains("不允许") ||
                msg.contains("不支持") ||
                msg.contains("HTTP 400") ||
                msg.contains("HTTP 403") ||
                msg.contains("HTTP 404") ||
                msg.contains("HTTP 413") ||
                msg.contains("HTTP 415")
        }

        /**
         * Builds the official KodCloud Android client User-Agent format observed in production server logs:
         * `kodCloud-System=Android;AppVersion=0.04;Language=zh_CN`
         */
        fun buildKodCloudAppUserAgent(): String {
            val appVersion = runCatching { BuildConfig.VERSION_NAME }.getOrNull().orEmpty().ifBlank { "0.04" }
            val locale = Locale.getDefault()
            val lang = if (locale.country.isNotBlank()) "${locale.language}_${locale.country}" else locale.language.ifBlank { "zh_CN" }
            return "kodCloud-System=Android;AppVersion=$appVersion;Language=$lang"
        }

        /**
         * Builds the Base64-encoded JSON device metadata passed as `HTTP_X_PLATFORM_1`
         * matching the official KodBox Android client protocol.
         */
        fun buildPlatformHeaderPayload(): String {
            fun esc(s: String): String = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "")
            val appVersion = runCatching { BuildConfig.VERSION_NAME }.getOrNull().orEmpty().ifBlank { "0.04" }
            val release = runCatching { Build.VERSION.RELEASE }.getOrNull().orEmpty().ifBlank { "14" }
            val sdkInt = runCatching { Build.VERSION.SDK_INT }.getOrNull()?.takeIf { it > 0 }?.toString() ?: "34"
            val brand = runCatching { Build.BRAND }.getOrNull().orEmpty().trim().ifBlank { "Android" }
            val model = runCatching { Build.MODEL }.getOrNull().orEmpty().trim().ifBlank { "AndroidDevice" }
            val locale = Locale.getDefault()
            val lang = if (locale.country.isNotBlank()) "${locale.language}_${locale.country}" else locale.language.ifBlank { "zh_CN" }
            val json = """{"appVersion":"${esc(appVersion)}","system":"Android ${esc(release)}","Language":"${esc(lang)}","brand":"${esc(brand)}","device":"${esc(model)}","systemVersion":"${esc(sdkInt)}"}"""
            return json.encodeUtf8().base64()
        }

        /**
         * Extracts the configured site name from KodBox init/options responses across versions
         * (e.g. `data.options.system.systemName` -> "FastCube", `data.system.settings.systemName`,
         * `data.system.systemName`, `data.options.systemName`, `data.kod.systemName`).
         */
        fun extractSiteName(data: JSONObject, fallbackHost: String = ""): String {
            fun JSONObject?.cleanStr(key: String): String {
                val raw = this?.optString(key).orEmpty().trim()
                return if (raw.isBlank() || raw.equals("null", ignoreCase = true)) "" else raw
            }

            val options = data.optJSONObject("options")
            val optionsSystem = options?.optJSONObject("system")
            val system = data.optJSONObject("system")
            val systemSettings = system?.optJSONObject("settings")
            val kod = data.optJSONObject("kod")
            val systemInfo = data.optJSONObject("systemInfo")

            return optionsSystem.cleanStr("systemName")
                .ifBlank { systemSettings.cleanStr("systemName") }
                .ifBlank { system.cleanStr("systemName") }
                .ifBlank { options.cleanStr("systemName") }
                .ifBlank { kod.cleanStr("systemName") }
                .ifBlank { systemInfo.cleanStr("systemName") }
                .ifBlank { data.cleanStr("systemName") }
                .ifBlank { fallbackHost.trim() }
                .ifBlank { "网盘" }
        }

        /**
         * Checks whether a `user/view/options` or `user/index/init` JSON response contains
         * an authenticated (non-guest) user profile.
         */
        fun hasAuthenticatedUser(json: JSONObject?): Boolean {
            val data = json?.optJSONObject("data") ?: return false
            val userObj = data.optJSONObject("user") ?: return false
            val userInfo = userObj.optJSONObject("info") ?: userObj
            val userId = userInfo.optString("userID").trim()
                .ifBlank { userObj.optString("userID").trim() }
                .ifBlank { userInfo.optString("id").trim() }
            val name = userInfo.optString("name").trim()
                .ifBlank { userObj.optString("name").trim() }
            if (userId == "0" || userId.equals("null", ignoreCase = true)) return false
            return userId.isNotBlank() || (name.isNotBlank() && !name.equals("null", ignoreCase = true))
        }

        /**
         * Builds a standards-compliant mobile browser User-Agent for WebView plugins (OnlyOffice, CAD, EPUB, etc.)
         * and Media3 streams.
         */
        fun buildAndroidUserAgent(): String {
            val androidVersion = runCatching { Build.VERSION.RELEASE }.getOrNull().orEmpty().ifBlank { "14" }
            val manufacturer = runCatching { Build.MANUFACTURER }.getOrNull().orEmpty().trim()
            val model = runCatching { Build.MODEL }.getOrNull().orEmpty().trim()
            val deviceLabel = when {
                model.isBlank() -> "Android Device"
                manufacturer.isNotBlank() && !model.startsWith(manufacturer, ignoreCase = true) -> "$manufacturer $model"
                else -> model
            }.replace(Regex("[\\r\\n();]+"), " ").trim().ifBlank { "Android Device" }
            val appVersion = runCatching { BuildConfig.VERSION_NAME }.getOrNull().orEmpty().ifBlank { "0.04" }
            return "Mozilla/5.0 (Linux; Android $androidVersion; $deviceLabel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 KodNext/$appVersion"
        }
    }

    private val _sessionExpiredEvents = MutableSharedFlow<SessionExpiredEvent>(extraBufferCapacity = 8)
    val sessionExpiredEvents: SharedFlow<SessionExpiredEvent> = _sessionExpiredEvents.asSharedFlow()

    internal fun notifySessionExpired(account: KodAccount, reason: String) {
        val cleanReason = reason.trim().ifBlank { "登录凭证已失效，请重新登录" }
        _sessionExpiredEvents.tryEmit(
            SessionExpiredEvent(
                accountId = account.id,
                serverUrl = account.serverUrl,
                siteName = account.siteName,
                username = account.username,
                reason = cleanReason
            )
        )
    }

    private fun checkAndThrowIfAuthError(
        account: KodAccount,
        httpCode: Int,
        json: JSONObject?,
        rawBody: String = ""
    ) {
        if (httpCode == 401) {
            val reason = "登录凭证已失效，请重新登录 (HTTP 401)"
            notifySessionExpired(account, reason)
            error(reason)
        }
        if (json != null && isKodAuthErrorJson(json)) {
            val detail = extractKodError(json, "登录凭证已失效，请重新登录")
            notifySessionExpired(account, detail)
            error(detail)
        }
        if (json == null && rawBody.isNotBlank() && looksLikeHtmlLoginPage(rawBody)) {
            val reason = "登录状态已失效，服务器已跳转至网页登录页，请重新登录"
            notifySessionExpired(account, reason)
            error(reason)
        }
    }

    val cookieJar = KodSessionCookieJar()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val original = chain.request()
            val updated = if (original.header("User-Agent").isNullOrBlank()) {
                original.newBuilder()
                    .header("User-Agent", buildKodCloudAppUserAgent())
                    .build()
            } else {
                original
            }
            chain.proceed(updated)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun createAuthenticatedImageLoader(context: Context, account: KodAccount?): ImageLoader {
        val imageClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val reqBuilder = original.newBuilder()
                    .header("X-Requested-With", "XMLHttpRequest")
                if (account != null) {
                    if (account.cookiesHeader.isNotBlank() && original.header("Cookie").isNullOrBlank()) {
                        reqBuilder.header("Cookie", account.cookiesHeader)
                    }
                    if (account.csrfToken.isNotBlank()) {
                        reqBuilder.header("CSRF-Token", account.csrfToken)
                    }
                }
                chain.proceed(reqBuilder.build())
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(imageClient)
            .crossfade(true)
            .build()
    }

    fun createAuthenticatedMediaClient(account: KodAccount?): OkHttpClient {
        return okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val reqBuilder = original.newBuilder()
                    .header("User-Agent", buildAndroidUserAgent())
                if (account != null) {
                    if (account.cookiesHeader.isNotBlank() && original.header("Cookie").isNullOrBlank()) {
                        reqBuilder.header("Cookie", account.cookiesHeader)
                    }
                    if (account.csrfToken.isNotBlank()) {
                        reqBuilder.header("CSRF-Token", account.csrfToken)
                    }
                }
                chain.proceed(reqBuilder.build())
            }
            .build()
    }

    fun hydrateAccountSession(account: KodAccount) {
        val url = account.serverUrl.toHttpUrlOrNull() ?: return
        if (account.cookiesHeader.isNotBlank()) {
            cookieJar.hydrateFromHeader(url, account.cookiesHeader)
        }
    }

    private data class InitFetchResult(
        val httpCode: Int,
        val json: JSONObject?,
        val serverError: String? = null
    )

    private data class TokenExchangeResult(
        val accessToken: String = "",
        val sessionEstablished: Boolean = false,
        val account: KodAccount? = null,
        val serverError: String? = null
    )

    private fun fetchOptionsOrInitJson(
        normalizedUrl: String,
        explicitToken: String = "",
        tokenParamName: String = "accessToken",
        requireAuthenticatedUser: Boolean = false
    ): InitFetchResult {
        val platformEncoded = urlEncode(buildPlatformHeaderPayload())
        val routes = listOf("user/view/options", "user/index/init")
        // Official KodCloud Android client uses POST /?<route>&accessToken=<tok>&HTTP_X_PLATFORM_1=<b64>
        val candidates = buildList {
            for (route in routes) {
                if (explicitToken.isNotBlank()) {
                    val encodedTok = urlEncode(explicitToken)
                    add(Triple("$normalizedUrl/?$route&$tokenParamName=$encodedTok&HTTP_X_PLATFORM_1=$platformEncoded", true, explicitToken))
                    add(Triple("$normalizedUrl/?$route&$tokenParamName=$encodedTok&HTTP_X_PLATFORM_1=$platformEncoded", false, explicitToken))
                    add(Triple("$normalizedUrl/index.php?$route&$tokenParamName=$encodedTok&HTTP_X_PLATFORM_1=$platformEncoded", true, explicitToken))
                    add(Triple("$normalizedUrl/index.php?$route&$tokenParamName=$encodedTok&HTTP_X_PLATFORM_1=$platformEncoded", false, explicitToken))
                } else {
                    add(Triple("$normalizedUrl/?$route&HTTP_X_PLATFORM_1=$platformEncoded", false, ""))
                    add(Triple("$normalizedUrl/index.php?$route&HTTP_X_PLATFORM_1=$platformEncoded", false, ""))
                }
            }
        }

        var lastHttpCode = 0
        var fallbackJson: JSONObject? = null
        var fallbackCode = 0
        var lastServerError: String? = null
        for ((endpoint, isPost, tokenParam) in candidates) {
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
            val request = if (isPost) {
                val form = FormBody.Builder()
                if (tokenParam.isNotBlank()) {
                    form.add(tokenParamName, tokenParam)
                }
                reqBuilder.post(form.build()).build()
            } else {
                reqBuilder.get().build()
            }
            var stopEarlyRejectedToken = false
            val result = runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    lastHttpCode = response.code
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val json = parseJsonObject(body)
                        if (json != null) {
                            if (json.optJSONObject("data") != null) {
                                if (fallbackJson == null) {
                                    fallbackJson = json
                                    fallbackCode = response.code
                                }
                                if (!requireAuthenticatedUser || hasAuthenticatedUser(json)) {
                                    return@use InitFetchResult(response.code, json, null)
                                }
                            } else if (!isKodSuccess(json)) {
                                val errText = extractKodError(json, "").trim().ifBlank {
                                    if (isKodAuthErrorJson(json)) "登录凭证已失效 (code ${json.opt("code")})" else ""
                                }
                                if (errText.isNotBlank() && lastServerError == null) {
                                    lastServerError = errText
                                }
                                // KodBox returns code=10001 (or code=false with error message) immediately when
                                // accessToken is rejected by auth middleware; no need to spam 7 duplicate routes
                                if (explicitToken.isNotBlank() && (isKodAuthErrorJson(json) || errText.isNotBlank())) {
                                    stopEarlyRejectedToken = true
                                }
                            }
                        }
                    }
                    null
                }
            }.getOrNull()
            if (result != null) return result
            if (stopEarlyRejectedToken) break
        }
        return if (fallbackJson != null) {
            InitFetchResult(fallbackCode, fallbackJson, lastServerError)
        } else {
            InitFetchResult(lastHttpCode, null, lastServerError)
        }
    }

    suspend fun probeServer(rawUrl: String): Result<ServerProbeInfo> = withContext(Dispatchers.IO) {
        val normalized = KodFormatters.normalizeServerUrl(rawUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        runCatching {
            val initResult = fetchOptionsOrInitJson(normalized, requireAuthenticatedUser = false)
            val httpCode = initResult.httpCode
            val json = initResult.json
            if (httpCode !in 200..299 && json == null) {
                return@runCatching ServerProbeInfo(
                    normalizedUrl = normalized,
                    reachable = false,
                    errorMessage = "服务器返回 HTTP $httpCode"
                )
            }
            if (json == null) {
                return@runCatching ServerProbeInfo(
                    normalizedUrl = normalized,
                    reachable = false,
                    errorMessage = "目标地址响应非网盘 JSON 格式，请确认填写的是网盘根目录"
                )
            }
            val data = json.optJSONObject("data") ?: JSONObject()
            val kod = data.optJSONObject("kod") ?: JSONObject()
            val system = data.optJSONObject("system") ?: JSONObject()
            val settings = system.optJSONObject("settings") ?: JSONObject()
            val options = data.optJSONObject("options") ?: JSONObject()
            val optionsSystem = options.optJSONObject("system") ?: JSONObject()
            val host = normalized.toHttpUrlOrNull()?.host.orEmpty()
            val csrf = cookieJar.getCsrfToken(host)
                .ifBlank { data.optString("CSRF_TOKEN") }
                .ifBlank { options.optString("CSRF_TOKEN") }

            val needCheckCode = settings.optInt("needCheckCode", 0) == 1 ||
                settings.optBoolean("needCheckCode", false) ||
                optionsSystem.optInt("needCheckCode", 0) == 1 ||
                optionsSystem.optBoolean("needCheckCode", false) ||
                options.optInt("needCheckCode", 0) == 1 ||
                options.optBoolean("needCheckCode", false) ||
                data.optInt("needCheckCode", 0) == 1

            val siteName = extractSiteName(data, host)
            val version = kod.optString("version")
                .ifBlank { options.optString("version") }
                .ifBlank { optionsSystem.optString("version") }

            ServerProbeInfo(
                normalizedUrl = normalized,
                reachable = true,
                siteName = siteName,
                version = version,
                needCheckCode = needCheckCode,
                csrfToken = csrf
            )
        }
    }

    suspend fun fetchCaptchaBytes(serverUrl: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val normalized = KodFormatters.normalizeServerUrl(serverUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        runCatching {
            val request = Request.Builder()
                .url("$normalized/index.php?user/view/checkCode&t=${System.currentTimeMillis()}")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("获取验证码失败 (HTTP ${resp.code})")
                resp.body?.bytes() ?: error("验证码内容为空")
            }
        }
    }

    suspend fun loginWithPassword(
        rawServerUrl: String,
        username: String,
        password: String,
        checkCode: String = ""
    ): Result<KodAccount> = withContext(Dispatchers.IO) {
        val normalized = KodFormatters.normalizeServerUrl(rawServerUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        if (username.isBlank() || password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("请输入账号和密码"))
        }
        runCatching {
            val host = normalized.toHttpUrlOrNull()?.host.orEmpty()
            // Step 1: Prime CSRF token & session cookies via init
            val probe = probeServer(normalized).getOrNull()
            val csrf = probe?.csrfToken?.ifBlank { cookieJar.getCsrfToken(host) }.orEmpty()

            // Step 2: Submit credentials to KodBox user/index/loginSubmit
            val formBuilder = FormBody.Builder()
                .add("name", username.trim())
                .add("password", password)
                .add("rememberPassword", "1")
                .add("salt", "0")
                .add("API_ROUTE", "user/index/loginSubmit")
            if (checkCode.isNotBlank()) {
                formBuilder.add("checkCode", checkCode.trim())
            }
            if (csrf.isNotBlank()) {
                formBuilder.add("CSRF_TOKEN", csrf)
            }

            val platformEncoded = urlEncode(buildPlatformHeaderPayload())
            val loginReq = Request.Builder()
                .url("$normalized/?user/index/loginSubmit&HTTP_X_PLATFORM_1=$platformEncoded")
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .apply {
                    if (csrf.isNotBlank()) header("CSRF-Token", csrf)
                }
                .post(formBuilder.build())
                .build()

            var loginToken = ""
            okHttpClient.newCall(loginReq).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("登录请求失败 (HTTP ${resp.code})")
                }
                val json = parseJsonObject(rawBody)
                    ?: error("服务器返回了非预期响应，请确认网盘地址是否正确")
                val codeOk = isKodSuccess(json)
                if (!codeOk) {
                    val errText = extractKodError(json, "登录失败，请检查账号或密码")
                    error(errText)
                }
                loginToken = extractTokenFromJson(json)
            }

            // Step 3: Fetch full workspace & user profile via user/view/options or user/index/init
            val account = fetchInitAccount(
                normalizedUrl = normalized,
                fallbackUsername = username.trim(),
                explicitToken = loginToken
            )
            val probedSite = probe?.siteName.orEmpty().trim()
            if (probedSite.isNotBlank() && probedSite != host && (account.siteName == host || account.siteName == "网盘" || account.siteName == "我的网盘")) {
                account.copy(siteName = probedSite)
            } else {
                account
            }
        }
    }

    /**
     * Official KodBox mobile QR login flow (scanned from Web Account Center `#?loginAppID=...`):
     * 1. `POST /?plugin/client/loginApp&HTTP_X_PLATFORM_1=<b64>` with `loginAppID=<id>` -> returns `accessToken`
     * 2. `POST /?user/view/options&accessToken=<accessToken>&HTTP_X_PLATFORM_1=<b64>` -> hydrates full user session
     */
    suspend fun loginWithAppId(
        rawServerUrl: String,
        loginAppId: String
    ): Result<KodAccount> = withContext(Dispatchers.IO) {
        val normalized = KodFormatters.normalizeServerUrl(rawServerUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        val cleanId = loginAppId.trim()
        if (cleanId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("二维码缺少有效的 loginAppID"))
        }
        runCatching {
            val platformEncoded = urlEncode(buildPlatformHeaderPayload())
            val exchangeAttempts = listOf(
                // Official primary format: POST /?plugin/client/loginApp with loginAppID
                "$normalized/?plugin/client/loginApp&HTTP_X_PLATFORM_1=$platformEncoded" to mapOf(
                    "loginAppID" to cleanId,
                    "API_ROUTE" to "plugin/client/loginApp"
                ),
                // Fallback if server client plugin reads token/id/code param name
                "$normalized/?plugin/client/loginApp&HTTP_X_PLATFORM_1=$platformEncoded" to mapOf(
                    "loginAppID" to cleanId,
                    "token" to cleanId,
                    "id" to cleanId,
                    "code" to cleanId,
                    "loginToken" to cleanId,
                    "API_ROUTE" to "plugin/client/loginApp"
                ),
                // Fallback for servers without URL rewrite rules
                "$normalized/index.php?plugin/client/loginApp&HTTP_X_PLATFORM_1=$platformEncoded" to mapOf(
                    "loginAppID" to cleanId,
                    "token" to cleanId,
                    "id" to cleanId,
                    "API_ROUTE" to "plugin/client/loginApp"
                )
            )

            var exchangedAccessToken = ""
            var sessionEstablished = false
            var directAccount: KodAccount? = null
            var lastServerError: String? = null

            for ((endpoint, formMap) in exchangeAttempts) {
                val formBuilder = FormBody.Builder()
                formMap.forEach { (k, v) -> formBuilder.add(k, v) }
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(formBuilder.build())
                    .build()

                val outcome = runCatching {
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string().orEmpty()
                        val json = parseJsonObject(body) ?: return@use null
                        val tok = extractTokenFromJson(json)
                        if (hasAuthenticatedUser(json)) {
                            val acc = runCatching {
                                buildAccountFromInitJson(
                                    normalizedUrl = normalized,
                                    json = json,
                                    fallbackUsername = "qr_user",
                                    explicitToken = tok
                                )
                            }.getOrNull()
                            if (acc != null) {
                                return@use TokenExchangeResult(accessToken = tok, sessionEstablished = true, account = acc)
                            }
                        }
                        if (tok.isNotBlank() && tok != cleanId) {
                            return@use TokenExchangeResult(accessToken = tok, sessionEstablished = true)
                        }
                        if (isKodSuccess(json)) {
                            val dataVal = json.opt("data")
                            if (dataVal !is Boolean || dataVal) {
                                return@use TokenExchangeResult(accessToken = tok.takeIf { it != cleanId }.orEmpty(), sessionEstablished = true)
                            }
                        } else {
                            val err = extractKodError(json, "").trim()
                            if (err.isNotBlank() && lastServerError == null) {
                                lastServerError = err
                            }
                        }
                        null
                    }
                }.getOrNull()

                if (outcome != null) {
                    directAccount = outcome.account
                    exchangedAccessToken = outcome.accessToken
                    sessionEstablished = outcome.sessionEstablished
                    break
                }
            }

            if (directAccount != null) {
                return@runCatching directAccount
            }
            if (exchangedAccessToken.isNotBlank()) {
                return@runCatching fetchInitAccount(
                    normalizedUrl = normalized,
                    fallbackUsername = "qr_user",
                    explicitToken = exchangedAccessToken
                )
            }
            if (sessionEstablished) {
                return@runCatching fetchInitAccount(
                    normalizedUrl = normalized,
                    fallbackUsername = "qr_user",
                    explicitToken = ""
                )
            }

            error(lastServerError ?: "扫码凭证交换失败或二维码已过期，请刷新网页端二维码重试")
        }
    }

    suspend fun loginWithToken(
        rawServerUrl: String,
        accessToken: String
    ): Result<KodAccount> = withContext(Dispatchers.IO) {
        val normalized = KodFormatters.normalizeServerUrl(rawServerUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        val cleanToken = accessToken.trim()
        if (cleanToken.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("请输入有效的登录凭证"))
        }
        runCatching {
            // 1. Direct official Android accessToken verification (POST /?user/view/options&accessToken=...&HTTP_X_PLATFORM_1=...)
            val directAttempt = runCatching {
                fetchInitAccount(
                    normalizedUrl = normalized,
                    fallbackUsername = "qr_user",
                    explicitToken = cleanToken
                )
            }
            if (directAttempt.isSuccess) {
                return@runCatching directAttempt.getOrThrow()
            }

            // 2. Direct loginToken verification on user/view/options & user/index/init (some KodBox builds accept loginToken directly)
            val loginTokenDirect = runCatching {
                fetchInitAccount(
                    normalizedUrl = normalized,
                    fallbackUsername = "qr_user",
                    explicitToken = cleanToken,
                    tokenParamName = "loginToken"
                )
            }
            if (loginTokenDirect.isSuccess) {
                return@runCatching loginTokenDirect.getOrThrow()
            }

            // 3. Prime CSRF_TOKEN & KOD_SESSION_ID cookie before POSTing to loginSubmit / token-exchange routes
            val host = normalized.toHttpUrlOrNull()?.host.orEmpty()
            val primedCsrf = cookieJar.getCsrfToken(host).ifBlank {
                probeServer(normalized).getOrNull()?.csrfToken.orEmpty()
            }

            // 4. Exchange short-lived loginToken / QR code token for session cookie or accessToken
            val exchange = exchangeLoginTokenForAccessToken(normalized, cleanToken, primedCsrf)
            if (exchange.account != null) {
                return@runCatching exchange.account
            }
            if (exchange.accessToken.isNotBlank()) {
                return@runCatching fetchInitAccount(
                    normalizedUrl = normalized,
                    fallbackUsername = "qr_user",
                    explicitToken = exchange.accessToken
                )
            }
            if (exchange.sessionEstablished) {
                // CRITICAL: Use the newly established KOD_SESSION_ID cookie (explicitToken = "")
                // so KodBox's accessToken middleware does NOT override the valid session with the old loginToken!
                return@runCatching fetchInitAccount(
                    normalizedUrl = normalized,
                    fallbackUsername = "qr_user",
                    explicitToken = ""
                )
            }

            val bestError = exchange.serverError
                ?: directAttempt.exceptionOrNull()?.message
                ?: loginTokenDirect.exceptionOrNull()?.message
                ?: "二维码凭证无效或已过期"
            error(bestError)
        }
    }

    /**
     * Handles dynamic QR login callback URLs (e.g. `?user/index/qrcode&...`, `?user/view/call&...`,
     * short links, or signed routes) by trying GET and POST with official Android client headers,
     * harvesting tokens from redirects, JSON, HTML/JS scripts, or session cookies, and hydrating the account.
     */
    suspend fun loginWithQrUrl(
        rawServerUrl: String,
        qrUrl: String
    ): Result<KodAccount> = withContext(Dispatchers.IO) {
        val normalized = KodFormatters.normalizeServerUrl(rawServerUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        runCatching {
            val platformEncoded = urlEncode(buildPlatformHeaderPayload())
            val targetUrl = appendQueryParamBeforeFragment(qrUrl, "HTTP_X_PLATFORM_1", platformEncoded)

            var extractedToken = ""
            var serverHintError: String? = null
            // Try: (1) AJAX GET, (2) Browser-style GET (allows 302 redirects / session cookies), (3) AJAX POST
            val attempts = listOf(
                Triple(false, true, targetUrl),
                Triple(false, false, qrUrl),
                Triple(true, true, targetUrl)
            )

            for ((usePost, withAjaxHeader, reqUrl) in attempts) {
                val reqBuilder = Request.Builder()
                    .url(reqUrl)
                    .header("Accept", "application/json, text/html, */*")
                if (withAjaxHeader) {
                    reqBuilder.header("X-Requested-With", "XMLHttpRequest")
                }
                val req = if (usePost) {
                    reqBuilder.post(FormBody.Builder().build()).build()
                } else {
                    reqBuilder.get().build()
                }

                val accountFound = runCatching {
                    okHttpClient.newCall(req).execute().use { resp ->
                        val finalUrlStr = resp.request.url.toString()
                        val parsedFinal = KodQrLoginParser.parse(finalUrlStr)
                        if (parsedFinal is KodQrPayload.TokenLogin) {
                            extractedToken = parsedFinal.accessToken
                        }

                        val body = resp.body?.string().orEmpty()
                        val json = parseJsonObject(body)
                        if (json != null) {
                            extractedToken = extractTokenFromJson(json).ifBlank { extractedToken }
                            if (hasAuthenticatedUser(json)) {
                                return@use buildAccountFromInitJson(
                                    normalizedUrl = normalized,
                                    json = json,
                                    fallbackUsername = "qr_user",
                                    explicitToken = extractedToken
                                )
                            }
                            if (!isKodSuccess(json)) {
                                val msg = extractKodError(json, "").trim()
                                if (msg.isNotBlank() && serverHintError == null) serverHintError = msg
                            }
                        } else if (body.isNotBlank()) {
                            extractedToken = extractTokenFromHtmlOrText(body).ifBlank { extractedToken }
                        }
                        null
                    }
                }.getOrNull()

                if (accountFound != null) return@runCatching accountFound
                if (extractedToken.isNotBlank()) break
            }

            if (extractedToken.isNotBlank()) {
                return@runCatching loginWithToken(normalized, extractedToken).getOrThrow()
            }

            // Fallback 1 (BEFORE candidate token probing): Session cookie may have ALREADY been set by visiting qrUrl
            val cookieAttempt = runCatching {
                fetchInitAccount(
                    normalizedUrl = normalized,
                    fallbackUsername = "qr_user",
                    explicitToken = ""
                )
            }
            if (cookieAttempt.isSuccess) {
                return@runCatching cookieAttempt.getOrThrow()
            }

            // Fallback 2: If QR URL carried candidate exchange tokens/codes (e.g. code=, key=, sign=, safeToken=), try exchanging them
            var lastCandidateError: String? = null
            val candidateTokens = KodQrLoginParser.extractCandidateExchangeTokens(qrUrl)
            for (candidate in candidateTokens) {
                val attempt = loginWithToken(normalized, candidate)
                if (attempt.isSuccess) {
                    return@runCatching attempt.getOrThrow()
                }
                val errMsg = attempt.exceptionOrNull()?.message?.trim()
                if (!errMsg.isNullOrBlank() && lastCandidateError == null) {
                    lastCandidateError = errMsg
                }
            }

            error(serverHintError ?: lastCandidateError ?: "该二维码未返回有效登录凭证或已过期")
        }
    }

    private fun appendQueryParamBeforeFragment(rawUrl: String, key: String, encodedValue: String): String {
        if (rawUrl.contains("$key=", ignoreCase = true)) return rawUrl
        val hashIdx = rawUrl.indexOf('#')
        val beforeHash = if (hashIdx >= 0) rawUrl.substring(0, hashIdx) else rawUrl
        val hashPart = if (hashIdx >= 0) rawUrl.substring(hashIdx) else ""
        val sep = if (beforeHash.contains("?")) "&" else "?"
        return "$beforeHash$sep$key=$encodedValue$hashPart"
    }

    /**
     * Authorizes a PC Web QR login session when an already signed-in mobile user scans
     * a same-server web QR code from the Me tab.
     */
    suspend fun confirmWebQrLogin(
        account: KodAccount,
        qrUrl: String
    ): Result<String> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val platformEncoded = urlEncode(buildPlatformHeaderPayload())
            val sep = if (qrUrl.contains("?")) "&" else "?"
            val withToken = buildString {
                append(qrUrl)
                if (account.accessToken.isNotBlank() && !qrUrl.contains("accessToken=", ignoreCase = true)) {
                    append(sep).append("accessToken=").append(urlEncode(account.accessToken))
                }
                if (!qrUrl.contains("HTTP_X_PLATFORM_1", ignoreCase = true)) {
                    append(if (contains("?")) "&" else "?").append("HTTP_X_PLATFORM_1=").append(platformEncoded)
                }
            }
            val req = Request.Builder()
                .url(withToken)
                .header("Accept", "application/json, text/html, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .apply {
                    if (account.csrfToken.isNotBlank()) header("CSRF-Token", account.csrfToken)
                }
                .post(
                    FormBody.Builder().apply {
                        if (account.accessToken.isNotBlank()) add("accessToken", account.accessToken)
                        if (account.csrfToken.isNotBlank()) add("CSRF_TOKEN", account.csrfToken)
                    }.build()
                )
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("确认授权失败 (HTTP ${resp.code})")
                val body = resp.body?.string().orEmpty()
                val json = parseJsonObject(body)
                if (json != null && isKodSuccess(json)) {
                    json.optString("info").takeIf { it.isNotBlank() && !it.equals("null", true) }
                        ?: "已确认网页端扫码授权"
                } else {
                    error(json?.let { extractKodError(it, "未能确认网页端授权") } ?: "该二维码不是网页端授权码")
                }
            }
        }
    }

    private fun extractTokenFromHtmlOrText(body: String): String {
        val patterns = listOf(
            Regex("""["']accessToken["']\s*[:=]\s*["']([A-Za-z0-9_\-+=/.]{16,})["']"""),
            Regex("""["']loginToken["']\s*[:=]\s*["']([A-Za-z0-9_\-+=/.]{16,})["']"""),
            Regex("""["']safeToken["']\s*[:=]\s*["']([A-Za-z0-9_\-+=/.]{16,})["']"""),
            Regex("""["']kodToken["']\s*[:=]\s*["']([A-Za-z0-9_\-+=/.]{16,})["']"""),
            Regex("""accessToken=([A-Za-z0-9_\-+%.]{16,})"""),
            Regex("""loginToken=([A-Za-z0-9_\-+%.]{16,})"""),
            Regex("""safeToken=([A-Za-z0-9_\-+%.]{16,})""")
        )
        for (regex in patterns) {
            val match = regex.find(body)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank()) {
                return KodQrLoginParser.percentDecodePreservePlus(match)
            }
        }
        return ""
    }

    private fun exchangeLoginTokenForAccessToken(
        normalizedUrl: String,
        rawToken: String,
        primedCsrf: String = ""
    ): TokenExchangeResult {
        val platformEncoded = urlEncode(buildPlatformHeaderPayload())
        val host = normalizedUrl.toHttpUrlOrNull()?.host.orEmpty()
        val csrf = primedCsrf.ifBlank { cookieJar.getCsrfToken(host) }
        val encodedTok = urlEncode(rawToken)

        val exchangeAttempts = listOf(
            // 0. Official KodBox client plugin QR exchange (`POST /?plugin/client/loginApp`)
            Triple(
                "$normalizedUrl/?plugin/client/loginApp&HTTP_X_PLATFORM_1=$platformEncoded",
                true,
                mapOf(
                    "loginAppID" to rawToken,
                    "API_ROUTE" to "plugin/client/loginApp"
                )
            ),
            Triple(
                "$normalizedUrl/?plugin/client/loginApp&HTTP_X_PLATFORM_1=$platformEncoded",
                true,
                mapOf(
                    "loginAppID" to rawToken,
                    "token" to rawToken,
                    "id" to rawToken,
                    "code" to rawToken,
                    "API_ROUTE" to "plugin/client/loginApp"
                )
            ),
            // 1. KodBox standard user/index/loginSubmit with loginToken (POST + CSRF)
            Triple(
                "$normalizedUrl/?user/index/loginSubmit&HTTP_X_PLATFORM_1=$platformEncoded",
                true,
                mapOf(
                    "loginToken" to rawToken,
                    "isApp" to "1",
                    "rememberPassword" to "1",
                    "salt" to "0",
                    "API_ROUTE" to "user/index/loginSubmit"
                )
            ),
            // 2. KodBox user/index/loginSubmit with loginToken via GET
            Triple(
                "$normalizedUrl/?user/index/loginSubmit&loginToken=$encodedTok&isApp=1&HTTP_X_PLATFORM_1=$platformEncoded",
                false,
                emptyMap()
            ),
            // 3. KodBox user/index/loginSubmit with token/safeToken (POST)
            Triple(
                "$normalizedUrl/?user/index/loginSubmit&HTTP_X_PLATFORM_1=$platformEncoded",
                true,
                mapOf(
                    "token" to rawToken,
                    "safeToken" to rawToken,
                    "isApp" to "1",
                    "rememberPassword" to "1",
                    "API_ROUTE" to "user/index/loginSubmit"
                )
            ),
            // 4. KodBox user/index/loginWithToken (POST & GET)
            Triple(
                "$normalizedUrl/?user/index/loginWithToken&HTTP_X_PLATFORM_1=$platformEncoded",
                true,
                mapOf("loginToken" to rawToken, "token" to rawToken, "isApp" to "1")
            ),
            Triple(
                "$normalizedUrl/?user/index/loginWithToken&loginToken=$encodedTok&token=$encodedTok&isApp=1&HTTP_X_PLATFORM_1=$platformEncoded",
                false,
                emptyMap()
            ),
            // 5. KodBox official SSO API login endpoint (user/sso/apiLogin, GET & POST)
            Triple(
                "$normalizedUrl/?user/sso/apiLogin&token=$encodedTok&loginToken=$encodedTok&accessToken=$encodedTok&HTTP_X_PLATFORM_1=$platformEncoded",
                false,
                emptyMap()
            ),
            Triple(
                "$normalizedUrl/?user/sso/apiLogin&HTTP_X_PLATFORM_1=$platformEncoded",
                true,
                mapOf("token" to rawToken, "loginToken" to rawToken, "accessToken" to rawToken)
            ),
            // 6. KodBox accessToken / appLogin endpoints
            Triple(
                "$normalizedUrl/?user/index/accessToken&HTTP_X_PLATFORM_1=$platformEncoded",
                true,
                mapOf("loginToken" to rawToken, "token" to rawToken, "safeToken" to rawToken, "code" to rawToken)
            ),
            Triple(
                "$normalizedUrl/?user/index/appLogin&HTTP_X_PLATFORM_1=$platformEncoded",
                true,
                mapOf("token" to rawToken, "loginToken" to rawToken, "code" to rawToken, "isApp" to "1")
            ),
            Triple(
                "$normalizedUrl/?user/view/call&loginToken=$encodedTok&token=$encodedTok&HTTP_X_PLATFORM_1=$platformEncoded",
                false,
                emptyMap()
            )
        )

        var lastMeaningfulError: String? = null
        for ((endpoint, isPost, formMap) in exchangeAttempts) {
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .apply { if (csrf.isNotBlank()) header("CSRF-Token", csrf) }

            val req = if (isPost) {
                val formBuilder = FormBody.Builder()
                formMap.forEach { (k, v) -> formBuilder.add(k, v) }
                if (csrf.isNotBlank()) formBuilder.add("CSRF_TOKEN", csrf)
                reqBuilder.post(formBuilder.build()).build()
            } else {
                reqBuilder.get().build()
            }

            val result = runCatching {
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    val json = parseJsonObject(body)
                    if (json == null) {
                        val htmlTok = extractTokenFromHtmlOrText(body)
                        return@use if (htmlTok.isNotBlank() && htmlTok != rawToken) {
                            TokenExchangeResult(accessToken = htmlTok, sessionEstablished = true)
                        } else {
                            null
                        }
                    }

                    val tok = extractTokenFromJson(json)
                    if (hasAuthenticatedUser(json)) {
                        val acc = runCatching {
                            buildAccountFromInitJson(
                                normalizedUrl = normalizedUrl,
                                json = json,
                                fallbackUsername = "qr_user",
                                explicitToken = tok
                            )
                        }.getOrNull()
                        if (acc != null) {
                            return@use TokenExchangeResult(accessToken = tok, sessionEstablished = true, account = acc)
                        }
                    }

                    if (tok.isNotBlank() && tok != rawToken) {
                        return@use TokenExchangeResult(accessToken = tok, sessionEstablished = true)
                    }

                    if (isKodSuccess(json)) {
                        // e.g. user/index/loginSubmit returned {"code":true,"data":"登录成功"} with Set-Cookie
                        val dataVal = json.opt("data")
                        val isRealLoginAck = dataVal !is Boolean || dataVal
                        if (isRealLoginAck) {
                            return@use TokenExchangeResult(accessToken = tok.takeIf { it != rawToken }.orEmpty(), sessionEstablished = true)
                        }
                    } else {
                        val err = extractKodError(json, "").trim()
                        if (err.isNotBlank() &&
                            !err.contains("not exist", ignoreCase = true) &&
                            !err.contains("不存在", ignoreCase = true) &&
                            lastMeaningfulError == null
                        ) {
                            lastMeaningfulError = err
                        }
                    }
                    null
                }
            }.getOrNull()

            if (result != null) return result
        }
        return TokenExchangeResult(serverError = lastMeaningfulError)
    }

    private fun extractTokenFromJson(json: JSONObject): String {
        val dataObj = json.optJSONObject("data")
        val fromObj = dataObj?.optString("accessToken").orEmpty()
            .ifBlank { dataObj?.optString("access_token").orEmpty() }
            .ifBlank { dataObj?.optString("loginToken").orEmpty() }
            .ifBlank { dataObj?.optString("login_token").orEmpty() }
            .ifBlank { dataObj?.optString("safeToken").orEmpty() }
            .ifBlank { dataObj?.optString("safe_token").orEmpty() }
            .ifBlank { dataObj?.optString("kodToken").orEmpty() }
            .ifBlank { dataObj?.optString("userToken").orEmpty() }
            .ifBlank { dataObj?.optString("authToken").orEmpty() }
            .ifBlank { dataObj?.optString("token").orEmpty() }
            .ifBlank { dataObj?.optJSONObject("kod")?.optString("accessToken").orEmpty() }
            .ifBlank { dataObj?.optJSONObject("user")?.optString("accessToken").orEmpty() }
            .ifBlank { dataObj?.optJSONObject("options")?.optString("accessToken").orEmpty() }
            .ifBlank { json.optString("accessToken") }
            .ifBlank { json.optString("access_token") }
            .ifBlank { json.optString("safeToken") }
            .ifBlank { json.optString("token") }
        if (fromObj.isNotBlank() && !fromObj.equals("null", true)) return fromObj

        // Check nested redirect URL inside data.url or data.link
        val nestedUrl = dataObj?.optString("url").orEmpty().ifBlank { dataObj?.optString("link").orEmpty() }
        if (nestedUrl.startsWith("http://", true) || nestedUrl.startsWith("https://", true)) {
            val parsed = KodQrLoginParser.parse(nestedUrl)
            if (parsed is KodQrPayload.TokenLogin) return parsed.accessToken
        }

        // Some endpoints return the token directly as string in `data` or `info` when `code == true`
        if (isKodSuccess(json)) {
            val dataStr = json.optString("data").trim()
            if (dataStr.length >= 16 && !dataStr.startsWith("{") && !dataStr.startsWith("[") && !dataStr.contains(" ")) {
                return dataStr
            }
            val infoStr = json.optString("info").trim()
            if (infoStr.length >= 16 && !infoStr.startsWith("{") && !infoStr.contains(" ")) {
                return infoStr
            }
        }
        return ""
    }

    suspend fun refreshAccountInfo(account: KodAccount): Result<KodAccount> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val initResult = fetchOptionsOrInitJson(
                normalizedUrl = account.serverUrl,
                explicitToken = account.accessToken,
                tokenParamName = "accessToken",
                requireAuthenticatedUser = true
            )
            if (initResult.httpCode == 401) {
                val reason = "当前账号的登录凭证已失效，请重新登录 (HTTP 401)"
                notifySessionExpired(account, reason)
                error(reason)
            }
            val json = initResult.json
            if (json == null) {
                val errDetail = initResult.serverError?.takeIf { it.isNotBlank() }
                if (errDetail != null && isAuthFailureMessage(errDetail)) {
                    notifySessionExpired(account, errDetail)
                    error(errDetail)
                }
                error(
                    when {
                        errDetail != null -> errDetail
                        initResult.httpCode in 200..299 -> "无法解析网盘初始化响应"
                        initResult.httpCode > 0 -> "获取用户信息失败 (HTTP ${initResult.httpCode})"
                        else -> "网络连接失败，无法连接服务器"
                    }
                )
            }
            if (!hasAuthenticatedUser(json)) {
                val detail = extractKodError(json, "").takeIf { it.isNotBlank() && isAuthFailureMessage(it) }
                    ?: "当前账号的登录凭证已失效或已在其他端退出，请重新登录"
                notifySessionExpired(account, detail)
                error(detail)
            }
            buildAccountFromInitJson(
                normalizedUrl = account.serverUrl,
                json = json,
                fallbackUsername = account.username,
                explicitToken = account.accessToken,
                existingId = account.id
            )
        }
    }

    private fun fetchInitAccount(
        normalizedUrl: String,
        fallbackUsername: String,
        explicitToken: String,
        tokenParamName: String = "accessToken",
        existingId: String? = null
    ): KodAccount {
        val initResult = fetchOptionsOrInitJson(
            normalizedUrl = normalizedUrl,
            explicitToken = explicitToken,
            tokenParamName = tokenParamName,
            requireAuthenticatedUser = true
        )
        val json = initResult.json
        if (json == null) {
            val errDetail = initResult.serverError?.takeIf { it.isNotBlank() }
            error(
                when {
                    errDetail != null -> errDetail
                    initResult.httpCode in 200..299 -> "无法解析网盘初始化响应"
                    else -> "获取用户信息失败 (HTTP ${initResult.httpCode})"
                }
            )
        }
        return buildAccountFromInitJson(
            normalizedUrl = normalizedUrl,
            json = json,
            fallbackUsername = fallbackUsername,
            explicitToken = if (tokenParamName == "accessToken") explicitToken else "",
            existingId = existingId
        )
    }

    private fun buildAccountFromInitJson(
        normalizedUrl: String,
        json: JSONObject,
        fallbackUsername: String,
        explicitToken: String,
        existingId: String? = null
    ): KodAccount {
        val data = json.optJSONObject("data") ?: error(extractKodError(json, "未能读取用户信息"))
        val userObj = data.optJSONObject("user")
        // KodBox 1.71 nests profile under data.user.info while older builds put fields directly on data.user
        val userInfo = userObj?.optJSONObject("info") ?: userObj
        val userId = userInfo?.optString("userID").orEmpty()
            .ifBlank { userObj?.optString("userID").orEmpty() }
            .ifBlank { userInfo?.optString("id").orEmpty() }
        val name = userInfo?.optString("name").orEmpty()
            .ifBlank { userObj?.optString("name").orEmpty() }

        if (userInfo == null || (userId.isBlank() && name.isBlank()) || userId == "0") {
            error("会话未生效或二维码凭证已失效，请重新获取二维码或使用账号密码登录")
        }

        val host = normalizedUrl.toHttpUrlOrNull()?.host.orEmpty()
        val cookiesHeader = cookieJar.exportHeaderForHost(host)
        val csrf = cookieJar.getCsrfToken(host)
            .ifBlank { data.optString("CSRF_TOKEN") }

        val resolvedName = name.ifBlank { fallbackUsername }
        val nickName = userInfo.optString("nickName").ifBlank { resolvedName }
        val avatarRaw = userInfo.optString("avatar").trim()
        val avatarUrl = when {
            avatarRaw.isBlank() -> ""
            avatarRaw.startsWith("http://") || avatarRaw.startsWith("https://") -> avatarRaw
            avatarRaw.startsWith("./") -> "$normalizedUrl/${avatarRaw.removePrefix("./")}"
            else -> "$normalizedUrl/${avatarRaw.trimStart('/')}"
        }

        val kodInfo = data.optJSONObject("kod")
        val optionsObj = data.optJSONObject("options")
        val optionsKod = optionsObj?.optJSONObject("kod")
        val token = explicitToken.ifBlank {
            kodInfo?.optString("accessToken").orEmpty()
                .ifBlank { optionsKod?.optString("accessToken").orEmpty() }
                .ifBlank { optionsObj?.optString("accessToken").orEmpty() }
                .ifBlank { data.optString("accessToken") }
                .ifBlank { userInfo.optString("accessToken") }
        }

        val myhome = userObj?.optString("myhome").orEmpty()
        val sourceInfo = userInfo.optJSONObject("sourceInfo")
        val personalSourceId = sourceInfo?.optString("sourceID").orEmpty()
            .ifBlank { userInfo.optString("sourceID") }
        val personalRootPath = when {
            myhome.isNotBlank() -> myhome
            personalSourceId.isNotBlank() -> "{source:$personalSourceId}/"
            else -> "{block:files}/"
        }

        val options = data.optJSONObject("options")
        val siteName = extractSiteName(data, host)
        val version = kodInfo?.optString("version").orEmpty()
            .ifBlank { options?.optString("version").orEmpty() }
            .ifBlank { options?.optJSONObject("system")?.optString("version").orEmpty() }

        val sizeUse = userInfo.optLongSafe("sizeUse")
        // KodBox stores sizeMax in GB (e.g., 20 = 20GB) or bytes depending on endpoint
        val rawSizeMax = userInfo.optDoubleSafe("sizeMax")
        val sizeMaxBytes = when {
            rawSizeMax <= 0.0 -> 0L
            rawSizeMax < 100_000.0 -> (rawSizeMax * 1024.0 * 1024.0 * 1024.0).toLong()
            else -> rawSizeMax.toLong()
        }

        val roleId = userInfo.optString("roleID")
        val isAdmin = (userObj?.optInt("isRoot", 0) == 1) ||
            (userObj?.optBoolean("isRoot", false) == true) ||
            (userInfo.optInt("isRoot", 0) == 1)
        val email = userInfo.optString("email").trim()
        val phone = userInfo.optString("phone").trim()
        val sex = userInfo.optString("sex").ifBlank { "1" }
        val departments = buildList {
            val groupInfo = userInfo.optJSONArray("groupInfo")
            if (groupInfo != null) {
                for (i in 0 until groupInfo.length()) {
                    val gObj = groupInfo.optJSONObject(i) ?: continue
                    val gName = gObj.optString("name").trim()
                    if (gName.isNotBlank()) add(gName)
                }
            }
        }

        return KodAccount(
            id = existingId ?: "${host}_$resolvedName",
            serverUrl = normalizedUrl,
            username = resolvedName,
            nickName = nickName,
            avatarUrl = avatarUrl,
            email = email,
            phone = phone,
            sex = sex,
            roleId = roleId,
            isAdmin = isAdmin,
            departments = departments,
            accessToken = token,
            cookiesHeader = cookiesHeader,
            csrfToken = csrf,
            userId = userId,
            sizeUse = sizeUse,
            sizeMax = sizeMaxBytes,
            serverVersion = version,
            siteName = siteName,
            personalRootPath = personalRootPath,
            groupRootPath = "{block:enterprise}/",
            lastLoginAt = System.currentTimeMillis()
        )
    }

    suspend fun listPath(
        account: KodAccount,
        path: String,
        sortField: SortField = SortField.NAME,
        sortOrder: SortOrder = SortOrder.ASC,
        page: Int = 1,
        pageNum: Int = 200,
        extraParams: Map<String, String> = emptyMap()
    ): Result<PathListResult> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val rawTarget = path.ifBlank { account.personalRootPath }
            val isEnterpriseFilter = rawTarget == "{block:enterprise}/" || rawTarget == "{block:enterprise}"
            val apiPath = when {
                isEnterpriseFilter -> "{block:files}/"
                rawTarget.startsWith("{") && rawTarget.endsWith("}") -> "$rawTarget/"
                else -> rawTarget
            }
            val params = mutableMapOf(
                "path" to apiPath,
                "page" to page.toString(),
                "pageNum" to pageNum.toString(),
                "sortField" to sortField.apiKey,
                "sortType" to sortOrder.apiKey
            )
            params.putAll(extraParams)
            val json = executeKodGet(account, "explorer/list/path", params)
            if (!isKodSuccess(json)) {
                error(extractKodError(json, "加载目录失败"))
            }
            val data = json.optJSONObject("data") ?: JSONObject()
            val items = mutableListOf<KodFileItem>()
            val isRecycle = apiPath.contains("userRecycle", ignoreCase = true)

            // Parse group/space folders if present (e.g., inside {block:files}/)
            parseFileArray(data.optJSONArray("groupList"), isFolderDefault = true, isRecycle = isRecycle, into = items)
            parseFileArray(data.optJSONArray("folderList"), isFolderDefault = true, isRecycle = isRecycle, into = items)
            parseFileArray(data.optJSONArray("fileList"), isFolderDefault = false, isRecycle = isRecycle, into = items)

            // Some virtual paths like {block:fileType}/ return "list" or "folderList"
            if (items.isEmpty()) {
                parseFileArray(data.optJSONArray("list"), isFolderDefault = false, isRecycle = isRecycle, into = items)
            }

            // Keep Enterprise space strictly parallel to Personal, Favorites, and Shared With Me
            val finalItems = if (isEnterpriseFilter) {
                val personalNorm = account.personalRootPath.trimEnd('/')
                items.filterNot { item ->
                    val pNorm = item.path.trimEnd('/')
                    pNorm == personalNorm ||
                        pNorm.startsWith("{userFav", ignoreCase = true) ||
                        pNorm.startsWith("{shareToMe", ignoreCase = true) ||
                        pNorm.startsWith("{userShare", ignoreCase = true)
                }
            } else {
                items
            }

            // Parse KodBox tags if returned
            val tags = parseTagList(data.optJSONArray("tagList"))

            // Parse current path info & breadcrumb trail
            val currentObj = data.optJSONObject("current")
            val resolvedPath = if (isEnterpriseFilter) {
                "{block:enterprise}/"
            } else {
                data.optString("thisPath").ifBlank {
                    currentObj?.optString("path").orEmpty().ifBlank { apiPath }
                }
            }
            val resolvedTitle = if (isEnterpriseFilter) {
                "企业网盘"
            } else {
                currentObj?.optString("name").orEmpty()
                    .ifBlank { data.optString("currentTitle") }
            }

            val breadcrumbs = buildList {
                val pathTree = data.optJSONArray("pathDesc") ?: data.optJSONArray("parentList")
                if (pathTree != null) {
                    for (i in 0 until pathTree.length()) {
                        val node = pathTree.optJSONObject(i) ?: continue
                        val label = node.optString("name").ifBlank { node.optString("title") }
                        val nodePath = node.optString("path")
                        if (label.isNotBlank() && nodePath.isNotBlank()) {
                            add(BreadcrumbNode(label, nodePath))
                        }
                    }
                }
            }

            PathListResult(
                currentPath = resolvedPath,
                currentTitle = resolvedTitle,
                items = finalItems,
                tags = tags,
                breadcrumbs = breadcrumbs
            )
        }
    }

    suspend fun searchFiles(
        account: KodAccount,
        keyword: String,
        scopePath: String
    ): Result<List<KodFileItem>> = withContext(Dispatchers.IO) {
        val clean = keyword.trim().replace("@", " ").replace("}", "")
        if (clean.isEmpty()) return@withContext Result.success(emptyList())
        val safeScope = scopePath.trim()
        val scopedSearchPath = if (safeScope.startsWith("{source:")) {
            "{search}/words=$clean@parentPath=$safeScope"
        } else {
            "{search}/words=$clean"
        }
        val primary = listPath(account, scopedSearchPath)
        if (primary.isSuccess) {
            return@withContext primary.map { it.items }
        }
        val fallbackParams = buildMap {
            put("words", clean)
            if (safeScope.startsWith("{source:")) put("parentPath", safeScope)
        }
        listPath(account, "{search}/", extraParams = fallbackParams).map { res ->
            res.items.filter { it.name.contains(clean, ignoreCase = true) }
        }
    }

    suspend fun createFolder(account: KodAccount, parentPath: String, folderName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanName = folderName.trim()
        if (cleanName.isEmpty()) return@withContext Result.failure(IllegalArgumentException("文件夹名称不能为空"))
        runCatching {
            val fullPath = joinKodPath(parentPath, cleanName)
            val json = executeKodPost(
                account = account,
                route = "explorer/index/mkdir",
                params = mapOf("path" to fullPath)
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "创建文件夹失败"))
        }
    }

    suspend fun createTextFile(
        account: KodAccount,
        parentPath: String,
        fileName: String,
        initialContent: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanName = fileName.trim()
        if (cleanName.isEmpty()) return@withContext Result.failure(IllegalArgumentException("文件名不能为空"))
        runCatching {
            val fullPath = joinKodPath(parentPath, cleanName)
            val json = executeKodPost(
                account = account,
                route = "explorer/index/mkfile",
                params = mapOf(
                    "path" to fullPath,
                    "content" to initialContent
                )
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "新建文件失败"))
        }
    }

    suspend fun renameItem(account: KodAccount, path: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return@withContext Result.failure(IllegalArgumentException("新名称不能为空"))
        runCatching {
            val json = executeKodPost(
                account = account,
                route = "explorer/index/pathRename",
                params = mapOf(
                    "path" to path,
                    "rnameTo" to cleanName
                )
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "重命名失败"))
        }
    }

    suspend fun deleteItems(account: KodAccount, items: List<KodFileItem>, permanently: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success(Unit)
        runCatching {
            val dataArr = JSONArray()
            items.forEach { item ->
                dataArr.put(
                    JSONObject().apply {
                        put("path", item.path)
                        put("name", item.name)
                        put("type", if (item.isFolder) "folder" else "file")
                    }
                )
            }
            val params = mutableMapOf("dataArr" to dataArr.toString())
            if (permanently) {
                params["shiftDelete"] = "1"
            }
            val json = executeKodPost(account, "explorer/index/pathDelete", params)
            if (!isKodSuccess(json)) error(extractKodError(json, "删除失败"))
        }
    }

    suspend fun restoreRecycleItems(account: KodAccount, items: List<KodFileItem>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dataArr = JSONArray()
            items.forEach { item ->
                dataArr.put(JSONObject().apply { put("path", item.path) })
            }
            val json = executeKodPost(
                account = account,
                route = "explorer/index/pathRestore",
                params = mapOf("dataArr" to dataArr.toString())
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "还原失败"))
        }
    }

    suspend fun clearRecycleBin(account: KodAccount): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = executeKodPost(
                account = account,
                route = "explorer/index/pathClear",
                params = mapOf("all" to "1")
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "清空回收站失败"))
        }
    }

    suspend fun copyOrMoveItems(
        account: KodAccount,
        items: List<KodFileItem>,
        targetDirPath: String,
        isMove: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success(Unit)
        runCatching {
            val dataArr = JSONArray()
            items.forEach { item ->
                dataArr.put(
                    JSONObject().apply {
                        put("path", item.path)
                        put("name", item.name)
                        put("type", if (item.isFolder) "folder" else "file")
                    }
                )
            }
            val directRoute = if (isMove) "explorer/index/pathCuteTo" else "explorer/index/pathCopyTo"
            val directJson = executeKodPost(
                account = account,
                route = directRoute,
                params = mapOf(
                    "dataArr" to dataArr.toString(),
                    "path" to targetDirPath
                )
            )
            if (isKodSuccess(directJson)) return@runCatching

            // Fallback to two-step clipboard copy/cut + paste used in older KodBox builds
            val stageRoute = if (isMove) "explorer/index/pathCute" else "explorer/index/pathCopy"
            val stageJson = executeKodPost(account, stageRoute, mapOf("dataArr" to dataArr.toString()))
            if (!isKodSuccess(stageJson)) error(extractKodError(stageJson, if (isMove) "移动准备失败" else "复制准备失败"))

            val pasteJson = executeKodPost(account, "explorer/index/pathPast", mapOf("path" to targetDirPath))
            if (!isKodSuccess(pasteJson)) error(extractKodError(pasteJson, if (isMove) "移动失败" else "复制失败"))
        }
    }

    suspend fun toggleFavorite(account: KodAccount, item: KodFileItem, makeFavorite: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val route = if (makeFavorite) "explorer/fav/add" else "explorer/fav/del"
            val params = if (makeFavorite) {
                mapOf(
                    "path" to item.path,
                    "name" to item.name,
                    "type" to if (item.isFolder) "folder" else "file"
                )
            } else {
                mapOf("name" to item.name, "path" to item.path)
            }
            val json = executeKodPost(account, route, params)
            if (!isKodSuccess(json)) error(extractKodError(json, if (makeFavorite) "收藏失败" else "取消收藏失败"))
        }
    }

    suspend fun listTags(account: KodAccount): Result<List<KodTag>> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val json = executeKodGet(account, "explorer/tag/get", emptyMap())
            if (!isKodSuccess(json)) return@runCatching emptyList()
            val arr = json.optJSONArray("data") ?: return@runCatching emptyList()
            parseTagList(arr)
        }
    }

    suspend fun createTag(account: KodAccount, name: String, style: String): Result<Unit> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val cleanName = name.trim()
            if (cleanName.isEmpty()) error("标签名称不能为空")
            val json = executeKodPost(
                account = account,
                route = "explorer/tag/add",
                params = mapOf(
                    "name" to cleanName,
                    "style" to style.ifBlank { "label-blue-normal" }
                )
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "创建标签失败"))
        }
    }

    suspend fun setItemTag(account: KodAccount, item: KodFileItem, tagId: String, add: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val route = if (add) "explorer/tag/filesAddToTag" else "explorer/tag/filesRemoveFromTag"
            val filesParam = item.sourceId.ifBlank {
                item.path.trimEnd('/').replace(",", "__*@*__")
            }
            val json = executeKodPost(
                account = account,
                route = route,
                params = mapOf(
                    "tagID" to tagId,
                    "files" to filesParam
                )
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "更新标签失败"))
        }
    }

    suspend fun listShares(account: KodAccount): Result<List<KodShareItem>> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val json = executeKodGet(
                account = account,
                route = "explorer/list/path",
                params = mapOf("path" to "{userShareLink}/", "pageNum" to "200")
            )
            if (!isKodSuccess(json)) {
                error(extractKodError(json, "加载外链共享失败"))
            }
            val data = json.optJSONObject("data") ?: JSONObject()
            val combined = mutableListOf<KodShareItem>()
            parseShareArray(account.serverUrl, data.optJSONArray("folderList"), isFolderDefault = true, into = combined)
            parseShareArray(account.serverUrl, data.optJSONArray("fileList"), isFolderDefault = false, into = combined)
            combined
        }
    }

    suspend fun listMyCollaborationShares(account: KodAccount): Result<List<KodShareItem>> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val json = executeKodGet(
                account = account,
                route = "explorer/list/path",
                params = mapOf("path" to "{userShare}/", "pageNum" to "200")
            )
            if (!isKodSuccess(json)) {
                error(extractKodError(json, "加载我的协作失败"))
            }
            val data = json.optJSONObject("data") ?: JSONObject()
            val combined = mutableListOf<KodShareItem>()
            parseShareArray(account.serverUrl, data.optJSONArray("folderList"), isFolderDefault = true, into = combined)
            parseShareArray(account.serverUrl, data.optJSONArray("fileList"), isFolderDefault = false, into = combined)
            combined
        }
    }

    suspend fun listSharedWithMe(account: KodAccount): Result<List<KodFileItem>> = withContext(Dispatchers.IO) {
        listPath(account, "{shareToMe}/", pageNum = 200).map { it.items }
    }

    data class ServerPluginsDiscovery(
        val viewerPlugins: List<KodPluginApp>,
        val workspaceApps: List<KodWorkspaceApp>
    )

    suspend fun fetchServerPlugins(account: KodAccount): Result<List<KodPluginApp>> {
        return fetchServerPluginsDiscovery(account).map { it.viewerPlugins }
    }

    suspend fun fetchServerPluginsDiscovery(account: KodAccount): Result<ServerPluginsDiscovery> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val params = buildMap {
                if (account.csrfToken.isNotBlank()) put("CSRF_TOKEN", account.csrfToken)
            }
            val url = buildAuthenticatedUrl(account, "user/view/plugins", params)
            val req = buildAuthedRequestBuilder(account, url)
                .header("Accept", "*/*")
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val js = resp.body?.string().orEmpty()
                if (resp.code == 401) {
                    checkAndThrowIfAuthError(account, resp.code, null, js)
                }
                if (!resp.isSuccessful) error("获取插件列表失败 (HTTP ${resp.code})")
                val maybeJson = parseJsonObject(js)
                if (maybeJson != null && isKodAuthErrorJson(maybeJson)) {
                    checkAndThrowIfAuthError(account, resp.code, maybeJson, js)
                }
                ServerPluginsDiscovery(
                    viewerPlugins = KodPluginRegistry.parsePluginsFromJs(js),
                    workspaceApps = KodPluginRegistry.parseWorkspaceAppsFromJs(js, account.serverUrl)
                )
            }
        }
    }

    suspend fun createOrUpdateShare(
        account: KodAccount,
        item: KodFileItem,
        password: String,
        expireEpochSeconds: Long,
        accessMode: ShareAccessMode,
        existingShareId: String? = null
    ): Result<KodShareItem> = withContext(Dispatchers.IO) {
        runCatching {
            val options = JSONObject().apply {
                put("notDownload", if (accessMode.notDownload) "1" else "0")
                put("notView", if (accessMode.notView) "1" else "0")
                put("canUpload", if (accessMode.canUpload) "1" else "0")
                if (accessMode.canUpload) {
                    put("canEdit", "1")
                }
            }
            val params = mutableMapOf(
                "path" to item.path,
                "isLink" to "1",
                "isShareTo" to "0",
                "title" to item.name,
                "password" to password.trim(),
                "timeTo" to if (expireEpochSeconds > 0L) expireEpochSeconds.toString() else "",
                "options" to options.toString()
            )
            val route = if (!existingShareId.isNullOrBlank()) {
                params["shareID"] = existingShareId
                "explorer/userShare/edit"
            } else {
                "explorer/userShare/add"
            }
            val json = executeKodPost(account, route, params)
            if (!isKodSuccess(json)) error(extractKodError(json, "保存外链分享失败"))

            val shareObj = json.optJSONObject("data") ?: JSONObject()
            val shareHash = shareObj.optString("shareHash")
            val shareUrl = shareObj.optString("url").ifBlank {
                if (shareHash.isNotBlank()) "${account.serverUrl}/#s/$shareHash" else account.serverUrl
            }
            KodShareItem(
                shareId = existingShareId ?: shareObj.optString("shareID").ifBlank { shareHash },
                shareHash = shareHash,
                title = item.name,
                path = item.path,
                sourceId = item.sourceId,
                shareUrl = shareUrl,
                isLink = true,
                password = password.trim(),
                timeTo = expireEpochSeconds,
                notDownload = accessMode.notDownload,
                notView = accessMode.notView,
                canUpload = accessMode.canUpload,
                isFolder = item.isFolder,
                size = item.size,
                ext = item.normalizedExt,
                createTime = System.currentTimeMillis() / 1000L
            )
        }
    }

    suspend fun updateExistingShare(
        account: KodAccount,
        share: KodShareItem,
        password: String,
        expireEpochSeconds: Long,
        accessMode: ShareAccessMode
    ): Result<KodShareItem> = withContext(Dispatchers.IO) {
        val pseudoItem = KodFileItem(
            path = share.path,
            sourceId = share.sourceId,
            name = share.title,
            isFolder = share.isFolder,
            size = share.size,
            ext = share.ext
        )
        createOrUpdateShare(
            account = account,
            item = pseudoItem,
            password = password,
            expireEpochSeconds = expireEpochSeconds,
            accessMode = accessMode,
            existingShareId = share.shareId
        ).map { updated ->
            updated.copy(
                shareUrl = if (updated.shareHash.isBlank()) share.shareUrl else updated.shareUrl,
                shareHash = updated.shareHash.ifBlank { share.shareHash },
                numView = share.numView,
                numDownload = share.numDownload
            )
        }
    }

    suspend fun cancelShare(account: KodAccount, shareId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = executeKodPost(
                account = account,
                route = "explorer/userShare/del",
                params = mapOf("dataArr" to JSONArray().put(shareId).toString())
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "取消分享失败"))
        }
    }

    suspend fun zipItems(account: KodAccount, items: List<KodFileItem>, archiveType: String = "zip"): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dataArr = JSONArray()
            items.forEach { item ->
                dataArr.put(
                    JSONObject().apply {
                        put("path", item.path)
                        put("name", item.name)
                        put("type", if (item.isFolder) "folder" else "file")
                    }
                )
            }
            val json = executeKodPost(
                account = account,
                route = "explorer/index/zip",
                params = mapOf(
                    "dataArr" to dataArr.toString(),
                    "type" to archiveType
                )
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "压缩文件失败"))
        }
    }

    suspend fun unzipItem(account: KodAccount, item: KodFileItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = executeKodPost(
                account = account,
                route = "explorer/index/unzip",
                params = mapOf("path" to item.path)
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "解压失败"))
        }
    }

    suspend fun offlineServerDownload(account: KodAccount, targetDir: String, remoteUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanUrl = remoteUrl.trim()
        val parsed = cleanUrl.toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("请输入有效的 HTTP/HTTPS 下载链接"))
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            return@withContext Result.failure(IllegalArgumentException("离线下载仅支持 HTTP/HTTPS 链接"))
        }
        val safeDir = if (isWritableKodDirectory(targetDir)) targetDir.trim() else account.personalRootPath
        runCatching {
            val json = executeKodPost(
                account = account,
                route = ROUTE_SERVER_DOWNLOAD,
                params = mapOf(
                    "type" to "download",
                    "savePath" to safeDir,
                    "path" to safeDir,
                    "url" to cleanUrl,
                    "uuid" to UUID.randomUUID().toString()
                )
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "离线下载任务创建失败"))
        }
    }

    suspend fun readTextFile(account: KodAccount, item: KodFileItem): Result<String> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val url = buildAuthenticatedUrl(
                account = account,
                route = "explorer/index/fileOut",
                params = mapOf("path" to item.path)
            )
            val req = buildAuthedRequestBuilder(account, url).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) {
                    checkAndThrowIfAuthError(account, resp.code, null, "")
                }
                if (!resp.isSuccessful) error("读取文件失败 (HTTP ${resp.code})")
                val body = resp.body ?: error("文件内容为空")
                val maxBytes = 2 * 1024 * 1024 // 2 MB cap for smooth inline editing
                val bytes = body.byteStream().use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = stream.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) error("文件超过 2 MB，请下载后使用外部编辑器打开")
                        out.write(buf, 0, read)
                    }
                    out.toByteArray()
                }
                val text = bytes.toString(Charsets.UTF_8)
                val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                if (contentType.contains("application/json") && item.normalizedExt != "json") {
                    val maybeErr = parseJsonObject(text)
                    if (maybeErr != null && maybeErr.has("code") && !isKodSuccess(maybeErr)) {
                        checkAndThrowIfAuthError(account, resp.code, maybeErr, text)
                        error(extractKodError(maybeErr, "读取文件失败"))
                    }
                }
                val htmlExts = setOf("html", "htm", "xhtml", "mht", "mhtml")
                if (contentType.contains("text/html") && item.normalizedExt !in htmlExts && looksLikeHtmlLoginPage(text)) {
                    checkAndThrowIfAuthError(account, resp.code, null, text)
                }
                text
            }
        }
    }

    suspend fun saveTextFile(account: KodAccount, item: KodFileItem, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val params = mapOf(
                "path" to item.path,
                "content" to content,
                "charset" to "utf-8"
            )
            val primaryAttempt = runCatching {
                executeKodPost(account = account, route = ROUTE_EDITOR_FILE_SAVE, params = params)
            }
            val json = primaryAttempt.getOrElse { primaryErr ->
                // Fallback for older builds exposing explorer/index/fileSave
                if (primaryErr.message?.contains("非 JSON") == true || primaryErr.message?.contains("HTTP 404") == true) {
                    executeKodPost(account = account, route = "explorer/index/fileSave", params = params)
                } else {
                    throw primaryErr
                }
            }
            if (!isKodSuccess(json)) error(extractKodError(json, "保存文件失败"))
        }
    }

    suspend fun uploadFile(
        account: KodAccount,
        targetDir: String,
        fileName: String,
        mimeType: String,
        inputStreamProvider: () -> InputStream?,
        totalBytes: Long,
        onProgress: (bytesWritten: Long, total: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val normalizedTargetDir = targetDir.trim()
            if (!isWritableKodDirectory(normalizedTargetDir)) {
                error("当前目录不支持上传文件，请先进入个人空间或企业网盘具体文件夹")
            }
            val safeFileName = fileName.trim().ifBlank { "upload_${System.currentTimeMillis()}" }
            val resolvedTotalBytes = if (totalBytes > 0L) {
                totalBytes
            } else {
                measureInputStreamBytes(inputStreamProvider)
            }
            val (totalChunks, chunkSize) = computeChunkPlan(resolvedTotalBytes)
            val url = buildAuthenticatedUrl(account, ROUTE_FILE_UPLOAD, emptyMap())

            if (totalChunks <= 1) {
                uploadSingleRequest(
                    account = account,
                    url = url,
                    targetDir = normalizedTargetDir,
                    fileName = safeFileName,
                    mimeType = mimeType,
                    inputStreamProvider = inputStreamProvider,
                    totalBytes = resolvedTotalBytes,
                    onProgress = onProgress
                )
            } else {
                uploadChunkedRequests(
                    account = account,
                    url = url,
                    targetDir = normalizedTargetDir,
                    fileName = safeFileName,
                    inputStreamProvider = inputStreamProvider,
                    totalBytes = resolvedTotalBytes,
                    totalChunks = totalChunks,
                    chunkSize = chunkSize,
                    onProgress = onProgress
                )
            }
        }
    }

    private fun measureInputStreamBytes(inputStreamProvider: () -> InputStream?): Long {
        val stream = inputStreamProvider() ?: error("无法读取本地文件流")
        return stream.use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
            }
            total
        }
    }

    private fun uploadSingleRequest(
        account: KodAccount,
        url: String,
        targetDir: String,
        fileName: String,
        mimeType: String,
        inputStreamProvider: () -> InputStream?,
        totalBytes: Long,
        onProgress: (bytesWritten: Long, total: Long) -> Unit
    ) {
        val resolvedMediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
        val progressBody = object : RequestBody() {
            override fun contentType(): MediaType? = resolvedMediaType
            override fun contentLength(): Long = if (totalBytes >= 0L) totalBytes else -1L
            override fun writeTo(sink: BufferedSink) {
                val stream = inputStreamProvider() ?: error("无法读取本地文件流")
                stream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var uploaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        uploaded += read
                        onProgress(uploaded, totalBytes)
                    }
                }
            }
        }

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("path", targetDir)
            .addFormDataPart("name", fileName)
            .addFormDataPart("chunks", "1")
            .addFormDataPart("chunk", "0")
            .addFormDataPart("chunkSize", totalBytes.coerceAtLeast(1L).toString())
            .addFormDataPart("size", totalBytes.coerceAtLeast(0L).toString())
            .addFormDataPart("file", fileName, progressBody)

        appendUploadAuthFields(multipartBuilder, account)

        val req = buildAuthedRequestBuilder(account, url)
            .post(multipartBuilder.build())
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            val maybeJson = parseJsonObject(rawBody)
            checkAndThrowIfAuthError(account, resp.code, maybeJson, rawBody)
            if (!resp.isSuccessful) error("上传失败 (HTTP ${resp.code})")
            requireKodSuccessJson(rawBody, "文件上传失败")
        }
    }

    private fun uploadChunkedRequests(
        account: KodAccount,
        url: String,
        targetDir: String,
        fileName: String,
        inputStreamProvider: () -> InputStream?,
        totalBytes: Long,
        totalChunks: Int,
        chunkSize: Long,
        onProgress: (bytesWritten: Long, total: Long) -> Unit
    ) {
        val stream = inputStreamProvider() ?: error("无法读取本地文件流")
        val chunkBuffer = ByteArray(chunkSize.toInt())
        val octetStreamType = "application/octet-stream".toMediaTypeOrNull()
        var uploadedBeforeChunk = 0L

        stream.use { input ->
            for (chunkIndex in 0 until totalChunks) {
                var filled = 0
                while (filled < chunkBuffer.size) {
                    val read = input.read(chunkBuffer, filled, chunkBuffer.size - filled)
                    if (read == -1) break
                    filled += read
                }
                if (filled <= 0) {
                    error("读取本地文件提前结束，已读取 $uploadedBeforeChunk/$totalBytes 字节")
                }

                val chunkBytes = if (filled == chunkBuffer.size) chunkBuffer else chunkBuffer.copyOf(filled)
                var lastError: Throwable? = null
                var succeeded = false

                for (attempt in 0..2) {
                    try {
                        val baseOffset = uploadedBeforeChunk
                        val chunkBody = object : RequestBody() {
                            override fun contentType(): MediaType? = octetStreamType
                            override fun contentLength(): Long = chunkBytes.size.toLong()
                            override fun writeTo(sink: BufferedSink) {
                                var offset = 0
                                val step = 16 * 1024
                                while (offset < chunkBytes.size) {
                                    val count = minOf(step, chunkBytes.size - offset)
                                    sink.write(chunkBytes, offset, count)
                                    offset += count
                                    onProgress(baseOffset + offset, totalBytes)
                                }
                            }
                        }

                        val multipartBuilder = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("path", targetDir)
                            .addFormDataPart("name", fileName)
                            .addFormDataPart("chunks", totalChunks.toString())
                            .addFormDataPart("chunk", chunkIndex.toString())
                            .addFormDataPart("chunkSize", chunkSize.toString())
                            .addFormDataPart("size", totalBytes.toString())
                            .addFormDataPart("file", fileName, chunkBody)

                        appendUploadAuthFields(multipartBuilder, account)

                        val req = buildAuthedRequestBuilder(account, url)
                            .post(multipartBuilder.build())
                            .build()

                        okHttpClient.newCall(req).execute().use { resp ->
                            val rawBody = resp.body?.string().orEmpty()
                            val maybeJson = parseJsonObject(rawBody)
                            checkAndThrowIfAuthError(account, resp.code, maybeJson, rawBody)
                            if (!resp.isSuccessful) {
                                error("分片上传失败 (${chunkIndex + 1}/$totalChunks, HTTP ${resp.code})")
                            }
                            requireKodSuccessJson(rawBody, "分片上传失败 (${chunkIndex + 1}/$totalChunks)")
                        }
                        succeeded = true
                        break
                    } catch (e: Throwable) {
                        lastError = e
                        if (isFatalTransferError(e) || attempt == 2) break
                    }
                }
                if (!succeeded) {
                    throw lastError ?: IllegalStateException("分片上传失败 (${chunkIndex + 1}/$totalChunks)")
                }
                uploadedBeforeChunk += filled
                onProgress(uploadedBeforeChunk, totalBytes)
            }
        }
        if (uploadedBeforeChunk < totalBytes) {
            error("文件上传不完整，仅传输 $uploadedBeforeChunk/$totalBytes 字节")
        }
    }

    private fun appendUploadAuthFields(builder: MultipartBody.Builder, account: KodAccount) {
        if (account.csrfToken.isNotBlank()) {
            builder.addFormDataPart("CSRF_TOKEN", account.csrfToken)
        }
        if (account.accessToken.isNotBlank()) {
            builder.addFormDataPart("accessToken", account.accessToken)
        }
    }

    private fun verifyBinaryDownloadResponse(
        account: KodAccount,
        resp: okhttp3.Response,
        fileExt: String,
        actionLabel: String
    ) {
        if (resp.code == 401) {
            checkAndThrowIfAuthError(account, resp.code, null, "")
        }
        if (!resp.isSuccessful) error("$actionLabel (HTTP ${resp.code})")
        val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
        val contentDisp = resp.header("Content-Disposition").orEmpty().lowercase(Locale.ROOT)
        val normalizedExt = fileExt.trim().lowercase(Locale.ROOT)
        val isAttachment = contentDisp.contains("attachment")

        if (!isAttachment && contentType.contains("application/json") && normalizedExt != "json") {
            val raw = resp.body?.string().orEmpty()
            val json = parseJsonObject(raw)
            checkAndThrowIfAuthError(account, resp.code, json, raw)
            if (json != null && json.has("code") && !isKodSuccess(json)) {
                error(extractKodError(json, actionLabel))
            }
            error("$actionLabel：服务器返回 JSON 错误而非文件内容")
        }

        val htmlExts = setOf("html", "htm", "xhtml", "mht", "mhtml")
        if (!isAttachment && contentType.contains("text/html") && normalizedExt !in htmlExts) {
            val rawHtml = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            if (looksLikeHtmlLoginPage(rawHtml) || isWebLoginRedirectUrl(resp.request.url.toString(), account.serverUrl)) {
                notifySessionExpired(account, "登录凭证已失效，无法下载文件，请重新登录")
                error("登录凭证已失效，无法下载文件，请重新登录")
            }
            error("$actionLabel：服务器返回了网页而非文件内容，请检查登录状态或文件权限")
        }
    }

    suspend fun downloadFile(
        account: KodAccount,
        item: KodFileItem,
        destinationFile: File,
        onProgress: (bytesRead: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val url = buildAuthenticatedUrl(
                account = account,
                route = "explorer/index/fileDownload",
                params = mapOf("path" to item.path)
            )
            destinationFile.parentFile?.mkdirs()
            val partFile = File(destinationFile.parentFile, "${destinationFile.name}.part")
            val existingBytes = if (partFile.exists() && partFile.isFile) partFile.length() else 0L
            val canResume = existingBytes > 0L && (item.size <= 0L || existingBytes < item.size)

            val reqBuilder = buildAuthedRequestBuilder(account, url).get()
            if (canResume) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
            }
            val req = reqBuilder.build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.code == 416 && partFile.exists() && partFile.length() > 0L) {
                    // Already fully downloaded in .part file
                    if (destinationFile.exists()) destinationFile.delete()
                    if (!partFile.renameTo(destinationFile)) {
                        partFile.copyTo(destinationFile, overwrite = true)
                        partFile.delete()
                    }
                    onProgress(destinationFile.length(), destinationFile.length())
                    return@runCatching destinationFile
                }
                verifyBinaryDownloadResponse(account, resp, item.normalizedExt, "下载失败")
                val body = resp.body ?: error("响应体为空")
                val isPartial = resp.code == 206 && canResume
                val startOffset = if (isPartial) existingBytes else 0L
                val responseLen = body.contentLength()
                val expectedTotal = when {
                    isPartial && responseLen > 0L -> startOffset + responseLen
                    responseLen > 0L -> responseLen
                    else -> item.size
                }

                if (startOffset > 0L) {
                    onProgress(startOffset, expectedTotal)
                }

                body.byteStream().use { input ->
                    FileOutputStream(partFile, isPartial).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloaded = startOffset
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, expectedTotal)
                        }
                        output.flush()
                    }
                }
                if (destinationFile.exists()) destinationFile.delete()
                if (!partFile.renameTo(destinationFile)) {
                    partFile.copyTo(destinationFile, overwrite = true)
                    partFile.delete()
                }
                destinationFile
            }
        }
    }

    suspend fun downloadFileToStream(
        account: KodAccount,
        item: KodFileItem,
        outputStreamProvider: () -> java.io.OutputStream?,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<Long> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            val url = buildAuthenticatedUrl(
                account = account,
                route = "explorer/index/fileDownload",
                params = mapOf("path" to item.path)
            )
            val req = buildAuthedRequestBuilder(account, url).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                verifyBinaryDownloadResponse(account, resp, item.normalizedExt, "同步下载失败")
                val body = resp.body ?: error("响应体为空")
                val responseLen = body.contentLength()
                val expectedTotal = when {
                    responseLen > 0L -> responseLen
                    item.size > 0L -> item.size
                    else -> -1L
                }
                val out = outputStreamProvider() ?: error("无法写入本地同步文件夹")
                var total = 0L
                body.byteStream().use { input ->
                    out.use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                            onProgress(total, if (expectedTotal > 0L) expectedTotal else total)
                        }
                        output.flush()
                    }
                }
                total
            }
        }
    }

    fun buildThumbnailUrl(account: KodAccount, item: KodFileItem, width: Int = 320): String {
        if (item.fileThumb.startsWith("http://") || item.fileThumb.startsWith("https://")) {
            return item.fileThumb
        }
        if (item.fileThumb.isNotBlank() && item.fileThumb.startsWith("/")) {
            return "${account.serverUrl}${item.fileThumb}"
        }
        return buildAuthenticatedUrl(
            account = account,
            route = "explorer/index/fileOut",
            params = mapOf(
                "path" to item.path,
                "width" to width.toString(),
                "etag" to item.modifyTime.toString()
            )
        )
    }

    fun buildFullPreviewUrl(account: KodAccount, item: KodFileItem): String {
        return buildAuthenticatedUrl(
            account = account,
            route = "explorer/index/fileOut",
            params = mapOf("path" to item.path)
        )
    }

    fun buildMediaStreamUrl(account: KodAccount, item: KodFileItem): String {
        return buildAuthenticatedUrl(
            account = account,
            route = "explorer/index/fileOut",
            params = mapOf(
                "path" to item.path,
                "name" to item.name
            )
        )
    }

    fun buildPluginPreviewUrl(
        account: KodAccount,
        item: KodFileItem,
        viewer: OfficePluginViewer
    ): String {
        val plugin = KodPluginApp(
            name = viewer.name.lowercase(),
            title = viewer.label,
            route = viewer.route,
            extensions = emptySet()
        )
        return buildPluginPreviewUrl(account, item, plugin)
    }

    fun buildPluginPreviewUrl(
        account: KodAccount,
        item: KodFileItem,
        plugin: KodPluginApp
    ): String {
        val base = account.serverUrl.trimEnd('/')
        val cleanRoute = plugin.route.trimStart('/')
        val prefix = if (cleanRoute.startsWith("?") || cleanRoute.startsWith("index.php?")) {
            "$base/$cleanRoute"
        } else {
            "$base/?$cleanRoute"
        }
        val sep = if (prefix.contains("?")) "&" else "?"
        val sb = StringBuilder(prefix)
        sb.append(sep).append("path=").append(urlEncode(item.path))
        sb.append("&ext=").append(urlEncode(item.normalizedExt))
        sb.append("&name=").append(urlEncode(item.name))
        if (account.accessToken.isNotBlank()) {
            sb.append("&accessToken=").append(urlEncode(account.accessToken))
        }
        if (account.csrfToken.isNotBlank()) {
            sb.append("&CSRF_TOKEN=").append(urlEncode(account.csrfToken))
        }
        return sb.toString()
    }

    internal fun executeKodGet(account: KodAccount, route: String, params: Map<String, String>): JSONObject {
        val url = buildAuthenticatedUrl(account, route, params)
        val req = buildAuthedRequestBuilder(account, url).get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val json = parseJsonObject(raw)
            checkAndThrowIfAuthError(account, resp.code, json, raw)
            if (!resp.isSuccessful) error("服务器请求错误 (HTTP ${resp.code})")
            if (json != null) return json
            val snippet = raw.trim().replace(Regex("\\s+"), " ").take(80)
            error(if (snippet.isEmpty()) "服务器返回空响应 ($route)" else "服务器返回非 JSON 数据 ($snippet)")
        }
    }

    internal fun executeKodPost(account: KodAccount, route: String, params: Map<String, String>): JSONObject {
        val url = buildAuthenticatedUrl(account, route, emptyMap())
        val form = FormBody.Builder()
        params.forEach { (k, v) -> form.add(k, v) }
        if (account.csrfToken.isNotBlank() && !params.containsKey("CSRF_TOKEN")) {
            form.add("CSRF_TOKEN", account.csrfToken)
        }
        if (account.accessToken.isNotBlank() && !params.containsKey("accessToken")) {
            form.add("accessToken", account.accessToken)
        }
        val req = buildAuthedRequestBuilder(account, url)
            .post(form.build())
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val json = parseJsonObject(raw)
            checkAndThrowIfAuthError(account, resp.code, json, raw)
            if (!resp.isSuccessful) error("服务器操作失败 (HTTP ${resp.code})")
            if (json != null) return json
            val snippet = raw.trim().replace(Regex("\\s+"), " ").take(80)
            error(if (snippet.isEmpty()) "服务器返回空响应 ($route)" else "服务器返回非 JSON 数据 ($snippet)")
        }
    }

    private fun buildAuthenticatedUrl(account: KodAccount, route: String, params: Map<String, String>): String {
        val sb = StringBuilder("${account.serverUrl}/?$route")
        params.forEach { (k, v) ->
            sb.append('&').append(urlEncode(k)).append('=').append(urlEncode(v))
        }
        if (account.accessToken.isNotBlank() && !params.containsKey("accessToken")) {
            sb.append("&accessToken=").append(urlEncode(account.accessToken))
        }
        sb.append("&HTTP_X_PLATFORM_1=").append(urlEncode(buildPlatformHeaderPayload()))
        return sb.toString()
    }

    private fun buildAuthedRequestBuilder(account: KodAccount, url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                if (account.csrfToken.isNotBlank()) {
                    header("CSRF-Token", account.csrfToken)
                }
                if (account.cookiesHeader.isNotBlank()) {
                    header("Cookie", account.cookiesHeader)
                }
            }
    }

    private fun parseFileArray(
        array: JSONArray?,
        isFolderDefault: Boolean,
        isRecycle: Boolean,
        into: MutableList<KodFileItem>
    ) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val path = obj.optString("path").ifBlank { obj.optString("sourceID").let { if (it.isNotBlank()) "{source:$it}/" else "" } }
            val name = obj.optString("name").ifBlank { obj.optString("title") }
            if (name.isBlank() || path.isBlank()) continue
            val typeStr = obj.optString("type").lowercase()
            val extStr = obj.optString("ext").trim()
            val isVirtualFolderPath = path.startsWith("{block:", ignoreCase = true) ||
                path.startsWith("{userFav", ignoreCase = true) ||
                path.startsWith("{shareToMe", ignoreCase = true) ||
                path.startsWith("{groupRoot", ignoreCase = true) ||
                path.startsWith("{userFileType", ignoreCase = true) ||
                path.startsWith("{userFileTag", ignoreCase = true) ||
                path.startsWith("{userShare", ignoreCase = true) ||
                path.startsWith("{userRecycle", ignoreCase = true) ||
                path.startsWith("{userRencent", ignoreCase = true)
            val isFolder = when {
                isVirtualFolderPath -> true
                typeStr == "folder" || typeStr == "group" -> true
                typeStr == "file" -> false
                obj.optInt("isParent", 0) == 1 || obj.optBoolean("isParent", false) -> true
                obj.optInt("isFolder", 0) == 1 || obj.optBoolean("isFolder", false) -> true
                extStr.isBlank() && path.endsWith("/") && isFolderDefault -> true
                else -> isFolderDefault
            }
            val tags = parseTagList(obj.optJSONArray("tags"))
            val createUser = obj.optJSONObject("createUser")
            val ownerName = createUser?.optString("nickName").orEmpty()
                .ifBlank { createUser?.optString("name").orEmpty() }
                .ifBlank { obj.optString("owner") }
            val childCount = (obj.optInt("hasFolder", 0) + obj.optInt("hasFile", 0))
                .takeIf { it > 0 }
                ?: obj.optInt("childrenCount", 0).takeIf { it > 0 }
                ?: obj.optInt("fileCount", 0)

            into.add(
                KodFileItem(
                    path = path,
                    sourceId = obj.optString("sourceID"),
                    name = name,
                    isFolder = isFolder,
                    size = obj.optLongSafe("size"),
                    modifyTime = obj.optLongSafe("modifyTime").takeIf { it > 0L } ?: obj.optLongSafe("mtime"),
                    createTime = obj.optLongSafe("createTime").takeIf { it > 0L } ?: obj.optLongSafe("ctime"),
                    ext = obj.optString("ext"),
                    isFav = obj.optInt("isFav", 0) == 1 || obj.optBoolean("isFav", false),
                    isShare = obj.optInt("isShare", 0) == 1 || obj.optBoolean("isShare", false) || obj.optJSONObject("shareInfo") != null,
                    tags = tags,
                    fileThumb = obj.optString("fileThumb"),
                    hashMd5 = obj.optString("hashMd5").ifBlank { obj.optString("hashSimple") },
                    ownerName = ownerName,
                    description = obj.optString("description"),
                    isReadable = obj.optInt("isReadable", 1) != 0,
                    isWriteable = obj.optInt("isWriteable", 1) != 0,
                    isRecycleItem = isRecycle || obj.optInt("isDelete", 0) == 1,
                    childCount = childCount
                )
            )
        }
    }

    private fun parseShareArray(
        serverUrl: String,
        array: JSONArray?,
        isFolderDefault: Boolean,
        into: MutableList<KodShareItem>
    ) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val shareObj = obj.optJSONObject("shareInfo") ?: obj
            val shareId = shareObj.optString("shareID").ifBlank { obj.optString("shareID") }
            val shareHash = shareObj.optString("shareHash").ifBlank { obj.optString("shareHash") }
            val title = obj.optString("name").ifBlank { shareObj.optString("title") }
            val path = obj.optString("path")
            if (title.isBlank()) continue

            val options = shareObj.optJSONObject("options")
            val shareUrl = shareObj.optString("url").ifBlank {
                if (shareHash.isNotBlank()) "$serverUrl/#s/$shareHash" else serverUrl
            }
            into.add(
                KodShareItem(
                    shareId = shareId.ifBlank { shareHash },
                    shareHash = shareHash,
                    title = title,
                    path = path,
                    sourceId = obj.optString("sourceID"),
                    shareUrl = shareUrl,
                    isLink = shareObj.optInt("isLink", 1) == 1,
                    isShareTo = shareObj.optInt("isShareTo", 0) == 1,
                    password = shareObj.optString("password"),
                    timeTo = shareObj.optLongSafe("timeTo"),
                    numView = shareObj.optInt("numView", 0),
                    numDownload = shareObj.optInt("numDownload", 0),
                    notDownload = options?.optString("notDownload") == "1",
                    notView = options?.optString("notView") == "1",
                    canUpload = options?.optString("canUpload") == "1" || options?.optString("canEdit") == "1",
                    isFolder = isFolderDefault || obj.optString("type") == "folder",
                    size = obj.optLongSafe("size"),
                    ext = obj.optString("ext"),
                    createTime = shareObj.optLongSafe("createTime")
                )
            )
        }
    }

    private fun parseTagList(array: JSONArray?): List<KodTag> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("tagID") }
                val name = obj.optString("name")
                val style = obj.optString("style", "label-blue-normal")
                if (name.isNotBlank()) {
                    add(
                        KodTag(
                            id = id.ifBlank { name },
                            name = name,
                            style = style,
                            colorHex = KodFormatters.tagColorForStyle(style)
                        )
                    )
                }
            }
        }
    }

    private fun joinKodPath(parentPath: String, childName: String): String {
        val base = parentPath.trimEnd('/')
        return "$base/$childName"
    }

    private fun parseJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun isKodSuccess(json: JSONObject): Boolean = isKodSuccessJson(json)

    private fun extractKodError(json: JSONObject, fallback: String): String = extractKodErrorJson(json, fallback)

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JSONObject.optLongSafe(key: String): Long {
        val v = opt(key) ?: return 0L
        return when (v) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }

    private fun JSONObject.optDoubleSafe(key: String): Double {
        val v = opt(key) ?: return 0.0
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}
