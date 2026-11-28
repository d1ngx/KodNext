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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CorporateFare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.data.KodFormatters
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodFileTypeCategory
import com.example.model.KodSpace
import com.example.model.ShareSubTab
import com.example.ui.components.NextcloudFileListItem
import com.example.viewmodel.KodUiState

/**
 * Modern Home Dashboard (首页) replacing the left navigation drawer:
 * - Rounded Search Pill
 * - 5 Colorful File-Type Quick Icons (文档 / 图片 / 音乐 / 视频 / 压缩包)
 * - Dual Gradient Hero Space Cards (企业网盘 & 个人空间)
 * - 5-Shortcut Action Strip (外链共享 / 我的协作 / 与我协作 / 标签 / 回收站)
 * - Live Favorites (收藏夹) & Recent Documents (最近文档) feeds
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTabContent(
    account: KodAccount,
    state: KodUiState,
    imageLoader: ImageLoader,
    thumbnailUrlFor: (KodFileItem) -> String?,
    onRefresh: () -> Unit,
    onSelectFileType: (KodFileTypeCategory) -> Unit,
    onEnterSpace: (KodSpace) -> Unit,
    onOpenShared: (ShareSubTab) -> Unit,
    onItemClick: (KodFileItem) -> Unit,
    onItemShareClick: (KodFileItem) -> Unit,
    onItemMoreClick: (KodFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        PullToRefreshBox(
            isRefreshing = state.isLoadingHome,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                // 1. Five colorful file-type quick entry icons
                item(key = "home_file_types") {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            HomeCategoryIconItem(
                                label = "文档",
                                icon = Icons.Filled.Description,
                                gradientColors = listOf(Color(0xFF4CA8FF), Color(0xFF1D7AFC)),
                                onClick = { onSelectFileType(KodFileTypeCategory.DOC) },
                                tag = "home_cat_doc"
                            )
                            HomeCategoryIconItem(
                                label = "图片",
                                icon = Icons.Filled.Image,
                                gradientColors = listOf(Color(0xFFFF8882), Color(0xFFF05454)),
                                onClick = { onSelectFileType(KodFileTypeCategory.IMAGE) },
                                tag = "home_cat_image"
                            )
                            HomeCategoryIconItem(
                                label = "音乐",
                                icon = Icons.Filled.MusicNote,
                                gradientColors = listOf(Color(0xFFB678FF), Color(0xFF8B46F6)),
                                onClick = { onSelectFileType(KodFileTypeCategory.MUSIC) },
                                tag = "home_cat_music"
                            )
                            HomeCategoryIconItem(
                                label = "视频",
                                icon = Icons.Filled.Videocam,
                                gradientColors = listOf(Color(0xFFFFA552), Color(0xFFF97316)),
                                onClick = { onSelectFileType(KodFileTypeCategory.VIDEO) },
                                tag = "home_cat_video"
                            )
                            HomeCategoryIconItem(
                                label = "压缩包",
                                icon = Icons.Filled.FolderZip,
                                gradientColors = listOf(Color(0xFF4ADE80), Color(0xFF16A34A)),
                                onClick = { onSelectFileType(KodFileTypeCategory.ARCHIVE) },
                                tag = "home_cat_archive"
                            )
                        }
                    }
                }

                // 2. Dual Gradient Space Hero Cards (共享空间 & 个人空间)
                item(key = "home_space_cards") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val enterpriseSubtitle = if (account.departments.isNotEmpty()) {
                            "共 ${account.departments.size} 个部门 · ${account.siteName}"
                        } else {
                            "${account.siteName} 团队共享文件"
                        }
                        HomeGradientSpaceCard(
                            title = KodSpace.ENTERPRISE.label,
                            subtitle = enterpriseSubtitle,
                            watermarkIcon = Icons.Filled.CorporateFare,
                            gradientColors = listOf(Color(0xFF2196F3), Color(0xFF0277BD)),
                            onClick = { onEnterSpace(KodSpace.ENTERPRISE) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_card_enterprise")
                        )

                        val personalSubtitle = when {
                            account.sizeMax > 0L -> "已用 ${KodFormatters.formatBytes(account.sizeUse)}/${KodFormatters.formatBytes(account.sizeMax)}"
                            account.sizeUse > 0L -> "已用 ${KodFormatters.formatBytes(account.sizeUse)}/不限制"
                            else -> "我的个人云盘"
                        }
                        HomeGradientSpaceCard(
                            title = "个人空间",
                            subtitle = personalSubtitle,
                            watermarkIcon = Icons.Filled.Person,
                            gradientColors = listOf(Color(0xFF1AD1A5), Color(0xFF05A081)),
                            onClick = { onEnterSpace(KodSpace.PERSONAL) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_card_personal")
                        )
                    }
                }

                // 3. Five-shortcut rounded action card (外链共享 / 我的协作 / 与我协作 / 标签 / 回收站)
                item(key = "home_quick_shortcuts") {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            HomeShortcutAction(
                                label = "外链共享",
                                icon = Icons.Filled.Share,
                                tint = Color(0xFF00B894),
                                onClick = { onOpenShared(ShareSubTab.EXTERNAL_LINKS) },
                                tag = "home_shortcut_external"
                            )
                            HomeShortcutAction(
                                label = "我的协作",
                                icon = Icons.Filled.GroupWork,
                                tint = Color(0xFF0984E3),
                                onClick = { onOpenShared(ShareSubTab.MY_COLLAB) },
                                tag = "home_shortcut_my_collab"
                            )
                            HomeShortcutAction(
                                label = "与我协作",
                                icon = Icons.Filled.FolderShared,
                                tint = Color(0xFFF59E0B),
                                onClick = { onOpenShared(ShareSubTab.SHARED_WITH_ME) },
                                tag = "home_shortcut_shared_with_me"
                            )
                            HomeShortcutAction(
                                label = "标签",
                                icon = Icons.Filled.Bookmark,
                                tint = Color(0xFFF97316),
                                onClick = { onEnterSpace(KodSpace.TAGS) },
                                tag = "home_shortcut_tags"
                            )
                            HomeShortcutAction(
                                label = "回收站",
                                icon = Icons.Filled.Delete,
                                tint = Color(0xFF0EA5E9),
                                onClick = { onEnterSpace(KodSpace.RECYCLE_BIN) },
                                tag = "home_shortcut_recycle"
                            )
                        }
                    }
                }

                // 4. Favorites Section (收藏夹)
                item(key = "home_favorites_header") {
                    HomeSectionHeader(
                        title = "收藏夹",
                        onMoreClick = { onEnterSpace(KodSpace.FAVORITES) },
                        moreTag = "home_favorites_more"
                    )
                }

                val favPreview = state.homeFavorites.take(5)
                val linkSharedKeys = buildSet {
                    state.shares.forEach { s ->
                        if (s.sourceId.isNotBlank()) add("id:${s.sourceId}")
                        val p = s.path.trim().trimEnd('/')
                        if (p.isNotBlank()) add("path:$p")
                    }
                }
                val collabSharedKeys = buildSet {
                    state.collabShares.forEach { s ->
                        if (s.sourceId.isNotBlank()) add("id:${s.sourceId}")
                        val p = s.path.trim().trimEnd('/')
                        if (p.isNotBlank()) add("path:$p")
                    }
                }
                if (favPreview.isEmpty()) {
                    item(key = "home_favorites_empty") {
                        HomeEmptySectionHint(
                            text = if (state.isLoadingHome) "正在同步收藏夹…" else "暂无收藏内容，长按或点击文件菜单即可收藏"
                        )
                    }
                } else {
                    itemsIndexed(
                        items = favPreview,
                        key = { _, item -> "fav_${item.path}" }
                    ) { idx, rawItem ->
                        val normPathKey = "path:${rawItem.path.trim().trimEnd('/')}"
                        val isKnownLink = rawItem.isLinkShare ||
                            (rawItem.sourceId.isNotBlank() && "id:${rawItem.sourceId}" in linkSharedKeys) ||
                            (normPathKey in linkSharedKeys)
                        val isKnownCollab = rawItem.isCollabShare ||
                            (rawItem.sourceId.isNotBlank() && "id:${rawItem.sourceId}" in collabSharedKeys) ||
                            (normPathKey in collabSharedKeys)
                        val isKnownShared = rawItem.isShare || isKnownLink || isKnownCollab
                        val item = rawItem.copy(
                            isFav = true,
                            isShare = isKnownShared,
                            isLinkShare = isKnownLink,
                            isCollabShare = isKnownCollab
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                NextcloudFileListItem(
                                    item = item,
                                    thumbnailUrl = if (item.supportsThumbnail) thumbnailUrlFor(item) else null,
                                    imageLoader = imageLoader,
                                    showThumbnails = state.preferences.showThumbnails,
                                    isSelected = false,
                                    isSelectionMode = false,
                                    onClick = { onItemClick(item) },
                                    onLongClick = { onItemMoreClick(item) },
                                    onShareClick = if (item.canShare) { { onItemShareClick(item) } } else null,
                                    onMoreClick = { onItemMoreClick(item) }
                                )
                                if (idx < favPreview.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 76.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. Recent Documents Section (最近文档)
                item(key = "home_recent_header") {
                    HomeSectionHeader(
                        title = "最近文档",
                        onMoreClick = { onEnterSpace(KodSpace.RECENT) },
                        moreTag = "home_recent_more"
                    )
                }

                val recentPreview = state.homeRecentFiles.take(6)
                if (recentPreview.isEmpty()) {
                    item(key = "home_recent_empty") {
                        HomeEmptySectionHint(
                            text = if (state.isLoadingHome) "正在加载最近文档…" else "暂无最近访问的文档"
                        )
                    }
                } else {
                    itemsIndexed(
                        items = recentPreview,
                        key = { _, item -> "recent_${item.path}" }
                    ) { idx, rawItem ->
                        val normPathKey = "path:${rawItem.path.trim().trimEnd('/')}"
                        val isKnownLink = rawItem.isLinkShare ||
                            (rawItem.sourceId.isNotBlank() && "id:${rawItem.sourceId}" in linkSharedKeys) ||
                            (normPathKey in linkSharedKeys)
                        val isKnownCollab = rawItem.isCollabShare ||
                            (rawItem.sourceId.isNotBlank() && "id:${rawItem.sourceId}" in collabSharedKeys) ||
                            (normPathKey in collabSharedKeys)
                        val isKnownShared = rawItem.isShare || isKnownLink || isKnownCollab
                        val item = if (
                            isKnownShared != rawItem.isShare ||
                            isKnownLink != rawItem.isLinkShare ||
                            isKnownCollab != rawItem.isCollabShare
                        ) {
                            rawItem.copy(
                                isShare = isKnownShared,
                                isLinkShare = isKnownLink,
                                isCollabShare = isKnownCollab
                            )
                        } else rawItem
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                NextcloudFileListItem(
                                    item = item,
                                    thumbnailUrl = if (item.supportsThumbnail) thumbnailUrlFor(item) else null,
                                    imageLoader = imageLoader,
                                    showThumbnails = state.preferences.showThumbnails,
                                    isSelected = false,
                                    isSelectionMode = false,
                                    onClick = { onItemClick(item) },
                                    onLongClick = { onItemMoreClick(item) },
                                    onShareClick = if (item.canShare) { { onItemShareClick(item) } } else null,
                                    onMoreClick = { onItemMoreClick(item) }
                                )
                                if (idx < recentPreview.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 76.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
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

@Composable
private fun HomeCategoryIconItem(
    label: String,
    icon: ImageVector,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    tag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(gradientColors)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun HomeGradientSpaceCard(
    title: String,
    subtitle: String,
    watermarkIcon: ImageVector,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(98.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(gradientColors))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        // Subtle decorative watermark icon on bottom-right
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = watermarkIcon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(26.dp)
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeShortcutAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    tag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(26.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    onMoreClick: () -> Unit,
    moreTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onMoreClick)
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .testTag(moreTag)
        ) {
            Text(
                text = "更多",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看全部$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun HomeEmptySectionHint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
        )
    }
}
