package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.KodApplication
import com.example.data.repository.TransferRepository
import com.example.model.KodAccount
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncRunStatus
import com.example.model.TransferStatus
import com.example.model.TransferType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class SyncOutcome(
    val uploadedCount: Int,
    val downloadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int = 0,
    val summary: String,
    val alreadyRunning: Boolean = false
)

/**
 * Bidirectional sync engine reconciling an Android Storage Access Framework (SAF)
 * local folder with a remote cloud directory — requires zero broad storage permissions.
 * Every upload and download is recorded in [TransferRepository] so the Transfers tab displays
 * live progress, completion status, and actionable errors in real time.
 */
class KodSyncEngine(
    private val context: Context,
    private val apiClient: KodBoxApiClient,
    private val transferRepository: TransferRepository? = null
) {
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class SyncCounters(
        var uploaded: Int = 0,
        var downloaded: Int = 0,
        var skipped: Int = 0,
        var failed: Int = 0,
        var firstError: Throwable? = null
    )

    suspend fun executeSyncRule(account: KodAccount, rule: SyncRule): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        if (!tryAcquireRuleLock(rule.id)) {
            return@withContext Result.success(
                SyncOutcome(
                    uploadedCount = 0,
                    downloadedCount = 0,
                    skippedCount = 0,
                    failedCount = 0,
                    summary = "同步任务正在执行中",
                    alreadyRunning = true
                )
            )
        }
        try {
            runCatching {
                val treeUri = Uri.parse(rule.localTreeUri)
                val localDir = DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("无法访问本地同步目录，请重新授权文件夹")
                if (!localDir.exists() || !localDir.isDirectory) {
                    error("本地同步文件夹已不存在或权限已失效")
                }
                if ((rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.UPLOAD_ONLY) &&
                    !KodBoxApiClient.isWritableKodDirectory(rule.remotePath)
                ) {
                    error("网盘目标目录不支持上传，请重新选择个人空间或企业网盘的具体文件夹")
                }

                val counters = SyncCounters()
                syncDirectoryRecursive(
                    account = account,
                    rule = rule,
                    localDir = localDir,
                    remoteDir = rule.remotePath,
                    relativePrefix = "",
                    depth = 0,
                    counters = counters
                )

                if (counters.failed > 0 && counters.uploaded == 0 && counters.downloaded == 0) {
                    throw counters.firstError ?: IllegalStateException("同步失败，共 ${counters.failed} 个文件传输失败")
                }

                val summary = buildString {
                    append("上传 ${counters.uploaded} · 下载 ${counters.downloaded} · 一致跳过 ${counters.skipped}")
                    if (counters.failed > 0) append(" · 失败 ${counters.failed}")
                }
                SyncOutcome(
                    uploadedCount = counters.uploaded,
                    downloadedCount = counters.downloaded,
                    skippedCount = counters.skipped,
                    failedCount = counters.failed,
                    summary = summary
                )
            }
        } finally {
            releaseRuleLock(rule.id)
        }
    }

    private suspend fun syncDirectoryRecursive(
        account: KodAccount,
        rule: SyncRule,
        localDir: DocumentFile,
        remoteDir: String,
        relativePrefix: String,
        depth: Int,
        counters: SyncCounters
    ) {
        // 1. Fetch remote directory listing
        val remoteListing = apiClient.listPath(account, remoteDir).getOrElse { err ->
            error("读取网盘目标文件夹失败：${err.message}")
        }
        val remoteFilesByName = remoteListing.items
            .filterNot { it.isFolder }
            .associateBy { it.name }
        val remoteFoldersByName = remoteListing.items
            .filter { it.isFolder && it.name.isNotBlank() }
            .associateBy { it.name }

        // 2. Scan local SAF directory files & subfolders
        val localEntries = localDir.listFiles()
        val localFilesByName = localEntries
            .filter { it.isFile && !it.name.isNullOrBlank() }
            .associateBy { it.name!! }
        val localFoldersByName = localEntries
            .filter { it.isDirectory && !it.name.isNullOrBlank() }
            .associateBy { it.name!! }

        val allFileNames = (remoteFilesByName.keys + localFilesByName.keys).sorted()
        val toleranceMs = 5_000L

        for (fileName in allFileNames) {
            val localFile = localFilesByName[fileName]
            val remoteFile = remoteFilesByName[fileName]
            val displayName = if (relativePrefix.isBlank()) fileName else "$relativePrefix$fileName"

            try {
                when {
                    // Only exists locally -> Upload
                    localFile != null && remoteFile == null -> {
                        if (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.UPLOAD_ONLY) {
                            uploadLocalDocument(account, remoteDir, localFile, displayName)
                            counters.uploaded++
                        } else {
                            counters.skipped++
                        }
                    }

                    // Only exists remotely -> Download
                    localFile == null && remoteFile != null -> {
                        if (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.DOWNLOAD_ONLY) {
                            downloadToLocalDocument(account, remoteFile, localDir, displayName)
                            counters.downloaded++
                        } else {
                            counters.skipped++
                        }
                    }

                    // Exists on both sides -> Compare modification timestamp & size (newer wins)
                    localFile != null && remoteFile != null -> {
                        val localModMs = localFile.lastModified()
                        val remoteModMs = if (remoteFile.modifyTime < 10_000_000_000L) {
                            remoteFile.modifyTime * 1000L
                        } else {
                            remoteFile.modifyTime
                        }
                        val sameSize = localFile.length() == remoteFile.size
                        val withinTolerance = abs(localModMs - remoteModMs) <= toleranceMs

                        if (sameSize && withinTolerance) {
                            counters.skipped++
                        } else if (localModMs > remoteModMs + toleranceMs &&
                            (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.UPLOAD_ONLY)
                        ) {
                            uploadLocalDocument(account, remoteDir, localFile, displayName)
                            counters.uploaded++
                        } else if (remoteModMs > localModMs + toleranceMs &&
                            (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.DOWNLOAD_ONLY)
                        ) {
                            downloadToLocalDocument(account, remoteFile, localDir, displayName, overwriteExisting = localFile)
                            counters.downloaded++
                        } else {
                            counters.skipped++
                        }
                    }
                }
            } catch (err: Throwable) {
                if (KodBoxApiClient.isAuthFailure(err)) throw err
                counters.failed++
                if (counters.firstError == null) counters.firstError = err
            }
        }

        // 3. Recurse into subdirectories (up to MAX_SYNC_DEPTH) so nested files are never silently missed
        if (depth >= MAX_SYNC_DEPTH) return
        val allFolderNames = (remoteFoldersByName.keys + localFoldersByName.keys)
            .filterNot { it.startsWith(".") }
            .sorted()

        for (folderName in allFolderNames) {
            val localSub = localFoldersByName[folderName]
            val remoteSub = remoteFoldersByName[folderName]
            val nextPrefix = "$relativePrefix$folderName/"

            when {
                localSub != null && remoteSub != null -> {
                    syncDirectoryRecursive(
                        account = account,
                        rule = rule,
                        localDir = localSub,
                        remoteDir = remoteSub.path,
                        relativePrefix = nextPrefix,
                        depth = depth + 1,
                        counters = counters
                    )
                }
                localSub != null && remoteSub == null &&
                    (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.UPLOAD_ONLY) -> {
                    val created = apiClient.createFolder(account, remoteDir, folderName)
                    if (created.isSuccess) {
                        val refreshedRemoteSub = apiClient.listPath(account, remoteDir)
                            .getOrNull()
                            ?.items
                            ?.firstOrNull { it.isFolder && it.name == folderName }
                        if (refreshedRemoteSub != null) {
                            syncDirectoryRecursive(
                                account = account,
                                rule = rule,
                                localDir = localSub,
                                remoteDir = refreshedRemoteSub.path,
                                relativePrefix = nextPrefix,
                                depth = depth + 1,
                                counters = counters
                            )
                        }
                    }
                }
                localSub == null && remoteSub != null &&
                    (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.DOWNLOAD_ONLY) -> {
                    val createdLocalSub = localDir.createDirectory(folderName)
                    if (createdLocalSub != null) {
                        syncDirectoryRecursive(
                            account = account,
                            rule = rule,
                            localDir = createdLocalSub,
                            remoteDir = remoteSub.path,
                            relativePrefix = nextPrefix,
                            depth = depth + 1,
                            counters = counters
                        )
                    }
                }
            }
        }
    }

    private suspend fun uploadLocalDocument(
        account: KodAccount,
        remoteDir: String,
        doc: DocumentFile,
        displayName: String
    ) {
        val fileName = doc.name ?: return
        val mime = doc.type ?: KodFormatters.mimeTypeForExtension(fileName.substringAfterLast('.', ""))
        val size = doc.length().coerceAtLeast(0L)
        val resolver = context.contentResolver
        val taskId = transferRepository?.recordDirectTransferStart(
            accountId = account.id,
            fileName = displayName,
            remotePath = remoteDir,
            type = TransferType.UPLOAD,
            totalBytes = size,
            sourceUri = doc.uri.toString(),
            mimeType = mime
        )

        var lastPersistedAt = 0L
        var lastPersistedPercent = -1
        var lastTransferredBytes = 0L

        val outcome = apiClient.uploadFile(
            account = account,
            targetDir = remoteDir,
            fileName = fileName,
            mimeType = mime,
            inputStreamProvider = { resolver.openInputStream(doc.uri) },
            totalBytes = size,
            onProgress = { transferred, total ->
                lastTransferredBytes = transferred
                if (taskId != null && transferRepository != null) {
                    val now = System.currentTimeMillis()
                    val resolvedTotal = total.coerceAtLeast(transferred)
                    val ratio = if (resolvedTotal > 0L) (transferred.toFloat() / resolvedTotal.toFloat()).coerceIn(0f, 1f) else 0.5f
                    val percent = (ratio * 100f).toInt().coerceIn(0, 100)
                    if (now - lastPersistedAt >= PROGRESS_THROTTLE_MS || percent - lastPersistedPercent >= 3 || percent == 100) {
                        lastPersistedAt = now
                        lastPersistedPercent = percent
                        progressScope.launch {
                            runCatching {
                                transferRepository.updateProgress(taskId, ratio, transferred, resolvedTotal)
                            }
                        }
                    }
                }
            }
        )

        outcome.fold(
            onSuccess = {
                if (taskId != null && transferRepository != null) {
                    val finalBytes = lastTransferredBytes.coerceAtLeast(size)
                    transferRepository.markTerminalState(
                        taskId = taskId,
                        status = TransferStatus.COMPLETED,
                        progress = 1f,
                        bytesTransferred = finalBytes,
                        localPath = "",
                        errorMessage = ""
                    )
                }
            },
            onFailure = { err ->
                if (taskId != null && transferRepository != null && !transferRepository.isTaskCancelled(taskId)) {
                    val resolvedTotal = size.coerceAtLeast(lastTransferredBytes)
                    val ratio = if (resolvedTotal > 0L) (lastTransferredBytes.toFloat() / resolvedTotal.toFloat()).coerceIn(0f, 1f) else 0f
                    transferRepository.markTerminalState(
                        taskId = taskId,
                        status = TransferStatus.FAILED,
                        progress = ratio,
                        bytesTransferred = lastTransferredBytes,
                        localPath = "",
                        errorMessage = err.message ?: "同步上传失败"
                    )
                }
                throw err
            }
        )
    }

    private suspend fun downloadToLocalDocument(
        account: KodAccount,
        remoteItem: com.example.model.KodFileItem,
        parentLocalDir: DocumentFile,
        displayName: String,
        overwriteExisting: DocumentFile? = null
    ) {
        val mime = KodFormatters.mimeTypeForExtension(remoteItem.normalizedExt)
        val taskId = transferRepository?.recordDirectTransferStart(
            accountId = account.id,
            fileName = displayName,
            remotePath = remoteItem.path,
            type = TransferType.DOWNLOAD,
            totalBytes = remoteItem.size.coerceAtLeast(0L),
            mimeType = mime
        )

        var lastPersistedAt = 0L
        var lastPersistedPercent = -1
        var lastTransferredBytes = 0L

        try {
            val targetDoc = overwriteExisting
                ?: parentLocalDir.createFile(mime, remoteItem.name)
                ?: error("无法在本地目录创建文件 ${remoteItem.name}")
            val resolver = context.contentResolver
            val downloadedBytes = apiClient.downloadFileToStream(
                account = account,
                item = remoteItem,
                outputStreamProvider = { resolver.openOutputStream(targetDoc.uri, "wt") },
                onProgress = { readBytes, totalBytes ->
                    lastTransferredBytes = readBytes
                    if (taskId != null && transferRepository != null) {
                        val now = System.currentTimeMillis()
                        val resolvedTotal = totalBytes.coerceAtLeast(readBytes)
                        val ratio = if (resolvedTotal > 0L) (readBytes.toFloat() / resolvedTotal.toFloat()).coerceIn(0f, 1f) else 0.5f
                        val percent = (ratio * 100f).toInt().coerceIn(0, 100)
                        if (now - lastPersistedAt >= PROGRESS_THROTTLE_MS || percent - lastPersistedPercent >= 3 || percent == 100) {
                            lastPersistedAt = now
                            lastPersistedPercent = percent
                            progressScope.launch {
                                runCatching {
                                    transferRepository.updateProgress(taskId, ratio, readBytes, resolvedTotal)
                                }
                            }
                        }
                    }
                }
            ).getOrThrow()

            if (taskId != null && transferRepository != null) {
                transferRepository.markTerminalState(
                    taskId = taskId,
                    status = TransferStatus.COMPLETED,
                    progress = 1f,
                    bytesTransferred = downloadedBytes.coerceAtLeast(lastTransferredBytes),
                    localPath = "",
                    errorMessage = ""
                )
            }
        } catch (err: Throwable) {
            if (taskId != null && transferRepository != null && !transferRepository.isTaskCancelled(taskId)) {
                val resolvedTotal = remoteItem.size.coerceAtLeast(lastTransferredBytes)
                val ratio = if (resolvedTotal > 0L) (lastTransferredBytes.toFloat() / resolvedTotal.toFloat()).coerceIn(0f, 1f) else 0f
                transferRepository.markTerminalState(
                    taskId = taskId,
                    status = TransferStatus.FAILED,
                    progress = ratio,
                    bytesTransferred = lastTransferredBytes,
                    localPath = "",
                    errorMessage = err.message ?: "同步下载失败"
                )
            }
            throw err
        }
    }

    companion object {
        private const val WORK_PREFIX = "kodnext_sync_"
        private const val PROGRESS_THROTTLE_MS = 200L
        private const val MAX_SYNC_DEPTH = 8

        /**
         * Process-wide lock ensuring a given [SyncRule.id] can never execute concurrently
         * across foreground [KodSyncAndTransferCoordinator], realtime watcher, and WorkManager [KodSyncWorker].
         */
        private val runningRuleIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

        internal fun tryAcquireRuleLock(ruleId: String): Boolean = runningRuleIds.add(ruleId)

        internal fun releaseRuleLock(ruleId: String) {
            runningRuleIds.remove(ruleId)
        }

        fun isRuleRunning(ruleId: String): Boolean = ruleId in runningRuleIds

        fun hasAnyRunningSync(): Boolean = runningRuleIds.isNotEmpty()

        fun isUnmeteredWifiConnected(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        fun scheduleRule(context: Context, rule: SyncRule) {
            val workManager = WorkManager.getInstance(context)
            val workName = "$WORK_PREFIX${rule.id}"
            if (!rule.enabled || rule.frequency == SyncFrequency.MANUAL || rule.frequency.intervalMinutes <= 0L) {
                workManager.cancelUniqueWork(workName)
                return
            }
            val intervalMinutes = rule.frequency.intervalMinutes.coerceAtLeast(15L)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (rule.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            // Always delay the first periodic WorkManager tick by one interval so newly created
            // or updated rules do not immediately race with foreground sync execution.
            val request = PeriodicWorkRequestBuilder<KodSyncWorker>(
                intervalMinutes,
                TimeUnit.MINUTES
            )
                .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(workDataOf(KodSyncWorker.KEY_RULE_ID to rule.id))
                .build()

            workManager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelRule(context: Context, ruleId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("$WORK_PREFIX$ruleId")
        }
    }
}

class KodSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE_ID) ?: return Result.failure()
        val container = (applicationContext as? KodApplication)?.container ?: return Result.failure()
        val syncRepo = container.syncRepository
        val authRepo = container.authRepository
        val fileRepo = container.fileRepository

        val rule = syncRepo.getRuleById(ruleId) ?: return Result.success()
        if (!rule.enabled) return Result.success()

        val account = authRepo.getAccountById(rule.accountId) ?: return Result.success()
        authRepo.hydrateSession(account)

        val outcome = syncRepo.executeSyncRule(account, rule)

        return outcome.fold(
            onSuccess = { res ->
                if (res.alreadyRunning) {
                    return@fold Result.success()
                }
                syncRepo.saveRule(
                    rule.copy(
                        lastSyncTime = System.currentTimeMillis(),
                        lastStatus = SyncRunStatus.SUCCESS,
                        lastSummary = res.summary
                    ),
                    rescheduleWorker = false
                )
                if (res.uploadedCount > 0) {
                    fileRepo.invalidatePath(account.id, rule.remotePath)
                }
                Result.success()
            },
            onFailure = { err ->
                syncRepo.saveRule(
                    rule.copy(
                        lastSyncTime = System.currentTimeMillis(),
                        lastStatus = SyncRunStatus.ERROR,
                        lastSummary = err.message ?: "后台同步失败"
                    ),
                    rescheduleWorker = false
                )
                val isNonRetryable = KodBoxApiClient.isAuthFailure(err) ||
                    err.message?.contains("权限已失效") == true ||
                    err.message?.contains("无法访问本地同步目录") == true ||
                    runAttemptCount >= 3
                if (isNonRetryable) Result.failure() else Result.retry()
            }
        )
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
    }
}
