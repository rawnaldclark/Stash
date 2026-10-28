package com.stash.feature.record.mixes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.model.Track
import com.stash.core.model.weblink.SongIdentity
import com.stash.core.model.weblink.SongRef
import com.stash.core.ui.components.LocalPostToCommunity
import com.stash.core.ui.components.PlaylistInfo
import com.stash.core.ui.components.SaveToPlaylistSheet
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatDuration
import com.stash.feature.record.R
import com.stash.feature.record.bridge.RecordSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A song of the record as the likes know it: your own row when you have it, else its words (the web's item key). */
internal fun MixSong.identity(): SongIdentity = lib ?: SongRef(title, artist)

/** A mix's song as the record plays it (its words, Spotify id, your row, the cover found). */
internal fun MixSong.recordSong(cover: String?): RecordSong =
    RecordSong(title = title, artist = artist, album = album, sid = sid, trackId = lib?.trackId, cover = cover)

/**
 * Which hearts are filled (the web's `L.isLiked`: the songs liked in Stash, [RecordMixes.likes]), with a tap shown at once: a
 * tap's answer is kept until the likes say the same (so a heart never blinks back while the like is written), and dropped if
 * the like couldn't be written (the heart goes back, and the screen says so).
 */
internal class RowLikes(private val scope: CoroutineScope, private val actions: RecordSongActions, likes: Flow<Have>) {
    /** Taps on their way, by the song's item key: the song and the heart it asked for. */
    private val pending = MutableStateFlow<Map<String, Pair<SongIdentity, Boolean>>>(emptyMap())

    @Volatile private var index: Have? = null

    /** What the hearts show now. */
    class Hearts(private val index: Have?, private val pending: Map<String, Pair<SongIdentity, Boolean>>) {
        fun liked(s: MixSong): Boolean = pending[s.key]?.second ?: (index?.has(s.identity()) == true)
    }

    val hearts: Flow<Hearts> = combine(
        likes.onEach { idx ->
            index = idx
            // A tap the likes now agree with is the likes' again
            pending.update { p -> p.filterValues { (ref, want) -> idx.has(ref) != want } }
        },
        pending,
    ) { idx, p -> Hearts(idx, p) }

    /** Like or unlike [s] (as its heart shows now, [liked]), its cover kept on a row made for it. */
    fun toggle(s: MixSong, liked: Boolean, cover: String?) {
        val ref = s.identity()
        val want = !liked
        pending.update { it + (s.key to (ref to want)) }
        scope.launch {
            val ok = if (want) {
                actions.like(s.recordSong(cover)).await()
            } else {
                // Every row of the song liked in Stash (one, almost always)
                actions.unlike(listOfNotNull(index?.find(ref)?.trackId ?: s.lib?.trackId)).await()
            }
            if (!ok) pending.update { it - s.key }
        }
    }
}

/** A row's colours: the record's dark ground (the Mixes tab), or the app's (a mix's page). */
internal data class RowColors(val title: Color, val sub: Color, val faint: Color, val square: Color)

internal object RowColorsOf {
    /** The Mixes tab, on the record's dark ground in both themes. */
    val Record = RowColors(title = StashTextPrimary, sub = StashTextSecondary, faint = StashTextSecondary.copy(alpha = 0.7f), square = Color.White.copy(alpha = 0.08f))

    @Composable
    fun app(): RowColors = RowColors(
        title = MaterialTheme.colorScheme.onSurface,
        sub = MaterialTheme.colorScheme.onSurfaceVariant,
        faint = StashTheme.extendedColors.textTertiary,
        square = StashTheme.extendedColors.elevatedSurface,
    )
}

/** Where a row's square is when it has no cover: the app's note, or the song's own colours with a play mark (the web's "Not in your Stash yet"). */
internal enum class RowSquare { NOTE, TINT_PLAY, NONE }

/**
 * One song of the record (stash-player `TrackRow.svelte`): a tap plays, a long press opens the song menu ([onMenu]), the heart
 * likes it in Stash; its number ([number], or none), its square ([square]: the cover found, else a placeholder), its words, its
 * length when your own row knows it. Laid out as the app's detail rows are (`DetailTrackRow`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecordSongRow(
    song: MixSong,
    cover: String?,
    number: Int?,
    square: RowSquare,
    liked: Boolean,
    colors: RowColors,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = 20.dp,
) {
    val more = stringResource(R.string.record_song_more, song.title)
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClickLabel = more, onLongClick = onMenu)
            .padding(start = gutter, end = gutter - 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text("$number", style = MaterialTheme.typography.bodySmall, color = colors.faint, modifier = Modifier.width(28.dp))
        }
        if (square != RowSquare.NONE) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).then(
                    if (cover == null && square == RowSquare.TINT_PLAY) Modifier.background(hueBrush(HistoryMixes.hueOf(song.title))) else Modifier.background(colors.square),
                ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    cover != null -> AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    square == RowSquare.TINT_PLAY -> Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
                    else -> Icon(Icons.Default.MusicNote, contentDescription = null, tint = colors.faint, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, color = colors.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (song.artist.isNotBlank()) {
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // A history song alone has no length yet (the web's row shows none)
        val length = song.lib?.durationMs ?: 0L
        if (length > 0) {
            Spacer(Modifier.width(8.dp))
            Text(formatDuration(length), style = MaterialTheme.typography.bodySmall, color = colors.faint)
        }
        RecordLikeButton(liked, onLike, unlikedTint = colors.sub)
    }
}

/** The heart (LikeButton.svelte): filled red when the song is liked in Stash, a toggle, read out "Like" or "Unlike". */
@Composable
internal fun RecordLikeButton(liked: Boolean, onTap: () -> Unit, unlikedTint: Color, modifier: Modifier = Modifier) {
    val label = stringResource(if (liked) R.string.record_song_unlike else R.string.record_song_like)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onTap, role = Role.Button)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = null,
            tint = if (liked) MaterialTheme.colorScheme.error else unlikedTint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A song whose menu is open: what the record plays it as, and how the menu's header shows it. */
internal data class MenuSong(val song: RecordSong, val shown: Track)

/** [song] for its menu: its words and cover (your own row's id when you have it; else one no row has, never a scanned file's). */
internal fun menuSong(song: MixSong, cover: String?, i: Int): MenuSong = MenuSong(
    song = song.recordSong(cover),
    shown = Track(
        id = song.lib?.trackId ?: -(i + 1L),
        title = song.title,
        artist = song.artist,
        album = song.album.orEmpty(),
        durationMs = song.lib?.durationMs ?: 0,
        albumArtUrl = cover,
        dateAdded = 0,
    ),
)

/**
 * The app's song menu (core:ui `TrackOptionsSheet`, as History's rows open it) for a song of the record, wired the way a Search
 * result's is: Play next, Add to queue, Start radio and Save to playlist each find or make the song's row first
 * ([RecordSongActions]). Not here: Post to Community (it would post a song that may have no row yet), Share (its links need the
 * song's ids, which a history song doesn't have), Download (a record's song is never downloaded unasked), Delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordSongMenu(open: MenuSong?, actions: RecordSongActions, onDismiss: () -> Unit) {
    var saving by remember { mutableStateOf<MenuSong?>(null) }
    if (open != null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(),
            containerColor = StashTheme.extendedColors.elevatedSurface,
        ) {
            CompositionLocalProvider(LocalPostToCommunity provides null) {
                TrackOptionsSheet(
                    track = open.shown,
                    onPlayNext = { actions.playNext(open.song); onDismiss() },
                    onAddToQueue = { actions.addToQueue(open.song); onDismiss() },
                    onStartRadio = { actions.startRadio(open.song); onDismiss() },
                    onSaveToPlaylist = { saving = open; onDismiss() },
                    onDismiss = onDismiss,
                )
            }
        }
    }
    saving?.let { s ->
        val playlists by actions.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
        SaveToPlaylistSheet(
            playlists = playlists.map { PlaylistInfo(it.id, it.name, it.trackCount) },
            onSaveToPlaylist = { id ->
                actions.addToPlaylist(s.song, id, playlists.firstOrNull { it.id == id }?.name.orEmpty())
                saving = null
            },
            onCreatePlaylist = { name ->
                actions.createPlaylistWith(s.song, name)
                saving = null
            },
            onDismiss = { saving = null },
        )
    }
}
