package com.example.viewmodel

import com.example.data.repository.FileRepository
import com.example.model.BreadcrumbNode
import com.example.model.KodFileItem
import com.example.model.KodShareItem
import com.example.model.KodSpace
import com.example.model.MainTab
import com.example.model.ShareAccessMode
import com.example.model.ShareSubTab
import com.example.model.SortField
import com.example.model.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Focused domain coordinator for External Links, Collaboration Shares, Shared-With-Me,
 * and the Media Gallery tab.
 */
internal class KodShareAndMediaCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<KodUiState>,
    private val fileRepository: FileRepository,
    private val emitMessage: (String) -> Unit,
    private val emitError: (String) -> Unit,
    private val onRefreshCurrentDirectory: () -> Unit,
    private val onOpenFilePreview: (KodFileItem) -> Unit,
    private val onOpenSharedFolder: (com.example.model.KodAccount, String, List<BreadcrumbNode>) -> Unit
) {

    fun showShareDialog(item: KodFileItem?) {
        uiState.update { it.copy(shareDialogItem = item, createdShareResult = null) }
    }

    fun showEditShareDialog(share: KodShareItem?) {
        uiState.update { it.copy(editingShare = share, createdShareResult = null) }
    }

    fun selectShareSubTab(subTab: ShareSubTab) {
        uiState.update { it.copy(activeShareSubTab = subTab) }
        val state = uiState.value
        val needsLoad = when (subTab) {
            ShareSubTab.EXTERNAL_LINKS -> state.shares.isEmpty()
            ShareSubTab.MY_COLLAB -> state.collabShares.isEmpty()
            ShareSubTab.SHARED_WITH_ME -> state.sharedWithMeItems.isEmpty()
        }
        if (needsLoad) loadShares()
    }

    fun loadShares() {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            uiState.update { it.copy(isLoadingShares = true, sharesError = null) }
            val (linksRes, collabRes, withMeRes) = coroutineScope {
                val linksJob = async { fileRepository.listShares(account) }
                val collabJob = async { fileRepository.listMyCollaborationShares(account) }
                val withMeJob = async { fileRepository.listSharedWithMe(account) }
                Triple(linksJob.await(), collabJob.await(), withMeJob.await())
            }
            val allFailed = linksRes.isFailure && collabRes.isFailure && withMeRes.isFailure
            uiState.update { state ->
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
        val account = uiState.value.activeAccount ?: return
        val item = KodFileItem(path = path, name = title, isFolder = isFolder, size = size, ext = ext)
        if (isFolder) {
            val crumbs = listOf(BreadcrumbNode(title, path))
            uiState.update {
                it.copy(
                    activeTab = MainTab.FILES,
                    showSharedScreen = false,
                    activeSpace = if (path.startsWith("{shareItem:")) KodSpace.SHARED_WITH_ME else it.activeSpace,
                    currentPath = path,
                    currentTitle = title,
                    breadcrumbs = crumbs,
                    selectedPaths = emptySet(),
                    isSearchActive = false,
                    searchQuery = ""
                )
            }
            onOpenSharedFolder(account, path, crumbs)
        } else {
            onOpenFilePreview(item)
        }
    }

    fun createShareLink(
        item: KodFileItem,
        password: String,
        expireEpochSeconds: Long,
        accessMode: ShareAccessMode
    ) {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            uiState.update { it.copy(isWorking = true) }
            fileRepository.createOrUpdateShare(account, item, password, expireEpochSeconds, accessMode).fold(
                onSuccess = { share ->
                    uiState.update { it.copy(isWorking = false, createdShareResult = share) }
                    emitMessage("外链已生成")
                    loadShares()
                    onRefreshCurrentDirectory()
                },
                onFailure = { err ->
                    uiState.update { it.copy(isWorking = false) }
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
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            uiState.update { it.copy(isWorking = true) }
            fileRepository.updateExistingShare(account, share, password, expireEpochSeconds, accessMode).fold(
                onSuccess = { updated ->
                    uiState.update { it.copy(isWorking = false, createdShareResult = updated, editingShare = null) }
                    emitMessage("分享权限设置已更新")
                    loadShares()
                },
                onFailure = { err ->
                    uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "更新分享设置失败")
                }
            )
        }
    }

    fun cancelShare(shareId: String) {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            fileRepository.cancelShare(account, shareId).fold(
                onSuccess = {
                    emitMessage("已取消外链分享")
                    loadShares()
                    onRefreshCurrentDirectory()
                },
                onFailure = { emitError(it.message ?: "取消分享失败") }
            )
        }
    }

    fun setMediaFilter(filter: String) {
        uiState.update { it.copy(mediaFilter = filter) }
    }

    fun loadMediaItems() {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            uiState.update { it.copy(isLoadingMedia = true, mediaError = null) }
            val (imgRes, vidRes, audRes) = coroutineScope {
                val imgJob = async { fileRepository.fetchRemoteDirectory(account, "{userFileType:image}/", SortField.MODIFY_TIME, SortOrder.DESC) }
                val vidJob = async { fileRepository.fetchRemoteDirectory(account, "{userFileType:movie}/", SortField.MODIFY_TIME, SortOrder.DESC) }
                val audJob = async { fileRepository.fetchRemoteDirectory(account, "{userFileType:music}/", SortField.MODIFY_TIME, SortOrder.DESC) }
                Triple(imgJob.await(), vidJob.await(), audJob.await())
            }
            val combined = buildList {
                imgRes.getOrNull()?.items?.let { addAll(it) }
                vidRes.getOrNull()?.items?.let { addAll(it) }
                audRes.getOrNull()?.items?.let { addAll(it) }
            }.distinctBy { it.path }.sortedByDescending { it.modifyTime }

            if (combined.isEmpty() && imgRes.isFailure && vidRes.isFailure && audRes.isFailure) {
                uiState.update {
                    it.copy(
                        isLoadingMedia = false,
                        mediaError = imgRes.exceptionOrNull()?.message ?: "加载媒体库失败"
                    )
                }
            } else {
                uiState.update {
                    it.copy(isLoadingMedia = false, mediaItems = combined, mediaError = null)
                }
            }
        }
    }
}
