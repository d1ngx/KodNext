package com.example.data.repository

import android.content.Context
import com.example.data.KodAccountStore
import com.example.data.KodAdminAndAccountClient
import com.example.data.KodBoxApiClient
import com.example.data.KodDirectoryCache
import com.example.data.KodSyncEngine
import com.example.data.local.KodDatabase
import com.example.data.security.KodCredentialVault

/**
 * Application-scoped singleton Dependency Injection container.
 * Guarantees that ViewModels, WorkManager workers ([com.example.data.KodSyncWorker],
 * [com.example.data.KodTransferWorker]), OkHttp session cookies, Keystore credential vault,
 * Room database, and L1/L2 caches share a single coherent instance graph.
 */
class KodAppContainer(context: Context) {
    private val appContext = context.applicationContext

    val apiClient: KodBoxApiClient by lazy { KodBoxApiClient() }
    val adminAccountClient: KodAdminAndAccountClient by lazy { KodAdminAndAccountClient(apiClient) }
    val credentialVault: KodCredentialVault by lazy { KodCredentialVault(appContext) }
    val accountStore: KodAccountStore by lazy { KodAccountStore(appContext, credentialVault) }
    val database: KodDatabase by lazy { KodDatabase.build(appContext) }
    val directoryCache: KodDirectoryCache by lazy { KodDirectoryCache() }
    val syncEngine: KodSyncEngine by lazy { KodSyncEngine(appContext, apiClient) }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            accountStore = accountStore,
            apiClient = apiClient,
            adminAccountClient = adminAccountClient
        )
    }

    val fileRepository: FileRepository by lazy {
        FileRepository(
            apiClient = apiClient,
            memoryCache = directoryCache,
            directoryCacheDao = database.directoryCacheDao()
        )
    }

    val syncRepository: SyncRepository by lazy {
        SyncRepository(
            context = appContext,
            syncRuleDao = database.syncRuleDao(),
            accountStore = accountStore,
            syncEngine = syncEngine
        )
    }

    val transferRepository: TransferRepository by lazy {
        TransferRepository(
            context = appContext,
            transferTaskDao = database.transferTaskDao()
        )
    }
}
