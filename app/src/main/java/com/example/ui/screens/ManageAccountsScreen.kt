package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.model.KodAccount
import com.example.ui.components.ConfirmDangerDialog
import com.example.ui.components.KodUserAvatar
import com.example.viewmodel.KodUiState
import kotlin.math.abs

/**
 * Dedicated Nextcloud-inspired "管理账号" screen:
 * - Clean account list with circular avatar + active blue check badge on the current account
 * - Per-account 3-dot overflow menu: 「切换到账号」 and 「删除账号」 (with confirmation dialog)
 * - Direct 「添加账号」 row right below the account list + top-bar QR scan shortcut
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAccountsScreen(
    state: KodUiState,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onSwitchAccount: (KodAccount) -> Unit,
    onDeleteAccount: (KodAccount) -> Unit,
    onAddAccount: () -> Unit,
    onOpenQrScanner: () -> Unit
) {
    BackHandler(onBack = onBack)

    val activeAccountId = state.activeAccount?.id
    var pendingDeleteAccount by remember { mutableStateOf<KodAccount?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "管理账号",
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("manage_accounts_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenQrScanner,
                        modifier = Modifier.testTag("manage_accounts_qr_add")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = "扫码添加账号"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .padding(vertical = 8.dp)
                    .testTag("manage_accounts_list")
            ) {
                items(
                    items = state.accounts,
                    key = { it.id }
                ) { account ->
                    val isActive = account.id == activeAccountId
                    ManageAccountRow(
                        account = account,
                        isActive = isActive,
                        imageLoader = imageLoader,
                        onRowClick = {
                            if (!isActive) {
                                onSwitchAccount(account)
                            }
                        },
                        onSwitchClick = { onSwitchAccount(account) },
                        onDeleteClick = { pendingDeleteAccount = account }
                    )
                }

                item(key = "add_account_row") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onAddAccount)
                            .padding(horizontal = 20.dp, vertical = 18.dp)
                            .testTag("manage_accounts_add_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PersonAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "添加账号",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }
    }

    pendingDeleteAccount?.let { target ->
        val isActive = target.id == activeAccountId
        val extraHint = if (isActive && state.accounts.size > 1) {
            "删除后将自动切换至其他已保存账号。"
        } else if (state.accounts.size <= 1) {
            "删除后将返回登录页面。"
        } else {
            ""
        }
        ConfirmDangerDialog(
            title = "删除账号",
            message = "确定要从本机移除账号「${target.displayName} (${target.username}@${target.hostLabel})」吗？本地登录凭证与缓存将被清除，不会影响云端数据。$extraHint",
            confirmLabel = "删除账号",
            dismissLabel = "返回",
            onConfirm = {
                pendingDeleteAccount = null
                onDeleteAccount(target)
            },
            onDismiss = { pendingDeleteAccount = null }
        )
    }
}

@Composable
private fun ManageAccountRow(
    account: KodAccount,
    isActive: Boolean,
    imageLoader: ImageLoader,
    onRowClick: () -> Unit,
    onSwitchClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val (avatarBg, avatarFg) = remember(account.id, isActive) {
        resolveAccountAvatarColors(account.id, isActive)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)
            .testTag("manage_account_row_${account.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp)) {
            KodUserAvatar(
                avatarUrl = account.avatarUrl,
                displayName = account.displayName,
                imageLoader = imageLoader,
                size = 48.dp,
                containerColor = avatarBg,
                contentColor = avatarFg
            )
            if (isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "当前使用账号",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${account.username}@${account.hostLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("manage_account_menu_${account.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "账号操作菜单",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                if (!isActive) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "切换到账号",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSwitchClick()
                        },
                        modifier = Modifier.testTag("manage_account_switch_${account.id}")
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "删除账号",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDeleteClick()
                    },
                    modifier = Modifier.testTag("manage_account_delete_${account.id}")
                )
            }
        }
    }
}

private fun resolveAccountAvatarColors(accountId: String, isActive: Boolean): Pair<Color, Color> {
    if (isActive) {
        return Color(0xFFF3ECE6) to Color(0xFFB58055)
    }
    val palette = listOf(
        Color(0xFFC9915A) to Color.White,
        Color(0xFF3B82A6) to Color.White,
        Color(0xFF4D9078) to Color.White,
        Color(0xFF6B72B8) to Color.White,
        Color(0xFFB86B77) to Color.White
    )
    val index = abs(accountId.hashCode()) % palette.size
    return palette[index]
}
