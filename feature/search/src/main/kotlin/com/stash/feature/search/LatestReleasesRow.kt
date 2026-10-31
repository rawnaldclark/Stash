package com.stash.feature.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.AlbumSquareCard
import com.stash.data.ytmusic.model.AlbumSummary

/**
 * Horizontal rail of [AlbumSquareCard]s for the Artist Profile "Latest releases" row:
 * the artist's newest albums, singles and EPs together, newest first ([latestReleases]).
 *
 * The same rail as [AlbumsRow] (12dp edges, 8dp between cards), so the rows line up
 * card for card. A tap hands back the release exactly as its shelf holds it, so it
 * opens the album page the way those rows do.
 */
@Composable
fun LatestReleasesRow(
    releases: List<LatestRelease>,
    onClick: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(releases, key = { it.album.id }) { release ->
            // The year alone, a Qobuz album's full date included, so the cards read alike.
            val year = release.year.toString()
            val kind = release.kind?.let { stringResource(it.label) }
            AlbumSquareCard(
                title = release.album.title,
                // The card's second line reads "2021 • Single". The row mixes albums with
                // singles, so it says which; on the artist's own page their name would only
                // repeat (Home's Qobuz rows put a track count there for the same reason).
                // A card with no type (a profile cached before types were kept, or a word
                // YouTube adds later) shows the year alone, not a dangling "2021 •".
                artist = kind ?: year,
                thumbnailUrl = release.album.thumbnailUrl,
                year = if (kind != null) year else null,
                onClick = { onClick(release.album) },
            )
        }
    }
}

/** How a kind of release reads on its card. */
@get:StringRes
private val ReleaseKind.label: Int
    get() = when (this) {
        ReleaseKind.ALBUM -> R.string.artist_release_album
        ReleaseKind.SINGLE -> R.string.artist_release_single
        ReleaseKind.EP -> R.string.artist_release_ep
        ReleaseKind.MIXTAPE -> R.string.artist_release_mixtape
        ReleaseKind.COMPILATION -> R.string.artist_release_compilation
    }
