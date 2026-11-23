package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.KodFormatters
import com.example.model.TransferStatus
import com.example.model.TransferTask
import com.example.model.TransferType
import com.example.ui.components.EmptyFolderState
import com.example.ui.theme.StatusSuccess
import com.example.viewmodel.KodUiState

/**
 * Orders transfer tasks so actively running transfers always stay pinned to the top
 * (newest first), followed by completed/failed/cancelled history (newest first).
 */
internal fun orderTransfersForDisplay(tasks: List<TransferTask>): List<TransferTask> {
    return tasks.sortedWith(
        compareBy<TransferTask> { it.status != TransferStatus.RUNNING }
            .thenByDescending { it.timestamp }
    )
}

@Composable
fun TransfersTabContent(
    state: KodUiState,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onOpenDownloaded: (TransferTask) -> Unit,
    onShareDownloaded: (TransferTask) -> Unit = {},
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orderedTransfers = remember(state.transfers) {
        orderTransfersForDisplay(state.transfers)
    }
    val runningCount = remember(orderedTransfers) {
        orderedTransfers.count { it.status == TransferStatus.RUNNING }
    }
    val listState = rememberLazyListState()
    val topTaskId = orderedTransfers.firstOrNull()?.id
    val runningSignature = remember(orderedTransfers) {
        orderedTransfers.filter { it.status == TransferStatus.RUNNING }.joinToString(",") { it.id }
    }

    // Auto-scroll to index 0 whenever a new transfer starts or takes the top slot,
    // preventing LazyColumn from anchoring on older items and hiding the active first row.
    LaunchedEffect(topTaskId, runningSignature) {
        if (orderedTransfers.isNotEmpty() && (runningCount > 0 || listState.firstVisibleItemIndex <= 1)) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (orderedTransfers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val summaryText = if (runningCount > 0) {
                    "正在传输 $runningCount 项 · 共 ${orderedTransfers.size} 条"
                } else {
                    "共 ${orderedTransfers.size} 条传输记录"
                }
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (runningCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (orderedTransfers.any { it.status != TransferStatus.RUNNING }) {
                    TextButton(
                        onClick = onClearFinished,
                        modifier = Modifier.testTag("clear_finished_transfers_button")
                    ) {
                        Text("清除已完成")
                    }
                }
            }
        }

        if (orderedTransfers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyFolderState(
                    title = "当前没有传输任务",
                    subtitle = "您上传到网盘或下载到本地的文件进度将实时显示在此"
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("transfers_lazy_column")
            ) {
                items(orderedTransfers, key = { it.id }) { task ->
                    val isCompletedDownload = task.status == TransferStatus.COMPLETED &&
                        task.type == TransferType.DOWNLOAD
                    Card(
                        onClick = {
                            if (isCompletedDownload) {
                                onOpenDownloaded(task)
                            }
                        },
                        enabled = isCompletedDownload,
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            disabledContentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("transfer_card_${task.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (task.type == TransferType.UPLOAD) Icons.Filled.CloudUpload else Icons.Filled.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.fileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val statusLabel = when (task.status) {
                                        TransferStatus.RUNNING -> if (task.totalBytes > 0L) {
                                            "${KodFormatters.formatBytes(task.bytesTransferred)} / ${KodFormatters.formatBytes(task.totalBytes)}"
                                        } else {
                                            "传输中 · ${KodFormatters.formatBytes(task.bytesTransferred)}"
                                        }
                                        TransferStatus.COMPLETED -> if (task.type == TransferType.UPLOAD) {
                                            "已上传到网盘"
                                        } else {
                                            "已下载 (${KodFormatters.formatBytes(task.bytesTransferred)}) · 点击打开"
                                        }
                                        TransferStatus.FAILED -> task.errorMessage.ifBlank { "传输失败" }
                                        TransferStatus.CANCELLED -> "已取消"
                                    }
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (task.status == TransferStatus.FAILED) {
                                            MaterialTheme.colorScheme.error
                                        } else if (isCompletedDownload) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                when (task.status) {
                                    TransferStatus.RUNNING -> {
                                        IconButton(onClick = { onCancelTask(task.id) }) {
                                            Icon(Icons.Filled.Cancel, contentDescription = "取消传输")
                                        }
                                    }
                                    TransferStatus.COMPLETED -> {
                                        if (isCompletedDownload) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                FilledTonalButton(
                                                    onClick = { onOpenDownloaded(task) },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier
                                                        .defaultMinSize(minHeight = 40.dp)
                                                        .testTag("open_downloaded_${task.id}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("打开", style = MaterialTheme.typography.labelMedium)
                                                }
                                                IconButton(
                                                    onClick = { onShareDownloaded(task) },
                                                    modifier = Modifier.testTag("share_downloaded_${task.id}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Share,
                                                        contentDescription = "分享或用其他应用打开",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        } else {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = "完成",
                                                tint = StatusSuccess,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                    }
                                    TransferStatus.FAILED -> {
                                        if (task.canRetry) {
                                            IconButton(
                                                onClick = { onRetryTask(task.id) },
                                                modifier = Modifier.testTag("retry_transfer_${task.id}")
                                            ) {
                                                Icon(
                                                    Icons.Filled.Refresh,
                                                    contentDescription = "重试传输",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        } else {
                                            Icon(
                                                Icons.Filled.Error,
                                                contentDescription = "失败",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                    }
                                    TransferStatus.CANCELLED -> {
                                        if (task.canRetry) {
                                            IconButton(
                                                onClick = { onRetryTask(task.id) },
                                                modifier = Modifier.testTag("retry_transfer_${task.id}")
                                            ) {
                                                Icon(
                                                    Icons.Filled.Refresh,
                                                    contentDescription = "继续传输",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (task.status == TransferStatus.RUNNING) {
                                LinearProgressIndicator(
                                    progress = { task.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
