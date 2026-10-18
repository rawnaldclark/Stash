package com.stash.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The lifecycle the NavHost runs its pages under: the activity's, held at STARTED while the player
 * covers the page ([covered]). The NavHost caps every back-stack entry at its host's lifecycle, so
 * the page in front pauses under the open player and resumes when it closes, as it did when Now
 * Playing was a destination pushed over it: ON_RESUME refreshes run again, and frame loops gated
 * on the page being in front (rememberPageActive) stop.
 *
 * STARTED rather than CREATED: a stopped NavHost would drop its back callback and re-add it on
 * restart, after the player's, and Back would then pop the page instead of closing the player.
 */
@Composable
fun rememberCoveredPageLifecycleOwner(covered: Boolean): LifecycleOwner {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember(parent) { CoveredPageLifecycleOwner(parent) }
    DisposableEffect(owner) {
        owner.attach()
        onDispose { owner.detach() }
    }
    SideEffect { owner.covered = covered }
    return owner
}

/** Mirrors [parent], capped at STARTED while [covered]. */
class CoveredPageLifecycleOwner(private val parent: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val observer = LifecycleEventObserver { _, _ -> sync() }

    /** The player covers the page. */
    var covered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    fun attach() {
        parent.addObserver(observer)
        sync()
    }

    fun detach() {
        parent.removeObserver(observer)
    }

    private fun sync() {
        val cap = if (covered) Lifecycle.State.STARTED else Lifecycle.State.RESUMED
        val target = minOf(parent.currentState, cap)
        val current = registry.currentState
        // A registry that never started can't be destroyed, and a destroyed one never comes back.
        if (current == Lifecycle.State.DESTROYED) return
        if (target == Lifecycle.State.DESTROYED && current == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }
}
