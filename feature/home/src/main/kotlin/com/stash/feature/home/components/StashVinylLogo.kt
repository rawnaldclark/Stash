package com.stash.feature.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.feature.home.R

/**
 * Spinning vinyl record logo. Rotates continuously at 6 seconds per revolution.
 *
 * Uses [withFrameMillis] instead of [rememberInfiniteTransition] so the
 * animation runs even when the system's Animator Duration Scale is set to 0x.
 * The frame clock is independent of the animator settings.
 */
@Composable
fun StashVinylLogo(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    var rotation by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastFrame = 0L
        while (true) {
            withFrameMillis { frameTime ->
                if (lastFrame != 0L) {
                    val delta = frameTime - lastFrame
                    // 360 degrees per 6000ms = 0.06 degrees per ms
                    rotation = (rotation + delta * 0.06f) % 360f
                }
                lastFrame = frameTime
            }
        }
    }

    Image(
        painter = painterResource(R.drawable.vinyl_record),
        contentDescription = "Stash logo",
        modifier = modifier
            .size(size)
            .graphicsLayer {
                rotationZ = rotation
                compositingStrategy = CompositingStrategy.Offscreen
            },
        contentScale = ContentScale.Fit,
    )
}
