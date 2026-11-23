package com.example.data.repository

import com.example.data.KodAccountStore
import com.example.data.KodAdminAndAccountClient
import com.example.data.KodBoxApiClient
import com.example.model.AppPreferences
import com.example.model.KodAccount
import com.example.model.KodUserDeviceItem
import com.example.model.KodUserLogItem
import com.example.model.ServerProbeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for multi-server account sessions, credentials (Keystore-backed),
 * preferences, profile updates, and session hydration.
 */
class AuthRepository(
    private val accountStore: KodAccountStore,
    private val apiClient: KodBoxApiClient,
    private val adminAccountClient: KodAdminAndAccountClient
) {
    val accountsFlow: Flow<List<KodAccount>> = accountStore.accountsFlow
    val activeAccountIdFlow: Flow<String?> = accountStore.activeAccountIdFlow
    val preferencesFlow: Flow<AppPreferences> = accountStore.preferencesFlow

    suspend fun migrateLegacySecretsIfNeeded() {
        accountStore.migrateLegacySecretsIfNeeded()
    }

    suspend fun getAccountById(accountId: String): KodAccount? {
        return accountStore.accountsFlow.first().find { it.id == accountId }
    }

    fun hydrateSession(account: KodAccount) {
        apiClient.hydrateAccountSession(account)
    }

    suspend fun probeServer(rawUrl: String): Result<ServerProbeInfo> =
        apiClient.probeServer(rawUrl)

    suspend fun fetchCaptchaBytes(rawUrl: String): Result<ByteArray> =
        apiClient.fetchCaptchaBytes(rawUrl)

    suspend fun loginWithPassword(
        serverUrl: String,
        username: String,
        password: String,
        checkCode: String
    ): Result<KodAccount> {
        val result = apiClient.loginWithPassword(serverUrl, username, password, checkCode)
        result.onSuccess { account ->
            accountStore.saveOrUpdateAccount(account, makeActive = true)
            apiClient.hydrateAccountSession(account)
        }
        return result
    }

    suspend fun loginWithAppId(serverUrl: String, loginAppId: String): Result<KodAccount> {
        val result = apiClient.loginWithAppId(serverUrl, loginAppId)
        result.onSuccess { account ->
            accountStore.saveOrUpdateAccount(account, makeActive = true)
            apiClient.hydrateAccountSession(account)
        }
        return result
    }

    suspend fun loginWithToken(serverUrl: String, accessToken: String): Result<KodAccount> {
        val result = apiClient.loginWithToken(serverUrl, accessToken)
        result.onSuccess { account ->
            accountStore.saveOrUpdateAccount(account, makeActive = true)
            apiClient.hydrateAccountSession(account)
        }
        return result
    }

    suspend fun loginWithQrUrl(serverUrl: String, qrUrl: String): Result<KodAccount> {
        val result = apiClient.loginWithQrUrl(serverUrl, qrUrl)
        result.onSuccess { account ->
            accountStore.saveOrUpdateAccount(account, makeActive = true)
            apiClient.hydrateAccountSession(account)
        }
        return result
    }

    suspend fun loginWithWebViewSession(
        serverUrl: String,
        cookieHeader: String,
        tokenCandidate: String = ""
    ): Result<KodAccount> {
        val result = apiClient.loginWithWebViewSession(serverUrl, cookieHeader, tokenCandidate)
        result.onSuccess { account ->
            accountStore.saveOrUpdateAccount(account, makeActive = true)
            apiClient.hydrateAccountSession(account)
        }
        return result
    }

    suspend fun confirmWebQrLogin(account: KodAccount, qrUrl: String): Result<String> =
        apiClient.confirmWebQrLogin(account, qrUrl)

    suspend fun saveOrUpdateAccount(account: KodAccount, makeActive: Boolean = true) {
        accountStore.saveOrUpdateAccount(account, makeActive = makeActive)
    }

    suspend fun setActiveAccount(accountId: String) {
        accountStore.setActiveAccount(accountId)
    }

    suspend fun removeAccount(accountId: String) {
        accountStore.removeAccount(accountId)
    }

    suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        accountStore.updatePreferences(transform)
    }

    suspend fun refreshAccountInfo(account: KodAccount): Result<KodAccount> {
        return apiClient.refreshAccountInfo(account).onSuccess { refreshed ->
            accountStore.saveOrUpdateAccount(
                refreshed.copy(
                    lanServerUrl = account.lanServerUrl,
                    preferLan = account.preferLan
                ),
                makeActive = false
            )
        }
    }

    suspend fun updateAccountLanEndpoint(
        account: KodAccount,
        lanServerUrl: String,
        preferLan: Boolean
    ): KodAccount {
        val normalizedLan = com.example.data.KodEndpointResolver.normalizeOptionalLanUrl(lanServerUrl)
        val updated = account.copy(lanServerUrl = normalizedLan, preferLan = preferLan)
        accountStore.saveOrUpdateAccount(updated, makeActive = false)
        return updated
    }

    suspend fun probeAccountEndpoint(context: android.content.Context?, account: KodAccount): com.example.data.AccountEndpointStatus {
        return apiClient.probeAccountEndpoint(context, account)
    }

    suspend fun fetchServerPluginsDiscovery(account: KodAccount) =
        apiClient.fetchServerPluginsDiscovery(account)

    suspend fun fetchUserDevices(account: KodAccount): Result<List<KodUserDeviceItem>> =
        adminAccountClient.fetchUserDevices(account)

    suspend fun fetchUserLogs(account: KodAccount): Result<List<KodUserLogItem>> =
        adminAccountClient.fetchUserLogs(account)

    suspend fun updateUserProfileField(account: KodAccount, key: String, value: String): Result<Unit> =
        adminAccountClient.updateUserProfileField(account, key, value)

    suspend fun changePassword(account: KodAccount, oldPassword: String, newPassword: String): Result<Unit> =
        adminAccountClient.changePassword(account, oldPassword, newPassword)
}
