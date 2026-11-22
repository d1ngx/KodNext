package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.model.KodFileItem
import com.example.ui.components.ConnectionErrorCard
import com.example.ui.components.EmptyFolderState
import com.example.ui.theme.FileAudioColor
import com.example.ui.theme.FileVideoColor
import com.example.viewmodel.KodUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTabContent(
    state: KodUiState,
    imageLoader: ImageLoader,
    thumbnailUrlFor: (KodFileItem) -> String?,
    onSetFilter: (String) -> Unit,
    onRefresh: () -> Unit,
    onMediaClick: (KodFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayed = remember(state.mediaItems, state.mediaFilter) {
        when (state.mediaFilter) {
            "IMAGE" -> state.mediaItems.filter { it.isImage }
            "VIDEO" -> state.mediaItems.filter { it.isVideo }
            "AUDIO" -> state.mediaItems.filter { it.isAudio }
            else -> state.mediaItems
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL" to "全部媒体", "IMAGE" to "照片", "VIDEO" to "视频", "AUDIO" to "音频").forEach { (key, label) ->
                FilterChip(
                    selected = state.mediaFilter == key,
                    onClick = { onSetFilter(key) },
                    label = { Text(label) }
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = state.isLoadingMedia,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                state.mediaError != null && displayed.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ConnectionErrorCard(message = state.mediaError, onRetry = onRefresh)
                    }
                }
                !state.isLoadingMedia && displayed.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyFolderState(
                            title = "暂无媒体文件",
                            subtitle = "来自 KodBox 图片、视频与音频分类的媒体将自动汇聚于此"
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 112.dp),
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 84.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayed, key = { it.path }) { item ->
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { onMediaClick(item) }
                            ) {
                                if (item.isImage) {
                                    AsyncImage(
                                        model = thumbnailUrlFor(item),
                                        imageLoader = imageLoader,
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    val tileColor = if (item.isAudio) FileAudioColor else FileVideoColor
                                    val tileIcon = if (item.isAudio) Icons.Filled.AudioFile else Icons.Filled.VideoFile
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(tileColor.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = tileIcon,
                                            contentDescription = item.name,
                                            tint = tileColor,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.55f),
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.PlayArrow,
                                                contentDescription = if (item.isAudio) "播放音频" else "播放视频",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.48f))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
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
