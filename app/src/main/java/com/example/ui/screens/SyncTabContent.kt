package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.data.KodFormatters
import com.example.model.SyncConflictPolicy
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncRunStatus
import com.example.ui.components.EmptyFolderState
import com.example.ui.components.TrackModalOverlay
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusSuccess
import com.example.viewmodel.KodUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SyncTabContent(
    state: KodUiState,
    onOpenRemoteFolderPicker: () -> Unit,
    onClearPickedRemote: () -> Unit,
    onCreateSyncRule: (
        name: String,
        localTreeUri: String,
        localFolderLabel: String,
        remotePath: String,
        remoteFolderLabel: String,
        direction: SyncDirection,
        frequency: SyncFrequency,
        wifiOnly: Boolean,
        conflictPolicy: SyncConflictPolicy,
        ignoreHiddenFiles: Boolean
    ) -> Unit,
    onToggleEnabled: (SyncRule, Boolean) -> Unit,
    onChangeFrequency: (SyncRule, SyncFrequency) -> Unit,
    onChangeConflictPolicy: (SyncRule, SyncConflictPolicy) -> Unit = { _, _ -> },
    onRunNow: (SyncRule) -> Unit,
    onRunAllNow: () -> Unit,
    onDeleteRule: (SyncRule) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCreateSheet by remember { mutableStateOf(false) }
    var showBatteryGuideSheet by remember { mutableStateOf(false) }

    // Draft state for creating a new sync rule
    var draftName by remember { mutableStateOf("") }
    var draftLocalUri by remember { mutableStateOf("") }
    var draftLocalLabel by remember { mutableStateOf("") }
    var draftDirection by remember { mutableStateOf(SyncDirection.TWO_WAY) }
    var draftFrequency by remember { mutableStateOf(SyncFrequency.HOURLY) }
    var draftWifiOnly by remember { mutableStateOf(true) }
    var draftConflictPolicy by remember { mutableStateOf(SyncConflictPolicy.KEEP_BOTH_CONFLICT_COPY) }
    var draftIgnoreHidden by remember { mutableStateOf(true) }

    // Zero-permission SAF folder picker
    val openLocalFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            draftLocalUri = treeUri.toString()
            draftLocalLabel = doc?.name?.ifBlank { treeUri.lastPathSegment ?: "本地文件夹" } ?: "本地文件夹"
        }
    }

    val remoteSelection = state.pickedSyncRemote
    val effectiveRemotePath = remoteSelection?.remotePath
        ?: state.activeAccount?.personalRootPath.orEmpty()
    val effectiveRemoteLabel = remoteSelection?.remoteLabel ?: "个人空间"

    Column(modifier = modifier.fillMaxSize()) {
        // Top Sync Dashboard Summary Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SyncAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "双向文件夹同步",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "已配置 ${state.syncRules.size} 组目录 · 支持实时、每小时、每天自动同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { showCreateSheet = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_sync_rule_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新建同步目录")
                }
                OutlinedButton(
                    onClick = onRunAllNow,
                    enabled = state.syncRules.any { it.enabled },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("sync_all_now_button")
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("全部同步")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "三路快照增量比对 · 支持冲突副本防覆盖",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { showBatteryGuideSheet = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("sync_battery_guide_button")
                ) {
                    Icon(Icons.Filled.BatterySaver, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("后台保活指引", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (state.syncRules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyFolderState(
                    title = "还没有配置同步文件夹",
                    subtitle = "选择手机本地文件夹与网盘目录配对，即可自动完成双向增量同步",
                    actionLabel = "添加第一组同步目录",
                    onAction = { showCreateSheet = true }
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.syncRules, key = { it.id }) { rule ->
                    val isSyncing = rule.id in state.activeSyncingRuleIds || rule.lastStatus == SyncRunStatus.SYNCING
                    SyncRuleCard(
                        rule = rule,
                        isSyncing = isSyncing,
                        onToggleEnabled = { onToggleEnabled(rule, it) },
                        onChangeFrequency = { onChangeFrequency(rule, it) },
                        onChangeConflictPolicy = { onChangeConflictPolicy(rule, it) },
                        onRunNow = { onRunNow(rule) },
                        onDelete = { onDeleteRule(rule) }
                    )
                }
            }
        }
    }

    if (showCreateSheet) {
        TrackModalOverlay()
        ModalBottomSheet(
            onDismissRequest = {
                showCreateSheet = false
                onClearPickedRemote()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("新建双向文件夹同步", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("任务名称（可选）") },
                    placeholder = { Text("例如：工作文档双向同步") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sync_rule_name_input")
                )

                // Step 1: Local folder via Android SAF
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("本地设备文件夹", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = draftLocalLabel.ifBlank { "尚未选择本地目录" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalButton(
                            onClick = { openLocalFolderLauncher.launch(null) },
                            modifier = Modifier.testTag("pick_local_sync_folder_button")
                        ) {
                            Text(if (draftLocalUri.isBlank()) "选择目录" else "更改")
                        }
                    }
                }

                // Step 2: Remote KodBox target folder
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("网盘目标文件夹", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = effectiveRemoteLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalButton(
                            onClick = onOpenRemoteFolderPicker,
                            modifier = Modifier.testTag("pick_remote_sync_folder_button")
                        ) {
                            Text("浏览云盘")
                        }
                    }
                }

                // Step 3: Direction
                Text("同步方向", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncDirection.entries.forEach { dir ->
                        FilterChip(
                            selected = draftDirection == dir,
                            onClick = { draftDirection = dir },
                            label = { Text(dir.label) },
                            modifier = Modifier.testTag("sync_dir_${dir.name}")
                        )
                    }
                }

                // Step 4: Sync Frequency (Realtime / Hourly / Daily / Manual)
                Text("同步频率", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SyncFrequency.entries.forEach { freq ->
                        FilterChip(
                            selected = draftFrequency == freq,
                            onClick = { draftFrequency = freq },
                            label = { Text(freq.label) },
                            modifier = Modifier.testTag("sync_freq_${freq.name}")
                        )
                    }
                }

                // Step 5: Conflict Resolution Strategy (3-way snapshot)
                Text("冲突处理策略（双端同时修改时）", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SyncConflictPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = draftConflictPolicy == policy,
                            onClick = { draftConflictPolicy = policy },
                            label = { Text(policy.label) },
                            modifier = Modifier.testTag("sync_conflict_${policy.name}")
                        )
                    }
                }
                Text(
                    text = draftConflictPolicy.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("仅在 Wi-Fi 网络下自动同步", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = draftWifiOnly, onCheckedChange = { draftWifiOnly = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("自动忽略以 . 开头的隐藏与临时文件", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = draftIgnoreHidden, onCheckedChange = { draftIgnoreHidden = it })
                }

                Button(
                    enabled = draftLocalUri.isNotBlank() && effectiveRemotePath.isNotBlank(),
                    onClick = {
                        onCreateSyncRule(
                            draftName.trim(),
                            draftLocalUri,
                            draftLocalLabel,
                            effectiveRemotePath,
                            effectiveRemoteLabel,
                            draftDirection,
                            draftFrequency,
                            draftWifiOnly,
                            draftConflictPolicy,
                            draftIgnoreHidden
                        )
                        draftName = ""
                        draftLocalUri = ""
                        draftLocalLabel = ""
                        showCreateSheet = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("confirm_create_sync_rule"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("保存并立即执行首次同步")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (showBatteryGuideSheet) {
        TrackModalOverlay()
        val romGuide = remember { resolveRomBackgroundGuide() }
        ModalBottomSheet(onDismissRequest = { showBatteryGuideSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatterySaver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text("后台同步防杀与省电指引", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "检测到当前系统：${romGuide.vendorLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        romGuide.steps.forEachIndexed { idx, step ->
                            Text(
                                text = "${idx + 1}. $step",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text(
                    text = "提示：为符合 Android 15 后台规范，小文件传输与定时同步默认采用低功耗 WorkManager 调度，不占用前台服务配额；将本应用设为“无限制”即可保障夜间及息屏定时同步稳定触发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("应用详情设置")
                    }
                    Button(
                        onClick = {
                            val opened = runCatching {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                true
                            }.getOrDefault(false)
                            if (!opened) {
                                runCatching {
                                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallback)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("电池优化设置")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

private data class RomBackgroundGuide(
    val vendorLabel: String,
    val steps: List<String>
)

private fun resolveRomBackgroundGuide(): RomBackgroundGuide {
    val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
    return when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> RomBackgroundGuide(
            vendorLabel = "小米 / Redmi (HyperOS / MIUI)",
            steps = listOf(
                "点击下方「应用详情设置」-> 省电策略 -> 选择「无限制」",
                "在同一页面开启「自启动」开关（如设备提供）",
                "在多任务卡片页长按本应用并点击锁头图标，避免一键清理中断同步"
            )
        )
        manufacturer.contains("oneplus") || manufacturer.contains("oppo") || manufacturer.contains("realme") -> RomBackgroundGuide(
            vendorLabel = "一加 / OPPO / realme (ColorOS)",
            steps = listOf(
                "点击「应用详情设置」-> 耗电管理 -> 开启「允许后台运行」与「允许完全后台行为」",
                "如有「允许自启动」选项，请同步开启",
                "进入「电池优化设置」将本应用设为「不优化」"
            )
        )
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> RomBackgroundGuide(
            vendorLabel = "华为 / 荣耀 (HarmonyOS / MagicOS)",
            steps = listOf(
                "点击「应用详情设置」-> 耗电详情 / 应用启动管理",
                "关闭「自动管理」，改为手动并勾选「允许自启动」与「允许后台活动」",
                "在「电池优化设置」中选择「不允许」电池优化"
            )
        )
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> RomBackgroundGuide(
            vendorLabel = "vivo / iQOO (OriginOS)",
            steps = listOf(
                "点击「应用详情设置」-> 耗电 -> 后台耗电管理 -> 选择「允许后台高耗电」",
                "在 i 管家 -> 应用管理 -> 权限管理中允许自启动"
            )
        )
        manufacturer.contains("samsung") -> RomBackgroundGuide(
            vendorLabel = "三星 (One UI)",
            steps = listOf(
                "点击「应用详情设置」-> 电池 -> 选择「不受限制」",
                "在设置 -> 电池 -> 后台使用限制中，将本应用加入「永不休眠的应用程序」"
            )
        )
        else -> RomBackgroundGuide(
            vendorLabel = "标准 Android (${Build.MANUFACTURER.ifBlank { "Android" }})",
            steps = listOf(
                "点击下方「电池优化设置」，找到本应用并切换为「不优化 / 无限制」",
                "确保未在系统设置中限制本应用的后台网络访问"
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncRuleCard(
    rule: SyncRule,
    isSyncing: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onChangeFrequency: (SyncFrequency) -> Unit,
    onChangeConflictPolicy: (SyncConflictPolicy) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_rule_card_${rule.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${rule.direction.label} · ${if (rule.wifiOnly) "仅 Wi-Fi" else "任意网络"} · ${rule.conflictPolicy.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            // Local <-> Remote mapping box
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(
                            text = "本地：${rule.localFolderLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        Text(
                            text = "云盘：${rule.remoteFolderLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Quick frequency switcher chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SyncFrequency.entries.forEach { freq ->
                    FilterChip(
                        selected = rule.frequency == freq,
                        onClick = { onChangeFrequency(freq) },
                        label = { Text(freq.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            // Status & actions footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    val (statusIcon, tint) = when (rule.lastStatus) {
                        SyncRunStatus.SUCCESS -> Icons.Filled.CheckCircle to StatusSuccess
                        SyncRunStatus.ERROR -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
                        else -> Icons.Filled.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(statusIcon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSyncing) "正在比对并同步文件…" else rule.lastSummary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (rule.lastSyncTime > 0L) {
                        Text(
                            text = "上次同步：${KodFormatters.formatTimestamp(rule.lastSyncTime)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onRunNow,
                    enabled = !isSyncing,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("同步")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "删除同步任务",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
