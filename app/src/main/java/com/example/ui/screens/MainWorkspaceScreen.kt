package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.ImageLoader
import com.example.model.FilterDateRange
import com.example.model.FilterFileType
import com.example.model.FilterSizeRange
import com.example.model.KodSpace
import com.example.model.MainTab
import com.example.model.ShareAccessMode
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.model.ViewMode
import com.example.ui.components.AccountSwitcherDialog
import com.example.ui.components.ConfirmDangerDialog
import com.example.ui.components.CreateNoteDialog
import com.example.ui.components.FileActionBottomSheet
import com.example.ui.components.FileDetailsDialog
import com.example.ui.components.FolderPickerBottomSheet
import com.example.ui.components.KodAppHubBottomSheet
import com.example.ui.components.KodShareConfigDialog
import com.example.ui.components.KodTagManagerSheet
import com.example.ui.components.SearchFilterBottomSheet
import com.example.ui.components.TextPromptDialog
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
    var showAccountSwitcher by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateNoteDialog by remember { mutableStateOf(false) }
    var showOfflineDownloadDialog by remember { mutableStateOf(false) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var askedNotificationOnce by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Transfer runs regardless; permission only enables status-bar progress */ }

    fun ensureTransferNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotificationOnce) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                askedNotificationOnce = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Zero-permission Android document & photo pickers (Google Play Policy compliant)
    val documentUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            ensureTransferNotificationPermission()
            viewModel.uploadUris(uris)
        }
    }
    val mediaUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            ensureTransferNotificationPermission()
            viewModel.uploadUris(uris)
        }
    }

    // Hierarchical BackHandler for sub-screens, selection mode, search, and folder navigation
    BackHandler(
        enabled = state.showSharedScreen ||
            state.showSyncScreen ||
            fabExpanded ||
            state.isInSelectionMode ||
            state.isSearchActive ||
            (state.activeTab == MainTab.FILES && state.canNavigateUp)
    ) {
        when {
            state.showSharedScreen -> viewModel.closeSharedScreen()
            state.showSyncScreen -> viewModel.closeSyncScreen()
            fabExpanded -> fabExpanded = false
            state.isInSelectionMode -> viewModel.clearSelection()
            state.isSearchActive -> viewModel.toggleSearch(false)
            state.activeTab == MainTab.FILES && state.canNavigateUp -> viewModel.navigateUp()
        }
    }

    val showingSubScreen = state.showSharedScreen || state.showSyncScreen

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 840.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (isExpandedWidth && !showingSubScreen) {
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
                        state.showSharedScreen -> {
                            TopAppBar(
                                title = { Text("外链与协作") },
                                navigationIcon = {
                                    IconButton(
                                        onClick = { viewModel.closeSharedScreen() },
                                        modifier = Modifier.testTag("shared_screen_back")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                    }
                                }
                            )
                        }

                        state.showSyncScreen -> {
                            TopAppBar(
                                title = { Text("自动备份与同步") },
                                navigationIcon = {
                                    IconButton(
                                        onClick = { viewModel.closeSyncScreen() },
                                        modifier = Modifier.testTag("sync_screen_back")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                    }
                                }
                            )
                        }

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
                                    IconButton(onClick = {
                                        ensureTransferNotificationPermission()
                                        viewModel.downloadItems(state.selectedItems)
                                    }) {
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
                                        placeholder = { Text("按文件名搜索…") },
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
                                        Icon(Icons.Filled.Search, contentDescription = "搜索")
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

                        state.activeTab == MainTab.HOME -> {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    onClick = { viewModel.openFilesWithSearch() },
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .height(44.dp)
                                        .testTag("home_search_bar")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "搜索网盘文件…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        state.activeTab == MainTab.ME -> {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "我的",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { viewModel.openQrScanner(true) },
                                        modifier = Modifier.testTag("me_qr_scan_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.QrCodeScanner,
                                            contentDescription = "扫一扫登录或授权"
                                        )
                                    }
                                }
                            }
                        }

                        state.activeTab == MainTab.FILES && !state.canNavigateUp -> {
                            // Clean search pill header without left drawer or top-right avatar
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    onClick = { viewModel.toggleSearch(true) },
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .height(44.dp)
                                        .testTag("open_search_button")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "在${state.currentTitle}中搜索…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        state.activeTab == MainTab.FILES -> {
                            TopAppBar(
                                navigationIcon = {
                                    IconButton(
                                        onClick = { viewModel.navigateUp() },
                                        modifier = Modifier.testTag("top_back_button")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                                    }
                                },
                                title = {
                                    Column {
                                        Text(
                                            text = state.currentTitle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold
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
                            )
                        }

                        state.activeTab == MainTab.TRANSFERS -> {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(
                                            text = "传输列表",
                                            style = MaterialTheme.typography.titleLarge,
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
                                    TextButton(
                                        onClick = { viewModel.openSyncScreen() },
                                        modifier = Modifier.testTag("transfers_open_sync_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CloudSync,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.size(4.dp))
                                        Text("自动同步")
                                    }
                                }
                            )
                        }
                    }
                },
                bottomBar = {
                    if (!isExpandedWidth && !showingSubScreen) {
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
                    if (!showingSubScreen &&
                        state.activeTab == MainTab.FILES &&
                        !state.isInSelectionMode &&
                        state.activeSpace != KodSpace.RECYCLE_BIN
                    ) {
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    AnimatedVisibility(visible = state.sessionExpiredNotice != null) {
                        val notice = state.sessionExpiredNotice
                        if (notice != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("session_expired_banner")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "登录凭证已失效",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = notice.reason,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.reloginExpiredAccount(openQrDirectly = false) },
                                        modifier = Modifier.testTag("session_expired_banner_relogin_button")
                                    ) {
                                        Text(
                                            text = "重新登录",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when {
                        state.showSharedScreen -> SharedTabContent(
                            state = state,
                            onSelectSubTab = { subTab -> viewModel.selectShareSubTab(subTab) },
                            onRefresh = { viewModel.loadShares() },
                            onCopyShare = { share ->
                                val payload = if (share.password.isNotBlank()) {
                                    "分享链接: ${share.shareUrl}\n提取码: ${share.password}"
                                } else {
                                    share.shareUrl
                                }
                                viewModel.copyText("网盘外链", payload)
                            },
                            onEditShare = { share -> viewModel.showEditShareDialog(share) },
                            onCancelShare = { share -> viewModel.cancelShare(share.shareId) },
                            onOpenSharedResource = { path, title, isFolder, ext, size ->
                                viewModel.openSharedResource(path, title, isFolder, ext, size)
                            }
                        )

                        state.showSyncScreen -> SyncTabContent(
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

                        state.activeTab == MainTab.HOME -> HomeTabContent(
                            account = account,
                            state = state,
                            imageLoader = imageLoader,
                            thumbnailUrlFor = { item -> viewModel.apiClient.buildThumbnailUrl(account, item) },
                            onRefresh = { viewModel.loadHomeOverview(forceRefresh = true) },
                            onSelectFileType = { cat -> viewModel.selectFileTypeCategory(cat) },
                            onEnterSpace = { space -> viewModel.enterSpace(space) },
                            onOpenShared = { subTab -> viewModel.openSharedScreen(subTab) },
                            onItemClick = { item ->
                                if (item.isFolder) {
                                    viewModel.openFolder(item)
                                } else {
                                    viewModel.openFilePreview(item)
                                }
                            },
                            onItemMoreClick = { item -> viewModel.showActionSheet(item) }
                        )

                        state.activeTab == MainTab.FILES -> FilesTabContent(
                            state = state,
                            imageLoader = imageLoader,
                            thumbnailUrlFor = { item -> viewModel.apiClient.buildThumbnailUrl(account, item) },
                            onRefresh = { viewModel.refreshCurrentDirectory() },
                            onSelectSpace = { space -> viewModel.enterSpace(space) },
                            onBreadcrumbClick = { idx -> viewModel.navigateToBreadcrumb(idx) },
                            onSelectFileTypeCategory = { cat -> viewModel.selectFileTypeCategory(cat) },
                            onSelectTagFilter = { tag -> viewModel.selectTagFilter(tag) },
                            onOpenFilterSheet = { viewModel.toggleFilterSheet(true) },
                            onToggleViewMode = { viewModel.toggleViewMode() },
                            onUpdateSort = { field, order -> viewModel.updateSort(field, order) },
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
                            onToggleReadmeExpanded = { viewModel.toggleDirectoryReadmeExpanded() },
                            onEmptyRecycleBin = { showEmptyTrashConfirm = true },
                            onCreateFolderPrompt = { showCreateFolderDialog = true }
                        )

                        state.activeTab == MainTab.TRANSFERS -> TransfersTabContent(
                            state = state,
                            onCancelTask = { viewModel.cancelTransfer(it) },
                            onRetryTask = { viewModel.retryTransfer(it) },
                            onOpenDownloaded = { viewModel.openDownloadedTransfer(it) },
                            onClearFinished = { viewModel.clearFinishedTransfers() }
                        )

                        state.activeTab == MainTab.ME -> MeTabContent(
                            account = account,
                            state = state,
                            imageLoader = imageLoader,
                            onOpenSettings = { viewModel.openSettings(true) },
                            onOpenAccountCenter = { viewModel.openAccountCenter(true) },
                            onOpenSyncScreen = { viewModel.openSyncScreen() },
                            onOpenAccountSwitcher = { showAccountSwitcher = true },
                            onOpenWebAdmin = { viewModel.openWebAdminPortal() },
                            onOpenWebPortal = { viewModel.openWebDrivePortal() },
                            onOpenAppHub = { viewModel.toggleAppHubSheet(true) },
                            onCopyDiagnostics = { viewModel.copyText("KodNext 诊断信息", it) }
                        )
                    }
                    }
                }
            }
        }
    }

    // Account Quick Switcher dialog (triggered from Me tab)
    if (showAccountSwitcher) {
        AccountSwitcherDialog(
            activeAccount = account,
            allAccounts = state.accounts,
            imageLoader = imageLoader,
            onDismiss = { showAccountSwitcher = false },
            onOpenQrScanner = { viewModel.openQrScanner(true) },
            onSwitchAccount = { viewModel.switchAccount(it) },
            onAddAccount = { viewModel.openAddAccountLogin() }
        )
    }

    // Bottom Sheets & Dialogs
    if (state.showAppHubSheet) {
        KodAppHubBottomSheet(
            apps = state.visibleWorkspaceApps,
            imageLoader = imageLoader,
            onLaunchApp = { viewModel.openWorkspaceApp(it) },
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
            onDownload = {
                ensureTransferNotificationPermission()
                viewModel.downloadItems(listOf(item))
            },
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
            onCopyUrl = { viewModel.copyText("网盘外链", it) },
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
            onCopyUrl = { viewModel.copyText("网盘外链", it) },
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
            title = "服务端离线下载",
            label = "远程文件直链 (HTTP / HTTPS)",
            placeholder = "https://example.com/archive.zip",
            supportingText = "任务将直接交由网盘服务器拉取并保存到当前目录。",
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
private fun TabIconWithBadge(tab: MainTab, selected: Boolean, activeTransfers: Int) {
    val icon = when (tab) {
        MainTab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
        MainTab.FILES -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
        MainTab.TRANSFERS -> if (selected) Icons.Filled.SwapVert else Icons.Outlined.SwapVert
        MainTab.ME -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
    }
    if (tab == MainTab.TRANSFERS && activeTransfers > 0) {
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
