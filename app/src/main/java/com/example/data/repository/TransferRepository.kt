package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.KodFormatters
import com.example.data.KodTransferWorker
import com.example.data.local.TransferTaskDao
import com.example.data.local.TransferTaskEntity
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.TransferStatus
import com.example.model.TransferTask
import com.example.model.TransferType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/**
 * Room-persisted background transfer repository orchestrating reliable WorkManager
 * uploads (chunked) and downloads (HTTP Range resumable) that survive process restarts.
 */
class TransferRepository(
    private val context: Context,
    private val transferTaskDao: TransferTaskDao
) {
    fun observeAllTransfers(): Flow<List<TransferTask>> =
        transferTaskDao.observeAll().map { entities -> entities.map { it.toModel() } }

    fun observeTransfersForAccount(accountId: String): Flow<List<TransferTask>> =
        transferTaskDao.observeByAccount(accountId).map { entities -> entities.map { it.toModel() } }

    suspend fun getTaskEntity(taskId: String): TransferTaskEntity? =
        transferTaskDao.getById(taskId)

    suspend fun enqueueUploads(account: KodAccount, targetDir: String, uris: List<Uri>): List<TransferTask> {
        val resolver = context.contentResolver
        val created = mutableListOf<TransferTask>()
        uris.forEach { uri ->
            // Attempt to preserve read permission across background WorkManager execution when supported
            runCatching {
                resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val (fileName, fileSize) = queryUriMetadata(uri)
            val mimeType = resolver.getType(uri)
                ?: KodFormatters.mimeTypeForExtension(fileName.substringAfterLast('.', ""))
            val taskId = UUID.randomUUID().toString()
            val workRequest = buildTransferWorkRequest(taskId)
            val task = TransferTask(
                id = taskId,
                fileName = fileName,
                remotePath = targetDir,
                localPath = "",
                type = TransferType.UPLOAD,
                progress = 0f,
                bytesTransferred = 0L,
                totalBytes = fileSize.coerceAtLeast(0L),
                status = TransferStatus.RUNNING,
                timestamp = System.currentTimeMillis(),
                accountId = account.id,
                sourceUri = uri.toString(),
                mimeType = mimeType
            )
            transferTaskDao.upsert(TransferTaskEntity.fromModel(task, workRequest.id.toString()))
            enqueueUniqueTransferWork(taskId, workRequest)
            created.add(task)
        }
        return created
    }

    suspend fun enqueueDownloads(account: KodAccount, items: List<KodFileItem>): List<TransferTask> {
        val baseDir = File(context.filesDir, "downloads")
        val created = mutableListOf<TransferTask>()
        items.filterNot { it.isFolder }.forEach { item ->
            val destFile = KodFormatters.safeConfinedFile(baseDir, item.name)
            val taskId = UUID.randomUUID().toString()
            val workRequest = buildTransferWorkRequest(taskId)
            val task = TransferTask(
                id = taskId,
                fileName = item.name,
                remotePath = item.path,
                localPath = destFile.absolutePath,
                type = TransferType.DOWNLOAD,
                progress = 0f,
                bytesTransferred = 0L,
                totalBytes = item.size.coerceAtLeast(0L),
                status = TransferStatus.RUNNING,
                timestamp = System.currentTimeMillis(),
                accountId = account.id,
                sourceUri = "",
                mimeType = KodFormatters.mimeTypeForExtension(item.normalizedExt)
            )
            transferTaskDao.upsert(TransferTaskEntity.fromModel(task, workRequest.id.toString()))
            enqueueUniqueTransferWork(taskId, workRequest)
            created.add(task)
        }
        return created
    }

    suspend fun retryTransfer(taskId: String): Boolean {
        val existing = transferTaskDao.getById(taskId) ?: return false
        val workRequest = buildTransferWorkRequest(taskId)
        transferTaskDao.markRestarted(taskId, workRequest.id.toString())
        enqueueUniqueTransferWork(taskId, workRequest)
        return true
    }

    suspend fun cancelTransfer(taskId: String) {
        val existing = transferTaskDao.getById(taskId)
        transferTaskDao.markCancelled(taskId)
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork("$WORK_PREFIX$taskId")
        existing?.workRequestId?.takeIf { it.isNotBlank() }?.let { rawUuid ->
            runCatching { wm.cancelWorkById(UUID.fromString(rawUuid)) }
        }
    }

    suspend fun clearFinishedTransfers(accountId: String?) {
        if (accountId.isNullOrBlank()) {
            transferTaskDao.deleteAllFinished()
        } else {
            transferTaskDao.deleteFinishedForAccount(accountId)
        }
    }

    suspend fun recordDirectTransferStart(
        accountId: String,
        fileName: String,
        remotePath: String,
        type: TransferType,
        totalBytes: Long,
        sourceUri: String = "",
        mimeType: String = "",
        localPath: String = ""
    ): String {
        val taskId = UUID.randomUUID().toString()
        val task = TransferTask(
            id = taskId,
            fileName = fileName,
            remotePath = remotePath,
            localPath = localPath,
            type = type,
            progress = 0f,
            bytesTransferred = 0L,
            totalBytes = totalBytes.coerceAtLeast(0L),
            status = TransferStatus.RUNNING,
            timestamp = System.currentTimeMillis(),
            accountId = accountId,
            sourceUri = sourceUri,
            mimeType = mimeType
        )
        transferTaskDao.upsert(TransferTaskEntity.fromModel(task, workRequestId = ""))
        return taskId
    }

    suspend fun isTaskCancelled(taskId: String): Boolean =
        transferTaskDao.getById(taskId)?.status == TransferStatus.CANCELLED.name

    suspend fun updateProgress(taskId: String, progress: Float, bytesTransferred: Long, totalBytes: Long) {
        transferTaskDao.updateProgress(taskId, progress, bytesTransferred, totalBytes)
    }

    suspend fun updateChunkCheckpoint(taskId: String, completedChunks: Int, chunkSizeBytes: Long) {
        transferTaskDao.updateChunkCheckpoint(taskId, completedChunks, chunkSizeBytes)
    }

    suspend fun markTerminalState(
        taskId: String,
        status: TransferStatus,
        progress: Float,
        bytesTransferred: Long,
        localPath: String,
        errorMessage: String = ""
    ) {
        transferTaskDao.updateTerminalState(
            id = taskId,
            status = status.name,
            progress = progress,
            bytesTransferred = bytesTransferred,
            localPath = localPath,
            errorMessage = errorMessage
        )
    }

    private fun buildTransferWorkRequest(taskId: String) =
        OneTimeWorkRequestBuilder<KodTransferWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(KodTransferWorker.KEY_TASK_ID to taskId))
            .addTag(TAG_ALL_TRANSFERS)
            .build()

    private fun enqueueUniqueTransferWork(taskId: String, request: androidx.work.OneTimeWorkRequest) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_PREFIX$taskId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun queryUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "upload_${System.currentTimeMillis()}"
        var size = -1L
        val resolver = context.contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx).orEmpty().ifBlank { name }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    companion object {
        private const val WORK_PREFIX = "kodnext_transfer_"
        const val TAG_ALL_TRANSFERS = "kodnext_transfers"
    }
}
