package com.stash.feature.record.lastfm

import android.content.Context
import android.util.Log
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.lastfm.recap.LastFmHistoryPacer
import com.stash.feature.record.imports.ImportState
import com.stash.feature.record.imports.RecordImportStore
import com.stash.feature.record.imports.RecordImports
import com.stash.feature.record.imports.RecordWrites
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Your Last.fm scrobbles coming in, as the screens show it (service.ts `LastfmState`). */
sealed interface LastfmState {
    data object Idle : LastfmState

    /** Reading; [progress] null until Last.fm has said how many pages there are ("Asking Last.fm…"). */
    data class Working(val progress: LastfmProgress?) : LastfmState

    /** A run finished: [added] scrobbles came in. */
    data class Done(val added: Int) : LastfmState

    data class Failed(val reason: LastfmFailure) : LastfmState
}

/**
 * Your Last.fm scrobbles in Your record, the app's side (stash-player `src/lib/spotify/service.ts`, its Last.fm half): the
 * import's state for the sheet and Settings, and the runs that change it. It runs beside the Spotify import, never inside its
 * steps.
 *
 * - [bringIn] (Bring them in, Carry on, Bring in newer, Try again): the rest of the connected account's history, or only what is
 *   newer once it's all in. Run by [RecordLastfmWorker], a foreground job with a notification, since a whole history is hundreds
 *   of pages; [ImportScrobbles] does the reading, its mark saved after every page. Refused while the Spotify import works (the
 *   sheet says "After the Spotify import running now").
 * - [topUp] (opening Your record): quiet, at most every [TOPUP_MS], only once the whole history is in and the same account is
 *   still connected; never an error on screen.
 * - [stop]: the run ends at the next page and carries on from there next time. A run the system stops (or the app's death) runs
 *   again by itself (WorkManager), from its mark.
 * - [remove]: every scrobble and the mark go; with nothing left, the import record too. Refused while either import works.
 *
 * A run holds the archive's write lock ([RecordWrites]) from its first page to its import record, as the web holds its lock.
 */
