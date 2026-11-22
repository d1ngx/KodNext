package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.KodFormatters
import com.example.model.KodFileItem
import com.example.model.KodShareItem
import com.example.model.ShareSubTab
import com.example.ui.components.ConfirmDangerDialog
import com.example.ui.components.ConnectionErrorCard
import com.example.ui.components.EmptyFolderState
import com.example.ui.components.resolveFileVisual
import com.example.viewmodel.KodUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTabContent(
    state: KodUiState,
    onSelectSubTab: (ShareSubTab) -> Unit,
    onRefresh: () -> Unit,
    onCopyShare: (KodShareItem) -> Unit,
    onEditShare: (KodShareItem) -> Unit,
    onCancelShare: (KodShareItem) -> Unit,
    onOpenSharedResource: (path: String, title: String, isFolder: Boolean, ext: String, size: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingCancelShare by remember { mutableStateOf<KodShareItem?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Segmented switch preserving 外链共享, 我的协作, and 与我协作
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                ShareSubTab.entries.forEachIndexed { index, subTab ->
                    val count = when (subTab) {
                        ShareSubTab.EXTERNAL_LINKS -> state.shares.size
                        ShareSubTab.MY_COLLAB -> state.collabShares.size
                        ShareSubTab.SHARED_WITH_ME -> state.sharedWithMeItems.size
                    }
                    SegmentedButton(
                        selected = state.activeShareSubTab == subTab,
                        onClick = { onSelectSubTab(subTab) },
                        shape = SegmentedButtonDefaults.itemShape(index, ShareSubTab.entries.size),
                        icon = {},
                        modifier = Modifier.testTag("share_subtab_${subTab.name.lowercase()}")
                    ) {
                        Text(
                            text = if (count > 0) "${subTab.label} ($count)" else subTab.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = state.isLoadingShares,
            onRefresh = onRefresh,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (state.activeShareSubTab) {
                ShareSubTab.EXTERNAL_LINKS -> {
                    when {
                        state.sharesError != null && state.shares.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ConnectionErrorCard(message = state.sharesError, onRetry = onRefresh)
                            }
                        }
                        !state.isLoadingShares && state.shares.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyFolderState(
                                    title = "暂无外链共享",
                                    subtitle = "在文件列表中点击文件或文件夹右侧菜单，即可创建带提取码、有效期和只读/读写权限的外链分享"
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 92.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(state.shares, key = { "link_${it.shareId.ifBlank { it.path }}" }) { share ->
                                    CompactExternalShareRow(
                                        share = share,
                                        onOpen = {
                                            if (share.path.isNotBlank()) {
                                                onOpenSharedResource(share.path, share.title, share.isFolder, share.ext, share.size)
                                            }
                                        },
                                        onCopy = { onCopyShare(share) },
                                        onEdit = { onEditShare(share) },
                                        onCancel = { pendingCancelShare = share }
                                    )
                                }
                            }
                        }
                    }
                }

                ShareSubTab.MY_COLLAB -> {
                    when {
                        state.sharesError != null && state.collabShares.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ConnectionErrorCard(message = state.sharesError, onRetry = onRefresh)
                            }
                        }
                        !state.isLoadingShares && state.collabShares.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyFolderState(
                                    title = "暂无我的协作",
                                    subtitle = "您在网盘中发起的团队内部协作共享将集中展示在此处"
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 92.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(state.collabShares, key = { "collab_${it.shareId.ifBlank { it.path }}" }) { share ->
                                    CompactCollabShareRow(
                                        share = share,
                                        onOpen = {
                                            if (share.path.isNotBlank()) {
                                                onOpenSharedResource(share.path, share.title, share.isFolder, share.ext, share.size)
                                            }
                                        },
                                        onCancel = if (share.shareId.isNotBlank()) {
                                            { pendingCancelShare = share }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }

                ShareSubTab.SHARED_WITH_ME -> {
                    when {
                        state.sharesError != null && state.sharedWithMeItems.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ConnectionErrorCard(message = state.sharesError, onRetry = onRefresh)
                            }
                        }
                        !state.isLoadingShares && state.sharedWithMeItems.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyFolderState(
                                    title = "暂无与我协作内容",
                                    subtitle = "其他成员共享给您的协作文件夹和文档会出现在这里"
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 92.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(state.sharedWithMeItems, key = { "withme_${it.path}" }) { item ->
                                    CompactSharedWithMeRow(
                                        item = item,
                                        onOpen = {
                                            onOpenSharedResource(item.path, item.name, item.isFolder, item.normalizedExt, item.size)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingCancelShare?.let { target ->
        ConfirmDangerDialog(
            title = "取消共享",
            message = "确定取消「${target.title}」的共享吗？取消后原链接或协作访问将立即失效。",
            confirmLabel = "确认取消",
            onConfirm = {
                val shareToCancel = target
                pendingCancelShare = null
                onCancelShare(shareToCancel)
            },
            onDismiss = { pendingCancelShare = null }
        )
    }
}

@Composable
private fun CompactExternalShareRow(
    share: KodShareItem,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit
) {
    val visual = resolveFileVisual(
        KodFileItem(
            path = share.path,
            name = share.title,
            isFolder = share.isFolder,
            ext = share.ext
        )
    )
    val permShort = when {
        share.canUpload -> "读写"
        share.notDownload -> "仅预览"
        else -> "只读"
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .testTag("share_card_${share.shareId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(visual.tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.tint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = share.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (share.canUpload) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        }
                    ) {
                        Text(
                            text = permShort,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (share.canUpload) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    if (share.password.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "已设提取码",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                val metaParts = buildList {
                    if (share.password.isNotBlank()) add("码:${share.password}")
                    add(KodFormatters.formatDateOnly(share.timeTo))
                    add("浏览${share.numView}/下载${share.numDownload}")
                }
                Text(
                    text = metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("copy_share_${share.shareId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制外链",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("edit_share_${share.shareId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "编辑权限",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("cancel_share_${share.shareId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.LinkOff,
                        contentDescription = "取消外链",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactCollabShareRow(
    share: KodShareItem,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?
) {
    val visual = resolveFileVisual(
        KodFileItem(
            path = share.path,
            name = share.title,
            isFolder = share.isFolder,
            ext = share.ext
        )
    )

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .testTag("collab_card_${share.shareId.ifBlank { share.title }}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(visual.tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.tint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = share.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "内部协作",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                val meta = buildList {
                    add(if (share.isFolder) "协作文件夹" else KodFormatters.formatBytes(share.size))
                    if (share.createTime > 0L) add(KodFormatters.formatTimestamp(share.createTime))
                }
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LinkOff,
                        contentDescription = "取消协作",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactSharedWithMeRow(
    item: KodFileItem,
    onOpen: () -> Unit
) {
    val visual = resolveFileVisual(item)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .testTag("shared_with_me_${item.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(visual.tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.tint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val meta = buildList {
                    if (item.ownerName.isNotBlank()) add("来自 ${item.ownerName}")
                    add(if (item.isFolder) "文件夹" else KodFormatters.formatBytes(item.size))
                    if (item.modifyTime > 0L) add(KodFormatters.formatTimestamp(item.modifyTime))
                }
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "打开协作资源",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
