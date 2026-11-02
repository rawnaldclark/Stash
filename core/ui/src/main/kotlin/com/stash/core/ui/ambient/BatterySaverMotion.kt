package com.stash.core.ui.ambient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Android's Battery Saver is on (`com.stash.core.common.power.BatterySaver`), provided once at the
 * app's root from the app-wide StateFlow. [rememberLoopsStill] reads it (D19).
 *
 * `compositionLocalOf`, not static: the switch flips while the app is open, and only the composables
 * that read it should recompose. False outside the app's root (previews, tests).
 */
val LocalBatterySaverOn = compositionLocalOf { false }

/**
 * Whether Stash's CONTINUOUS loops stand still: the ambient orbits, the radar sweep, marquees, the
 * full-art cycle (its pans and page fades), lyric springs and breaks, shimmers, pulses, the Home
 * ticker. They stop with the system's "Remove animations" ([rememberReducedMotion]) or while
 * Android's Battery Saver is on: a loop draws a frame every refresh for as long as it is on screen,
 * which is what costs battery with the screen on.
 *
 * One-shot transitions (the player sheet's slide, swipe-to-skip, the art flip, the tab bar, the
 * album colour's crossfade) read [rememberReducedMotion] instead and keep animating under Battery
 * Saver: they last a moment, and a player that snaps everywhere at 15 % feels broken.
 */
@Composable
fun rememberLoopsStill(): Boolean = motionStill(rememberReducedMotion(), LocalBatterySaverOn.current)

/** [rememberLoopsStill]'s rule: either switch stops the loops. */
internal fun motionStill(removeAnimations: Boolean, batterySaverOn: Boolean): Boolean =
    removeAnimations || batterySaverOn
