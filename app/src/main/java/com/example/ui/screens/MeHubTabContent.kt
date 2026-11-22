package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.BuildConfig
import com.example.data.KodFormatters
import com.example.model.KodAccount
import com.example.model.KodWorkspaceApp
import com.example.ui.components.KodUserAvatar
import com.example.ui.components.resolveWorkspaceAppVisual
import com.example.ui.theme.CloudAzure
import com.example.ui.theme.CloudAzureDark
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KodUiState

/**
 * KodBox-native 「我的」 Hub tab combining:
 * 1. Synced User Profile Hero + Storage Quota + Quick Account Center entry
 * 2. Administrator-exclusive 「后台管理控制台」 card (only for admin accounts)
 * 3. KodBox Workspace Apps strip (Project, KodIM, AI Doc, Drawio, MindMap, etc.)
 * 4. Multi-server switching & full App Settings / About entry
 */
@Composable
fun MeHubTabContent(
    state: KodUiState,
    imageLoader: ImageLoader,
    onOpenAccountCenter: () -> Unit,
    onOpenAdminConsole: () -> Unit,
    onOpenWebAdmin: () -> Unit,
    onLaunchWorkspaceApp: (KodWorkspaceApp) -> Unit,
    onOpenAppHub: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchAccount: (KodAccount) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    val account = state.activeAccount ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Profile & Quota Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onOpenAccountCenter)
                .testTag("me_account_center_hero")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(CloudAzureDark, CloudAzure)))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        KodUserAvatar(
                            avatarUrl = account.avatarUrl,
                            displayName = account.displayName,
                            imageLoader = imageLoader,
                            size = 60.dp,
                            containerColor = Color.White.copy(alpha = 0.24f),
                            contentColor = Color.White,
                            borderColor = Color.White.copy(alpha = 0.72f)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = account.displayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (account.isAdmin) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.22f)
                                ) {
                                    Text(
                                        text = account.roleLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (account.isAdmin) Color(0xFF1F1600) else Color.White,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${account.siteName} · ${account.hostLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = Color.White.copy(alpha = 0.86f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "进入账号中心",
                            tint = Color.White
                        )
                    }

                    // Quota progress inside hero card
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "云盘空间",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Text(
                                text = if (account.sizeMax > 0L) {
                                    "${KodFormatters.formatBytes(account.sizeUse)} / ${KodFormatters.formatBytes(account.sizeMax)}"
                                } else {
                                    "已用 ${KodFormatters.formatBytes(account.sizeUse)}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        if (account.sizeMax > 0L) {
                            LinearProgressIndicator(
                                progress = { account.quotaProgress },
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.24f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }
            }
        }

        // 2. Administrator-Only Console Card
        if (account.isAdmin) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onOpenAdminConsole)
                    .testTag("me_admin_console_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AdminPanelSettings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "后台管理控制台",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = "管理员专属",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "成员、部门、存储池、插件开关与完整网页后台",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenWebAdmin,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("网页", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // 3. KodBox Workspace Plugins Strip
        val apps = state.visibleWorkspaceApps
        if (apps.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("插件工作台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "全部 (${apps.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenAppHub)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(apps.take(8), key = { it.id }) { app ->
                            val (icon, tint) = resolveWorkspaceAppVisual(app.id)
                            Surface(
                                onClick = { onLaunchWorkspaceApp(app) },
                                shape = RoundedCornerShape(14.dp),
                                color = tint.copy(alpha = 0.10f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .width(78.dp)
                                        .padding(vertical = 10.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                                    Text(
                                        text = app.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Unified Entries Card (Account Center / App Hub / Settings & About)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column {
                ListItem(
                    headlineContent = { Text("账号中心与安全") },
                    supportingContent = { Text("个人资料编辑、修改密码、登录设备与操作日志") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.ManageAccounts,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier
                        .clickable(onClick = onOpenAccountCenter)
                        .testTag("me_open_account_center")
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("左上角快捷插件设置") },
                    supportingContent = {
                        val pinnedName = state.primaryTopLeftApp?.title ?: "项目"
                        Text("当前置顶：$pinnedName · 支持自定义任意服务端插件")
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenAppHub)
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("设置、服务器管理与关于") },
                    supportingContent = {
                        Text("主题外观 · 缓存 ${KodFormatters.formatBytes(state.cacheSizeBytes)} · v${BuildConfig.VERSION_NAME}")
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier
                        .clickable(onClick = onOpenSettings)
                        .testTag("me_open_settings")
                )
            }
        }

        // 5. Quick Multi-Server Switcher
        if (state.accounts.size > 1) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("切换服务器账号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            text = "添加",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onAddAccount)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    state.accounts.filter { it.id != account.id }.forEach { other ->
                        ListItem(
                            headlineContent = { Text(other.displayName) },
                            supportingContent = {
                                Text(other.hostLabel, style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFamily)
                            },
                            leadingContent = {
                                KodUserAvatar(
                                    avatarUrl = other.avatarUrl,
                                    displayName = other.displayName,
                                    imageLoader = imageLoader,
                                    size = 34.dp
                                )
                            },
                            modifier = Modifier.clickable { onSwitchAccount(other) }
                        )
                    }
                }
            }
        }
    }
}
