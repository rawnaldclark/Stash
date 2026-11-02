package com.stash.core.ui.ambient

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How far down the page the glow reaches before it is fully gone. */
val ALBUM_GLOW_REACH: Dp = 340.dp

/** Where the glow's brightest point sits, above the page's top edge (so the top edge is lit, not a spot). */
private val ALBUM_GLOW_LIFT: Dp = 48.dp

/** The glow's falloff: full at the centre, about a third at half its reach, gone at its edge. */
private const val ALBUM_GLOW_MID_STOP = 0.5f
private const val ALBUM_GLOW_MID_ALPHA = 0.35f

/**
 * A soft light in the playing album's colour at the top of a page (Home's ambience, D9 2026-11-01):
 * a low-alpha radial glow centred just above the page's top edge, gone [reach] below it.
 *
 * Pure-black safe: the colour is [AccentRoles.glow], whose alpha already keeps the page's text at 7:1
 * (R8), nothing is drawn below [reach], so the rest of an AMOLED page keeps its pixels off, and with
 * nothing playing the glow is transparent and nothing is drawn at all; nor with [enabled] false (Album
 * colours on Neutral: pass [ThemeAccent.followsSong]). Read at draw time from [chrome] (its one glow
 * role, not the whole set), so a song change's crossfade only redraws this layer.
 *
 * Draws behind the node's content, from the node's own top: put it before any status-bar padding so
 * the light starts at the top of the screen, and on a node that does not scroll so the light stays
 * put while the page scrolls under it.
 */
fun Modifier.albumGlow(chrome: AlbumChrome, enabled: Boolean = true, reach: Dp = ALBUM_GLOW_REACH): Modifier {
    if (!enabled) return this
    return drawBehind {
        val glow = chrome.glow
        if (glow.alpha <= 0f) return@drawBehind
        val radius = reach.toPx()
        val center = Offset(size.width / 2f, -ALBUM_GLOW_LIFT.toPx())
        val brush = Brush.radialGradient(
            0f to glow,
            ALBUM_GLOW_MID_STOP to glow.copy(alpha = glow.alpha * ALBUM_GLOW_MID_ALPHA),
            1f to glow.copy(alpha = 0f),
            center = center,
            radius = radius + ALBUM_GLOW_LIFT.toPx(),
        )
        drawRect(brush = brush, size = Size(size.width, minOf(size.height, radius)))
    }
}
