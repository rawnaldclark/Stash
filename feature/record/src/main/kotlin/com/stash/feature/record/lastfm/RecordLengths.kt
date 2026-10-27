package com.stash.feature.record.lastfm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.stash.core.data.db.dao.RecordLengthDao
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.lastfm.recap.LastFmRecapRepository
import com.stash.core.data.lastfm.recap.LastFmSongLength
import com.stash.feature.record.imports.RecordImportStore
import com.stash.feature.record.imports.RecordImports
import com.stash.feature.record.imports.RecordWrites
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Your record's lengths pass, the app's side (stash-player `src/lib/spotify/service.ts` `timeSongs`): it times the scrobbles still
 * guessed ([Lengths]), a slice at a time under the archive's write lock ([RecordWrites]), until nothing is left to ask, Last.fm
 * stops it, or it is stopped. Quiet: no notification, nothing on screen but the hours coming right.
 *
 * - [start]: after every Last.fm run, whole read or top-up, including one that stopped short (but not one the person stopped, or
 *   one whose session ended), and after a Spotify commit once Last.fm has been brought in. Run by [RecordLengthsWorker], a plain
 *   job: one the system stops (its time is up, the network went) runs again by itself, and plans afresh, since timed songs drop
 *   out of the plan. The pass running now carries on (the web returns it).
 * - [pause]: a Last.fm run, a Spotify commit or a removal stops the pass first and waits for it to let go of the lock; a pass
 *   stopped while it waits for the lock reads and writes nothing.
 * - [stop]: the person's Stop on a Last.fm run stops the pass too.
 *
 * The hours on screen follow: the import record again (under the lock) and the card and an open record read the plays again
 * after the first slice that timed songs, then at most every [REFRESH_MS], and at the end. A pass stopped part way leaves the
 * record to read them again ([changed]) and the card to its next read.
 */
@Singleton
class RecordLengths internal constructor(
    /** A store for one slice (it reads the mark afresh). */
    private val newStore: () -> LengthsStore,
    private val reader: LengthReader,
    /** The library's song lengths ([Scrobbles.libraryLengths]), read again for each slice. */
    private val library: suspend () -> Map<String, Long>,
    private val writes: RecordWrites,
    /** The import record over the archive as it is now (service.ts `storeRecord`), under the lock. */
    private val recordAfter: suspend () -> Unit,
    /** The plays changed: an open record reads them again. */
    private val changed: () -> Unit,
    /** Starts the job that calls [run] (a direct call in tests). */
    private val launch: () -> Unit,
    /** Stops the job, queued or running. */
    private val cancelJob: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** A monotonic clock (a slice's time, the refreshes'). */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val gapMs: Long = ImportScrobbles.PAGE_GAP_MS,
    private val busyMs: Long = ImportScrobbles.BUSY_WAIT_MS,
    private val sliceMs: Long = Lengths.SLICE_MS,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        dao: RecordPlayDao,
        lengths: RecordLengthDao,
        own: WebLibraryExportDao,
        store: RecordImportStore,
        imports: RecordImports,
        writes: RecordWrites,
        session: LastFmSessionPreference,
        recap: LastFmRecapRepository,
    ) : this(
        newStore = { DaoLengthsStore(dao, lengths, store) },
        reader = ProxyLengthReader(connected = { session.session.first() != null }, ask = recap::songLength),
        library = { libraryLengths(own) },
        writes = writes,
        recordAfter = imports::recordAfterLengths,
        changed = imports::archiveWasChanged,
        launch = { RecordLengthsWorker.start(context) },
        cancelJob = { RecordLengthsWorker.cancel(context) },
    )

    private val lock = Any()

    /** The pass running in this process, to stop and wait for. */
    private var running: Job? = null

    /** service.ts `timeSongs`: the pass, in the background; the one running now carries on. */
    fun start() = launch()

    /** service.ts `pauseLengths`: stops the pass and waits for it to let go of the lock (another writer is next); whether one was running. */
    suspend fun pause(): Boolean {
        cancelJob()
        val job = synchronized(lock) { running } ?: return false
        job.cancelAndJoin()
        return true
    }

    /** The person's Stop (service.ts `stopLastfm`): the pass ends, and nothing runs again by itself. */
    fun stop() {
        cancelJob()
        synchronized(lock) { running }?.cancel()
    }

    /** The pass, by the job: slices until nothing is left, Last.fm stops it, or it is stopped ([pause], [stop], the system). */
    internal suspend fun run() = coroutineScope {
        val job = launch { slices() }
        synchronized(lock) { running = job }
        try {
            job.join()
        } finally {
            synchronized(lock) { if (running === job) running = null }
        }
    }

    private suspend fun slices() {
        // Timed songs not on screen yet, and when they last were
        var dirty = false
        var shownAt: Long? = null
        var timed = 0
        writes.setLengthsRunning(true)
        try {
            while (true) {
                var show = false
                val r = writes.mutex.withLock {
                    val r = Lengths.fill(newStore(), reader, now() / 1000, library(), Lengths.SLICE, sliceMs, gapMs, busyMs, clock) { n ->
                        // As each song's rows are written: a slice stopped part way has still changed the hours
                        dirty = true
                        timed += n
                    }
                    val end = r.left == 0 || r.stopped != null
                    if (dirty && (end || shownAt.let { it == null || clock() - it >= REFRESH_MS })) {
                        recordAfter()
                        dirty = false
                        shownAt = clock()
                        show = true
                    }
                    r
                }
                if (show) {
                    writes.showLengths()
                    changed()
                }
                if (r.left == 0 || r.stopped != null) {
                    Log.i(TAG, "the lengths pass ${if (r.stopped == null) "is done" else "stopped: ${r.stopped}"}: $timed rows timed")
                    break
                }
            }
        } finally {
            writes.setLengthsRunning(false)
            // Stopped part way with songs timed since the record last read them
            if (dirty) changed()
        }
    }

    companion object {
        private const val TAG = "YourRecord"

        /** service.ts `LENGTHS_REFRESH_MS`: while the pass runs, the hours on screen are worked out again at most this often. */
        const val REFRESH_MS = 2 * 60_000L

        /** The library's song lengths (spec §7, step 2), folded by song key. */
        internal suspend fun libraryLengths(own: WebLibraryExportDao): Map<String, Long> =
            Scrobbles.libraryLengths(own.libraryLengths().map { Triple(it.title, it.artist, it.durationMs.toDouble()) })
    }
}

/**
 * The pass's length reader ([LengthReader]) over the Last.fm recap's one-song ask ([LastFmRecapRepository.songLength]: through
 * Stash's proxy only, its answers cached on the phone): the web's `httpLengthReader` with its Worker. Behind the connected
 * account, as the web's route is behind the session: with none, the pass stops as for a reconnect.
 */
internal class ProxyLengthReader(
    private val connected: suspend () -> Boolean,
    private val ask: suspend (artist: String, title: String) -> LastFmSongLength,
) : LengthReader {
    override suspend fun look(artist: String, track: String): LengthAnswer {
        if (!connected()) throw LastfmError(LastfmFailure.RECONNECT)
        return when (val r = ask(artist, track)) {
            is LastFmSongLength.Known -> LengthAnswer(r.ms, hit = r.cached)
            is LastFmSongLength.None -> LengthAnswer(null, hit = r.cached)
            LastFmSongLength.Busy -> throw LastfmError(LastfmFailure.BUSY)
            LastFmSongLength.Unavailable -> throw LastfmError(LastfmFailure.UNAVAILABLE)
        }
    }
}
