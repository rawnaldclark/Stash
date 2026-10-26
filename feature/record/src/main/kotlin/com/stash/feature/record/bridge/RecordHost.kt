package com.stash.feature.record.bridge

import android.os.SystemClock
import android.util.Log
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.art.ArtSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Where the host's messages go: one page's reply proxy (the frame that sent the message). Called on the main thread only. */
interface RecordPort {
    fun post(message: String)
}

/** What the host asks of the screen around the record. */
sealed interface RecordEvent {
    /** Back to Stash: the record's × or a back with nothing of its own open (or a page that didn't answer back). */
    data object Close : RecordEvent

    /** A line for the listener (a snackbar). */
    data class Say(val note: RecordNote) : RecordEvent
}

/** Plays the record's songs in Stash; returns at once (the matching and the queue go on elsewhere: [RecordPlayback]). */
fun interface RecordPlayer {
    fun play(songs: List<RecordSong>, index: Int, from: String)
}

/** Real covers by words ([com.stash.feature.record.art.RecordArt]); [onBatch] is called where [covers] was called. */
fun interface RecordCovers {
    suspend fun covers(songs: List<ArtSong>, retry: CoroutineScope, onBatch: (List<String?>) -> Unit): List<String?>
}

/** Takes the record's files (`stash:save` chunks), in order ([RecordSaves]). */
interface RecordSaveSink {
    /** Takes [chunk], calling [appended] once it is in the file; what to tell the listener once a file is done, else null. */
    suspend fun take(chunk: RecordAsk.Save, appended: suspend () -> Unit): RecordNote?

    /** The page gave up on file [id]. */
    suspend fun abort(id: String)

    /** The page went. */
    suspend fun drop()
}

/**
 * The app's side of the bridge (PLAN "Bridge protocol v1"), apart from the WebView so it can be tested: it reads what the
 * record says ([RecordMessages.read]) and answers.
 *
 * Messages are handled one at a time in the order they came ([onMessage] only queues them), read off the main thread (a file's
 * chunk is most of a megabyte) and handled on [main], where every post is made. A file's chunks go to a queue of their own
 * ([SAVE_QUEUE] deep), put together by their own worker, so a tap, a cover's ask or the × never waits behind a 30 MB video being
 * copied to Downloads. Each message carries the page it came from (a generation, moved on by [detach]): what a page that has
 * gone still had queued is dropped.
 *
 * - `stash:hello`: the page's listener is live. The archive goes in chunks of at most [chunkSize] rows, `seq` from 0 for each
 *   hello and the last marked `done`. A chunk doesn't say which hello it answers (the record starts over at seq 0), so a new
 *   hello stops the stream still sending for the previous one, and a chunk built for an old hello is never posted, even one a
 *   slow read finishes after its stream was stopped. The theme goes too, so the record has Stash's look from its first frame.
 * - `stash:ready`: the theme again (the web sends it on load and on ready). `stash:counts`: logged (the source switches are
 *   phase 4).
 * - `stash:play`: "Playing …" at once (the record covers the mini player), and the songs to [RecordPlayer], which outlives
 *   the screen: back right after a tap still plays.
 * - `stash:art`: covers looked up here ([RecordCovers]), handed back by the record's own names as each batch lands, then the
 *   whole list with null for none (and any found later, after that).
 * - `stash:save`: a poster's or a video's chunks, put together ([RecordSaveSink]) and saved or shared; each answered
 *   `stash:save-ack` once it is in the file (the page keeps two in flight). `stash:save-abort`: what was gathered goes.
 * - `stash:import`: the Spotify import is phase 3; for now the listener is told it's coming.
 * - `stash:close`: [RecordEvent.Close].
 *
 * Everything a page started (its archive, its covers, the wait on back) runs in that page's own job, which [detach] cancels.
 *
 * Back ([back]) asks the record to close what it has open ([RecordMessages.BACK]); it answers `stash:backed` once it closed
 * something, `stash:close` when nothing was open. With neither within [BACK_WAIT_MS] (a page that is stuck), the record closes
 * anyway, and so does a second back while the first is still unanswered ([SECOND_BACK_MS]). A page that never said hello (or
 * whose renderer stopped answering, [unresponsive]) can't answer, so back closes at once.
 */
