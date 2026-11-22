package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncAlt
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
import com.example.model.SyncDirection
import com.example.model.SyncFrequency
import com.example.model.SyncRule
import com.example.model.SyncRunStatus
import com.example.ui.components.EmptyFolderState
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
        wifiOnly: Boolean
    ) -> Unit,
    onToggleEnabled: (SyncRule, Boolean) -> Unit,
    onChangeFrequency: (SyncRule, SyncFrequency) -> Unit,
    onRunNow: (SyncRule) -> Unit,
    onRunAllNow: () -> Unit,
    onDeleteRule: (SyncRule) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCreateSheet by remember { mutableStateOf(false) }

    // Draft state for creating a new sync rule
    var draftName by remember { mutableStateOf("") }
    var draftLocalUri by remember { mutableStateOf("") }
    var draftLocalLabel by remember { mutableStateOf("") }
    var draftDirection by remember { mutableStateOf(SyncDirection.TWO_WAY) }
    var draftFrequency by remember { mutableStateOf(SyncFrequency.HOURLY) }
    var draftWifiOnly by remember { mutableStateOf(true) }

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
        }

        if (state.syncRules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyFolderState(
                    title = "还没有配置同步文件夹",
                    subtitle = "选择手机本地文件夹与 KodBox 云盘目录配对，即可自动完成双向增量同步",
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
                        onRunNow = { onRunNow(rule) },
                        onDelete = { onDeleteRule(rule) }
                    )
                }
            }
        }
    }

    if (showCreateSheet) {
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
                            Text("KodBox 云盘目标文件夹", style = MaterialTheme.typography.labelLarge)
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
                            draftWifiOnly
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncRuleCard(
    rule: SyncRule,
    isSyncing: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onChangeFrequency: (SyncFrequency) -> Unit,
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
                        text = "${rule.direction.label} · ${if (rule.wifiOnly) "仅 Wi-Fi" else "任意网络"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
