package com.example.data

import android.content.Context
import coil.ImageLoader
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
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real KodBox HTTP API client communicating directly with self-hosted KodBox instances
 * via standard index.php?<controller>/<action> endpoints, session cookies, CSRF_TOKEN,
 * and accessToken support.
 */
class KodBoxApiClient {

    val cookieJar = KodSessionCookieJar()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
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
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
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

    private fun fetchOptionsOrInitJson(
        normalizedUrl: String,
        explicitToken: String = ""
    ): Pair<Int, JSONObject?> {
        val routes = listOf("user/view/options", "user/index/init")
        var lastHttpCode = 0
        for (route in routes) {
            val endpoint = buildString {
                append("$normalizedUrl/index.php?$route")
                if (explicitToken.isNotBlank()) {
                    append("&accessToken=")
                    append(urlEncode(explicitToken))
                }
            }
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                lastHttpCode = response.code
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = parseJsonObject(body)
                    if (json != null && json.optJSONObject("data") != null) {
                        return response.code to json
                    }
                }
            }
        }
        return lastHttpCode to null
    }

    suspend fun probeServer(rawUrl: String): Result<ServerProbeInfo> = withContext(Dispatchers.IO) {
        val normalized = KodFormatters.normalizeServerUrl(rawUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        runCatching {
            val (httpCode, json) = fetchOptionsOrInitJson(normalized)
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
                    errorMessage = "目标地址响应非 KodBox JSON 格式，请确认填写的是 KodBox 根目录"
                )
            }
            val data = json.optJSONObject("data") ?: JSONObject()
            val kod = data.optJSONObject("kod") ?: JSONObject()
            val system = data.optJSONObject("system") ?: JSONObject()
            val settings = system.optJSONObject("settings") ?: JSONObject()
            val options = data.optJSONObject("options") ?: JSONObject()
            val host = normalized.toHttpUrlOrNull()?.host.orEmpty()
            val csrf = cookieJar.getCsrfToken(host)
                .ifBlank { data.optString("CSRF_TOKEN") }
                .ifBlank { options.optString("CSRF_TOKEN") }

            val needCheckCode = settings.optInt("needCheckCode", 0) == 1 ||
                settings.optBoolean("needCheckCode", false) ||
                options.optInt("needCheckCode", 0) == 1 ||
                options.optBoolean("needCheckCode", false) ||
                data.optInt("needCheckCode", 0) == 1

            val siteName = settings.optString("systemName")
                .ifBlank { options.optString("systemName") }
                .ifBlank { kod.optString("systemName") }
                .ifBlank { "KodBox" }
            val version = kod.optString("version")
                .ifBlank { options.optString("version") }

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

            val loginReq = Request.Builder()
                .url("$normalized/index.php?user/index/loginSubmit")
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
                    ?: error("服务器返回了非预期响应，请确认 KodBox 地址是否正确")
                val codeOk = isKodSuccess(json)
                if (!codeOk) {
                    val errText = extractKodError(json, "登录失败，请检查账号或密码")
                    error(errText)
                }
                val dataObj = json.optJSONObject("data")
                loginToken = dataObj?.optString("accessToken").orEmpty()
                    .ifBlank { json.optString("info") }
            }

            // Step 3: Fetch full workspace & user profile via user/index/init
            fetchInitAccount(
                normalizedUrl = normalized,
                fallbackUsername = username.trim(),
                explicitToken = loginToken
            )
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
            return@withContext Result.failure(IllegalArgumentException("请输入有效的 AccessToken"))
        }
        runCatching {
            fetchInitAccount(
                normalizedUrl = normalized,
                fallbackUsername = "token_user",
                explicitToken = cleanToken
            )
        }
    }

    suspend fun refreshAccountInfo(account: KodAccount): Result<KodAccount> = withContext(Dispatchers.IO) {
        hydrateAccountSession(account)
        runCatching {
            fetchInitAccount(
                normalizedUrl = account.serverUrl,
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
        existingId: String? = null
    ): KodAccount {
        val (httpCode, json) = fetchOptionsOrInitJson(normalizedUrl, explicitToken)
        if (json == null) {
            error(if (httpCode in 200..299) "无法解析 KodBox 初始化响应" else "获取用户信息失败 (HTTP $httpCode)")
        }
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
            error("会话未生效或 AccessToken 无效，请重新登录")
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
        val token = explicitToken.ifBlank {
            kodInfo?.optString("accessToken").orEmpty()
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

        val systemSettings = data.optJSONObject("system")?.optJSONObject("settings")
        val options = data.optJSONObject("options")
        val siteName = systemSettings?.optString("systemName").orEmpty()
            .ifBlank { options?.optString("systemName").orEmpty() }
            .ifBlank { kodInfo?.optString("systemName").orEmpty() }
            .ifBlank { "KodBox" }
        val version = kodInfo?.optString("version").orEmpty()

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
        runCatching {
            val json = executeKodGet(account, "user/userTag/get", emptyMap())
            if (!isKodSuccess(json)) return@runCatching emptyList()
            val arr = json.optJSONArray("data") ?: return@runCatching emptyList()
            parseTagList(arr)
        }
    }

    suspend fun createTag(account: KodAccount, name: String, style: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = executeKodPost(
                account = account,
                route = "user/userTag/add",
                params = mapOf("name" to name.trim(), "style" to style)
            )
            if (!isKodSuccess(json)) error(extractKodError(json, "创建标签失败"))
        }
    }

    suspend fun setItemTag(account: KodAccount, item: KodFileItem, tagId: String, add: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val route = if (add) "explorer/userTag/filesAddToTag" else "explorer/userTag/filesRemoveFromTag"
            val filesParam = item.sourceId.ifBlank { item.path }
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
                if (!resp.isSuccessful) error("获取插件列表失败 (HTTP ${resp.code})")
                val js = resp.body?.string().orEmpty()
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
        runCatching {
            val json = executeKodPost(
                account = account,
                route = "explorer/index/serverDownload",
                params = mapOf(
                    "type" to "download",
                    "savePath" to targetDir,
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
                bytes.toString(Charsets.UTF_8)
            }
        }
    }

    suspend fun saveTextFile(account: KodAccount, item: KodFileItem, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = executeKodPost(
                account = account,
                route = "explorer/index/fileSave",
                params = mapOf(
                    "path" to item.path,
                    "content" to content,
                    "charset" to "utf-8"
                )
            )
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
            val url = buildAuthenticatedUrl(account, "explorer/index/fileUpload", emptyMap())
            val progressBody = object : RequestBody() {
                override fun contentType(): MediaType? = mimeType.toMediaTypeOrNull()
                override fun contentLength(): Long = if (totalBytes > 0L) totalBytes else -1L
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
                .addFormDataPart("size", totalBytes.coerceAtLeast(0L).toString())
                .addFormDataPart("file", fileName, progressBody)

            if (account.csrfToken.isNotBlank()) {
                multipartBuilder.addFormDataPart("CSRF_TOKEN", account.csrfToken)
            }
            if (account.accessToken.isNotBlank()) {
                multipartBuilder.addFormDataPart("accessToken", account.accessToken)
            }

            val req = buildAuthedRequestBuilder(account, url)
                .post(multipartBuilder.build())
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("上传失败 (HTTP ${resp.code})")
                val json = parseJsonObject(rawBody)
                if (json != null && !isKodSuccess(json)) {
                    error(extractKodError(json, "服务器拒绝上传"))
                }
            }
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
            val req = buildAuthedRequestBuilder(account, url).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败 (HTTP ${resp.code})")
                val body = resp.body ?: error("响应体为空")
                val expectedTotal = body.contentLength().takeIf { it > 0L } ?: item.size
                destinationFile.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloaded = 0L
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
                destinationFile
            }
        }
    }

    suspend fun downloadFileToStream(
        account: KodAccount,
        item: KodFileItem,
        outputStreamProvider: () -> java.io.OutputStream?
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
                if (!resp.isSuccessful) error("同步下载失败 (HTTP ${resp.code})")
                val body = resp.body ?: error("响应体为空")
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
            if (!resp.isSuccessful) error("服务器请求错误 (HTTP ${resp.code})")
            return parseJsonObject(raw) ?: error("服务器返回非 JSON 数据")
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
            if (!resp.isSuccessful) error("服务器操作失败 (HTTP ${resp.code})")
            return parseJsonObject(raw) ?: error("服务器返回非 JSON 数据")
        }
    }

    private fun buildAuthenticatedUrl(account: KodAccount, route: String, params: Map<String, String>): String {
        val sb = StringBuilder("${account.serverUrl}/index.php?$route")
        params.forEach { (k, v) ->
            sb.append('&').append(urlEncode(k)).append('=').append(urlEncode(v))
        }
        if (account.accessToken.isNotBlank() && !params.containsKey("accessToken")) {
            sb.append("&accessToken=").append(urlEncode(account.accessToken))
        }
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
                    childCount = obj.optInt("hasFolder", 0) + obj.optInt("hasFile", 0)
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

    private fun isKodSuccess(json: JSONObject): Boolean {
        val code = json.opt("code")
        return when (code) {
            is Boolean -> code
            is Number -> code.toInt() == 1 || code.toInt() == 200
            is String -> code.equals("true", ignoreCase = true) || code == "1" || code == "200"
            else -> false
        }
    }

    private fun extractKodError(json: JSONObject, fallback: String): String {
        val dataStr = json.opt("data") as? String
        if (!dataStr.isNullOrBlank()) return dataStr
        val infoStr = json.opt("info") as? String
        if (!infoStr.isNullOrBlank()) return infoStr
        val msgStr = json.opt("msg") as? String
        if (!msgStr.isNullOrBlank()) return msgStr
        return fallback
    }

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
