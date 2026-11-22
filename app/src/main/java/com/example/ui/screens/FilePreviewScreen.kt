package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.data.KodBoxApiClient
import com.example.data.KodFormatters
import com.example.data.KodPluginRegistry
import com.example.model.KodAccount
import com.example.model.KodPluginApp
import com.example.ui.components.KodAudioPlayerView
import com.example.ui.components.KodPluginWebViewPreview
import com.example.ui.components.KodVideoPlayerView
import com.example.ui.components.resolveFileVisual
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.PreviewState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    account: KodAccount,
    apiClient: KodBoxApiClient,
    serverPlugins: List<KodPluginApp>,
    preview: PreviewState,
    fullPreviewUrl: String,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUpdateText: (String) -> Unit,
    onToggleEditMode: (Boolean) -> Unit,
    onSaveText: () -> Unit
) {
    BackHandler(onBack = onBack)

    val item = preview.item
    val matchedPlugins = remember(item.normalizedExt, serverPlugins) {
        KodPluginRegistry.matchPluginsForExtension(item.normalizedExt, serverPlugins)
    }
    var selectedPluginName by remember(item.path, matchedPlugins) {
        mutableStateOf(matchedPlugins.firstOrNull()?.name.orEmpty())
    }
    val selectedPlugin = matchedPlugins.firstOrNull {
        it.name.equals(selectedPluginName, ignoreCase = true)
    } ?: matchedPlugins.firstOrNull()

    // For images (e.g., SVG) or editable text (e.g., CSV), default to fast native viewer
    // but allow 1-tap toggle to KodBox plugin (e.g., SVG Editor / OnlyOffice / Photopea).
    var preferPluginMode by remember(item.path) { mutableStateOf(false) }

    val canOfferOptionalPlugin = (item.isImage || item.isEditableText) && matchedPlugins.isNotEmpty()
    val usePluginViewer = when {
        item.isVideo || item.isAudio -> false
        item.isImage || item.isEditableText -> preferPluginMode && selectedPlugin != null
        else -> selectedPlugin != null
    }
    val isDarkCanvas = (item.isImage && !usePluginViewer) || item.isVideo

    val mediaOkHttpClient = remember(account.id, account.cookiesHeader, account.csrfToken) {
        apiClient.createAuthenticatedMediaClient(account)
    }
    val mediaStreamUrl = remember(account.id, item.path) {
        apiClient.buildMediaStreamUrl(account, item)
    }

    Scaffold(
        containerColor = if (isDarkCanvas) Color(0xFF0A0F16) else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = item.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        val modeLabel = when {
                            usePluginViewer && selectedPlugin != null -> selectedPlugin.title
                            item.isVideo -> "在线视频流"
                            item.isAudio -> "在线音频流"
                            item.isImage -> "高清图片预览"
                            item.isEditableText -> "原生代码 / 文本"
                            else -> KodFormatters.formatTimestamp(item.modifyTime)
                        }
                        Text(
                            text = "${KodFormatters.formatBytes(item.size)} · $modeLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDarkCanvas) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("preview_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (canOfferOptionalPlugin) {
                        IconButton(
                            onClick = { preferPluginMode = !preferPluginMode },
                            modifier = Modifier.testTag("preview_toggle_plugin_mode")
                        ) {
                            Icon(
                                imageVector = if (preferPluginMode) Icons.Filled.Image else Icons.Filled.Extension,
                                contentDescription = if (preferPluginMode) "切换原生视图" else "使用 KodBox 插件打开"
                            )
                        }
                    }
                    if (item.isEditableText && !usePluginViewer && !preview.isLoadingText && preview.errorMessage == null) {
                        if (preview.isEditingText) {
                            IconButton(
                                onClick = onSaveText,
                                enabled = !preview.isSavingText,
                                modifier = Modifier.testTag("preview_save_button")
                            ) {
                                if (preview.isSavingText) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Save, contentDescription = "保存到服务器")
                                }
                            }
                        } else {
                            IconButton(
                                onClick = { onToggleEditMode(true) },
                                modifier = Modifier.testTag("preview_edit_button")
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "在线编辑")
                            }
                        }
                    }
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (item.isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "收藏",
                            tint = if (item.isFav) Color(0xFFFBBF24) else if (isDarkCanvas) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "外链分享")
                    }
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = "下载到本地")
                    }
                },
                colors = if (isDarkCanvas) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xCC0A0F16),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            when {
                usePluginViewer && selectedPlugin != null -> {
                    val pluginUrl = remember(account.id, item.path, selectedPlugin.name, selectedPlugin.route) {
                        apiClient.buildPluginPreviewUrl(account, item, selectedPlugin)
                    }
                    KodPluginWebViewPreview(
                        account = account,
                        availablePlugins = matchedPlugins,
                        selectedPlugin = selectedPlugin,
                        pluginUrl = pluginUrl,
                        onSelectPlugin = { selectedPluginName = it.name },
                        onDownloadFallback = onDownload
                    )
                }

                item.isImage -> {
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                    scale = nextScale
                                    offset = if (nextScale > 1f) offset + pan else Offset.Zero
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = fullPreviewUrl,
                            imageLoader = imageLoader,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    }
                }

                item.isVideo -> {
                    KodVideoPlayerView(
                        streamUrl = mediaStreamUrl,
                        mediaOkHttpClient = mediaOkHttpClient,
                        onDownloadFallback = onDownload
                    )
                }

                item.isAudio -> {
                    KodAudioPlayerView(
                        item = item,
                        streamUrl = mediaStreamUrl,
                        mediaOkHttpClient = mediaOkHttpClient,
                        onDownloadFallback = onDownload
                    )
                }

                item.isEditableText -> {
                    when {
                        preview.isLoadingText -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        preview.errorMessage != null -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(preview.errorMessage, color = MaterialTheme.colorScheme.error)
                                Button(onClick = onDownload) {
                                    Text("下载后本地打开")
                                }
                            }
                        }
                        preview.isEditingText -> {
                            OutlinedTextField(
                                value = preview.textContent,
                                onValueChange = onUpdateText,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp)
                                    .testTag("text_editor_field")
                            )
                        }
                        else -> {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = preview.textContent.ifEmpty { "（空文件）" },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(16.dp)
                                )
                            }
                        }
                    }
                }

                else -> {
                    val visual = resolveFileVisual(item)
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(visual.tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = visual.icon,
                                contentDescription = null,
                                tint = visual.tint,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.normalizedExt.uppercase().ifBlank { "文件" }} · ${KodFormatters.formatBytes(item.size)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onDownload) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("下载并打开")
                            }
                            OutlinedButton(onClick = onShare) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("外链分享")
                            }
                        }
                    }
                }
            }
        }
    }
}
