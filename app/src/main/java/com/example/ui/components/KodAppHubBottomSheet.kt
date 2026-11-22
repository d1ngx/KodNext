package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HelpCenter
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.model.KodWorkspaceApp

fun resolveWorkspaceAppVisual(appId: String): Pair<ImageVector, Color> {
    return when (appId.lowercase()) {
        "project" -> Icons.Filled.ViewKanban to Color(0xFF0074C8)
        "kodim" -> Icons.Filled.Forum to Color(0xFF0D9488)
        "aidoc" -> Icons.Filled.AutoAwesome to Color(0xFF7C3AED)
        "drawio", "minder", "excalidraw", "oemle5ledraw" -> Icons.Filled.AccountTree to Color(0xFF0284C7)
        "photopea", "svgeditor", "tuiimage", "polarr" -> Icons.Filled.Brush to Color(0xFFDB2777)
        "helpcenter" -> Icons.Filled.HelpCenter to Color(0xFF2563EB)
        "webconsole" -> Icons.Filled.Terminal to Color(0xFF334155)
        "adminer" -> Icons.Filled.Storage to Color(0xFFD97706)
        else -> Icons.Filled.DashboardCustomize to Color(0xFF0074C8)
    }
}

/**
 * Prominent top-left launcher pill placed directly in the mobile header for immediate
 * one-tap access to flagship KodBox plugins like `project` (项目管理) plus the full App Hub.
 */
@Composable
fun TopLeftPluginLauncherPill(
    primaryApp: KodWorkspaceApp?,
    onLaunchPrimary: (KodWorkspaceApp) -> Unit,
    onOpenAppHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (primaryApp != null) {
            val (icon, tint) = resolveWorkspaceAppVisual(primaryApp.id)
            Surface(
                onClick = { onLaunchPrimary(primaryApp) },
                shape = RoundedCornerShape(14.dp),
                color = tint.copy(alpha = 0.13f),
                border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
                modifier = Modifier
                    .height(38.dp)
                    .testTag("top_left_project_launcher")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = primaryApp.title,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = primaryApp.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = tint,
                        maxLines = 1
                    )
                }
            }
            Surface(
                onClick = onOpenAppHub,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("top_left_app_hub_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = "全部插件应用",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            Surface(
                onClick = onOpenAppHub,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .height(38.dp)
                    .testTag("top_left_app_hub_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = "插件应用",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "应用",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KodAppHubBottomSheet(
    apps: List<KodWorkspaceApp>,
    pinnedAppId: String,
    imageLoader: ImageLoader,
    onLaunchApp: (KodWorkspaceApp) -> Unit,
    onPinApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = "KodBox 应用与插件工作台",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "点击应用立即打开，点击图钉可将常用插件置顶到首页左上角",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (apps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前服务器尚未发现已启用的工作台插件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(apps, key = { it.id }) { app ->
                        val isPinned = app.id.equals(pinnedAppId, ignoreCase = true)
                        val (icon, tint) = resolveWorkspaceAppVisual(app.id)
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isPinned) {
                                    tint.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            ),
                            border = if (isPinned) BorderStroke(1.dp, tint.copy(alpha = 0.35f)) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onLaunchApp(app) }
                                .testTag("app_hub_item_${app.id.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(tint.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (app.iconUrl.isNotBlank() && !app.iconUrl.endsWith(".svg", ignoreCase = true)) {
                                            AsyncImage(
                                                model = app.iconUrl,
                                                imageLoader = imageLoader,
                                                contentDescription = app.title,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = tint,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onPinApp(app.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                            contentDescription = "置顶到左上角",
                                            tint = if (isPinned) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = app.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.description.ifBlank { app.id },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}
