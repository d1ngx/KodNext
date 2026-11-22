package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.KodApplication
import com.example.R
import com.example.model.KodFileItem
import com.example.model.TransferStatus
import com.example.model.TransferType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reliable WorkManager background transfer worker executing chunked uploads and
 * HTTP Range-resumable downloads with a Google Play-compliant `dataSync` foreground notification.
 * Progress and terminal states are persisted directly into Room via [TransferRepository].
 */
class KodTransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val container = (applicationContext as? KodApplication)?.container ?: return Result.failure()
        val transferRepo = container.transferRepository
        val authRepo = container.authRepository
        val fileRepo = container.fileRepository
        val apiClient = container.apiClient

        val taskEntity = transferRepo.getTaskEntity(taskId) ?: return Result.success()
        if (taskEntity.status == TransferStatus.CANCELLED.name) return Result.success()

        val account = authRepo.getAccountById(taskEntity.accountId)
        if (account == null) {
            transferRepo.markTerminalState(
                taskId = taskId,
                status = TransferStatus.FAILED,
                progress = taskEntity.progress,
                bytesTransferred = taskEntity.bytesTransferred,
                localPath = taskEntity.localPath,
                errorMessage = "对应网盘账号已退出，请重新登录后重试"
            )
            return Result.failure()
        }

        val transferType = runCatching { TransferType.valueOf(taskEntity.type) }.getOrDefault(TransferType.DOWNLOAD)
        val notificationId = NOTIFICATION_BASE_ID + (taskId.hashCode() and 0x0FFF)

        // Promote to foreground gracefully (never crash if POST_NOTIFICATIONS is denied on Android 13+)
        runCatching {
            setForeground(createForegroundInfo(notificationId, taskEntity.fileName, transferType, 0, true))
        }

        var lastPersistedAt = 0L
        var lastPersistedPercent = -1

        fun handleProgress(transferred: Long, total: Long) {
            val now = System.currentTimeMillis()
            val ratio = if (total > 0L) (transferred.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
            val percent = (ratio * 100f).toInt().coerceIn(0, 100)
            if (now - lastPersistedAt >= PROGRESS_THROTTLE_MS || percent - lastPersistedPercent >= 3 || percent == 100) {
                lastPersistedAt = now
                lastPersistedPercent = percent
                progressScope.launch {
                    runCatching {
                        transferRepo.updateProgress(taskId, ratio, transferred, total.coerceAtLeast(transferred))
                    }
                    runCatching {
                        setForeground(
                            createForegroundInfo(
                                notificationId = notificationId,
                                fileName = taskEntity.fileName,
                                type = transferType,
                                percent = percent,
                                indeterminate = total <= 0L
                            )
                        )
                    }
                }
            }
        }

        return try {
            when (transferType) {
                TransferType.UPLOAD -> {
                    if (taskEntity.sourceUri.isBlank()) {
                        transferRepo.markTerminalState(
                            taskId = taskId,
                            status = TransferStatus.FAILED,
                            progress = 0f,
                            bytesTransferred = 0L,
                            localPath = "",
                            errorMessage = "源文件链接已失效，请重新选择文件上传"
                        )
                        return Result.failure()
                    }
                    val uri = Uri.parse(taskEntity.sourceUri)
                    val resolver = applicationContext.contentResolver
                    val mime = taskEntity.mimeType.ifBlank {
                        resolver.getType(uri) ?: "application/octet-stream"
                    }
                    val outcome = apiClient.uploadFile(
                        account = account,
                        targetDir = taskEntity.remotePath,
                        fileName = taskEntity.fileName,
                        mimeType = mime,
                        inputStreamProvider = { resolver.openInputStream(uri) },
                        totalBytes = taskEntity.totalBytes,
                        onProgress = ::handleProgress
                    )
                    outcome.fold(
                        onSuccess = {
                            val finalBytes = taskEntity.totalBytes.coerceAtLeast(taskEntity.bytesTransferred)
                            transferRepo.markTerminalState(
                                taskId = taskId,
                                status = TransferStatus.COMPLETED,
                                progress = 1f,
                                bytesTransferred = finalBytes,
                                localPath = taskEntity.localPath,
                                errorMessage = ""
                            )
                            fileRepo.invalidatePath(account.id, taskEntity.remotePath)
                            Result.success()
                        },
                        onFailure = { err ->
                            handleFailure(transferRepo, taskEntity, err)
                        }
                    )
                }

                TransferType.DOWNLOAD -> {
                    val baseDir = File(applicationContext.filesDir, "downloads")
                    val destFile = if (taskEntity.localPath.isNotBlank()) {
                        File(taskEntity.localPath)
                    } else {
                        KodFormatters.safeConfinedFile(baseDir, taskEntity.fileName)
                    }
                    val ext = taskEntity.fileName.substringAfterLast('.', "").lowercase()
                    val remoteItem = KodFileItem(
                        path = taskEntity.remotePath,
                        name = taskEntity.fileName,
                        isFolder = false,
                        size = taskEntity.totalBytes,
                        ext = ext
                    )
                    val outcome = apiClient.downloadFile(
                        account = account,
                        item = remoteItem,
                        destinationFile = destFile,
                        onProgress = ::handleProgress
                    )
                    outcome.fold(
                        onSuccess = { saved ->
                            mirrorToPublicDownloadsIfSupported(saved, ext)
                            transferRepo.markTerminalState(
                                taskId = taskId,
                                status = TransferStatus.COMPLETED,
                                progress = 1f,
                                bytesTransferred = saved.length(),
                                localPath = saved.absolutePath,
                                errorMessage = ""
                            )
                            Result.success()
                        },
                        onFailure = { err ->
                            handleFailure(transferRepo, taskEntity, err)
                        }
                    )
                }
            }
        } catch (cancel: CancellationException) {
            transferRepo.markTerminalState(
                taskId = taskId,
                status = TransferStatus.CANCELLED,
                progress = taskEntity.progress,
                bytesTransferred = taskEntity.bytesTransferred,
                localPath = taskEntity.localPath,
                errorMessage = ""
            )
            throw cancel
        } catch (unexpected: Throwable) {
            handleFailure(transferRepo, taskEntity, unexpected)
        }
    }

    private suspend fun handleFailure(
        transferRepo: com.example.data.repository.TransferRepository,
        taskEntity: com.example.data.local.TransferTaskEntity,
        error: Throwable
    ): Result {
        val isFatal = KodBoxApiClient.isFatalTransferError(error)
        val canAutoRetry = !isFatal && runAttemptCount < 2

        if (canAutoRetry) {
            return Result.retry()
        }

        val current = transferRepo.getTaskEntity(taskEntity.id)
        if (current?.status != TransferStatus.CANCELLED.name) {
            transferRepo.markTerminalState(
                taskId = taskEntity.id,
                status = TransferStatus.FAILED,
                progress = current?.progress ?: taskEntity.progress,
                bytesTransferred = current?.bytesTransferred ?: taskEntity.bytesTransferred,
                localPath = taskEntity.localPath,
                errorMessage = error.message ?: "传输失败，请点击重试"
            )
        }
        return Result.failure()
    }

    private fun createForegroundInfo(
        notificationId: Int,
        fileName: String,
        type: TransferType,
        percent: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        ensureNotificationChannel()
        val actionTitle = if (type == TransferType.UPLOAD) "正在上传到网盘" else "正在下载到本地"
        val contentText = if (indeterminate) fileName else "$fileName · $percent%"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(actionTitle)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, indeterminate)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "文件上传与下载",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示后台上传与下载进度通知"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private suspend fun mirrorToPublicDownloadsIfSupported(sourceFile: File, ext: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext
        runCatching {
            val resolver = applicationContext.contentResolver
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

    companion object {
        const val KEY_TASK_ID = "transfer_task_id"
        private const val CHANNEL_ID = "kodnext_transfers"
        private const val NOTIFICATION_BASE_ID = 4100
        private const val PROGRESS_THROTTLE_MS = 250L
    }
}
