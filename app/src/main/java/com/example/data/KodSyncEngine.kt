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
import com.example.model.KodAccount
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncRunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class SyncOutcome(
    val uploadedCount: Int,
    val downloadedCount: Int,
    val skippedCount: Int,
    val summary: String
)

/**
 * Bidirectional sync engine reconciling an Android Storage Access Framework (SAF)
 * local folder with a remote cloud directory — requires zero broad storage permissions.
 */
class KodSyncEngine(
    private val context: Context,
    private val apiClient: KodBoxApiClient
) {
    suspend fun executeSyncRule(account: KodAccount, rule: SyncRule): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val treeUri = Uri.parse(rule.localTreeUri)
            val localDir = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("无法访问本地同步目录，请重新授权文件夹")
            if (!localDir.exists() || !localDir.isDirectory) {
                error("本地同步文件夹已不存在或权限已失效")
            }

            // 1. Fetch remote directory listing
            val remoteListing = apiClient.listPath(account, rule.remotePath).getOrElse { err ->
                error("读取网盘目标文件夹失败：${err.message}")
            }
            val remoteFilesByName = remoteListing.items
                .filterNot { it.isFolder }
                .associateBy { it.name }

            // 2. Scan local SAF directory files
            val localFilesByName = localDir.listFiles()
                .filter { it.isFile && !it.name.isNullOrBlank() }
                .associateBy { it.name!! }

            val allNames = (remoteFilesByName.keys + localFilesByName.keys).sorted()
            var uploaded = 0
            var downloaded = 0
            var skipped = 0
            val toleranceMs = 5_000L

            for (fileName in allNames) {
                val localFile = localFilesByName[fileName]
                val remoteFile = remoteFilesByName[fileName]

                when {
                    // Only exists locally -> Upload
                    localFile != null && remoteFile == null -> {
                        if (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.UPLOAD_ONLY) {
                            uploadLocalDocument(account, rule.remotePath, localFile)
                            uploaded++
                        } else {
                            skipped++
                        }
                    }

                    // Only exists remotely -> Download
                    localFile == null && remoteFile != null -> {
                        if (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.DOWNLOAD_ONLY) {
                            downloadToLocalDocument(account, remoteFile, localDir)
                            downloaded++
                        } else {
                            skipped++
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
                            skipped++
                        } else if (localModMs > remoteModMs + toleranceMs &&
                            (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.UPLOAD_ONLY)
                        ) {
                            uploadLocalDocument(account, rule.remotePath, localFile)
                            uploaded++
                        } else if (remoteModMs > localModMs + toleranceMs &&
                            (rule.direction == SyncDirection.TWO_WAY || rule.direction == SyncDirection.DOWNLOAD_ONLY)
                        ) {
                            downloadToLocalDocument(account, remoteFile, localDir, overwriteExisting = localFile)
                            downloaded++
                        } else {
                            skipped++
                        }
                    }
                }
            }

            val summary = "上传 $uploaded · 下载 $downloaded · 一致跳过 $skipped"
            SyncOutcome(
                uploadedCount = uploaded,
                downloadedCount = downloaded,
                skippedCount = skipped,
                summary = summary
            )
        }
    }

    private suspend fun uploadLocalDocument(account: KodAccount, remoteDir: String, doc: DocumentFile) {
        val fileName = doc.name ?: return
        val mime = doc.type ?: KodFormatters.mimeTypeForExtension(fileName.substringAfterLast('.', ""))
        val size = doc.length()
        val resolver = context.contentResolver
        apiClient.uploadFile(
            account = account,
            targetDir = remoteDir,
            fileName = fileName,
            mimeType = mime,
            inputStreamProvider = { resolver.openInputStream(doc.uri) },
            totalBytes = size,
            onProgress = { _, _ -> }
        ).getOrThrow()
    }

    private suspend fun downloadToLocalDocument(
        account: KodAccount,
        remoteItem: com.example.model.KodFileItem,
        parentLocalDir: DocumentFile,
        overwriteExisting: DocumentFile? = null
    ) {
        val mime = KodFormatters.mimeTypeForExtension(remoteItem.normalizedExt)
        val targetDoc = overwriteExisting
            ?: parentLocalDir.createFile(mime, remoteItem.name)
            ?: error("无法在本地目录创建文件 ${remoteItem.name}")
        val resolver = context.contentResolver
        apiClient.downloadFileToStream(
            account = account,
            item = remoteItem,
            outputStreamProvider = { resolver.openOutputStream(targetDoc.uri, "wt") }
        ).getOrThrow()
    }

    companion object {
        private const val WORK_PREFIX = "kodnext_sync_"

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
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (rule.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<KodSyncWorker>(
                rule.frequency.intervalMinutes.coerceAtLeast(15L),
                TimeUnit.MINUTES
            )
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
