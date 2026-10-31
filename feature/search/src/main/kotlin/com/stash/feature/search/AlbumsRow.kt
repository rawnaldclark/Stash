package com.stash.feature.search

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.AlbumSquareCard
import com.stash.data.ytmusic.model.AlbumSummary
import kotlinx.coroutines.delay

/**
 * Horizontal rail of [AlbumSquareCard]s for the Artist Profile "Albums" shelf.
 *
 * 12dp horizontal content padding and 8dp item spacing matches the spec §8.4
 * visual grid. Each card's tap forwards the [AlbumSummary] to the caller so
 * navigation can compute the correct [com.stash.app.navigation.AlbumDetailRoute].
 *
 * When [focusIndex] is non-null (the Now Playing "tap track → this album" flow
 * landed here), the rail scrolls that card into view and briefly rings it so
 * the user sees which release they came from. Null = normal rail, no scroll.
 * [SinglesRow] delegates here, so this logic serves both shelves.
 *
 * Once per arrival: [focusShown] says it has been shown, [onFocusShown] is told
 * when it is. The caller keeps that flag, because this rail is composed afresh
 * on Back from an album and when it scrolls off and back, and each time it
 * would jump back to the card the user had already scrolled away from.
 */
@Composable
fun AlbumsRow(
    albums: List<AlbumSummary>,
    onClick: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
    focusIndex: Int? = null,
    focusShown: Boolean = false,
    onFocusShown: () -> Unit = {},
) {
    val rowState = rememberLazyListState()
    var highlighted by remember { mutableStateOf(false) }
    // focusShown is no key: turning it on mid-ring would restart this and cut the ring short.
    LaunchedEffect(focusIndex, albums) {
        val i = focusIndex ?: return@LaunchedEffect
        if (focusShown) return@LaunchedEffect
        if (i in albums.indices) {
            rowState.animateScrollToItem(i)
            onFocusShown()
            highlighted = true
            try {
                delay(1500)
            } finally {
                // New albums mid-ring restart this, and the restart returns early: the ring must not stay on.
                highlighted = false
            }
        }
    }
    LazyRow(
        state = rowState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(albums, key = { _, album -> album.id }) { index, album ->
            val ring = if (highlighted && index == focusIndex) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            } else {
                Modifier
            }
            AlbumSquareCard(
                title = album.title,
                artist = album.artist,
                thumbnailUrl = album.thumbnailUrl,
                year = album.year,
                onClick = { onClick(album) },
                modifier = ring,
            )
        }
    }
}
