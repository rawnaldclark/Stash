// core/ui/src/main/kotlin/com/stash/core/ui/components/ShimmerPlaceholder.kt
package com.stash.core.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape

@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    baseAlpha: Float = 0.06f,
    highlightAlpha: Float = 0.12f,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = baseAlpha,
        targetValue = highlightAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = alpha)),
    )
}
