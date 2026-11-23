package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRuleDao {

    @Query("SELECT * FROM sync_rules WHERE accountId = :accountId ORDER BY lastSyncTime DESC, name ASC")
    fun observeByAccount(accountId: String): Flow<List<SyncRuleEntity>>

    @Query("SELECT * FROM sync_rules ORDER BY lastSyncTime DESC, name ASC")
    fun observeAll(): Flow<List<SyncRuleEntity>>

    @Query("SELECT * FROM sync_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SyncRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SyncRuleEntity>)

    @Query("DELETE FROM sync_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM sync_rules WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)
}

@Dao
interface SyncSnapshotDao {

    @Query("SELECT * FROM sync_snapshots WHERE ruleId = :ruleId")
    suspend fun getByRule(ruleId: String): List<SyncSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncSnapshotEntity)

    @Query("DELETE FROM sync_snapshots WHERE ruleId = :ruleId")
    suspend fun deleteByRule(ruleId: String)

    @Query("DELETE FROM sync_snapshots WHERE ruleId = :ruleId AND relativePath = :relativePath")
    suspend fun deleteEntry(ruleId: String, relativePath: String)
}

@Dao
interface TransferTaskDao {

    @Query("SELECT * FROM transfer_tasks WHERE accountId = :accountId ORDER BY timestamp DESC")
    fun observeByAccount(accountId: String): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransferTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransferTaskEntity)

    @Query(
        """
        UPDATE transfer_tasks
        SET progress = :progress,
            bytesTransferred = :bytesTransferred,
            totalBytes = :totalBytes,
            status = :status
        WHERE id = :id AND status = 'RUNNING'
        """
    )
    suspend fun updateProgress(
        id: String,
        progress: Float,
        bytesTransferred: Long,
        totalBytes: Long,
        status: String = "RUNNING"
    )

    @Query(
        """
        UPDATE transfer_tasks
        SET status = :status,
            progress = :progress,
            bytesTransferred = :bytesTransferred,
            localPath = :localPath,
            errorMessage = :errorMessage
        WHERE id = :id
        """
    )
    suspend fun updateTerminalState(
        id: String,
        status: String,
        progress: Float,
        bytesTransferred: Long,
        localPath: String,
        errorMessage: String
    )

    @Query("UPDATE transfer_tasks SET workRequestId = :workRequestId, status = 'RUNNING', errorMessage = '' WHERE id = :id")
    suspend fun markRestarted(id: String, workRequestId: String)

    @Query("UPDATE transfer_tasks SET completedChunks = :completedChunks, chunkSizeBytes = :chunkSizeBytes WHERE id = :id")
    suspend fun updateChunkCheckpoint(id: String, completedChunks: Int, chunkSizeBytes: Long)

    @Query("UPDATE transfer_tasks SET status = 'CANCELLED', errorMessage = '' WHERE id = :id")
    suspend fun markCancelled(id: String)

    @Query("DELETE FROM transfer_tasks WHERE accountId = :accountId AND status != 'RUNNING'")
    suspend fun deleteFinishedForAccount(accountId: String)

    @Query("DELETE FROM transfer_tasks WHERE status != 'RUNNING'")
    suspend fun deleteAllFinished()

    @Query("DELETE FROM transfer_tasks WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)
}

@Dao
interface DirectoryCacheDao {

    @Query(
        """
        SELECT * FROM cached_directories
        WHERE accountId = :accountId AND path = :path AND sortKey = :sortKey
        LIMIT 1
        """
    )
    suspend fun get(accountId: String, path: String, sortKey: String): CachedDirectoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedDirectoryEntity)

    @Query(
        """
        DELETE FROM cached_directories
        WHERE accountId = :accountId AND (path = :path OR path LIKE '{userRencent%' OR path LIKE '{userFav%')
        """
    )
    suspend fun invalidatePath(accountId: String, path: String)

    @Query("DELETE FROM cached_directories WHERE accountId = :accountId")
    suspend fun invalidateAccount(accountId: String)

    @Query("DELETE FROM cached_directories WHERE cachedAtMs < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long)

    @Query("DELETE FROM cached_directories")
    suspend fun clearAll()
}
