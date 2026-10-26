package com.stash.feature.record.bridge

import android.util.Log
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.art.ArtSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Where the host's messages go: one page's reply proxy (the frame that sent the message). Called on the main thread only. */
interface RecordPort {
    fun post(message: String)
}

/** What the host asks of the screen around the record. */
sealed interface RecordEvent {
    /** Back to Stash: the record's × or a back with nothing of its own open. */
    data object Close : RecordEvent

    /** A line for the listener (a snackbar). */
    data class Say(val text: String) : RecordEvent
}

/** Plays the record's songs in Stash; returns what to tell the listener when nothing could play, else null. */
fun interface RecordPlayer {
    suspend fun play(songs: List<RecordSong>, index: Int, from: String): String?
}

/** Real covers by words ([com.stash.feature.record.art.RecordArt]). */
fun interface RecordCovers {
    suspend fun covers(songs: List<ArtSong>, retry: CoroutineScope, onBatch: (List<String?>) -> Unit): List<String?>
}

/**
 * The app's side of the bridge (PLAN "Bridge protocol v1"), apart from the WebView so it can be tested: it reads what the
 * record says ([RecordMessages.read]) and answers.
 *
 * - `stash:hello`: the page's listener is live. The archive goes in chunks of at most [chunkSize] rows, `seq` from 0 for each
 *   hello and the last marked `done`. A chunk doesn't say which hello it answers (the record starts over at seq 0), so a new
 *   hello stops the stream still sending for the previous one before its own starts, and a chunk built for an old hello is
 *   never posted. The theme goes too, so the record has Stash's look from its first frame.
 * - `stash:ready`: the theme again (the web sends it on load and on ready). `stash:counts`: logged (the source switches are
 *   phase 4).
 * - `stash:play`: the songs matched by name and queued in Stash ([RecordPlayer]); a newer tap replaces one still matching.
 * - `stash:art`: covers looked up here ([RecordCovers]), handed back by the record's own names as each batch lands, then the
 *   whole list with null for none (and any found later, after that).
 * - `stash:import`: the Spotify import is phase 3; for now the listener is told it's coming.
 * - `stash:close`: [RecordEvent.Close].
 *
 * Back ([back]) asks the record to close what it has open ([RecordMessages.BACK]); it answers `stash:close` when nothing was.
 * A page that never said hello (or has stopped answering, [unresponsive]) can't answer, so back closes at once.
 *
 * Main thread: [onMessage], [back], [setTheme] and [detach] are called there, and every post is made there ([main]).
 */
class RecordHost(
    private val scope: CoroutineScope,
    private val archive: RecordArchiveSource,
    private val covers: RecordCovers,
    private val player: RecordPlayer,
    private val events: (RecordEvent) -> Unit,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
    private val background: CoroutineContext = Dispatchers.Default,
    private val chunkSize: Int = CHUNK_ROWS,
) {
    /** The page that last said hello: where the archive, the theme and back go. */
    private var current: RecordPort? = null

    /** Hellos answered so far; a stream posts only while its own is the latest. */
    private var hellos = 0
    private var stream: Job? = null
    private var playing: Job? = null
    private var light = false

    /** The page's renderer stopped answering: back closes without asking it. */
    var unresponsive = false

    fun onMessage(raw: String, from: RecordPort) {
        when (val ask = RecordMessages.read(raw) ?: return) {
            is RecordAsk.Hello -> hello(ask, from)
            is RecordAsk.Ready -> {
                Log.i(TAG, "the record is ready: ${ask.plays} plays")
                from.post(RecordMessages.theme(light))
            }
            is RecordAsk.Counts -> Log.i(TAG, "the record's sources: spotify ${ask.spotify}, stash ${ask.stash}, lastfm ${ask.lastfm}")
            is RecordAsk.Play -> play(ask)
            is RecordAsk.Art -> art(ask, from)
            RecordAsk.Import -> events(RecordEvent.Say(IMPORT_COMING))
            RecordAsk.Close -> events(RecordEvent.Close)
        }
    }

    /** Back pressed over the record. */
    fun back() {
        val page = current
        if (page == null || unresponsive) events(RecordEvent.Close) else page.post(RecordMessages.BACK)
    }

    /** The app's theme; the record hears of a change at once, and of the theme with every hello and ready. */
    fun setTheme(light: Boolean) {
        if (this.light == light) return
        this.light = light
        current?.post(RecordMessages.theme(light))
    }

    /** The page is gone (closed, or its renderer died): nothing more is sent to it. */
    fun detach() {
        stream?.cancel()
        stream = null
        current = null
        hellos++
        unresponsive = false
    }

    private fun hello(ask: RecordAsk.Hello, from: RecordPort) {
        if (ask.v != RecordMessages.PROTOCOL) Log.w(TAG, "the record speaks bridge v${ask.v}; this app speaks v${RecordMessages.PROTOCOL}")
        stream?.cancel()
        current = from
        unresponsive = false
        val mine = ++hellos
        from.post(RecordMessages.theme(light))
        stream = scope.launch {
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
                    events(RecordEvent.Say(ARCHIVE_FAILED))
                }
            }
        }
    }

    private fun play(ask: RecordAsk.Play) {
        playing?.cancel()
        playing = scope.launch {
            val note = try {
                player.play(ask.songs, ask.index, ask.from)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a play from the record failed: ${e.javaClass.simpleName}", e)
                PLAY_FAILED
            }
            note?.let { events(RecordEvent.Say(it)) }
        }
    }

    private fun art(ask: RecordAsk.Art, from: RecordPort) {
        val asks = ask.asks
        // web: a name with no title is an artist; a song's or album's words otherwise (the title stands in for an artist's)
        val songs = asks.map { if (it.isArtist) ArtSong(it.artist, it.artist, isArtist = true) else ArtSong(it.title!!, it.artist, it.album) }
        scope.launch {
            // Called where covers() runs (this scope's thread, the main one), so it posts in order, before the final list
            val found: (List<String?>) -> Unit = { urls ->
                val list = asks.indices.mapNotNull { i -> urls.getOrNull(i)?.let { asks[i].id to it } }
                if (list.isNotEmpty()) from.post(RecordMessages.covers(list))
            }
            try {
                val urls = covers.covers(songs, scope, found)
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

        const val IMPORT_COMING = "Bringing in your Spotify history is coming in an update."
        const val PLAY_FAILED = "Couldn't play that here."
        const val ARCHIVE_FAILED = "Some of your plays couldn't be read."
    }
}
