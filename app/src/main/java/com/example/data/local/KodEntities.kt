package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.BreadcrumbNode
import com.example.model.KodFileItem
import com.example.model.KodTag
import com.example.model.PathListResult
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncRunStatus
import com.example.model.TransferStatus
import com.example.model.TransferTask
import com.example.model.TransferType
import org.json.JSONArray
import org.json.JSONObject

@Entity(
    tableName = "sync_rules",
    indices = [Index(value = ["accountId"])]
)
data class SyncRuleEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val name: String,
    val localTreeUri: String,
    val localFolderLabel: String,
    val remotePath: String,
    val remoteFolderLabel: String,
    val direction: String,
    val frequency: String,
    val wifiOnly: Boolean,
    val enabled: Boolean,
    val lastSyncTime: Long,
    val lastStatus: String,
    val lastSummary: String
) {
    fun toModel(): SyncRule = SyncRule(
        id = id,
        accountId = accountId,
        name = name,
        localTreeUri = localTreeUri,
        localFolderLabel = localFolderLabel,
        remotePath = remotePath,
        remoteFolderLabel = remoteFolderLabel,
        direction = runCatching { SyncDirection.valueOf(direction) }.getOrDefault(SyncDirection.TWO_WAY),
        frequency = runCatching { SyncFrequency.valueOf(frequency) }.getOrDefault(SyncFrequency.HOURLY),
        wifiOnly = wifiOnly,
        enabled = enabled,
        lastSyncTime = lastSyncTime,
        lastStatus = runCatching { SyncRunStatus.valueOf(lastStatus) }.getOrDefault(SyncRunStatus.IDLE),
        lastSummary = lastSummary
    )

    companion object {
        fun fromModel(rule: SyncRule): SyncRuleEntity = SyncRuleEntity(
            id = rule.id,
            accountId = rule.accountId,
            name = rule.name,
            localTreeUri = rule.localTreeUri,
            localFolderLabel = rule.localFolderLabel,
            remotePath = rule.remotePath,
            remoteFolderLabel = rule.remoteFolderLabel,
            direction = rule.direction.name,
            frequency = rule.frequency.name,
            wifiOnly = rule.wifiOnly,
            enabled = rule.enabled,
            lastSyncTime = rule.lastSyncTime,
            lastStatus = rule.lastStatus.name,
            lastSummary = rule.lastSummary
        )
    }
}

@Entity(
    tableName = "transfer_tasks",
    indices = [
        Index(value = ["accountId", "timestamp"]),
        Index(value = ["status"])
    ]
)
data class TransferTaskEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val fileName: String,
    val remotePath: String,
    val localPath: String,
    val sourceUri: String,
    val mimeType: String,
    val workRequestId: String,
    val type: String,
    val progress: Float,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: String,
    val errorMessage: String,
    val timestamp: Long
) {
    fun toModel(): TransferTask = TransferTask(
        id = id,
        fileName = fileName,
        remotePath = remotePath,
        localPath = localPath,
        type = runCatching { TransferType.valueOf(type) }.getOrDefault(TransferType.DOWNLOAD),
        progress = progress,
        bytesTransferred = bytesTransferred,
        totalBytes = totalBytes,
        status = runCatching { TransferStatus.valueOf(status) }.getOrDefault(TransferStatus.FAILED),
        errorMessage = errorMessage,
        timestamp = timestamp,
        accountId = accountId,
        sourceUri = sourceUri,
        mimeType = mimeType
    )

    companion object {
        fun fromModel(task: TransferTask, workRequestId: String = ""): TransferTaskEntity = TransferTaskEntity(
            id = task.id,
            accountId = task.accountId,
            fileName = task.fileName,
            remotePath = task.remotePath,
            localPath = task.localPath,
            sourceUri = task.sourceUri,
            mimeType = task.mimeType,
            workRequestId = workRequestId,
            type = task.type.name,
            progress = task.progress,
            bytesTransferred = task.bytesTransferred,
            totalBytes = task.totalBytes,
            status = task.status.name,
            errorMessage = task.errorMessage,
            timestamp = task.timestamp
        )
    }
}

@Entity(
    tableName = "cached_directories",
    primaryKeys = ["accountId", "path", "sortKey"],
    indices = [Index(value = ["accountId"]), Index(value = ["cachedAtMs"])]
)
data class CachedDirectoryEntity(
    val accountId: String,
    val path: String,
    val sortKey: String,
    val currentTitle: String,
    val itemsJson: String,
    val tagsJson: String,
    val breadcrumbsJson: String,
    val cachedAtMs: Long
) {
    fun toPathListResult(): PathListResult? = DirectoryCacheCodec.decode(this)

    companion object {
        fun fromPathListResult(
            accountId: String,
            path: String,
            sortKey: String,
            result: PathListResult,
            cachedAtMs: Long = System.currentTimeMillis()
        ): CachedDirectoryEntity = DirectoryCacheCodec.encode(
            accountId = accountId,
            path = path,
            sortKey = sortKey,
            result = result,
            cachedAtMs = cachedAtMs
        )
    }
}

