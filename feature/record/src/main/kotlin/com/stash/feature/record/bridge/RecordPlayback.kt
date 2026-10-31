package com.stash.feature.record.bridge

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.feature.record.art.ArtSong
import com.stash.feature.record.art.RecordArt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
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
    /** Each song's cover as far as the phone knows it now, asking no one ([RecordArt.cached]). */
    private val cachedCovers: suspend (List<ArtSong>) -> List<String?> = { songs -> songs.map { null } },
    /** Each song's cover looked up ([RecordArt.covers]), every batch told as it lands, the later asks in the scope given. */
    private val lookCovers: suspend (List<ArtSong>, CoroutineScope, (List<String?>) -> Unit) -> List<String?> = { songs, _, _ -> songs.map { null } },
    /** Covers found after the queue was set, by row: kept on rows that have none, and put on the queue ([PlayerRepository.learnArtwork]). */
    private val learn: suspend (Map<Long, String>) -> Unit = {},
    /** Which record play holds the queue now (its "Playing from" label), else null: a Play elsewhere ends this one's lookups. */
    private val queuedFrom: Flow<String?> = flow { awaitCancellation() },
) : RecordPlayer {
    @Inject
    constructor(
        match: RecordMatch,
        player: PlayerRepository,
        connectivity: ConnectivityMonitor,
        @ApplicationContext context: Context,
        art: RecordArt,
        tracks: TrackDao,
    ) : this(
        match = match::rows,
        setQueue = { tracks, start, from -> player.setQueue(tracks, start, PlaybackSource.Listening(from)) },
        online = connectivity::isConnected,
        toast = { note -> Toast.makeText(context, note.text(context.resources), Toast.LENGTH_SHORT).show() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        cachedCovers = art::cached,
        lookCovers = { songs, retry, onBatch -> art.covers(songs, retry, onBatch) },
        learn = { arts ->
            for ((id, url) in arts) {
                try {
                    tracks.fillMissingAlbumArtUrl(id, url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "a cover couldn't be kept: ${e.javaClass.simpleName}")
                }
            }
            player.learnArtwork(arts)
        },
        queuedFrom = player.playerState.map { (it.source as? PlaybackSource.Listening)?.label }.distinctUntilChanged(),
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
                // Every cover the phone already knows goes on the queue at once (a history's songs come with words only)
                val arts = songs.map { ArtSong(title = it.title, artist = it.artist, album = it.album) }
                val known = cachedCovers(arts)
                val rows = match(songs.mapIndexed { i, s -> if (s.cover == null && known.getOrNull(i) != null) s.copy(cover = known[i]) else s })
                when (val plan = planRecordQueue(rows, index, online())) {
                    is RecordQueuePlan.Play -> {
                        if (shuffle) setQueue(shuffled(plan.tracks), 0, from) else setQueue(plan.tracks, plan.startIndex, from)
                        // ...and the rest as they are found: on the rows and the queue (the playing one too), never a blank cover
                        try {
                            learnLater(rows, arts, from)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The music plays: only covers are missing
                            Log.w(TAG, "the queue's covers couldn't be found: ${e.javaClass.simpleName}")
                        }
                    }
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

    /**
     * The covers of [rows] that have none, looked up after the queue is set and learnt batch by batch ([learn]), while the queue
     * is still this play's ([from]): once another Play has taken it, nothing more is looked up for it.
     */
    private suspend fun learnLater(rows: List<Track?>, arts: List<ArtSong>, from: String) = coroutineScope {
        val bare = rows.indices.filter { i -> rows[i]?.let { it.albumArtUrl.isNullOrBlank() && it.albumArtPath.isNullOrBlank() } == true }
        if (bare.isEmpty()) return@coroutineScope
        val lookups = this
        val watch = launch {
            // Ours once the player shows it, then anything else (null included: a queue that isn't a record play's)
            queuedFrom.dropWhile { it != from }.map { it != from }.firstOrNull { it } ?: return@launch
            Log.d(TAG, "the queue went to another play: its covers aren't looked up any more")
            lookups.cancel()
        }
        val told = HashMap<Long, String>()
        fun fresh(urls: List<String?>): Map<Long, String> {
            val out = HashMap<Long, String>()
            urls.forEachIndexed { j, url ->
                val id = rows[bare[j]]!!.id
                if (url != null && told.put(id, url) == null) out[id] = url
            }
            return out
        }
        val last = lookCovers(bare.map { arts[it] }, this) { sofar -> fresh(sofar).takeIf { it.isNotEmpty() }?.let { launch { learn(it) } } }
        fresh(last).takeIf { it.isNotEmpty() }?.let { learn(it) }
        // The later asks (the retry) and their covers are children too: watched until the last one ends
        while (true) {
            val others = coroutineContext.job.children.filter { it !== watch }.toList()
            if (others.isEmpty()) break
            others.forEach { it.join() }
        }
        watch.cancel()
    }

    private fun say(note: RecordNote) {
        if (_notes.subscriptionCount.value > 0) _notes.tryEmit(note) else toast(note)
    }

    private companion object {
        const val TAG = "YourRecord"
    }
}
