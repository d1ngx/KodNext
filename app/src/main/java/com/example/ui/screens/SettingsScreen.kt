package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Extension
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
import com.example.model.ThemePreference
import com.example.ui.components.KodUserAvatar
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.viewmodel.KodUiState

/**
 * Focused App Settings screen (应用设置):
 * - Server connection status & manual refresh (sync avatar, quota, plugins)
 * - Appearance, browsing preferences & local cache cleanup
 * - Online preview plugins catalog & privacy declaration
 *
 * Account switching lives in MeTabContent -> AccountSwitcherDialog;
 * Account profile & sign-out live in AccountCenterScreen;
 * App info & diagnostics live in MeTabContent -> 关于.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: KodUiState,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onCheckConnection: () -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    onClearCache: () -> Unit
) {
    BackHandler(onBack = onBack)
    val account = state.activeAccount
    var showPluginsDialog by remember { mutableStateOf(false) }

    val activePlugins = remember(state.serverPlugins) {
        if (state.serverPlugins.isNotEmpty()) state.serverPlugins else KodPluginRegistry.builtInFallbackPlugins
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用设置") },
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
                                size = 46.dp,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = account.siteName.ifBlank { account.displayName },
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
                                    text = "· 服务端 v${account.serverVersion}",
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_check_connection_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("检测连接、同步头像与插件列表")
                        }
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
                            Text(
                                "显示以 . 开头的系统或配置文件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                            Text(
                                "在文件列表和网格视图中预览云端缩略图",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.preferences.showThumbnails,
                            onCheckedChange = { checked -> onUpdatePrefs { it.copy(showThumbnails = checked) } }
                        )
                    }

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("清除本地缓存") },
                        supportingContent = { Text("仅清理本地缩略图与临时预览缓存，不影响云端文件") },
                        leadingContent = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
                        trailingContent = {
                            Text(
                                text = KodFormatters.formatBytes(state.cacheSizeBytes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .clickable(onClick = onClearCache)
                            .testTag("settings_clear_cache_item")
                    )
                }
            }

            // Extensions & Privacy
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = "扩展与隐私",
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
                }
            }

            // Quiet version caption at bottom
            Text(
                text = "KodNext v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 4.dp)
            )
        }
    }

    if (showPluginsDialog) {
        AlertDialog(
            onDismissRequest = { showPluginsDialog = false },
            title = { Text("已适配的网盘预览插件 (${activePlugins.size})") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (state.serverPlugins.isNotEmpty()) {
                            "以下插件已从您的网盘服务器动态同步，点击对应格式文件时将自动调用："
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
}
