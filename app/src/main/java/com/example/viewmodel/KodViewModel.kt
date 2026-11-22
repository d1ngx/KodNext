package com.example.viewmodel

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.example.data.KodAccountStore
import com.example.data.KodBoxApiClient
import com.example.data.KodFormatters
import com.example.data.KodSyncEngine
import com.example.model.AppPreferences
import com.example.model.BreadcrumbNode
import com.example.model.FilterDateRange
import com.example.model.FilterFileType
import com.example.model.FilterSizeRange
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodFileTypeCategory
import com.example.model.KodShareItem
import com.example.model.KodSpace
import com.example.model.KodTag
import com.example.model.MainTab
import com.example.model.SearchFilterState
import com.example.model.ShareAccessMode
import com.example.model.ShareSubTab
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncRunStatus
import com.example.model.ThemePreference
import com.example.model.TransferStatus
import com.example.model.TransferTask
import com.example.model.TransferType
import com.example.model.ViewMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import com.example.data.KodAdminAndAccountClient
import com.example.data.KodDirectoryCache
import com.example.model.KodWorkspaceApp
import com.example.model.SyncSubTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class KodViewModel(application: Application) : AndroidViewModel(application) {

    val apiClient = KodBoxApiClient()
    private val adminAccountClient = KodAdminAndAccountClient(apiClient)
    private val directoryCache = KodDirectoryCache()
    private val accountStore = KodAccountStore(application.applicationContext)
    private val syncEngine = KodSyncEngine(application.applicationContext, apiClient)

    private val _uiState = MutableStateFlow(KodUiState())
    val uiState: StateFlow<KodUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<KodUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<KodUiEvent> = _events.asSharedFlow()

    private var activeAccountImageLoader: ImageLoader? = null
    private var activeLoaderKey: String? = null
    private val transferJobs = mutableMapOf<String, Job>()
    private var realtimeWatcherJob: Job? = null

    init {
        observeStore()
        startRealtimeSyncWatcher()
    }

    fun getAuthenticatedImageLoader(): ImageLoader {
        val acc = _uiState.value.activeAccount
        val key = "${acc?.id}|${acc?.cookiesHeader?.hashCode()}|${acc?.avatarUrl}"
        if (activeAccountImageLoader == null || activeLoaderKey != key) {
            activeLoaderKey = key
            activeAccountImageLoader = apiClient.createAuthenticatedImageLoader(getApplication(), acc)
        }
        return activeAccountImageLoader!!
    }

    private fun observeStore() {
        viewModelScope.launch {
            combine(
                accountStore.accountsFlow,
                accountStore.activeAccountIdFlow,
                accountStore.preferencesFlow,
                accountStore.syncRulesFlow
            ) { accounts, activeId, prefs, allRules ->
                val resolvedActive = accounts.find { it.id == activeId } ?: accounts.firstOrNull()
                val scopedRules = if (resolvedActive != null) {
                    allRules.filter { it.accountId == resolvedActive.id }
                } else {
                    emptyList()
                }
                Pair(Triple(accounts, resolvedActive, prefs), scopedRules)
            }.collect { (triple, scopedRules) ->
                val (accounts, resolvedActive, prefs) = triple
                val previousAccountId = _uiState.value.activeAccount?.id
                _uiState.update { state ->
                    state.copy(
                        isHydrated = true,
                        accounts = accounts,
                        activeAccount = resolvedActive,
                        preferences = prefs,
                        syncRules = scopedRules
                    )
                }
                if (resolvedActive != null && resolvedActive.id != previousAccountId) {
                    apiClient.hydrateAccountSession(resolvedActive)
                    enterSpace(KodSpace.PERSONAL, forceAccount = resolvedActive)
                    loadTags(resolvedActive)
                    loadServerPlugins(resolvedActive)
                    refreshAccountQuota(resolvedActive)
                }
            }
        }
    }

    private fun loadServerPlugins(account: KodAccount) {
        viewModelScope.launch {
            apiClient.fetchServerPluginsDiscovery(account).onSuccess { discovery ->
                _uiState.update {
                    it.copy(
                        serverPlugins = discovery.viewerPlugins,
                        workspaceApps = discovery.workspaceApps
                    )
                }
            }
        }
    }

    private fun startRealtimeSyncWatcher() {
        realtimeWatcherJob?.cancel()
        realtimeWatcherJob = viewModelScope.launch {
            while (true) {
                delay(45_000L)
                val state = _uiState.value
                val account = state.activeAccount ?: continue
                val realtimeRules = state.syncRules.filter {
                    it.enabled && it.frequency == SyncFrequency.REALTIME && it.id !in state.activeSyncingRuleIds
                }
                realtimeRules.forEach { rule ->
                    runSyncRuleInternal(account, rule, silent = true)
                }
            }
        }
    }

    // region Login & Multi-Server Management

    fun updateLoginForm(transform: (LoginFormState) -> LoginFormState) {
        _uiState.update { it.copy(loginForm = transform(it.loginForm).copy(errorMessage = null)) }
    }

    fun probeServerConnection() {
        val rawUrl = _uiState.value.loginForm.serverUrl
        viewModelScope.launch {
            _uiState.update {
                it.copy(loginForm = it.loginForm.copy(isProbing = true, errorMessage = null, probeInfo = null))
            }
            val result = apiClient.probeServer(rawUrl)
            result.fold(
                onSuccess = { probe ->
                    _uiState.update {
                        it.copy(
                            loginForm = it.loginForm.copy(
                                serverUrl = probe.normalizedUrl,
                                isProbing = false,
                                probeInfo = probe,
                                errorMessage = if (probe.reachable) null else probe.errorMessage
                            )
                        )
                    }
                    if (probe.reachable && probe.needCheckCode) {
                        refreshCaptcha()
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            loginForm = it.loginForm.copy(
                                isProbing = false,
                                errorMessage = err.message ?: "无法连接到该服务器地址"
                            )
                        )
                    }
                }
            )
        }
    }

    fun refreshCaptcha() {
        val rawUrl = _uiState.value.loginForm.serverUrl
        viewModelScope.launch {
            apiClient.fetchCaptchaBytes(rawUrl).onSuccess { bytes ->
                _uiState.update { it.copy(loginForm = it.loginForm.copy(captchaBytes = bytes)) }
            }
        }
    }

    fun submitLogin() {
        val form = _uiState.value.loginForm
        viewModelScope.launch {
            _uiState.update { it.copy(loginForm = it.loginForm.copy(isLoggingIn = true, errorMessage = null)) }
            val result = if (form.useTokenMode) {
                apiClient.loginWithToken(form.serverUrl, form.accessToken)
            } else {
                apiClient.loginWithPassword(form.serverUrl, form.username, form.password, form.checkCode)
            }
            result.fold(
                onSuccess = { account ->
                    accountStore.saveOrUpdateAccount(account, makeActive = true)
                    _uiState.update {
                        it.copy(
                            showAddAccountLogin = false,
                            loginForm = LoginFormState(),
                            activeTab = MainTab.FILES
                        )
                    }
                    emitMessage("已登录 ${account.siteName} (${account.displayName})")
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            loginForm = it.loginForm.copy(
                                isLoggingIn = false,
                                errorMessage = err.message ?: "登录失败，请检查网络与服务器配置"
                            )
                        )
                    }
                    if (err.message?.contains("验证码") == true) {
                        refreshCaptcha()
                    }
                }
            )
        }
    }

    fun openAddAccountLogin() {
        _uiState.update {
            it.copy(
                showAddAccountLogin = true,
                showSettings = false,
                loginForm = LoginFormState()
            )
        }
    }

    fun cancelAddAccountLogin() {
        if (_uiState.value.accounts.isNotEmpty()) {
            _uiState.update { it.copy(showAddAccountLogin = false) }
        }
    }

    fun switchAccount(account: KodAccount) {
        viewModelScope.launch {
            accountStore.setActiveAccount(account.id)
            _uiState.update {
                it.copy(
                    showAddAccountLogin = false,
                    showSettings = false,
                    selectedPaths = emptySet(),
                    previewState = null
                )
            }
            emitMessage("已切换至 ${account.displayName} @ ${account.hostLabel}")
        }
    }

    fun logoutAccount(account: KodAccount) {
        viewModelScope.launch {
            accountStore.removeAccount(account.id)
            _uiState.update {
                it.copy(
                    showSettings = false,
                    selectedPaths = emptySet(),
                    files = emptyList(),
                    shares = emptyList(),
                    mediaItems = emptyList()
                )
            }
            emitMessage("已移除账号 ${account.displayName}")
        }
    }

    fun refreshAccountQuota(accountOverride: KodAccount? = null) {
        val account = accountOverride ?: _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            apiClient.refreshAccountInfo(account).fold(
                onSuccess = { refreshed ->
                    accountStore.saveOrUpdateAccount(refreshed, makeActive = false)
                    loadServerPlugins(refreshed)
                    _uiState.update {
                        it.copy(
                            serverHealthMessage = "连接正常 · KodBox ${refreshed.serverVersion.ifBlank { "已就绪" }}"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(serverHealthMessage = "连接检查失败：${err.message}")
                    }
                }
            )
        }
    }

    // endregion

    // region Navigation, Spaces & Directory Browsing

    fun selectTab(tab: MainTab) {
        _uiState.update {
            it.copy(
                activeTab = tab,
                selectedPaths = emptySet(),
                isSearchActive = false,
                searchQuery = ""
            )
        }
        when (tab) {
            MainTab.FILES -> if (_uiState.value.files.isEmpty()) refreshCurrentDirectory()
            MainTab.MEDIA -> loadMediaItems()
            MainTab.SHARED -> loadShares()
            MainTab.SYNC -> Unit
            MainTab.ME -> refreshCacheSize()
        }
    }

    fun selectSyncSubTab(subTab: SyncSubTab) {
        _uiState.update { it.copy(activeSyncSubTab = subTab) }
    }

    fun enterSpace(space: KodSpace, forceAccount: KodAccount? = null) {
        val account = forceAccount ?: _uiState.value.activeAccount ?: return
        val rootPath = when (space) {
            KodSpace.PERSONAL -> account.personalRootPath
            KodSpace.ENTERPRISE -> account.groupRootPath
            else -> space.virtualPath
        }
        val initialCrumbs = listOf(BreadcrumbNode(space.label, rootPath))
        _uiState.update {
            it.copy(
                activeTab = MainTab.FILES,
                activeSpace = space,
                activeFileTypeCategory = KodFileTypeCategory.ALL,
                activeTagFilter = null,
                currentPath = rootPath,
                currentTitle = space.label,
                breadcrumbs = initialCrumbs,
                selectedPaths = emptySet(),
                isSearchActive = false,
                searchQuery = "",
                showSettings = false
            )
        }
        loadDirectory(account, rootPath, resetCrumbs = initialCrumbs)
    }

    fun selectFileTypeCategory(category: KodFileTypeCategory) {
        val account = _uiState.value.activeAccount ?: return
        val path = category.virtualPath
        val crumbs = listOf(
            BreadcrumbNode(KodSpace.FILE_TYPES.label, "{block:fileType}/"),
            BreadcrumbNode(category.label, path)
        ).distinctBy { it.path }
        _uiState.update {
            it.copy(
                activeSpace = KodSpace.FILE_TYPES,
                activeFileTypeCategory = category,
                currentPath = path,
                currentTitle = if (category == KodFileTypeCategory.ALL) "文档分类" else "分类 · ${category.label}",
                breadcrumbs = crumbs,
                selectedPaths = emptySet()
            )
        }
        loadDirectory(account, path, resetCrumbs = crumbs)
    }

    fun selectTagFilter(tag: KodTag?) {
        val account = _uiState.value.activeAccount ?: return
        val path = if (tag == null) "{block:fileTag}/" else "{userFileTag:${tag.id}}/"
        val crumbs = if (tag == null) {
            listOf(BreadcrumbNode("文件标签", "{block:fileTag}/"))
        } else {
            listOf(
                BreadcrumbNode("文件标签", "{block:fileTag}/"),
                BreadcrumbNode("#${tag.name}", path)
            )
        }
        _uiState.update {
            it.copy(
                activeSpace = KodSpace.TAGS,
                activeTagFilter = tag,
                currentPath = path,
                currentTitle = tag?.let { t -> "标签 · ${t.name}" } ?: "文件标签",
                breadcrumbs = crumbs,
                selectedPaths = emptySet()
            )
        }
        loadDirectory(account, path, resetCrumbs = crumbs)
    }

    fun openFolder(folder: KodFileItem) {
        val account = _uiState.value.activeAccount ?: return
        val newCrumbs = _uiState.value.breadcrumbs + BreadcrumbNode(folder.name, folder.path)
        _uiState.update {
            it.copy(
                currentPath = folder.path,
                currentTitle = folder.name,
                breadcrumbs = newCrumbs,
                selectedPaths = emptySet(),
                isSearchActive = false,
                searchQuery = ""
            )
        }
        loadDirectory(account, folder.path, resetCrumbs = newCrumbs)
    }

    fun navigateToBreadcrumb(index: Int) {
        val account = _uiState.value.activeAccount ?: return
        val crumbs = _uiState.value.breadcrumbs
        if (index !in crumbs.indices) return
        val sliced = crumbs.take(index + 1)
        val target = sliced.last()
        _uiState.update {
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
        val crumbs = _uiState.value.breadcrumbs
        if (crumbs.size <= 1) return false
        navigateToBreadcrumb(crumbs.lastIndex - 1)
        return true
    }

    fun refreshCurrentDirectory() {
        val account = _uiState.value.activeAccount ?: return
        val path = _uiState.value.currentPath.ifBlank { account.personalRootPath }
        directoryCache.invalidatePath(account.id, path)
        loadDirectory(account, path, forceRefresh = true)
    }

    private fun loadDirectory(
        account: KodAccount,
        path: String,
        resetCrumbs: List<BreadcrumbNode>? = null,
        forceRefresh: Boolean = false
    ) {
        viewModelScope.launch {
            val prefs = _uiState.value.preferences
            val cached = if (!forceRefresh) {
                directoryCache.get(account.id, path, prefs.sortField, prefs.sortOrder)
            } else null

            if (cached != null) {
                // 0ms instant Stale-While-Revalidate render from LRU cache
                _uiState.update { state ->
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
            } else {
                _uiState.update { it.copy(isLoadingFiles = true, filesError = null) }
            }

            val result = apiClient.listPath(
                account = account,
                path = path,
                sortField = prefs.sortField,
                sortOrder = prefs.sortOrder
            )
            result.fold(
                onSuccess = { res ->
                    directoryCache.put(account.id, path, prefs.sortField, prefs.sortOrder, res)
                    // Only apply if user is still viewing this path
                    if (_uiState.value.currentPath == path || resetCrumbs != null) {
                        _uiState.update { state ->
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
                    }
                },
                onFailure = { err ->
                    if (cached == null) {
                        _uiState.update {
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

    private fun applyClientFiltersAndSort(
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

    // endregion

    // region Search, Multi-Dimensional Filters, Sort, ViewMode & Multi-Select

    fun toggleSearch(active: Boolean) {
        _uiState.update { state ->
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
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                files = applyClientFiltersAndSort(state.rawFiles, state.preferences, query, state.searchFilter)
            )
        }
    }

    fun toggleFilterSheet(show: Boolean) {
        _uiState.update { it.copy(showFilterSheet = show) }
    }

    fun updateSearchFilter(transform: (SearchFilterState) -> SearchFilterState) {
        _uiState.update { state ->
            val nextFilter = transform(state.searchFilter)
            state.copy(
                searchFilter = nextFilter,
                files = applyClientFiltersAndSort(state.rawFiles, state.preferences, state.searchQuery, nextFilter)
            )
        }
    }

    fun clearSearchFilters() {
        _uiState.update { state ->
            val cleared = SearchFilterState()
            state.copy(
                searchFilter = cleared,
                files = applyClientFiltersAndSort(state.rawFiles, state.preferences, state.searchQuery, cleared)
            )
        }
    }

    fun executeSearch() {
        val account = _uiState.value.activeAccount ?: return
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            refreshCurrentDirectory()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFiles = true, filesError = null) }
            val scopePath = _uiState.value.currentPath.ifBlank { account.personalRootPath }
            apiClient.searchFiles(account, query, scopePath).fold(
                onSuccess = { results ->
                    _uiState.update { state ->
                        state.copy(
                            isLoadingFiles = false,
                            rawFiles = results,
                            files = applyClientFiltersAndSort(results, state.preferences, query, state.searchFilter)
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingFiles = false,
                            filesError = err.message ?: "搜索失败"
                        )
                    }
                }
            )
        }
    }

    fun toggleViewMode() {
        viewModelScope.launch {
            accountStore.updatePreferences { prefs ->
                prefs.copy(viewMode = if (prefs.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST)
            }
        }
    }

    fun updateSort(field: SortField, order: SortOrder) {
        viewModelScope.launch {
            accountStore.updatePreferences { prefs ->
                prefs.copy(sortField = field, sortOrder = order)
            }
            _uiState.update { state ->
                val newPrefs = state.preferences.copy(sortField = field, sortOrder = order)
                state.copy(files = applyClientFiltersAndSort(state.rawFiles, newPrefs, state.searchQuery, state.searchFilter))
            }
        }
    }

    fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch {
            accountStore.updatePreferences(transform)
            refreshCurrentDirectory()
        }
    }

    fun setThemePreference(pref: ThemePreference) {
        viewModelScope.launch {
            accountStore.updatePreferences { it.copy(themePreference = pref) }
        }
    }

    fun toggleItemSelection(item: KodFileItem) {
        _uiState.update { state ->
            val updated = state.selectedPaths.toMutableSet()
            if (!updated.add(item.path)) updated.remove(item.path)
            state.copy(selectedPaths = updated)
        }
    }

    fun selectAllItems() {
        _uiState.update { state ->
            state.copy(selectedPaths = state.files.map { it.path }.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet()) }
    }

    // endregion

    // region File Operations (KodBox Core Actions)

    fun showActionSheet(item: KodFileItem?) {
        _uiState.update { it.copy(actionSheetItem = item) }
    }

    fun showDetailDialog(item: KodFileItem?) {
        _uiState.update { it.copy(detailDialogItem = item) }
    }

    fun showRenameDialog(item: KodFileItem?) {
        _uiState.update { it.copy(renameDialogItem = item) }
    }

    fun showShareDialog(item: KodFileItem?) {
        _uiState.update { it.copy(shareDialogItem = item, createdShareResult = null) }
    }

    fun showTagSheet(item: KodFileItem?) {
        _uiState.update { it.copy(tagSheetItem = item) }
        if (item != null) {
            _uiState.value.activeAccount?.let { loadTags(it) }
        }
    }

    fun createFolder(name: String) {
        val account = _uiState.value.activeAccount ?: return
        val targetDir = _uiState.value.currentPath.ifBlank { account.personalRootPath }
        runFileAction("已创建文件夹 \"$name\"") {
            apiClient.createFolder(account, targetDir, name)
        }
    }

    fun createTextFile(fileName: String, content: String) {
        val account = _uiState.value.activeAccount ?: return
        val targetDir = _uiState.value.currentPath.ifBlank { account.personalRootPath }
        runFileAction("已创建文件 \"$fileName\"") {
            apiClient.createTextFile(account, targetDir, fileName, content)
        }
    }

    fun renameItem(item: KodFileItem, newName: String) {
        val account = _uiState.value.activeAccount ?: return
        _uiState.update { it.copy(renameDialogItem = null) }
        runFileAction("已重命名为 \"$newName\"") {
            apiClient.renameItem(account, item.path, newName)
        }
    }

    fun deleteItems(items: List<KodFileItem>, permanently: Boolean = false) {
        val account = _uiState.value.activeAccount ?: return
        _uiState.update { it.copy(actionSheetItem = null, selectedPaths = emptySet()) }
        val isPerm = permanently || _uiState.value.activeSpace == KodSpace.RECYCLE_BIN
        runFileAction(if (isPerm) "已永久删除 ${items.size} 项" else "已移至回收站 (${items.size} 项)") {
            apiClient.deleteItems(account, items, isPerm)
        }
    }

    fun restoreRecycleItems(items: List<KodFileItem>) {
        val account = _uiState.value.activeAccount ?: return
        _uiState.update { it.copy(actionSheetItem = null, selectedPaths = emptySet()) }
        runFileAction("已还原 ${items.size} 项") {
            apiClient.restoreRecycleItems(account, items)
        }
    }

    fun clearRecycleBin() {
        val account = _uiState.value.activeAccount ?: return
        runFileAction("回收站已清空") {
            apiClient.clearRecycleBin(account)
        }
    }

    fun toggleFavorite(item: KodFileItem) {
        val account = _uiState.value.activeAccount ?: return
        _uiState.update { it.copy(actionSheetItem = null) }
        val targetFav = !item.isFav
        runFileAction(if (targetFav) "已添加到收藏" else "已取消收藏") {
            apiClient.toggleFavorite(account, item, targetFav)
        }
    }

    fun zipItems(items: List<KodFileItem>) {
        val account = _uiState.value.activeAccount ?: return
        _uiState.update { it.copy(actionSheetItem = null, selectedPaths = emptySet()) }
        runFileAction("已提交服务端压缩 (${items.size} 项)") {
            apiClient.zipItems(account, items)
        }
    }

    fun unzipItem(item: KodFileItem) {
        val account = _uiState.value.activeAccount ?: return
        _uiState.update { it.copy(actionSheetItem = null) }
        runFileAction("已在当前目录解压 \"${item.name}\"") {
            apiClient.unzipItem(account, item)
        }
    }

    fun submitOfflineDownload(remoteUrl: String) {
        val account = _uiState.value.activeAccount ?: return
        val targetDir = _uiState.value.currentPath.ifBlank { account.personalRootPath }
        runFileAction("已向 KodBox 服务端提交离线下载任务") {
            apiClient.offlineServerDownload(account, targetDir, remoteUrl)
        }
    }

    // Folder Picker for Copy / Move
    fun startCopyOrMove(items: List<KodFileItem>, isMove: Boolean) {
        val account = _uiState.value.activeAccount ?: return
        val rootPath = account.personalRootPath
        val initCrumbs = listOf(BreadcrumbNode("个人空间", rootPath))
        _uiState.update {
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

    fun browseFolderPickerInto(folder: KodFileItem) {
        val account = _uiState.value.activeAccount ?: return
        val state = _uiState.value.folderPickerState ?: return
        val nextCrumbs = state.breadcrumbs + BreadcrumbNode(folder.name, folder.path)
        _uiState.update {
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
        val account = _uiState.value.activeAccount ?: return
        val state = _uiState.value.folderPickerState ?: return
        val targetPath = if (isEnterprise) account.groupRootPath else account.personalRootPath
        val label = if (isEnterprise) "企业网盘" else "个人空间"
        _uiState.update {
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
        val account = _uiState.value.activeAccount ?: return
        val state = _uiState.value.folderPickerState ?: return
        if (state.breadcrumbs.size <= 1) return
        val nextCrumbs = state.breadcrumbs.dropLast(1)
        val last = nextCrumbs.last()
        _uiState.update {
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

    fun dismissFolderPicker() {
        _uiState.update { it.copy(folderPickerState = null) }
    }

    fun openRemoteSyncFolderPicker() {
        val account = _uiState.value.activeAccount ?: return
        val rootPath = account.personalRootPath
        val initCrumbs = listOf(BreadcrumbNode("个人空间", rootPath))
        _uiState.update {
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

    fun clearPickedSyncRemote() {
        _uiState.update { it.copy(pickedSyncRemote = null) }
    }

    fun confirmCopyOrMove() {
        val account = _uiState.value.activeAccount ?: return
        val picker = _uiState.value.folderPickerState ?: return
        if (picker.isForSyncTarget) {
            val label = picker.breadcrumbs.joinToString(" / ") { it.label }
            _uiState.update {
                it.copy(
                    folderPickerState = null,
                    pickedSyncRemote = SyncRemoteSelection(
                        remotePath = picker.currentPath,
                        remoteLabel = label
                    )
                )
            }
            return
        }
        _uiState.update { it.copy(folderPickerState = null, selectedPaths = emptySet()) }
        runFileAction(if (picker.isMove) "已移动 ${picker.targetItems.size} 项到 ${picker.currentTitle}" else "已复制 ${picker.targetItems.size} 项到 ${picker.currentTitle}") {
            apiClient.copyOrMoveItems(account, picker.targetItems, picker.currentPath, picker.isMove)
        }
    }

    private fun loadFolderPickerDir(account: KodAccount, path: String) {
        viewModelScope.launch {
            apiClient.listPath(account, path).fold(
                onSuccess = { res ->
                    _uiState.update { state ->
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
                    _uiState.update { state ->
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

    // endregion

    // region Tags & Shares

    private fun loadTags(account: KodAccount) {
        viewModelScope.launch {
            apiClient.listTags(account).onSuccess { tags ->
                _uiState.update { it.copy(availableTags = tags) }
            }
        }
    }

    fun createTag(name: String, style: String) {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            apiClient.createTag(account, name, style).fold(
                onSuccess = {
                    loadTags(account)
                    emitMessage("已创建标签 \"$name\"")
                },
                onFailure = { emitError(it.message ?: "创建标签失败") }
            )
        }
    }

    fun toggleTagOnItem(item: KodFileItem, tag: KodTag) {
        val account = _uiState.value.activeAccount ?: return
        val alreadyHas = item.tags.any { it.id == tag.id }
        viewModelScope.launch {
            apiClient.setItemTag(account, item, tag.id, add = !alreadyHas).fold(
                onSuccess = {
                    val updatedTags = if (alreadyHas) item.tags.filterNot { it.id == tag.id } else item.tags + tag
                    val updatedItem = item.copy(tags = updatedTags)
                    _uiState.update { state ->
                        state.copy(
                            tagSheetItem = updatedItem,
                            files = state.files.map { if (it.path == item.path) updatedItem else it }
                        )
                    }
                    emitMessage(if (alreadyHas) "已移除标签 #${tag.name}" else "已添加标签 #${tag.name}")
                },
                onFailure = { emitError(it.message ?: "更新标签失败") }
            )
        }
    }

    fun selectShareSubTab(subTab: ShareSubTab) {
        _uiState.update { it.copy(activeShareSubTab = subTab) }
        val state = _uiState.value
        val needsLoad = when (subTab) {
            ShareSubTab.EXTERNAL_LINKS -> state.shares.isEmpty()
            ShareSubTab.MY_COLLAB -> state.collabShares.isEmpty()
            ShareSubTab.SHARED_WITH_ME -> state.sharedWithMeItems.isEmpty()
        }
        if (needsLoad) loadShares()
    }

    fun loadShares() {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingShares = true, sharesError = null) }
            val (linksRes, collabRes, withMeRes) = coroutineScope {
                val linksJob = async { apiClient.listShares(account) }
                val collabJob = async { apiClient.listMyCollaborationShares(account) }
                val withMeJob = async { apiClient.listSharedWithMe(account) }
                Triple(linksJob.await(), collabJob.await(), withMeJob.await())
            }

            val allFailed = linksRes.isFailure && collabRes.isFailure && withMeRes.isFailure
            _uiState.update { state ->
                state.copy(
                    isLoadingShares = false,
                    shares = linksRes.getOrElse { state.shares },
                    collabShares = collabRes.getOrElse { state.collabShares },
                    sharedWithMeItems = withMeRes.getOrElse { state.sharedWithMeItems },
                    sharesError = if (allFailed) {
                        linksRes.exceptionOrNull()?.message ?: "加载共享列表失败"
                    } else null
                )
            }
        }
    }

    fun openSharedResource(
        path: String,
        title: String,
        isFolder: Boolean,
        ext: String = "",
        size: Long = 0L
    ) {
        val account = _uiState.value.activeAccount ?: return
        val item = KodFileItem(
            path = path,
            name = title,
            isFolder = isFolder,
            size = size,
            ext = ext
        )
        if (isFolder) {
            val crumbs = listOf(BreadcrumbNode(title, path))
            _uiState.update {
                it.copy(
                    activeTab = MainTab.FILES,
                    activeSpace = if (path.startsWith("{shareItem:")) KodSpace.SHARED_WITH_ME else it.activeSpace,
                    currentPath = path,
                    currentTitle = title,
                    breadcrumbs = crumbs,
                    selectedPaths = emptySet(),
                    isSearchActive = false,
                    searchQuery = ""
                )
            }
            loadDirectory(account, path, resetCrumbs = crumbs)
        } else {
            openFilePreview(item)
        }
    }

    fun showEditShareDialog(share: KodShareItem?) {
        _uiState.update { it.copy(editingShare = share, createdShareResult = null) }
    }

    fun createShareLink(
        item: KodFileItem,
        password: String,
        expireEpochSeconds: Long,
        accessMode: ShareAccessMode
    ) {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            apiClient.createOrUpdateShare(account, item, password, expireEpochSeconds, accessMode).fold(
                onSuccess = { share ->
                    _uiState.update { it.copy(isWorking = false, createdShareResult = share) }
                    emitMessage("外链已生成")
                    loadShares()
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "创建分享链接失败")
                }
            )
        }
    }

    fun updateExistingShare(
        share: KodShareItem,
        password: String,
        expireEpochSeconds: Long,
        accessMode: ShareAccessMode
    ) {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            apiClient.updateExistingShare(account, share, password, expireEpochSeconds, accessMode).fold(
                onSuccess = { updated ->
                    _uiState.update { it.copy(isWorking = false, createdShareResult = updated, editingShare = null) }
                    emitMessage("分享权限设置已更新")
                    loadShares()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "更新分享设置失败")
                }
            )
        }
    }

    fun cancelShare(shareId: String) {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            apiClient.cancelShare(account, shareId).fold(
                onSuccess = {
                    emitMessage("已取消外链分享")
                    loadShares()
                    refreshCurrentDirectory()
                },
                onFailure = { emitError(it.message ?: "取消分享失败") }
            )
        }
    }

    fun copyText(label: String, text: String) {
        _events.tryEmit(KodUiEvent.CopyToClipboard(label, text))
    }

    // endregion

    // region Bidirectional Folder Sync

    fun createSyncRule(
        name: String,
        localTreeUri: String,
        localFolderLabel: String,
        remotePath: String,
        remoteFolderLabel: String,
        direction: SyncDirection,
        frequency: SyncFrequency,
        wifiOnly: Boolean
    ) {
        val account = _uiState.value.activeAccount ?: return
        val rule = SyncRule(
            id = UUID.randomUUID().toString(),
            accountId = account.id,
            name = name.ifBlank { "$localFolderLabel ⇄ $remoteFolderLabel" },
            localTreeUri = localTreeUri,
            localFolderLabel = localFolderLabel,
            remotePath = remotePath,
            remoteFolderLabel = remoteFolderLabel,
            direction = direction,
            frequency = frequency,
            wifiOnly = wifiOnly,
            enabled = true
        )
        viewModelScope.launch {
            accountStore.saveOrUpdateSyncRule(rule)
            KodSyncEngine.scheduleRule(getApplication(), rule)
            _uiState.update { it.copy(pickedSyncRemote = null) }
            emitMessage("已添加同步任务：${rule.name}")
            runSyncRuleInternal(account, rule, silent = false)
        }
    }

    fun toggleSyncRuleEnabled(rule: SyncRule, enabled: Boolean) {
        val updated = rule.copy(enabled = enabled)
        viewModelScope.launch {
            accountStore.saveOrUpdateSyncRule(updated)
            KodSyncEngine.scheduleRule(getApplication(), updated)
            emitMessage(if (enabled) "已启用自动同步：${rule.name}" else "已暂停自动同步：${rule.name}")
        }
    }

    fun updateSyncRuleFrequency(rule: SyncRule, frequency: SyncFrequency) {
        val updated = rule.copy(frequency = frequency)
        viewModelScope.launch {
            accountStore.saveOrUpdateSyncRule(updated)
            KodSyncEngine.scheduleRule(getApplication(), updated)
            emitMessage("同步频率已更改为：${frequency.label}")
        }
    }

    fun updateSyncRuleDirection(rule: SyncRule, direction: SyncDirection) {
        val updated = rule.copy(direction = direction)
        viewModelScope.launch {
            accountStore.saveOrUpdateSyncRule(updated)
            emitMessage("同步模式已更改为：${direction.label}")
        }
    }

    fun deleteSyncRule(rule: SyncRule) {
        viewModelScope.launch {
            KodSyncEngine.cancelRule(getApplication(), rule.id)
            accountStore.removeSyncRule(rule.id)
            emitMessage("已删除同步任务：${rule.name}")
        }
    }

    fun runSyncRuleNow(rule: SyncRule) {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            runSyncRuleInternal(account, rule, silent = false)
        }
    }

    fun runAllSyncRulesNow() {
        val account = _uiState.value.activeAccount ?: return
        val rules = _uiState.value.syncRules.filter { it.enabled }
        if (rules.isEmpty()) {
            emitMessage("暂无已启用的同步任务")
            return
        }
        viewModelScope.launch {
            rules.forEach { rule ->
                runSyncRuleInternal(account, rule, silent = false)
            }
        }
    }

    private suspend fun runSyncRuleInternal(account: KodAccount, rule: SyncRule, silent: Boolean) {
        if (rule.id in _uiState.value.activeSyncingRuleIds) return
        _uiState.update { it.copy(activeSyncingRuleIds = it.activeSyncingRuleIds + rule.id) }
        val runningRule = rule.copy(lastStatus = SyncRunStatus.SYNCING)
        accountStore.saveOrUpdateSyncRule(runningRule)

        val outcome = syncEngine.executeSyncRule(account, rule)
        outcome.fold(
            onSuccess = { res ->
                val done = rule.copy(
                    lastSyncTime = System.currentTimeMillis(),
                    lastStatus = SyncRunStatus.SUCCESS,
                    lastSummary = res.summary
                )
                accountStore.saveOrUpdateSyncRule(done)
                if (!silent) {
                    emitMessage("${rule.name} 同步完成 (${res.summary})")
                }
                if (res.uploadedCount > 0) {
                    refreshCurrentDirectory()
                }
            },
            onFailure = { err ->
                val failed = rule.copy(
                    lastSyncTime = System.currentTimeMillis(),
                    lastStatus = SyncRunStatus.ERROR,
                    lastSummary = err.message ?: "同步异常"
                )
                accountStore.saveOrUpdateSyncRule(failed)
                if (!silent) {
                    emitError("${rule.name} 同步失败：${err.message}")
                }
            }
        )
        _uiState.update { it.copy(activeSyncingRuleIds = it.activeSyncingRuleIds - rule.id) }
    }

    // endregion

    // region Media Gallery

    fun setMediaFilter(filter: String) {
        _uiState.update { it.copy(mediaFilter = filter) }
    }

    fun loadMediaItems() {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMedia = true, mediaError = null) }
            val imgRes = apiClient.listPath(account, "{userFileType:image}/", SortField.MODIFY_TIME, SortOrder.DESC)
            val vidRes = apiClient.listPath(account, "{userFileType:movie}/", SortField.MODIFY_TIME, SortOrder.DESC)
            val audRes = apiClient.listPath(account, "{userFileType:music}/", SortField.MODIFY_TIME, SortOrder.DESC)
            val combined = buildList {
                imgRes.getOrNull()?.items?.let { addAll(it) }
                vidRes.getOrNull()?.items?.let { addAll(it) }
                audRes.getOrNull()?.items?.let { addAll(it) }
            }.distinctBy { it.path }.sortedByDescending { it.modifyTime }

            if (combined.isEmpty() && imgRes.isFailure && vidRes.isFailure && audRes.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoadingMedia = false,
                        mediaError = imgRes.exceptionOrNull()?.message ?: "加载媒体库失败"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(isLoadingMedia = false, mediaItems = combined, mediaError = null)
                }
            }
        }
    }

    // endregion

    // region Preview & Built-in Text/Markdown Editor

    fun openFilePreview(item: KodFileItem) {
        val account = _uiState.value.activeAccount ?: return
        if (item.isFolder) {
            openFolder(item)
            return
        }
        _uiState.update {
            it.copy(
                actionSheetItem = null,
                previewState = PreviewState(
                    item = item,
                    isLoadingText = item.isEditableText
                )
            )
        }
        if (item.isEditableText) {
            viewModelScope.launch {
                apiClient.readTextFile(account, item).fold(
                    onSuccess = { content ->
                        _uiState.update { state ->
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
                        _uiState.update { state ->
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
        _uiState.update { state ->
            state.copy(previewState = state.previewState?.copy(textContent = newContent))
        }
    }

    fun togglePreviewEditMode(editing: Boolean) {
        _uiState.update { state ->
            state.copy(previewState = state.previewState?.copy(isEditingText = editing))
        }
    }

    fun savePreviewTextContent() {
        val account = _uiState.value.activeAccount ?: return
        val preview = _uiState.value.previewState ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(previewState = preview.copy(isSavingText = true)) }
            apiClient.saveTextFile(account, preview.item, preview.textContent).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(previewState = it.previewState?.copy(isSavingText = false, isEditingText = false))
                    }
                    emitMessage("已保存到 KodBox 服务器")
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(previewState = it.previewState?.copy(isSavingText = false)) }
                    emitError(err.message ?: "保存失败")
                }
            )
        }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewState = null) }
    }

    // endregion

    // region Uploads & Downloads (Zero-Permission Android Storage Compliant)

    fun uploadUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val account = _uiState.value.activeAccount ?: return
        val targetDir = _uiState.value.currentPath.ifBlank { account.personalRootPath }
        val resolver = getApplication<Application>().contentResolver

        uris.forEach { uri ->
            val (fileName, fileSize) = queryUriMetadata(uri)
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"
            val taskId = UUID.randomUUID().toString()
            val task = TransferTask(
                id = taskId,
                fileName = fileName,
                remotePath = targetDir,
                type = TransferType.UPLOAD,
                totalBytes = fileSize
            )
            addOrUpdateTransfer(task)

            val job = viewModelScope.launch {
                val result = apiClient.uploadFile(
                    account = account,
                    targetDir = targetDir,
                    fileName = fileName,
                    mimeType = mimeType,
                    inputStreamProvider = { resolver.openInputStream(uri) },
                    totalBytes = fileSize,
                    onProgress = { written, total ->
                        val ratio = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                        updateTransfer(taskId) {
                            it.copy(progress = ratio, bytesTransferred = written, totalBytes = total)
                        }
                    }
                )
                result.fold(
                    onSuccess = {
                        updateTransfer(taskId) {
                            it.copy(status = TransferStatus.COMPLETED, progress = 1f, bytesTransferred = it.totalBytes.coerceAtLeast(it.bytesTransferred))
                        }
                        emitMessage("上传完成：$fileName")
                        refreshCurrentDirectory()
                    },
                    onFailure = { err ->
                        updateTransfer(taskId) {
                            it.copy(status = TransferStatus.FAILED, errorMessage = err.message ?: "上传失败")
                        }
                        emitError("上传失败 ($fileName)：${err.message}")
                    }
                )
            }
            transferJobs[taskId] = job
        }
        emitMessage("已添加 ${uris.size} 个上传任务")
    }

    fun downloadItems(items: List<KodFileItem>) {
        val account = _uiState.value.activeAccount ?: return
        val filesOnly = items.filterNot { it.isFolder }
        if (filesOnly.isEmpty()) {
            emitMessage("请选择具体文件进行下载（文件夹请先压缩后下载）")
            return
        }
        _uiState.update { it.copy(actionSheetItem = null, selectedPaths = emptySet()) }
        val baseDir = File(getApplication<Application>().filesDir, "downloads")

        filesOnly.forEach { item ->
            val destFile = KodFormatters.safeConfinedFile(baseDir, item.name)
            val taskId = UUID.randomUUID().toString()
            addOrUpdateTransfer(
                TransferTask(
                    id = taskId,
                    fileName = item.name,
                    remotePath = item.path,
                    localPath = destFile.absolutePath,
                    type = TransferType.DOWNLOAD,
                    totalBytes = item.size
                )
            )
            val job = viewModelScope.launch {
                val res = apiClient.downloadFile(
                    account = account,
                    item = item,
                    destinationFile = destFile,
                    onProgress = { read, total ->
                        val ratio = if (total > 0L) (read.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                        updateTransfer(taskId) {
                            it.copy(progress = ratio, bytesTransferred = read, totalBytes = total)
                        }
                    }
                )
                res.fold(
                    onSuccess = { saved ->
                        // Also mirror to public Downloads via zero-permission MediaStore on Android 10+
                        mirrorToPublicDownloadsIfSupported(saved, item.normalizedExt)
                        updateTransfer(taskId) {
                            it.copy(
                                status = TransferStatus.COMPLETED,
                                progress = 1f,
                                localPath = saved.absolutePath,
                                bytesTransferred = saved.length()
                            )
                        }
                        emitMessage("下载完成：${item.name}")
                    },
                    onFailure = { err ->
                        updateTransfer(taskId) {
                            it.copy(status = TransferStatus.FAILED, errorMessage = err.message ?: "下载失败")
                        }
                        emitError("下载失败 (${item.name})：${err.message}")
                    }
                )
            }
            transferJobs[taskId] = job
        }
        emitMessage("已开始下载 ${filesOnly.size} 个文件")
    }

    fun openDownloadedTransfer(task: TransferTask) {
        if (task.localPath.isBlank()) return
        val file = File(task.localPath)
        if (!file.exists()) {
            emitError("本地文件不存在，请重新下载")
            return
        }
        val ext = file.extension
        _events.tryEmit(KodUiEvent.OpenLocalFile(file, KodFormatters.mimeTypeForExtension(ext)))
    }

    fun cancelTransfer(taskId: String) {
        transferJobs.remove(taskId)?.cancel()
        updateTransfer(taskId) { it.copy(status = TransferStatus.CANCELLED) }
    }

    fun clearFinishedTransfers() {
        _uiState.update { state ->
            state.copy(transfers = state.transfers.filter { it.status == TransferStatus.RUNNING })
        }
    }

    // endregion

    // region Settings

    fun openSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
        if (show) {
            refreshCacheSize()
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = calculateDirSize(getApplication<Application>().cacheDir)
            _uiState.update { it.copy(cacheSizeBytes = bytes) }
        }
    }

    fun clearImageCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getAuthenticatedImageLoader().memoryCache?.clear()
                File(getApplication<Application>().cacheDir, "image_cache").deleteRecursively()
                getApplication<Application>().cacheDir.listFiles()?.forEach { child ->
                    if (child.name.contains("cache", ignoreCase = true) || child.name.contains("coil", ignoreCase = true)) {
                        child.deleteRecursively()
                    }
                }
            }
            val remaining = calculateDirSize(getApplication<Application>().cacheDir)
            _uiState.update { it.copy(cacheSizeBytes = remaining) }
            emitMessage("本地缓存已清理")
        }
    }

    private fun calculateDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { calculateDirSize(it) } ?: 0L
    }

    // region Workspace Apps (Project / IM / AI / Plugins), Account Center & Admin Console

    fun toggleAppHubSheet(show: Boolean) {
        _uiState.update { it.copy(showAppHubSheet = show) }
    }

    fun openWorkspaceApp(app: KodWorkspaceApp) {
        _uiState.update { it.copy(activeWorkspaceApp = app, showAppHubSheet = false) }
    }

    fun closeWorkspaceApp() {
        _uiState.update { it.copy(activeWorkspaceApp = null) }
    }

    fun pinTopLeftApp(appId: String) {
        updatePreferences { it.copy(pinnedAppId = appId) }
        emitMessage("已置顶到左上角快捷入口")
    }

    fun openWebAdminPortal() {
        val account = _uiState.value.activeAccount ?: return
        if (!account.isAdmin) {
            emitError("仅系统管理员可访问后台管理")
            return
        }
        openWorkspaceApp(
            KodWorkspaceApp(
                id = "admin",
                title = "KodBox 网页后台管理",
                description = "完整网页端系统管理控制台",
                launchUrl = "${account.serverUrl.trimEnd('/')}/#admin",
                isRouterPage = true,
                isAdminOnly = true
            )
        )
    }

    fun openAccountCenter(open: Boolean) {
        _uiState.update { it.copy(showAccountCenter = open) }
        if (open) {
            loadAccountCenterData()
        }
    }

    fun loadAccountCenterData() {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAccountCenter = true) }
            val (devicesRes, logsRes) = coroutineScope {
                val devicesJob = async { adminAccountClient.fetchUserDevices(account) }
                val logsJob = async { adminAccountClient.fetchUserLogs(account) }
                devicesJob.await() to logsJob.await()
            }
            _uiState.update { state ->
                state.copy(
                    isLoadingAccountCenter = false,
                    userDevices = devicesRes.getOrElse { state.userDevices },
                    userLogs = logsRes.getOrElse { state.userLogs }
                )
            }
        }
    }

    fun updateAccountProfile(nickName: String, email: String, phone: String, sex: String) {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val updates = buildList {
                if (nickName.trim() != account.nickName) add("nickName" to nickName.trim())
                if (email.trim() != account.email) add("email" to email.trim())
                if (phone.trim() != account.phone) add("phone" to phone.trim())
                if (sex != account.sex) add("sex" to sex)
            }
            if (updates.isEmpty()) {
                _uiState.update { it.copy(isWorking = false) }
                emitMessage("个人资料未发生变化")
                return@launch
            }
            var firstError: String? = null
            for ((k, v) in updates) {
                val res = adminAccountClient.updateUserProfileField(account, k, v)
                if (res.isFailure) {
                    firstError = res.exceptionOrNull()?.message ?: "更新 $k 失败"
                    break
                }
            }
            _uiState.update { it.copy(isWorking = false) }
            if (firstError != null) {
                emitError(firstError)
            } else {
                val updated = account.copy(
                    nickName = nickName.trim().ifBlank { account.nickName },
                    email = email.trim(),
                    phone = phone.trim(),
                    sex = sex
                )
                accountStore.saveOrUpdateAccount(updated, makeActive = true)
                emitMessage("个人资料已同步到 KodBox")
            }
        }
    }

    fun changeAccountPassword(oldPassword: String, newPassword: String) {
        val account = _uiState.value.activeAccount ?: return
        if (oldPassword.isBlank() || newPassword.length < 6) {
            emitError("请输入原密码，且新密码不少于 6 位")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            adminAccountClient.changePassword(account, oldPassword, newPassword).fold(
                onSuccess = {
                    _uiState.update { it.copy(isWorking = false) }
                    emitMessage("登录密码已修改")
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "修改密码失败")
                }
            )
        }
    }

    fun openAdminConsole(open: Boolean) {
        val account = _uiState.value.activeAccount
        if (open && account?.isAdmin != true) {
            emitError("当前账号不是系统管理员")
            return
        }
        _uiState.update { it.copy(showAdminConsole = open) }
        if (open) {
            loadAdminOverview()
        }
    }

    fun loadAdminOverview() {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAdmin = true, adminError = null) }
            adminAccountClient.fetchAdminOverview(account).fold(
                onSuccess = { overview ->
                    _uiState.update {
                        it.copy(
                            isLoadingAdmin = false,
                            adminOverview = overview,
                            adminError = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingAdmin = false,
                            adminError = err.message ?: "加载后台管理数据失败"
                        )
                    }
                }
            )
        }
    }

    fun toggleAdminPluginStatus(pluginId: String, enable: Boolean) {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            adminAccountClient.toggleAdminPlugin(account, pluginId, enable).fold(
                onSuccess = {
                    _uiState.update { it.copy(isWorking = false) }
                    emitMessage(if (enable) "已启用插件 $pluginId" else "已停用插件 $pluginId")
                    loadAdminOverview()
                    loadServerPlugins(account)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "切换插件状态失败")
                }
            )
        }
    }

    // endregion

    private fun runFileAction(successMsg: String, block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            block().fold(
                onSuccess = {
                    _uiState.update { it.copy(isWorking = false) }
                    emitMessage(successMsg)
                    _uiState.value.activeAccount?.let { directoryCache.invalidateAccount(it.id) }
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "操作失败")
                }
            )
        }
    }

    private fun addOrUpdateTransfer(task: TransferTask) {
        _uiState.update { state ->
            state.copy(transfers = listOf(task) + state.transfers.filterNot { it.id == task.id })
        }
    }

    private fun updateTransfer(taskId: String, transform: (TransferTask) -> TransferTask) {
        _uiState.update { state ->
            state.copy(transfers = state.transfers.map { if (it.id == taskId) transform(it) else it })
        }
    }

    private fun queryUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "upload_${System.currentTimeMillis()}"
        var size = -1L
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx).orEmpty().ifBlank { name }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }

    private suspend fun mirrorToPublicDownloadsIfSupported(sourceFile: File, ext: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Downloads.MIME_TYPE, KodFormatters.mimeTypeForExtension(ext))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/KodNext")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            }
        }
    }

    private fun emitMessage(message: String) {
        _events.tryEmit(KodUiEvent.ShowMessage(message, isError = false))
    }

    private fun emitError(message: String) {
        _events.tryEmit(KodUiEvent.ShowMessage(message, isError = true))
    }
}
