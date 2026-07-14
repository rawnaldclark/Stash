package com.stash.core.ui.components.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.composed
import com.stash.core.ui.theme.StashMotion

/** Scales content to StashMotion.PRESS_SCALE while pressed via [interactionSource]. */
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) StashMotion.PRESS_SCALE else 1f,
        animationSpec = tween(StashMotion.DUR_SHORT_MS, easing = StashMotion.EaseEnter),
        label = "pressScale",
    )
    this.scale(scale)
}
