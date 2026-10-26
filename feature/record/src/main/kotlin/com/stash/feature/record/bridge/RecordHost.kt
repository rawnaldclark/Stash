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
    suspend fun take(chunk: RecordAsk.Save): RecordNote?

    suspend fun drop()
}

/**
 * The app's side of the bridge (PLAN "Bridge protocol v1"), apart from the WebView so it can be tested: it reads what the
 * record says ([RecordMessages.read]) and answers.
 *
 * Messages are handled one at a time in the order they came ([onMessage] only queues them): read off the main thread (a file's
 * chunk is most of a megabyte), files put together off it too, everything else handled on [main], where every post is made.
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
 * - `stash:save`: a poster's or a video's chunks, put together ([RecordSaveSink]) and saved or shared.
 * - `stash:import`: the Spotify import is phase 3; for now the listener is told it's coming.
 * - `stash:close`: [RecordEvent.Close].
 *
 * Everything a page started (its archive, its covers, the wait on back) runs in that page's own job, which [detach] cancels.
 *
 * Back ([back]) asks the record to close what it has open ([RecordMessages.BACK]); it answers `stash:close` when nothing was,
 * and nothing when it closed something. So the screen [probe]s the page: if it neither closes nor shows any sign of life within
 * [BACK_WAIT_MS], or back comes again within [SECOND_BACK_MS], the record closes anyway. A page that never said hello (or has
 * stopped answering, [unresponsive]) can't answer, so back closes at once.
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
    /** Asks the page for a sign of life; calls its argument on the main thread once the page's script answers. */
    var probe: (onAlive: () -> Unit) -> Unit = {}

    /** The page's renderer stopped answering: back closes without asking it. */
    var unresponsive = false

    private val inbox = Channel<Pair<String, RecordPort>>(Channel.UNLIMITED)

    /** The page's own job: its archive, covers and the wait on back. */
    private var page: CompletableJob = newPage()

    /** The page that last said hello: where the theme and back go. */
    private var current: RecordPort? = null

    /** Hellos answered so far; a stream posts only while its own is the latest. */
    private var hellos = 0
    private var stream: Job? = null
    private var light = false
    private var lastBack: Long? = null
    private var backWait: Job? = null
    private var answered = true

    init {
        scope.launch(background) {
            for ((raw, from) in inbox) {
                val ask = RecordMessages.read(raw) ?: continue
                if (ask is RecordAsk.Save) {
                    val note = try {
                        saves.take(ask)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "a file from the record failed: ${e.javaClass.simpleName}")
                        RecordNote.SaveFailed
                    }
                    if (note != null) withContext(main) { events(RecordEvent.Say(note)) }
                } else {
                    withContext(main) { handle(ask, from) }
                }
            }
        }
    }

    /** A message from the page. Queued: messages are handled in the order they came. */
    fun onMessage(raw: String, from: RecordPort) {
        inbox.trySend(raw to from)
    }

    private fun handle(ask: RecordAsk, from: RecordPort) {
        // Any word from the page: it is alive, so back needn't close it by force
        answered = true
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
            is RecordAsk.Save -> Unit // taken off the main thread, before this
        }
    }

    /** Back pressed over the record. */
    fun back() {
        val at = now()
        val again = lastBack?.let { at - it < SECOND_BACK_MS } == true
        lastBack = at
        val page = current
        if (page == null || unresponsive || again) {
            close()
            return
        }
        answered = false
        page.post(RecordMessages.BACK)
        probe { answered = true }
        backWait?.cancel()
        backWait = pageScope().launch(main) {
            delay(BACK_WAIT_MS)
            if (!answered) {
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

    /** The page is gone (closed, or its renderer died): all it started stops, and nothing more is sent to it. */
    fun detach() {
        page.cancel()
        page = newPage()
        stream = null
        current = null
        hellos++
        unresponsive = false
        backWait = null
        lastBack = null
        scope.launch(background) { saves.drop() }
    }

    private fun close() {
        backWait?.cancel()
        backWait = null
        lastBack = null
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

        /** Back closes the record when the page has shown no sign of life this long after it. */
        const val BACK_WAIT_MS = 1_500L

        /** A second back this soon after the first closes the record whatever the page is doing. */
        const val SECOND_BACK_MS = 1_000L
    }
}
