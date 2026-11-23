package com.example.viewmodel

import com.example.data.repository.AuthRepository
import com.example.data.repository.FileRepository
import com.example.model.AppPreferences
import com.example.model.BreadcrumbNode
import com.example.model.FilterDateRange
import com.example.model.FilterFileType
import com.example.model.FilterSizeRange
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodFileTypeCategory
import com.example.model.KodSpace
import com.example.model.KodTag
import com.example.model.MainTab
import com.example.model.SearchFilterState
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.model.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Focused domain coordinator for file directory navigation, 2-tier SWR caching (L1 + Room L2),
 * multi-dimensional search/filter/sort, multi-select CRUD, folder picker, tags, and text preview.
 */
internal class KodFilesCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<KodUiState>,
    private val fileRepository: FileRepository,
    private val authRepository: AuthRepository,
    private val emitMessage: (String) -> Unit,
    private val emitError: (String) -> Unit
) {
    private var readmeLoadJob: Job? = null
    private var loadedReadmeKey: String? = null

    fun toggleDirectoryReadmeExpanded() {
        uiState.update { it.copy(isDirectoryReadmeExpanded = !it.isDirectoryReadmeExpanded) }
    }

    private fun clearDirectoryReadme() {
        readmeLoadJob?.cancel()
        readmeLoadJob = null
        loadedReadmeKey = null
        uiState.update {
            if (it.directoryReadmeItem != null || it.directoryReadmeContent.isNotEmpty() || it.isLoadingDirectoryReadme) {
                it.copy(
                    directoryReadmeItem = null,
                    directoryReadmeContent = "",
                    isLoadingDirectoryReadme = false
                )
            } else {
                it
            }
        }
    }

    private fun detectAndLoadDirectoryReadme(
        account: KodAccount,
        dirPath: String,
        items: List<KodFileItem>,
        forceReload: Boolean = false
    ) {
        if (dirPath.contains("userRecycle", ignoreCase = true) || dirPath.startsWith("{search}", ignoreCase = true)) {
            clearDirectoryReadme()
            return
        }
        val readmeItem = findDirectoryReadme(items)
        if (readmeItem == null) {
            clearDirectoryReadme()
            return
        }

        val cacheKey = "${account.id}|${readmeItem.path}|${readmeItem.modifyTime}|${readmeItem.size}"
        if (!forceReload && cacheKey == loadedReadmeKey && uiState.value.directoryReadmeItem?.path == readmeItem.path) {
            return
        }

        // Skip auto-downloading oversized README files (> 512 KB); card still shows quick-open button
        if (readmeItem.size > MAX_INLINE_README_BYTES) {
            readmeLoadJob?.cancel()
            loadedReadmeKey = cacheKey
            uiState.update {
                it.copy(
                    directoryReadmeItem = readmeItem,
                    directoryReadmeContent = "",
                    isLoadingDirectoryReadme = false,
                    isDirectoryReadmeExpanded = true
                )
            }
            return
        }

        readmeLoadJob?.cancel()
        uiState.update {
            it.copy(
                directoryReadmeItem = readmeItem,
                directoryReadmeContent = if (it.directoryReadmeItem?.path == readmeItem.path) it.directoryReadmeContent else "",
                isLoadingDirectoryReadme = true,
                isDirectoryReadmeExpanded = true
            )
        }
        readmeLoadJob = scope.launch {
            fileRepository.readTextFile(account, readmeItem).fold(
                onSuccess = { content ->
                    if (uiState.value.currentPath == dirPath) {
                        loadedReadmeKey = cacheKey
                        uiState.update {
                            it.copy(
                                directoryReadmeItem = readmeItem,
                                directoryReadmeContent = content,
                                isLoadingDirectoryReadme = false
                            )
                        }
                    }
                },
                onFailure = {
                    if (uiState.value.currentPath == dirPath) {
                        uiState.update {
                            it.copy(
                                directoryReadmeItem = readmeItem,
                                isLoadingDirectoryReadme = false
                            )
                        }
                    }
                }
            )
        }
    }

    companion object {
        private const val MAX_INLINE_README_BYTES = 512L * 1024L

        /**
         * Finds a standard directory README markdown file (case-insensitive `readme.md` or `readme.markdown`).
         */
        fun findDirectoryReadme(items: List<KodFileItem>): KodFileItem? {
            return items.firstOrNull { item ->
                !item.isFolder && (
                    item.name.equals("readme.md", ignoreCase = true) ||
                        item.name.equals("readme.markdown", ignoreCase = true)
                    )
            }
        }
    }

    fun enterSpace(space: KodSpace, forceAccount: KodAccount? = null, switchToFilesTab: Boolean = true) {
        val account = forceAccount ?: uiState.value.activeAccount ?: return
        val rootPath = when (space) {
            KodSpace.PERSONAL -> account.personalRootPath
            KodSpace.ENTERPRISE -> account.groupRootPath
            else -> space.virtualPath
        }
        val initialCrumbs = listOf(BreadcrumbNode(space.label, rootPath))
        uiState.update {
            it.copy(
                activeTab = if (switchToFilesTab) MainTab.FILES else it.activeTab,
                activeSpace = space,
                activeFileTypeCategory = KodFileTypeCategory.ALL,
                activeTagFilter = null,
                currentPath = rootPath,
                currentTitle = space.label,
                breadcrumbs = initialCrumbs,
                selectedPaths = emptySet(),
                isSearchActive = false,
                searchQuery = "",
                showSettings = false,
                showSharedScreen = false,
                showSyncScreen = false
            )
        }
        loadDirectory(account, rootPath, resetCrumbs = initialCrumbs)
    }

    fun openFilesWithSearch() {
        val account = uiState.value.activeAccount ?: return
        uiState.update {
            it.copy(
                activeTab = MainTab.FILES,
                isSearchActive = true,
                showSharedScreen = false,
                showSyncScreen = false
            )
        }
        if (uiState.value.files.isEmpty()) {
            loadDirectory(account, uiState.value.currentPath.ifBlank { account.personalRootPath })
        }
    }

    fun loadHomeOverview(forceAccount: KodAccount? = null, forceRefresh: Boolean = false) {
        val account = forceAccount ?: uiState.value.activeAccount ?: return
        scope.launch {
            if (!forceRefresh) {
                val cachedFav = fileRepository.getCachedDirectory(account.id, "{userFav}/", SortField.MODIFY_TIME, SortOrder.DESC)
                val cachedRecent = fileRepository.getCachedDirectory(account.id, "{userRencent}/", SortField.MODIFY_TIME, SortOrder.DESC)
                if (cachedFav != null || cachedRecent != null) {
                    uiState.update { state ->
                        state.copy(
                            homeFavorites = cachedFav?.items ?: state.homeFavorites,
                            homeRecentFiles = cachedRecent?.items ?: state.homeRecentFiles,
                            isLoadingHome = false
                        )
                    }
                } else {
                    uiState.update { it.copy(isLoadingHome = true) }
                }
            } else {
                uiState.update { it.copy(isLoadingHome = true) }
                fileRepository.invalidatePath(account.id, "{userFav}/")
                fileRepository.invalidatePath(account.id, "{userRencent}/")
            }

            val (favRes, recentRes) = coroutineScope {
                val favJob = async {
                    fileRepository.fetchRemoteDirectory(
                        account = account,
                        path = "{userFav}/",
                        sortField = SortField.MODIFY_TIME,
                        sortOrder = SortOrder.DESC
                    )
                }
                val recentJob = async {
                    fileRepository.fetchRemoteDirectory(
                        account = account,
                        path = "{userRencent}/",
                        sortField = SortField.MODIFY_TIME,
                        sortOrder = SortOrder.DESC
                    )
                }
                favJob.await() to recentJob.await()
            }

            uiState.update { state ->
                state.copy(
                    isLoadingHome = false,
                    homeFavorites = favRes.getOrNull()?.items ?: state.homeFavorites,
                    homeRecentFiles = recentRes.getOrNull()?.items ?: state.homeRecentFiles
                )
            }
        }
    }

    fun selectFileTypeCategory(category: KodFileTypeCategory) {
        val account = uiState.value.activeAccount ?: return
        val path = category.virtualPath
        val crumbs = listOf(
            BreadcrumbNode(KodSpace.FILE_TYPES.label, "{block:fileType}/"),
            BreadcrumbNode(category.label, path)
        ).distinctBy { it.path }
        uiState.update {
            it.copy(
                activeTab = MainTab.FILES,
                activeSpace = KodSpace.FILE_TYPES,
                activeFileTypeCategory = category,
                currentPath = path,
                currentTitle = if (category == KodFileTypeCategory.ALL) "文档分类" else "分类 · ${category.label}",
                breadcrumbs = crumbs,
                selectedPaths = emptySet(),
                showSharedScreen = false,
                showSyncScreen = false
            )
        }
        loadDirectory(account, path, resetCrumbs = crumbs)
    }

    fun selectTagFilter(tag: KodTag?) {
        val account = uiState.value.activeAccount ?: return
        val path = if (tag == null) "{block:fileTag}/" else "{userFileTag:${tag.id}}/"
        val crumbs = if (tag == null) {
            listOf(BreadcrumbNode("文件标签", "{block:fileTag}/"))
        } else {
            listOf(
                BreadcrumbNode("文件标签", "{block:fileTag}/"),
                BreadcrumbNode("#${tag.name}", path)
            )
        }
        uiState.update {
            it.copy(
                activeTab = MainTab.FILES,
                activeSpace = KodSpace.TAGS,
                activeTagFilter = tag,
                currentPath = path,
                currentTitle = tag?.let { t -> "标签 · ${t.name}" } ?: "文件标签",
                breadcrumbs = crumbs,
                selectedPaths = emptySet(),
                showSharedScreen = false,
                showSyncScreen = false
            )
        }
        loadDirectory(account, path, resetCrumbs = crumbs)
    }

    fun openFolder(folder: KodFileItem) {
        val account = uiState.value.activeAccount ?: return
        val fromHome = uiState.value.activeTab != MainTab.FILES
        val newCrumbs = if (fromHome) {
            listOf(BreadcrumbNode(folder.name, folder.path))
        } else {
            uiState.value.breadcrumbs + BreadcrumbNode(folder.name, folder.path)
        }
        uiState.update {
            it.copy(
                activeTab = MainTab.FILES,
                currentPath = folder.path,
                currentTitle = folder.name,
                breadcrumbs = newCrumbs,
                selectedPaths = emptySet(),
                isSearchActive = false,
                searchQuery = "",
                showSharedScreen = false,
                showSyncScreen = false
            )
        }
        loadDirectory(account, folder.path, resetCrumbs = newCrumbs)
    }

    fun openDirectoryDirect(account: KodAccount, path: String, crumbs: List<BreadcrumbNode>) {
        loadDirectory(account, path, resetCrumbs = crumbs)
    }

    fun navigateToBreadcrumb(index: Int) {
        val account = uiState.value.activeAccount ?: return
        val crumbs = uiState.value.breadcrumbs
        if (index !in crumbs.indices) return
        val sliced = crumbs.take(index + 1)
        val target = sliced.last()
        uiState.update {
            it.copy(
                currentPath = target.path,
                currentTitle = target.label,
                breadcrumbs = sliced,
                selectedPaths = emptySet()
            )
        }
        loadDirectory(account, target.path, resetCrumbs = sliced)
    }

    fun navigateUp(): Boolean {
        val crumbs = uiState.value.breadcrumbs
        if (crumbs.size <= 1) return false
        navigateToBreadcrumb(crumbs.lastIndex - 1)
        return true
    }

    fun refreshCurrentDirectory() {
        val account = uiState.value.activeAccount ?: return
        val path = uiState.value.currentPath.ifBlank { account.personalRootPath }
        scope.launch {
            fileRepository.invalidatePath(account.id, path)
            loadDirectory(account, path, forceRefresh = true)
        }
    }

    private fun loadDirectory(
        account: KodAccount,
        path: String,
        resetCrumbs: List<BreadcrumbNode>? = null,
        forceRefresh: Boolean = false
    ) {
        scope.launch {
            val prefs = uiState.value.preferences
            val cached = if (!forceRefresh) {
                fileRepository.getCachedDirectory(account.id, path, prefs.sortField, prefs.sortOrder)
            } else null

            if (cached != null) {
                // Instant Stale-While-Revalidate render from L1 memory or L2 Room offline cache
                uiState.update { state ->
                    val mergedTags = if (cached.tags.isNotEmpty()) cached.tags else state.availableTags
                    val filtered = applyClientFiltersAndSort(
                        items = cached.items,
                        prefs = state.preferences,
                        query = state.searchQuery,
                        filter = state.searchFilter
                    )
                    state.copy(
                        isLoadingFiles = false,
                        rawFiles = cached.items,
                        files = filtered,
                        availableTags = mergedTags,
                        breadcrumbs = resetCrumbs ?: state.breadcrumbs,
                        filesError = null
                    )
                }
                detectAndLoadDirectoryReadme(account, path, cached.items, forceReload = false)
            } else {
                clearDirectoryReadme()
                uiState.update { it.copy(isLoadingFiles = true, filesError = null) }
            }

            fileRepository.fetchRemoteDirectory(
                account = account,
                path = path,
                sortField = prefs.sortField,
                sortOrder = prefs.sortOrder
            ).fold(
                onSuccess = { res ->
                    if (uiState.value.currentPath == path || resetCrumbs != null) {
                        uiState.update { state ->
                            val mergedTags = if (res.tags.isNotEmpty()) res.tags else state.availableTags
                            val filtered = applyClientFiltersAndSort(
                                items = res.items,
                                prefs = state.preferences,
                                query = state.searchQuery,
                                filter = state.searchFilter
                            )
                            state.copy(
                                isLoadingFiles = false,
                                rawFiles = res.items,
                                files = filtered,
                                availableTags = mergedTags,
                                breadcrumbs = resetCrumbs ?: state.breadcrumbs,
                                filesError = null
                            )
                        }
                        detectAndLoadDirectoryReadme(account, path, res.items, forceReload = forceRefresh)
                    }
                },
                onFailure = { err ->
                    if (cached == null) {
                        uiState.update {
                            it.copy(
                                isLoadingFiles = false,
                                filesError = err.message ?: "加载目录失败，请检查服务器连接"
                            )
                        }
                    }
                }
            )
        }
    }

    fun applyClientFiltersAndSort(
        items: List<KodFileItem>,
        prefs: AppPreferences,
        query: String = "",
        filter: SearchFilterState = SearchFilterState()
    ): List<KodFileItem> {
        var visible = if (prefs.showHiddenFiles) items else items.filterNot { it.name.startsWith(".") }

        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isNotEmpty()) {
            visible = visible.filter { it.name.lowercase().contains(cleanQuery) }
        }

        if (filter.fileType != FilterFileType.ALL) {
            visible = visible.filter { item ->
                when (filter.fileType) {
                    FilterFileType.ALL -> true
                    FilterFileType.FOLDER -> item.isFolder
                    FilterFileType.DOCUMENT -> !item.isFolder && item.normalizedExt in setOf(
                        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "markdown", "rtf", "odt", "csv"
                    )
                    FilterFileType.IMAGE -> item.isImage
                    FilterFileType.VIDEO -> item.isVideo
                    FilterFileType.AUDIO -> item.isAudio
                    FilterFileType.ARCHIVE -> item.isArchive
                    FilterFileType.CODE -> item.isEditableText
                }
            }
        }

        filter.dateRange.withinDays?.let { days ->
            val cutoffMillis = System.currentTimeMillis() - days * 86_400_000L
            visible = visible.filter { item ->
                val millis = if (item.modifyTime < 10_000_000_000L) item.modifyTime * 1000L else item.modifyTime
                millis >= cutoffMillis
            }
        }

        if (filter.sizeRange != FilterSizeRange.ANY) {
            visible = visible.filter { item ->
                if (item.isFolder) return@filter false
                val minOk = filter.sizeRange.minBytes?.let { item.size >= it } ?: true
                val maxOk = filter.sizeRange.maxBytes?.let { item.size < it } ?: true
                minOk && maxOk
            }
        }

        val comparator = when (prefs.sortField) {
            SortField.NAME -> compareBy<KodFileItem> { it.name.lowercase() }
            SortField.MODIFY_TIME -> compareBy<KodFileItem> { it.modifyTime }
            SortField.SIZE -> compareBy<KodFileItem> { it.size }
            SortField.EXT -> compareBy<KodFileItem> { it.normalizedExt }
        }
        val finalComparator = if (prefs.sortOrder == SortOrder.ASC) comparator else comparator.reversed()
        val folders = visible.filter { it.isFolder }.sortedWith(finalComparator)
        val regularFiles = visible.filterNot { it.isFolder }.sortedWith(finalComparator)
        return folders + regularFiles
    }

    fun toggleSearch(active: Boolean) {
        uiState.update { state ->
            val nextQuery = if (active) state.searchQuery else ""
            state.copy(
                isSearchActive = active,
                searchQuery = nextQuery,
                files = applyClientFiltersAndSort(state.rawFiles, state.preferences, nextQuery, state.searchFilter)
            )
        }
        if (!active) refreshCurrentDirectory()
    }

    fun updateSearchQuery(query: String) {
        uiState.update { state ->
            state.copy(
                searchQuery = query,
                files = applyClientFiltersAndSort(state.rawFiles, state.preferences, query, state.searchFilter)
            )
        }
    }

    fun toggleFilterSheet(show: Boolean) {
        uiState.update { it.copy(showFilterSheet = show) }
    }

    fun updateSearchFilter(transform: (SearchFilterState) -> SearchFilterState) {
        uiState.update { state ->
            val nextFilter = transform(state.searchFilter)
            state.copy(
                searchFilter = nextFilter,
                files = applyClientFiltersAndSort(state.rawFiles, state.preferences, state.searchQuery, nextFilter)
            )
        }
    }

    fun clearSearchFilters() {
        uiState.update { state ->
            val cleared = SearchFilterState()
            state.copy(
                searchFilter = cleared,
                files = applyClientFiltersAndSort(state.rawFiles, state.preferences, state.searchQuery, cleared)
            )
        }
    }

    fun executeSearch() {
        val account = uiState.value.activeAccount ?: return
        val query = uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            refreshCurrentDirectory()
            return
        }
        scope.launch {
            uiState.update { it.copy(isLoadingFiles = true, filesError = null) }
            val scopePath = uiState.value.currentPath.ifBlank { account.personalRootPath }
            fileRepository.searchFiles(account, query, scopePath).fold(
                onSuccess = { results ->
                    uiState.update { state ->
                        state.copy(
                            isLoadingFiles = false,
                            rawFiles = results,
                            files = applyClientFiltersAndSort(results, state.preferences, query, state.searchFilter)
                        )
                    }
                },
                onFailure = { err ->
                    uiState.update {
                        it.copy(isLoadingFiles = false, filesError = err.message ?: "搜索失败")
                    }
                }
            )
        }
    }

    fun toggleViewMode() {
        scope.launch {
            authRepository.updatePreferences { prefs ->
                prefs.copy(viewMode = if (prefs.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST)
            }
        }
    }

    fun updateSort(field: SortField, order: SortOrder) {
        scope.launch {
            authRepository.updatePreferences { prefs ->
                prefs.copy(sortField = field, sortOrder = order)
            }
            uiState.update { state ->
                val newPrefs = state.preferences.copy(sortField = field, sortOrder = order)
                state.copy(files = applyClientFiltersAndSort(state.rawFiles, newPrefs, state.searchQuery, state.searchFilter))
            }
        }
    }

    fun toggleItemSelection(item: KodFileItem) {
        uiState.update { state ->
            val updated = state.selectedPaths.toMutableSet()
            if (!updated.add(item.path)) updated.remove(item.path)
            state.copy(selectedPaths = updated)
        }
    }

    fun selectAllItems() {
        uiState.update { state -> state.copy(selectedPaths = state.files.map { it.path }.toSet()) }
    }

    fun clearSelection() {
        uiState.update { it.copy(selectedPaths = emptySet()) }
    }

    fun showActionSheet(item: KodFileItem?) = uiState.update { it.copy(actionSheetItem = item) }
    fun showDetailDialog(item: KodFileItem?) = uiState.update { it.copy(detailDialogItem = item) }
    fun showRenameDialog(item: KodFileItem?) = uiState.update { it.copy(renameDialogItem = item) }
    fun showTagSheet(item: KodFileItem?) {
        uiState.update { it.copy(tagSheetItem = item) }
        if (item != null) uiState.value.activeAccount?.let { loadTags(it) }
    }

    fun createFolder(name: String) {
        val account = uiState.value.activeAccount ?: return
        val targetDir = uiState.value.currentPath.ifBlank { account.personalRootPath }
        runFileAction("已创建文件夹 \"$name\"") { fileRepository.createFolder(account, targetDir, name) }
    }

    fun createTextFile(fileName: String, content: String) {
        val account = uiState.value.activeAccount ?: return
        val targetDir = uiState.value.currentPath.ifBlank { account.personalRootPath }
        runFileAction("已创建文件 \"$fileName\"") { fileRepository.createTextFile(account, targetDir, fileName, content) }
    }

    fun renameItem(item: KodFileItem, newName: String) {
        val account = uiState.value.activeAccount ?: return
        uiState.update { it.copy(renameDialogItem = null) }
        runFileAction("已重命名为 \"$newName\"") { fileRepository.renameItem(account, item.path, newName) }
    }

    fun deleteItems(items: List<KodFileItem>, permanently: Boolean = false) {
        val account = uiState.value.activeAccount ?: return
        uiState.update { it.copy(actionSheetItem = null, selectedPaths = emptySet()) }
        val isPerm = permanently || uiState.value.activeSpace == KodSpace.RECYCLE_BIN
        runFileAction(if (isPerm) "已永久删除 ${items.size} 项" else "已移至回收站 (${items.size} 项)") {
            fileRepository.deleteItems(account, items, isPerm)
        }
    }

    fun restoreRecycleItems(items: List<KodFileItem>) {
        val account = uiState.value.activeAccount ?: return
        uiState.update { it.copy(actionSheetItem = null, selectedPaths = emptySet()) }
        runFileAction("已还原 ${items.size} 项") { fileRepository.restoreRecycleItems(account, items) }
    }

    fun clearRecycleBin() {
        val account = uiState.value.activeAccount ?: return
        runFileAction("回收站已清空") { fileRepository.clearRecycleBin(account) }
    }

    fun toggleFavorite(item: KodFileItem) {
        val account = uiState.value.activeAccount ?: return
        uiState.update { it.copy(actionSheetItem = null) }
        val targetFav = !item.isFav
        runFileAction(if (targetFav) "已添加到收藏" else "已取消收藏") {
            fileRepository.toggleFavorite(account, item, targetFav)
        }
    }

    fun zipItems(items: List<KodFileItem>) {
        val account = uiState.value.activeAccount ?: return
        uiState.update { it.copy(actionSheetItem = null, selectedPaths = emptySet()) }
        runFileAction("已提交服务端压缩 (${items.size} 项)") { fileRepository.zipItems(account, items) }
    }

    fun unzipItem(item: KodFileItem) {
        val account = uiState.value.activeAccount ?: return
        uiState.update { it.copy(actionSheetItem = null) }
        runFileAction("已在当前目录解压 \"${item.name}\"") { fileRepository.unzipItem(account, item) }
    }

    fun submitOfflineDownload(remoteUrl: String) {
        val account = uiState.value.activeAccount ?: return
        val targetDir = uiState.value.currentPath.ifBlank { account.personalRootPath }
        runFileAction("已向网盘服务端提交离线下载任务") {
            fileRepository.offlineServerDownload(account, targetDir, remoteUrl)
        }
    }

    // Folder Picker (Copy / Move / Sync target)
    fun startCopyOrMove(items: List<KodFileItem>, isMove: Boolean) {
        val account = uiState.value.activeAccount ?: return
        val rootPath = account.personalRootPath
        val initCrumbs = listOf(BreadcrumbNode("个人空间", rootPath))
        uiState.update {
            it.copy(
                actionSheetItem = null,
                folderPickerState = FolderPickerState(
                    isMove = isMove,
                    targetItems = items,
                    currentPath = rootPath,
                    currentTitle = "个人空间",
                    breadcrumbs = initCrumbs,
                    folders = emptyList(),
                    isLoading = true
                )
            )
        }
        loadFolderPickerDir(account, rootPath)
    }

    fun openRemoteSyncFolderPicker() {
        val account = uiState.value.activeAccount ?: return
        val rootPath = account.personalRootPath
        val initCrumbs = listOf(BreadcrumbNode("个人空间", rootPath))
        uiState.update {
            it.copy(
                folderPickerState = FolderPickerState(
                    isMove = false,
                    isForSyncTarget = true,
                    targetItems = emptyList(),
                    currentPath = rootPath,
                    currentTitle = "个人空间",
                    breadcrumbs = initCrumbs,
                    folders = emptyList(),
                    isLoading = true
                )
            )
        }
        loadFolderPickerDir(account, rootPath)
    }

    fun browseFolderPickerInto(folder: KodFileItem) {
        val account = uiState.value.activeAccount ?: return
        val state = uiState.value.folderPickerState ?: return
        val nextCrumbs = state.breadcrumbs + BreadcrumbNode(folder.name, folder.path)
        uiState.update {
            it.copy(
                folderPickerState = state.copy(
                    currentPath = folder.path,
                    currentTitle = folder.name,
                    breadcrumbs = nextCrumbs,
                    isLoading = true
                )
            )
        }
        loadFolderPickerDir(account, folder.path)
    }

    fun switchFolderPickerSpace(isEnterprise: Boolean) {
        val account = uiState.value.activeAccount ?: return
        val state = uiState.value.folderPickerState ?: return
        val targetPath = if (isEnterprise) account.groupRootPath else account.personalRootPath
        val label = if (isEnterprise) KodSpace.ENTERPRISE.label else KodSpace.PERSONAL.label
        uiState.update {
            it.copy(
                folderPickerState = state.copy(
                    currentPath = targetPath,
                    currentTitle = label,
                    breadcrumbs = listOf(BreadcrumbNode(label, targetPath)),
                    isLoading = true
                )
            )
        }
        loadFolderPickerDir(account, targetPath)
    }

    fun navigateFolderPickerUp() {
        val account = uiState.value.activeAccount ?: return
        val state = uiState.value.folderPickerState ?: return
        if (state.breadcrumbs.size <= 1) return
        val nextCrumbs = state.breadcrumbs.dropLast(1)
        val last = nextCrumbs.last()
        uiState.update {
            it.copy(
                folderPickerState = state.copy(
                    currentPath = last.path,
                    currentTitle = last.label,
                    breadcrumbs = nextCrumbs,
                    isLoading = true
                )
            )
        }
        loadFolderPickerDir(account, last.path)
    }

    fun dismissFolderPicker() = uiState.update { it.copy(folderPickerState = null) }
    fun clearPickedSyncRemote() = uiState.update { it.copy(pickedSyncRemote = null) }

    fun confirmCopyOrMove() {
        val account = uiState.value.activeAccount ?: return
        val picker = uiState.value.folderPickerState ?: return
        if (picker.isForSyncTarget) {
            val label = picker.breadcrumbs.joinToString(" / ") { it.label }
            uiState.update {
                it.copy(
                    folderPickerState = null,
                    pickedSyncRemote = SyncRemoteSelection(remotePath = picker.currentPath, remoteLabel = label)
                )
            }
            return
        }
        uiState.update { it.copy(folderPickerState = null, selectedPaths = emptySet()) }
        runFileAction(
            if (picker.isMove) "已移动 ${picker.targetItems.size} 项到 ${picker.currentTitle}"
            else "已复制 ${picker.targetItems.size} 项到 ${picker.currentTitle}"
        ) {
            fileRepository.copyOrMoveItems(account, picker.targetItems, picker.currentPath, picker.isMove)
        }
    }

    private fun loadFolderPickerDir(account: KodAccount, path: String) {
        scope.launch {
            fileRepository.fetchRemoteDirectory(account, path).fold(
                onSuccess = { res ->
                    uiState.update { state ->
                        state.copy(
                            folderPickerState = state.folderPickerState?.copy(
                                folders = res.items.filter { it.isFolder },
                                isLoading = false,
                                errorMessage = null
                            )
                        )
                    }
                },
                onFailure = { err ->
                    uiState.update { state ->
                        state.copy(
                            folderPickerState = state.folderPickerState?.copy(
                                isLoading = false,
                                errorMessage = err.message ?: "读取目录失败"
                            )
                        )
                    }
                }
            )
        }
    }

    // Tags
    fun loadTags(account: KodAccount) {
        scope.launch {
            fileRepository.listTags(account).onSuccess { tags ->
                uiState.update { it.copy(availableTags = tags) }
            }
        }
    }

    fun createTag(name: String, style: String) {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            fileRepository.createTag(account, name, style).fold(
                onSuccess = {
                    loadTags(account)
                    emitMessage("已创建标签 \"$name\"")
                },
                onFailure = { emitError(it.message ?: "创建标签失败") }
            )
        }
    }

    fun toggleTagOnItem(item: KodFileItem, tag: KodTag) {
        val account = uiState.value.activeAccount ?: return
        val alreadyHas = item.tags.any { it.id == tag.id }
        scope.launch {
            fileRepository.setItemTag(account, item, tag.id, add = !alreadyHas).fold(
                onSuccess = {
                    val updatedTags = if (alreadyHas) item.tags.filterNot { it.id == tag.id } else item.tags + tag
                    val updatedItem = item.copy(tags = updatedTags)
                    uiState.update { state ->
                        state.copy(
                            tagSheetItem = updatedItem,
                            rawFiles = state.rawFiles.map { if (it.path == item.path) updatedItem else it },
                            files = state.files.map { if (it.path == item.path) updatedItem else it }
                        )
                    }
                    emitMessage(if (alreadyHas) "已移除标签 #${tag.name}" else "已添加标签 #${tag.name}")
                },
                onFailure = { emitError(it.message ?: "更新标签失败") }
            )
        }
    }

    // Preview & Inline Text/Markdown Editor
    fun openFilePreview(item: KodFileItem) {
        val account = uiState.value.activeAccount ?: return
        if (item.isFolder) {
            openFolder(item)
            return
        }
        uiState.update {
            it.copy(
                actionSheetItem = null,
                previewState = PreviewState(item = item, isLoadingText = item.isEditableText)
            )
        }
        if (item.isEditableText) {
            scope.launch {
                fileRepository.readTextFile(account, item).fold(
                    onSuccess = { content ->
                        uiState.update { state ->
                            state.copy(
                                previewState = state.previewState?.copy(
                                    textContent = content,
                                    isLoadingText = false,
                                    errorMessage = null
                                )
                            )
                        }
                    },
                    onFailure = { err ->
                        uiState.update { state ->
                            state.copy(
                                previewState = state.previewState?.copy(
                                    isLoadingText = false,
                                    errorMessage = err.message ?: "无法读取文本内容"
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    fun updatePreviewTextContent(newContent: String) {
        uiState.update { state ->
            state.copy(previewState = state.previewState?.copy(textContent = newContent))
        }
    }

    fun togglePreviewEditMode(editing: Boolean) {
        uiState.update { state ->
            state.copy(previewState = state.previewState?.copy(isEditingText = editing))
        }
    }

    fun savePreviewTextContent() {
        val account = uiState.value.activeAccount ?: return
        val preview = uiState.value.previewState ?: return
        scope.launch {
            uiState.update { it.copy(previewState = preview.copy(isSavingText = true)) }
            fileRepository.saveTextFile(account, preview.item, preview.textContent).fold(
                onSuccess = {
                    uiState.update { state ->
                        val updatedReadmeContent = if (state.directoryReadmeItem?.path == preview.item.path) {
                            preview.textContent
                        } else {
                            state.directoryReadmeContent
                        }
                        state.copy(
                            directoryReadmeContent = updatedReadmeContent,
                            previewState = state.previewState?.copy(isSavingText = false, isEditingText = false)
                        )
                    }
                    emitMessage("已保存到网盘服务器")
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    uiState.update { it.copy(previewState = it.previewState?.copy(isSavingText = false)) }
                    emitError(err.message ?: "保存失败")
                }
            )
        }
    }

    fun closePreview() = uiState.update { it.copy(previewState = null) }

    private fun runFileAction(successMsg: String, block: suspend () -> Result<Unit>) {
        scope.launch {
            uiState.update { it.copy(isWorking = true) }
            block().fold(
                onSuccess = {
                    uiState.update { it.copy(isWorking = false) }
                    emitMessage(successMsg)
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "操作失败")
                }
            )
        }
    }
}
