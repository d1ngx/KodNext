package com.example.viewmodel

import android.app.Application
import android.net.Uri
import com.example.data.KodBoxApiClient
import com.example.data.LocalFileOpener
import com.example.data.repository.SyncRepository
import com.example.data.repository.TransferRepository
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.SyncConflictPolicy
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncRunStatus
import com.example.model.TransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Focused domain coordinator for Room-persisted Bidirectional Sync rules (respecting wifiOnly
 * even in foreground realtime mode) and WorkManager-backed persistent Uploads/Downloads.
 */
internal class KodSyncAndTransferCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<KodUiState>,
    private val syncRepository: SyncRepository,
    private val transferRepository: TransferRepository,
    private val emitMessage: (String) -> Unit,
    private val emitError: (String) -> Unit,
    private val emitOpenLocalFile: (File, String) -> Unit,
    private val emitShareLocalFile: (File, String) -> Unit,
    private val onRefreshCurrentDirectory: () -> Unit
) {
    private var realtimeWatcherJob: Job? = null
    @Volatile
    var lastSyncBatchFinishedAtMs: Long = 0L
        private set

    fun startRealtimeSyncWatcher() {
        realtimeWatcherJob?.cancel()
        realtimeWatcherJob = scope.launch {
            while (true) {
                delay(45_000L)
                val state = uiState.value
                val account = state.activeAccount ?: continue
                val realtimeRules = state.syncRules.filter {
                    it.enabled &&
                        it.frequency == SyncFrequency.REALTIME &&
                        it.id !in state.activeSyncingRuleIds &&
                        !syncRepository.isRuleRunning(it.id)
                }
                realtimeRules.forEach { rule ->
                    if (!rule.wifiOnly || syncRepository.isWifiConnected()) {
                        runSyncRuleInternal(account, rule, silent = true)
                    }
                }
            }
        }
    }

    fun createSyncRule(
        name: String,
        localTreeUri: String,
        localFolderLabel: String,
        remotePath: String,
        remoteFolderLabel: String,
        direction: SyncDirection,
        frequency: SyncFrequency,
        wifiOnly: Boolean,
        conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.KEEP_BOTH_CONFLICT_COPY,
        ignoreHiddenFiles: Boolean = true
    ) {
        val account = uiState.value.activeAccount ?: return
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
            enabled = true,
            conflictPolicy = conflictPolicy,
            ignoreHiddenFiles = ignoreHiddenFiles
        )
        scope.launch {
            syncRepository.saveRule(rule, rescheduleWorker = true)
            uiState.update { it.copy(pickedSyncRemote = null) }
            emitMessage("已添加同步任务：${rule.name}")
            if (!rule.wifiOnly || syncRepository.isWifiConnected()) {
                runSyncRuleInternal(account, rule, silent = false)
            } else {
                emitMessage("当前非 Wi-Fi 网络，已按规则等待 Wi-Fi 环境自动同步")
            }
        }
    }

    fun toggleSyncRuleEnabled(rule: SyncRule, enabled: Boolean) {
        val updated = rule.copy(enabled = enabled)
        scope.launch {
            syncRepository.saveRule(updated, rescheduleWorker = true)
            emitMessage(if (enabled) "已启用自动同步：${rule.name}" else "已暂停自动同步：${rule.name}")
        }
    }

    fun updateSyncRuleFrequency(rule: SyncRule, frequency: SyncFrequency) {
        val updated = rule.copy(frequency = frequency)
        scope.launch {
            syncRepository.saveRule(updated, rescheduleWorker = true)
            emitMessage("同步频率已更改为：${frequency.label}")
        }
    }

    fun updateSyncRuleDirection(rule: SyncRule, direction: SyncDirection) {
        val updated = rule.copy(direction = direction)
        scope.launch {
            syncRepository.saveRule(updated, rescheduleWorker = false)
            emitMessage("同步模式已更改为：${direction.label}")
        }
    }

    fun updateSyncRuleConflictPolicy(rule: SyncRule, policy: SyncConflictPolicy) {
        val updated = rule.copy(conflictPolicy = policy)
        scope.launch {
            syncRepository.saveRule(updated, rescheduleWorker = false)
            emitMessage("冲突策略已更改为：${policy.label}")
        }
    }

    fun deleteSyncRule(rule: SyncRule) {
        scope.launch {
            syncRepository.deleteRule(rule.id)
            emitMessage("已删除同步任务：${rule.name}")
        }
    }

    fun runSyncRuleNow(rule: SyncRule) {
        val account = uiState.value.activeAccount ?: return
        scope.launch {
            runSyncRuleInternal(account, rule, silent = false)
        }
    }

    fun runAllSyncRulesNow() {
        val account = uiState.value.activeAccount ?: return
        val rules = uiState.value.syncRules.filter { it.enabled }
        if (rules.isEmpty()) {
            emitMessage("暂无已启用的同步任务")
            return
        }
        scope.launch {
            rules.forEach { rule ->
                runSyncRuleInternal(account, rule, silent = false)
            }
        }
    }

    private suspend fun runSyncRuleInternal(account: KodAccount, rule: SyncRule, silent: Boolean) {
        if (rule.id in uiState.value.activeSyncingRuleIds || syncRepository.isRuleRunning(rule.id)) return
        uiState.update { it.copy(activeSyncingRuleIds = it.activeSyncingRuleIds + rule.id) }
        syncRepository.saveRule(rule.copy(lastStatus = SyncRunStatus.SYNCING), rescheduleWorker = false)

        val outcome = syncRepository.executeSyncRule(account, rule)
        outcome.fold(
            onSuccess = { res ->
                if (!res.alreadyRunning) {
                    lastSyncBatchFinishedAtMs = System.currentTimeMillis()
                    syncRepository.saveRule(
                        rule.copy(
                            lastSyncTime = lastSyncBatchFinishedAtMs,
                            lastStatus = SyncRunStatus.SUCCESS,
                            lastSummary = res.summary
                        ),
                        rescheduleWorker = false
                    )
                    if (!silent) {
                        emitMessage("${rule.name} 同步完成 (${res.summary})")
                    }
                    if (res.uploadedCount > 0) {
                        onRefreshCurrentDirectory()
                    }
                }
            },
            onFailure = { err ->
                lastSyncBatchFinishedAtMs = System.currentTimeMillis()
                syncRepository.saveRule(
                    rule.copy(
                        lastSyncTime = lastSyncBatchFinishedAtMs,
                        lastStatus = SyncRunStatus.ERROR,
                        lastSummary = err.message ?: "同步异常"
                    ),
                    rescheduleWorker = false
                )
                if (!silent) {
                    emitError("${rule.name} 同步失败：${err.message}")
                }
            }
        )
        uiState.update { it.copy(activeSyncingRuleIds = it.activeSyncingRuleIds - rule.id) }
    }

    // WorkManager-backed persistent Uploads & Downloads

    fun uploadUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val account = uiState.value.activeAccount ?: return
        val currentPath = uiState.value.currentPath
        val redirectedToPersonal = !KodBoxApiClient.isWritableKodDirectory(currentPath)
        val targetDir = if (redirectedToPersonal) account.personalRootPath else currentPath
        scope.launch {
            val tasks = transferRepository.enqueueUploads(account, targetDir, uris)
            if (tasks.isNotEmpty()) {
                val msg = if (redirectedToPersonal) {
                    "当前视图不支持直接上传，已提交 ${tasks.size} 个任务上传至个人空间"
                } else {
                    "已提交 ${tasks.size} 个后台上传任务"
                }
                emitMessage(msg)
            }
        }
    }

    fun downloadItems(items: List<KodFileItem>) {
        val account = uiState.value.activeAccount ?: return
        val filesOnly = items.filterNot { it.isFolder }
        if (filesOnly.isEmpty()) {
            emitMessage("请选择具体文件进行下载（文件夹请先压缩后下载）")
            return
        }
        uiState.update { it.copy(actionSheetItem = null, selectedPaths = emptySet()) }
        scope.launch {
            val tasks = transferRepository.enqueueDownloads(account, filesOnly)
            if (tasks.isNotEmpty()) {
                emitMessage("已开始后台下载 ${tasks.size} 个文件")
            }
        }
    }

    fun retryTransfer(taskId: String) {
        scope.launch {
            val restarted = transferRepository.retryTransfer(taskId)
            if (restarted) {
                emitMessage("已重新加入后台传输队列")
            } else {
                emitError("无法重试该任务，请重新选择文件")
            }
        }
    }

    fun cancelTransfer(taskId: String) {
        scope.launch {
            transferRepository.cancelTransfer(taskId)
            emitMessage("已取消传输任务")
        }
    }

    fun clearFinishedTransfers() {
        val accountId = uiState.value.activeAccount?.id
        scope.launch {
            transferRepository.clearFinishedTransfers(accountId)
        }
    }

    fun openDownloadedTransfer(task: TransferTask) {
        val file = LocalFileOpener.resolveExistingDownloadFile(
            context = application,
            recordedLocalPath = task.localPath,
            fileName = task.fileName
        )
        if (file == null) {
            emitError("本地文件不存在或已被清理，请重新下载")
            return
        }
        val mime = LocalFileOpener.resolveMimeType(file, task.mimeType)
        emitOpenLocalFile(file, mime)
    }

    fun shareDownloadedTransfer(task: TransferTask) {
        val file = LocalFileOpener.resolveExistingDownloadFile(
            context = application,
            recordedLocalPath = task.localPath,
            fileName = task.fileName
        )
        if (file == null) {
            emitError("本地文件不存在或已被清理，请重新下载")
            return
        }
        val mime = LocalFileOpener.resolveMimeType(file, task.mimeType)
        emitShareLocalFile(file, mime)
    }
}