@Singleton
class RecordLastfm internal constructor(
    /** A store for one run (its per-title cache lives as long as the run). */
    private val newStore: () -> ScrobbleStore,
    /** Where the import stands, as kept now (read straight from the store: the run's own checks never lag behind it)... */
    private val markNow: () -> LastfmMark?,
    /** ...and as it changes, for the screens. */
    markFlow: Flow<LastfmMark?>,
    /** The connected Last.fm account; the first value is the stored one. */
    private val accountFlow: Flow<LastfmAccount?>,
    /** This build can connect to Last.fm at all (an API key): Your record offers Last.fm only then. */
    val configured: Boolean,
    private val remote: LastfmRemote,
    private val writes: RecordWrites,
    /** The Spotify import is reading or writing now: a run waits for it. */
    private val spotifyBusy: () -> Boolean,
    /** The import record over the archive as it is now, after a run (under the lock). */
    private val recordAfter: suspend () -> Unit,
    /** ...and after the scrobbles were removed. */
    private val recordAfterRemoved: suspend () -> Unit,
    /** The scrobbles changed: an open record presses itself again. */
    private val changed: () -> Unit,
    /** Starts the job that calls [run] ([quiet]: a top-up, no notification); a direct call in tests. */
    private val launch: (quiet: Boolean) -> Unit,
    /** Stops the job (the person's Stop). */
    private val cancelJob: () -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val gapMs: Long = ImportScrobbles.PAGE_GAP_MS,
    private val busyMs: Long = ImportScrobbles.BUSY_WAIT_MS,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        dao: RecordPlayDao,
        store: RecordImportStore,
        imports: RecordImports,
        writes: RecordWrites,
        session: LastFmSessionPreference,
        api: LastFmApiClient,
        pacer: LastFmHistoryPacer,
    ) : this(
        newStore = { DaoScrobbleStore(dao, store) },
        markNow = { store.info.value.lastfm },
        markFlow = store.info.map { it.lastfm },
        accountFlow = session.session.map { it?.let { s -> LastfmAccount(s.username, s.sessionKey) } }.distinctUntilChanged(),
        configured = api.isConfigured,
        remote = ClientLastfmRemote(api, pacer),
        writes = writes,
        spotifyBusy = { imports.state.value is ImportState.Working },
        recordAfter = imports::recordAfterScrobbles,
        recordAfterRemoved = imports::recordAfterScrobblesRemoved,
        changed = imports::archiveWasChanged,
        launch = { quiet -> RecordLastfmWorker.start(context, quiet) },
        cancelJob = { RecordLastfmWorker.cancel(context) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** Where the import stands, for the screens. */
    val mark: StateFlow<LastfmMark?> = markFlow.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, markNow())

    private val _state = MutableStateFlow<LastfmState>(LastfmState.Idle)
    val state: StateFlow<LastfmState> = _state.asStateFlow()

    /** The connected account for the screens: null until read, then [Connected] with the name or none. */
    val account: StateFlow<Connected?> = accountFlow.map { Connected(it) }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The connected account as read ([account] null: not read yet). */
    data class Connected(val account: LastfmAccount?)

    private val lock = Any()

    /** A run is going (asked, or under way in the job)... */
    private var running = false

    /** ...and the job has taken it up. */
    private var started = false

    /** The run going is a quiet top-up: its progress isn't shown (until the person asks for one: [bringIn]). */
    @Volatile var quiet = false
        private set

    /** The latest progress of the run going, shown if it stops being quiet. */
    private var latest: LastfmProgress? = null

    /**
     * service.ts `importLastfm`: the rest of the connected account's scrobbles, or the newer ones once all are in. Nothing while
     * one runs (a quiet top-up running shows its progress from now on) or while the Spotify import works.
     */
    fun bringIn() {
        if (!configured) return
        synchronized(lock) {
            if (running) {
                if (quiet) {
                    quiet = false
                    _state.value = LastfmState.Working(latest)
                }
                return
            }
            if (spotifyBusy()) return
            running = true
            started = false
            quiet = false
            latest = null
            _state.value = LastfmState.Working(null)
        }
        launch(false)
    }

    /**
     * service.ts `topUpLastfm`: newer scrobbles, quietly, when they were all brought in before, the same account is still
     * connected and it's been [TOPUP_MS] since the last run. Opening Your record asks for it.
     */
    fun topUp() {
        if (!configured) return
        scope.launch {
            val m = markNow() ?: return@launch
            val acc = accountFlow.first() ?: return@launch
            if (!m.done || m.user != acc.name || now() - m.at * 1000 < TOPUP_MS) return@launch
            synchronized(lock) {
                if (running || spotifyBusy()) return@launch
                running = true
                started = false
                quiet = true
                latest = null
            }
            launch(true)
        }
    }

    /** service.ts `stopLastfm`: the run stops at the next page or pause; it carries on from there next time. */
    fun stop() {
        cancelJob()
        synchronized(lock) {
            // A run the job hadn't taken up yet never will: idle now (one under way says so as it ends)
            if (running && !started) {
                running = false
                quiet = false
                _state.value = LastfmState.Idle
            }
        }
    }

    /** Back to idle after what a run said (the sheet closed on "done", or an error dismissed). */
    fun reset() {
        synchronized(lock) {
            if (!running) _state.value = LastfmState.Idle
        }
    }

    /**
     * service.ts `removeLastfm`: every scrobble out again, and the mark; the Spotify export's plays stay, and with nothing left
     * the import record goes too. False, and nothing done, while either import works.
     */
    suspend fun remove(): Boolean {
        if (synchronized(lock) { running } || spotifyBusy()) return false
        writes.mutex.withLock {
            ImportScrobbles.remove(newStore())
            recordAfterRemoved()
        }
        changed()
        synchronized(lock) { if (!running) _state.value = LastfmState.Idle }
        return true
    }

    /**
     * One run, by the job: the connected account's scrobbles ([ImportScrobbles.run]) under the write lock, then the import
     * record. [onProgress] keeps the job's notification up to date. What the run says (done, an error) shows unless it is quiet;
     * a stop (the person's, or the system's) says nothing and leaves the mark where it got to.
     */
    internal suspend fun run(quietAsked: Boolean, onProgress: (LastfmProgress?) -> Unit = {}) {
        val acc = accountFlow.first()
        synchronized(lock) {
            if (!running) {
                // The job ran again by itself (after a system stop, or the app's death): a run under way once more
                running = true
                quiet = quietAsked
                latest = null
            }
            started = true
            if (!quiet) _state.value = LastfmState.Working(latest)
        }
        val before = markNow()
        var outcome: LastfmState = LastfmState.Idle
        try {
            if (acc == null) throw LastfmError(LastfmFailure.RECONNECT)
            val m = writes.mutex.withLock {
                writes.setLastfmRunning(true)
                try {
                    val nowS = now() / 1000
                    val reader = LastfmPages(remote, acc.name, account = { accountFlow.first() }, nowS = { now() / 1000 })
                    val m = ImportScrobbles.run(newStore(), reader, acc.name, nowS, progress = { report(it, onProgress) }, gapMs = gapMs, busyMs = busyMs)
                    recordAfter()
                    m
                } finally {
                    writes.setLastfmRunning(false)
                }
            }
            val had = before?.takeIf { it.user == acc.name }?.added ?: 0
            Log.i(TAG, "Last.fm: ${m.added - had} scrobbles in (${m.added} in all, ${m.covered} another source had), page ${m.next - 1} of ${m.pages}")
            outcome = LastfmState.Done(m.added - had)
        } catch (e: CancellationException) {
            // Stopped: by the person (it carries on next time), or by the system (the job runs again by itself)
            Log.i(TAG, "Last.fm stopped at page ${(markNow()?.next ?: 1) - 1}")
            throw e
        } catch (e: LastfmError) {
            Log.w(TAG, "Last.fm stopped short: ${e.kind}")
            outcome = LastfmState.Failed(e.kind)
        } catch (e: Exception) {
            Log.w(TAG, "Last.fm failed: ${e.javaClass.simpleName}", e)
            outcome = LastfmState.Failed(LastfmFailure.OTHER)
        } finally {
            synchronized(lock) {
                running = false
                started = false
                _state.value = if (quiet) LastfmState.Idle else outcome
                quiet = false
            }
            val after = markNow()
            if (after?.added != before?.added || after?.covered != before?.covered || after?.user != before?.user) changed()
        }
    }

    private fun report(p: LastfmProgress, onProgress: (LastfmProgress?) -> Unit) {
        synchronized(lock) {
            latest = p
            if (!quiet) _state.value = LastfmState.Working(p)
        }
        onProgress(p)
    }

    /** The latest progress of the run going, for its notification. */
    internal val progress: LastfmProgress? get() = synchronized(lock) { latest }

    companion object {
        private const val TAG = "YourRecord"

        /** service.ts `LASTFM_TOPUP_MS`: Last.fm tops up at most this often by itself. */
        const val TOPUP_MS = 60 * 60_000L
    }
}
