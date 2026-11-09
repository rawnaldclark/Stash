package com.stash.feature.home.shelves

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.ui.components.LocalListenTogetherRole
import com.stash.core.ui.components.LocalPostToCommunity
import com.stash.core.ui.components.PlaylistInfo
import com.stash.core.ui.components.SaveToPlaylistSheet
import com.stash.core.ui.components.SheetOptionRow
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.components.sheetBarsFollow
import com.stash.core.ui.theme.StashTheme
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary
import com.stash.feature.home.R

/**
 * The app's song sheet (core:ui `TrackOptionsSheet`) for a long-pressed "Because you like" card, wired the way a
 * Search result's actions are ([HomeShelvesViewModel] through `TrackActionsDelegate`): Play next, Add to queue, Start
 * radio, Save to playlist (then the playlist picker) and Download. Hosted as Your record's song menu is
 * (`RecordSongMenu`). Not here: Post to Community (the song may have no row yet), Share (its links need ids a card
 * doesn't carry), View album (a card names its album but has no id for it), Like (Search's songs have none either).
 *
 * Both shelf sheets open fully expanded ([sheetOpensFully]) and scroll when taller than the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfSongSheet(song: TrackSummary?, viewModel: HomeShelvesViewModel, onDismiss: () -> Unit) {
    var saving by remember { mutableStateOf<TrackSummary?>(null) }
    if (song != null) {
        val surface = StashTheme.extendedColors.elevatedSurface
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetOpensFully(),
            containerColor = surface,
            properties = sheetBarsFollow(surface),
        ) {
            CompositionLocalProvider(LocalPostToCommunity provides null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrackOptionsSheet(
                        track = song.toStreamTrack(),
                        onPlayNext = { viewModel.playNext(song); onDismiss() },
                        onAddToQueue = { viewModel.addToQueue(song); onDismiss() },
                        onStartRadio = { viewModel.startRadio(song); onDismiss() },
                        onSaveToPlaylist = { saving = song; onDismiss() },
                        onDownload = { viewModel.download(song); onDismiss() },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
    saving?.let { s ->
        val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
        SaveToPlaylistSheet(
            playlists = playlists.map { PlaylistInfo(it.id, it.name, it.trackCount) },
            onSaveToPlaylist = { id ->
                viewModel.saveToPlaylist(s, id)
                saving = null
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAdd(s, name)
                saving = null
            },
            onDismiss = { saving = null },
        )
    }
}

/**
 * A long-pressed "New from your artists" card's sheet, in the song sheet's look (its header and rows) and words: Play,
 * Shuffle, Play Next, Add to Queue, View Album, View Artist. Each closes the sheet at once; the album loads behind it (its
 * page's own load, warmed when the sheet opened) and a failure is a toast. In a Listen Together session a guest has
 * no Play or Shuffle (the host picks what plays), and a guest whose adds go to the host as suggestions has no Play
 * next or Add to queue (an album would be a dozen suggestions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfAlbumSheet(
    album: AlbumSummary?,
    onPlay: (AlbumSummary) -> Unit,
    onShuffle: (AlbumSummary) -> Unit,
    onPlayNext: (AlbumSummary) -> Unit,
    onAddToQueue: (AlbumSummary) -> Unit,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onGoToArtist: (AlbumSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    if (album == null) return
    val surface = StashTheme.extendedColors.elevatedSurface
    val role = LocalListenTogetherRole.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetOpensFully(),
        containerColor = surface,
        properties = sheetBarsFollow(surface),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            AlbumSheetHeader(album)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                thickness = 0.5.dp,
                color = StashTheme.extendedColors.glassBorder,
            )
            Spacer(Modifier.height(8.dp))
            if (!role.isGuest) {
                SheetOptionRow(
                    icon = Icons.Default.PlayArrow,
                    label = stringResource(R.string.home_album_play),
                    onClick = { onDismiss(); onPlay(album) },
                )
                SheetOptionRow(
                    icon = Icons.Default.Shuffle,
                    label = stringResource(R.string.home_album_shuffle),
                    onClick = { onDismiss(); onShuffle(album) },
                )
            }
            if (role.canQueue) {
                SheetOptionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    label = stringResource(R.string.home_album_play_next),
                    onClick = { onDismiss(); onPlayNext(album) },
                )
                SheetOptionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = stringResource(R.string.home_album_add_to_queue),
                    onClick = { onDismiss(); onAddToQueue(album) },
                )
            }
            SheetOptionRow(
                icon = Icons.Default.Album,
                label = stringResource(R.string.home_album_view_album),
                onClick = { onDismiss(); onOpenAlbum(album) },
            )
            SheetOptionRow(
                icon = Icons.Default.Person,
                label = stringResource(R.string.home_album_view_artist),
                onClick = { onDismiss(); onGoToArtist(album) },
            )
        }
    }
}

/**
 * A shelf sheet's state: it opens fully expanded, as the app's other row sheets do (Now Playing's options, the queue,
 * Settings' choices). Material's default stops halfway up the screen first when the content is taller than half of it,
 * which left the album sheet's last rows below the screen's edge, under the navigation bar, until dragged up (Pixel 5,
 * six rows). Fully open, the sheet's own window insets keep its last row above the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun sheetOpensFully() = rememberModalBottomSheetState(skipPartiallyExpanded = true)

/** The album's cover, title and artist, laid out as the song sheet's header (`TrackOptionsSheet`). */
@Composable
private fun AlbumSheetHeader(album: AlbumSummary) {
    val colors = StashTheme.extendedColors
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.glassBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (album.thumbnailUrl != null) {
                AsyncImage(
                    model = ArtUrlUpgrader.upgrade(album.thumbnailUrl),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
