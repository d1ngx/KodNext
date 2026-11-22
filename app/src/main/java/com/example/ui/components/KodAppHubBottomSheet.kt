package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HelpCenter
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KodAppHubBottomSheet(
    apps: List<KodWorkspaceApp>,
    imageLoader: ImageLoader,
    onLaunchApp: (KodWorkspaceApp) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = "扩展应用",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "已从服务器自动识别并同步登录态的网盘插件应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (apps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前服务器暂无可打开的扩展应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(apps, key = { it.id }) { app ->
                        val (fallbackIcon, tint) = resolveWorkspaceAppVisual(app.id)
                        Card(
                            onClick = { onLaunchApp(app) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.testTag("app_hub_card_${app.id.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(tint.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (app.iconUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = app.iconUrl,
                                                imageLoader = imageLoader,
                                                contentDescription = app.title,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = fallbackIcon,
                                                contentDescription = app.title,
                                                tint = tint,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    if (app.isAdminOnly) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                text = "管理员",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = app.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (app.description.isNotBlank()) {
                                    Text(
                                        text = app.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
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
}
