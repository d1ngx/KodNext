package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether any modal popup window (AlertDialog, Dialog, ModalBottomSheet) is currently
 * visible on top of the Activity window. Because Compose modals render in separate Android
 * Popup windows above the Activity's Scaffold SnackbarHost, MainActivity consults this tracker
 * to route feedback messages to a top-layer Toast so notifications are never occluded behind
 * dialogs or bottom sheets.
 */
object ModalOverlayTracker {
    private val activeModalCount = AtomicInteger(0)

    fun enter() {
        activeModalCount.incrementAndGet()
    }

    fun exit() {
        activeModalCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun hasActiveModal(): Boolean = activeModalCount.get() > 0

    internal fun resetForTest() {
        activeModalCount.set(0)
    }
}

@Composable
fun TrackModalOverlay() {
    DisposableEffect(Unit) {
        ModalOverlayTracker.enter()
        onDispose {
            ModalOverlayTracker.exit()
        }
    }
}
