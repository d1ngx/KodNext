package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.KodOAuthDiscovery
import com.example.data.KodQrLoginParser
import com.example.data.KodQrPayload
import com.example.model.KodOAuthProvider
import java.net.URI

/**
 * Hardened in-app OAuth WebView screen for KodBox third-party login (QQ, WeChat, WeWork, DingTalk,
 * Feishu, GitHub, OIDC, etc.).
 *
 * Key capabilities & safeguards:
 * - Entry URL is strictly validated against HTTP/HTTPS and the user's configured KodBox host.
 * - Strips BOTH `; wv` and `Version/4.0` from User-Agent and upgrades `graph.qq.com/oauth2.0/authorize`
 *   to `display=mobile` so QQ Connect's mobile H5 page enables native one-tap QQ client handoff.
 * - Strictly validates native app handoff against allowlisted schemes (`wtloginmqq://`, `mqqapi://`,
 *   `weixin://`, `wxwork://`, `dingtalk://`, `lark://`, `feishu://`, etc.) and official client
 *   packages, stripping `component`/`selector`/URI grant flags from `intent://` URIs.
 * - Provides one-tap "保存二维码并打开微信 / 打开微信" & "打开 QQ" helpers using zero-permission
 *   MediaStore image capture (Android 10+) + scoped `<queries>` app launch so users on a single
 *   phone can complete WeChat or QQ QR authorization seamlessly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OAuthLoginWebViewScreen(
    serverUrl: String,
    provider: KodOAuthProvider,
    onBack: () -> Unit,
    onTryCompleteOAuth: (serverUrl: String, cookieHeader: String, tokenCandidate: String, silentOnNotLoggedIn: Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val normalizedServer = remember(serverUrl) { serverUrl.trim().trimEnd('/') }
    val entryUrl = remember(normalizedServer, provider.authUrl) {
        KodOAuthDiscovery.resolveSafeServerAuthUrl(provider.authUrl, normalizedServer)
            .ifBlank { "$normalizedServer/#user/login" }
    }
    val fallbackLoginUrl = remember(normalizedServer, provider.fallbackLoginUrl) {
        KodOAuthDiscovery.resolveSafeServerAuthUrl(provider.fallbackLoginUrl, normalizedServer)
            .ifBlank { "$normalizedServer/#user/login" }
    }
    val hostSubtitle = remember(normalizedServer) {
        runCatching { URI(normalizedServer).host.orEmpty() }.getOrDefault("")
            .ifBlank { normalizedServer }
    }

    var cookiesPrepared by remember(normalizedServer, provider.id) { mutableStateOf(false) }
    var loadProgress by remember(normalizedServer, provider.id) { mutableIntStateOf(0) }
    var webError by remember(normalizedServer, provider.id) { mutableStateOf<String?>(null) }
    var clientHandoffHint by remember(normalizedServer, provider.id) { mutableStateOf<String?>(null) }
    var currentPageUrl by remember(normalizedServer, provider.id) { mutableStateOf(entryUrl) }
    var lastAutoCheckedSig by remember(normalizedServer, provider.id) { mutableStateOf("") }
    var launchedNativeClient by remember(normalizedServer, provider.id) { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun collectSessionAndCheck(pageUrl: String, silent: Boolean) {
        val cm = CookieManager.getInstance()
        cm.flush()
        val rootCookies = cm.getCookie(normalizedServer).orEmpty()
        val pageCookies = if (pageUrl.startsWith("http://", true) || pageUrl.startsWith("https://", true)) {
            cm.getCookie(pageUrl).orEmpty()
        } else {
            ""
        }
        val mergedCookies = mergeCookieStrings(rootCookies, pageCookies)
        val tokenCandidate = extractTokenFromCallbackUrl(pageUrl)
        if (silent) {
            val sig = "$pageUrl|$mergedCookies|$tokenCandidate"
            if (sig == lastAutoCheckedSig) return
            if (mergedCookies.isBlank() && tokenCandidate.isBlank()) return
            lastAutoCheckedSig = sig
        }
        onTryCompleteOAuth(normalizedServer, mergedCookies, tokenCandidate, silent)
    }

    fun launchAllowlistedPackage(packages: List<String>, preferredSchemeUri: String?, appLabel: String): Boolean {
        if (!preferredSchemeUri.isNullOrBlank()) {
            val uri = Uri.parse(preferredSchemeUri)
            if (KodOAuthDiscovery.isAllowedNativeOAuthAppScheme(uri.scheme)) {
                val schemeLaunched = runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.isSuccess
                if (schemeLaunched) {
                    launchedNativeClient = true
                    return true
                }
            }
        }
        for (pkg in packages) {
            if (!KodOAuthDiscovery.isAllowedNativeOAuthPackage(pkg)) continue
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            val ok = runCatching {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }.isSuccess
            if (ok) {
                launchedNativeClient = true
                return true
            }
        }
        clientHandoffHint = "未检测到已安装的$appLabel 客户端，请确认已安装或使用网页登录"
        return false
    }

    fun saveQrAndOpenSocialApp(packages: List<String>, preferredSchemeUri: String?, appLabel: String) {
        val wv = webViewRef
        val saved = if (wv != null) {
            captureWebViewQrToGallery(context, wv, appLabel)
        } else {
            false
        }
        val opened = launchAllowlistedPackage(packages, preferredSchemeUri, appLabel)
        if (opened) {
            clientHandoffHint = if (saved) {
                "二维码已保存到相册并打开$appLabel，请在「扫一扫 → 相册」识别确认后返回本应用"
            } else {
                "已打开$appLabel，请截屏或扫码确认授权后返回本应用"
            }
        }
    }

    /**
     * Safely launches an allowlisted native OAuth social client (e.g., QQ, TIM, WeChat, WeWork,
     * DingTalk, Feishu) via either custom scheme or validated Chrome `intent://` URI.
     */
    fun tryLaunchAllowlistedNativeClient(rawUrl: String, uri: Uri, webView: WebView?): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (KodOAuthDiscovery.isAllowedNativeOAuthAppScheme(scheme)) {
            val launched = runCatching {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.isSuccess || runCatching {
                // Retry without BROWSABLE category in case the client activity only declares DEFAULT
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.isSuccess
            if (launched) {
                launchedNativeClient = true
                clientHandoffHint = "已唤起${provider.displayName}客户端，请在客户端确认授权后返回"
            } else {
                clientHandoffHint = "未检测到已安装的${provider.displayName}客户端，请直接在网页中完成登录"
            }
            return true
        }

        if (scheme == "intent") {
            val safeMeta = KodOAuthDiscovery.parseSafeIntentSchemeMeta(rawUrl) ?: return true
            val launched = runCatching {
                val parsed = Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME).apply {
                    // Standard Android Intent URI hardening: strip component, selector, and URI grants
                    component = null
                    selector = null
                    flags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (safeMeta.targetPackage.isNotBlank()) {
                        setPackage(safeMeta.targetPackage)
                    }
                }
                context.startActivity(parsed)
            }.isSuccess
            if (launched) {
                launchedNativeClient = true
                clientHandoffHint = "已唤起${provider.displayName}客户端，请在客户端确认授权后返回"
            } else if (safeMeta.browserFallbackUrl.isNotBlank()) {
                clientHandoffHint = "未检测到已安装的${provider.displayName}客户端，已切换网页授权"
                webView?.loadUrl(safeMeta.browserFallbackUrl)
            } else {
                clientHandoffHint = "未检测到已安装的${provider.displayName}客户端，请直接在网页中完成登录"
            }
            return true
        }

        // Block all non-allowlisted schemes (javascript:, file:, content:, data:, etc.)
        return true
    }

    // When returning from QQ / WeChat / DingTalk / Feishu / WeWork native client,
    // immediately check if the OAuth callback completed on the KodBox host.
    DisposableEffect(lifecycleOwner, normalizedServer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && launchedNativeClient) {
                launchedNativeClient = false
                if (KodOAuthDiscovery.isTargetServerHost(currentPageUrl, normalizedServer)) {
                    collectSessionAndCheck(currentPageUrl, silent = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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

    // Clear stale session cookies before starting a fresh OAuth sign-in flow.
    LaunchedEffect(normalizedServer, provider.id) {
        cookiesPrepared = false
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.removeSessionCookies {
            cm.flush()
            cookiesPrepared = true
        }
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

    val isWeChatAuth = provider.id.equals("weixin", ignoreCase = true) ||
        currentPageUrl.contains("open.weixin.qq.com", ignoreCase = true) ||
        currentPageUrl.contains("weixin.qq.com", ignoreCase = true)
    val isQqAuth = !isWeChatAuth && (
        provider.id.equals("qq", ignoreCase = true) ||
            currentPageUrl.contains("graph.qq.com", ignoreCase = true) ||
            currentPageUrl.contains("ptlogin2.qq.com", ignoreCase = true)
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${provider.displayName} 登录",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = hostSubtitle,
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
                        modifier = Modifier.testTag("oauth_webview_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回登录页"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            webError = null
                            webViewRef?.loadUrl(fallbackLoginUrl)
                        },
                        modifier = Modifier.testTag("oauth_webview_fallback_login")
                    ) {
                        Text("网页登录")
                    }
                    IconButton(
                        onClick = {
                            val target = currentPageUrl.ifBlank { entryUrl }
                            if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                                }
                            }
                        },
                        modifier = Modifier.testTag("oauth_webview_open_external")
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = "在外部浏览器打开")
                    }
                    IconButton(
                        onClick = {
                            webError = null
                            webViewRef?.reload()
                        },
                        modifier = Modifier.testTag("oauth_webview_refresh")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新授权页面")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "完成授权返回网盘后自动同步会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(
                        onClick = { collectSessionAndCheck(currentPageUrl, silent = false) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("oauth_webview_sync_login_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("同步登录")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("oauth_webview_container")
        ) {
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isWeChatAuth) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = clientHandoffHint
                                    ?: "微信网页授权仅支持扫码登录，点击下方按钮可一键存图并唤起微信扫一扫",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    saveQrAndOpenSocialApp(
                                        packages = listOf("com.tencent.mm"),
                                        preferredSchemeUri = "weixin://dl/scan",
                                        appLabel = "微信"
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("oauth_save_qr_open_wechat")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.QrCodeScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("存二维码并打开微信", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = {
                                    if (launchAllowlistedPackage(
                                            packages = listOf("com.tencent.mm"),
                                            preferredSchemeUri = "weixin://",
                                            appLabel = "微信"
                                        )
                                    ) {
                                        clientHandoffHint = "已打开微信，完成扫码授权后请切回本应用"
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("oauth_open_wechat_direct")
                            ) {
                                Text("打开微信", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            } else if (isQqAuth) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = clientHandoffHint ?: "点击页面快捷登录按钮可唤起 QQ 客户端；如显示二维码可一键存图打开 QQ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                saveQrAndOpenSocialApp(
                                    packages = listOf("com.tencent.mobileqq", "com.tencent.tim"),
                                    preferredSchemeUri = null,
                                    appLabel = "QQ"
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("oauth_save_qr_open_qq")
                        ) {
                            Text("打开 QQ", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            } else {
                clientHandoffHint?.let { hint ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            webError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "页面加载提示：$err（可点击右上角「网页登录」切换）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!cookiesPrepared) {
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
                                text = "正在准备 ${provider.displayName} 授权登录…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                val mobileChromeUa = KodOAuthDiscovery.sanitizeMobileChromeUserAgent(
                                    settings.userAgentString.orEmpty()
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    javaScriptCanOpenWindowsAutomatically = true
                                    setSupportMultipleWindows(true)
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    if (mobileChromeUa.isNotBlank()) {
                                        userAgentString = mobileChromeUa
                                    }
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
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.allowFileAccess = false
                                            settings.allowContentAccess = false
                                            if (mobileChromeUa.isNotBlank()) {
                                                settings.userAgentString = mobileChromeUa
                                            }
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    v: WebView?,
                                                    req: WebResourceRequest?
                                                ): Boolean {
                                                    val uri = req?.url
                                                    val raw = uri?.toString().orEmpty()
                                                    val scheme = uri?.scheme?.lowercase().orEmpty()
                                                    if (scheme == "http" || scheme == "https") {
                                                        val normalized = KodOAuthDiscovery.ensureQqMobileAuthorizeUrl(raw)
                                                        parent.loadUrl(normalized)
                                                    } else if (uri != null) {
                                                        tryLaunchAllowlistedNativeClient(raw, uri, parent)
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
                                        val uri = request?.url ?: return false
                                        val raw = uri.toString()
                                        val scheme = uri.scheme?.lowercase().orEmpty()
                                        if (scheme == "http" || scheme == "https") {
                                            val mobileQqUrl = KodOAuthDiscovery.ensureQqMobileAuthorizeUrl(raw)
                                            if (mobileQqUrl != raw) {
                                                view?.loadUrl(mobileQqUrl)
                                                return true
                                            }
                                            return false
                                        }
                                        return tryLaunchAllowlistedNativeClient(raw, uri, view)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val finishedUrl = url.orEmpty()
                                        if (finishedUrl.isBlank()) return
                                        currentPageUrl = finishedUrl
                                        webError = null
                                        if (KodOAuthDiscovery.isTargetServerHost(finishedUrl, normalizedServer)) {
                                            val lower = finishedUrl.lowercase()
                                            val isInitialUnauthedEntry = finishedUrl == entryUrl &&
                                                (lower.contains("user/bind/oauth") || lower.contains("#user/login"))
                                            if (!isInitialUnauthedEntry) {
                                                collectSessionAndCheck(finishedUrl, silent = true)
                                            }
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            webError = error?.description?.toString() ?: "网络连接异常"
                                        }
                                    }
                                }
                                webViewRef = this
                                loadUrl(entryUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Captures the visible OAuth QR page from WebView and saves it into `Pictures/KodNext` via
 * Android 10+ scoped MediaStore (zero storage permissions required), so users can immediately
 * select the QR code inside WeChat / QQ "扫一扫 → 相册".
 */
private fun captureWebViewQrToGallery(context: Context, webView: WebView, label: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val width = webView.width
    val height = webView.height
    if (width <= 0 || height <= 0) return false
    return runCatching {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        val fileName = "KodNext_${label}_QR_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/KodNext")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val insertedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        try {
            resolver.openOutputStream(insertedUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(insertedUri, publishValues, null, null)
            true
        } catch (t: Throwable) {
            resolver.delete(insertedUri, null, null)
            false
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(false)
}

private fun mergeCookieStrings(primary: String, secondary: String): String {
    val map = linkedMapOf<String, String>()
    listOf(primary, secondary).forEach { header ->
        header.split(";").forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (key.isNotBlank()) {
                    map[key] = value
                }
            }
        }
    }
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

private fun extractTokenFromCallbackUrl(url: String): String {
    if (url.isBlank()) return ""
    return when (val parsed = KodQrLoginParser.parse(url)) {
        is KodQrPayload.TokenLogin -> parsed.accessToken
        else -> ""
    }
}
