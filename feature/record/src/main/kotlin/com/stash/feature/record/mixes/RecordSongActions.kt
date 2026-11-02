package com.stash.feature.record.mixes

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.widget.Toast
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.model.Playlist
import com.stash.core.model.RadioStartResult
import com.stash.core.model.Track
import com.stash.core.model.isUnavailableForDisplay
import com.stash.feature.record.R
import com.stash.feature.record.bridge.RecordMatch
import com.stash.feature.record.bridge.RecordSong
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a song row's Like or menu came to, for a line under the screen (said in the screen's language). */
sealed interface SongLine {
    data object LikeFailed : SongLine
    data object UnlikeFailed : SongLine
    data object PlayingNext : SongLine
    data object AddedToQueue : SongLine
    data object QueueFailed : SongLine
    data class AddedToPlaylist(val name: String) : SongLine
    data object PlaylistFailed : SongLine
    data object RadioFailed : SongLine

    /** A blocked song is never liked, queued or put in a playlist from here (Search's own refusal). */
    data object Blocked : SongLine

    fun text(res: Resources): String = when (this) {
        LikeFailed -> res.getString(R.string.record_like_failed)
        UnlikeFailed -> res.getString(R.string.record_unlike_failed)
        PlayingNext -> res.getString(R.string.record_song_playing_next)
        AddedToQueue -> res.getString(R.string.record_song_added_to_queue)
        QueueFailed -> res.getString(R.string.record_song_queue_failed)
        is AddedToPlaylist -> res.getString(R.string.record_song_added_to_playlist, name)
        PlaylistFailed -> res.getString(R.string.record_song_playlist_failed)
        RadioFailed -> res.getString(R.string.record_song_radio_failed)
        Blocked -> res.getString(R.string.record_song_blocked)
    }
}

/**
 * What a song row of Your record's Mixes tab and of a mix's page does besides play (stash-player `TrackRow.svelte`: Like, and
 * the song menu), on a song that is often only words from the history: the song as this phone's row first ([RecordMatch]: your
 * own copy, the same song found, else a stream-only row that Stash resolves when it plays, as a Search result's is kept), then
 * Stash's own action on that row.
 *
 * A blocked song (BlocklistGuard's rule, by its words and its Spotify id) is refused first, as Search refuses one ("Can't add a
 * blocked track"), and no row is made for it. Like is Now Playing's heart ([LikeCoordinator.setLiked]): the row goes in Stash's
 * Liked Songs, and on to Spotify, YouTube Music and Last.fm when their "Like on … too" switches are on (the switch says "Songs
 * you heart in Stash", and these are hearts too). Nothing downloads, unless Liked Songs itself is set to download (as any heart
 * then does). App-wide and on its own scope: a Like tapped just before leaving still lands. Each line goes to the screen that
 * is open ([lines]), else a toast; in a Listen Together session an add says nothing (the session confirms it), and a song the
 * player can't queue has the player's own line, not a second one.
 */
