package com.example.viewmodel

import android.app.Application
import coil.ImageLoader
import com.example.BuildConfig
import com.example.data.KodAppUpdateChecker
import com.example.data.KodBoxApiClient
import com.example.data.KodQrLoginParser
import com.example.data.KodQrPayload
import com.example.data.repository.AuthRepository
import com.example.data.repository.FileRepository
import com.example.model.AppPreferences
import com.example.model.KodAccount
import com.example.model.KodOAuthProvider
import com.example.model.KodWorkspaceApp
import com.example.model.MainTab
import com.example.model.ThemePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Focused domain coordinator for authentication, 2-step server onboarding, QR login,
 * multi-account switching, Account Center, Workspace Apps, App Updates, and Settings/Cache.
 */
internal class KodSessionCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<KodUiState>,
    private val authRepository: AuthRepository,
    private val fileRepository: FileRepository,
    private val updateChecker: KodAppUpdateChecker,
    private val emitMessage: (String) -> Unit,
    private val emitError: (String) -> Unit,
    private val emitOpenExternalUrl: (String) -> Unit,
    private val onRefreshCurrentDirectory: () -> Unit
) {
    private var activeAccountImageLoader: ImageLoader? = null
    private var activeLoaderKey: String? = null
    private var oauthProbeJob: Job? = null
    private var oauthCompleting: Boolean = false

    fun getAuthenticatedImageLoader(): ImageLoader {
        val acc = uiState.value.activeAccount
        val key = "${acc?.id}|${acc?.cookiesHeader?.hashCode()}|${acc?.avatarUrl}"
        if (activeAccountImageLoader == null || activeLoaderKey != key) {
            activeLoaderKey = key
            activeAccountImageLoader = fileRepository.apiClient.createAuthenticatedImageLoader(application, acc)
        }
        return activeAccountImageLoader!!
    }

    fun loadServerPlugins(account: KodAccount) {
        scope.launch {
            authRepository.fetchServerPluginsDiscovery(account).onSuccess { discovery ->
                uiState.update {
                    it.copy(
                        serverPlugins = discovery.viewerPlugins,
                        workspaceApps = discovery.workspaceApps
                    )
                }
            }
        }
    }

    fun updateLoginForm(transform: (LoginFormState) -> LoginFormState) {
        oauthProbeJob?.cancel()
        uiState.update {
            it.copy(
                loginForm = transform(it.loginForm).copy(
                    isCheckingOAuth = false,
                    errorMessage = null
                )
            )
        }
    }

    private fun scheduleQuietOAuthDetection(rawUrl: String) {
        oauthProbeJob?.cancel()
        val trimmed = rawUrl.trim()
        val looksLikeHost = trimmed.length >= 4 &&
            !trimmed.contains(" ") &&
            (trimmed.contains(".") || trimmed.startsWith("localhost", ignoreCase = true) || trimmed.contains(":"))
        if (!looksLikeHost) {
            uiState.update { it.copy(loginForm = it.loginForm.copy(isCheckingOAuth = false)) }
            return
        }
        oauthProbeJob = scope.launch {
            delay(650L)
            val currentForm = uiState.value.loginForm
            if (currentForm.step != LoginStep.SERVER_URL || currentForm.serverUrl.trim() != trimmed || currentForm.isProbing) {
                return@launch
            }
            uiState.update { it.copy(loginForm = it.loginForm.copy(isCheckingOAuth = true)) }
            val probe = authRepository.probeServer(trimmed).getOrNull()
            // Apply only if the user hasn't edited the URL or left Step 1 while probing
            uiState.update { state ->
                if (state.loginForm.serverUrl.trim() != trimmed) {
                    state
                } else if (probe != null && probe.reachable) {
                    state.copy(
                        loginForm = state.loginForm.copy(
                            isCheckingOAuth = false,
                            probeInfo = probe
                        )
                    )
                } else {
                    state.copy(
                        loginForm = state.loginForm.copy(isCheckingOAuth = false)
                    )
                }
            }
        }
    }

    fun startOAuthLogin(provider: KodOAuthProvider) {
        val form = uiState.value.loginForm
        val serverUrl = form.probeInfo?.normalizedUrl?.ifBlank { form.serverUrl } ?: form.serverUrl
        if (serverUrl.isBlank()) {
            emitError("请先输入网盘服务器地址")
            return
        }
        oauthCompleting = false
        uiState.update {
            it.copy(
                activeOAuthSession = OAuthLoginSession(
                    serverUrl = serverUrl.trim().trimEnd('/'),
                    provider = provider
                )
            )
        }
    }

    fun cancelOAuthLogin() {
        oauthCompleting = false
        uiState.update { it.copy(activeOAuthSession = null) }
    }

    fun completeOAuthLogin(
        serverUrl: String,
        cookieHeader: String,
        tokenCandidate: String = "",
        silentOnNotLoggedIn: Boolean = true
    ) {
        if (oauthCompleting) return
        oauthCompleting = true
        scope.launch {
            authRepository.loginWithWebViewSession(serverUrl, cookieHeader, tokenCandidate).fold(
                onSuccess = { account ->
                    oauthCompleting = false
                    uiState.update {
                        it.copy(
                            activeOAuthSession = null,
                            showAddAccountLogin = false,
                            loginForm = LoginFormState(),
                            sessionExpiredNotice = null,
                            activeTab = MainTab.HOME
                        )
                    }
                    emitMessage("第三方登录成功：${account.siteName} (${account.displayName})")
                },
                onFailure = { err ->
                    oauthCompleting = false
                    if (!silentOnNotLoggedIn) {
                        emitError(err.message ?: "尚未检测到登录完成，请先在页面中完成第三方授权")
                    }
                }
            )
        }
    }

    fun resetLoginToServerStep() {
        oauthProbeJob?.cancel()
        uiState.update {
            it.copy(
                loginForm = it.loginForm.copy(
                    step = LoginStep.SERVER_URL,
                    isCheckingOAuth = false,
                    captchaBytes = null,
                    checkCode = "",
                    infoMessage = null,
                    errorMessage = null
                )
            )
        }
    }

    fun openQrScanner(open: Boolean) {
        uiState.update { it.copy(showQrScanner = open) }
    }

    fun handleScannedQrContent(rawContent: String) {
        val activeAcc = uiState.value.activeAccount
        val wasOnLoginScreen = activeAcc == null || uiState.value.showAddAccountLogin
        uiState.update { it.copy(showQrScanner = false) }
        val safeHint = KodQrLoginParser.describeSafely(rawContent)

        when (val payload = KodQrLoginParser.parse(rawContent)) {
            is KodQrPayload.AppLoginId -> {
                scope.launch {
                    uiState.update {
                        it.copy(
                            showAddAccountLogin = true,
                            loginForm = LoginFormState(
                                step = LoginStep.SERVER_URL,
                                serverUrl = payload.serverUrl,
                                isLoggingIn = true,
                                infoMessage = "已识别客户端扫码登录凭证，正在登录…",
                                errorMessage = null
                            )
                        )
                    }
                    authRepository.loginWithAppId(payload.serverUrl, payload.loginAppId).fold(
                        onSuccess = { account ->
                            uiState.update {
                                it.copy(
                                    showAddAccountLogin = false,
                                    loginForm = LoginFormState(),
                                    sessionExpiredNotice = null,
                                    activeTab = MainTab.HOME
                                )
                            }
                            emitMessage("扫码登录成功：${account.siteName} (${account.displayName})")
                        },
                        onFailure = { err ->
                            val reason = err.message ?: "二维码凭证已失效，请刷新网页端二维码或使用账号密码登录"
                            uiState.update {
                                it.copy(
                                    loginForm = LoginFormState(
                                        step = LoginStep.SERVER_URL,
                                        serverUrl = payload.serverUrl,
                                        isLoggingIn = false,
                                        errorMessage = "$reason（格式：$safeHint）"
                                    )
                                )
                            }
                            emitError("扫码登录失败：$reason（$safeHint）")
                        }
                    )
                }
            }

            is KodQrPayload.TokenLogin -> {
                scope.launch {
                    uiState.update {
                        it.copy(
                            showAddAccountLogin = true,
                            loginForm = LoginFormState(
                                step = LoginStep.SERVER_URL,
                                serverUrl = payload.serverUrl,
                                isLoggingIn = true,
                                infoMessage = "已识别免密登录凭证，正在登录…",
                                errorMessage = null
                            )
                        )
                    }
                    authRepository.loginWithToken(payload.serverUrl, payload.accessToken).fold(
                        onSuccess = { account ->
                            uiState.update {
                                it.copy(
                                    showAddAccountLogin = false,
                                    loginForm = LoginFormState(),
                                    sessionExpiredNotice = null,
                                    activeTab = MainTab.HOME
                                )
                            }
                            emitMessage("扫码登录成功：${account.siteName} (${account.displayName})")
                        },
                        onFailure = { err ->
                            val reason = err.message ?: "二维码凭证已失效，请刷新网页端二维码或使用账号密码登录"
                            uiState.update {
                                it.copy(
                                    loginForm = LoginFormState(
                                        step = LoginStep.SERVER_URL,
                                        serverUrl = payload.serverUrl,
                                        isLoggingIn = false,
                                        errorMessage = "$reason（格式：$safeHint）"
                                    )
                                )
                            }
                            emitError("扫码登录失败：$reason（$safeHint）")
                        }
                    )
                }
            }

            is KodQrPayload.AuthUrl -> {
                scope.launch {
                    // If already signed in on the same host and scanned from Me tab, try confirming PC Web QR login first
                    val sameHost = activeAcc != null &&
                        activeAcc.serverUrl.trimEnd('/').equals(payload.serverUrl.trimEnd('/'), ignoreCase = true)
                    val looksLikeWebConfirm = payload.fullUrl.contains("qrcode", ignoreCase = true) ||
                        payload.fullUrl.contains("scan", ignoreCase = true) ||
                        payload.fullUrl.contains("confirm", ignoreCase = true)

                    if (!wasOnLoginScreen && sameHost && activeAcc != null && looksLikeWebConfirm) {
                        val confirmRes = authRepository.confirmWebQrLogin(activeAcc, payload.fullUrl)
                        if (confirmRes.isSuccess) {
                            emitMessage(confirmRes.getOrDefault("已确认网页端扫码登录"))
                            return@launch
                        }
                    }

                    uiState.update {
                        it.copy(
                            showAddAccountLogin = true,
                            loginForm = LoginFormState(
                                step = LoginStep.SERVER_URL,
                                serverUrl = payload.serverUrl,
                                isLoggingIn = true,
                                infoMessage = "正在与服务器交换扫码登录凭证…",
                                errorMessage = null
                            )
                        )
                    }
                    authRepository.loginWithQrUrl(payload.serverUrl, payload.fullUrl).fold(
                        onSuccess = { account ->
                            uiState.update {
                                it.copy(
                                    showAddAccountLogin = false,
                                    loginForm = LoginFormState(),
                                    sessionExpiredNotice = null,
                                    activeTab = MainTab.HOME
                                )
                            }
                            emitMessage("扫码登录成功：${account.siteName} (${account.displayName})")
                        },
                        onFailure = { err ->
                            val reason = err.message ?: "二维码未返回有效免密登录凭证"
                            uiState.update {
                                it.copy(
                                    loginForm = LoginFormState(
                                        step = LoginStep.SERVER_URL,
                                        serverUrl = payload.serverUrl,
                                        isLoggingIn = false,
                                        infoMessage = "已识别站点地址（格式：$safeHint），$reason；已为您自动检测连接，请输入账号密码登录"
                                    )
                                )
                            }
                            emitError("扫码未直接登录：$reason（$safeHint）")
                            probeServerConnection(preserveInfoMessage = true)
                        }
                    )
                }
            }

            is KodQrPayload.CredentialsLogin -> {
                scope.launch {
                    uiState.update {
                        it.copy(
                            showAddAccountLogin = true,
                            loginForm = LoginFormState(
                                step = LoginStep.SERVER_URL,
                                serverUrl = payload.serverUrl,
                                username = payload.username,
                                password = payload.password,
                                isLoggingIn = true,
                                infoMessage = "已识别账号信息，正在登录…"
                            )
                        )
                    }
                    authRepository.loginWithPassword(payload.serverUrl, payload.username, payload.password, "").fold(
                        onSuccess = { account ->
                            uiState.update {
                                it.copy(
                                    showAddAccountLogin = false,
                                    loginForm = LoginFormState(),
                                    sessionExpiredNotice = null,
                                    activeTab = MainTab.HOME
                                )
                            }
                            emitMessage("扫码登录成功：${account.siteName} (${account.displayName})")
                        },
                        onFailure = { err ->
                            uiState.update {
                                it.copy(
                                    loginForm = it.loginForm.copy(
                                        isLoggingIn = false,
                                        infoMessage = null,
                                        errorMessage = err.message ?: "扫码登录失败"
                                    )
                                )
                            }
                            emitError(err.message ?: "扫码登录失败")
                        }
                    )
                }
            }

            is KodQrPayload.ServerOnly -> {
                uiState.update {
                    it.copy(
                        showAddAccountLogin = true,
                        loginForm = LoginFormState(
                            step = LoginStep.SERVER_URL,
                            serverUrl = payload.serverUrl,
                            infoMessage = "该二维码仅包含站点地址（未携带免密登录凭证），已自动填充并检测服务器，请输入账号密码登录"
                        )
                    )
                }
                emitMessage("该二维码仅含站点地址，正在自动检测连接…")
                probeServerConnection(preserveInfoMessage = true)
            }

            is KodQrPayload.Unsupported -> {
                val msg = "${payload.reason}（识别摘要：$safeHint）"
                uiState.update {
                    it.copy(loginForm = it.loginForm.copy(infoMessage = null, errorMessage = msg))
                }
                emitError(msg)
            }
        }
    }

    fun probeServerConnection(preserveInfoMessage: Boolean = false) {
        val rawUrl = uiState.value.loginForm.serverUrl.trim()
        if (rawUrl.isEmpty()) {
            uiState.update {
                it.copy(loginForm = it.loginForm.copy(errorMessage = "请先输入网盘服务器地址"))
            }
            return
        }
        scope.launch {
            uiState.update {
                it.copy(
                    loginForm = it.loginForm.copy(
                        isProbing = true,
                        infoMessage = if (preserveInfoMessage) it.loginForm.infoMessage else null,
                        errorMessage = null,
                        probeInfo = null
                    )
                )
            }
            authRepository.probeServer(rawUrl).fold(
                onSuccess = { probe ->
                    uiState.update {
                        it.copy(
                            loginForm = it.loginForm.copy(
                                step = if (probe.reachable) LoginStep.CREDENTIALS else LoginStep.SERVER_URL,
                                serverUrl = probe.normalizedUrl,
                                isProbing = false,
                                probeInfo = probe,
                                errorMessage = if (probe.reachable) null else probe.errorMessage
                            )
                        )
                    }
                    if (probe.reachable && probe.needCheckCode) {
                        refreshCaptcha()
                    }
                },
                onFailure = { err ->
                    uiState.update {
                        it.copy(
                            loginForm = it.loginForm.copy(
                                step = LoginStep.SERVER_URL,
                                isProbing = false,
                                errorMessage = err.message ?: "无法连接到该服务器地址"
                            )
                        )
                    }
                }
            )
        }
    }

    fun refreshCaptcha() {
        val rawUrl = uiState.value.loginForm.serverUrl
        scope.launch {
            authRepository.fetchCaptchaBytes(rawUrl).onSuccess { bytes ->
                uiState.update { it.copy(loginForm = it.loginForm.copy(captchaBytes = bytes)) }
            }
        }
    }

    fun submitLogin() {
        val form = uiState.value.loginForm
        if (form.step != LoginStep.CREDENTIALS || form.probeInfo?.reachable != true) {
            probeServerConnection()
            return
        }
        scope.launch {
            uiState.update { it.copy(loginForm = it.loginForm.copy(isLoggingIn = true, errorMessage = null)) }
            authRepository.loginWithPassword(form.serverUrl, form.username, form.password, form.checkCode).fold(
                onSuccess = { account ->
                    uiState.update {
                        it.copy(
                            showAddAccountLogin = false,
                            loginForm = LoginFormState(),
                            sessionExpiredNotice = null,
                            activeTab = MainTab.HOME
                        )
                    }
                    emitMessage("已登录 ${account.siteName} (${account.displayName})")
                },
                onFailure = { err ->
                    uiState.update {
                        it.copy(
                            loginForm = it.loginForm.copy(
                                isLoggingIn = false,
                                errorMessage = err.message ?: "登录失败，请检查账号和密码"
                            )
                        )
                    }
                    if (err.message?.contains("验证码") == true) {
                        refreshCaptcha()
                    }
                }
            )
        }
    }

    fun openAddAccountLogin() {
        uiState.update {
            it.copy(
                showAddAccountLogin = true,
                showSettings = false,
                showAccountCenter = false,
                loginForm = LoginFormState()
            )
        }
    }

    fun cancelAddAccountLogin() {
        if (uiState.value.accounts.isNotEmpty()) {
            uiState.update { it.copy(showAddAccountLogin = false) }
        }
    }

    fun switchAccount(account: KodAccount) {
        scope.launch {
            authRepository.setActiveAccount(account.id)
            uiState.update {
                it.copy(
                    showAddAccountLogin = false,
                    showSettings = false,
                    sessionExpiredNotice = null,
                    selectedPaths = emptySet(),
                    previewState = null
                )
            }
            emitMessage("已切换至 ${account.displayName} @ ${account.hostLabel}")
        }
    }

    fun logoutAccount(account: KodAccount) {
        scope.launch {
            authRepository.removeAccount(account.id)
            fileRepository.invalidateAccount(account.id)
            uiState.update {
                it.copy(
                    showSettings = false,
                    showAccountCenter = false,
                    sessionExpiredNotice = null,
                    selectedPaths = emptySet(),
                    files = emptyList(),
                    shares = emptyList(),
                    mediaItems = emptyList()
                )
            }
            emitMessage("已移除账号 ${account.displayName}")
        }
    }

    fun onSessionExpired(account: KodAccount, reason: String) {
        val current = uiState.value
        if (current.activeAccount?.id != account.id) return
        if (current.showAddAccountLogin || current.showQrScanner) return
        val cleanReason = reason.trim().ifBlank { "当前账号登录凭证已失效，请重新登录" }
        val existing = current.sessionExpiredNotice
        if (existing != null && existing.accountId == account.id) {
            if (existing.reason.isBlank() && cleanReason.isNotBlank()) {
                uiState.update { it.copy(sessionExpiredNotice = existing.copy(reason = cleanReason)) }
            }
            return
        }
        uiState.update {
            it.copy(
                serverHealthMessage = "登录凭证已失效，请重新登录",
                sessionExpiredNotice = SessionExpiredNotice(
                    accountId = account.id,
                    serverUrl = account.serverUrl,
                    siteName = account.siteName,
                    username = account.username,
                    reason = cleanReason,
                    dialogDismissed = false
                )
            )
        }
    }

    fun dismissSessionExpiredDialog() {
        uiState.update { state ->
            val notice = state.sessionExpiredNotice ?: return@update state
            state.copy(sessionExpiredNotice = notice.copy(dialogDismissed = true))
        }
    }

    fun reloginExpiredAccount(openQrDirectly: Boolean = false) {
        val state = uiState.value
        val account = state.activeAccount
        val notice = state.sessionExpiredNotice
        val serverUrl = account?.serverUrl ?: notice?.serverUrl.orEmpty()
        val username = account?.username ?: notice?.username.orEmpty()
        val siteName = account?.siteName ?: notice?.siteName.orEmpty().ifBlank { "网盘" }

        if (openQrDirectly) {
            uiState.update {
                it.copy(
                    activeWorkspaceApp = null,
                    previewState = null,
                    showSettings = false,
                    showAccountCenter = false,
                    showSharedScreen = false,
                    showSyncScreen = false,
                    actionSheetItem = null,
                    sessionExpiredNotice = it.sessionExpiredNotice?.copy(dialogDismissed = true),
                    showQrScanner = true
                )
            }
            return
        }

        uiState.update {
            it.copy(
                activeWorkspaceApp = null,
                previewState = null,
                showSettings = false,
                showAccountCenter = false,
                showSharedScreen = false,
                showSyncScreen = false,
                actionSheetItem = null,
                sessionExpiredNotice = it.sessionExpiredNotice?.copy(dialogDismissed = true),
                showAddAccountLogin = true,
                loginForm = LoginFormState(
                    step = LoginStep.SERVER_URL,
                    serverUrl = serverUrl,
                    username = username,
                    infoMessage = "「$siteName」登录凭证已失效，请重新输入密码或扫码登录"
                )
            )
        }
        if (serverUrl.isNotBlank()) {
            probeServerConnection(preserveInfoMessage = true)
        }
    }

    fun refreshAccountQuota(accountOverride: KodAccount? = null) {
        val account = accountOverride ?: uiState.value.activeAccount ?: return
        scope.launch {
            val routeStatus = authRepository.probeAccountEndpoint(application, account)
            uiState.update { it.copy(activeEndpointStatus = routeStatus) }
            authRepository.refreshAccountInfo(account).fold(
                onSuccess = { refreshed ->
                    loadServerPlugins(refreshed)
                    val routeTag = if (routeStatus.mode == com.example.data.EndpointMode.LAN) {
                        "局域网直连 (${routeStatus.latencyMs}ms)"
                    } else {
                        "公网线路"
                    }
                    uiState.update {
                        it.copy(
                            sessionExpiredNotice = if (it.sessionExpiredNotice?.accountId == refreshed.id) null else it.sessionExpiredNotice,
                            serverHealthMessage = "连接正常 · $routeTag · 服务端 ${refreshed.serverVersion.ifBlank { "已就绪" }}"
                        )
                    }
                },
                onFailure = { err ->
                    uiState.update {
                        it.copy(serverHealthMessage = "连接检查失败：${err.message}")
                    }
                    if (KodBoxApiClient.isAuthFailure(err)) {
                        onSessionExpired(account, err.message ?: "当前账号的登录凭证已失效，请重新登录")
                    }
                }
            )
        }
    }

    fun saveAccountLanEndpoint(rawLanUrl: String, preferLan: Boolean) {
        val account = uiState.value.activeAccount ?: return
        val trimmed = rawLanUrl.trim()
        val normalized = com.example.data.KodEndpointResolver.normalizeOptionalLanUrl(trimmed)
        if (trimmed.isNotBlank() && normalized.isBlank()) {
            emitError("局域网地址格式无效，请输入如 http://192.168.1.10:8080")
            return
        }
        scope.launch {
            uiState.update { it.copy(isProbingEndpoint = true) }
            val updated = authRepository.updateAccountLanEndpoint(account, normalized, preferLan)
            val routeStatus = authRepository.probeAccountEndpoint(application, updated)
            uiState.update {
                it.copy(
                    activeEndpointStatus = routeStatus,
                    isProbingEndpoint = false
                )
            }
            val msg = when {
                normalized.isBlank() -> "已清除局域网地址，当前使用公网线路"
                !preferLan -> "已保存局域网地址（已关闭自动优先局域网）"
                routeStatus.mode == com.example.data.EndpointMode.LAN ->
                    "局域网直连已生效 (${routeStatus.latencyMs}ms)：${routeStatus.activeBaseUrl}"
                else -> "已保存局域网地址，当前未在局域网环境，已自动走公网线路"
            }
            emitMessage(msg)
        }
    }

    fun probeActiveEndpoint() {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            uiState.update { it.copy(isProbingEndpoint = true) }
            val routeStatus = authRepository.probeAccountEndpoint(application, account)
            uiState.update {
                it.copy(
                    activeEndpointStatus = routeStatus,
                    isProbingEndpoint = false
                )
            }
            val msg = if (routeStatus.mode == com.example.data.EndpointMode.LAN) {
                "已切换至局域网直连 (${routeStatus.latencyMs}ms)"
            } else {
                "局域网不可达或未配置，当前使用公网线路"
            }
            emitMessage(msg)
        }
    }

    fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        scope.launch {
            authRepository.updatePreferences(transform)
            onRefreshCurrentDirectory()
        }
    }

    fun setThemePreference(pref: ThemePreference) {
        scope.launch {
            authRepository.updatePreferences { it.copy(themePreference = pref) }
        }
    }

    fun openSettings(show: Boolean) {
        uiState.update { it.copy(showSettings = show) }
        if (show) refreshCacheSize()
    }

    fun refreshCacheSize() {
        scope.launch(Dispatchers.IO) {
            val bytes = calculateDirSize(application.cacheDir)
            uiState.update { it.copy(cacheSizeBytes = bytes) }
        }
    }

    fun clearImageCache() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                getAuthenticatedImageLoader().memoryCache?.clear()
                File(application.cacheDir, "image_cache").deleteRecursively()
                application.cacheDir.listFiles()?.forEach { child ->
                    if (child.name.contains("cache", ignoreCase = true) || child.name.contains("coil", ignoreCase = true)) {
                        child.deleteRecursively()
                    }
                }
            }
            fileRepository.clearAllDirectoryCache()
            val remaining = calculateDirSize(application.cacheDir)
            uiState.update { it.copy(cacheSizeBytes = remaining) }
            emitMessage("本地图片与目录缓存已清理")
        }
    }

    fun toggleAppHubSheet(show: Boolean) {
        uiState.update { it.copy(showAppHubSheet = show) }
    }

    fun openWorkspaceApp(app: KodWorkspaceApp) {
        uiState.update { it.copy(activeWorkspaceApp = app, showAppHubSheet = false) }
    }

    fun closeWorkspaceApp() {
        uiState.update { it.copy(activeWorkspaceApp = null) }
    }

    fun openWebAdminPortal() {
        val account = uiState.value.activeAccount ?: return
        if (!account.isAdmin) {
            emitError("仅系统管理员可访问后台管理")
            return
        }
        openWorkspaceApp(
            KodWorkspaceApp(
                id = "admin",
                title = "系统后台管理",
                description = "官方完整管理控制台",
                launchUrl = "${account.serverUrl.trimEnd('/')}/#admin",
                isRouterPage = true,
                isAdminOnly = true
            )
        )
    }

    fun openWebDrivePortal() {
        val account = uiState.value.activeAccount ?: return
        openWorkspaceApp(
            KodWorkspaceApp(
                id = "web_portal",
                title = "网盘网页端",
                description = "打开完整网页版网盘界面",
                launchUrl = "${account.serverUrl.trimEnd('/')}/",
                isRouterPage = true,
                isAdminOnly = false
            )
        )
    }

    fun openWebAccountPortal() {
        val account = uiState.value.activeAccount ?: return
        openWorkspaceApp(
            KodWorkspaceApp(
                id = "user_setting",
                title = "网页版个人中心",
                description = "完整个人资料、头像与绑定设置",
                launchUrl = "${account.serverUrl.trimEnd('/')}/#setting/user",
                isRouterPage = true,
                isAdminOnly = false
            )
        )
    }

    fun openAccountCenter(open: Boolean) {
        uiState.update { it.copy(showAccountCenter = open) }
        if (open) loadAccountCenterData()
    }

    fun loadAccountCenterData() {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            uiState.update { it.copy(isLoadingAccountCenter = true) }
            val (devicesRes, logsRes) = coroutineScope {
                val devicesJob = async { authRepository.fetchUserDevices(account) }
                val logsJob = async { authRepository.fetchUserLogs(account) }
                devicesJob.await() to logsJob.await()
            }
            uiState.update { state ->
                state.copy(
                    isLoadingAccountCenter = false,
                    userDevices = devicesRes.getOrElse { state.userDevices },
                    userLogs = logsRes.getOrElse { state.userLogs }
                )
            }
        }
    }

    fun updateAccountProfile(nickName: String, email: String, phone: String, sex: String) {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            uiState.update { it.copy(isWorking = true) }
            val updates = buildList {
                if (nickName.trim() != account.nickName) add("nickName" to nickName.trim())
                if (email.trim() != account.email) add("email" to email.trim())
                if (phone.trim() != account.phone) add("phone" to phone.trim())
                if (sex != account.sex) add("sex" to sex)
            }
            if (updates.isEmpty()) {
                uiState.update { it.copy(isWorking = false) }
                emitMessage("个人资料未发生变化")
                return@launch
            }
            var firstError: String? = null
            for ((k, v) in updates) {
                val res = authRepository.updateUserProfileField(account, k, v)
                if (res.isFailure) {
                    firstError = res.exceptionOrNull()?.message ?: "更新 $k 失败"
                    break
                }
            }
            uiState.update { it.copy(isWorking = false) }
            if (firstError != null) {
                emitError(firstError)
            } else {
                val updated = account.copy(
                    nickName = nickName.trim().ifBlank { account.nickName },
                    email = email.trim(),
                    phone = phone.trim(),
                    sex = sex
                )
                authRepository.saveOrUpdateAccount(updated, makeActive = true)
                emitMessage("个人资料已同步到网盘")
            }
        }
    }

    fun changeAccountPassword(oldPassword: String, newPassword: String) {
        val account = uiState.value.activeAccount ?: return
        if (oldPassword.isBlank() || newPassword.length < 6) {
            emitError("请输入原密码，且新密码不少于 6 位")
            return
        }
        scope.launch {
            uiState.update { it.copy(isWorking = true) }
            authRepository.changePassword(account, oldPassword, newPassword).fold(
                onSuccess = {
                    uiState.update { it.copy(isWorking = false) }
                    emitMessage("登录密码已修改")
                },
                onFailure = { err ->
                    uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "修改密码失败")
                }
            )
        }
    }

    fun setAutoCheckUpdates(autoCheck: Boolean) {
        scope.launch {
            authRepository.updatePreferences {
                it.copy(autoCheckUpdates = autoCheck)
            }
        }
    }

    fun checkForUpdates(manual: Boolean) {
        val targetUrl = KodAppUpdateChecker.fixedUpdateUrl()
        if (targetUrl.isBlank()) {
            if (manual) {
                uiState.update { it.copy(updateStatusMessage = "当前安装包未内置更新地址") }
                emitMessage("当前安装包未内置更新地址")
            }
            return
        }
        if (uiState.value.isCheckingUpdate) return

        scope.launch {
            uiState.update {
                it.copy(
                    isCheckingUpdate = true,
                    updateStatusMessage = "正在检测更新…"
                )
            }
            updateChecker.checkForUpdate(
                siteOrManifestUrl = targetUrl,
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE
            ).fold(
                onSuccess = { updateInfo ->
                    if (updateInfo != null) {
                        uiState.update {
                            it.copy(
                                isCheckingUpdate = false,
                                availableUpdate = updateInfo,
                                updateDialogDismissed = false,
                                updateStatusMessage = "发现新版本 v${updateInfo.versionName}"
                            )
                        }
                        if (manual) {
                            emitMessage("发现新版本 v${updateInfo.versionName}")
                        }
                    } else {
                        uiState.update {
                            it.copy(
                                isCheckingUpdate = false,
                                availableUpdate = null,
                                updateStatusMessage = "当前已是最新版本 v${BuildConfig.VERSION_NAME}"
                            )
                        }
                        if (manual) {
                            emitMessage("当前已是最新版本 v${BuildConfig.VERSION_NAME}")
                        }
                    }
                },
                onFailure = { err ->
                    val msg = err.message?.takeIf { it.isNotBlank() } ?: "无法连接更新站点"
                    uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateStatusMessage = "检测失败：$msg"
                        )
                    }
                    if (manual) {
                        emitError("检测更新失败：$msg")
                    }
                }
            )
        }
    }

    fun dismissUpdateDialog() {
        uiState.update { it.copy(updateDialogDismissed = true) }
    }

    fun openUpdateDownload() {
        val url = uiState.value.availableUpdate?.downloadUrl?.trim().orEmpty()
        if (url.isBlank()) {
            emitError("更新包下载地址无效")
            return
        }
        uiState.update { it.copy(updateDialogDismissed = true) }
        emitOpenExternalUrl(url)
    }

    private fun calculateDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { calculateDirSize(it) } ?: 0L
    }
}
