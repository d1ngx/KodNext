package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@Composable
fun TransfersTabContent(
    state: KodUiState,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onOpenDownloaded: (TransferTask) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.transfers.any { it.status != TransferStatus.RUNNING }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "共 ${state.transfers.size} 条传输记录",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onClearFinished) {
                    Text("清除已完成")
                }
            }
        }

        if (state.transfers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyFolderState(
                    title = "当前没有传输任务",
                    subtitle = "您上传到网盘或下载到本地的文件进度将实时显示在此"
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.transfers, key = { it.id }) { task ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
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
                                        TransferStatus.RUNNING -> "${KodFormatters.formatBytes(task.bytesTransferred)} / ${KodFormatters.formatBytes(task.totalBytes)}"
                                        TransferStatus.COMPLETED -> if (task.type == TransferType.UPLOAD) "已上传到网盘" else "已保存到本地 (${KodFormatters.formatBytes(task.bytesTransferred)})"
                                        TransferStatus.FAILED -> task.errorMessage.ifBlank { "传输失败" }
                                        TransferStatus.CANCELLED -> "已取消"
                                    }
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (task.status == TransferStatus.FAILED) {
                                            MaterialTheme.colorScheme.error
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
                                        if (task.type == TransferType.DOWNLOAD && task.localPath.isNotBlank()) {
                                            IconButton(onClick = { onOpenDownloaded(task) }) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = "打开本地文件",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
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
