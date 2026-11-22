package com.example.data.repository

import com.example.data.KodBoxApiClient
import com.example.data.KodDirectoryCache
import com.example.data.local.CachedDirectoryEntity
import com.example.data.local.DirectoryCacheDao
import com.example.model.KodAccount
import com.example.model.KodFileItem
import com.example.model.KodShareItem
import com.example.model.KodTag
import com.example.model.PathListResult
import com.example.model.ShareAccessMode
import com.example.model.SortField
import com.example.model.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two-tier Stale-While-Revalidate File Repository:
 * - L1: Fast in-memory LRU [KodDirectoryCache] (0ms hot navigation)
 * - L2: Persistent Room [DirectoryCacheDao] (instant cold-start & offline directory browsing)
 * - Remote: Real [KodBoxApiClient] HTTP API
 */
class FileRepository(
    val apiClient: KodBoxApiClient,
    private val memoryCache: KodDirectoryCache,
    private val directoryCacheDao: DirectoryCacheDao
) {
    private fun sortKey(sortField: SortField, sortOrder: SortOrder): String =
        "${sortField.apiKey}_${sortOrder.apiKey}"

    /**
     * Reads L1 memory cache first, then falls back to L2 Room cache (populating L1 on hit).
     */
    suspend fun getCachedDirectory(
        accountId: String,
        path: String,
        sortField: SortField,
        sortOrder: SortOrder
    ): PathListResult? {
        memoryCache.get(accountId, path, sortField, sortOrder)?.let { return it }
        val entity = withContext(Dispatchers.IO) {
            directoryCacheDao.get(accountId, path.trim(), sortKey(sortField, sortOrder))
        } ?: return null

        val isFreshEnough = System.currentTimeMillis() - entity.cachedAtMs <= L2_CACHE_MAX_AGE_MS
        if (!isFreshEnough) return null

        val decoded = entity.toPathListResult() ?: return null
        memoryCache.put(accountId, path, sortField, sortOrder, decoded)
        return decoded
    }

    /**
     * Fetches the latest directory listing from the remote server and writes through to both L1 and L2 caches.
     */
    suspend fun fetchRemoteDirectory(
        account: KodAccount,
        path: String,
        sortField: SortField = SortField.NAME,
        sortOrder: SortOrder = SortOrder.ASC
    ): Result<PathListResult> {
        val result = apiClient.listPath(account, path, sortField, sortOrder)
        result.onSuccess { listing ->
            memoryCache.put(account.id, path, sortField, sortOrder, listing)
            withContext(Dispatchers.IO) {
                runCatching {
                    directoryCacheDao.upsert(
                        CachedDirectoryEntity.fromPathListResult(
                            accountId = account.id,
                            path = path,
                            sortKey = sortKey(sortField, sortOrder),
                            result = listing
                        )
                    )
                    // Opportunistically keep L2 cache bounded to last 14 days
                    directoryCacheDao.pruneOlderThan(System.currentTimeMillis() - L2_CACHE_MAX_AGE_MS)
                }
            }
        }
        return result
    }

    suspend fun invalidatePath(accountId: String, path: String) {
        memoryCache.invalidatePath(accountId, path)
        withContext(Dispatchers.IO) {
            runCatching { directoryCacheDao.invalidatePath(accountId, path.trim()) }
        }
    }

    suspend fun invalidateAccount(accountId: String) {
        memoryCache.invalidateAccount(accountId)
        withContext(Dispatchers.IO) {
            runCatching { directoryCacheDao.invalidateAccount(accountId) }
        }
    }

    suspend fun clearAllDirectoryCache() {
        memoryCache.clearAll()
        withContext(Dispatchers.IO) {
            runCatching { directoryCacheDao.clearAll() }
        }
    }

    // Search & CRUD pass-throughs with automatic cache invalidation on mutations
    suspend fun searchFiles(account: KodAccount, keyword: String, scopePath: String): Result<List<KodFileItem>> =
        apiClient.searchFiles(account, keyword, scopePath)

    suspend fun createFolder(account: KodAccount, targetDir: String, name: String): Result<Unit> =
        apiClient.createFolder(account, targetDir, name).also { if (it.isSuccess) invalidatePath(account.id, targetDir) }

    suspend fun createTextFile(account: KodAccount, targetDir: String, fileName: String, content: String): Result<Unit> =
        apiClient.createTextFile(account, targetDir, fileName, content).also { if (it.isSuccess) invalidatePath(account.id, targetDir) }

    suspend fun renameItem(account: KodAccount, path: String, newName: String): Result<Unit> =
        apiClient.renameItem(account, path, newName).also { if (it.isSuccess) invalidateAccount(account.id) }

    suspend fun deleteItems(account: KodAccount, items: List<KodFileItem>, permanently: Boolean): Result<Unit> =
        apiClient.deleteItems(account, items, permanently).also { if (it.isSuccess) invalidateAccount(account.id) }

    suspend fun restoreRecycleItems(account: KodAccount, items: List<KodFileItem>): Result<Unit> =
        apiClient.restoreRecycleItems(account, items).also { if (it.isSuccess) invalidateAccount(account.id) }

    suspend fun clearRecycleBin(account: KodAccount): Result<Unit> =
        apiClient.clearRecycleBin(account).also { if (it.isSuccess) invalidateAccount(account.id) }

    suspend fun toggleFavorite(account: KodAccount, item: KodFileItem, favorite: Boolean): Result<Unit> =
        apiClient.toggleFavorite(account, item, favorite).also { if (it.isSuccess) invalidateAccount(account.id) }

    suspend fun zipItems(account: KodAccount, items: List<KodFileItem>): Result<Unit> =
        apiClient.zipItems(account, items).also { if (it.isSuccess) invalidateAccount(account.id) }

    suspend fun unzipItem(account: KodAccount, item: KodFileItem): Result<Unit> =
        apiClient.unzipItem(account, item).also { if (it.isSuccess) invalidateAccount(account.id) }

    suspend fun offlineServerDownload(account: KodAccount, targetDir: String, remoteUrl: String): Result<Unit> =
        apiClient.offlineServerDownload(account, targetDir, remoteUrl).also { if (it.isSuccess) invalidatePath(account.id, targetDir) }

    suspend fun copyOrMoveItems(account: KodAccount, items: List<KodFileItem>, targetPath: String, isMove: Boolean): Result<Unit> =
        apiClient.copyOrMoveItems(account, items, targetPath, isMove).also { if (it.isSuccess) invalidateAccount(account.id) }

    // Tags
    suspend fun listTags(account: KodAccount): Result<List<KodTag>> = apiClient.listTags(account)
    suspend fun createTag(account: KodAccount, name: String, style: String): Result<Unit> = apiClient.createTag(account, name, style)
    suspend fun setItemTag(account: KodAccount, item: KodFileItem, tagId: String, add: Boolean): Result<Unit> =
        apiClient.setItemTag(account, item, tagId, add).also { if (it.isSuccess) invalidateAccount(account.id) }

    // Text Preview / Edit
    suspend fun readTextFile(account: KodAccount, item: KodFileItem): Result<String> = apiClient.readTextFile(account, item)
    suspend fun saveTextFile(account: KodAccount, item: KodFileItem, content: String): Result<Unit> =
        apiClient.saveTextFile(account, item, content).also { if (it.isSuccess) invalidateAccount(account.id) }

    // Shares
    suspend fun listShares(account: KodAccount): Result<List<KodShareItem>> = apiClient.listShares(account)
    suspend fun listMyCollaborationShares(account: KodAccount): Result<List<KodShareItem>> = apiClient.listMyCollaborationShares(account)
    suspend fun listSharedWithMe(account: KodAccount): Result<List<KodFileItem>> = apiClient.listSharedWithMe(account)
    suspend fun createOrUpdateShare(
        account: KodAccount,
        item: KodFileItem,
        password: String,
        expireEpochSeconds: Long,
        accessMode: ShareAccessMode
    ): Result<KodShareItem> = apiClient.createOrUpdateShare(account, item, password, expireEpochSeconds, accessMode)

    suspend fun updateExistingShare(
        account: KodAccount,
        share: KodShareItem,
        password: String,
        expireEpochSeconds: Long,
        accessMode: ShareAccessMode
    ): Result<KodShareItem> = apiClient.updateExistingShare(account, share, password, expireEpochSeconds, accessMode)

    suspend fun cancelShare(account: KodAccount, shareId: String): Result<Unit> = apiClient.cancelShare(account, shareId)

    companion object {
        private const val L2_CACHE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L // 14 days offline support
    }
}