class RecordHost(
    private val scope: CoroutineScope,
    private val archive: RecordArchiveSource,
    private val covers: RecordCovers,
    private val player: RecordPlayer,
    private val saves: RecordSaveSink,
    private val events: (RecordEvent) -> Unit,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
    private val background: CoroutineContext = Dispatchers.Default,
    private val chunkSize: Int = CHUNK_ROWS,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    /** The page's renderer stopped answering: back closes without asking it. */
    var unresponsive = false

    /** A message as it came: its words, the page that sent it, and that page's [generation] then. */
    private class Incoming(val raw: String, val from: RecordPort, val generation: Int)

    /** What the save worker does: a chunk (and where its ack goes), a file given up, or everything dropped. */
    private sealed interface SaveWork {
        class Chunk(val ask: RecordAsk.Save, val from: RecordPort, val generation: Int) : SaveWork
        class Abort(val id: String) : SaveWork
        data object Drop : SaveWork
    }

    private val inbox = Channel<Incoming>(Channel.UNLIMITED)
    private val saveQueue = Channel<SaveWork>(SAVE_QUEUE)

    /** Moved on by [detach]: messages a page that has gone still had queued carry an older one, and are dropped. */
    @Volatile private var generation = 0

    /** The page's own job: its archive, covers and the wait on back. */
    private var page: CompletableJob = newPage()

    /** The page that last said hello: where the theme and back go. */
    private var current: RecordPort? = null

    /** Hellos answered so far; a stream posts only while its own is the latest. */
    private var hellos = 0
    private var stream: Job? = null
    private var light = false
    /** When the back still waiting for the page's answer was pressed; null when none is. */
    private var backAsked: Long? = null
    private var backWait: Job? = null

    init {
        scope.launch(background) {
            for (m in inbox) {
                if (m.generation != generation) continue
                when (val ask = RecordMessages.read(m.raw) ?: continue) {
                    is RecordAsk.Save -> queueSave(SaveWork.Chunk(ask, m.from, m.generation))
                    is RecordAsk.SaveAbort -> queueSave(SaveWork.Abort(ask.id))
                    else -> withContext(main) { if (m.generation == generation) handle(ask, m.from) }
                }
            }
        }
        scope.launch(background) {
            for (work in saveQueue) save(work)
        }
    }

    /** A message from the page. Queued: messages are handled in the order they came. */
    fun onMessage(raw: String, from: RecordPort) {
        inbox.trySend(Incoming(raw, from, generation))
    }

    /**
     * Onto the save worker's queue. The page keeps two chunks unacknowledged, so the queue never fills; a page that ignores
     * that loses the chunk, and so the file (the next one comes out of order), and gives up on it by itself.
     */
    private fun queueSave(work: SaveWork) {
        if (!saveQueue.trySend(work).isSuccess) Log.w(TAG, "a file from the record came faster than it could be kept; dropped")
    }

    private suspend fun save(work: SaveWork) {
        try {
            when (work) {
                is SaveWork.Chunk -> {
                    if (work.generation != generation) return
                    val ack = RecordMessages.saveAck(work.ask.id, work.ask.seq)
                    val note = saves.take(work.ask) { withContext(main) { if (work.generation == generation) work.from.post(ack) } }
                    if (note != null) withContext(main) { events(RecordEvent.Say(note)) }
                }
                is SaveWork.Abort -> saves.abort(work.id)
                SaveWork.Drop -> saves.drop()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a file from the record failed: ${e.javaClass.simpleName}")
            withContext(main) { events(RecordEvent.Say(RecordNote.SaveFailed)) }
        }
    }

    private fun handle(ask: RecordAsk, from: RecordPort) {
        when (ask) {
            is RecordAsk.Hello -> hello(ask, from)
            is RecordAsk.Ready -> {
                Log.i(TAG, "the record is ready: ${ask.plays} plays")
                from.post(RecordMessages.theme(light))
            }
            is RecordAsk.Counts -> Log.i(TAG, "the record's sources: spotify ${ask.spotify}, stash ${ask.stash}, lastfm ${ask.lastfm}")
            is RecordAsk.Play -> play(ask)
            is RecordAsk.Art -> art(ask, from)
            RecordAsk.Import -> events(RecordEvent.Say(RecordNote.ImportComing))
            RecordAsk.Close -> close()
            RecordAsk.Backed -> {
                // The page closed what it had open: the record stays
                backAsked = null
                backWait?.cancel()
                backWait = null
            }
            is RecordAsk.Save, is RecordAsk.SaveAbort -> Unit // the save worker's, before this
        }
    }

    /** Back pressed over the record. */
    fun back() {
        val at = now()
        // Back again while the page hasn't answered the last one: it is stuck, or the listener means it
        val again = backAsked?.let { at - it < SECOND_BACK_MS } == true
        val page = current
        if (page == null || unresponsive || again) {
            close()
            return
        }
        if (backAsked == null) backAsked = at
        page.post(RecordMessages.BACK)
        if (backWait == null) {
            backWait = pageScope().launch(main) {
                delay(BACK_WAIT_MS)
                Log.w(TAG, "the record didn't answer back; closing it")
                close()
            }
        }
    }

    /** The app's theme; the record hears of a change at once, and of the theme with every hello and ready. */
    fun setTheme(light: Boolean) {
        if (this.light == light) return
        this.light = light
        current?.post(RecordMessages.theme(light))
    }

    /**
     * The page is gone (closed, or its renderer died): all it started stops, nothing more is sent to it, what it still had
     * queued is dropped, and so is a file it was handing over. (When the whole screen goes, its scope is already cancelled:
     * [RecordSaves.clearNow] clears the file then.)
     */
    fun detach() {
        generation++
        page.cancel()
        page = newPage()
        stream = null
        current = null
        hellos++
        unresponsive = false
        backWait = null
        backAsked = null
        saveQueue.trySend(SaveWork.Drop)
    }

    private fun close() {
        backWait?.cancel()
        backWait = null
        backAsked = null
        events(RecordEvent.Close)
    }

    private fun newPage(): CompletableJob = SupervisorJob(scope.coroutineContext[Job])

    private fun pageScope() = CoroutineScope(scope.coroutineContext + page)

    private fun hello(ask: RecordAsk.Hello, from: RecordPort) {
        if (ask.v != RecordMessages.PROTOCOL) Log.w(TAG, "the record speaks bridge v${ask.v}; this app speaks v${RecordMessages.PROTOCOL}")
        stream?.cancel()
        current = from
        unresponsive = false
        val mine = ++hellos
        from.post(RecordMessages.theme(light))
        stream = pageScope().launch {
            var seq = 0
            var sent = 0
            try {
                withContext(background) {
                    archive.stream(chunkSize) { rows, last ->
                        val message = RecordMessages.archive(rows, seq++, last)
                        withContext(main) { if (mine == hellos) from.post(message) }
                        sent += rows.size
                    }
                }
                Log.i(TAG, "archive sent: $sent rows in $seq chunks")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the archive stopped at $sent rows: ${e.javaClass.simpleName}", e)
                // The record presses what it has rather than waiting for ever
                if (mine == hellos) {
                    from.post(RecordMessages.archive(emptyList(), seq, done = true))
                    events(RecordEvent.Say(RecordNote.ArchiveFailed))
                }
            }
        }
    }

    private fun play(ask: RecordAsk.Play) {
        val songs = ask.songs
        Log.i(TAG, "a play from the record: ${songs.size} songs")
        events(RecordEvent.Say(if (songs.size == 1) RecordNote.Playing(songs[0].title) else RecordNote.PlayingSongs(songs.size)))
        player.play(songs, ask.index, ask.from)
    }

    private fun art(ask: RecordAsk.Art, from: RecordPort) {
        val asks = ask.asks
        // web: a name with no title is an artist; a song's or album's words otherwise (the title stands in for an artist's)
        val songs = asks.map { if (it.isArtist) ArtSong(it.artist, it.artist, isArtist = true) else ArtSong(it.title!!, it.artist, it.album) }
        val here = pageScope()
        here.launch {
            // Called where covers() runs (this scope's thread, the main one), so it posts in order, before the final list
            val found: (List<String?>) -> Unit = { urls ->
                val list = asks.indices.mapNotNull { i -> urls.getOrNull(i)?.let { asks[i].id to it } }
                if (list.isNotEmpty()) from.post(RecordMessages.covers(list))
            }
            try {
                val urls = covers.covers(songs, here, found)
                // Answered to the page that asked: one that has gone since drops it, and a new page ignores names it never asked
                from.post(RecordMessages.covers(asks.mapIndexed { i, a -> a.id to urls.getOrNull(i) }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Its drawn covers stay: nothing to say
                Log.w(TAG, "covers failed: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "YourRecord"

        /** Rows per `stash:archive` chunk (PLAN: 20k; about 3 MB of JSON). */
        const val CHUNK_ROWS = 20_000

        /** Back closes the record when the page hasn't answered it (`stash:backed` or `stash:close`) this long after. */
        const val BACK_WAIT_MS = 1_500L

        /** A second back this soon after a first the page hasn't answered yet closes the record. */
        const val SECOND_BACK_MS = 1_000L

        /** A file's chunks waiting to be kept, at most (the page keeps two unacknowledged). */
        const val SAVE_QUEUE = 8
    }
}
