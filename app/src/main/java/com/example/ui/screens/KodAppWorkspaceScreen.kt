package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.KodAccount
import com.example.model.KodWorkspaceApp
import java.net.URLEncoder

/**
 * Full-screen authenticated workspace host for interactive KodBox plugins such as
 * Project (项目管理 `#project`), KodIM (`?plugin/kodIM/`), AI Doc (`#aiDoc`),
 * HelpCenter, Drawio, MindMap, and full Web Admin Console (`#admin`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KodAppWorkspaceScreen(
    account: KodAccount,
    app: KodWorkspaceApp,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var loadProgress by remember(app.id) { mutableIntStateOf(0) }
    var webError by remember(app.id) { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val targetUrl = remember(account.id, app.launchUrl, account.accessToken, account.csrfToken) {
        appendAuthParamsIfNeeded(app.launchUrl, account)
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    LaunchedEffect(account.serverUrl, account.cookiesHeader) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (account.cookiesHeader.isNotBlank()) {
            account.cookiesHeader.split(";").forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.contains("=")) {
                    cm.setCookie(account.serverUrl, "$trimmed; Path=/")
                }
            }
            cm.flush()
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
                            text = "${account.siteName} · KodBox 插件工作台",
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回网盘")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            webError = null
                            webViewRef?.reload()
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.launchUrl)))
                            }
                        }
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = "浏览器打开")
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

            webError?.let { err ->
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
                                userAgentString =
                                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val scheme = request?.url?.scheme?.lowercase().orEmpty()
                                    return scheme != "http" && scheme != "https"
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    // Clean up redundant desktop outer menu chrome when running router-page plugins like #project
                                    if (app.isRouterPage && !app.id.equals("admin", ignoreCase = true)) {
                                        view?.evaluateJavascript(
                                            """
                                            (function(){
                                              var style = document.getElementById('kodnext-mobile-trim') || document.createElement('style');
                                              style.id = 'kodnext-mobile-trim';
                                              style.textContent = '.app-menu-left, .kod-header-left-menu { display: none !important; } .app-main { left: 0 !important; width: 100% !important; }';
                                              document.head && document.head.appendChild(style);
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        webError = error?.description?.toString() ?: "网络连接失败"
                                    }
                                }
                            }
                            webViewRef = this
                            val headers = buildMap {
                                if (account.csrfToken.isNotBlank()) put("CSRF-Token", account.csrfToken)
                            }
                            loadUrl(targetUrl, headers)
                        }
                    },
                    update = { wv ->
                        webViewRef = wv
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun appendAuthParamsIfNeeded(rawUrl: String, account: KodAccount): String {
    if (rawUrl.isBlank()) return account.serverUrl
    val hashIdx = rawUrl.indexOf('#')
    val basePart = if (hashIdx >= 0) rawUrl.substring(0, hashIdx) else rawUrl
    val hashPart = if (hashIdx >= 0) rawUrl.substring(hashIdx) else ""
    if (basePart.contains("accessToken=")) return rawUrl
    if (account.accessToken.isBlank()) return rawUrl
    val sep = if (basePart.contains("?")) "&" else "?"
    val encodedToken = URLEncoder.encode(account.accessToken, "UTF-8")
    return "$basePart${sep}accessToken=$encodedToken$hashPart"
}
