package com.stash.feature.home.shelves

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.home.BecauseRow
import com.stash.core.data.home.BecauseYouLike
import com.stash.core.data.home.BecauseYouLikeRepository
import com.stash.core.data.home.HomeShownSongs
import com.stash.core.data.home.NewFromArtistsRepository
import com.stash.core.data.prefs.HomeSection
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary
import com.stash.feature.home.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What Home's own streaming shelves show. A list is empty when its section is hidden, and every list is empty in
 * Offline mode (the app's Download mode plays only what's on the phone, and these shelves are streaming content),
 * so Home renders nothing for them there.
 */
data class HomeShelvesState(
    /** "New from your artists": album and single cards, newest first. */
    val newFromArtists: List<AlbumSummary> = emptyList(),
    /** "Because you like ‹song›": each row's songs, none another Home shelf shows ([HomeShownSongs]). */
    val becauseYouLike: List<BecauseRow> = emptyList(),
)

/**
 * Home's own shelves, apart from [com.stash.feature.home.HomeViewModel] (which carries the Discover hero, the synced
 * mixes and the Qobuz rows): "New from your artists" and "Because you like". Each is built by its
 * repository (at most once a day; "Because you like" also after enough listening) and saved to a file.
 *
 * A shelf loads only while it's wanted: Streaming on and its section shown in Settings > Home layout, the rule the
 * Qobuz rows follow (HomeViewModel's discovery flow): a hidden section costs no request. It loads when it becomes
 * wanted (Home first shown, the section turned back on, Offline mode switched off) and on each return to Home (a file
 * read, and for "Because you like" two counts, until a rebuild is due); a shelf no longer wanted stops loading at
 * once. Each shelf loads on its own, so none waits on another. With no network the saved shelf is shown and no
 * build is tried, so a failed attempt (and the wait after it) only comes from a real network.
 *
 * A long press on a card opens its sheet: a "Because you like" song's goes through [TrackActionsDelegate], as a Search
 * result's does (Play next, Add to queue, Start radio, Save to playlist, Download); a "New from your artists" album's
 * loads the album ([AlbumCache], as its page does) and plays, shuffles or queues its songs, or finds its artist.
 */
