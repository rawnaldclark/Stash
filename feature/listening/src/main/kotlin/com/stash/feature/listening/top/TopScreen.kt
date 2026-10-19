package com.stash.feature.listening.top

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.CrispChipRow
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.listening.ListeningLabels
import com.stash.feature.listening.stats.ListeningInsights
import com.stash.feature.listening.ui.ArtImage
import com.stash.feature.listening.ui.ArtistGlyph
import com.stash.feature.listening.ui.CircleArt
import com.stash.feature.listening.ui.EstimatedFootnote
import com.stash.feature.listening.ui.genreCredit
import com.stash.feature.listening.ui.genresThinMessage
import com.stash.feature.listening.ui.GenreBar
import com.stash.feature.listening.ui.genreFractions
import com.stash.feature.listening.ui.Gutter
import com.stash.feature.listening.ui.ListeningTopBar
import com.stash.feature.listening.ui.RangeChips
import com.stash.feature.listening.ui.RankedRow
import com.stash.feature.listening.ui.RefreshTodayEffect
import com.stash.feature.listening.ui.SquareArt

/**
 * Top (DESIGN.md Screen 2): the range's songs, artists or albums, ranked (up to 100), each with
 * plays and listening time. Songs play from the top, shuffled, or from a tapped song; an artist
 * opens its page, an album its album page.
 */
@Composable
fun TopScreen(
    onBack: () -> Unit,
    onOpenArtist: (name: String) -> Unit,
    onOpenAlbum: (album: String, artist: String) -> Unit,
    viewModel: TopViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val nowPlayingId by viewModel.nowPlayingTrackId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    RefreshTodayEffect(viewModel, viewModel::refreshToday)
    // A new list starts at its top.
    LaunchedEffect(kind, range) { listState.scrollToItem(0) }
    val s = state

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ListeningTopBar(title = "Top ${kind.label.lowercase()}", onBack = onBack)
        CrispChipRow(
            chips = TopKind.entries.map { it.label },
            selected = kind.label,
            onSelect = { label -> TopKind.entries.firstOrNull { it.label == label }?.let(viewModel::selectKind) },
        )
        RangeChips(selected = range, onSelect = viewModel::selectRange)
        Text(
            text = s.days?.let { ListeningLabels.rangeCaption(it.fromDay, it.toDay, s.today) }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = StashTheme.extendedColors.textTertiary,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp)),
        ) {
            val stale = s.kind != kind || s.range != range
            if (s.isEmpty && !stale) {
                item(key = "empty") {
                    Text(
                        text = "Nothing heard in this period yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 48.dp),
                    )
                }
                return@LazyColumn
            }
            when (s.kind) {
                TopKind.SONGS -> {
                    if (s.songs.isNotEmpty()) {
                        item(key = "actions") { PlayShuffleRow(onPlay = viewModel::play, onShuffle = viewModel::shuffle) }
                    }
                    itemsIndexed(s.songs, key = { _, r -> "s${r.track.id}" }) { i, row ->
                        RankedRow(
                            rank = i + 1,
                            title = row.track.title,
                            subtitle = row.track.artist,
                            plays = ListeningLabels.plays(row.plays),
                            time = row.listenedMs.takeIf { it > 0 }?.let(ListeningLabels::listenTime),
                            onClick = { viewModel.playFrom(row.track.id) },
                            highlighted = row.track.id == nowPlayingId,
                            art = { ArtImage(row.track.albumArtPath, row.track.albumArtUrl, 48.dp, SquareArt) },
                        )
                    }
                }
                TopKind.ARTISTS -> itemsIndexed(s.artists, key = { _, a -> "a${a.key}" }) { i, artist ->
                    RankedRow(
                        rank = i + 1,
                        title = artist.name,
                        subtitle = if (artist.songs == 1) "1 song" else "${artist.songs} songs",
                        plays = ListeningLabels.plays(artist.plays),
                        time = artist.listenedMs.takeIf { it > 0 }?.let(ListeningLabels::listenTime),
                        onClick = { onOpenArtist(artist.name) },
                        art = {
                            ArtImage(
                                path = if (artist.photoUrl != null) null else artist.artPath,
                                url = artist.photoUrl ?: artist.artUrl,
                                size = 48.dp,
                                shape = CircleArt,
                                placeholder = ArtistGlyph,
                            )
                        },
                    )
                }
                TopKind.ALBUMS -> itemsIndexed(s.albums, key = { _, a -> "l" + ListeningInsights.albumKey(a.album, a.artist) }) { i, album ->
                    RankedRow(
                        rank = i + 1,
                        title = album.album,
                        subtitle = album.artist,
                        plays = ListeningLabels.plays(album.plays),
                        time = album.listenedMs.takeIf { it > 0 }?.let(ListeningLabels::listenTime),
                        onClick = { onOpenAlbum(album.album, album.artist) },
                        art = { ArtImage(album.albumArtPath, album.albumArtUrl, 48.dp, SquareArt) },
                    )
                }
                TopKind.GENRES -> {
                    if (s.genresTooThin) {
                        item(key = "genres_thin") { GenresComingIn(genresThinMessage(s.genreCoverage, s.genresCached)) }
                    } else {
                        val fractions = genreFractions(s.genres.map { it.genre })
                        itemsIndexed(s.genres, key = { _, g -> "g${g.genre.key}" }) { i, g ->
                            GenreRow(rank = i + 1, genre = g, fraction = fractions[i])
                        }
                        item(key = "genres_credit") {
                            Text(
                                text = genreCredit(s.genreCoverage),
                                style = MaterialTheme.typography.bodySmall,
                                color = StashTheme.extendedColors.textTertiary,
                                modifier = Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 12.dp),
                            )
                        }
                    }
                }
            }
            if (s.estimated && !s.isEmpty) item(key = "footnote") { EstimatedFootnote() }
        }
    }
}

/**
 * One ranked genre: the rank numeral (as on the other lists), the name and percent over a bar,
 * and the artists that give it the most listening time. Not tappable: there is no genre page yet.
 */
@Composable
private fun GenreRow(rank: Int, genre: TopGenreUi, fraction: Float) {
    val colors = StashTheme.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            color = if (rank == 1) MaterialTheme.colorScheme.primary else colors.textTertiary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(34.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            GenreBar(
                genre = genre.genre,
                fraction = fraction,
                color = if (rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            if (genre.artists.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = genre.artists.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Genres known for too little of the range to rank them: say so ([genresThinMessage]). */
@Composable
private fun GenresComingIn(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter + 8.dp, vertical = 48.dp),
    )
}

/** Play and Shuffle, side by side, half the row each (as on Library's playlist pages). */
@Composable
private fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActionButton("Play", Icons.Rounded.PlayArrow, filled = true, onClick = onPlay, modifier = Modifier.weight(1f))
        ActionButton("Shuffle", Icons.Rounded.Shuffle, filled = false, onClick = onShuffle, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StashTheme.extendedColors
    val content: Color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = if (filled) MaterialTheme.colorScheme.primary else colors.glassBackground,
        border = if (filled) null else BorderStroke(1.dp, colors.glassBorderBright),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}
