package com.stash.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.AlbumSquareCard
import com.stash.data.ytmusic.model.AlbumSummary

/**
 * Horizontal rail of [AlbumSquareCard]s for the Artist Profile "Albums" shelf.
 *
 * 12dp horizontal content padding and 8dp item spacing matches the spec §8.4
 * visual grid. Each card's tap forwards the [AlbumSummary] to the caller so
 * navigation can compute the correct [com.stash.app.navigation.AlbumDetailRoute].
 */
@Composable
fun AlbumsRow(
    albums: List<AlbumSummary>,
    onClick: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumSquareCard(
                title = album.title,
                artist = album.artist,
                thumbnailUrl = album.thumbnailUrl,
                year = album.year,
                onClick = { onClick(album) },
            )
        }
    }
}
