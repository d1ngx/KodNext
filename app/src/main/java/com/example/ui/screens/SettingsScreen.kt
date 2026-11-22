package com.example.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.BuildConfig
import com.example.data.KodFormatters
import com.example.data.KodPluginRegistry
import com.example.model.AppPreferences
import com.example.model.KodAccount
import com.example.model.ThemePreference
import com.example.ui.components.ConfirmDangerDialog
import com.example.ui.components.KodUserAvatar
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.viewmodel.KodUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: KodUiState,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onCheckConnection: () -> Unit,
    onAddAccount: () -> Unit,
    onSwitchAccount: (KodAccount) -> Unit,
    onLogoutAccount: (KodAccount) -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    onClearCache: () -> Unit,
    onCopyDiagnostics: (String) -> Unit
) {
    BackHandler(onBack = onBack)
    val account = state.activeAccount
    var accountPendingRemoval by remember { mutableStateOf<KodAccount?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showPluginsDialog by remember { mutableStateOf(false) }

    val activePlugins = remember(state.serverPlugins) {
        if (state.serverPlugins.isNotEmpty()) state.serverPlugins else KodPluginRegistry.builtInFallbackPlugins
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置与服务器管理") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (account != null) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            KodUserAvatar(
                                avatarUrl = account.avatarUrl,
                                displayName = account.displayName,
                                imageLoader = imageLoader,
                                size = 48.dp,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = account.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = account.serverUrl,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (account.isHttps) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                contentDescription = null,
                                tint = if (account.isHttps) StatusSuccess else StatusWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (account.isHttps) "HTTPS 加密连接" else "HTTP 局域网连接",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (account.isHttps) StatusSuccess else StatusWarning
                            )
                            if (account.serverVersion.isNotBlank()) {
                                Text(
                                    text = "· KodBox v${account.serverVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (account.sizeMax > 0L) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("空间配额", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        "${KodFormatters.formatBytes(account.sizeUse)} / ${KodFormatters.formatBytes(account.sizeMax)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { account.quotaProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }

                        if (!state.serverHealthMessage.isNullOrBlank()) {
                            Text(
                                text = state.serverHealthMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FilledTonalButton(
                            onClick = onCheckConnection,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("检测连接、同步头像与插件列表")
                        }
                    }
                }
            }

            // Multi-Account Switcher
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("多服务器账号管理", style = MaterialTheme.typography.titleMedium)
                        FilledTonalButton(onClick = onAddAccount) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("添加服务器")
                        }
                    }

                    state.accounts.forEach { acc ->
                        val isCurrent = acc.id == account?.id
                        ListItem(
                            headlineContent = {
                                Text(
                                    acc.displayName,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            supportingContent = {
                                Text(
                                    acc.hostLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily
                                )
                            },
                            leadingContent = {
                                KodUserAvatar(
                                    avatarUrl = acc.avatarUrl,
                                    displayName = acc.displayName,
                                    imageLoader = imageLoader,
                                    size = 38.dp,
                                    containerColor = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (isCurrent) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isCurrent) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = "当前账号",
                                            tint = StatusSuccess,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { accountPendingRemoval = acc }) {
                                        Icon(
                                            Icons.Filled.DeleteOutline,
                                            contentDescription = "移除账号",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { if (!isCurrent) onSwitchAccount(acc) }
                        )
                    }
                }
            }

            // Appearance & Display Preferences
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("外观与浏览偏好", style = MaterialTheme.typography.titleMedium)

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemePreference.entries.forEachIndexed { index, pref ->
                            SegmentedButton(
                                selected = state.preferences.themePreference == pref,
                                onClick = { onSetTheme(pref) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemePreference.entries.size)
                            ) {
                                Text(pref.label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("显示隐藏文件", style = MaterialTheme.typography.bodyLarge)
                            Text("显示以 . 开头的系统或配置文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.preferences.showHiddenFiles,
                            onCheckedChange = { checked -> onUpdatePrefs { it.copy(showHiddenFiles = checked) } }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("加载图片缩略图", style = MaterialTheme.typography.bodyLarge)
                            Text("在文件列表和网格视图中预览云端缩略图", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.preferences.showThumbnails,
                            onCheckedChange = { checked -> onUpdatePrefs { it.copy(showThumbnails = checked) } }
                        )
                    }

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("清理本地缩略图缓存") },
                        supportingContent = {
                            Text(
                                "当前已占用 ${KodFormatters.formatBytes(state.cacheSizeBytes)}，清理不影响云端文件"
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onClearCache)
                    )
                }
            }

            // About & Diagnostics Section
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = "关于与扩展支持",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                    )

                    ListItem(
                        headlineContent = { Text("在线预览插件支持") },
                        supportingContent = {
                            val sourceLabel = if (state.serverPlugins.isNotEmpty()) "已从服务端自动发现" else "内置兼容目录"
                            Text("$sourceLabel ${activePlugins.size} 个插件（Office / DWG / EPUB / Drawio / 思维导图等）")
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier
                            .clickable { showPluginsDialog = true }
                            .testTag("settings_plugins_item")
                    )

                    ListItem(
                        headlineContent = { Text("隐私与权限说明") },
                        supportingContent = {
                            Text("零宽泛存储权限，仅通过系统文件选择器访问您主动选取的目录与文件")
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                tint = StatusSuccess
                            )
                        }
                    )

                    ListItem(
                        headlineContent = { Text("关于 KodNext") },
                        supportingContent = {
                            val serverVer = account?.serverVersion?.takeIf { it.isNotBlank() }?.let { " · 服务端 v$it" }.orEmpty()
                            Text("版本 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})$serverVer")
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier
                            .clickable { showAboutDialog = true }
                            .testTag("settings_about_item")
                    )
                }
            }
        }
    }

    accountPendingRemoval?.let { target ->
        ConfirmDangerDialog(
            title = "移除服务器账号",
            message = "确定退出并移除「${target.displayName} (${target.hostLabel})」吗？本地保存的登录凭据和同步会话将被清除，不会删除云端数据。",
            confirmLabel = "确认移除",
            onConfirm = {
                val acc = target
                accountPendingRemoval = null
                onLogoutAccount(acc)
            },
            onDismiss = { accountPendingRemoval = null }
        )
    }

    if (showPluginsDialog) {
        AlertDialog(
            onDismissRequest = { showPluginsDialog = false },
            title = { Text("已适配的 KodBox 预览插件 (${activePlugins.size})") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (state.serverPlugins.isNotEmpty()) {
                            "以下插件已从您的 KodBox 服务器动态同步，点击对应格式文件时将自动调用："
                        } else {
                            "当前展示内置兼容插件目录，检测到服务器插件后将自动更新："
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    activePlugins.forEach { plugin ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${plugin.title} (${plugin.name})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = plugin.extensions.sorted().take(16).joinToString(", ") { ".$it" } +
                                        if (plugin.extensions.size > 16) " 等 ${plugin.extensions.size} 种格式" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPluginsDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }

    if (showAboutDialog) {
        val diagnosticsText = buildString {
            appendLine("应用名称: KodNext")
            appendLine("应用版本: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
            appendLine("应用包名: ${BuildConfig.APPLICATION_ID}")
            appendLine("Android 系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (account != null) {
                appendLine("KodBox 站点: ${account.siteName} (${account.hostLabel})")
                appendLine("KodBox 版本: ${account.serverVersion.ifBlank { "未知" }}")
            }
            appendLine("已发现插件 (${activePlugins.size}): ${activePlugins.joinToString(", ") { it.name }}")
        }.trim()

        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("关于 KodNext") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "KodNext 是一款面向私有部署 KodBox 网盘的原生安卓客户端，结合清爽高效的移动交互体验与完整的 KodBox 空间、外链、协作、双向同步及多格式在线插件能力。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("版本：v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelMedium)
                            Text("包名：${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFamily)
                            Text("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", style = MaterialTheme.typography.labelSmall)
                            if (account != null) {
                                Text(
                                    "当前服务端：KodBox ${account.serverVersion.ifBlank { "未知版本" }}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Material 3 原生", "Media3 音视频流", "OnlyOffice/CAD/EPUB", "零存储权限").forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onCopyDiagnostics(diagnosticsText)
                        showAboutDialog = false
                    }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制诊断信息")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}
