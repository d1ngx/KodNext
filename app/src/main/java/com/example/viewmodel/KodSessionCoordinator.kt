package com.example.viewmodel

import android.app.Application
import coil.ImageLoader
import com.example.data.KodQrLoginParser
import com.example.data.KodQrPayload
import com.example.data.repository.AuthRepository
import com.example.data.repository.FileRepository
import com.example.model.AppPreferences
import com.example.model.KodAccount
import com.example.model.KodWorkspaceApp
import com.example.model.MainTab
import com.example.model.ThemePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Focused domain coordinator for authentication, 2-step server onboarding, QR login,
 * multi-account switching, Account Center, Workspace Apps, and Settings/Cache.
 */
internal class KodSessionCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<KodUiState>,
    private val authRepository: AuthRepository,
    private val fileRepository: FileRepository,
    private val emitMessage: (String) -> Unit,
    private val emitError: (String) -> Unit,
    private val onRefreshCurrentDirectory: () -> Unit
) {
    private var activeAccountImageLoader: ImageLoader? = null
    private var activeLoaderKey: String? = null

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
        uiState.update { it.copy(loginForm = transform(it.loginForm).copy(errorMessage = null)) }
    }

    fun resetLoginToServerStep() {
        uiState.update {
            it.copy(
                loginForm = it.loginForm.copy(
                    step = LoginStep.SERVER_URL,
                    probeInfo = null,
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
                                    activeTab = MainTab.HOME
                                )
                            }
                            emitMessage("扫码登录成功：${account.siteName} (${account.displayName})")
                        },
                        onFailure = { err ->
                            uiState.update {
                                it.copy(
                                    loginForm = LoginFormState(
                                        step = LoginStep.SERVER_URL,
                                        serverUrl = payload.serverUrl,
                                        isLoggingIn = false,
                                        errorMessage = err.message ?: "二维码凭证已失效，请刷新网页端二维码或使用账号密码登录"
                                    )
                                )
                            }
                            emitError(err.message ?: "扫码登录失败，请重新扫描或手动登录")
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
                                        infoMessage = "已识别站点地址（$safeHint），$reason；已为您自动检测连接，请输入账号密码登录"
                                    )
                                )
                            }
                            emitError("扫码未直接登录：$reason")
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
                    selectedPaths = emptySet(),
                    files = emptyList(),
                    shares = emptyList(),
                    mediaItems = emptyList()
                )
            }
            emitMessage("已移除账号 ${account.displayName}")
        }
    }

    fun refreshAccountQuota(accountOverride: KodAccount? = null) {
        val account = accountOverride ?: uiState.value.activeAccount ?: return
        scope.launch {
            authRepository.refreshAccountInfo(account).fold(
                onSuccess = { refreshed ->
                    loadServerPlugins(refreshed)
                    uiState.update {
                        it.copy(
                            serverHealthMessage = "连接正常 · 服务端 ${refreshed.serverVersion.ifBlank { "已就绪" }}"
                        )
                    }
                },
                onFailure = { err ->
                    uiState.update {
                        it.copy(serverHealthMessage = "连接检查失败：${err.message}")
                    }
                }
            )
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

    private fun calculateDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { calculateDirSize(it) } ?: 0L
    }
}
