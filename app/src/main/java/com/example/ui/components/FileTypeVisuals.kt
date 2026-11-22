package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.R
import com.example.data.KodFormatters
import com.example.model.KodFileItem
import com.example.ui.theme.FileArchiveColor
import com.example.ui.theme.FileAudioColor
import com.example.ui.theme.FileCodeColor
import com.example.ui.theme.FileDefaultColor
import com.example.ui.theme.FileDocColor
import com.example.ui.theme.FileFolderColor
import com.example.ui.theme.FileImageColor
import com.example.ui.theme.FilePdfColor
import com.example.ui.theme.FileSheetColor
import com.example.ui.theme.FileSlideColor
import com.example.ui.theme.FileVideoColor

data class FileVisualSpec(
    val icon: ImageVector,
    val tint: Color
)

fun resolveFileVisual(item: KodFileItem): FileVisualSpec {
    if (item.isFolder) return FileVisualSpec(Icons.Filled.Folder, FileFolderColor)
    return when (item.normalizedExt) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" ->
            FileVisualSpec(Icons.Filled.Image, FileImageColor)
        "mp4", "mkv", "mov", "avi", "webm", "flv", "m4v", "ts" ->
            FileVisualSpec(Icons.Filled.PlayCircle, FileVideoColor)
        "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus" ->
            FileVisualSpec(Icons.Filled.AudioFile, FileAudioColor)
        "pdf", "ofd", "djvu" ->
            FileVisualSpec(Icons.Filled.PictureAsPdf, FilePdfColor)
        "doc", "docx", "docm", "rtf", "odt", "wps", "wpt", "txt", "md", "markdown" ->
            FileVisualSpec(Icons.Filled.Description, FileDocColor)
        "xls", "xlsx", "xlsb", "xlsm", "csv", "ods", "et" ->
            FileVisualSpec(Icons.Filled.TableChart, FileSheetColor)
        "ppt", "pptx", "pptm", "pps", "ppsx", "odp", "dps", "key" ->
            FileVisualSpec(Icons.Filled.Slideshow, FileSlideColor)
        "dwg", "dxf", "dxfb", "stl", "obj", "fbx", "glb", "gltf", "stp", "step", "igs", "iges", "ifc", "3ds", "ply", "blend", "usdz" ->
            FileVisualSpec(Icons.Filled.Architecture, Color(0xFF0284C7))
        "epub", "mobi", "azw", "azw3", "cbz", "fb2", "fbz", "kf8" ->
            FileVisualSpec(Icons.Filled.AutoStories, Color(0xFF7C3AED))
        "drawio", "vsd", "vsdx", "vdx", "graphml", "dio", "smm", "mind", "xmind", "km", "edraw", "excalidraw", "vsdu", "lt" ->
            FileVisualSpec(Icons.Filled.AccountTree, Color(0xFF0D9488))
        "psd", "psb", "ai", "xd", "sketch", "xcf", "eps", "tif", "tiff", "raw", "dng" ->
            FileVisualSpec(Icons.Filled.Brush, Color(0xFFDB2777))
        "eml", "msg", "mht", "mhtml", "emlx" ->
            FileVisualSpec(Icons.Filled.Email, Color(0xFF2563EB))
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" ->
            FileVisualSpec(Icons.Filled.FolderZip, FileArchiveColor)
        "kt", "java", "py", "js", "ts", "json", "xml", "yaml", "yml", "html", "css", "php", "sql", "sh", "go", "rs", "c", "cpp" ->
            FileVisualSpec(Icons.Filled.Code, FileCodeColor)
        else ->
            FileVisualSpec(Icons.Filled.InsertDriveFile, FileDefaultColor)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NextcloudFileListItem(
    item: KodFileItem,
    thumbnailUrl: String?,
    imageLoader: ImageLoader,
    showThumbnails: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = resolveFileVisual(item)
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            .testTag("file_row_${item.name}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading thumbnail or crisp file-type badge
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(visual.tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            if (showThumbnails && item.isImage && !thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = thumbnailUrl,
                    imageLoader = imageLoader,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.tint,
                    modifier = Modifier.size(26.dp)
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已选中",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Name + Metadata + KodBox badges (Fav, Share, Color Tags)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.isFav) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "已收藏",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(15.dp)
                    )
                }
                if (item.isShare) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = "已共享",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val metaText = if (item.isFolder) {
                    buildString {
                        append(KodFormatters.formatTimestamp(item.modifyTime))
                        if (item.size > 0L) append(" · ${KodFormatters.formatBytes(item.size)}")
                        if (item.childCount > 0) append(" · ${item.childCount}项")
                    }
                } else {
                    "${KodFormatters.formatBytes(item.size)} · ${KodFormatters.formatTimestamp(item.modifyTime)}"
                }
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Optional description note badge or inline colored tag pills (inspired by KodBox file rows)
            if (item.description.isNotBlank() || item.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (item.description.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "“${item.description}”",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    item.tags.take(3).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(tag.colorHex).copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(tag.colorHex))
                                )
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isSelectionMode) {
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.testTag("file_more_${item.name}")
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(modifier = Modifier.width(10.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NextcloudFileGridCard(
    item: KodFileItem,
    thumbnailUrl: String?,
    imageLoader: ImageLoader,
    showThumbnails: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = resolveFileVisual(item)
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("file_grid_${item.name}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.28f)
                    .background(visual.tint.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                if (showThumbnails && item.isImage && !thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = thumbnailUrl,
                        imageLoader = imageLoader,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = visual.icon,
                        contentDescription = null,
                        tint = visual.tint,
                        modifier = Modifier.size(44.dp)
                    )
                }

                // Top badges
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (item.isFav) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "已收藏",
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                    if (item.isShare) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = "已共享",
                                tint = Color.White,
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(22.dp)
                            .background(Color.White, CircleShape)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (item.isFolder) "文件夹" else KodFormatters.formatBytes(item.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyFolderState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_vault_1795282477000),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(196.dp)
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(22.dp))
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(18.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun ConnectionErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "无法加载云盘数据",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onRetry) {
                Text("重试连接")
            }
        }
    }
}
