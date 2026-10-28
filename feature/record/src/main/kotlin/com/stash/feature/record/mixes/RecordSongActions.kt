package com.stash.feature.record.mixes

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.widget.Toast
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Playlist
import com.stash.core.model.RadioStartResult
import com.stash.core.model.Track
import com.stash.feature.record.R
import com.stash.feature.record.bridge.RecordMatch
import com.stash.feature.record.bridge.RecordSong
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    fun text(res: Resources): String = when (this) {
        LikeFailed -> res.getString(R.string.record_like_failed)
        UnlikeFailed -> res.getString(R.string.record_unlike_failed)
        PlayingNext -> res.getString(R.string.record_song_playing_next)
        AddedToQueue -> res.getString(R.string.record_song_added_to_queue)
        QueueFailed -> res.getString(R.string.record_song_queue_failed)
        is AddedToPlaylist -> res.getString(R.string.record_song_added_to_playlist, name)
        PlaylistFailed -> res.getString(R.string.record_song_playlist_failed)
        RadioFailed -> res.getString(R.string.record_song_radio_failed)
    }
}

/**
 * What a song row of Your record's Mixes tab and of a mix's page does besides play (stash-player `TrackRow.svelte`: Like, and
 * the song menu), on a song that is often only words from the history: the song as this phone's row first ([RecordMatch]: your
 * own copy, the same song found, else a stream-only row that Stash resolves when it plays, as a Search result's is kept), then
 * Stash's own action on that row.
 *
 * Like puts the row in Stash's Liked Songs alone ([StashLikedPlaylistRepository], what Now Playing's heart does without its
 * mirroring): never written to Spotify or YouTube Music, and nothing downloads, unless Liked Songs itself is set to download (as
 * any heart then does). App-wide and on its own scope: a Like tapped just before leaving still lands. Each line goes to the
 * screen that is open ([lines]), else a toast.
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
    /** The playlists a song can be saved to (Library's own). */
    val playlists: Flow<List<Playlist>>,
    private val toast: (SongLine) -> Unit,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        match: RecordMatch,
        liked: StashLikedPlaylistRepository,
        player: PlayerRepository,
        music: MusicRepository,
        @ApplicationContext context: Context,
    ) : this(
        rows = match::rows,
        like = liked::add,
        unlike = liked::remove,
        addNext = player::addNext,
        addToQueue = player::addToQueue,
        startRadio = { seed -> player.startRadio(seed) },
        addToPlaylist = music::addTrackToPlaylist,
        createPlaylist = music::createPlaylist,
        playlists = music.getUserCreatedPlaylists(),
        toast = { line -> Toast.makeText(context, line.text(context.resources), Toast.LENGTH_SHORT).show() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _lines = MutableSharedFlow<SongLine>(extraBufferCapacity = 4)

    /** What a row's Like or menu came to, for the screen that is open. */
    val lines: SharedFlow<SongLine> = _lines.asSharedFlow()

    private fun say(line: SongLine) {
        if (_lines.subscriptionCount.value > 0) _lines.tryEmit(line) else toast(line)
    }

    /** [song] as one row of this phone's, or null (none could be found or made). */
    private suspend fun rowOf(song: RecordSong): Track? = rows(listOf(song)).firstOrNull()

    /** Runs [work] on this scope, a failure logged and said as [failed]; its answer, false on a failure. */
    private fun act(what: String, failed: SongLine, work: suspend () -> Boolean): Deferred<Boolean> = scope.async {
        try {
            work().also { ok -> if (!ok) say(failed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            say(failed)
            false
        }
    }

    /** Likes [song] in Stash: true once it is in Liked Songs. */
    fun like(song: RecordSong): Deferred<Boolean> = act("a like", SongLine.LikeFailed) {
        val row = rowOf(song) ?: return@act false
        like(row.id)
        true
    }

    /** Takes [trackIds] (the rows of a song liked in Stash) out of Liked Songs: true once they are. */
    fun unlike(trackIds: List<Long>): Deferred<Boolean> = act("an unlike", SongLine.UnlikeFailed) {
        if (trackIds.isEmpty()) return@act false
        for (id in trackIds) unlike(id)
        true
    }

    /** Play next: after the song playing now. */
    fun playNext(song: RecordSong) {
        act("play next", SongLine.QueueFailed) {
            val row = rowOf(song) ?: return@act false
            addNext(row).also { if (it) say(SongLine.PlayingNext) }
        }
    }

    /** Add to queue: at its end. */
    fun addToQueue(song: RecordSong) {
        act("add to queue", SongLine.QueueFailed) {
            val row = rowOf(song) ?: return@act false
            addToQueue(row).also { if (it) say(SongLine.AddedToQueue) }
        }
    }

    /** A radio from the song, by its words (and your own row's YouTube id when you have it: nothing is made for this). */
    fun startRadio(song: RecordSong) {
        act("a radio", SongLine.RadioFailed) {
            val yt = song.trackId?.let { rowOf(song)?.youtubeId }
            startRadio(RadioSeed.Song(title = song.title, artist = song.artist, ytVideoId = yt)) is RadioStartResult.Started
        }
    }

    /** Adds the song to the playlist [playlistId] ([name] for the line). */
    fun addToPlaylist(song: RecordSong, playlistId: Long, name: String) {
        act("add to a playlist", SongLine.PlaylistFailed) {
            val row = rowOf(song) ?: return@act false
            addToPlaylist(row.id, playlistId)
            say(SongLine.AddedToPlaylist(name))
            true
        }
    }

    /** A new playlist named [name], with the song in it. */
    fun createPlaylistWith(song: RecordSong, name: String) {
        act("a new playlist", SongLine.PlaylistFailed) {
            val row = rowOf(song) ?: return@act false
            addToPlaylist(row.id, createPlaylist(name))
            say(SongLine.AddedToPlaylist(name))
            true
        }
    }

    private companion object {
        const val TAG = "YourRecord"
    }
}
