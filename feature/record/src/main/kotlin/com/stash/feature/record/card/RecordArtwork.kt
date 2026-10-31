package com.stash.feature.record.card

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.theme.StashCyanDark
import com.stash.core.ui.theme.StashElevatedSurface
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashPurpleDark

/** One of the sleeve's albums: its cover when there is one, else a placeholder coloured from its name. */
internal data class SleeveTile(val seed: String, val cover: String?)

/** The sleeve's side (the web's phone size, `--s: 96px`); the rest follows from it. */
internal val SleeveSize = 96.dp

/** The artwork's width: the sleeve and the disc out of it (the web's 150 px). */
internal val RecordArtWidth = 150.dp

/** How far the disc comes out of the sleeve: far enough for its label to show (the web's 58 px on a phone). */
private val DiscOut = 58.dp

/** The web's `--ease-out-expo`. */
private val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

private val DiscBlack = Color(0xFF0B0A10)
private val Groove = Color(0xFF17141F)
private val SleevePurple = Color(0xFF2E1B6E)

/**
 * The record graphic (stash-player RecordArt.svelte): a disc with grooves that slides out of its sleeve with a turn, once
 * (never with reduced motion). The sleeve is the albums played most ([sleeve]: a 2×2 with four, else the first) and the
 * label the most played album's cover ([label]); with [sleeve] null (the invitation) a plain Stash-purple sleeve and label.
 * Decorative: the words beside it say what it is.
 */
@Composable
internal fun RecordArtwork(sleeve: List<SleeveTile>?, label: String?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val reduced = rememberReducedMotion()
    // Out of its sleeve once a Home: scrolling the card away and back doesn't play it again
    var played by rememberSaveable { mutableStateOf(false) }
    val out = remember { Animatable(if (played || reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played && !reduced) out.animateTo(1f, tween(DISC_MS, easing = EaseOutExpo))
        played = true
    }
    Box(modifier.size(width = RecordArtWidth, height = SleeveSize)) {
        val disc = SleeveSize * 0.944f
        Box(
            Modifier
                .offset(x = SleeveSize * 0.055f, y = SleeveSize * 0.028f)
                .size(disc)
                .zIndex(1f)
                .graphicsLayer {
                    val p = out.value
                    translationX = with(density) { (8.dp + (DiscOut - 8.dp) * p).toPx() }
                    rotationZ = -60f + 210f * p
                }
                .shadow(10.dp, CircleShape)
                .clip(CircleShape),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(DiscBlack)
                // The grooves: rings 1.5 dp wide every 3 dp from the label out (the web's repeating radial gradient)
                val r = size.minDimension / 2
                val step = 3.dp.toPx()
                val ring = 1.5.dp.toPx()
                var rr = r * 0.38f + ring
                while (rr < r - ring / 2) {
                    drawCircle(Groove, radius = rr, style = Stroke(width = ring))
                    rr += step
                }
                // The light catching it: two soft wedges (a conic gradient from 20° on the web)
                rotate(-70f) {
                    drawCircle(
                        Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.08f to Color.Transparent,
                            0.12f to Color.White.copy(alpha = 0.16f),
                            0.18f to Color.Transparent,
                            0.5f to Color.Transparent,
                            0.62f to Color.White.copy(alpha = 0.10f),
                            0.68f to Color.Transparent,
                            1f to Color.Transparent,
                            center = center,
                        ),
                    )
                }
                drawCircle(Color.White.copy(alpha = 0.06f), radius = r - 0.5.dp.toPx(), style = Stroke(width = 1.dp.toPx()))
            }
            // The label: 38 % of the disc (the web's inset 31 %), its cover, and the spindle hole
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(disc * 0.38f)
                    .clip(CircleShape)
                    .background(StashPurple)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.25f), Color.Transparent),
                            center = Offset(with(density) { (disc * 0.38f * 0.35f).toPx() }, with(density) { (disc * 0.38f * 0.30f).toPx() }),
                            radius = with(density) { (disc * 0.38f * 0.6f).toPx() },
                        ),
                    ),
            ) {
                if (label != null) {
                    AsyncImage(model = label, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(DiscBlack)
                        .border(0.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                )
            }
        }
        val sleeveShape = RoundedCornerShape(5.dp)
        Box(
            Modifier
                .size(SleeveSize)
                .zIndex(2f)
                .shadow(12.dp, sleeveShape)
                .clip(sleeveShape),
        ) {
            if (sleeve == null) BlankSleeve() else Mosaic(sleeve)
            Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.08f), sleeveShape))
        }
    }
}

/** Nothing pressed yet: Stash's purple, the record's ring showing through it. */
@Composable
private fun BlankSleeve() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(StashPurpleDark, SleevePurple)))
            .background(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(with(LocalDensity.current) { (SleeveSize * 0.72f).toPx() }, with(LocalDensity.current) { (SleeveSize * 0.26f).toPx() }),
                    radius = with(LocalDensity.current) { (SleeveSize * 0.55f).toPx() },
                ),
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(SleeveSize * 0.12f)
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
        )
    }
}

/** The web's Tile: a 2×2 of four albums, else the first one's cover, else a placeholder. */
@Composable
private fun Mosaic(tiles: List<SleeveTile>) {
    when {
        tiles.size >= 4 -> Column(Modifier.fillMaxSize()) {
            for (row in 0..1) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (col in 0..1) Tile(tiles[row * 2 + col], Modifier.weight(1f).fillMaxSize())
                }
            }
        }
        tiles.isNotEmpty() -> Tile(tiles[0], Modifier.fillMaxSize())
        else -> Placeholder("Your record", Modifier.fillMaxSize())
    }
}

@Composable
private fun Tile(tile: SleeveTile, modifier: Modifier) {
    Box(modifier) {
        Placeholder(tile.seed, Modifier.fillMaxSize())
        if (tile.cover != null) {
            AsyncImage(model = tile.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A cover not found (yet): a gradient coloured from [seed], so the same album keeps the same colours. */
@Composable
internal fun Placeholder(seed: String, modifier: Modifier = Modifier) {
    val tint = SEEDS[Math.floorMod(seed.hashCode(), SEEDS.size)]
    Box(modifier.background(Brush.linearGradient(listOf(tint, StashElevatedSurface))))
}

private val SEEDS = listOf(StashPurpleDark, StashCyanDark, SleevePurple, Color(0xFF5B3FA8), Color(0xFF1E4B66), Color(0xFF3A2466))

private const val DISC_MS = 1100
