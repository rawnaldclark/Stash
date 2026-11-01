package com.stash.core.ui.ambient

import androidx.compose.runtime.compositionLocalOf

/**
 * Android's Battery Saver is on (`com.stash.core.common.power.BatterySaver`), provided once at the
 * app's root from the app-wide StateFlow. [rememberReducedMotion] reads it, so every animation that
 * already honours the system's "Remove animations" also stands still under Battery Saver (D19).
 *
 * `compositionLocalOf`, not static: the switch flips while the app is open, and only the composables
 * that read it should recompose. False outside the app's root (previews, tests).
 */
val LocalBatterySaverOn = compositionLocalOf { false }

/**
 * Whether motion stands still: the system's "Remove animations" (animator duration scale 0) or
 * Android's Battery Saver. Continuous loops (the ambient orbits, full-art pans, marquees, lyric
 * springs) stop; one-shot transitions snap, as they do with animations removed.
 */
internal fun motionStill(removeAnimations: Boolean, batterySaverOn: Boolean): Boolean =
    removeAnimations || batterySaverOn
