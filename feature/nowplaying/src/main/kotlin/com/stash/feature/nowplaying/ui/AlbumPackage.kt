package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Up to this many pages the indicator is a row of dots; past it, a slim track with the page's place on it. */
private const val MAX_DOTS = 10

/**
 * The back of the album held in the hand: its cover and then the pages inside (booklet, gatefold
 * panels, inner sleeve), turned with a sideways swipe in the art's own place; two fingers lift a
 * page off the art to the whole screen ([LiftablePage], drawn by each page). A tap still turns
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
    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 24.dp,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            page(index)
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
