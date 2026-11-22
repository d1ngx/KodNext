package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.example.KodApplication
import com.example.data.KodBoxApiClient
import com.example.data.repository.KodAppContainer
import com.example.model.AppPreferences
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodFileTypeCategory
import com.example.model.KodShareItem
import com.example.model.KodSpace
import com.example.model.KodTag
import com.example.model.KodWorkspaceApp
import com.example.model.MainTab
import com.example.model.SearchFilterState
import com.example.model.ShareAccessMode
import com.example.model.ShareSubTab
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncSubTab
import com.example.model.ThemePreference
import com.example.model.TransferStatus
import com.example.model.TransferTask
import com.example.model.TransferType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Slim ViewModel facade wired to the process-wide [KodAppContainer] singleton graph
 * and four focused domain coordinators ([KodSessionCoordinator], [KodFilesCoordinator],
 * [KodShareAndMediaCoordinator], [KodSyncAndTransferCoordinator]).
 */
class KodViewModel(application: Application) : AndroidViewModel(application) {

    private val container: KodAppContainer =
        (application as? KodApplication)?.container ?: KodAppContainer(application)

    val apiClient: KodBoxApiClient = container.apiClient

    private val _uiState = MutableStateFlow(KodUiState())
    val uiState: StateFlow<KodUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<KodUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<KodUiEvent> = _events.asSharedFlow()

    private var previousCompletedUploadIds: Set<String> = emptySet()

