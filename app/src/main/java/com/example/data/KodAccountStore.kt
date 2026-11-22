package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppPreferences
import com.example.model.KodAccount
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncRunStatus
import com.example.model.ThemePreference
import com.example.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.kodDataStore: DataStore<Preferences> by preferencesDataStore(name = "kodnext_prefs")

class KodAccountStore(private val context: Context) {

    private object Keys {
        val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        val ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val SHOW_THUMBNAILS = booleanPreferencesKey("show_thumbnails")
        val THEME_PREF = stringPreferencesKey("theme_pref")
        val PINNED_APP_ID = stringPreferencesKey("pinned_app_id")
        val SYNC_RULES_JSON = stringPreferencesKey("sync_rules_json")
    }

    val accountsFlow: Flow<List<KodAccount>> = context.kodDataStore.data.map { prefs ->
        decodeAccounts(prefs[Keys.ACCOUNTS_JSON].orEmpty())
    }

    val activeAccountIdFlow: Flow<String?> = context.kodDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_ACCOUNT_ID]
    }

    val syncRulesFlow: Flow<List<SyncRule>> = context.kodDataStore.data.map { prefs ->
        decodeSyncRules(prefs[Keys.SYNC_RULES_JSON].orEmpty())
    }

    val preferencesFlow: Flow<AppPreferences> = context.kodDataStore.data.map { prefs ->
        AppPreferences(
            viewMode = runCatching { ViewMode.valueOf(prefs[Keys.VIEW_MODE] ?: ViewMode.LIST.name) }.getOrDefault(ViewMode.LIST),
            sortField = runCatching { SortField.valueOf(prefs[Keys.SORT_FIELD] ?: SortField.NAME.name) }.getOrDefault(SortField.NAME),
            sortOrder = runCatching { SortOrder.valueOf(prefs[Keys.SORT_ORDER] ?: SortOrder.ASC.name) }.getOrDefault(SortOrder.ASC),
            showHiddenFiles = prefs[Keys.SHOW_HIDDEN] ?: false,
            showThumbnails = prefs[Keys.SHOW_THUMBNAILS] ?: true,
            themePreference = runCatching { ThemePreference.valueOf(prefs[Keys.THEME_PREF] ?: ThemePreference.SYSTEM.name) }.getOrDefault(ThemePreference.SYSTEM),
            pinnedAppId = prefs[Keys.PINNED_APP_ID] ?: "project"
        )
    }

    suspend fun saveOrUpdateAccount(account: KodAccount, makeActive: Boolean = true) {
        context.kodDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[Keys.ACCOUNTS_JSON].orEmpty()).toMutableList()
            val idx = current.indexOfFirst { it.id == account.id || (it.serverUrl == account.serverUrl && it.username == account.username) }
            if (idx >= 0) {
                current[idx] = account
            } else {
                current.add(0, account)
            }
            prefs[Keys.ACCOUNTS_JSON] = encodeAccounts(current)
            if (makeActive) {
                prefs[Keys.ACTIVE_ACCOUNT_ID] = account.id
            }
        }
    }

    suspend fun setActiveAccount(accountId: String) {
        context.kodDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_ACCOUNT_ID] = accountId
        }
    }

    suspend fun removeAccount(accountId: String) {
        context.kodDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[Keys.ACCOUNTS_JSON].orEmpty()).filterNot { it.id == accountId }
            prefs[Keys.ACCOUNTS_JSON] = encodeAccounts(current)
            if (prefs[Keys.ACTIVE_ACCOUNT_ID] == accountId) {
                val nextId = current.firstOrNull()?.id
                if (nextId != null) {
                    prefs[Keys.ACTIVE_ACCOUNT_ID] = nextId
                } else {
                    prefs.remove(Keys.ACTIVE_ACCOUNT_ID)
                }
            }
        }
    }

    suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        context.kodDataStore.edit { prefs ->
            val current = AppPreferences(
                viewMode = runCatching { ViewMode.valueOf(prefs[Keys.VIEW_MODE] ?: ViewMode.LIST.name) }.getOrDefault(ViewMode.LIST),
                sortField = runCatching { SortField.valueOf(prefs[Keys.SORT_FIELD] ?: SortField.NAME.name) }.getOrDefault(SortField.NAME),
                sortOrder = runCatching { SortOrder.valueOf(prefs[Keys.SORT_ORDER] ?: SortOrder.ASC.name) }.getOrDefault(SortOrder.ASC),
                showHiddenFiles = prefs[Keys.SHOW_HIDDEN] ?: false,
                showThumbnails = prefs[Keys.SHOW_THUMBNAILS] ?: true,
                themePreference = runCatching { ThemePreference.valueOf(prefs[Keys.THEME_PREF] ?: ThemePreference.SYSTEM.name) }.getOrDefault(ThemePreference.SYSTEM),
                pinnedAppId = prefs[Keys.PINNED_APP_ID] ?: "project"
            )
            val updated = transform(current)
            prefs[Keys.VIEW_MODE] = updated.viewMode.name
            prefs[Keys.SORT_FIELD] = updated.sortField.name
            prefs[Keys.SORT_ORDER] = updated.sortOrder.name
            prefs[Keys.SHOW_HIDDEN] = updated.showHiddenFiles
            prefs[Keys.SHOW_THUMBNAILS] = updated.showThumbnails
            prefs[Keys.THEME_PREF] = updated.themePreference.name
            prefs[Keys.PINNED_APP_ID] = updated.pinnedAppId
        }
    }

    private fun encodeAccounts(accounts: List<KodAccount>): String {
        val array = JSONArray()
        accounts.forEach { acc ->
            array.put(
                JSONObject().apply {
                    put("id", acc.id)
                    put("serverUrl", acc.serverUrl)
                    put("username", acc.username)
                    put("nickName", acc.nickName)
                    put("avatarUrl", acc.avatarUrl)
                    put("email", acc.email)
                    put("phone", acc.phone)
                    put("sex", acc.sex)
                    put("roleId", acc.roleId)
                    put("isAdmin", acc.isAdmin)
                    put("departments", JSONArray(acc.departments))
                    put("accessToken", acc.accessToken)
                    put("cookiesHeader", acc.cookiesHeader)
                    put("csrfToken", acc.csrfToken)
                    put("userId", acc.userId)
                    put("sizeUse", acc.sizeUse)
                    put("sizeMax", acc.sizeMax)
                    put("serverVersion", acc.serverVersion)
                    put("siteName", acc.siteName)
                    put("personalRootPath", acc.personalRootPath)
                    put("groupRootPath", acc.groupRootPath)
                    put("lastLoginAt", acc.lastLoginAt)
                }
            )
        }
        return array.toString()
    }

    private fun decodeAccounts(raw: String): List<KodAccount> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val deptArr = obj.optJSONArray("departments")
                    val depts = buildList {
                        if (deptArr != null) {
                            for (j in 0 until deptArr.length()) {
                                val d = deptArr.optString(j)
                                if (d.isNotBlank()) add(d)
                            }
                        }
                    }
                    add(
                        KodAccount(
                            id = obj.optString("id"),
                            serverUrl = obj.optString("serverUrl"),
                            username = obj.optString("username"),
                            nickName = obj.optString("nickName"),
                            avatarUrl = obj.optString("avatarUrl"),
                            email = obj.optString("email"),
                            phone = obj.optString("phone"),
                            sex = obj.optString("sex", "1"),
                            roleId = obj.optString("roleId"),
                            isAdmin = obj.optBoolean("isAdmin", false),
                            departments = depts,
                            accessToken = obj.optString("accessToken"),
                            cookiesHeader = obj.optString("cookiesHeader"),
                            csrfToken = obj.optString("csrfToken"),
                            userId = obj.optString("userId"),
                            sizeUse = obj.optLong("sizeUse", 0L),
                            sizeMax = obj.optLong("sizeMax", 0L),
                            serverVersion = obj.optString("serverVersion"),
                            siteName = obj.optString("siteName", "KodBox"),
                            personalRootPath = obj.optString("personalRootPath", "{source:1}/"),
                            groupRootPath = obj.optString("groupRootPath", "{block:enterprise}/").let {
                                if (it == "{block:files}/" || it.isBlank()) "{block:enterprise}/" else it
                            },
                            lastLoginAt = obj.optLong("lastLoginAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveOrUpdateSyncRule(rule: SyncRule) {
        context.kodDataStore.edit { prefs ->
            val current = decodeSyncRules(prefs[Keys.SYNC_RULES_JSON].orEmpty()).toMutableList()
            val idx = current.indexOfFirst { it.id == rule.id }
            if (idx >= 0) current[idx] = rule else current.add(0, rule)
            prefs[Keys.SYNC_RULES_JSON] = encodeSyncRules(current)
        }
    }

    suspend fun removeSyncRule(ruleId: String) {
        context.kodDataStore.edit { prefs ->
            val current = decodeSyncRules(prefs[Keys.SYNC_RULES_JSON].orEmpty()).filterNot { it.id == ruleId }
            prefs[Keys.SYNC_RULES_JSON] = encodeSyncRules(current)
        }
    }

    private fun encodeSyncRules(rules: List<SyncRule>): String {
        val arr = JSONArray()
        rules.forEach { rule ->
            arr.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("accountId", rule.accountId)
                    put("name", rule.name)
                    put("localTreeUri", rule.localTreeUri)
                    put("localFolderLabel", rule.localFolderLabel)
                    put("remotePath", rule.remotePath)
                    put("remoteFolderLabel", rule.remoteFolderLabel)
                    put("direction", rule.direction.name)
                    put("frequency", rule.frequency.name)
                    put("wifiOnly", rule.wifiOnly)
                    put("enabled", rule.enabled)
                    put("lastSyncTime", rule.lastSyncTime)
                    put("lastStatus", rule.lastStatus.name)
                    put("lastSummary", rule.lastSummary)
                }
            )
        }
        return arr.toString()
    }

    private fun decodeSyncRules(raw: String): List<SyncRule> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        SyncRule(
                            id = obj.optString("id"),
                            accountId = obj.optString("accountId"),
                            name = obj.optString("name"),
                            localTreeUri = obj.optString("localTreeUri"),
                            localFolderLabel = obj.optString("localFolderLabel"),
                            remotePath = obj.optString("remotePath"),
                            remoteFolderLabel = obj.optString("remoteFolderLabel"),
                            direction = runCatching { SyncDirection.valueOf(obj.optString("direction")) }.getOrDefault(SyncDirection.TWO_WAY),
                            frequency = runCatching { SyncFrequency.valueOf(obj.optString("frequency")) }.getOrDefault(SyncFrequency.HOURLY),
                            wifiOnly = obj.optBoolean("wifiOnly", true),
                            enabled = obj.optBoolean("enabled", true),
                            lastSyncTime = obj.optLong("lastSyncTime", 0L),
                            lastStatus = runCatching { SyncRunStatus.valueOf(obj.optString("lastStatus")) }.getOrDefault(SyncRunStatus.IDLE),
                            lastSummary = obj.optString("lastSummary", "尚未执行过同步")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
