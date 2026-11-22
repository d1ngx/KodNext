package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage

/**
 * Displays the synced KodBox user avatar with automatic fallback to an initial-letter badge
 * whenever the account has no avatar configured or the image fails to load.
 */
@Composable
fun KodUserAvatar(
    avatarUrl: String,
    displayName: String,
    imageLoader: ImageLoader,
    size: Dp = 44.dp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    borderColor: Color? = null,
    modifier: Modifier = Modifier
) {
    var loadFailed by remember(avatarUrl) { mutableStateOf(false) }
    val showRemoteImage = avatarUrl.isNotBlank() && !loadFailed

    val baseModifier = modifier
        .size(size)
        .clip(CircleShape)
        .background(containerColor)
        .let { m ->
            if (borderColor != null) m.border(1.5.dp, borderColor, CircleShape) else m
        }
        .testTag("kod_user_avatar")

    Box(
        modifier = baseModifier,
        contentAlignment = Alignment.Center
    ) {
        val initial = displayName.trim().take(1).uppercase().ifBlank { "K" }
        val textStyle = when {
            size >= 48.dp -> MaterialTheme.typography.titleLarge
            size >= 36.dp -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.labelLarge
        }
        Text(
            text = initial,
            style = textStyle,
            color = contentColor,
            fontWeight = FontWeight.Bold
        )

        if (showRemoteImage) {
            AsyncImage(
                model = avatarUrl,
                imageLoader = imageLoader,
                contentDescription = "$displayName 的头像",
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
