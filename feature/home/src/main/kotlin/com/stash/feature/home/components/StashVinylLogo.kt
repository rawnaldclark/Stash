package com.stash.feature.home.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.feature.home.R

/**
 * Stash vinyl-on-checkerboard logo using pre-rendered CSS PNGs.
 *
 * The checkerboard background is a static PNG rendered from the exact CSS.
 * The vinyl record is a separate transparent PNG that rotates continuously
 * via Compose's hardware-accelerated graphicsLayer animation.
 *
 * This approach gives pixel-perfect CSS rendering (conic gradients, repeating
 * radial gradients) that Compose Canvas cannot reproduce, combined with
 * smooth 60fps rotation animation.
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

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // Static checkerboard background (pre-rendered CSS)
        Image(
            painter = painterResource(R.drawable.vinyl_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // Spinning vinyl record (pre-rendered CSS, transparent background)
        Image(
            painter = painterResource(R.drawable.vinyl_record),
            contentDescription = "Stash logo",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation },
            contentScale = ContentScale.Crop,
        )
    }
}
