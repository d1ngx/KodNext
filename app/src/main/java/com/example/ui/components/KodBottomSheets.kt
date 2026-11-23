package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.KodFormatters
import com.example.model.FilterDateRange
import com.example.model.FilterFileType
import com.example.model.FilterSizeRange
import com.example.model.KodFileItem
import com.example.model.KodSpace
import com.example.model.KodTag
import com.example.model.SearchFilterState
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.FolderPickerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionBottomSheet(
    item: KodFileItem,
    isRecycleBin: Boolean,
    onDismiss: () -> Unit,
    onOpenOrPreview: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onManageTags: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onZip: () -> Unit,
    onUnzip: () -> Unit,
    onDetails: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val visual = resolveFileVisual(item)
    TrackModalOverlay()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isFolder && item.isDepartmentFolder) {
                    DepartmentFolderThumbnailIcon(compact = true)
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(visual.tint.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(visual.icon, contentDescription = null, tint = visual.tint)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (item.isFolder) {
                            "${if (item.isDepartmentFolder) "部门文件夹" else "文件夹"} · ${KodFormatters.formatTimestamp(item.modifyTime)}"
                        } else {
                            "${KodFormatters.formatBytes(item.size)} · ${KodFormatters.formatTimestamp(item.modifyTime)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if ((isRecycleBin || item.isRecycleItem) && item.displayPath.isNotBlank()) {
                        Text(
                            text = "原位置：${item.displayPath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (isRecycleBin || item.isRecycleItem) {
                ActionSheetRow(
                    icon = Icons.Filled.RestoreFromTrash,
                    label = "还原到原位置",
                    onClick = onRestore
                )
                ActionSheetRow(
                    icon = Icons.Filled.Delete,
                    label = "永久彻底删除",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            } else {
                // Quick top action strip (Nextcloud signature quick-actions row)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionTile(
                        icon = Icons.Filled.Visibility,
                        label = if (item.isFolder) "打开" else "预览",
                        onClick = onOpenOrPreview,
                        modifier = Modifier.weight(1f)
                    )
                    if (!item.isFolder) {
                        QuickActionTile(
                            icon = Icons.Filled.Download,
                            label = "下载",
                            onClick = onDownload,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (item.canShare) {
                        QuickActionTile(
                            icon = Icons.Filled.Share,
                            label = "共享/协作",
                            onClick = onShare,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    QuickActionTile(
                        icon = if (item.isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                        label = if (item.isFav) "已收藏" else "收藏",
                        tint = if (item.isFav) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary,
                        onClick = onToggleFavorite,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                ActionSheetRow(icon = Icons.Filled.Label, label = "网盘颜色标签", onClick = onManageTags)
                ActionSheetRow(icon = Icons.Filled.DriveFileRenameOutline, label = "重命名", onClick = onRename)
                ActionSheetRow(icon = Icons.AutoMirrored.Filled.DriveFileMove, label = "移动到…", onClick = onMove)
                ActionSheetRow(icon = Icons.Filled.ContentCopy, label = "复制到…", onClick = onCopy)
                if (item.isArchive) {
                    ActionSheetRow(icon = Icons.Filled.Unarchive, label = "在线解压到当前目录", onClick = onUnzip)
                } else {
                    ActionSheetRow(icon = Icons.Filled.Archive, label = "在线压缩为 ZIP", onClick = onZip)
                }
                ActionSheetRow(icon = Icons.Filled.Info, label = "属性与详情", onClick = onDetails)
                ActionSheetRow(
                    icon = Icons.Filled.Delete,
                    label = "移至回收站",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActionSheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    ListItem(
        headlineContent = { Text(label, color = tint, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterBottomSheet(
    filter: SearchFilterState,
    onUpdateFilter: ((SearchFilterState) -> SearchFilterState) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    TrackModalOverlay()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("多维筛选条件", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(
                    onClick = onReset,
                    enabled = filter.hasActiveFilters,
                    modifier = Modifier.testTag("filter_reset_button")
                ) {
                    Text("重置全部")
                }
            }

            // 1. File Type
            Text("文件类型", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterFileType.entries.forEach { type ->
                    FilterChip(
                        selected = filter.fileType == type,
                        onClick = { onUpdateFilter { it.copy(fileType = type) } },
                        label = { Text(type.label) },
                        modifier = Modifier.testTag("filter_type_${type.name}")
                    )
                }
            }

            // 2. Modified Date
            Text("修改日期", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterDateRange.entries.forEach { range ->
                    FilterChip(
                        selected = filter.dateRange == range,
                        onClick = { onUpdateFilter { it.copy(dateRange = range) } },
                        label = { Text(range.label) },
                        modifier = Modifier.testTag("filter_date_${range.name}")
                    )
                }
            }

            // 3. File Size
            Text("文件大小", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterSizeRange.entries.forEach { range ->
                    FilterChip(
                        selected = filter.sizeRange == range,
                        onClick = { onUpdateFilter { it.copy(sizeRange = range) } },
                        label = { Text(range.label) },
                        modifier = Modifier.testTag("filter_size_${range.name}")
                    )
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("filter_apply_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (filter.hasActiveFilters) "应用筛选 (${filter.activeCount} 项条件)" else "完成")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KodTagManagerSheet(
    item: KodFileItem,
    availableTags: List<KodTag>,
    onToggleTag: (KodTag) -> Unit,
    onCreateTag: (name: String, style: String) -> Unit,
    onDismiss: () -> Unit
) {
    var newTagName by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf("label-blue-normal") }
    val colorOptions = listOf(
        "label-blue-normal" to 0xFF0074C8L,
        "label-red-normal" to 0xFFEF4444L,
        "label-orange-normal" to 0xFFF97316L,
        "label-yellow-normal" to 0xFFEAB308L,
        "label-green-normal" to 0xFF22C55EL,
        "label-purple-normal" to 0xFF8B5CF6L
    )

    TrackModalOverlay()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("文件颜色标签 · ${item.name}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)

            if (availableTags.isEmpty()) {
                Text(
                    "暂无自定义标签，可在下方新建网盘颜色标签。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableTags.forEach { tag ->
                        val assigned = item.tags.any { it.id == tag.id }
                        FilterChip(
                            selected = assigned,
                            onClick = { onToggleTag(tag) },
                            label = { Text(tag.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(tag.colorHex))
                                )
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            Text("新建标签", style = MaterialTheme.typography.labelLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                colorOptions.forEach { (style, hex) ->
                    val chosen = selectedStyle == style
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(hex))
                            .border(
                                width = if (chosen) 2.5.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape
                            )
                            .clickable { selectedStyle = style },
                        contentAlignment = Alignment.Center
                    ) {
                        if (chosen) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    placeholder = { Text("例如：重要合同、项目资料") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = newTagName.isNotBlank(),
                    onClick = {
                        onCreateTag(newTagName.trim(), selectedStyle)
                        newTagName = ""
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("添加")
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerBottomSheet(
    state: FolderPickerState,
    onSwitchSpace: (isEnterprise: Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    onEnterFolder: (KodFileItem) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    TrackModalOverlay()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.breadcrumbs.size > 1) {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            state.isForSyncTarget -> "选择网盘同步目标文件夹"
                            state.isMove -> "移动 ${state.targetItems.size} 项到"
                            else -> "复制 ${state.targetItems.size} 项到"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = state.breadcrumbs.joinToString(" / ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.breadcrumbs.firstOrNull()?.label == KodSpace.PERSONAL.label,
                    onClick = { onSwitchSpace(false) },
                    label = { Text(KodSpace.PERSONAL.label) }
                )
                FilterChip(
                    selected = state.breadcrumbs.firstOrNull()?.label == KodSpace.ENTERPRISE.label,
                    onClick = { onSwitchSpace(true) },
                    label = { Text(KodSpace.ENTERPRISE.label) }
                )
            }

            HorizontalDivider()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 320.dp)
            ) {
                when {
                    state.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.folders.isEmpty() -> {
                        Text(
                            text = "此目录下没有子文件夹",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn {
                            items(state.folders, key = { it.path }) { folder ->
                                val folderVisual = resolveFileVisual(folder)
                                ListItem(
                                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = if (folder.isDepartmentFolder) {
                                        { Text("部门文件夹", style = MaterialTheme.typography.labelSmall) }
                                    } else null,
                                    leadingContent = {
                                        Icon(
                                            imageVector = folderVisual.icon,
                                            contentDescription = null,
                                            tint = folderVisual.tint
                                        )
                                    },
                                    modifier = Modifier.clickable { onEnterFolder(folder) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            state.isForSyncTarget -> "选择此目录"
                            state.isMove -> "移动到此处"
                            else -> "复制到此处"
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initialValue: String = "",
    placeholder: String = "",
    confirmLabel: String = "确定",
    supportingText: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    TrackModalOverlay()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("prompt_dialog_input")
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
                modifier = Modifier.testTag("prompt_dialog_confirm")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun CreateNoteDialog(
    onConfirm: (fileName: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    var fileName by remember { mutableStateOf("未命名笔记.md") }
    var content by remember { mutableStateOf("# 新建笔记\n\n") }

    TrackModalOverlay()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文本 / Markdown") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("初始内容") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = fileName.isNotBlank(),
                onClick = { onConfirm(fileName.trim(), content) }
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun FileDetailsDialog(
    item: KodFileItem,
    onDismiss: () -> Unit
) {
    TrackModalOverlay()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("文件属性") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("名称", item.name)
                DetailRow(
                    "类型",
                    when {
                        item.isFolder && item.isDepartmentFolder -> "部门文件夹"
                        item.isFolder -> "文件夹"
                        else -> item.normalizedExt.uppercase().ifBlank { "文件" }
                    }
                )
                if (!item.isFolder) {
                    DetailRow("大小", KodFormatters.formatBytes(item.size))
                }
                DetailRow("修改时间", KodFormatters.formatTimestamp(item.modifyTime))
                if (item.createTime > 0L) {
                    DetailRow("创建时间", KodFormatters.formatTimestamp(item.createTime))
                }
                if (item.ownerName.isNotBlank()) {
                    DetailRow("创建者", item.ownerName)
                }
                if (item.displayPath.isNotBlank()) {
                    DetailRow(if (item.isRecycleItem) "原位置" else "所在位置", item.displayPath)
                }
                DetailRow("网盘路径", item.path, mono = true)
                if (item.hashMd5.isNotBlank()) {
                    DetailRow("MD5 校验", item.hashMd5, mono = true)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ConfirmDangerDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    TrackModalOverlay()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}
