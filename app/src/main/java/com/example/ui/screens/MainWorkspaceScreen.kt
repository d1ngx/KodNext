package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.model.FilterDateRange
import com.example.model.FilterFileType
import com.example.model.FilterSizeRange
import com.example.model.KodSpace
import com.example.model.MainTab
import com.example.model.ShareAccessMode
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.model.SyncSubTab
import com.example.model.ViewMode
import com.example.ui.components.ConfirmDangerDialog
import com.example.ui.components.CreateNoteDialog
import com.example.ui.components.FileActionBottomSheet
import com.example.ui.components.FileDetailsDialog
import com.example.ui.components.FolderPickerBottomSheet
import com.example.ui.components.KodAppHubBottomSheet
import com.example.ui.components.KodShareConfigDialog
import com.example.ui.components.KodTagManagerSheet
import com.example.ui.components.KodUserAvatar
import com.example.ui.components.SearchFilterBottomSheet
import com.example.ui.components.TextPromptDialog
import com.example.ui.components.TopLeftPluginLauncherPill
import com.example.viewmodel.KodUiState
import com.example.viewmodel.KodViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainWorkspaceScreen(
    state: KodUiState,
    viewModel: KodViewModel,
    imageLoader: ImageLoader,
    snackbarHostState: SnackbarHostState
) {
    val account = state.activeAccount ?: return

    var fabExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateNoteDialog by remember { mutableStateOf(false) }
    var showOfflineDownloadDialog by remember { mutableStateOf(false) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }

    // Zero-permission Android document & photo pickers (Google Play Policy compliant)
    val documentUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadUris(uris)
    }
    val mediaUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadUris(uris)
    }

    // Hierarchical BackHandler for KodBox-native navigation
    BackHandler(
        enabled = fabExpanded || state.isInSelectionMode || state.isSearchActive || (state.activeTab == MainTab.FILES && state.canNavigateUp)
    ) {
        when {
            fabExpanded -> fabExpanded = false
            state.isInSelectionMode -> viewModel.clearSelection()
            state.isSearchActive -> viewModel.toggleSearch(false)
            state.activeTab == MainTab.FILES && state.canNavigateUp -> viewModel.navigateUp()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 840.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (isExpandedWidth) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxHeight()
                        .statusBarsPadding()
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MainTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = state.activeTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            icon = { TabIconWithBadge(tab, state.activeTab == tab, state.activeTransferCount) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    when {
                        state.isInSelectionMode -> {
                            TopAppBar(
                                title = { Text("已选 ${state.selectedPaths.size} 项") },
                                navigationIcon = {
                                    IconButton(onClick = { viewModel.clearSelection() }) {
                                        Icon(Icons.Filled.Close, contentDescription = "取消选择")
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { viewModel.selectAllItems() }) {
                                        Icon(Icons.Filled.SelectAll, contentDescription = "全选")
                                    }
                                    IconButton(onClick = { viewModel.startCopyOrMove(state.selectedItems, isMove = true) }) {
                                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "移动")
                                    }
                                    IconButton(onClick = { viewModel.startCopyOrMove(state.selectedItems, isMove = false) }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                                    }
                                    IconButton(onClick = { viewModel.zipItems(state.selectedItems) }) {
                                        Icon(Icons.Filled.Archive, contentDescription = "压缩")
                                    }
                                    IconButton(onClick = { viewModel.downloadItems(state.selectedItems) }) {
                                        Icon(Icons.Filled.Download, contentDescription = "下载")
                                    }
                                    IconButton(onClick = { viewModel.deleteItems(state.selectedItems) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }

                        state.isSearchActive && state.activeTab == MainTab.FILES -> {
                            TopAppBar(
                                title = {
                                    OutlinedTextField(
                                        value = state.searchQuery,
                                        onValueChange = { viewModel.updateSearchQuery(it) },
                                        placeholder = { Text("按文件名搜索 KodBox…") },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color.Transparent,
                                            unfocusedBorderColor = Color.Transparent
                                        ),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { viewModel.executeSearch() }),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("search_input")
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { viewModel.toggleSearch(false) }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
                                    }
                                },
                                actions = {
                                    if (state.searchQuery.isNotBlank()) {
                                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                            Icon(Icons.Filled.Close, contentDescription = "清空")
                                        }
                                    }
                                    IconButton(
                                        onClick = { viewModel.executeSearch() },
                                        modifier = Modifier.testTag("search_submit_button")
                                    ) {
                                        Icon(Icons.Filled.Search, contentDescription = "云端搜索")
                                    }
                                    IconButton(
                                        onClick = { viewModel.toggleFilterSheet(true) },
                                        modifier = Modifier.testTag("search_bar_filter_button")
                                    ) {
                                        BadgedBox(
                                            badge = {
                                                if (state.searchFilter.hasActiveFilters) {
                                                    Badge { Text("${state.searchFilter.activeCount}") }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Filled.FilterList, contentDescription = "筛选条件")
                                        }
                                    }
                                }
                            )
                        }

                        else -> {
                            TopAppBar(
                                navigationIcon = {
                                    if (state.activeTab == MainTab.FILES && state.canNavigateUp) {
                                        IconButton(
                                            onClick = { viewModel.navigateUp() },
                                            modifier = Modifier.testTag("top_back_button")
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                                        }
                                    } else {
                                        // Prominent Top-Left KodBox Plugin Launcher (Project / pinned app + App Hub)
                                        TopLeftPluginLauncherPill(
                                            primaryApp = state.primaryTopLeftApp,
                                            onLaunchPrimary = { viewModel.openWorkspaceApp(it) },
                                            onOpenAppHub = { viewModel.toggleAppHubSheet(true) }
                                        )
                                    }
                                },
                                title = {
                                    Column(modifier = Modifier.padding(start = 6.dp)) {
                                        Text(
                                            text = when (state.activeTab) {
                                                MainTab.FILES -> state.currentTitle
                                                MainTab.MEDIA -> "相册与媒体"
                                                MainTab.SHARED -> "外链与协作"
                                                MainTab.SYNC -> "同步与传输"
                                                MainTab.ME -> "我的工作台"
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = account.siteName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                actions = {
                                    if (state.activeTab == MainTab.FILES) {
                                        IconButton(
                                            onClick = { viewModel.toggleSearch(true) },
                                            modifier = Modifier.testTag("open_search_button")
                                        ) {
                                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                                        }
                                        IconButton(
                                            onClick = { viewModel.toggleViewMode() },
                                            modifier = Modifier.testTag("toggle_view_mode_button")
                                        ) {
                                            Icon(
                                                imageVector = if (state.preferences.viewMode == ViewMode.LIST) {
                                                    Icons.Filled.GridView
                                                } else {
                                                    Icons.AutoMirrored.Filled.ViewList
                                                },
                                                contentDescription = "切换视图"
                                            )
                                        }
                                        Box {
                                            IconButton(
                                                onClick = { sortMenuExpanded = true },
                                                modifier = Modifier.testTag("sort_menu_button")
                                            ) {
                                                Icon(Icons.Filled.Sort, contentDescription = "排序")
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
                                                            viewModel.updateSort(field, nextOrder)
                                                            sortMenuExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (state.activeTab == MainTab.ME) {
                                        IconButton(
                                            onClick = { viewModel.openSettings(true) },
                                            modifier = Modifier.testTag("top_settings_button")
                                        ) {
                                            Icon(Icons.Filled.Settings, contentDescription = "应用设置")
                                        }
                                    } else {
                                        // Quick synced Avatar entry to Me / Account Center / Admin Console
                                        Box(
                                            modifier = Modifier
                                                .padding(end = 10.dp)
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .clickable { viewModel.selectTab(MainTab.ME) }
                                                .testTag("top_right_avatar_button"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            KodUserAvatar(
                                                avatarUrl = account.avatarUrl,
                                                displayName = account.displayName,
                                                imageLoader = imageLoader,
                                                size = 32.dp
                                            )
                                            if (account.isAdmin) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .size(13.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Shield,
                                                            contentDescription = "管理员",
                                                            tint = MaterialTheme.colorScheme.onTertiary,
                                                            modifier = Modifier.size(9.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                },
                bottomBar = {
                    if (!isExpandedWidth) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            MainTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    icon = { TabIconWithBadge(tab, state.activeTab == tab, state.activeTransferCount) },
                                    label = { Text(tab.label, maxLines = 1) },
                                    modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                                )
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (state.activeTab == MainTab.FILES && !state.isInSelectionMode && state.activeSpace != KodSpace.RECYCLE_BIN) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AnimatedVisibility(visible = fabExpanded) {
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    MiniFabItem(
                                        label = "离线下载 (URL)",
                                        icon = Icons.Filled.CloudDownload,
                                        onClick = {
                                            fabExpanded = false
                                            showOfflineDownloadDialog = true
                                        }
                                    )
                                    MiniFabItem(
                                        label = "新建文本 / MD",
                                        icon = Icons.Filled.NoteAdd,
                                        onClick = {
                                            fabExpanded = false
                                            showCreateNoteDialog = true
                                        }
                                    )
                                    MiniFabItem(
                                        label = "新建文件夹",
                                        icon = Icons.Filled.CreateNewFolder,
                                        onClick = {
                                            fabExpanded = false
                                            showCreateFolderDialog = true
                                        }
                                    )
                                    MiniFabItem(
                                        label = "上传照片 / 视频",
                                        icon = Icons.Filled.PhotoLibrary,
                                        onClick = {
                                            fabExpanded = false
                                            mediaUploadLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                            )
                                        }
                                    )
                                    MiniFabItem(
                                        label = "上传本地文件",
                                        icon = Icons.Filled.UploadFile,
                                        onClick = {
                                            fabExpanded = false
                                            documentUploadLauncher.launch(arrayOf("*/*"))
                                        }
                                    )
                                }
                            }

                            FloatingActionButton(
                                onClick = { fabExpanded = !fabExpanded },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.testTag("main_add_fab")
                            ) {
                                Icon(
                                    imageVector = if (fabExpanded) Icons.Filled.Close else Icons.Filled.Add,
                                    contentDescription = "新建或上传"
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (state.activeTab) {
                        MainTab.FILES -> FilesTabContent(
                            state = state,
                            imageLoader = imageLoader,
                            thumbnailUrlFor = { item -> viewModel.apiClient.buildThumbnailUrl(account, item) },
                            onSelectSpace = { space -> viewModel.enterSpace(space) },
                            onRefresh = { viewModel.refreshCurrentDirectory() },
                            onBreadcrumbClick = { idx -> viewModel.navigateToBreadcrumb(idx) },
                            onSelectFileTypeCategory = { cat -> viewModel.selectFileTypeCategory(cat) },
                            onSelectTagFilter = { tag -> viewModel.selectTagFilter(tag) },
                            onOpenFilterSheet = { viewModel.toggleFilterSheet(true) },
                            onClearSingleTypeFilter = { viewModel.updateSearchFilter { it.copy(fileType = FilterFileType.ALL) } },
                            onClearSingleDateFilter = { viewModel.updateSearchFilter { it.copy(dateRange = FilterDateRange.ANY) } },
                            onClearSingleSizeFilter = { viewModel.updateSearchFilter { it.copy(sizeRange = FilterSizeRange.ANY) } },
                            onClearAllFilters = { viewModel.clearSearchFilters() },
                            onItemClick = { item ->
                                if (state.isInSelectionMode) {
                                    viewModel.toggleItemSelection(item)
                                } else if (item.isFolder) {
                                    viewModel.openFolder(item)
                                } else {
                                    viewModel.openFilePreview(item)
                                }
                            },
                            onItemLongClick = { item -> viewModel.toggleItemSelection(item) },
                            onItemMoreClick = { item -> viewModel.showActionSheet(item) },
                            onEmptyRecycleBin = { showEmptyTrashConfirm = true },
                            onCreateFolderPrompt = { showCreateFolderDialog = true }
                        )

                        MainTab.MEDIA -> MediaTabContent(
                            state = state,
                            imageLoader = imageLoader,
                            thumbnailUrlFor = { item -> viewModel.apiClient.buildThumbnailUrl(account, item, width = 420) },
                            onSetFilter = { viewModel.setMediaFilter(it) },
                            onRefresh = { viewModel.loadMediaItems() },
                            onMediaClick = { item -> viewModel.openFilePreview(item) }
                        )

                        MainTab.SHARED -> SharedTabContent(
                            state = state,
                            onSelectSubTab = { subTab -> viewModel.selectShareSubTab(subTab) },
                            onRefresh = { viewModel.loadShares() },
                            onCopyShare = { share ->
                                val payload = if (share.password.isNotBlank()) {
                                    "分享链接: ${share.shareUrl}\n提取码: ${share.password}"
                                } else {
                                    share.shareUrl
                                }
                                viewModel.copyText("KodBox 外链", payload)
                            },
                            onEditShare = { share -> viewModel.showEditShareDialog(share) },
                            onCancelShare = { share -> viewModel.cancelShare(share.shareId) },
                            onOpenSharedResource = { path, title, isFolder, ext, size ->
                                viewModel.openSharedResource(path, title, isFolder, ext, size)
                            }
                        )

                        MainTab.SYNC -> SyncAndTransferHubContent(
                            state = state,
                            viewModel = viewModel
                        )

                        MainTab.ME -> MeHubTabContent(
                            state = state,
                            imageLoader = imageLoader,
                            onOpenAccountCenter = { viewModel.openAccountCenter(true) },
                            onOpenAdminConsole = { viewModel.openAdminConsole(true) },
                            onOpenWebAdmin = { viewModel.openWebAdminPortal() },
                            onLaunchWorkspaceApp = { viewModel.openWorkspaceApp(it) },
                            onOpenAppHub = { viewModel.toggleAppHubSheet(true) },
                            onOpenSettings = { viewModel.openSettings(true) },
                            onSwitchAccount = { viewModel.switchAccount(it) },
                            onAddAccount = { viewModel.openAddAccountLogin() }
                        )
                    }
                }
            }
        }
    }

    // Bottom Sheets & Dialogs
    if (state.showAppHubSheet) {
        KodAppHubBottomSheet(
            apps = state.visibleWorkspaceApps,
            pinnedAppId = state.preferences.pinnedAppId,
            imageLoader = imageLoader,
            onLaunchApp = { viewModel.openWorkspaceApp(it) },
            onPinApp = { viewModel.pinTopLeftApp(it) },
            onDismiss = { viewModel.toggleAppHubSheet(false) }
        )
    }

    if (state.showFilterSheet) {
        SearchFilterBottomSheet(
            filter = state.searchFilter,
            onUpdateFilter = { viewModel.updateSearchFilter(it) },
            onReset = { viewModel.clearSearchFilters() },
            onDismiss = { viewModel.toggleFilterSheet(false) }
        )
    }

    state.actionSheetItem?.let { item ->
        FileActionBottomSheet(
            item = item,
            isRecycleBin = state.activeSpace == KodSpace.RECYCLE_BIN,
            onDismiss = { viewModel.showActionSheet(null) },
            onOpenOrPreview = { viewModel.openFilePreview(item) },
            onDownload = { viewModel.downloadItems(listOf(item)) },
            onShare = {
                viewModel.showActionSheet(null)
                viewModel.showShareDialog(item)
            },
            onToggleFavorite = { viewModel.toggleFavorite(item) },
            onManageTags = {
                viewModel.showActionSheet(null)
                viewModel.showTagSheet(item)
            },
            onRename = {
                viewModel.showActionSheet(null)
                viewModel.showRenameDialog(item)
            },
            onMove = { viewModel.startCopyOrMove(listOf(item), isMove = true) },
            onCopy = { viewModel.startCopyOrMove(listOf(item), isMove = false) },
            onZip = { viewModel.zipItems(listOf(item)) },
            onUnzip = { viewModel.unzipItem(item) },
            onDetails = {
                viewModel.showActionSheet(null)
                viewModel.showDetailDialog(item)
            },
            onRestore = { viewModel.restoreRecycleItems(listOf(item)) },
            onDelete = { viewModel.deleteItems(listOf(item)) }
        )
    }

    state.shareDialogItem?.let { item ->
        KodShareConfigDialog(
            titleName = item.name,
            isFolder = item.isFolder,
            createdShare = state.createdShareResult,
            isWorking = state.isWorking,
            onSaveShare = { pwd, expireSec, mode ->
                viewModel.createShareLink(item, pwd, expireSec, mode)
            },
            onCopyUrl = { viewModel.copyText("KodBox 外链", it) },
            onDismiss = { viewModel.showShareDialog(null) }
        )
    }

    state.editingShare?.let { existing ->
        val initialMode = when {
            existing.canUpload -> ShareAccessMode.READ_WRITE
            existing.notDownload -> ShareAccessMode.READ_ONLY_PREVIEW
            else -> ShareAccessMode.READ_ONLY_DOWNLOAD
        }
        KodShareConfigDialog(
            titleName = existing.title,
            isFolder = existing.isFolder,
            initialPassword = existing.password,
            initialExpireEpochSeconds = existing.timeTo,
            initialAccessMode = initialMode,
            isEditingExisting = true,
            createdShare = state.createdShareResult,
            isWorking = state.isWorking,
            onSaveShare = { pwd, expireSec, mode ->
                viewModel.updateExistingShare(existing, pwd, expireSec, mode)
            },
            onCopyUrl = { viewModel.copyText("KodBox 外链", it) },
            onDismiss = { viewModel.showEditShareDialog(null) }
        )
    }

    state.tagSheetItem?.let { item ->
        KodTagManagerSheet(
            item = item,
            availableTags = state.availableTags,
            onToggleTag = { tag -> viewModel.toggleTagOnItem(item, tag) },
            onCreateTag = { name, style -> viewModel.createTag(name, style) },
            onDismiss = { viewModel.showTagSheet(null) }
        )
    }

    state.folderPickerState?.let { pickerState ->
        FolderPickerBottomSheet(
            state = pickerState,
            onSwitchSpace = { viewModel.switchFolderPickerSpace(it) },
            onNavigateUp = { viewModel.navigateFolderPickerUp() },
            onEnterFolder = { viewModel.browseFolderPickerInto(it) },
            onConfirm = { viewModel.confirmCopyOrMove() },
            onDismiss = { viewModel.dismissFolderPicker() }
        )
    }

    state.renameDialogItem?.let { item ->
        TextPromptDialog(
            title = "重命名",
            label = "新名称",
            initialValue = item.name,
            confirmLabel = "重命名",
            onConfirm = { newName -> viewModel.renameItem(item, newName) },
            onDismiss = { viewModel.showRenameDialog(null) }
        )
    }

    state.detailDialogItem?.let { item ->
        FileDetailsDialog(
            item = item,
            onDismiss = { viewModel.showDetailDialog(null) }
        )
    }

    if (showCreateFolderDialog) {
        TextPromptDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            placeholder = "例如：项目归档",
            confirmLabel = "创建",
            onConfirm = { name ->
                showCreateFolderDialog = false
                viewModel.createFolder(name)
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    if (showCreateNoteDialog) {
        CreateNoteDialog(
            onConfirm = { fileName, content ->
                showCreateNoteDialog = false
                viewModel.createTextFile(fileName, content)
            },
            onDismiss = { showCreateNoteDialog = false }
        )
    }

    if (showOfflineDownloadDialog) {
        TextPromptDialog(
            title = "KodBox 服务端离线下载",
            label = "远程文件直链 (HTTP / HTTPS)",
            placeholder = "https://example.com/archive.zip",
            supportingText = "任务将直接交由您的私有 KodBox 服务器拉取并保存到当前目录。",
            confirmLabel = "开始离线下载",
            onConfirm = { url ->
                showOfflineDownloadDialog = false
                viewModel.submitOfflineDownload(url)
            },
            onDismiss = { showOfflineDownloadDialog = false }
        )
    }

    if (showEmptyTrashConfirm) {
        ConfirmDangerDialog(
            title = "清空回收站",
            message = "清空后，回收站中的所有文件将被彻底删除且无法恢复，确认继续吗？",
            confirmLabel = "彻底清空",
            onConfirm = {
                showEmptyTrashConfirm = false
                viewModel.clearRecycleBin()
            },
            onDismiss = { showEmptyTrashConfirm = false }
        )
    }
}

@Composable
private fun SyncAndTransferHubContent(
    state: KodUiState,
    viewModel: KodViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SyncSubTab.entries.forEachIndexed { idx, sub ->
                val labelText = if (sub == SyncSubTab.TRANSFERS && state.activeTransferCount > 0) {
                    "${sub.label} (${state.activeTransferCount})"
                } else {
                    sub.label
                }
                SegmentedButton(
                    selected = state.activeSyncSubTab == sub,
                    onClick = { viewModel.selectSyncSubTab(sub) },
                    shape = SegmentedButtonDefaults.itemShape(idx, SyncSubTab.entries.size),
                    modifier = Modifier.testTag("sync_subtab_${sub.name.lowercase()}")
                ) {
                    Text(labelText, maxLines = 1)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (state.activeSyncSubTab) {
            SyncSubTab.RULES -> SyncTabContent(
                state = state,
                onOpenRemoteFolderPicker = { viewModel.openRemoteSyncFolderPicker() },
                onClearPickedRemote = { viewModel.clearPickedSyncRemote() },
                onCreateSyncRule = { name, uri, localLabel, remotePath, remoteLabel, dir, freq, wifiOnly ->
                    viewModel.createSyncRule(name, uri, localLabel, remotePath, remoteLabel, dir, freq, wifiOnly)
                },
                onToggleEnabled = { rule, enabled -> viewModel.toggleSyncRuleEnabled(rule, enabled) },
                onChangeFrequency = { rule, freq -> viewModel.updateSyncRuleFrequency(rule, freq) },
                onRunNow = { rule -> viewModel.runSyncRuleNow(rule) },
                onRunAllNow = { viewModel.runAllSyncRulesNow() },
                onDeleteRule = { rule -> viewModel.deleteSyncRule(rule) }
            )

            SyncSubTab.TRANSFERS -> TransfersTabContent(
                state = state,
                onCancelTask = { viewModel.cancelTransfer(it) },
                onOpenDownloaded = { viewModel.openDownloadedTransfer(it) },
                onClearFinished = { viewModel.clearFinishedTransfers() }
            )
        }
    }
}

@Composable
private fun TabIconWithBadge(tab: MainTab, selected: Boolean, activeTransfers: Int) {
    val icon = when (tab) {
        MainTab.FILES -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
        MainTab.MEDIA -> if (selected) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary
        MainTab.SHARED -> if (selected) Icons.Filled.Share else Icons.Outlined.Share
        MainTab.SYNC -> if (selected) Icons.Filled.SyncAlt else Icons.Outlined.SyncAlt
        MainTab.ME -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
    }
    if (tab == MainTab.SYNC && activeTransfers > 0) {
        BadgedBox(badge = { Badge { Text("$activeTransfers") } }) {
            Icon(icon, contentDescription = tab.label)
        }
    } else {
        Icon(icon, contentDescription = tab.label)
    }
}

@Composable
private fun MiniFabItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}
