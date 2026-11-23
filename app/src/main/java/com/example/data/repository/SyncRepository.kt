package com.example.data.repository

import android.content.Context
import com.example.data.KodAccountStore
import com.example.data.KodSyncEngine
import com.example.data.SyncOutcome
import com.example.data.local.SyncRuleDao
import com.example.data.local.SyncRuleEntity
import com.example.data.local.SyncSnapshotDao
import com.example.model.KodAccount
import com.example.model.SyncRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed repository for bidirectional folder sync rules, transparent migration from
 * legacy DataStore JSON, WorkManager scheduling, and sync execution.
 */
class SyncRepository(
    private val context: Context,
    private val syncRuleDao: SyncRuleDao,
    private val accountStore: KodAccountStore,
    private val syncEngine: KodSyncEngine,
    private val syncSnapshotDao: SyncSnapshotDao? = null
) {
    fun observeAllRules(): Flow<List<SyncRule>> =
        syncRuleDao.observeAll().map { entities -> entities.map { it.toModel() } }

    fun observeRulesForAccount(accountId: String): Flow<List<SyncRule>> =
        syncRuleDao.observeByAccount(accountId).map { entities -> entities.map { it.toModel() } }

    suspend fun getRuleById(ruleId: String): SyncRule? =
        syncRuleDao.getById(ruleId)?.toModel()

    /**
     * One-time migration moving pre-6.2 `sync_rules_json` from Preferences DataStore into Room.
     */
    suspend fun migrateLegacyRulesIfNeeded() {
        val legacyRules = accountStore.consumeLegacySyncRules()
        if (legacyRules.isNotEmpty()) {
            syncRuleDao.upsertAll(legacyRules.map { SyncRuleEntity.fromModel(it) })
            legacyRules.forEach { KodSyncEngine.scheduleRule(context, it) }
        }
    }

    suspend fun saveRule(rule: SyncRule, rescheduleWorker: Boolean = true) {
        syncRuleDao.upsert(SyncRuleEntity.fromModel(rule))
        if (rescheduleWorker) {
            KodSyncEngine.scheduleRule(context, rule)
        }
    }

    suspend fun deleteRule(ruleId: String) {
        KodSyncEngine.cancelRule(context, ruleId)
        syncSnapshotDao?.deleteByRule(ruleId)
        syncRuleDao.deleteById(ruleId)
    }

    suspend fun executeSyncRule(account: KodAccount, rule: SyncRule): Result<SyncOutcome> =
        syncEngine.executeSyncRule(account, rule)

    fun isWifiConnected(): Boolean = KodSyncEngine.isUnmeteredWifiConnected(context)

    fun isRuleRunning(ruleId: String): Boolean = KodSyncEngine.isRuleRunning(ruleId)

    fun hasAnyRunningSync(): Boolean = KodSyncEngine.hasAnyRunningSync()
}
