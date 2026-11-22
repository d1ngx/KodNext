package com.example.viewmodel

import com.example.model.AppPreferences
import com.example.model.BreadcrumbNode
import com.example.model.KodAccount
import com.example.model.KodAdminOverview
import com.example.model.KodFileItem
import com.example.model.KodFileTypeCategory
import com.example.model.KodPluginApp
import com.example.model.KodShareItem
import com.example.model.KodSpace
import com.example.model.KodTag
import com.example.model.KodUserDeviceItem
import com.example.model.KodUserLogItem
import com.example.model.KodWorkspaceApp
import com.example.model.MainTab
import com.example.model.SearchFilterState
import com.example.model.ServerProbeInfo
import com.example.model.ShareSubTab
import com.example.model.SyncRule
import com.example.model.SyncSubTab
import com.example.model.TransferTask
import java.io.File

sealed interface KodUiEvent {
    data class ShowMessage(val message: String, val isError: Boolean = false) : KodUiEvent
    data class CopyToClipboard(val label: String, val text: String) : KodUiEvent
    data class OpenLocalFile(val file: File, val mimeType: String) : KodUiEvent
}

enum class LoginStep {
    SERVER_URL,
    CREDENTIALS
}

data class LoginFormState(
    val step: LoginStep = LoginStep.SERVER_URL,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val checkCode: String = "",
    val isProbing: Boolean = false,
    val isLoggingIn: Boolean = false,
    val probeInfo: ServerProbeInfo? = null,
    val captchaBytes: ByteArray? = null,
    val infoMessage: String? = null,
    val errorMessage: String? = null
)

data class FolderPickerState(
    val isMove: Boolean = false,
    val isForSyncTarget: Boolean = false,
    val targetItems: List<KodFileItem> = emptyList(),
    val currentPath: String,
    val currentTitle: String,
    val breadcrumbs: List<BreadcrumbNode>,
    val folders: List<KodFileItem>,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class SyncRemoteSelection(
    val remotePath: String,
    val remoteLabel: String
)

data class PreviewState(
    val item: KodFileItem,
    val textContent: String = "",
    val isLoadingText: Boolean = false,
    val isSavingText: Boolean = false,
    val isEditingText: Boolean = false,
    val errorMessage: String? = null
)

data class SessionExpiredNotice(
    val accountId: String,
    val serverUrl: String,
    val siteName: String,
    val username: String,
    val reason: String,
    val dialogDismissed: Boolean = false
)

data class KodUiState(
    val isHydrated: Boolean = false,
    val accounts: List<KodAccount> = emptyList(),
    val activeAccount: KodAccount? = null,
    val showAddAccountLogin: Boolean = false,
    val showQrScanner: Boolean = false,
    val preferences: AppPreferences = AppPreferences(),
    val loginForm: LoginFormState = LoginFormState(),
    val serverPlugins: List<KodPluginApp> = emptyList(),
    val workspaceApps: List<KodWorkspaceApp> = emptyList(),
    val activeWorkspaceApp: KodWorkspaceApp? = null,
    val showAppHubSheet: Boolean = false,
    val cacheSizeBytes: Long = 0L,

    // Main workspace navigation (4-tab Home-mode: 首页 / 文件 / 传输 / 我的)
    val activeTab: MainTab = MainTab.HOME,
    val activeSpace: KodSpace = KodSpace.PERSONAL,
    val activeFileTypeCategory: KodFileTypeCategory = KodFileTypeCategory.ALL,
    val currentPath: String = "",
    val currentTitle: String = "个人空间",
    val breadcrumbs: List<BreadcrumbNode> = emptyList(),

    // Home dashboard overview (收藏夹 & 最近文档)
    val homeFavorites: List<KodFileItem> = emptyList(),
    val homeRecentFiles: List<KodFileItem> = emptyList(),
    val isLoadingHome: Boolean = false,

    // Files list (rawFiles from server + filtered display files)
    val rawFiles: List<KodFileItem> = emptyList(),
    val files: List<KodFileItem> = emptyList(),
    val isLoadingFiles: Boolean = false,
    val filesError: String? = null,

    // Auto-previewed README.md in the current directory
    val directoryReadmeItem: KodFileItem? = null,
    val directoryReadmeContent: String = "",
    val isLoadingDirectoryReadme: Boolean = false,
    val isDirectoryReadmeExpanded: Boolean = true,

    // Search & multi-dimensional filters
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchFilter: SearchFilterState = SearchFilterState(),
    val showFilterSheet: Boolean = false,

    // Multi-select
    val selectedPaths: Set<String> = emptySet(),

    // KodBox Tags
    val availableTags: List<KodTag> = emptyList(),
    val activeTagFilter: KodTag? = null,

    // Media tab
    val mediaItems: List<KodFileItem> = emptyList(),
    val mediaFilter: String = "ALL",
    val isLoadingMedia: Boolean = false,
    val mediaError: String? = null,

    // Shares tab (External Links + My Collaboration + Shared With Me)
    val activeShareSubTab: ShareSubTab = ShareSubTab.EXTERNAL_LINKS,
    val shares: List<KodShareItem> = emptyList(),
    val collabShares: List<KodShareItem> = emptyList(),
    val sharedWithMeItems: List<KodFileItem> = emptyList(),
    val isLoadingShares: Boolean = false,
    val sharesError: String? = null,
    val editingShare: KodShareItem? = null,

    // Bidirectional Sync & Transfers unified tab
    val activeSyncSubTab: SyncSubTab = SyncSubTab.RULES,
    val syncRules: List<SyncRule> = emptyList(),
    val activeSyncingRuleIds: Set<String> = emptySet(),
    val pickedSyncRemote: SyncRemoteSelection? = null,
    val transfers: List<TransferTask> = emptyList(),

    // Account Center
    val showAccountCenter: Boolean = false,
    val isLoadingAccountCenter: Boolean = false,
    val userDevices: List<KodUserDeviceItem> = emptyList(),
    val userLogs: List<KodUserLogItem> = emptyList(),

    // Secondary screens & sheets
    val showSettings: Boolean = false,
    val showSharedScreen: Boolean = false,
    val showSyncScreen: Boolean = false,
    val previewState: PreviewState? = null,
    val actionSheetItem: KodFileItem? = null,
    val detailDialogItem: KodFileItem? = null,
    val shareDialogItem: KodFileItem? = null,
    val createdShareResult: KodShareItem? = null,
    val tagSheetItem: KodFileItem? = null,
    val renameDialogItem: KodFileItem? = null,
    val folderPickerState: FolderPickerState? = null,
    val isWorking: Boolean = false,
    val serverHealthMessage: String? = null,
    val sessionExpiredNotice: SessionExpiredNotice? = null
) {
    val isInSelectionMode: Boolean
        get() = selectedPaths.isNotEmpty()

    val selectedItems: List<KodFileItem>
        get() = files.filter { it.path in selectedPaths }

    val activeTransferCount: Int
        get() = transfers.count { it.status == com.example.model.TransferStatus.RUNNING }

    val canNavigateUp: Boolean
        get() = breadcrumbs.size > 1

    /**
     * Server-discovered interactive workspace apps visible to the current account role.
     */
    val visibleWorkspaceApps: List<KodWorkspaceApp>
        get() {
            val isAdmin = activeAccount?.isAdmin == true
            return workspaceApps.filter { !it.isAdminOnly || isAdmin }
        }
}
