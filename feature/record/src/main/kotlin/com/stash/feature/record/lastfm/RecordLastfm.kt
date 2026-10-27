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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import kotlin.coroutines.CoroutineContext

/**
 * A run asked for: [quiet] (a top-up), for the account [user] (the job does nothing for another one connected since), under the
 * scrobbles' [generation] (a removal moves it on: a job asked for before it does nothing). The job keeps it in its input.
 */
data class LastfmAsk(val quiet: Boolean, val user: String, val generation: Long)

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
 * - [remove]: every scrobble and the mark go; with nothing left, the import record too. Refused while a run is under way or the
 *   Spotify import works. The job goes with them, and a job that slips through finds its generation gone ([LastfmAsk]).
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
    /** Starts the job that calls [run] with the run asked for (a top-up: quiet, no notification); a direct call in tests. */
    private val launch: (LastfmAsk) -> Unit,
    /** Stops the job (the person's Stop). */
    private val cancelJob: () -> Unit,
    private val scope: CoroutineScope,
    /** The scrobbles' generation now (moved on by each removal, kept between runs)... */
    private val generation: () -> Long,
    /** ...moved on (under the write lock)... */
    private val nextGeneration: () -> Unit,
    /** ...and as it changes. */
    generations: Flow<Long>,
    /** Where a removal runs: off the main thread. */
    private val io: CoroutineContext = Dispatchers.IO,
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
        launch = { ask -> RecordLastfmWorker.start(context, ask) },
        cancelJob = { RecordLastfmWorker.cancel(context) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        generation = { store.lastfmGeneration },
        nextGeneration = store::nextLastfmGeneration,
        generations = store.info.map { it.lastfmGeneration },
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

    /** The run asked for in this process and not taken up by the job yet, in its latest form (the job's own input is older). */
    private var asked: LastfmAsk? = null

    init {
        // A Remove import (or this service's own remove) moved the scrobbles on: a run asked for before it never comes
        scope.launch {
            generations.distinctUntilChanged().collect { gen ->
                synchronized(lock) {
                    val stale = asked?.takeIf { it.generation != gen } ?: return@synchronized
                    if (running && !started && asked === stale) idleLocked()
                }
            }
        }
    }

    /**
     * service.ts `importLastfm`: the rest of the connected account's scrobbles, or the newer ones once all are in. Nothing while
     * one runs (a quiet top-up running shows its progress from now on; one not taken up yet takes this account) or while the
     * Spotify import works.
     */
    fun bringIn() {
        if (!configured) return
        val user = account.value?.account?.name ?: return
        val ask = synchronized(lock) {
            if (running) {
                if (quiet) {
                    quiet = false
                    _state.value = LastfmState.Working(latest)
                }
                if (!started) asked = asked?.copy(quiet = false, user = user)
                return
            }
            if (spotifyBusy()) return
            LastfmAsk(quiet = false, user = user, generation = generation()).also { beginLocked(it) }
        }
        launch(ask)
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
            val ask = synchronized(lock) {
                if (running || spotifyBusy()) return@launch
                LastfmAsk(quiet = true, user = acc.name, generation = generation()).also { beginLocked(it) }
            }
            launch(ask)
        }
    }

    /** service.ts `stopLastfm`: the run stops at the next page or pause; it carries on from there next time. */
    fun stop() {
        cancelJob()
        synchronized(lock) {
            // A run the job hadn't taken up yet never will: idle now (one under way says so as it ends)
            if (running && !started) idleLocked()
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
     * the import record goes too. False, and nothing done, while a run is under way or the Spotify import works.
     *
     * The job goes first (a run queued, or one the system stopped, would otherwise come back and bring the whole history in
     * again) and the scrobbles' generation moves on, so a job that slips through does nothing. Run in the app's scope, never the
     * screen's (leaving Settings mid-way can't cut it in two), off the main thread; the mark goes before the rows, so a removal
     * cut short by the app's death leaves no "all in" mark over rows that are gone (a missing mark only means a full read).
     */
    suspend fun remove(): Boolean {
        if (synchronized(lock) { running && started } || spotifyBusy()) return false
        return scope.async(io + NonCancellable) {
            writes.mutex.withLock {
                dropQueued()
                val store = newStore()
                ImportScrobbles.remove(store)
                recordAfterRemoved()
            }
            changed()
            synchronized(lock) { if (!running) _state.value = LastfmState.Idle }
            true
        }.await()
    }

    /** Under the write lock: the job (queued or stopped) goes, and a run asked for before now never comes. */
    private fun dropQueued() {
        cancelJob()
        nextGeneration()
        synchronized(lock) { if (running && !started) idleLocked() }
    }

    /**
     * One run, by the job: the connected account's scrobbles ([ImportScrobbles.run]) under the write lock, then the import
     * record. [onProgress] keeps the job's notification up to date. What the run says (done, an error) shows unless it is quiet;
     * a stop (the person's, or the system's) says nothing and leaves the mark where it got to.
     *
     * It takes the run asked for in this process ([asked]), else the job's own [input] (a job run again after a system stop or
     * the app's death). It does nothing for another account than the one it was asked for (the person connected another one
     * since) or for scrobbles removed since it was asked (a generation gone).
     */
    internal suspend fun run(input: LastfmAsk, onProgress: (LastfmProgress?) -> Unit = {}) {
        val ask = synchronized(lock) {
            val a = asked ?: input
            asked = null
            if (!running) {
                running = true
                quiet = a.quiet
                latest = null
            }
            started = true
            if (!quiet) _state.value = LastfmState.Working(latest)
            a
        }
        var before: LastfmMark? = null
        // The run took the archive (and read the mark then): what it wrote may change the record
        var took = false
        var outcome: LastfmState = LastfmState.Idle
        try {
            val acc = accountFlow.first() ?: throw LastfmError(LastfmFailure.RECONNECT)
            if (acc.name != ask.user) {
                Log.i(TAG, "Last.fm: asked for another account than the one connected now; nothing read")
                return
            }
            val m = writes.mutex.withLock {
                if (generation() != ask.generation) {
                    Log.i(TAG, "Last.fm: the scrobbles were removed since this run was asked for; nothing read")
                    return@withLock null
                }
                before = markNow()
                took = true
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
            } ?: return
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
            if (took) {
                val was = before
                val after = markNow()
                if (after?.added != was?.added || after?.covered != was?.covered || after?.user != was?.user) changed()
            }
        }
    }

    /** A run asked for: shown under way (unless quiet) until the job takes it up. Under [lock]. */
    private fun beginLocked(ask: LastfmAsk) {
        asked = ask
        running = true
        started = false
        quiet = ask.quiet
        latest = null
        if (!ask.quiet) _state.value = LastfmState.Working(null)
    }

    /** No run after all. Under [lock]. */
    private fun idleLocked() {
        running = false
        started = false
        quiet = false
        asked = null
        _state.value = LastfmState.Idle
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
