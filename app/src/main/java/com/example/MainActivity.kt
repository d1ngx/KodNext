package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.AccountCenterScreen
import com.example.ui.screens.AdminConsoleScreen
import com.example.ui.screens.FilePreviewScreen
import com.example.ui.screens.KodAppWorkspaceScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.MainWorkspaceScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.KodNextTheme
import com.example.viewmodel.KodUiEvent
import com.example.viewmodel.KodViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: KodViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        is KodUiEvent.ShowMessage -> {
                            snackbarHostState.showSnackbar(
                                message = event.message,
                                duration = if (event.isError) SnackbarDuration.Long else SnackbarDuration.Short
                            )
                        }
                        is KodUiEvent.CopyToClipboard -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(event.label, event.text))
                            snackbarHostState.showSnackbar("已复制到剪贴板")
                        }
                        is KodUiEvent.OpenLocalFile -> {
                            runCatching {
                                val uri = FileProvider.getUriForFile(
                                    this@MainActivity,
                                    "$packageName.fileprovider",
                                    event.file
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, event.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(intent, "打开文件"))
                            }.onFailure {
                                snackbarHostState.showSnackbar("未找到可打开此类型文件的应用")
                            }
                        }
                    }
                }
            }

            KodNextTheme(themePreference = state.preferences.themePreference) {
                val activeAccount = state.activeAccount
                val imageLoader = remember(
                    activeAccount?.id,
                    activeAccount?.cookiesHeader,
                    activeAccount?.avatarUrl
                ) { viewModel.getAuthenticatedImageLoader() }

                when {
                    !state.isHydrated -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    activeAccount == null || state.showAddAccountLogin -> {
                        LoginScreen(
                            form = state.loginForm,
                            savedAccounts = state.accounts,
                            canCancel = activeAccount != null,
                            onUpdateForm = { viewModel.updateLoginForm(it) },
                            onProbeServer = { viewModel.probeServerConnection() },
                            onRefreshCaptcha = { viewModel.refreshCaptcha() },
                            onSubmitLogin = { viewModel.submitLogin() },
                            onSelectSavedAccount = { viewModel.switchAccount(it) },
                            onCancel = { viewModel.cancelAddAccountLogin() }
                        )
                    }

                    state.activeWorkspaceApp != null -> {
                        KodAppWorkspaceScreen(
                            account = activeAccount,
                            app = state.activeWorkspaceApp!!,
                            onBack = { viewModel.closeWorkspaceApp() }
                        )
                    }

                    state.previewState != null -> {
                        val preview = state.previewState!!
                        FilePreviewScreen(
                            account = activeAccount,
                            apiClient = viewModel.apiClient,
                            serverPlugins = state.serverPlugins,
                            preview = preview,
                            fullPreviewUrl = viewModel.apiClient.buildFullPreviewUrl(activeAccount, preview.item),
                            imageLoader = imageLoader,
                            onBack = { viewModel.closePreview() },
                            onDownload = { viewModel.downloadItems(listOf(preview.item)) },
                            onShare = {
                                viewModel.closePreview()
                                viewModel.showShareDialog(preview.item)
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(preview.item) },
                            onUpdateText = { viewModel.updatePreviewTextContent(it) },
                            onToggleEditMode = { viewModel.togglePreviewEditMode(it) },
                            onSaveText = { viewModel.savePreviewTextContent() }
                        )
                    }

                    state.showAdminConsole -> {
                        AdminConsoleScreen(
                            state = state,
                            onBack = { viewModel.openAdminConsole(false) },
                            onRefresh = { viewModel.loadAdminOverview() },
                            onTogglePlugin = { pluginId, enable ->
                                viewModel.toggleAdminPluginStatus(pluginId, enable)
                            },
                            onOpenWebAdmin = { viewModel.openWebAdminPortal() }
                        )
                    }

                    state.showAccountCenter -> {
                        AccountCenterScreen(
                            state = state,
                            imageLoader = imageLoader,
                            onBack = { viewModel.openAccountCenter(false) },
                            onRefresh = { viewModel.loadAccountCenterData() },
                            onSaveProfile = { nick, email, phone, sex ->
                                viewModel.updateAccountProfile(nick, email, phone, sex)
                            },
                            onChangePassword = { oldPwd, newPwd ->
                                viewModel.changeAccountPassword(oldPwd, newPwd)
                            }
                        )
                    }

                    state.showSettings -> {
                        SettingsScreen(
                            state = state,
                            imageLoader = imageLoader,
                            onBack = { viewModel.openSettings(false) },
                            onCheckConnection = { viewModel.refreshAccountQuota() },
                            onAddAccount = { viewModel.openAddAccountLogin() },
                            onSwitchAccount = { viewModel.switchAccount(it) },
                            onLogoutAccount = { viewModel.logoutAccount(it) },
                            onSetTheme = { viewModel.setThemePreference(it) },
                            onUpdatePrefs = { viewModel.updatePreferences(it) },
                            onClearCache = { viewModel.clearImageCache() },
                            onCopyDiagnostics = { viewModel.copyText("KodNext 诊断信息", it) }
                        )
                    }

                    else -> {
                        MainWorkspaceScreen(
                            state = state,
                            viewModel = viewModel,
                            imageLoader = imageLoader,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }
        }
    }
}
