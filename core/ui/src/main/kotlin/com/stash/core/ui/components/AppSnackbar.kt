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
    /** The lines showing or waiting their turn (main thread only). */
    private val pending = mutableSetOf<String>()

    /**
     * Queues [message] as a short snackbar. A line already showing or waiting isn't queued again, so three failed
     * votes in a row say it once, not three times one after another.
     */
    fun show(message: String) {
        if (message.isBlank() || !pending.add(message)) return
        scope.launch {
            try {
                host.showSnackbar(message, duration = SnackbarDuration.Short)
            } finally {
                pending.remove(message)
            }
        }
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
