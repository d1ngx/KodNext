package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.data.KodFormatters
import com.example.ui.components.ConfirmDangerDialog
import com.example.ui.components.KodUserAvatar
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.KodUiState

/**
 * Compact, single-screen Account Center matching clean modern mobile cloud layout:
 * - Grouped sections: 账号设置, 账号绑定与资料, 安全与活动记录 (Devices & Logs open in a dedicated bottom sheet!)
 * - Prominent red 「退出账号」 card immediately reachable without scrolling past long log lists.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountCenterScreen(
    state: KodUiState,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWebAccount: () -> Unit,
    onSaveProfile: (nickName: String, email: String, phone: String, sex: String) -> Unit,
    onChangePassword: (oldPwd: String, newPwd: String) -> Unit,
    onLogout: () -> Unit
) {
    BackHandler(onBack = onBack)
    val account = state.activeAccount ?: return

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showActivitySheet by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var activeActivityTab by remember { mutableIntStateOf(0) } // 0 = Devices, 1 = Logs

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text("账号中心") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("account_center_back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新账号信息")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section 1: 账号设置 (Avatar, Nickname, Account/Role, Change Password)
            AccountSectionLabel("账号设置")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Avatar row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenWebAccount)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("account_row_avatar"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "头像",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        KodUserAvatar(
                            avatarUrl = account.avatarUrl,
                            displayName = account.displayName,
                            imageLoader = imageLoader,
                            size = 42.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    AccountRowDivider()
                    AccountInfoRow(
                        title = "昵称",
                        value = account.displayName,
                        onClick = { showEditProfileDialog = true },
                        tag = "account_row_nickname"
                    )
                    AccountRowDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("账号与身份", style = MaterialTheme.typography.bodyLarge)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "@${account.username}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (account.isAdmin) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (account.isAdmin) {
                                        Icon(
                                            Icons.Filled.Shield,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                    Text(
                                        text = account.roleLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (account.isAdmin) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                    AccountRowDivider()
                    AccountInfoRow(
                        title = "修改密码",
                        value = "点击修改",
                        onClick = { showPasswordDialog = true },
                        tag = "account_row_password"
                    )
                }
            }

            // Section 2: 账号绑定与资料
            AccountSectionLabel("账号绑定与资料")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AccountInfoRow(
                        title = "电子邮箱",
                        value = account.email.ifBlank { "未绑定" },
                        onClick = { showEditProfileDialog = true },
                        tag = "account_row_email"
                    )
                    AccountRowDivider()
                    AccountInfoRow(
                        title = "手机号码",
                        value = account.phone.ifBlank { "未绑定" },
                        onClick = { showEditProfileDialog = true },
                        tag = "account_row_phone"
                    )
                    AccountRowDivider()
                    AccountInfoRow(
                        title = "性别",
                        value = if (account.sex == "0") "女" else "男",
                        onClick = { showEditProfileDialog = true },
                        tag = "account_row_sex"
                    )
                    if (account.departments.isNotEmpty()) {
                        AccountRowDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("所属部门", style = MaterialTheme.typography.bodyLarge)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                account.departments.take(6).forEach { dept ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow
                                    ) {
                                        Text(
                                            text = dept,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: 安全与记录 (Compact rows — long logs open in dedicated sheet!)
            AccountSectionLabel("安全与活动")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val activitySummary = when {
                        state.isLoadingAccountCenter -> "同步中…"
                        else -> "${state.userDevices.size} 台设备 · ${state.userLogs.size} 条日志"
                    }
                    AccountInfoRow(
                        title = "登录设备与操作日志",
                        value = activitySummary,
                        onClick = { showActivitySheet = true },
                        tag = "account_row_activity_logs"
                    )
                    AccountRowDivider()
                    AccountInfoRow(
                        title = "网页版个人中心",
                        value = "第三方绑定 / 头像",
                        onClick = onOpenWebAccount,
                        tag = "account_row_web_profile"
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Prominent Sign Out card — immediately accessible without endless scrolling
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { showLogoutConfirm = true }
                    .testTag("account_center_logout_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "退出账号",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(
                text = "当前站点：${account.serverUrl}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
    }

    // Dedicated ModalBottomSheet for Login Devices & Activity Logs (keeps main Account Center crisp!)
    if (showActivitySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showActivitySheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.76f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "登录设备与操作日志",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新记录")
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        "登录设备 (${state.userDevices.size})",
                        "个人操作日志 (${state.userLogs.size})"
                    ).forEachIndexed { idx, label ->
                        SegmentedButton(
                            selected = activeActivityTab == idx,
                            onClick = { activeActivityTab = idx },
                            shape = SegmentedButtonDefaults.itemShape(idx, 2)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }

                if (state.isLoadingAccountCenter) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    }
                } else if (activeActivityTab == 0) {
                    if (state.userDevices.isEmpty()) {
                        Text(
                            text = "暂无近期登录设备记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 18.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            itemsIndexed(state.userDevices) { idx, device ->
                                if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                val (deviceIcon, deviceLabel) = formatDeviceDisplay(device.userAgent)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = deviceIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = deviceLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${device.ip} ${device.address}".trim() +
                                                if (device.createTime > 0L) " · ${KodFormatters.formatTimestamp(device.createTime)}" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (state.userLogs.isEmpty()) {
                        Text(
                            text = "暂无个人操作日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 18.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            itemsIndexed(state.userLogs) { idx, log ->
                                if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = log.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = buildString {
                                                if (log.detail.isNotBlank()) append(log.detail).append(" · ")
                                                append(KodFormatters.formatTimestamp(log.createTime))
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditProfileDialog) {
        var nickName by remember(account.id, account.nickName) { mutableStateOf(account.nickName) }
        var email by remember(account.id, account.email) { mutableStateOf(account.email) }
        var phone by remember(account.id, account.phone) { mutableStateOf(account.phone) }
        var sex by remember(account.id, account.sex) { mutableStateOf(account.sex.ifBlank { "1" }) }

        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("编辑个人资料") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nickName,
                        onValueChange = { nickName = it },
                        label = { Text("显示昵称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("电子邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("手机号码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("性别", style = MaterialTheme.typography.bodyMedium)
                        SingleChoiceSegmentedButtonRow {
                            listOf("1" to "男", "0" to "女").forEachIndexed { idx, (code, label) ->
                                SegmentedButton(
                                    selected = sex == code,
                                    onClick = { sex = code },
                                    shape = SegmentedButtonDefaults.itemShape(idx, 2)
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEditProfileDialog = false
                        onSaveProfile(nickName, email, phone, sex)
                    },
                    enabled = !state.isWorking,
                    modifier = Modifier.testTag("save_profile_button")
                ) {
                    Text("保存资料")
                }
            }
        )
    }

    if (showLogoutConfirm) {
        ConfirmDangerDialog(
            title = "退出当前账号",
            message = "确定退出「${account.displayName} (${account.hostLabel})」吗？退出后仅清除本机登录会话，不会影响云端文件。",
            confirmLabel = "退出账号",
            onConfirm = {
                showLogoutConfirm = false
                onBack()
                onLogout()
            },
            onDismiss = { showLogoutConfirm = false }
        )
    }

    if (showPasswordDialog) {
        var oldPwd by remember { mutableStateOf("") }
        var newPwd by remember { mutableStateOf("") }
        var confirmPwd by remember { mutableStateOf("") }
        var localErr by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("修改登录密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = oldPwd,
                        onValueChange = { oldPwd = it; localErr = null },
                        label = { Text("当前密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPwd,
                        onValueChange = { newPwd = it; localErr = null },
                        label = { Text("新密码（至少 6 位）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPwd,
                        onValueChange = { confirmPwd = it; localErr = null },
                        label = { Text("确认新密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    localErr?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            oldPwd.isBlank() -> localErr = "请输入当前密码"
                            newPwd.length < 6 -> localErr = "新密码至少需要 6 位"
                            newPwd != confirmPwd -> localErr = "两次输入的新密码不一致"
                            else -> {
                                showPasswordDialog = false
                                onChangePassword(oldPwd, newPwd)
                            }
                        }
                    }
                ) {
                    Text("确认修改")
                }
            }
        )
    }
}

@Composable
private fun AccountSectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp)
    )
}

@Composable
private fun AccountRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun AccountInfoRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun formatDeviceDisplay(rawUa: String): Pair<ImageVector, String> {
    val ua = rawUa.trim()
    val lower = ua.lowercase()
    return when {
        ua.isBlank() -> Icons.Filled.Devices to "未知设备"
        lower.contains("kodcloud-system=android") -> {
            val ver = Regex("""AppVersion=([0-9.]+)""", RegexOption.IGNORE_CASE).find(ua)?.groupValues?.get(1)
            val label = if (!ver.isNullOrBlank()) "Android 客户端 (v$ver)" else "Android 客户端"
            Icons.Filled.PhoneAndroid to label
        }
        lower.contains("kodnext") -> {
            val ver = Regex("""KodNext/([0-9.]+)""", RegexOption.IGNORE_CASE).find(ua)?.groupValues?.get(1)
            val model = Regex("""Android [^;]+;\s*([^)]+)\)""", RegexOption.IGNORE_CASE).find(ua)?.groupValues?.get(1)?.trim()
            val label = buildString {
                append("Android 客户端")
                if (!model.isNullOrBlank() && !model.equals("Android Device", true)) append(" · $model")
                if (!ver.isNullOrBlank()) append(" (v$ver)")
            }
            Icons.Filled.PhoneAndroid to label
        }
        lower.contains("android") -> Icons.Filled.PhoneAndroid to "Android 设备 ($ua)"
        lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios") -> Icons.Filled.PhoneAndroid to "iOS / iPadOS 设备"
        lower.contains("windows") -> Icons.Filled.Laptop to "Windows 浏览器 / 客户端"
        lower.contains("macintosh") || lower.contains("mac os") -> Icons.Filled.Laptop to "macOS 浏览器 / 客户端"
        lower.contains("linux") -> Icons.Filled.Laptop to "Linux 设备"
        lower.contains("okhttp") -> Icons.Filled.PhoneAndroid to "Android 客户端 (历史会话)"
        else -> Icons.Filled.Language to ua
    }
}
