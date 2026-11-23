package com.example.viewmodel

import com.example.data.repository.FileRepository
import com.example.model.BreadcrumbNode
import com.example.model.KodCollabAssignment
import com.example.model.KodCollabAuthRole
import com.example.model.KodFileItem
import com.example.model.KodShareItem
import com.example.model.KodSpace
import com.example.model.MainTab
import com.example.model.ShareAccessMode
import com.example.model.ShareCancelMode
import com.example.model.ShareDialogTab
import com.example.model.ShareSubTab
import com.example.model.SortField
import com.example.model.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Focused domain coordinator for External Links, Internal Collaboration Shares,
 * Shared-With-Me, and the Media Gallery tab.
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
    private var collabSearchJob: Job? = null
    private var collabDataLoadedForAccountId: String = ""

    fun showShareDialog(
        item: KodFileItem?,
        initialTab: ShareDialogTab = ShareDialogTab.EXTERNAL_LINK
    ) {
        if (item == null) {
            collabSearchJob?.cancel()
            uiState.update {
                it.copy(
                    shareDialogItem = null,
                    createdShareResult = null,
                    shareExistingDetail = null,
                    isLoadingShareDetail = false,
                    isSearchingCollabTargets = false,
                    collabSearchError = null
                )
            }
            return
        }

        uiState.update {
            it.copy(
                shareDialogItem = item,
                shareDialogInitialTab = initialTab,
                createdShareResult = null,
                shareExistingDetail = null,
                isLoadingShareDetail = true,
                collabSearchError = null
            )
        }

        val account = uiState.value.activeAccount ?: return
        scope.launch {
            val detailRes = fileRepository.fetchItemShareDetail(account, item)
            if (uiState.value.shareDialogItem?.path != item.path) return@launch
            uiState.update { state ->
                state.copy(
                    isLoadingShareDetail = false,
                    shareExistingDetail = detailRes.getOrNull()
                )
            }
        }
        if (initialTab == ShareDialogTab.INTERNAL_COLLAB) {
            ensureCollabDataLoaded()
        }
    }

    fun ensureCollabDataLoaded() {
        val account = uiState.value.activeAccount ?: return
        if (collabDataLoadedForAccountId == account.id && uiState.value.collabTargetCandidates.isNotEmpty()) {
            return
        }
        collabSearchJob?.cancel()
        collabSearchJob = scope.launch {
            uiState.update { it.copy(isSearchingCollabTargets = true, collabSearchError = null) }
            val (rolesRes, candidatesRes) = coroutineScope {
                val rolesJob = async { fileRepository.fetchCollabAuthRoles(account) }
                val candidatesJob = async { fileRepository.searchCollabTargets(account, "") }
                rolesJob.await() to candidatesJob.await()
            }
            if (candidatesRes.isSuccess) {
                collabDataLoadedForAccountId = account.id
            }
            uiState.update { state ->
                state.copy(
                    isSearchingCollabTargets = false,
                    collabAuthRoles = rolesRes.getOrNull().orEmpty().ifEmpty { KodCollabAuthRole.DEFAULT_ROLES },
                    collabTargetCandidates = candidatesRes.getOrElse { state.collabTargetCandidates },
                    collabSearchError = candidatesRes.exceptionOrNull()?.message
                )
            }
        }
    }

    fun showEditCollabShare(share: KodShareItem) {
        val pseudoItem = KodFileItem(
            path = share.path,
            sourceId = share.sourceId,
            name = share.title,
            isFolder = share.isFolder,
            size = share.size,
            ext = share.ext,
            isShare = true
        )
        showShareDialog(pseudoItem, initialTab = ShareDialogTab.INTERNAL_COLLAB)
    }

    fun searchCollabTargets(query: String) {
        val account = uiState.value.activeAccount ?: return
        collabSearchJob?.cancel()
        collabSearchJob = scope.launch {
            uiState.update { it.copy(isSearchingCollabTargets = true, collabSearchError = null) }
            fileRepository.searchCollabTargets(account, query).fold(
                onSuccess = { targets ->
                    uiState.update {
                        it.copy(
                            isSearchingCollabTargets = false,
                            collabTargetCandidates = targets,
                            collabSearchError = null
                        )
                    }
                },
                onFailure = { err ->
                    uiState.update {
                        it.copy(
                            isSearchingCollabTargets = false,
                            collabSearchError = err.message ?: "搜索协作对象失败"
                        )
                    }
                }
            )
        }
    }

    fun saveInternalCollabShare(
        item: KodFileItem,
        assignments: List<KodCollabAssignment>
    ) {
        val account = uiState.value.activeAccount ?: return
        val existingDetail = uiState.value.shareExistingDetail
        scope.launch {
            uiState.update { it.copy(isWorking = true) }
            fileRepository.saveInternalCollabShare(account, item, assignments, existingDetail).fold(
                onSuccess = { share ->
                    uiState.update { state ->
                        val updatedState = markItemShareState(state, item.path, item.sourceId, isShared = true)
                        updatedState.copy(
                            isWorking = false,
                            shareDialogItem = null,
                            shareExistingDetail = null,
                            createdShareResult = null
                        )
                    }
                    emitMessage("内部协作已保存（${assignments.size} 个协作对象）")
                    loadShares()
                    onRefreshCurrentDirectory()
                },
                onFailure = { err ->
                    uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "保存内部协作失败")
                }
            )
        }
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
        val existingDetail = uiState.value.shareExistingDetail
        scope.launch {
            uiState.update { it.copy(isWorking = true) }
            fileRepository.createOrUpdateShare(
                account = account,
                item = item,
                password = password,
                expireEpochSeconds = expireEpochSeconds,
                accessMode = accessMode,
                existingShare = existingDetail
            ).fold(
                onSuccess = { share ->
                    uiState.update { state ->
                        val updatedState = markItemShareState(state, item.path, item.sourceId, isShared = true)
                        updatedState.copy(isWorking = false, createdShareResult = share)
                    }
                    emitMessage(if (existingDetail?.isLink == true) "外链分享设置已更新" else "外链已生成")
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

    fun cancelShare(
        shareId: String,
        path: String = "",
        sourceId: String = "",
        cancelMode: ShareCancelMode = ShareCancelMode.ALL
    ) {
        val account = uiState.value.activeAccount ?: return
        val currentState = uiState.value
        val matchedShare = (currentState.shares + currentState.collabShares).firstOrNull {
            (shareId.isNotBlank() && it.shareId == shareId) ||
                (sourceId.isNotBlank() && it.sourceId == sourceId) ||
                (path.isNotBlank() && it.path.trimEnd('/') == path.trimEnd('/'))
        }
        val effectivePath = path.ifBlank { matchedShare?.path.orEmpty() }
        val effectiveSourceId = sourceId.ifBlank { matchedShare?.sourceId.orEmpty() }
        val effectiveShareId = shareId.ifBlank { matchedShare?.shareId.orEmpty() }

        scope.launch {
            uiState.update { it.copy(isWorking = true) }
            fileRepository.cancelShare(
                account = account,
                shareId = effectiveShareId,
                path = effectivePath,
                sourceId = effectiveSourceId,
                cancelMode = cancelMode
            ).fold(
                onSuccess = {
                    uiState.update { state ->
                        val normTargetPath = effectivePath.trim().trimEnd('/')
                        fun matchesShare(s: KodShareItem): Boolean =
                            (effectiveShareId.isNotBlank() && s.shareId == effectiveShareId) ||
                                (effectiveSourceId.isNotBlank() && s.sourceId == effectiveSourceId) ||
                                (normTargetPath.isNotBlank() && s.path.trim().trimEnd('/') == normTargetPath)

                        val remainingLinks = if (cancelMode == ShareCancelMode.INTERNAL_COLLAB_ONLY) {
                            state.shares
                        } else {
                            state.shares.filterNot(::matchesShare)
                        }
                        val remainingCollab = if (cancelMode == ShareCancelMode.EXTERNAL_LINK_ONLY) {
                            state.collabShares
                        } else {
                            state.collabShares.filterNot(::matchesShare)
                        }

                        val stillShared = (remainingLinks + remainingCollab).any { other ->
                            (effectiveSourceId.isNotBlank() && other.sourceId == effectiveSourceId) ||
                                (normTargetPath.isNotBlank() && other.path.trim().trimEnd('/') == normTargetPath)
                        } || (cancelMode == ShareCancelMode.EXTERNAL_LINK_ONLY && state.shareExistingDetail?.isShareTo == true) ||
                            (cancelMode == ShareCancelMode.INTERNAL_COLLAB_ONLY && state.shareExistingDetail?.isLink == true)

                        val baseState = state.copy(
                            isWorking = false,
                            shares = remainingLinks,
                            collabShares = remainingCollab,
                            createdShareResult = null,
                            shareDialogItem = null,
                            shareExistingDetail = null,
                            editingShare = null
                        )
                        if (!stillShared && (effectivePath.isNotBlank() || effectiveSourceId.isNotBlank())) {
                            markItemShareState(baseState, effectivePath, effectiveSourceId, isShared = false)
                        } else {
                            baseState
                        }
                    }
                    val successText = when (cancelMode) {
                        ShareCancelMode.EXTERNAL_LINK_ONLY -> "已取消外链分享"
                        ShareCancelMode.INTERNAL_COLLAB_ONLY -> "已取消内部协作"
                        ShareCancelMode.ALL -> "已取消分享"
                    }
                    emitMessage(successText)
                    loadShares()
                    onRefreshCurrentDirectory()
                },
                onFailure = { err ->
                    uiState.update { it.copy(isWorking = false) }
                    emitError(err.message ?: "取消分享失败")
                }
            )
        }
    }

    private fun markItemShareState(
        state: KodUiState,
        targetPath: String,
        targetSourceId: String,
        isShared: Boolean
    ): KodUiState {
        val normTarget = targetPath.trim().trimEnd('/')
        fun matches(file: KodFileItem): Boolean {
            if (targetSourceId.isNotBlank() && file.sourceId == targetSourceId) return true
            if (normTarget.isNotBlank() && file.path.trim().trimEnd('/') == normTarget) return true
            return false
        }
        return state.copy(
            files = state.files.map { if (matches(it)) it.copy(isShare = isShared) else it },
            homeFavorites = state.homeFavorites.map { if (matches(it)) it.copy(isShare = isShared) else it },
            homeRecentFiles = state.homeRecentFiles.map { if (matches(it)) it.copy(isShare = isShared) else it }
        )
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
