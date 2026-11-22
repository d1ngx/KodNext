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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.AccountCenterScreen
import com.example.ui.screens.FilePreviewScreen
import com.example.ui.screens.KodAppWorkspaceScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.MainWorkspaceScreen
import com.example.ui.screens.QrScannerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.KodNextTheme
import com.example.viewmodel.KodUiEvent
import com.example.viewmodel.KodViewModel
import kotlinx.coroutines.launch

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
                            launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    message = event.message,
                                    duration = if (event.isError) SnackbarDuration.Long else SnackbarDuration.Short
                                )
                            }
                        }
                        is KodUiEvent.CopyToClipboard -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(event.label, event.text))
                            launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    message = "已复制到剪贴板",
                                    duration = SnackbarDuration.Short
                                )
                            }
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
                                launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar("未找到可打开此类型文件的应用")
                                }
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

                val isShowingMainWorkspace = state.isHydrated &&
                    !state.showQrScanner &&
                    activeAccount != null &&
                    !state.showAddAccountLogin &&
                    state.activeWorkspaceApp == null &&
                    state.previewState == null &&
                    !state.showAccountCenter &&
                    !state.showSettings

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        !state.isHydrated -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }

                        state.showQrScanner -> {
                            QrScannerScreen(
                                onQrScanned = { viewModel.handleScannedQrContent(it) },
                                onClose = { viewModel.openQrScanner(false) }
                            )
                        }

                        activeAccount == null || state.showAddAccountLogin -> {
                            LoginScreen(
                                form = state.loginForm,
                                savedAccounts = state.accounts,
                                canCancel = activeAccount != null,
                                onUpdateForm = { viewModel.updateLoginForm(it) },
                                onProbeServer = { viewModel.probeServerConnection() },
                                onBackToServerStep = { viewModel.resetLoginToServerStep() },
                                onOpenQrScanner = { viewModel.openQrScanner(true) },
                                onRefreshCaptcha = { viewModel.refreshCaptcha() },
                                onSubmitLogin = { viewModel.submitLogin() },
                                onSelectSavedAccount = { viewModel.switchAccount(it) },
                                onCancel = { viewModel.cancelAddAccountLogin() }
                            )
                        }

                        state.activeWorkspaceApp != null -> {
                            val app = state.activeWorkspaceApp!!
                            KodAppWorkspaceScreen(
                                account = activeAccount,
                                app = app,
                                onCopyUrl = { url -> viewModel.copyText(app.title, url) },
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

                        state.showAccountCenter -> {
                            AccountCenterScreen(
                                state = state,
                                imageLoader = imageLoader,
                                onBack = { viewModel.openAccountCenter(false) },
                                onRefresh = { viewModel.loadAccountCenterData() },
                                onOpenWebAccount = { viewModel.openWebAccountPortal() },
                                onSaveProfile = { nick, email, phone, sex ->
                                    viewModel.updateAccountProfile(nick, email, phone, sex)
                                },
                                onChangePassword = { oldPwd, newPwd ->
                                    viewModel.changeAccountPassword(oldPwd, newPwd)
                                },
                                onLogout = { viewModel.logoutAccount(activeAccount) }
                            )
                        }

                        state.showSettings -> {
                            SettingsScreen(
                                state = state,
                                imageLoader = imageLoader,
                                onBack = { viewModel.openSettings(false) },
                                onCheckConnection = { viewModel.refreshAccountQuota() },
                                onSetTheme = { viewModel.setThemePreference(it) },
                                onUpdatePrefs = { viewModel.updatePreferences(it) },
                                onClearCache = { viewModel.clearImageCache() }
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

                    // Render global SnackbarHost on LoginScreen and all secondary screens
                    // (MainWorkspaceScreen renders its own Scaffold SnackbarHost above the bottom bar/FAB)
                    if (!isShowingMainWorkspace) {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
