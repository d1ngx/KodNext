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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.model.ViewMode
import com.example.ui.components.ConnectionErrorCard
import com.example.ui.components.DirectoryReadmeCard
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
    onRefresh: () -> Unit,
    onSelectSpace: (KodSpace) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onSelectFileTypeCategory: (KodFileTypeCategory) -> Unit,
    onSelectTagFilter: (KodTag?) -> Unit,
    onOpenFilterSheet: () -> Unit,
    onToggleViewMode: () -> Unit,
    onUpdateSort: (SortField, SortOrder) -> Unit,
    onClearSingleTypeFilter: () -> Unit,
    onClearSingleDateFilter: () -> Unit,
    onClearSingleSizeFilter: () -> Unit,
    onClearAllFilters: () -> Unit,
    onItemClick: (KodFileItem) -> Unit,
    onItemLongClick: (KodFileItem) -> Unit,
    onItemShareClick: (KodFileItem) -> Unit,
    onItemMoreClick: (KodFileItem) -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onCreateFolderPrompt: () -> Unit,
    onToggleReadmeExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Compact quick space switcher pills when at top-level in Files tab (no drawer needed)
        if (!state.isSearchActive && state.breadcrumbs.size <= 1) {
            val quickSpaces = listOf(
                KodSpace.PERSONAL,
                KodSpace.ENTERPRISE,
                KodSpace.FAVORITES,
                KodSpace.RECENT,
                KodSpace.TAGS,
                KodSpace.RECYCLE_BIN
            )
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickSpaces, key = { it.name }) { space ->
                    FilterChip(
                        selected = state.activeSpace == space,
                        onClick = { onSelectSpace(space) },
                        label = { Text(space.label, maxLines = 1) },
                        modifier = Modifier.testTag("files_space_chip_${space.name.lowercase()}")
                    )
                }
            }
        }

        // Clean breadcrumb navigation bar + quick multi-dimensional filter pill
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

            // Nextcloud-inspired clean sub-bar: Sort dropdown + item count + Grid/List toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 12.dp, end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { sortMenuExpanded = true }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .testTag("inline_sort_dropdown")
                        ) {
                            Text(
                                text = "${state.preferences.sortField.label}${if (state.preferences.sortOrder == SortOrder.ASC) "顺序" else "倒序"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "切换排序",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            SortField.entries.forEach { field ->
                                val selected = state.preferences.sortField == field
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${field.label}${if (selected) " (${state.preferences.sortOrder.label})" else ""}",
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    trailingIcon = {
                                        if (selected) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        val nextOrder = if (selected) {
                                            if (state.preferences.sortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC
                                        } else {
                                            SortOrder.ASC
                                        }
                                        onUpdateSort(field, nextOrder)
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (state.files.isNotEmpty() || state.rawFiles.isNotEmpty()) {
                        val countText = if (state.files.size != state.rawFiles.size && state.rawFiles.isNotEmpty()) {
                            "${state.files.size}/${state.rawFiles.size} 项"
                        } else {
                            "共 ${state.files.size} 项"
                        }
                        Text(
                            text = countText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onToggleViewMode,
                    modifier = Modifier.testTag("inline_toggle_view_mode")
                ) {
                    Icon(
                        imageVector = if (state.preferences.viewMode == ViewMode.LIST) {
                            Icons.Filled.GridView
                        } else {
                            Icons.AutoMirrored.Filled.ViewList
                        },
                        contentDescription = "切换列表或网格视图",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp)
                    )
                }
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
                                "点击右下角 + 按钮上传文件、新建文件夹或提交网盘离线下载"
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
                    val readmeItem = state.directoryReadmeItem
                    val showInlineReadme = readmeItem != null && !state.isSearchActive && !state.searchFilter.hasActiveFilters
                    val sharedKeys = remember(state.shares, state.collabShares) {
                        buildSet {
                            (state.shares + state.collabShares).forEach { s ->
                                if (s.sourceId.isNotBlank()) add("id:${s.sourceId}")
                                val p = s.path.trim().trimEnd('/')
                                if (p.isNotBlank()) add("path:$p")
                            }
                        }
                    }
                    val isTopLevelSpecialSpace = state.breadcrumbs.size <= 1 && (
                        state.activeSpace == KodSpace.ENTERPRISE ||
                            state.activeSpace == KodSpace.FILE_TYPES ||
                            state.activeSpace == KodSpace.TAGS
                        )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        if (showInlineReadme && readmeItem != null) {
                            item(key = "directory_readme_card_${readmeItem.path}") {
                                DirectoryReadmeCard(
                                    item = readmeItem,
                                    markdownContent = state.directoryReadmeContent,
                                    isLoading = state.isLoadingDirectoryReadme,
                                    isExpanded = state.isDirectoryReadmeExpanded,
                                    onToggleExpanded = onToggleReadmeExpanded,
                                    onOpenFullPreview = onItemClick
                                )
                            }
                        }
                        val isRecycleSpace = state.activeSpace == KodSpace.RECYCLE_BIN
                        items(state.files, key = { it.path }) { rawItem ->
                            val isRecycleEntry = isRecycleSpace || rawItem.isRecycleItem
                            val isKnownShared = rawItem.isShare ||
                                (rawItem.sourceId.isNotBlank() && "id:${rawItem.sourceId}" in sharedKeys) ||
                                ("path:${rawItem.path.trim().trimEnd('/')}" in sharedKeys)
                            val item = if (isKnownShared && !rawItem.isShare) rawItem.copy(isShare = true) else rawItem
                            val allowShareButton = !isRecycleEntry && !isTopLevelSpecialSpace && item.canShare
                            NextcloudFileListItem(
                                item = item,
                                thumbnailUrl = if (item.supportsThumbnail) thumbnailUrlFor(item) else null,
                                imageLoader = imageLoader,
                                showThumbnails = state.preferences.showThumbnails,
                                isSelected = item.path in state.selectedPaths,
                                isSelectionMode = state.isInSelectionMode,
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemLongClick(item) },
                                onShareClick = if (allowShareButton) {
                                    { onItemShareClick(item) }
                                } else null,
                                showLocationPath = isRecycleEntry,
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
                    val readmeItem = state.directoryReadmeItem
                    val showInlineReadme = readmeItem != null && !state.isSearchActive && !state.searchFilter.hasActiveFilters
                    val sharedKeys = remember(state.shares, state.collabShares) {
                        buildSet {
                            (state.shares + state.collabShares).forEach { s ->
                                if (s.sourceId.isNotBlank()) add("id:${s.sourceId}")
                                val p = s.path.trim().trimEnd('/')
                                if (p.isNotBlank()) add("path:$p")
                            }
                        }
                    }
                    val isTopLevelSpecialSpace = state.breadcrumbs.size <= 1 && (
                        state.activeSpace == KodSpace.ENTERPRISE ||
                            state.activeSpace == KodSpace.FILE_TYPES ||
                            state.activeSpace == KodSpace.TAGS
                        )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 152.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (showInlineReadme && readmeItem != null) {
                            item(
                                key = "directory_readme_card_${readmeItem.path}",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                DirectoryReadmeCard(
                                    item = readmeItem,
                                    markdownContent = state.directoryReadmeContent,
                                    isLoading = state.isLoadingDirectoryReadme,
                                    isExpanded = state.isDirectoryReadmeExpanded,
                                    onToggleExpanded = onToggleReadmeExpanded,
                                    onOpenFullPreview = onItemClick,
                                    modifier = Modifier.padding(horizontal = 0.dp)
                                )
                            }
                        }
                        val isRecycleSpace = state.activeSpace == KodSpace.RECYCLE_BIN
                        items(state.files, key = { it.path }) { rawItem ->
                            val isRecycleEntry = isRecycleSpace || rawItem.isRecycleItem
                            val isKnownShared = rawItem.isShare ||
                                (rawItem.sourceId.isNotBlank() && "id:${rawItem.sourceId}" in sharedKeys) ||
                                ("path:${rawItem.path.trim().trimEnd('/')}" in sharedKeys)
                            val item = if (isKnownShared && !rawItem.isShare) rawItem.copy(isShare = true) else rawItem
                            val allowShareButton = !isRecycleEntry && !isTopLevelSpecialSpace && item.canShare
                            NextcloudFileGridCard(
                                item = item,
                                thumbnailUrl = if (item.supportsThumbnail) thumbnailUrlFor(item) else null,
                                imageLoader = imageLoader,
                                showThumbnails = state.preferences.showThumbnails,
                                isSelected = item.path in state.selectedPaths,
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemLongClick(item) },
                                onShareClick = if (allowShareButton) {
                                    { onItemShareClick(item) }
                                } else null,
                                showLocationPath = isRecycleEntry,
                                onMoreClick = { onItemMoreClick(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}
