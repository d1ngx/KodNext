package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.BuildConfig
import com.example.data.KodFormatters
import com.example.model.KodAccount
import com.example.ui.components.KodUserAvatar
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KodUiState

/**
 * Clean, zero-duplication "我的" (Me) tab:
 * - Top bar ("我的" + single QR scan icon) is rendered in Scaffold.topBar for seamless status-bar inset handling.
 * - Profile & Storage Card acts as the single authoritative entry to 账号中心 (no duplicate list row).
 * - Group 1 (同步与多账号): 自动备份与同步, 切换 / 添加账号, 更多网盘应用 (when available).
 * - Group 2 (管理与偏好): 后台管理 (admin only), Web 网页端, 应用设置 (single authoritative entry), 关于.
 */
@Composable
fun MeTabContent(
    account: KodAccount,
    state: KodUiState,
    imageLoader: ImageLoader,
    onOpenSettings: () -> Unit,
    onOpenAccountCenter: () -> Unit,
    onOpenSyncScreen: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    onOpenWebAdmin: () -> Unit,
    onOpenWebPortal: () -> Unit,
    onOpenAppHub: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAboutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .verticalScroll(rememberScrollState())
    ) {
        // Profile & Storage Quota Card — Single authoritative entry into 账号中心
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onOpenAccountCenter)
                .testTag("me_profile_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    KodUserAvatar(
                        avatarUrl = account.avatarUrl,
                        displayName = account.displayName,
                        imageLoader = imageLoader,
                        size = 62.dp
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = account.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (account.isAdmin) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    if (account.isAdmin) {
                                        Icon(
                                            imageVector = Icons.Filled.Shield,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Text(
                                        text = account.roleLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (account.isAdmin) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            Text(
                                text = account.siteName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "账号中心",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "进入账号中心",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Storage quota row + progress bar
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "存储空间",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (account.sizeMax > 0L) {
                                "${KodFormatters.formatBytes(account.sizeUse)} / ${KodFormatters.formatBytes(account.sizeMax)}"
                            } else {
                                "${KodFormatters.formatBytes(account.sizeUse)} / 不限制"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (account.sizeMax > 0L) account.quotaProgress.coerceAtLeast(0.03f) else 0.12f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        strokeCap = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }
        }

        // Group 1: Sync, Multi-Account Switcher, Workspace Apps
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val activeSyncCount = state.syncRules.count { it.enabled }
                MeActionRow(
                    icon = Icons.Filled.CloudUpload,
                    iconTint = Color(0xFF10B981),
                    title = "自动备份与同步",
                    trailingText = if (state.syncRules.isNotEmpty()) "$activeSyncCount/${state.syncRules.size} 规则" else "双向同步",
                    onClick = onOpenSyncScreen,
                    tag = "me_row_sync"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                MeActionRow(
                    icon = Icons.Filled.SwitchAccount,
                    iconTint = Color(0xFF0EA5E9),
                    title = "切换 / 添加账号",
                    trailingText = if (state.accounts.size > 1) "${state.accounts.size} 个站点" else account.hostLabel,
                    onClick = onOpenAccountSwitcher,
                    tag = "me_row_switch_account"
                )
                if (state.visibleWorkspaceApps.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    MeActionRow(
                        icon = Icons.Filled.Apps,
                        iconTint = Color(0xFF4F46E5),
                        title = "更多网盘应用",
                        trailingText = "${state.visibleWorkspaceApps.size} 个插件",
                        onClick = onOpenAppHub,
                        tag = "me_row_workspace_apps"
                    )
                }
            }
        }

        // Group 2: Admin Portal, Web Portal, Settings, About
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (account.isAdmin) {
                    MeActionRow(
                        icon = Icons.Filled.AdminPanelSettings,
                        iconTint = Color(0xFF2563EB),
                        title = "后台管理",
                        trailingText = "管理员",
                        onClick = onOpenWebAdmin,
                        tag = "me_row_admin"
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
                MeActionRow(
                    icon = Icons.Filled.Language,
                    iconTint = Color(0xFF0284C7),
                    title = "Web 页面",
                    trailingText = account.hostLabel,
                    onClick = onOpenWebPortal,
                    tag = "me_row_web_portal"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                MeActionRow(
                    icon = Icons.Filled.Tune,
                    iconTint = Color(0xFF7C3AED),
                    title = "应用设置",
                    trailingText = if (state.cacheSizeBytes > 0L) "缓存 ${KodFormatters.formatBytes(state.cacheSizeBytes)}" else "外观与插件",
                    onClick = onOpenSettings,
                    tag = "me_row_settings"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                MeActionRow(
                    icon = Icons.Filled.Info,
                    iconTint = Color(0xFF0EA5E9),
                    title = "关于",
                    trailingText = "v${BuildConfig.VERSION_NAME}",
                    onClick = { showAboutDialog = true },
                    tag = "me_row_about"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showAboutDialog) {
        val diagText = buildString {
            appendLine("应用：KodNext v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("当前站点：${account.siteName}")
            appendLine("服务器地址：${account.serverUrl}")
            if (account.serverVersion.isNotBlank()) appendLine("服务端版本：${account.serverVersion}")
            appendLine("登录账号：${account.displayName} (@${account.username})")
        }.trim()

        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于 KodNext") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "现代化的云端网盘安卓客户端，支持双向同步、README 自动预览、多插件在线预览、外链协作与扫码登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    Text(
                        text = "客户端版本：v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "当前站点：${account.siteName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = account.serverUrl,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (account.serverVersion.isNotBlank()) {
                        Text(
                            text = "服务端版本：${account.serverVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onCopyDiagnostics(diagText)
                        showAboutDialog = false
                    }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制诊断信息")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }
}

@Composable
private fun MeActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    trailingText: String = "",
    onClick: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailingText.isNotBlank()) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
    }
}
