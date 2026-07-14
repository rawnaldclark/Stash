package com.stash.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/** Premium Crisp motion tokens (spec §3.5). Named so screens never use ad-hoc values. */
object StashMotion {
    const val DUR_SHORT_MS = 180
    const val DUR_MED_MS = 260
    const val PRESS_SCALE = 0.97f
    const val SECTION_STAGGER_MS = 40
    // Material standard curves.
    val EaseEnter: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)  // FastOutSlowIn (decelerate)
    val EaseExit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)   // FastOutLinearIn (accelerate)
}
