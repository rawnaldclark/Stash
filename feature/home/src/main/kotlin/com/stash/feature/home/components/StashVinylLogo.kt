package com.stash.feature.home.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.feature.home.R

/**
 * Spinning vinyl record logo. Just the record — no background.
 * The record PNG is pre-rendered from CSS with Righteous font "S" label.
 * Rotates continuously at 6 seconds per revolution.
 */
@Composable
fun StashVinylLogo(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl-spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    Image(
        painter = painterResource(R.drawable.vinyl_record),
        contentDescription = "Stash logo",
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation },
        contentScale = ContentScale.Fit,
    )
}
