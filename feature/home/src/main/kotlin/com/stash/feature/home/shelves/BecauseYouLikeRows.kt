package com.stash.feature.home.shelves

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.home.BecauseRow
import com.stash.core.ui.components.AlbumSquareCard
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.home.R

/** The seed song's cover beside a row's title. */
private val SeedArtSize = 44.dp
private val SeedArtShape = RoundedCornerShape(6.dp)

/**
 * Home's "Because you like ‹song›" rows: each headed by the seed song's cover, a small "Because you like" and the
 * song's title (YouTube Music's own layout for these rows), then its related songs as cards. A tap plays the row
 * from that song ([onPlay] with the row, the song's index and the row's name for Now Playing).
 */
@Composable
internal fun BecauseYouLikeRows(
    rows: List<BecauseRow>,
    onPlay: (row: BecauseRow, index: Int, label: String) -> Unit,
) {
    Column {
        rows.forEach { row ->
            val label = stringResource(R.string.home_because_you_like_song, row.seed.title)
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    // One heading for TalkBack: "Because you like ‹song›"
                    .clearAndSetSemantics {
                        contentDescription = label
                        heading()
                    },
            ) {
                AsyncImage(
                    model = ArtUrlUpgrader.upgrade(row.seed.artUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(SeedArtSize)
                        .clip(SeedArtShape)
                        .border(1.dp, StashTheme.extendedColors.glassBorder, SeedArtShape),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.home_because_you_like),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = row.seed.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(row.songs) { index, song ->
                    val description = stringResource(R.string.home_song_card, song.title, song.artist)
                    AlbumSquareCard(
                        title = song.title,
                        artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl,
                        year = null,
                        isLossless = false,
                        onClick = { onPlay(row, index, label) },
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                    )
                }
            }
        }
    }
}
