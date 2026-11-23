package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.KodBoxApiClient
import com.example.model.KodAccount
import com.example.model.KodWorkspaceApp
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Full-screen authenticated web container preserving the 100% authentic KodBox web interface
 * for official Admin Console (`#admin`), Web Account Center (`#setting/user`), and workspace
 * plugins (`#project`, `?plugin/kodIM/`, `#aiDoc`, `adminer`, `webConsole`, etc.) without mutating original UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KodAppWorkspaceScreen(
    account: KodAccount,
    apiClient: KodBoxApiClient,
    app: KodWorkspaceApp,
    onCopyUrl: (String) -> Unit,
    onBack: () -> Unit,
    onSessionExpired: (String) -> Unit = {},
    onReloginClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var loadProgress by remember(app.id) { mutableIntStateOf(0) }
    var webError by remember(app.id) { mutableStateOf<String?>(null) }
    var redirectLoopError by remember(app.id) { mutableStateOf(false) }
    var loginRedirectDetected by remember(app.id) { mutableStateOf(false) }
    var cookiesReady by remember(account.id, app.id, app.launchUrl) { mutableStateOf(false) }
    var resolvedTargetUrl by remember(account.id, app.id, app.launchUrl) { mutableStateOf(app.launchUrl) }
    var sessionResetEpoch by remember(account.id, app.id) { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val isStandalonePhpPlugin = remember(app.id, app.launchUrl) {
        val idLower = app.id.lowercase()
        idLower == "adminer" ||
            idLower == "webconsole" ||
            idLower == "console" ||
            idLower.contains("console") ||
            app.launchUrl.contains("/plugins/", ignoreCase = true) ||
            app.launchUrl.contains("?plugin/", ignoreCase = true)
    }

    val subtitle = remember(app.id, account.siteName) {
        when (app.id.lowercase()) {
            "admin" -> "${account.siteName} · 官方完整管理后台"
            "user_setting" -> "${account.siteName} · 官方网页个人中心"
            "web_portal" -> "${account.siteName} · 完整网页端"
            else -> "${account.siteName} · 扩展应用"
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    // Bootstrap a clean browser-mode PHP session (KOD_SESSION_ID + CSRF_TOKEN + plugin cookies)
    // and clear stale session cookies BEFORE mounting WebView so standalone PHP plugins
    // (adminer, webConsole/console, /plugins/) never hit ERR_TOO_MANY_REDIRECTS loops.
    LaunchedEffect(account.id, account.serverUrl, account.cookiesHeader, app.id, app.launchUrl, sessionResetEpoch) {
        cookiesReady = false
        webError = null
        redirectLoopError = false
        loginRedirectDetected = false

        val bootstrap = runCatching {
            apiClient.prepareWorkspaceWebSession(
                account = account,
                rawLaunchUrl = app.launchUrl,
                appId = app.id,
                isStandalonePlugin = isStandalonePhpPlugin,
                forceFreshSession = isStandalonePhpPlugin || sessionResetEpoch > 0
            )
        }.getOrElse {
            val fallbackCookies = runCatching {
                apiClient.getFreshWebViewCookies(account, warmUpServerSession = isStandalonePhpPlugin)
            }.getOrDefault(emptyList())
            KodBoxApiClient.WebViewSessionBootstrap(
                cookies = fallbackCookies,
                resolvedUrl = KodBoxApiClient.upgradeRedirectToHttpsIfNeeded(app.launchUrl, account.serverUrl),
                sessionVerified = false
            )
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // Clear stale session cookies so duplicate/expired KOD_SESSION_ID or PHPSESSID cookies cannot loop
        suspendCancellableCoroutine<Unit> { cont ->
            cm.removeSessionCookies {
                if (cont.isActive) cont.resume(Unit)
            }
        }

        val isHttps = account.serverUrl.startsWith("https://", ignoreCase = true)
        val secureSuffix = if (isHttps) "; Secure; SameSite=Lax" else ""
        val cookieOrigin = account.serverUrl.trimEnd('/')
        bootstrap.cookies.forEach { pair ->
            if (pair.contains("=")) {
                cm.setCookie(cookieOrigin, "$pair; Path=/$secureSuffix")
            }
        }
        cm.flush()

        // Skip appending accessToken query param for standalone PHP plugins once browser cookie session is ready,
        // preventing PHP front-controller/plugin redirect loops on adminer and webConsole.
        val skipTokenParam = isStandalonePhpPlugin || bootstrap.sessionVerified
        resolvedTargetUrl = appendAuthParamsIfNeeded(
            rawUrl = bootstrap.resolvedUrl.ifBlank { app.launchUrl },
            account = account,
            skipAccessTokenParam = skipTokenParam
        )
        cookiesReady = true
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = app.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("workspace_app_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onCopyUrl(resolvedTargetUrl.ifBlank { app.launchUrl }) }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制页面地址")
                    }
                    IconButton(
                        onClick = {
                            webError = null
                            redirectLoopError = false
                            webViewRef?.reload()
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        onClick = {
                            val openTarget = resolvedTargetUrl.ifBlank { app.launchUrl }
                            if (openTarget.startsWith("http://", true) || openTarget.startsWith("https://", true)) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openTarget)))
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = "在浏览器打开")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .testTag("workspace_app_container_${app.id.lowercase()}")
        ) {
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            webError?.takeIf { !redirectLoopError }?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "页面加载异常：$err",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (!cookiesReady) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text(
                                text = "正在初始化插件会话…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    key(sessionResetEpoch, resolvedTargetUrl) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    settings.apply {
                                        javaScriptEnabled = true
                                        javaScriptCanOpenWindowsAutomatically = true
                                        setSupportMultipleWindows(true)
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        setSupportZoom(true)
                                        allowFileAccess = false
                                        allowContentAccess = false
                                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                        userAgentString = KodBoxApiClient.buildAndroidUserAgent()
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            loadProgress = newProgress
                                        }

                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message?
                                        ): Boolean {
                                            val parent = view ?: return false
                                            val tempWebView = WebView(parent.context).apply {
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        v: WebView?,
                                                        req: WebResourceRequest?
                                                    ): Boolean {
                                                        val popupUrl = req?.url?.toString().orEmpty()
                                                        if (popupUrl.startsWith("http://", true) ||
                                                            popupUrl.startsWith("https://", true)
                                                        ) {
                                                            val upgraded = KodBoxApiClient.upgradeRedirectToHttpsIfNeeded(
                                                                popupUrl,
                                                                account.serverUrl
                                                            )
                                                            parent.loadUrl(upgraded)
                                                        }
                                                        v?.destroy()
                                                        return true
                                                    }
                                                }
                                            }
                                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                                            transport?.webView = tempWebView
                                            resultMsg?.sendToTarget()
                                            return true
                                        }
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val urlStr = request?.url?.toString().orEmpty()
                                            val scheme = request?.url?.scheme?.lowercase().orEmpty()
                                            if (scheme != "http" && scheme != "https") return true

                                            // Upgrade same-host http:// redirects back to https:// on HTTPS servers
                                            val upgraded = KodBoxApiClient.upgradeRedirectToHttpsIfNeeded(
                                                urlStr,
                                                account.serverUrl
                                            )
                                            if (upgraded != urlStr) {
                                                view?.loadUrl(upgraded)
                                                return true
                                            }

                                            if (request?.isForMainFrame == true &&
                                                KodBoxApiClient.isWebLoginRedirectUrl(upgraded, account.serverUrl)
                                            ) {
                                                loginRedirectDetected = true
                                                onSessionExpired("网页端提示当前登录凭证已失效，请重新登录")
                                                return true
                                            }
                                            return false
                                        }

                                        override fun onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: android.graphics.Bitmap?
                                        ) {
                                            super.onPageStarted(view, url, favicon)
                                            if (!url.isNullOrBlank() &&
                                                KodBoxApiClient.isWebLoginRedirectUrl(url, account.serverUrl)
                                            ) {
                                                view?.stopLoading()
                                                loginRedirectDetected = true
                                                onSessionExpired("网页端提示当前登录凭证已失效，请重新登录")
                                            }
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            if (request?.isForMainFrame == true) {
                                                val desc = error?.description?.toString().orEmpty()
                                                val isLoop = error?.errorCode == ERROR_REDIRECT_LOOP ||
                                                    desc.contains("TOO_MANY_REDIRECTS", ignoreCase = true)
                                                if (isLoop) {
                                                    redirectLoopError = true
                                                    webError = "插件页面发生多次重定向 ($desc)"
                                                } else {
                                                    webError = desc.ifBlank { "网络连接失败" }
                                                }
                                            }
                                        }
                                    }
                                    webViewRef = this
                                    loadUrl(resolvedTargetUrl)
                                }
                            },
                            update = { wv ->
                                webViewRef = wv
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (redirectLoopError && !loginRedirectDetected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp)
                            .testTag("workspace_redirect_loop_guard"),
                        contentAlignment = Alignment.Center
                    ) {
                        ElevatedCard(
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SyncProblem,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                                Text(
                                    text = "插件重定向次数过多",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "服务端插件「${app.title}」在校验会话或反向代理跳转时出现循环，可重置会话重试或在系统浏览器中打开。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val openTarget = resolvedTargetUrl.ifBlank { app.launchUrl }
                                            if (openTarget.startsWith("http://", true) || openTarget.startsWith("https://", true)) {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openTarget)))
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("浏览器打开")
                                    }
                                    Button(
                                        onClick = { sessionResetEpoch += 1 },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("workspace_reset_session_retry_button")
                                    ) {
                                        Text("重置会话重试")
                                    }
                                }
                            }
                        }
                    }
                }

                if (loginRedirectDetected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp)
                            .testTag("workspace_login_redirect_guard"),
                        contentAlignment = Alignment.Center
                    ) {
                        ElevatedCard(
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.errorContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LockReset,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                                Text(
                                    text = "登录凭证已失效",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "网页端要求重新登录，请使用客户端重新验证账号身份。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = onReloginClick,
                                    modifier = Modifier.testTag("workspace_relogin_button")
                                ) {
                                    Text("立即重新登录")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun appendAuthParamsIfNeeded(
    rawUrl: String,
    account: KodAccount,
    skipAccessTokenParam: Boolean = false
): String {
    if (rawUrl.isBlank()) return account.serverUrl
    if (skipAccessTokenParam) return rawUrl
    val hashIdx = rawUrl.indexOf('#')
    val basePart = if (hashIdx >= 0) rawUrl.substring(0, hashIdx) else rawUrl
    val hashPart = if (hashIdx >= 0) rawUrl.substring(hashIdx) else ""
    if (basePart.contains("accessToken=")) return rawUrl
    if (account.accessToken.isBlank()) return rawUrl
    val sep = if (basePart.contains("?")) "&" else "?"
    val encodedToken = URLEncoder.encode(account.accessToken, "UTF-8")
    return "$basePart${sep}accessToken=$encodedToken$hashPart"
}
