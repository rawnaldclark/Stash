package com.stash.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the page this is on is the one in front: its lifecycle is RESUMED. Under the open player
 * the scaffold holds the page at STARTED (as the NavHost used to when Now Playing was a destination
 * on top of it), and with the app in the background it is lower still. A frame loop that only
 * decorates the page (a ticker, a spinning logo, a shimmer, a pulse) runs only while this is true,
 * so nothing ticks under the player. Recomposes its caller only when the answer changes.
 */
@Composable
fun rememberPageActive(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}