@HiltViewModel
class HomeShelvesViewModel @Inject constructor(
    newFromArtistsRepository: NewFromArtistsRepository,
    becauseYouLikeRepository: BecauseYouLikeRepository,
    shownSongs: HomeShownSongs,
    private val playerRepository: PlayerRepository,
    streamingPreference: StreamingPreference,
    homeSectionsPreference: HomeSectionsPreference,
    private val connectivity: ConnectivityMonitor,
    private val delegate: TrackActionsDelegate,
    private val albumCache: AlbumCache,
) : ViewModel() {

    private val newFromArtists = Shelf(HomeSection.NEW_FROM_ARTISTS, newFromArtistsRepository::cached, newFromArtistsRepository::shelf)
    private val because = Shelf(HomeSection.BECAUSE_YOU_LIKE, becauseYouLikeRepository::cached, becauseYouLikeRepository::shelf)
    private val shelves = listOf(newFromArtists, because)

    /** The shelf sections Home shows now: none in Offline mode, else the visible ones. */
    private val wanted: StateFlow<Set<HomeSection>> =
        combine(streamingPreference.enabled, homeSectionsPreference.visibleSections) { online, visible ->
            if (online) shelves.mapTo(HashSet()) { it.section }.filterTo(HashSet()) { it in visible } else emptySet()
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val state: StateFlow<HomeShelvesState> = combine(
        wanted,
        newFromArtists.items,
        because.items,
        shownSongs.keys,
    ) { shown, releases, rows, elsewhere ->
        HomeShelvesState(
            newFromArtists = if (HomeSection.NEW_FROM_ARTISTS in shown) releases else emptyList(),
            // The record's mixes are made apart from these rows: their songs leave the rows while they show
            becauseYouLike = if (HomeSection.BECAUSE_YOU_LIKE in shown) {
                BecauseYouLike.without(rows, elsewhere.values.flatten().toSet())
            } else {
                emptyList()
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeShelvesState())

    /** A song sheet's toasts (the delegate's own words: "Added to queue", "Download failed: ..."). */
    val songMessages: Flow<String> = delegate.userMessages

    private val _albumMessages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** An album sheet's toasts, as string resources ("Added album to queue", "Couldn't load this album"). */
    val albumMessages: SharedFlow<Int> = _albumMessages.asSharedFlow()

    /** Your playlists, for a song sheet's Save to playlist. */
    val playlists: Flow<List<Playlist>> = delegate.userPlaylists

    init {
        delegate.bindToScope(viewModelScope)
        viewModelScope.launch {
            var before = emptySet<HomeSection>()
            wanted.collect { now ->
                for (shelf in shelves) {
                    when {
                        // Shown again (or for the first time): fill it
                        shelf.section in now && shelf.section !in before -> shelf.refresh()
                        // Hidden, or Offline mode: stop what it's loading
                        shelf.section !in now -> shelf.stop()
                    }
                }
                before = now
            }
        }
    }

    /** Home is back in view: each wanted shelf is built again if due; otherwise this only reads the saved ones. */
    fun onResume() {
        val now = wanted.value
        shelves.filter { it.section in now }.forEach { it.refresh() }
    }

    /**
     * Plays [row] from its song at [index], streamed (these are songs you mostly don't have), the row as the queue.
     * [label] is the row as Now Playing names it ("Because you like ‹song›").
     */
    fun playRow(row: BecauseRow, index: Int, label: String) {
        val tracks = row.songs.map { it.toStreamTrack() }
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            playerRepository.setQueue(tracks, index.coerceIn(0, tracks.lastIndex), PlaybackSource.Listening(label))
        }
    }

    // -- A "Because you like" song's sheet --------------------------------------

    fun playNext(song: TrackSummary) = delegate.playNext(song.toTrackItem())

    fun addToQueue(song: TrackSummary) = delegate.addToQueue(song.toTrackItem())

    fun startRadio(song: TrackSummary) = delegate.startRadio(song.toTrackItem())

    fun download(song: TrackSummary) = delegate.downloadTrack(song.toTrackItem())

    fun saveToPlaylist(song: TrackSummary, playlistId: Long) = delegate.addToPlaylist(song.toTrackItem(), playlistId)

    fun createPlaylistAndAdd(song: TrackSummary, name: String) = delegate.createPlaylistAndAdd(song.toTrackItem(), name)

    // -- A "New from your artists" album's sheet --------------------------------

    /** The sheet is open: start loading the album, so the action picked next doesn't wait for it. */
    fun warmAlbum(album: AlbumSummary) {
        if (album.source != AlbumSource.YOUTUBE) return
        viewModelScope.launch {
            try {
                albumCache.get(album.id, album.source)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Quiet: the action picked loads it again, and says so if it still fails.
            }
        }
    }

    /** Plays [album] from its first song, or shuffled with [shuffle], as its page's Play does: the album is the queue. */
    fun playAlbum(album: AlbumSummary, shuffle: Boolean = false) {
        viewModelScope.launch {
            val (detail, tracks) = loadAlbum(album) ?: return@launch
            playerRepository.setQueue(
                if (shuffle) tracks.shuffled() else tracks,
                0,
                PlaybackSource.Album(detail.title, detail.artist),
            )
        }
    }

    /** Puts [album]'s songs right after the current song, in album order (each Play next goes after the last). */
    fun playAlbumNext(album: AlbumSummary) {
        viewModelScope.launch {
            val (_, tracks) = loadAlbum(album) ?: return@launch
            var added = false
            for (track in tracks) {
                try {
                    if (playerRepository.addNext(track)) added = true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "play next ${track.youtubeId} failed: ${e.message}")
                }
            }
            confirmAlbumAdd(added, R.string.home_album_playing_next)
        }
    }

    /** Adds [album]'s songs to the end of the queue in one go, as its page's Add to queue does. */
    fun addAlbumToQueue(album: AlbumSummary) {
        viewModelScope.launch {
            val (_, tracks) = loadAlbum(album) ?: return@launch
            val added = try {
                playerRepository.addToQueue(tracks)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "add album ${album.id} to queue failed: ${e.message}")
                false
            }
            confirmAlbumAdd(added, R.string.home_album_added_to_queue)
        }
    }

    /**
     * Finds [album]'s artist (its page links the artist's channel) and hands it to [open] as (id, name). An album whose
     * page links no artist says so instead.
     */
    fun openArtist(album: AlbumSummary, open: (id: String, name: String) -> Unit) {
        viewModelScope.launch {
            val (detail, _) = loadAlbum(album) ?: return@launch
            val id = detail.artistId
            if (id.isNullOrBlank()) {
                _albumMessages.tryEmit(R.string.home_album_no_artist)
            } else {
                open(id, detail.artist.ifBlank { album.artist })
            }
        }
    }

    /** In a Listen Together session the session confirms an add; otherwise say what happened. */
    private fun confirmAlbumAdd(added: Boolean, @StringRes done: Int) {
        if (added && delegate.inListenTogether) return
        _albumMessages.tryEmit(if (added) done else R.string.home_album_add_failed)
    }

    /**
     * [album]'s page and its songs as queue tracks, or null (said with a toast) when it didn't load or has no songs.
     * Only YouTube Music albums, which New from your artists' are: a Qobuz album's songs have no video id to stream.
     */
    private suspend fun loadAlbum(album: AlbumSummary): Pair<AlbumDetail, List<Track>>? {
        val detail = try {
            if (album.source == AlbumSource.YOUTUBE) albumCache.get(album.id, album.source) else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "album ${album.id} didn't load: ${e.message}")
            null
        }
        val tracks = detail?.let(::albumTracks).orEmpty()
        if (detail == null || tracks.isEmpty()) {
            _albumMessages.tryEmit(R.string.home_album_load_failed)
            return null
        }
        return detail to tracks
    }

    override fun onCleared() {
        delegate.onOwnerCleared()
        super.onCleared()
    }

    /** One shelf: its saved list painted at once, then the repository's current one; one load at a time. */
    private inner class Shelf<T>(
        val section: HomeSection,
        private val cached: suspend () -> List<T>,
        private val current: suspend () -> List<T>,
    ) {
        val items = MutableStateFlow<List<T>>(emptyList())
        private var loading: Job? = null

        fun refresh() {
            if (loading?.isActive == true) return
            loading = viewModelScope.launch {
                try {
                    // Yesterday's shelf paints at once; today's replaces it when built.
                    if (items.value.isEmpty()) items.value = cached()
                    // No network: keep the saved shelf, and don't spend an attempt the shelf would then wait out
                    if (!connectivity.isConnected()) return@launch
                    items.value = current()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // A shelf is a nicety: whatever went wrong, Home keeps what it shows.
                    Log.w(TAG, "$section failed: ${e.message}")
                }
            }
        }

        fun stop() {
            loading?.cancel()
            loading = null
        }
    }

    private companion object {
        const val TAG = "HomeShelves"
    }
}

