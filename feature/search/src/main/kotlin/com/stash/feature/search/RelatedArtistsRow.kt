package com.stash.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.ArtistAvatarCard
import com.stash.data.ytmusic.model.ArtistSummary

/**
 * Horizontal rail of [ArtistAvatarCard]s for the "Fans also like" shelf.
 *
 * Matches [AlbumsRow]'s visual grid: 12dp horizontal content padding and
 * 8dp item spacing. Tapping a card navigates to that artist's profile —
 * same screen recursed, which is why the NavHost wires
 * [com.stash.app.navigation.SearchArtistRoute] to itself on
 * `onNavigateToArtist`.
 */
@Composable
fun RelatedArtistsRow(
    artists: List<ArtistSummary>,
    onClick: (ArtistSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(artists, key = { it.id }) { artist ->
            ArtistAvatarCard(
                name = artist.name,
                avatarUrl = artist.avatarUrl,
                onClick = { onClick(artist) },
            )
        }
    }
}
