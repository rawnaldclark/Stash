package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * `GapArtMin`), never over a scan; beside it, the credit a page from Discogs asks for.
 *
 * @param pageCount The back cover plus the pages inside (1: the back alone).
 * @param credit Page [index]'s Discogs page when its image comes from Discogs, else null.
 * @param initialPage The page it opens on.
 * @param page Draws page [index]: 0 is the back cover.
 */
@Composable
internal fun AlbumPackage(
    pageCount: Int,
    modifier: Modifier = Modifier,
    credit: (index: Int) -> String? = { null },
    initialPage: Int = 0,
    page: @Composable (index: Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) { pageCount }
    // Turned over on a page the cycle was showing: the back opens on it.
    androidx.compose.runtime.LaunchedEffect(initialPage) {
        pagerState.scrollToPage(initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }
    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 24.dp,
            // The neighbouring pages load before they are swiped to.
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            page(index)
        }
        val discogs = credit(pagerState.currentPage)
        if (pageCount > 1) {
            // Moved to the start while a credit takes the end.
            PageIndicator(
                count = pageCount,
                current = pagerState.currentPage,
                modifier = Modifier
                    .align(if (discogs != null) Alignment.BottomStart else Alignment.BottomCenter)
                    .offset(x = if (discogs != null) 4.dp else 0.dp, y = 10.dp),
            )
        }
        if (discogs != null) {
            DiscogsCredit(
                page = discogs,
                modifier = Modifier
                    .align(if (pageCount > 1) Alignment.BottomEnd else Alignment.BottomCenter)
                    .offset(y = 14.dp),
            )
        }
    }
}

/**
 * "Data provided by Discogs", linked to the Discogs [page] the images come from: the attribution
 * Discogs' API terms ask for wherever its images are shown. Quiet, in the Now Playing ink.
 */
@Composable
internal fun DiscogsCredit(page: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = "Data provided by Discogs",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
        color = npInk().copy(alpha = 0.6f),
        textDecoration = TextDecoration.Underline,
        modifier = modifier
            .clickable { runCatching { uriHandler.openUri(page) } }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
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
