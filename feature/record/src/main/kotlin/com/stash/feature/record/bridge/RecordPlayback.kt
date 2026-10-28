package com.stash.feature.record.bridge

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What a tap in the record plays, decided without the player so it can be tested. */
internal sealed interface RecordQueuePlan {
    data class Play(val tracks: List<Track>, val startIndex: Int) : RecordQueuePlan

    data class Blocked(val note: RecordNote) : RecordQueuePlan
}

/**
 * The record's songs (matched, in its order; null: no row for that song) as a queue from [index]. A song that couldn't be
 * matched is left out and the start stays on the song that was asked for, else the next one that can play (the web's rule for
 * a bad song in a play). Online every song plays, streaming what isn't downloaded; offline only songs with a file here, as in
 * Library: a queue that mixes the others in stops after a few failures.
 */
internal fun planRecordQueue(tracks: List<Track?>, index: Int, online: Boolean): RecordQueuePlan {
    val playable = tracks.indices.filter { i -> tracks[i]?.let { online || it.filePath != null } == true }
    if (playable.isEmpty()) {
        return RecordQueuePlan.Blocked(if (tracks.any { it != null }) RecordNote.NoneDownloaded else RecordNote.NoneFound)
    }
    val from = playable.indexOfFirst { it >= index }.takeIf { it >= 0 } ?: playable.lastIndex
    return RecordQueuePlan.Play(playable.map { tracks[it]!! }, from)
}

/**
 * Plays the record's songs in Stash (PLAN "Play"): each as this phone's row ([RecordMatch]: by its words through the hand-off's
 * matcher, the library's own row when there is one, else a stream-only row that Stash resolves when it plays, as a Search tap
 * does; a song of Your record's own mixes also by its Spotify id, or as your own row, with the cover found for it), then the
 * queue, its "Playing from" the record's label ("The Record · Sunday, 3 March 2024") or the mix's title.
 *
 * App-wide and on its own scope: a back right after a tap closes the record, and the song still starts. The newest tap wins
 * (one still matching is dropped). When nothing can play it says so ([notes], collected by the record's screen while it is
 * open; a toast once it has closed).
 */
@Singleton
class RecordPlayback internal constructor(
    private val match: suspend (List<RecordSong>) -> List<Track?>,
    private val setQueue: suspend (tracks: List<Track>, start: Int, from: String) -> Unit,
    private val online: () -> Boolean,
    private val toast: (RecordNote) -> Unit,
    private val scope: CoroutineScope,
    /** Shuffle's order (the app's own: a playlist's Shuffle shuffles the queue). */
    private val shuffled: (List<Track>) -> List<Track> = { it.shuffled() },
) : RecordPlayer {
    @Inject
    constructor(
        match: RecordMatch,
        player: PlayerRepository,
        connectivity: ConnectivityMonitor,
        @ApplicationContext context: Context,
    ) : this(
        match = match::rows,
        setQueue = { tracks, start, from -> player.setQueue(tracks, start, PlaybackSource.Listening(from)) },
        online = connectivity::isConnected,
        toast = { note -> Toast.makeText(context, note.text(context.resources), Toast.LENGTH_SHORT).show() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _notes = MutableSharedFlow<RecordNote>(extraBufferCapacity = 4)

    /** What a tap came to when it couldn't play, for the record's screen while it is open. */
    val notes: SharedFlow<RecordNote> = _notes.asSharedFlow()

    private var playing: Job? = null

    override fun play(songs: List<RecordSong>, index: Int, from: String) = play(songs, index, from, shuffle = false)

    /**
     * [play], or with [shuffle] the songs found in a random order from the first (the app's Shuffle, as a playlist's). Offline,
     * only the songs with a file here, either way ([planRecordQueue]).
     */
    fun play(songs: List<RecordSong>, index: Int, from: String, shuffle: Boolean) {
        // Main thread (the record's messages are handled there): the newest tap wins
        playing?.cancel()
        playing = scope.launch {
            try {
                val rows = match(songs)
                when (val plan = planRecordQueue(rows, index, online())) {
                    is RecordQueuePlan.Play ->
                        if (shuffle) setQueue(shuffled(plan.tracks), 0, from) else setQueue(plan.tracks, plan.startIndex, from)
                    is RecordQueuePlan.Blocked -> say(plan.note)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a play from the record failed: ${e.javaClass.simpleName}", e)
                say(RecordNote.PlayFailed)
            }
        }
    }

    private fun say(note: RecordNote) {
        if (_notes.subscriptionCount.value > 0) _notes.tryEmit(note) else toast(note)
    }

    private companion object {
        const val TAG = "YourRecord"
    }
}
