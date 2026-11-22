package com.example.data

import com.example.model.PathListResult
import com.example.model.SortField
import com.example.model.SortOrder

/**
 * Thread-safe bounded LRU in-memory cache for KodBox directory listings.
 * Enables 0ms Stale-While-Revalidate navigation when switching spaces or stepping up breadcrumbs.
 */
class KodDirectoryCache(
    private val maxEntries: Int = 48,
    private val ttlMs: Long = 90_000L
) {
    private data class CacheKey(
        val accountId: String,
        val path: String,
        val sortField: SortField,
        val sortOrder: SortOrder
    )

    private data class CacheEntry(
        val result: PathListResult,
        val cachedAtMs: Long
    )

    private val map = object : LinkedHashMap<CacheKey, CacheEntry>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(
        accountId: String,
        path: String,
        sortField: SortField,
        sortOrder: SortOrder
    ): PathListResult? {
        val key = CacheKey(accountId, path.trim(), sortField, sortOrder)
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.cachedAtMs > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.result
    }

    @Synchronized
    fun put(
        accountId: String,
        path: String,
        sortField: SortField,
        sortOrder: SortOrder,
        result: PathListResult
    ) {
        val key = CacheKey(accountId, path.trim(), sortField, sortOrder)
        map[key] = CacheEntry(result = result, cachedAtMs = System.currentTimeMillis())
    }

    @Synchronized
    fun invalidatePath(accountId: String, path: String) {
        val norm = path.trim()
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val k = iterator.next().key
            if (k.accountId == accountId && (k.path == norm || k.path.startsWith("{userRencent") || k.path.startsWith("{userFav"))) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun invalidateAccount(accountId: String) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key.accountId == accountId) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clearAll() {
        map.clear()
    }
}
