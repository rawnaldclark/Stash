package com.stash.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stash.data.ytmusic.model.AlbumSummary

/**
 * Horizontal rail of [com.stash.core.ui.components.AlbumSquareCard]s for
 * the Artist Profile "Singles & EPs" shelf.
 *
 * Identical visual to [AlbumsRow] — delegates to it rather than duplicating
 * the LazyRow body so a layout tweak to the albums rail automatically
 * applies to singles too.
 */
@Composable
fun SinglesRow(
    singles: List<AlbumSummary>,
    onClick: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    AlbumsRow(
        albums = singles,
        onClick = onClick,
        modifier = modifier,
    )
}