@Singleton
class RecordSongActions internal constructor(
    /** The songs as this phone's rows, in order (null: none for that song): [RecordMatch.rows]. */
    private val rows: suspend (List<RecordSong>) -> List<Track?>,
    private val like: suspend (trackId: Long) -> Unit,
    private val unlike: suspend (trackId: Long) -> Unit,
    private val addNext: suspend (Track) -> Boolean,
    private val addToQueue: suspend (Track) -> Boolean,
    private val startRadio: suspend (RadioSeed.Song) -> RadioStartResult,
    private val addToPlaylist: suspend (trackId: Long, playlistId: Long) -> Unit,
    private val createPlaylist: suspend (name: String) -> Long,
    /** Takes a playlist made for a song back out (an add that failed: no empty playlist is left behind). */
    private val deletePlaylist: suspend (playlistId: Long) -> Unit,
    /** The playlists a song can be saved to (Library's own). */
    val playlists: Flow<List<Playlist>>,
    /** BlocklistGuard.isBlocked: the song's words and its Spotify id (`spotify:track:…`). */
    private val isBlocked: suspend (title: String, artist: String, spotifyUri: String?) -> Boolean,
    /** A Listen Together session is on (an add becomes the session's, which confirms it). */
    private val inListenTogether: () -> Boolean,
    private val toast: (SongLine) -> Unit,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        match: RecordMatch,
        likes: LikeCoordinator,
        player: PlayerRepository,
        music: MusicRepository,
        playlistDao: PlaylistDao,
        blocklist: BlocklistGuard,
        listenTogether: ListenTogetherController,
        @ApplicationContext context: Context,
    ) : this(
        rows = match::rows,
        like = { likes.setLiked(it, liked = true) },
        unlike = { likes.setLiked(it, liked = false) },
        addNext = player::addNext,
        addToQueue = player::addToQueue,
        startRadio = { seed -> player.startRadio(seed) },
        addToPlaylist = music::addTrackToPlaylist,
        createPlaylist = music::createPlaylist,
        deletePlaylist = playlistDao::deleteById,
        playlists = music.getUserCreatedPlaylists(),
        isBlocked = { title, artist, uri -> blocklist.isBlocked(artist = artist, title = title, spotifyUri = uri, youtubeId = null) },
        inListenTogether = { listenTogether.active.value },
        toast = { line -> Toast.makeText(context, line.text(context.resources), Toast.LENGTH_SHORT).show() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _lines = MutableSharedFlow<SongLine>(extraBufferCapacity = 4)

    /** What a row's Like or menu came to, for the screen that is open. */
    val lines: SharedFlow<SongLine> = _lines.asSharedFlow()

    private fun say(line: SongLine) {
        if (_lines.subscriptionCount.value > 0) _lines.tryEmit(line) else toast(line)
    }

    /** [song] is on the blocklist (by its words, or its Spotify id). */
    private suspend fun blocked(song: RecordSong): Boolean = isBlocked(song.title, song.artist, song.sid?.let { "spotify:track:$it" })

    /**
     * [song] as one row of this phone's, or null: none could be found or made, or the song is blocked (said, and nothing made:
     * a row made for a blocked song would only be hidden and swept).
     */
    private suspend fun rowOf(song: RecordSong): Track? {
        if (blocked(song)) {
            say(SongLine.Blocked)
            return null
        }
        return rows(listOf(song)).firstOrNull()
    }

    /** Runs [work] on this scope, a failure logged and said as [failed]; [work]'s answer, or [orElse] on a failure. */
    private fun <T> act(what: String, failed: SongLine, orElse: T, work: suspend () -> T): Deferred<T> = scope.async {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            say(failed)
            orElse
        }
    }

    /** Likes [song] in Stash: the row it landed on once it is in Liked Songs, else null (said why). */
    fun like(song: RecordSong): Deferred<Long?> = act("a like", SongLine.LikeFailed, null) {
        if (blocked(song)) {
            say(SongLine.Blocked)
            return@act null
        }
        val row = rows(listOf(song)).firstOrNull()
        if (row == null) {
            say(SongLine.LikeFailed)
            return@act null
        }
        like(row.id)
        row.id
    }

    /** Takes [trackIds] (every row of a song liked in Stash) out of Liked Songs: true once they all are. */
    fun unlike(trackIds: List<Long>): Deferred<Boolean> = act("an unlike", SongLine.UnlikeFailed, false) {
        if (trackIds.isEmpty()) {
            say(SongLine.UnlikeFailed)
            return@act false
        }
        for (id in trackIds.distinct()) unlike(id)
        true
    }

    /**
     * What a queue add came to: nothing in a Listen Together session (the session confirms it, as Search's `confirmAdd`); a row
     * the player can't queue already has the player's own line ("Nothing in this queue is playable right now."), so none here.
     */
    private fun added(row: Track, ok: Boolean, line: SongLine) {
        when {
            ok -> if (!inListenTogether()) say(line)
            row.isUnavailableForDisplay -> Unit
            else -> say(SongLine.QueueFailed)
        }
    }

    /** Play next: after the song playing now. */
    fun playNext(song: RecordSong) {
        act("play next", SongLine.QueueFailed, Unit) {
            val row = rowOf(song) ?: return@act
            added(row, addNext(row), SongLine.PlayingNext)
        }
    }

    /** Add to queue: at its end. */
    fun addToQueue(song: RecordSong) {
        act("add to queue", SongLine.QueueFailed, Unit) {
            val row = rowOf(song) ?: return@act
            added(row, addToQueue(row), SongLine.AddedToQueue)
        }
    }

    /** A radio from the song, by its words (and your own row's YouTube id when you have it: nothing is made for this). */
    fun startRadio(song: RecordSong) {
        act("a radio", SongLine.RadioFailed, Unit) {
            val yt = song.trackId?.let { rows(listOf(song)).firstOrNull()?.youtubeId }
            val started = startRadio(RadioSeed.Song(title = song.title, artist = song.artist, ytVideoId = yt)) is RadioStartResult.Started
            if (!started) say(SongLine.RadioFailed)
        }
    }

    /** Adds the song to the playlist [playlistId] ([name] for the line). */
    fun addToPlaylist(song: RecordSong, playlistId: Long, name: String) {
        act("add to a playlist", SongLine.PlaylistFailed, Unit) {
            val row = rowOf(song) ?: return@act
            addToPlaylist(row.id, playlistId)
            say(SongLine.AddedToPlaylist(name))
        }
    }

    /** A new playlist named [name], with the song in it; none is left behind if the song can't go in. */
    fun createPlaylistWith(song: RecordSong, name: String) {
        act("a new playlist", SongLine.PlaylistFailed, Unit) {
            // The row first: a blocked song, or one with no row, makes no playlist at all
            val row = rowOf(song) ?: return@act
            val playlist = createPlaylist(name)
            var added = false
            try {
                addToPlaylist(row.id, playlist)
                added = true
            } finally {
                // The song didn't go in (a failure, or cancelled): the empty playlist goes, even when cancelled
                if (!added) withContext(NonCancellable) { takeBack(playlist) }
            }
            say(SongLine.AddedToPlaylist(name))
        }
    }

    /** Deletes the playlist made for a song that didn't go in; a failure here is only logged (the add's own is said). */
    private suspend fun takeBack(playlist: Long) {
        try {
            deletePlaylist(playlist)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "an empty playlist couldn't be taken back: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "YourRecord"
    }
}
