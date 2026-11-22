package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.example.model.FilterDateRange
import com.example.model.FilterFileType
import com.example.model.FilterSizeRange
import com.example.model.KodFileItem
import com.example.model.KodFileTypeCategory
import com.example.model.KodSpace
import com.example.model.KodTag
import com.example.model.ViewMode
import com.example.ui.components.ConnectionErrorCard
import com.example.ui.components.EmptyFolderState
import com.example.ui.components.NextcloudFileGridCard
import com.example.ui.components.NextcloudFileListItem
import com.example.viewmodel.KodUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesTabContent(
    state: KodUiState,
    imageLoader: ImageLoader,
    thumbnailUrlFor: (KodFileItem) -> String?,
    onSelectSpace: (KodSpace) -> Unit,
    onRefresh: () -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onSelectFileTypeCategory: (KodFileTypeCategory) -> Unit,
    onSelectTagFilter: (KodTag?) -> Unit,
    onOpenFilterSheet: () -> Unit,
    onClearSingleTypeFilter: () -> Unit,
    onClearSingleDateFilter: () -> Unit,
    onClearSingleSizeFilter: () -> Unit,
    onClearAllFilters: () -> Unit,
    onItemClick: (KodFileItem) -> Unit,
    onItemLongClick: (KodFileItem) -> Unit,
    onItemMoreClick: (KodFileItem) -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onCreateFolderPrompt: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // KodBox-native inline Parallel Space Ribbon (1-tap switching between Personal, Enterprise, Favorites, Shared, etc.)
        if (!state.isSearchActive) {
            KodSpaceRibbon(
                activeSpace = state.activeSpace,
                onSelectSpace = onSelectSpace
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }

        // Interactive breadcrumb bar + quick Filter button
        if (state.breadcrumbs.isNotEmpty() && !state.isSearchActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(state.breadcrumbs) { idx, node ->
                        val isLast = idx == state.breadcrumbs.lastIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isLast) { onBreadcrumbClick(idx) }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            if (idx == 0) {
                                Icon(
                                    imageVector = Icons.Filled.Home,
                                    contentDescription = null,
                                    tint = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = node.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!isLast) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Filter funnel pill
                FilterChip(
                    selected = state.searchFilter.hasActiveFilters,
                    onClick = onOpenFilterSheet,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = "多维筛选",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            if (state.searchFilter.hasActiveFilters) "筛选(${state.searchFilter.activeCount})"
                            else "筛选"
                        )
                    },
                    modifier = Modifier.testTag("open_filter_chip")
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Active multi-dimensional filter chips bar
        if (state.searchFilter.hasActiveFilters) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.searchFilter.fileType != FilterFileType.ALL) {
                    item {
                        InputChip(
                            selected = true,
                            onClick = onClearSingleTypeFilter,
                            label = { Text("类型：${state.searchFilter.fileType.label}") },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(15.dp)) }
                        )
                    }
                }
                if (state.searchFilter.dateRange != FilterDateRange.ANY) {
                    item {
                        InputChip(
                            selected = true,
                            onClick = onClearSingleDateFilter,
                            label = { Text("日期：${state.searchFilter.dateRange.label}") },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(15.dp)) }
                        )
                    }
                }
                if (state.searchFilter.sizeRange != FilterSizeRange.ANY) {
                    item {
                        InputChip(
                            selected = true,
                            onClick = onClearSingleSizeFilter,
                            label = { Text("大小：${state.searchFilter.sizeRange.label}") },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(15.dp)) }
                        )
                    }
                }
                item {
                    TextButton(onClick = onClearAllFilters) {
                        Text("清除全部")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // KodBox Space-specific quick bars
        if (state.activeSpace == KodSpace.FILE_TYPES) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(KodFileTypeCategory.entries) { cat ->
                    FilterChip(
                        selected = state.activeFileTypeCategory == cat,
                        onClick = { onSelectFileTypeCategory(cat) },
                        label = { Text(cat.label) }
                    )
                }
            }
        } else if (state.activeSpace == KodSpace.TAGS && state.availableTags.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.activeTagFilter == null,
                        onClick = { onSelectTagFilter(null) },
                        label = { Text("全部标签") }
                    )
                }
                items(state.availableTags, key = { it.id }) { tag ->
                    FilterChip(
                        selected = state.activeTagFilter?.id == tag.id,
                        onClick = { onSelectTagFilter(tag) },
                        label = { Text(tag.name) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(Color(tag.colorHex))
                            )
                        }
                    )
                }
            }
        } else if (state.activeSpace == KodSpace.RECYCLE_BIN && state.files.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "回收站共 ${state.files.size} 项，可点击右侧菜单还原",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onEmptyRecycleBin,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空")
                    }
                }
            }
        }

        if (state.isWorking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        PullToRefreshBox(
            isRefreshing = state.isLoadingFiles,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                state.filesError != null && state.files.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ConnectionErrorCard(
                            message = state.filesError,
                            onRetry = onRefresh
                        )
                    }
                }
                !state.isLoadingFiles && state.files.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val isFiltered = state.searchFilter.hasActiveFilters || state.searchQuery.isNotBlank()
                        EmptyFolderState(
                            title = if (isFiltered) "没有匹配筛选条件的文件" else "此目录暂无内容",
                            subtitle = if (isFiltered) {
                                "请尝试调整文件名关键词、文件类型、日期或大小筛选条件"
                            } else {
                                "点击右下角 + 按钮上传文件、新建文件夹或提交 KodBox 离线下载"
                            },
                            actionLabel = when {
                                isFiltered -> "重置筛选条件"
                                state.activeSpace == KodSpace.PERSONAL || state.activeSpace == KodSpace.ENTERPRISE -> "新建文件夹"
                                else -> null
                            },
                            onAction = when {
                                isFiltered -> onClearAllFilters
                                state.activeSpace == KodSpace.PERSONAL || state.activeSpace == KodSpace.ENTERPRISE -> onCreateFolderPrompt
                                else -> null
                            }
                        )
                    }
                }
                state.preferences.viewMode == ViewMode.LIST -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(state.files, key = { it.path }) { item ->
                            NextcloudFileListItem(
                                item = item,
                                thumbnailUrl = if (item.isImage) thumbnailUrlFor(item) else null,
                                imageLoader = imageLoader,
                                showThumbnails = state.preferences.showThumbnails,
                                isSelected = item.path in state.selectedPaths,
                                isSelectionMode = state.isInSelectionMode,
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemLongClick(item) },
                                onMoreClick = { onItemMoreClick(item) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 152.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.files, key = { it.path }) { item ->
                            NextcloudFileGridCard(
                                item = item,
                                thumbnailUrl = if (item.isImage) thumbnailUrlFor(item) else null,
                                imageLoader = imageLoader,
                                showThumbnails = state.preferences.showThumbnails,
                                isSelected = item.path in state.selectedPaths,
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemLongClick(item) },
                                onMoreClick = { onItemMoreClick(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KodSpaceRibbon(
    activeSpace: KodSpace,
    onSelectSpace: (KodSpace) -> Unit
) {
    val primarySpaces = KodSpace.entries.filter { it.isPrimarySpace }
    val utilitySpaces = KodSpace.entries.filter { !it.isPrimarySpace }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(primarySpaces, key = { it.name }) { space ->
            SpaceRibbonPill(
                space = space,
                selected = space == activeSpace,
                onClick = { onSelectSpace(space) }
            )
        }

        item {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        items(utilitySpaces, key = { it.name }) { space ->
            SpaceRibbonPill(
                space = space,
                selected = space == activeSpace,
                onClick = { onSelectSpace(space) }
            )
        }
    }
}

@Composable
private fun SpaceRibbonPill(
    space: KodSpace,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        } else {
            null
        },
        modifier = Modifier
            .height(34.dp)
            .testTag("space_ribbon_${space.name.lowercase()}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = spaceIcon(space),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else contentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = space.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

private fun spaceIcon(space: KodSpace): ImageVector = when (space) {
    KodSpace.PERSONAL -> Icons.Filled.Folder
    KodSpace.ENTERPRISE -> Icons.Filled.Business
    KodSpace.FAVORITES -> Icons.Filled.Star
    KodSpace.SHARED_WITH_ME -> Icons.Filled.Groups
    KodSpace.RECENT -> Icons.Filled.History
    KodSpace.FILE_TYPES -> Icons.Filled.Category
    KodSpace.TAGS -> Icons.Filled.Label
    KodSpace.RECYCLE_BIN -> Icons.Outlined.DeleteOutline
}

