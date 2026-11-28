package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CorporateFare
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.KodFormatters
import com.example.model.KodCollabAssignment
import com.example.model.KodCollabAuthRole
import com.example.model.KodCollabTarget
import com.example.model.KodCollabTargetType
import com.example.model.KodExistingShareDetail
import com.example.model.KodShareItem
import com.example.model.ShareAccessMode
import com.example.model.ShareCancelMode
import com.example.model.ShareDialogTab
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KodShareConfigDialog(
    titleName: String,
    isFolder: Boolean,
    initialTab: ShareDialogTab = ShareDialogTab.EXTERNAL_LINK,
    allowInternalCollab: Boolean = true,
    initialPassword: String = "",
    initialExpireEpochSeconds: Long = 0L,
    initialAccessMode: ShareAccessMode = ShareAccessMode.READ_ONLY_DOWNLOAD,
    isEditingExisting: Boolean = false,
    isItemAlreadyShared: Boolean = false,
    shareOwnerUserId: String = "",
    shareOwnerName: String = "",
    currentUserId: String = "",
    existingDetail: KodExistingShareDetail? = null,
    isLoadingDetail: Boolean = false,
    authRoles: List<KodCollabAuthRole> = KodCollabAuthRole.DEFAULT_ROLES,
    collabCandidates: List<KodCollabTarget> = emptyList(),
    isSearchingCollabTargets: Boolean = false,
    collabSearchError: String? = null,
    createdShare: KodShareItem?,
    isWorking: Boolean,
    onSelectTab: ((ShareDialogTab) -> Unit)? = null,
    onSearchCollabTargets: (String) -> Unit = {},
    onSaveInternalCollab: (List<KodCollabAssignment>) -> Unit = {},
    onSaveShare: (password: String, expireEpochSeconds: Long, accessMode: ShareAccessMode) -> Unit,
    onCancelShare: ((ShareCancelMode) -> Unit)? = null,
    onCopyUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    TrackModalOverlay()

    var activeTab by remember(initialTab, allowInternalCollab) {
        mutableStateOf(if (allowInternalCollab) initialTab else ShareDialogTab.EXTERNAL_LINK)
    }
    var userManuallySelectedTab by remember { mutableStateOf(false) }
    var inlineFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackTrigger by remember { mutableIntStateOf(0) }
    var pendingCancelMode by remember { mutableStateOf<ShareCancelMode?>(null) }

    LaunchedEffect(feedbackTrigger) {
        if (feedbackTrigger > 0 && inlineFeedbackMessage != null) {
            delay(2_800L)
            inlineFeedbackMessage = null
        }
    }

    fun copyWithInlineFeedback(payload: String) {
        onCopyUrl(payload)
        inlineFeedbackMessage = "分享链接已复制到剪贴板"
        feedbackTrigger += 1
    }

    // External link form state
    var passwordEnabled by remember { mutableStateOf(initialPassword.isNotBlank()) }
    var password by remember { mutableStateOf(initialPassword) }
    var expirePreset by remember {
        mutableIntStateOf(if (initialExpireEpochSeconds > 0L) -1 else 7)
    }
    var customExpireEpochSec by remember {
        mutableLongStateOf(
            if (initialExpireEpochSeconds > 0L) initialExpireEpochSeconds
            else (System.currentTimeMillis() / 1000L) + 7 * 86400L
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var accessMode by remember { mutableStateOf(initialAccessMode) }

    // Internal collaboration form state
    val effectiveRoles = remember(authRoles) {
        authRoles.ifEmpty { KodCollabAuthRole.DEFAULT_ROLES }
    }
    var defaultRole by remember(effectiveRoles) { mutableStateOf(effectiveRoles.first()) }
    var selectedAssignments by remember { mutableStateOf<List<KodCollabAssignment>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<KodCollabTargetType?>(null) }
    var showManualAdd by remember { mutableStateOf(false) }
    var manualTargetType by remember { mutableStateOf(KodCollabTargetType.USER) }
    var manualTargetId by remember { mutableStateOf("") }
    var hydratedDetailId by remember { mutableStateOf("") }

    // Hydrate existing share config from server once fetched
    LaunchedEffect(existingDetail?.shareId, existingDetail?.assignments?.size) {
        val detail = existingDetail ?: return@LaunchedEffect
        if (detail.shareId.isNotBlank() && detail.shareId != hydratedDetailId) {
            hydratedDetailId = detail.shareId
            if (!isEditingExisting && detail.isLink) {
                if (detail.password.isNotBlank()) {
                    passwordEnabled = true
                    password = detail.password
                }
                if (detail.timeTo > 0L) {
                    customExpireEpochSec = detail.timeTo
                    expirePreset = -1
                }
                accessMode = when {
                    detail.canUpload -> ShareAccessMode.READ_WRITE
                    detail.notDownload -> ShareAccessMode.READ_ONLY_PREVIEW
                    else -> ShareAccessMode.READ_ONLY_DOWNLOAD
                }
            }
            if (detail.assignments.isNotEmpty() && selectedAssignments.isEmpty()) {
                selectedAssignments = detail.assignments
            }
            if (!isEditingExisting &&
                allowInternalCollab &&
                !userManuallySelectedTab &&
                !detail.isLink &&
                detail.isShareTo &&
                activeTab != ShareDialogTab.INTERNAL_COLLAB
            ) {
                activeTab = ShareDialogTab.INTERNAL_COLLAB
                onSelectTab?.invoke(ShareDialogTab.INTERNAL_COLLAB)
            }
        }
    }

    val resolvedExpireEpochSec = when (expirePreset) {
        0 -> 0L
        -1 -> customExpireEpochSec
        else -> (System.currentTimeMillis() / 1000L) + expirePreset * 86400L
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = (customExpireEpochSec * 1000L).coerceAtLeast(System.currentTimeMillis() + 86_400_000L)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            customExpireEpochSec = (millis / 1000L) + 86399L
                            expirePreset = -1
                        }
                        showDatePicker = false
                    }
                ) { Text("确定日期") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    val effectiveOwnerId = existingDetail?.ownerUserId?.ifBlank { shareOwnerUserId } ?: shareOwnerUserId
    val effectiveOwnerName = existingDetail?.ownerName?.ifBlank { shareOwnerName } ?: shareOwnerName
    val isSharedByOtherUser = effectiveOwnerId.isNotBlank() &&
        currentUserId.isNotBlank() &&
        effectiveOwnerId != currentUserId

    val canCancelExternalLink = onCancelShare != null && (
        createdShare != null ||
            isEditingExisting ||
            existingDetail?.isLink == true ||
            (isItemAlreadyShared && !isLoadingDetail && existingDetail?.isShareTo != true)
        )
    val canCancelCollab = onCancelShare != null && existingDetail?.isShareTo == true

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 520.dp)
                .heightIn(max = 680.dp)
                .testTag("kod_share_config_dialog")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Fixed Dialog Header with explicit top-right Close (×) button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (activeTab == ShareDialogTab.INTERNAL_COLLAB) Icons.Filled.GroupAdd else Icons.Filled.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isEditingExisting -> "编辑外链分享权限"
                                allowInternalCollab -> "共享与协作"
                                else -> "网盘外链分享"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${if (isFolder) "文件夹" else "文件"} · $titleName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_share_dialog_icon")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭窗口",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                // Scrollable middle form content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedVisibility(visible = !inlineFeedbackMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("share_dialog_inline_feedback")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = inlineFeedbackMessage.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (allowInternalCollab && createdShare == null) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("share_mode_segmented_row")
                        ) {
                            ShareDialogTab.entries.forEachIndexed { idx, tab ->
                                SegmentedButton(
                                    selected = activeTab == tab,
                                    onClick = {
                                        userManuallySelectedTab = true
                                        activeTab = tab
                                        onSelectTab?.invoke(tab)
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(idx, ShareDialogTab.entries.size),
                                    icon = {},
                                    modifier = Modifier.testTag("share_dialog_tab_${tab.name.lowercase()}")
                                ) {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    if (isSharedByOtherUser && createdShare == null) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("share_owner_notice_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                val ownerLabel = effectiveOwnerName.ifBlank { "其他成员 (ID:$effectiveOwnerId)" }
                                Text(
                                    text = "当前共享由 $ownerLabel 创建，取消或修改需具备管理权限",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    if (activeTab == ShareDialogTab.EXTERNAL_LINK || !allowInternalCollab) {
                        if (createdShare != null) {
                            ShareReadyLinkCard(
                                share = createdShare,
                                isCopied = inlineFeedbackMessage != null,
                                onCopyUrl = ::copyWithInlineFeedback
                            )
                        } else {
                            if (isLoadingDetail) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        "正在读取现有分享状态…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            val activeExternalUrl = existingDetail?.shareUrl.orEmpty()
                            if (existingDetail?.isLink == true && activeExternalUrl.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Link,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "外链分享已开启",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Text(
                                                text = activeExternalUrl,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = JetBrainsMonoFamily,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val pwd = existingDetail.password
                                                val payload = if (pwd.isNotBlank()) {
                                                    "分享链接: $activeExternalUrl\n提取码: $pwd"
                                                } else {
                                                    activeExternalUrl
                                                }
                                                copyWithInlineFeedback(payload)
                                            },
                                            modifier = Modifier.testTag("copy_existing_share_button")
                                        ) {
                                            Icon(
                                                imageVector = if (inlineFeedbackMessage != null) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                                contentDescription = "复制现有外链",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                        // Password Protection section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("启用密码保护", style = MaterialTheme.typography.labelLarge)
                            Switch(
                                checked = passwordEnabled,
                                onCheckedChange = { enabled ->
                                    passwordEnabled = enabled
                                    if (enabled && password.isBlank()) {
                                        password = generateRandomShareCode()
                                    }
                                },
                                modifier = Modifier.testTag("share_password_switch")
                            )
                        }
                        if (passwordEnabled) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("提取密码") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { password = generateRandomShareCode() }) {
                                        Icon(Icons.Filled.Casino, contentDescription = "随机生成密码")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("share_password_input")
                            )
                        }

                        // Expiration Date section
                        Text("过期日期", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0 to "永久有效", 1 to "1 天", 7 to "7 天", 30 to "30 天").forEach { (days, label) ->
                                FilterChip(
                                    selected = expirePreset == days,
                                    onClick = { expirePreset = days },
                                    label = { Text(label) }
                                )
                            }
                            FilterChip(
                                selected = expirePreset == -1,
                                onClick = { showDatePicker = true },
                                leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = {
                                    Text(
                                        if (expirePreset == -1) "至 ${KodFormatters.formatDateOnly(customExpireEpochSec)}"
                                        else "自定义日期"
                                    )
                                },
                                modifier = Modifier.testTag("share_custom_date_chip")
                            )
                        }

                        // Read-Only vs Read-Write Access Permissions
                        Text("访问权限（只读 / 读写）", style = MaterialTheme.typography.labelLarge)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ShareAccessMode.entries.forEach { mode ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (accessMode == mode) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { accessMode = mode }
                                        .testTag("share_mode_${mode.name}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = accessMode == mode,
                                            onClick = { accessMode = mode }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(mode.label, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                mode.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Internal Collaboration Share Tab (内部协作分享)
                    if (isLoadingDetail) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "正在读取协作配置与成员列表…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (existingDetail?.isShareTo == true) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.GroupAdd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "已开启内部协作 (${existingDetail.assignments.size} 个对象)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Default permission role selector
                    Text("默认协作权限", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        effectiveRoles.take(6).forEach { role ->
                            FilterChip(
                                selected = defaultRole.authId == role.authId,
                                onClick = { defaultRole = role },
                                label = { Text(role.name, maxLines = 1) },
                                modifier = Modifier.testTag("collab_role_${role.authId}")
                            )
                        }
                    }

                    // Selected collaborators section
                    if (selectedAssignments.isNotEmpty()) {
                        Text(
                            text = "已选协作对象 (${selectedAssignments.size})",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedAssignments.forEach { assignment ->
                                SelectedCollabRow(
                                    assignment = assignment,
                                    roles = effectiveRoles,
                                    onChangeRole = { newRole ->
                                        selectedAssignments = selectedAssignments.map {
                                            if (it.target.uniqueKey == assignment.target.uniqueKey) {
                                                it.copy(authId = newRole.authId, authName = newRole.name)
                                            } else it
                                        }
                                    },
                                    onRemove = {
                                        selectedAssignments = selectedAssignments.filterNot {
                                            it.target.uniqueKey == assignment.target.uniqueKey
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider()
                    }

                    // Search bar for KodBox users & departments
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索同事姓名、账号或部门") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("collab_search_input")
                        )
                        IconButton(
                            onClick = { onSearchCollabTargets(searchQuery) },
                            modifier = Modifier.testTag("collab_search_button")
                        ) {
                            if (isSearchingCollabTargets) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Search, contentDescription = "搜索协作成员")
                            }
                        }
                    }

                    // Filter chips: 全部 / 成员 / 部门
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = typeFilter == null,
                            onClick = { typeFilter = null },
                            label = { Text("全部") }
                        )
                        FilterChip(
                            selected = typeFilter == KodCollabTargetType.USER,
                            onClick = { typeFilter = KodCollabTargetType.USER },
                            label = { Text("成员") }
                        )
                        FilterChip(
                            selected = typeFilter == KodCollabTargetType.GROUP,
                            onClick = { typeFilter = KodCollabTargetType.GROUP },
                            label = { Text("部门") }
                        )
                    }

                    val filteredCandidates = remember(collabCandidates, typeFilter, searchQuery) {
                        collabCandidates.filter { candidate ->
                            val matchesType = typeFilter == null || candidate.targetType == typeFilter
                            val q = searchQuery.trim()
                            val matchesQuery = q.isBlank() ||
                                candidate.name.contains(q, ignoreCase = true) ||
                                candidate.subtitle.contains(q, ignoreCase = true) ||
                                candidate.targetId.equals(q, ignoreCase = true)
                            matchesType && matchesQuery
                        }
                    }

                    if (filteredCandidates.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            filteredCandidates.take(12).forEach { target ->
                                val alreadyAdded = selectedAssignments.any { it.target.uniqueKey == target.uniqueKey }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (alreadyAdded) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (alreadyAdded) {
                                                selectedAssignments = selectedAssignments.filterNot {
                                                    it.target.uniqueKey == target.uniqueKey
                                                }
                                            } else {
                                                selectedAssignments = selectedAssignments + KodCollabAssignment(
                                                    target = target,
                                                    authId = defaultRole.authId,
                                                    authName = defaultRole.name
                                                )
                                            }
                                        }
                                        .testTag("collab_candidate_${target.uniqueKey}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (target.targetType == KodCollabTargetType.GROUP) {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (target.targetType == KodCollabTargetType.GROUP) {
                                                    Icons.Filled.CorporateFare
                                                } else {
                                                    Icons.Filled.Person
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = target.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${target.targetType.label} · ${target.subtitle.ifBlank { "ID: ${target.targetId}" }}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = if (alreadyAdded) Icons.Filled.Check else Icons.Filled.Add,
                                            contentDescription = if (alreadyAdded) "已添加" else "添加协作成员",
                                            tint = if (alreadyAdded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!isSearchingCollabTargets && !isLoadingDetail) {
                        Text(
                            text = collabSearchError ?: "未搜索到匹配的成员或部门，可输入关键词重新搜索或按 ID 手动添加",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Optional manual ID quick-add for servers restricting non-admin member listings
                    TextButton(
                        onClick = { showManualAdd = !showManualAdd },
                        modifier = Modifier.testTag("toggle_manual_collab_add")
                    ) {
                        Text(if (showManualAdd) "收起按 ID 添加" else "按用户/部门 ID 手动添加")
                    }
                    if (showManualAdd) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = manualTargetType == KodCollabTargetType.USER,
                                    onClick = { manualTargetType = KodCollabTargetType.USER },
                                    label = { Text("用户 ID") }
                                )
                                FilterChip(
                                    selected = manualTargetType == KodCollabTargetType.GROUP,
                                    onClick = { manualTargetType = KodCollabTargetType.GROUP },
                                    label = { Text("部门 ID") }
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = manualTargetId,
                                    onValueChange = { manualTargetId = it },
                                    placeholder = { Text("输入数字 ID") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    enabled = manualTargetId.isNotBlank(),
                                    onClick = {
                                        val cleanId = manualTargetId.trim()
                                        val target = KodCollabTarget(
                                            targetType = manualTargetType,
                                            targetId = cleanId,
                                            name = "${manualTargetType.label} #$cleanId",
                                            subtitle = "ID: $cleanId"
                                        )
                                        if (selectedAssignments.none { it.target.uniqueKey == target.uniqueKey }) {
                                            selectedAssignments = selectedAssignments + KodCollabAssignment(
                                                target = target,
                                                authId = defaultRole.authId,
                                                authName = defaultRole.name
                                            )
                                        }
                                        manualTargetId = ""
                                    }
                                ) {
                                    Text("添加")
                                }
                            }
                        }
                    }
                    }
                }

                // Pinned Floating Bottom Action Bar (always visible without scrolling)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_dialog_floating_bottom_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeTab == ShareDialogTab.INTERNAL_COLLAB && allowInternalCollab) {
                            if (canCancelCollab) {
                                OutlinedButton(
                                    enabled = !isWorking,
                                    onClick = { pendingCancelMode = ShareCancelMode.INTERNAL_COLLAB_ONLY },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("cancel_collab_share_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LinkOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("取消协作", maxLines = 1, softWrap = false)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .weight(0.85f)
                                        .testTag("close_share_dialog_button")
                                ) {
                                    Text("关闭", maxLines = 1, softWrap = false)
                                }
                            }
                            Button(
                                enabled = !isWorking && selectedAssignments.isNotEmpty(),
                                onClick = { onSaveInternalCollab(selectedAssignments) },
                                modifier = Modifier
                                    .weight(1.15f)
                                    .testTag("confirm_save_collab_button")
                            ) {
                                if (isWorking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text("保存协作 (${selectedAssignments.size})", maxLines = 1, softWrap = false)
                                }
                            }
                        } else if (createdShare != null) {
                            if (canCancelExternalLink) {
                                OutlinedButton(
                                    enabled = !isWorking,
                                    onClick = { pendingCancelMode = ShareCancelMode.EXTERNAL_LINK_ONLY },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("cancel_created_share_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LinkOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("取消外链", maxLines = 1, softWrap = false)
                                }
                            }
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("close_share_dialog_button")
                            ) {
                                Text("完成", maxLines = 1, softWrap = false)
                            }
                        } else {
                            if (canCancelExternalLink) {
                                OutlinedButton(
                                    enabled = !isWorking,
                                    onClick = { pendingCancelMode = ShareCancelMode.EXTERNAL_LINK_ONLY },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("cancel_external_share_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LinkOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("取消外链", maxLines = 1, softWrap = false)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .weight(0.85f)
                                        .testTag("close_share_dialog_button")
                                ) {
                                    Text("关闭", maxLines = 1, softWrap = false)
                                }
                            }
                            Button(
                                enabled = !isWorking,
                                onClick = {
                                    val finalPassword = if (passwordEnabled) password.trim() else ""
                                    onSaveShare(finalPassword, resolvedExpireEpochSec, accessMode)
                                },
                                modifier = Modifier
                                    .weight(1.15f)
                                    .testTag("confirm_create_share_button")
                            ) {
                                if (isWorking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text(
                                        text = when {
                                            isEditingExisting -> "保存修改"
                                            existingDetail?.isLink == true -> "更新外链"
                                            else -> "生成外链"
                                        },
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingCancelMode?.let { mode ->
        val isCollabCancel = mode == ShareCancelMode.INTERNAL_COLLAB_ONLY
        ConfirmDangerDialog(
            title = if (isCollabCancel) "确认取消内部协作？" else "确认取消外链分享？",
            message = if (isCollabCancel) {
                "取消后，已添加的协作成员和部门将无法继续访问「$titleName」。"
            } else {
                "取消后，「$titleName」的外链将立即失效，外部访问者将无法打开。"
            },
            confirmLabel = if (isCollabCancel) "确认取消协作" else "确认取消外链",
            dismissLabel = "返回",
            onConfirm = {
                pendingCancelMode = null
                onCancelShare?.invoke(mode)
            },
            onDismiss = { pendingCancelMode = null }
        )
    }
}

@Composable
private fun ShareReadyLinkCard(
    share: KodShareItem,
    isCopied: Boolean,
    onCopyUrl: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("外链已就绪", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = share.shareUrl,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily
            )
            if (share.password.isNotBlank()) {
                Text("提取密码：${share.password}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "有效期：${KodFormatters.formatDateOnly(share.timeTo)} · 权限：${
                    when {
                        share.canUpload -> "读写协作"
                        share.notDownload -> "仅预览"
                        else -> "只读（可下载）"
                    }
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    val copyPayload = if (share.password.isNotBlank()) {
                        "分享链接: ${share.shareUrl}\n提取码: ${share.password}"
                    } else {
                        share.shareUrl
                    }
                    onCopyUrl(copyPayload)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("copy_created_share_button")
            ) {
                Icon(
                    imageVector = if (isCopied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isCopied) "已复制分享链接" else "复制分享链接")
            }
        }
    }
}

@Composable
private fun SelectedCollabRow(
    assignment: KodCollabAssignment,
    roles: List<KodCollabAuthRole>,
    onChangeRole: (KodCollabAuthRole) -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (assignment.target.targetType == KodCollabTargetType.GROUP) {
                    Icons.Filled.CorporateFare
                } else {
                    Icons.Filled.Person
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = assignment.target.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { menuExpanded = true }
                ) {
                    Text(
                        text = assignment.authName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    roles.forEach { role ->
                        DropdownMenuItem(
                            text = { Text(role.name) },
                            onClick = {
                                onChangeRole(role)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "移除协作对象",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun generateRandomShareCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..4).map { chars.random() }.joinToString("")
}