    private val filesCoordinator = KodFilesCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        fileRepository = container.fileRepository,
        authRepository = container.authRepository,
        emitMessage = ::emitMessage,
        emitError = ::emitError
    )

    private val sessionCoordinator = KodSessionCoordinator(
        application = application,
        scope = viewModelScope,
        uiState = _uiState,
        authRepository = container.authRepository,
        fileRepository = container.fileRepository,
        emitMessage = ::emitMessage,
        emitError = ::emitError,
        onRefreshCurrentDirectory = { filesCoordinator.refreshCurrentDirectory() }
    )

    private val shareAndMediaCoordinator = KodShareAndMediaCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        fileRepository = container.fileRepository,
        emitMessage = ::emitMessage,
        emitError = ::emitError,
        onRefreshCurrentDirectory = { filesCoordinator.refreshCurrentDirectory() },
        onOpenFilePreview = { filesCoordinator.openFilePreview(it) },
        onOpenSharedFolder = { acc, path, crumbs -> filesCoordinator.openDirectoryDirect(acc, path, crumbs) }
    )

    private val syncAndTransferCoordinator = KodSyncAndTransferCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        syncRepository = container.syncRepository,
        transferRepository = container.transferRepository,
        emitMessage = ::emitMessage,
        emitError = ::emitError,
        emitOpenLocalFile = { file, mime -> _events.tryEmit(KodUiEvent.OpenLocalFile(file, mime)) },
        onRefreshCurrentDirectory = { filesCoordinator.refreshCurrentDirectory() }
    )

    init {
        runStartupMigrations()
        observeSessionAndPreferences()
        observeRoomSyncAndTransfers()
        syncAndTransferCoordinator.startRealtimeSyncWatcher()
    }

    private fun runStartupMigrations() {
        viewModelScope.launch {
            runCatching { container.authRepository.migrateLegacySecretsIfNeeded() }
            runCatching { container.syncRepository.migrateLegacyRulesIfNeeded() }
        }
    }

    private fun observeSessionAndPreferences() {
        viewModelScope.launch {
            combine(
                container.authRepository.accountsFlow,
                container.authRepository.activeAccountIdFlow,
                container.authRepository.preferencesFlow
            ) { accounts, activeId, prefs ->
                val resolvedActive = accounts.find { it.id == activeId } ?: accounts.firstOrNull()
                Triple(accounts, resolvedActive, prefs)
            }.collect { (accounts, resolvedActive, prefs) ->
                val previousAccountId = _uiState.value.activeAccount?.id
                _uiState.update { state ->
                    state.copy(
                        isHydrated = true,
                        accounts = accounts,
                        activeAccount = resolvedActive,
                        preferences = prefs
                    )
                }
                if (resolvedActive != null && resolvedActive.id != previousAccountId) {
                    container.authRepository.hydrateSession(resolvedActive)
                    filesCoordinator.enterSpace(KodSpace.PERSONAL, forceAccount = resolvedActive, switchToFilesTab = false)
                    filesCoordinator.loadHomeOverview(forceAccount = resolvedActive)
                    filesCoordinator.loadTags(resolvedActive)
                    sessionCoordinator.loadServerPlugins(resolvedActive)
                    sessionCoordinator.refreshAccountQuota(resolvedActive)
                }
            }
        }
    }

    private fun observeRoomSyncAndTransfers() {
        viewModelScope.launch {
            combine(
                container.authRepository.activeAccountIdFlow,
                container.authRepository.accountsFlow,
                container.syncRepository.observeAllRules(),
                container.transferRepository.observeAllTransfers()
            ) { activeId, accounts, allRules, allTransfers ->
                val resolvedId = accounts.find { it.id == activeId }?.id ?: accounts.firstOrNull()?.id
                val scopedRules = if (resolvedId != null) allRules.filter { it.accountId == resolvedId } else emptyList()
                val scopedTransfers = if (resolvedId != null) {
                    allTransfers.filter { it.accountId == resolvedId || it.accountId.isBlank() }
                } else emptyList()
                scopedRules to scopedTransfers
            }.collect { (scopedRules, scopedTransfers) ->
                // Auto-refresh directory when a background upload newly completes
                val completedUploads = scopedTransfers
                    .filter { it.type == TransferType.UPLOAD && it.status == TransferStatus.COMPLETED }
                    .map { it.id }
                    .toSet()
                val newlyCompleted = completedUploads - previousCompletedUploadIds
                val shouldRefresh = previousCompletedUploadIds.isNotEmpty() && newlyCompleted.isNotEmpty()
                previousCompletedUploadIds = completedUploads

                _uiState.update { state ->
                    state.copy(
                        syncRules = scopedRules,
                        transfers = scopedTransfers
                    )
                }
                if (shouldRefresh) {
                    filesCoordinator.refreshCurrentDirectory()
                }
            }
        }
    }

    // Session, Login, QR, Account Center & Settings
    fun getAuthenticatedImageLoader(): ImageLoader = sessionCoordinator.getAuthenticatedImageLoader()
    fun updateLoginForm(transform: (LoginFormState) -> LoginFormState) = sessionCoordinator.updateLoginForm(transform)
    fun resetLoginToServerStep() = sessionCoordinator.resetLoginToServerStep()
    fun openQrScanner(open: Boolean) = sessionCoordinator.openQrScanner(open)
    fun handleScannedQrContent(rawContent: String) = sessionCoordinator.handleScannedQrContent(rawContent)
    fun probeServerConnection() = sessionCoordinator.probeServerConnection()
    fun refreshCaptcha() = sessionCoordinator.refreshCaptcha()
    fun submitLogin() = sessionCoordinator.submitLogin()
    fun openAddAccountLogin() = sessionCoordinator.openAddAccountLogin()
    fun cancelAddAccountLogin() = sessionCoordinator.cancelAddAccountLogin()
    fun switchAccount(account: KodAccount) = sessionCoordinator.switchAccount(account)
    fun logoutAccount(account: KodAccount) = sessionCoordinator.logoutAccount(account)
    fun refreshAccountQuota(accountOverride: KodAccount? = null) = sessionCoordinator.refreshAccountQuota(accountOverride)
    fun updatePreferences(transform: (AppPreferences) -> AppPreferences) = sessionCoordinator.updatePreferences(transform)
    fun setThemePreference(pref: ThemePreference) = sessionCoordinator.setThemePreference(pref)
    fun openSettings(show: Boolean) = sessionCoordinator.openSettings(show)
    fun refreshCacheSize() = sessionCoordinator.refreshCacheSize()
    fun clearImageCache() = sessionCoordinator.clearImageCache()
    fun toggleAppHubSheet(show: Boolean) = sessionCoordinator.toggleAppHubSheet(show)
    fun openWorkspaceApp(app: KodWorkspaceApp) = sessionCoordinator.openWorkspaceApp(app)
    fun closeWorkspaceApp() = sessionCoordinator.closeWorkspaceApp()
    fun openWebAdminPortal() = sessionCoordinator.openWebAdminPortal()
    fun openWebDrivePortal() = sessionCoordinator.openWebDrivePortal()
    fun openWebAccountPortal() = sessionCoordinator.openWebAccountPortal()
    fun openAccountCenter(open: Boolean) = sessionCoordinator.openAccountCenter(open)
    fun loadAccountCenterData() = sessionCoordinator.loadAccountCenterData()
    fun updateAccountProfile(nickName: String, email: String, phone: String, sex: String) =
        sessionCoordinator.updateAccountProfile(nickName, email, phone, sex)
    fun changeAccountPassword(oldPassword: String, newPassword: String) =
        sessionCoordinator.changeAccountPassword(oldPassword, newPassword)

    // Tabs & Files
    fun selectTab(tab: MainTab) {
        _uiState.update {
            it.copy(
                activeTab = tab,
                selectedPaths = emptySet(),
                isSearchActive = false,
                searchQuery = "",
                showSharedScreen = false,
                showSyncScreen = false
            )
        }
        when (tab) {
            MainTab.HOME -> {
                if (_uiState.value.homeFavorites.isEmpty() && _uiState.value.homeRecentFiles.isEmpty()) {
                    filesCoordinator.loadHomeOverview()
                }
            }
            MainTab.FILES -> if (_uiState.value.files.isEmpty()) filesCoordinator.refreshCurrentDirectory()
            MainTab.TRANSFERS -> Unit
            MainTab.ME -> sessionCoordinator.refreshCacheSize()
        }
    }

    fun loadHomeOverview(forceRefresh: Boolean = false) = filesCoordinator.loadHomeOverview(forceRefresh = forceRefresh)
    fun openFilesWithSearch() = filesCoordinator.openFilesWithSearch()

    fun openSharedScreen(initialSubTab: ShareSubTab? = null) {
        _uiState.update {
            it.copy(
                showSharedScreen = true,
                activeShareSubTab = initialSubTab ?: it.activeShareSubTab
            )
        }
        shareAndMediaCoordinator.loadShares()
    }

    fun closeSharedScreen() {
        _uiState.update { it.copy(showSharedScreen = false) }
    }

    fun openSyncScreen() {
        _uiState.update { it.copy(showSyncScreen = true) }
    }

    fun closeSyncScreen() {
        _uiState.update { it.copy(showSyncScreen = false) }
    }

    fun selectSyncSubTab(subTab: SyncSubTab) = _uiState.update { it.copy(activeSyncSubTab = subTab) }
    fun enterSpace(space: KodSpace, forceAccount: KodAccount? = null) = filesCoordinator.enterSpace(space, forceAccount)
    fun selectFileTypeCategory(category: KodFileTypeCategory) = filesCoordinator.selectFileTypeCategory(category)
    fun selectTagFilter(tag: KodTag?) = filesCoordinator.selectTagFilter(tag)
    fun openFolder(folder: KodFileItem) = filesCoordinator.openFolder(folder)
    fun navigateToBreadcrumb(index: Int) = filesCoordinator.navigateToBreadcrumb(index)
    fun navigateUp(): Boolean = filesCoordinator.navigateUp()
    fun refreshCurrentDirectory() = filesCoordinator.refreshCurrentDirectory()
    fun toggleDirectoryReadmeExpanded() = filesCoordinator.toggleDirectoryReadmeExpanded()
    fun toggleSearch(active: Boolean) = filesCoordinator.toggleSearch(active)
    fun updateSearchQuery(query: String) = filesCoordinator.updateSearchQuery(query)
    fun toggleFilterSheet(show: Boolean) = filesCoordinator.toggleFilterSheet(show)
    fun updateSearchFilter(transform: (SearchFilterState) -> SearchFilterState) = filesCoordinator.updateSearchFilter(transform)
    fun clearSearchFilters() = filesCoordinator.clearSearchFilters()
    fun executeSearch() = filesCoordinator.executeSearch()
    fun toggleViewMode() = filesCoordinator.toggleViewMode()
    fun updateSort(field: SortField, order: SortOrder) = filesCoordinator.updateSort(field, order)
    fun toggleItemSelection(item: KodFileItem) = filesCoordinator.toggleItemSelection(item)
    fun selectAllItems() = filesCoordinator.selectAllItems()
    fun clearSelection() = filesCoordinator.clearSelection()
    fun showActionSheet(item: KodFileItem?) = filesCoordinator.showActionSheet(item)
    fun showDetailDialog(item: KodFileItem?) = filesCoordinator.showDetailDialog(item)
    fun showRenameDialog(item: KodFileItem?) = filesCoordinator.showRenameDialog(item)
    fun showTagSheet(item: KodFileItem?) = filesCoordinator.showTagSheet(item)
    fun createFolder(name: String) = filesCoordinator.createFolder(name)
    fun createTextFile(fileName: String, content: String) = filesCoordinator.createTextFile(fileName, content)
    fun renameItem(item: KodFileItem, newName: String) = filesCoordinator.renameItem(item, newName)
    fun deleteItems(items: List<KodFileItem>, permanently: Boolean = false) = filesCoordinator.deleteItems(items, permanently)
    fun restoreRecycleItems(items: List<KodFileItem>) = filesCoordinator.restoreRecycleItems(items)
    fun clearRecycleBin() = filesCoordinator.clearRecycleBin()
    fun toggleFavorite(item: KodFileItem) = filesCoordinator.toggleFavorite(item)
    fun zipItems(items: List<KodFileItem>) = filesCoordinator.zipItems(items)
    fun unzipItem(item: KodFileItem) = filesCoordinator.unzipItem(item)
    fun submitOfflineDownload(remoteUrl: String) = filesCoordinator.submitOfflineDownload(remoteUrl)
    fun startCopyOrMove(items: List<KodFileItem>, isMove: Boolean) = filesCoordinator.startCopyOrMove(items, isMove)
    fun openRemoteSyncFolderPicker() = filesCoordinator.openRemoteSyncFolderPicker()
    fun browseFolderPickerInto(folder: KodFileItem) = filesCoordinator.browseFolderPickerInto(folder)
    fun switchFolderPickerSpace(isEnterprise: Boolean) = filesCoordinator.switchFolderPickerSpace(isEnterprise)
    fun navigateFolderPickerUp() = filesCoordinator.navigateFolderPickerUp()
    fun dismissFolderPicker() = filesCoordinator.dismissFolderPicker()
    fun clearPickedSyncRemote() = filesCoordinator.clearPickedSyncRemote()
    fun confirmCopyOrMove() = filesCoordinator.confirmCopyOrMove()
    fun createTag(name: String, style: String) = filesCoordinator.createTag(name, style)
    fun toggleTagOnItem(item: KodFileItem, tag: KodTag) = filesCoordinator.toggleTagOnItem(item, tag)
    fun openFilePreview(item: KodFileItem) = filesCoordinator.openFilePreview(item)
    fun updatePreviewTextContent(newContent: String) = filesCoordinator.updatePreviewTextContent(newContent)
    fun togglePreviewEditMode(editing: Boolean) = filesCoordinator.togglePreviewEditMode(editing)
    fun savePreviewTextContent() = filesCoordinator.savePreviewTextContent()
    fun closePreview() = filesCoordinator.closePreview()

    // Shares & Media
    fun showShareDialog(item: KodFileItem?) = shareAndMediaCoordinator.showShareDialog(item)
    fun showEditShareDialog(share: KodShareItem?) = shareAndMediaCoordinator.showEditShareDialog(share)
    fun selectShareSubTab(subTab: ShareSubTab) = shareAndMediaCoordinator.selectShareSubTab(subTab)
    fun loadShares() = shareAndMediaCoordinator.loadShares()
    fun openSharedResource(path: String, title: String, isFolder: Boolean, ext: String = "", size: Long = 0L) =
        shareAndMediaCoordinator.openSharedResource(path, title, isFolder, ext, size)
    fun createShareLink(item: KodFileItem, password: String, expireEpochSeconds: Long, accessMode: ShareAccessMode) =
        shareAndMediaCoordinator.createShareLink(item, password, expireEpochSeconds, accessMode)
    fun updateExistingShare(share: KodShareItem, password: String, expireEpochSeconds: Long, accessMode: ShareAccessMode) =
        shareAndMediaCoordinator.updateExistingShare(share, password, expireEpochSeconds, accessMode)
    fun cancelShare(shareId: String) = shareAndMediaCoordinator.cancelShare(shareId)
    fun setMediaFilter(filter: String) = shareAndMediaCoordinator.setMediaFilter(filter)
    fun loadMediaItems() = shareAndMediaCoordinator.loadMediaItems()
    fun copyText(label: String, text: String) {
        _events.tryEmit(KodUiEvent.CopyToClipboard(label, text))
    }

    // Sync & WorkManager Transfers
    fun createSyncRule(
        name: String,
        localTreeUri: String,
        localFolderLabel: String,
        remotePath: String,
        remoteFolderLabel: String,
        direction: SyncDirection,
        frequency: SyncFrequency,
        wifiOnly: Boolean
    ) = syncAndTransferCoordinator.createSyncRule(
        name, localTreeUri, localFolderLabel, remotePath, remoteFolderLabel, direction, frequency, wifiOnly
    )
    fun toggleSyncRuleEnabled(rule: SyncRule, enabled: Boolean) = syncAndTransferCoordinator.toggleSyncRuleEnabled(rule, enabled)
    fun updateSyncRuleFrequency(rule: SyncRule, frequency: SyncFrequency) = syncAndTransferCoordinator.updateSyncRuleFrequency(rule, frequency)
    fun updateSyncRuleDirection(rule: SyncRule, direction: SyncDirection) = syncAndTransferCoordinator.updateSyncRuleDirection(rule, direction)
    fun deleteSyncRule(rule: SyncRule) = syncAndTransferCoordinator.deleteSyncRule(rule)
    fun runSyncRuleNow(rule: SyncRule) = syncAndTransferCoordinator.runSyncRuleNow(rule)
    fun runAllSyncRulesNow() = syncAndTransferCoordinator.runAllSyncRulesNow()
    fun uploadUris(uris: List<Uri>) = syncAndTransferCoordinator.uploadUris(uris)
    fun downloadItems(items: List<KodFileItem>) = syncAndTransferCoordinator.downloadItems(items)
    fun retryTransfer(taskId: String) = syncAndTransferCoordinator.retryTransfer(taskId)
    fun cancelTransfer(taskId: String) = syncAndTransferCoordinator.cancelTransfer(taskId)
    fun clearFinishedTransfers() = syncAndTransferCoordinator.clearFinishedTransfers()
    fun openDownloadedTransfer(task: TransferTask) = syncAndTransferCoordinator.openDownloadedTransfer(task)

    private fun emitMessage(message: String) {
        _events.tryEmit(KodUiEvent.ShowMessage(message, isError = false))
    }

    private fun emitError(message: String) {
        _events.tryEmit(KodUiEvent.ShowMessage(message, isError = true))
    }
}
