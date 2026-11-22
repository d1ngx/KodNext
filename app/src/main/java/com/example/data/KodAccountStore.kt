package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.security.AccountSecret
import com.example.data.security.KodCredentialVault
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.kodDataStore: DataStore<Preferences> by preferencesDataStore(name = "kodnext_prefs")

/**
 * Stores non-sensitive account metadata and user preferences in Preferences DataStore,
 * while isolating sensitive session credentials (accessToken, cookiesHeader, csrfToken)
 * inside the Android Keystore-backed [KodCredentialVault].
 */
class KodAccountStore(
    private val context: Context,
    private val credentialVault: KodCredentialVault
) {

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
        val LEGACY_SYNC_RULES_JSON = stringPreferencesKey("sync_rules_json")
    }

    val accountsFlow: Flow<List<KodAccount>> = combine(
        context.kodDataStore.data,
        credentialVault.secretsFlow
    ) { prefs, secretsMap ->
        decodeAccounts(prefs[Keys.ACCOUNTS_JSON].orEmpty(), secretsMap)
    }

    val activeAccountIdFlow: Flow<String?> = context.kodDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_ACCOUNT_ID]
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

    /**
     * One-time transparent migration: moves any legacy plaintext tokens/cookies found in
     * `accounts_json` into Keystore-encrypted [KodCredentialVault] and scrubs them from DataStore.
     */
    suspend fun migrateLegacySecretsIfNeeded() {
        val prefs = context.kodDataStore.data.first()
        val raw = prefs[Keys.ACCOUNTS_JSON].orEmpty()
        if (raw.isBlank()) return
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return

        var foundPlaintextSecret = false
        val legacySecrets = mutableListOf<Pair<String, AccountSecret>>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val token = obj.optString("accessToken")
            val cookies = obj.optString("cookiesHeader")
            val csrf = obj.optString("csrfToken")
            if (token.isNotBlank() || cookies.isNotBlank() || csrf.isNotBlank()) {
                foundPlaintextSecret = true
                if (id.isNotBlank()) {
                    legacySecrets.add(id to AccountSecret(token, cookies, csrf))
                }
            }
        }
        if (!foundPlaintextSecret) return

        for ((id, secret) in legacySecrets) {
            val existing = credentialVault.getSecret(id)
            if (existing.isEmpty) {
                credentialVault.saveSecret(id, secret)
            }
        }
        val accounts = decodeAccounts(raw, emptyMap())
        context.kodDataStore.edit { mutablePrefs ->
            mutablePrefs[Keys.ACCOUNTS_JSON] = encodeAccountsMetadataOnly(accounts)
        }
    }

    /**
     * Reads and clears pre-6.2 DataStore `sync_rules_json` so [com.example.data.repository.SyncRepository]
     * can seamlessly migrate user sync rules into Room on first launch.
     */
    suspend fun consumeLegacySyncRules(): List<SyncRule> {
        val prefs = context.kodDataStore.data.first()
        val raw = prefs[Keys.LEGACY_SYNC_RULES_JSON].orEmpty()
        if (raw.isBlank()) return emptyList()
        val migrated = decodeSyncRules(raw)
        context.kodDataStore.edit { mutablePrefs ->
            mutablePrefs.remove(Keys.LEGACY_SYNC_RULES_JSON)
        }
        return migrated
    }

    suspend fun saveOrUpdateAccount(account: KodAccount, makeActive: Boolean = true) {
        // Persist sensitive tokens/cookies exclusively in Keystore-encrypted vault
        credentialVault.saveSecret(
            accountId = account.id,
            secret = AccountSecret(
                accessToken = account.accessToken,
                cookiesHeader = account.cookiesHeader,
                csrfToken = account.csrfToken
            )
        )
        context.kodDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[Keys.ACCOUNTS_JSON].orEmpty(), emptyMap()).toMutableList()
            val idx = current.indexOfFirst {
                it.id == account.id || (it.serverUrl == account.serverUrl && it.username == account.username)
            }
            if (idx >= 0) {
                val previousId = current[idx].id
                if (previousId != account.id && previousId.isNotBlank()) {
                    credentialVault.removeSecret(previousId)
                }
                current[idx] = account
            } else {
                current.add(0, account)
            }
            prefs[Keys.ACCOUNTS_JSON] = encodeAccountsMetadataOnly(current)
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
        credentialVault.removeSecret(accountId)
        context.kodDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[Keys.ACCOUNTS_JSON].orEmpty(), emptyMap())
                .filterNot { it.id == accountId }
            prefs[Keys.ACCOUNTS_JSON] = encodeAccountsMetadataOnly(current)
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

    internal fun encodeAccountsMetadataOnly(accounts: List<KodAccount>): String {
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
                    // Intentionally OMIT accessToken, cookiesHeader, csrfToken from plaintext DataStore
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

    private fun decodeAccounts(raw: String, secretsMap: Map<String, AccountSecret>): List<KodAccount> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val vaultSecret = secretsMap[id]
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
                            id = id,
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
                            accessToken = vaultSecret?.accessToken?.takeIf { it.isNotBlank() }
                                ?: obj.optString("accessToken"),
                            cookiesHeader = vaultSecret?.cookiesHeader?.takeIf { it.isNotBlank() }
                                ?: obj.optString("cookiesHeader"),
                            csrfToken = vaultSecret?.csrfToken?.takeIf { it.isNotBlank() }
                                ?: obj.optString("csrfToken"),
                            userId = obj.optString("userId"),
                            sizeUse = obj.optLong("sizeUse", 0L),
                            sizeMax = obj.optLong("sizeMax", 0L),
                            serverVersion = obj.optString("serverVersion"),
                            siteName = obj.optString("siteName", "网盘").let {
                                if (it == "我的网盘" || it.isBlank()) "网盘" else it
                            },
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
