package com.stash.core.ui.ambient

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The one crossfade behind every album colour the app wears: a mix from the roles on screen when
 * the song changed ([from]) to the new song's ([to]). [ProvideAlbumChrome] drives [mix] from 0 to 1;
 * readers take the colour they draw (accent, ink, dom, ...) at draw or composition time, so a
 * crossfade redraws only what wears the colour.
 *
 * Colour never jumps: [retarget] starts from what is displayed at that moment, however far the
 * previous crossfade had got. [neighbour] and [blend] are the hook for a later swipe-driven blend
 * (the colours leaning toward the next or previous song while a swipe is held); nothing sets them
 * yet, and with no neighbour they cost nothing.
 *
 * All state is snapshot state, so reads in a draw lambda or a composable are tracked.
 */
@Stable
class AccentAnimator(initial: AccentRoles) {
    /** Where the current crossfade started: the colours displayed when it began. */
    var from: AccentRoles by mutableStateOf(initial)
        private set

    /** Where the current crossfade ends: the current song's roles. */
    var to: AccentRoles by mutableStateOf(initial)
        private set

    /** How far the crossfade has got: 0 = [from], 1 = [to]. */
    var mix: Float by mutableFloatStateOf(1f)

    /** The roles a swipe leans toward (the next or previous song), or null. Not set yet (a later phase). */
    var neighbour: AccentRoles? by mutableStateOf(null)

    /** How far toward [neighbour] the colours lean, 0..1. Ignored while [neighbour] is null. */
    var blend: Float by mutableFloatStateOf(0f)

    /** Every role as displayed right now: the crossfade, then the lean toward [neighbour]. */
    fun displayed(): AccentRoles {
        val settled = lerpRoles(from, to, mix)
        val n = neighbour ?: return settled
        return lerpRoles(settled, n, blend)
    }

    /**
     * Heads for [target] from whatever is displayed now (crossfade and lean included), with the
     * mix back at 0 and the lean dropped into the new start, so nothing visibly moves at this
     * instant. Returns false (and changes nothing) when [target] is already where the crossfade
     * is heading. The caller then animates [mix] to 1.
     */
    fun retarget(target: AccentRoles): Boolean {
        if (target == to) return false
        from = displayed()
        to = target
        neighbour = null
        blend = 0f
        mix = 0f
        return true
    }

    /** Jumps straight to [target] (no crossfade): a reduced-motion snap, or a first frame. */
    fun snapTo(target: AccentRoles) {
        from = target
        to = target
        neighbour = null
        blend = 0f
        mix = 1f
    }

    /** One role as displayed now, without building the whole [AccentRoles]: the per-frame read. */
    internal inline fun role(pick: (AccentRoles) -> Color): Color {
        val settled = mixSrgb(pick(from), pick(to), mix)
        val n = neighbour ?: return settled
        return mixSrgb(settled, pick(n), blend)
    }
}
