package com.stash.feature.home.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * An animated spinning vinyl record logo for the Stash app.
 *
 * Renders a 3x3 purple checkerboard background with a dark vinyl disc
 * centered on top. The disc has concentric groove rings, a purple label
 * with a white "S", and a subtle light reflection. The record spins
 * continuously (one full rotation every 6 seconds) while the checkerboard
 * and the "S" letter remain stationary.
 *
 * @param modifier Modifier applied to the outermost container.
 * @param size     Width and height of the square logo. Defaults to 64.dp.
 */
@Composable
fun StashVinylLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    // Infinite rotation animation: 0 -> 360 degrees over 6 seconds, linear.
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl-spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    // Outer container: rounded-corner box with purple gradient background.
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF3B0764), Color(0xFF6D28D9)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // ── Static 3x3 checkerboard ────────────────────────────────
        // Alternating purple squares at 50% opacity on diagonal positions.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellW = this.size.width / 3f
            val cellH = this.size.height / 3f
            val checkerColor = Color(0xFFA78BFA).copy(alpha = 0.5f)

            // Draw squares at positions (col, row): (1,0), (0,1), (2,1), (1,2)
            listOf(
                Offset(cellW, 0f),
                Offset(0f, cellH),
                Offset(2 * cellW, cellH),
                Offset(cellW, 2 * cellH),
            ).forEach { offset ->
                drawRect(
                    color = checkerColor,
                    topLeft = offset,
                    size = Size(cellW, cellH),
                )
            }
        }

        // ── Spinning vinyl record ──────────────────────────────────
        val recordSize = size * 0.63f

        Box(
            modifier = Modifier
                .size(recordSize)
                .graphicsLayer { rotationZ = rotation }
                .clip(CircleShape)
                .background(Color(0xFF151515)),
            contentAlignment = Alignment.Center,
        ) {
            // Groove rings: concentric circles with subtle brightness variation.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val maxRadius = this.size.width / 2f
                val labelRadius = maxRadius * 0.34f

                // Draw groove rings from outer edge down to the label boundary.
                val grooveCount = 10
                for (i in 1..grooveCount) {
                    val fraction = i.toFloat() / (grooveCount + 1)
                    val radius = labelRadius + (maxRadius - labelRadius) * (1f - fraction)
                    val alpha = if (i % 2 == 0) 0.10f else 0.05f
                    drawCircle(
                        color = Color.White,
                        radius = radius,
                        center = center,
                        style = Stroke(width = 0.8f),
                        alpha = alpha,
                    )
                }
            }

            // Light reflection: subtle white gradient across the top-left quadrant.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(this.size.width * 0.7f, this.size.height * 0.7f),
                    ),
                    radius = this.size.width / 2f,
                    center = Offset(this.size.width / 2f, this.size.height / 2f),
                )
            }

            // ── Purple label center ────────────────────────────────
            Box(
                modifier = Modifier
                    .size(recordSize * 0.34f)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFA78BFA), Color(0xFF7C3AED)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // "S" letter: counter-rotates so it stays upright while the disc spins.
                Text(
                    text = "S",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    fontSize = (size.value * 0.12f).sp,
                    modifier = Modifier.graphicsLayer { rotationZ = -rotation },
                )
            }
        }
    }
}
