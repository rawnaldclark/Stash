package com.stash.core.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app's own snackbar: the scaffold's host, above the bottom chrome (or above the system bar over an open
 * player). For a screen or a Home section that has no host of its own. A message is shown from the scaffold's
 * scope, so it outlives the screen that sent it (a post taken down, then Back).
 */
@Stable
class AppSnackbar(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    /** Queues [message] as a short snackbar. The same line already showing isn't queued again (three failed votes say it once). */
    fun show(message: String) {
        if (message.isBlank() || host.currentSnackbarData?.visuals?.message == message) return
        scope.launch { host.showSnackbar(message, duration = SnackbarDuration.Short) }
    }
}

/** An [AppSnackbar] over [host], showing from this composition's scope. */
@Composable
fun rememberAppSnackbar(host: SnackbarHostState): AppSnackbar {
    val scope = rememberCoroutineScope()
    return remember(host, scope) { AppSnackbar(host, scope) }
}

/** The scaffold's [AppSnackbar]; null outside it (a preview, a test), where callers fall back to their own. */
val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbar?> { null }