/** A shelf song as the queue streams it: by its video id, with an id made from it. */
internal fun TrackSummary.toStreamTrack(): Track = Track(
    id = videoId.hashCode().toLong(),
    title = title,
    artist = artist,
    album = album.orEmpty(),
    durationMs = (durationSeconds * 1000.0).toLong(),
    albumArtUrl = thumbnailUrl,
    youtubeId = videoId,
    source = MusicSource.YOUTUBE,
    isStreamable = true,
)

/** A shelf song as [TrackActionsDelegate] takes it (the same adapter as Search's, feature/search SearchViewModel.kt). */
internal fun TrackSummary.toTrackItem(): TrackItem = TrackItem(
    videoId = videoId,
    title = title,
    artist = artist,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
    album = album,
    musicVideoType = musicVideoType,
    explicit = explicit,
)

/**
 * A YouTube Music album's songs as queue tracks, as its page builds them (AlbumDiscoveryViewModel): each streamed by
 * its video id and named for the album, with the album's artist when the row names none and the album's cover when
 * it has none. A row with no video id can't be streamed and is left out.
 */
internal fun albumTracks(detail: AlbumDetail): List<Track> =
    detail.tracks.filter { it.videoId.isNotBlank() }.map { t ->
        Track(
            id = t.videoId.hashCode().toLong(),
            title = t.title,
            artist = t.artist.ifBlank { detail.artist },
            album = detail.title,
            durationMs = (t.durationSeconds * 1000.0).toLong(),
            albumArtUrl = t.thumbnailUrl ?: detail.thumbnailUrl,
            youtubeId = t.videoId,
            source = MusicSource.YOUTUBE,
            isStreamable = true,
        )
    }
