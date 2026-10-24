package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** The furthest a page zooms in place: enough to read a booklet's small print at 1200px. */
private const val MAX_ZOOM = 4f

/** Up to this many pages the indicator is a row of dots; past it, a slim track with the page's place on it. */
private const val MAX_DOTS = 10

/**
 * The back of the album held in the hand: its cover and then the pages inside (booklet, gatefold
 * panels, inner sleeve), turned with a sideways swipe in the art's own place, and two fingers to
 * zoom into one (a zoomed page pans with one finger and holds the pager still). A tap still turns
 * the cover back over, and double-tapping a side still seeks: those are the art's
 * ([SwipeableArt]), which takes the song swipe away while the back is up.
 *
 * Where the reader is shows just under the art, in the gap above the title (at least 16dp:
 * `GapArtMin`), never over a scan.
 *
 * @param pageCount The back cover plus the pages inside.
 * @param page Draws page [index]: 0 is the back cover.
 */
@Composable
internal fun AlbumPackage(
    pageCount: Int,
    modifier: Modifier = Modifier,
    page: @Composable (index: Int) -> Unit,
) {
    val pagerState = rememberPagerState { pageCount }
    // Whether each page is zoomed in: a zoomed page takes the drag to pan, so the pager holds still.
    val zoomed = remember { mutableStateMapOf<Int, Boolean>() }
    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = zoomed[pagerState.currentPage] != true,
            pageSpacing = 24.dp,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            ZoomableInPlace(onZoomedChange = { zoomed[index] = it }) { page(index) }
        }
        if (pageCount > 1) {
            PageIndicator(
                count = pageCount,
                current = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 10.dp),
            )
        }
    }
}

/**
 * [content], zoomable in place: two fingers zoom it (up to [MAX_ZOOM]) about the middle and, once
 * zoomed, one finger pans it within its edges, clipped to its box so it never spills over the title.
 * At rest it leaves single-finger drags alone, for the pager to turn the page and the art to flip.
 */
@Composable
private fun ZoomableInPlace(onZoomedChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = bounds.width * (s - 1f) / 2f
        val maxY = bounds.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val zoomedIn = scale > 1f
    LaunchedEffect(zoomedIn) { onZoomedChange(zoomedIn) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { bounds = it }
            // Clipped only while zoomed: at rest the page's glow may reach past its box.
            .then(if (zoomedIn) Modifier.clipToBounds() else Modifier)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            offset = if (newScale == 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Where the reader is among [count] pages: dots up to [MAX_DOTS], else a slim track with the
 * page's place on it. In the Now Playing ink, quiet. Hidden from TalkBack: each page says its own
 * number.
 */
@Composable
private fun PageIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    val ink = npInk()
    if (count <= MAX_DOTS) {
        Row(
            modifier = modifier.clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { i ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(ink.copy(alpha = if (i == current) 0.9f else 0.3f), CircleShape),
                )
            }
        }
    } else {
        val track = 72.dp
        val thumb = (track / count).coerceAtLeast(8.dp)
        Box(
            modifier = modifier
                .clearAndSetSemantics { }
                .width(track)
                .height(4.dp)
                .background(ink.copy(alpha = 0.25f), RoundedCornerShape(50)),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = (track - thumb) * (current.toFloat() / (count - 1)))
                    .width(thumb)
                    .height(4.dp)
                    .background(ink.copy(alpha = 0.9f), RoundedCornerShape(50)),
            )
        }
    }
}