internal object DirectoryCacheCodec {

    fun encode(
        accountId: String,
        path: String,
        sortKey: String,
        result: PathListResult,
        cachedAtMs: Long
    ): CachedDirectoryEntity {
        val itemsArr = JSONArray()
        result.items.forEach { item ->
            val tagsArr = JSONArray()
            item.tags.forEach { t ->
                tagsArr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("name", t.name)
                        put("style", t.style)
                        put("colorHex", t.colorHex)
                    }
                )
            }
            itemsArr.put(
                JSONObject().apply {
                    put("path", item.path)
                    put("sourceId", item.sourceId)
                    put("name", item.name)
                    put("isFolder", item.isFolder)
                    put("size", item.size)
                    put("modifyTime", item.modifyTime)
                    put("createTime", item.createTime)
                    put("ext", item.ext)
                    put("isFav", item.isFav)
                    put("isShare", item.isShare)
                    put("tags", tagsArr)
                    put("fileThumb", item.fileThumb)
                    put("hashMd5", item.hashMd5)
                    put("ownerName", item.ownerName)
                    put("description", item.description)
                    put("isReadable", item.isReadable)
                    put("isWriteable", item.isWriteable)
                    put("isRecycleItem", item.isRecycleItem)
                    put("childCount", item.childCount)
                }
            )
        }

        val allTagsArr = JSONArray()
        result.tags.forEach { t ->
            allTagsArr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("style", t.style)
                    put("colorHex", t.colorHex)
                }
            )
        }

        val crumbsArr = JSONArray()
        result.breadcrumbs.forEach { node ->
            crumbsArr.put(
                JSONObject().apply {
                    put("label", node.label)
                    put("path", node.path)
                }
            )
        }

        return CachedDirectoryEntity(
            accountId = accountId,
            path = path.trim(),
            sortKey = sortKey,
            currentTitle = result.currentTitle,
            itemsJson = itemsArr.toString(),
            tagsJson = allTagsArr.toString(),
            breadcrumbsJson = crumbsArr.toString(),
            cachedAtMs = cachedAtMs
        )
    }

    fun decode(entity: CachedDirectoryEntity): PathListResult? = runCatching {
        val itemsArr = JSONArray(entity.itemsJson)
        val items = buildList {
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val tagsArr = obj.optJSONArray("tags")
                val itemTags = decodeTags(tagsArr)
                add(
                    KodFileItem(
                        path = obj.optString("path"),
                        sourceId = obj.optString("sourceId"),
                        name = obj.optString("name"),
                        isFolder = obj.optBoolean("isFolder", false),
                        size = obj.optLong("size", 0L),
                        modifyTime = obj.optLong("modifyTime", 0L),
                        createTime = obj.optLong("createTime", 0L),
                        ext = obj.optString("ext"),
                        isFav = obj.optBoolean("isFav", false),
                        isShare = obj.optBoolean("isShare", false),
                        tags = itemTags,
                        fileThumb = obj.optString("fileThumb"),
                        hashMd5 = obj.optString("hashMd5"),
                        ownerName = obj.optString("ownerName"),
                        description = obj.optString("description"),
                        isReadable = obj.optBoolean("isReadable", true),
                        isWriteable = obj.optBoolean("isWriteable", true),
                        isRecycleItem = obj.optBoolean("isRecycleItem", false),
                        childCount = obj.optInt("childCount", 0)
                    )
                )
            }
        }
        val tags = decodeTags(runCatching { JSONArray(entity.tagsJson) }.getOrNull())
        val crumbsArr = runCatching { JSONArray(entity.breadcrumbsJson) }.getOrNull()
        val crumbs = buildList {
            if (crumbsArr != null) {
                for (i in 0 until crumbsArr.length()) {
                    val obj = crumbsArr.optJSONObject(i) ?: continue
                    add(BreadcrumbNode(label = obj.optString("label"), path = obj.optString("path")))
                }
            }
        }
        PathListResult(
            currentPath = entity.path,
            currentTitle = entity.currentTitle,
            items = items,
            tags = tags,
            breadcrumbs = crumbs
        )
    }.getOrNull()

    private fun decodeTags(array: JSONArray?): List<KodTag> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(
                    KodTag(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        style = obj.optString("style", "label-blue-normal"),
                        colorHex = obj.optLong("colorHex", 0xFF0074C8L)
                    )
                )
            }
        }
    }
}
