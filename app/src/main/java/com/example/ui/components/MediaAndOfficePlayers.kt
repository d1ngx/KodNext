package com.example.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.data.KodBoxApiClient
import com.example.data.KodFormatters
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodPluginApp
import com.example.ui.theme.FileAudioColor
import java.net.URI
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
@Composable
fun KodVideoPlayerView(
    streamUrl: String,
    mediaOkHttpClient: OkHttpClient,
    onDownloadFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var playbackError by remember(streamUrl) { mutableStateOf<String?>(null) }
    var isBuffering by remember(streamUrl) { mutableStateOf(true) }

    val exoPlayer = remember(streamUrl, mediaOkHttpClient) {
        val dataSourceFactory = OkHttpDataSource.Factory(mediaOkHttpClient)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs for smooth WAN/NAS streaming
                60_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs
                3_000   // bufferForPlaybackAfterRebufferMs
            )
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    playbackError = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playbackError = error.localizedMessage ?: "视频流解码或网络加载失败"
            }
        }
        exoPlayer.addListener(listener)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF06090F))
            .testTag("video_player_container"),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering && playbackError == null) {
            CircularProgressIndicator(color = Color.White)
        }

        playbackError?.let { err ->
            Surface(
                color = Color(0xE6111827),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFFCA5A5),
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "在线播放失败",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                playbackError = null
                                isBuffering = true
                                exoPlayer.prepare()
                                exoPlayer.play()
                            }
                        ) {
                            Text("重试播放", color = Color.White)
                        }
                        Button(onClick = onDownloadFallback) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载本地播放")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun KodAudioPlayerView(
    item: KodFileItem,
    streamUrl: String,
    mediaOkHttpClient: OkHttpClient,
    onDownloadFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isPlaying by remember(streamUrl) { mutableStateOf(false) }
    var isBuffering by remember(streamUrl) { mutableStateOf(true) }
    var currentPosMs by remember(streamUrl) { mutableLongStateOf(0L) }
    var durationMs by remember(streamUrl) { mutableLongStateOf(0L) }
    var playbackSpeed by remember(streamUrl) { mutableFloatStateOf(1.0f) }
    var playbackError by remember(streamUrl) { mutableStateOf<String?>(null) }

    val exoPlayer = remember(streamUrl, mediaOkHttpClient) {
        val dataSourceFactory = OkHttpDataSource.Factory(mediaOkHttpClient)
        // Generous buffer & audio focus management to prevent FLAC/lossless audio stuttering on NAS/WAN
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,
                90_000,
                1_200,
                2_500
            )
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    playbackError = null
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                isPlaying = false
                playbackError = error.localizedMessage ?: "音频流加载失败"
            }
        }
        exoPlayer.addListener(listener)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val d = exoPlayer.duration
            if (d > 0L) durationMs = d
            delay(350L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .testTag("audio_player_container"),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(FileAudioColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AudioFile,
                        contentDescription = null,
                        tint = FileAudioColor,
                        modifier = Modifier.size(52.dp)
                    )
                }

                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "${item.normalizedExt.uppercase().ifBlank { "AUDIO" }} · ${KodFormatters.formatBytes(item.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                playbackError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        playbackError = null
                                        isBuffering = true
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    }
                                ) {
                                    Text("重试")
                                }
                                Button(onClick = onDownloadFallback) {
                                    Text("下载播放")
                                }
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    val safeDuration = durationMs.coerceAtLeast(1L)
                    Slider(
                        value = (currentPosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            val target = (fraction * safeDuration).toLong()
                            currentPosMs = target
                            exoPlayer.seekTo(target)
                        },
                        enabled = durationMs > 0L,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatMediaTime(currentPosMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatMediaTime(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    IconButton(
                        onClick = {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Replay10, contentDescription = "快退10秒", modifier = Modifier.size(28.dp))
                    }

                    FilledIconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                    exoPlayer.seekTo(0L)
                                }
                                exoPlayer.play()
                            }
                        },
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("audio_play_pause_button")
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            val maxPos = if (durationMs > 0L) durationMs else Long.MAX_VALUE
                            exoPlayer.seekTo((exoPlayer.currentPosition + 10_000L).coerceAtMost(maxPos))
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Forward10, contentDescription = "快进10秒", modifier = Modifier.size(28.dp))
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        FilterChip(
                            selected = playbackSpeed == speed,
                            onClick = {
                                playbackSpeed = speed
                                exoPlayer.playbackParameters = PlaybackParameters(speed)
                            },
                            label = { Text("${speed}x", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Detects whether a URL is attempting to navigate into the KodBox H5 Web SPA
 * (e.g. root `/`, `#explorer`, `#desktop`, `user/login`, `user/view/index`) rather than
 * staying inside an isolated document/media plugin frame.
 */
fun isKodBoxMainSpaUrl(url: String, serverUrl: String): Boolean {
    if (url.isBlank() || serverUrl.isBlank()) return false
    return runCatching {
        val target = URI(url)
        val server = URI(serverUrl)
        val targetHost = target.host?.lowercase().orEmpty()
        val serverHost = server.host?.lowercase().orEmpty()
        if (targetHost.isBlank() || targetHost != serverHost) return false

        val path = target.path.orEmpty().lowercase()
        val query = target.rawQuery.orEmpty().lowercase()
        val fragment = target.fragment.orEmpty().lowercase()

        // Explicit H5 SPA hash routes
        if (fragment.startsWith("explorer") ||
            fragment.startsWith("desktop") ||
            fragment.startsWith("setting") ||
            fragment.startsWith("admin") ||
            fragment.startsWith("user")
        ) {
            return true
        }

        // Explicit KodBox web login or explorer shell endpoints
        if (query.startsWith("user/login") ||
            query.contains("&user/login") ||
            query.startsWith("user/view") && !query.contains("plugins") ||
            query.startsWith("explorer/index/index") ||
            query.startsWith("desktop")
        ) {
            return true
        }

        // Plugin & static plugin asset routes are always permitted
        if (query.contains("plugin/") || path.contains("/plugins/") || path.contains("/static/")) {
            return false
        }

        // Direct file output / share file preview streams are permitted
        if (query.contains("explorer/index/fileout") || query.contains("explorer/share/file")) {
            return false
        }

        // Root `/` or `/index.php` without a plugin route loads the full KodBox H5 web app
        val isRootPath = path.isEmpty() || path == "/" || path.endsWith("/index.php")
        isRootPath && query.isEmpty()
    }.getOrDefault(false)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KodPluginWebViewPreview(
    account: KodAccount,
    availablePlugins: List<KodPluginApp>,
    selectedPlugin: KodPluginApp,
    pluginUrl: String,
    onSelectPlugin: (KodPluginApp) -> Unit,
    onDownloadFallback: () -> Unit,
    onSessionExpired: (String) -> Unit = {},
    onReloginClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var loadProgress by remember(pluginUrl) { mutableIntStateOf(0) }
    var webError by remember(pluginUrl) { mutableStateOf<String?>(null) }
    var interceptedSpaUrl by remember(pluginUrl) { mutableStateOf<String?>(null) }
    var interceptedLoginRedirect by remember(pluginUrl) { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Synchronize KodBox session cookies into Android WebView CookieManager before loading plugin
    LaunchedEffect(account.serverUrl, account.cookiesHeader) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (account.cookiesHeader.isNotBlank()) {
            account.cookiesHeader.split(";").forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.contains("=")) {
                    cookieManager.setCookie(account.serverUrl, "$trimmed; Path=/")
                }
            }
            cookieManager.flush()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("office_plugin_webview_container")
    ) {
        // Horizontal scrollable plugin switcher strip (OnlyOffice / CAD / EPUB / Drawio / MindMap / Refresh)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(availablePlugins, key = { it.name }) { plugin ->
                        FilterChip(
                            selected = plugin.name.equals(selectedPlugin.name, ignoreCase = true),
                            onClick = { onSelectPlugin(plugin) },
                            label = { Text(plugin.title, maxLines = 1) },
                            modifier = Modifier.testTag("plugin_chip_${plugin.name.lowercase()}")
                        )
                    }
                }
                IconButton(
                    onClick = {
                        webError = null
                        interceptedSpaUrl = null
                        interceptedLoginRedirect = false
                        reloadNonce += 1
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新插件视图")
                }
            }
        }

        if (loadProgress in 1..99 && interceptedSpaUrl == null) {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "插件加载异常：$err",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onDownloadFallback) {
                        Text("本地打开")
                    }
                }
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
                            userAgentString = KodBoxApiClient.buildAndroidUserAgent()
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
                                val targetUrl = request?.url?.toString().orEmpty()
                                val scheme = request?.url?.scheme?.lowercase().orEmpty()
                                if (scheme != "http" && scheme != "https") return true
                                if (request?.isForMainFrame == true) {
                                    if (KodBoxApiClient.isWebLoginRedirectUrl(targetUrl, account.serverUrl)) {
                                        interceptedLoginRedirect = true
                                        interceptedSpaUrl = targetUrl
                                        onSessionExpired("登录凭证已失效，无法加载在线预览，请重新登录")
                                        return true
                                    }
                                    if (isKodBoxMainSpaUrl(targetUrl, account.serverUrl)) {
                                        interceptedSpaUrl = targetUrl
                                        return true
                                    }
                                }
                                return false
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?
                            ) {
                                super.onPageStarted(view, url, favicon)
                                if (!url.isNullOrBlank()) {
                                    if (KodBoxApiClient.isWebLoginRedirectUrl(url, account.serverUrl)) {
                                        view?.stopLoading()
                                        interceptedLoginRedirect = true
                                        interceptedSpaUrl = url
                                        onSessionExpired("登录凭证已失效，无法加载在线预览，请重新登录")
                                    } else if (isKodBoxMainSpaUrl(url, account.serverUrl)) {
                                        view?.stopLoading()
                                        interceptedSpaUrl = url
                                    }
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
                        loadUrl(pluginUrl, headers)
                    }
                },
                update = { webView ->
                    webViewRef = webView
                    val targetTag = "$pluginUrl#$reloadNonce"
                    if (webView.tag != targetTag) {
                        webView.tag = targetTag
                        webError = null
                        interceptedSpaUrl = null
                        interceptedLoginRedirect = false
                        val headers = buildMap {
                            if (account.csrfToken.isNotBlank()) put("CSRF-Token", account.csrfToken)
                        }
                        webView.loadUrl(pluginUrl, headers)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Native guard overlay when a KodBox plugin attempts to redirect to the H5 web shell
            if (interceptedSpaUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp)
                        .testTag("h5_spa_intercept_guard"),
                    contentAlignment = Alignment.Center
                ) {
                    ElevatedCard(
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (interceptedLoginRedirect) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (interceptedLoginRedirect) Icons.Filled.LockReset else Icons.Filled.Shield,
                                    contentDescription = null,
                                    tint = if (interceptedLoginRedirect) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Text(
                                text = if (interceptedLoginRedirect) "登录凭证已失效" else "已拦截网页端跳转",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (interceptedLoginRedirect) {
                                    "当前账号登录状态已过期，服务器拒绝了「${selectedPlugin.title}」预览请求，请重新登录。"
                                } else {
                                    "「${selectedPlugin.title}」插件当前未就绪或尝试跳转至网盘 H5 网页端界面，已自动拦截以保持原生体验。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (interceptedLoginRedirect) {
                                    Button(
                                        onClick = onReloginClick,
                                        modifier = Modifier.testTag("plugin_relogin_button")
                                    ) {
                                        Text("立即重新登录")
                                    }
                                } else {
                                    val nextPlugin = availablePlugins.firstOrNull {
                                        !it.name.equals(selectedPlugin.name, ignoreCase = true)
                                    }
                                    if (nextPlugin != null) {
                                        OutlinedButton(onClick = { onSelectPlugin(nextPlugin) }) {
                                            Text("切换 ${nextPlugin.title}")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                interceptedSpaUrl = null
                                                reloadNonce += 1
                                            }
                                        ) {
                                            Text("重试加载")
                                        }
                                    }
                                    Button(onClick = onDownloadFallback) {
                                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("下载本地打开")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMediaTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
