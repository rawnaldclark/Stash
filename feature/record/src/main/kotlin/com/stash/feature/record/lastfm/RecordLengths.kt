package com.stash.feature.record.lastfm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.stash.core.data.db.dao.RecordLengthDao
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.recap.LastFmRecapRepository
import com.stash.core.data.lastfm.recap.LastFmSongLength
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.feature.record.imports.RecordImportStore
import com.stash.feature.record.imports.RecordImports
import com.stash.feature.record.imports.RecordWrites
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Your record's lengths pass, the app's side (stash-player `src/lib/spotify/service.ts` `timeSongs`): it times the scrobbles still
 * guessed ([Lengths]), planned once a run and asked a slice at a time under the archive's write lock ([RecordWrites]), until
 * nothing is left to ask, Last.fm stops it, a limit holds it, or it is stopped. Quiet: no notification, nothing on screen but the
 * hours coming right. It needs no Last.fm session: its lookups go through Stash's proxy with the proxy's key.
 *
 * The free fill (the songs Spotify or the library times, no lookup) never waits for a network: a pass asked for runs at once, with
 * no network asked of it. Its lookups go only on a network the app's Wi-Fi-only setting allows (as its syncs and cloud uploads
 * do): with none, the run ends after the fill and comes back, as a job waiting for that network, behind it. A change of the
 * setting applies to a job still waiting.
 *
 * - [start]: after every Last.fm run, whole read or top-up, including one that stopped short (but not one the person stopped, or
 *   one whose session ended), and after a Spotify commit once Last.fm has been brought in. Run by [RecordLengthsWorker], a plain
 *   job on an unmetered network: one the system stops runs again by itself, and plans afresh. The pass running now carries on.
 * - [pause]: a Last.fm run, a Spotify commit or a removal stops the pass first and waits for it to let go of the lock (an ask in
 *   flight is cancelled, never waited out); a pass stopped while it waits for the lock reads and writes nothing.
 * - [stop]: the person's Stop on a Last.fm run stops the pass too.
 * - Limits: the lookups are this device's share of the proxy's key, which every listener's scrobbling also uses: at most
 *   [DAILY_LOOKUPS] in a rolling day ([LookupBudget]), at least [LENGTH_GAP_MS] apart, and a rate limit holds them all for
 *   [LIMITED_WAIT_MS]. A run held by one still fills what needs no lookup, then ends and comes back by itself when it lifts
 *   ([RecordLengthsWorker.wakeAt]); the songs go most played first, so the first day puts most of the hours right. Without a
 *   proxy there are no lookups at all, and nothing is marked.
 *
 * The hours on screen follow: the import record again (under the lock) and the card and an open record read the plays again
 * after the first slice that timed songs, then at most every [REFRESH_MS], and at the end. A pass stopped part way leaves the
 * record to read them again ([changed]) and the card to its next read.
 */
@Singleton
class RecordLengths internal constructor(
    /** A store for one run (it keeps what it has written). */
    private val newStore: () -> LengthsStore,
    private val reader: LengthReader,
    /** The library's song lengths ([Scrobbles.libraryLengths]), read once a run. */
    private val library: suspend () -> Map<String, Long>,
    private val writes: RecordWrites,
    /** The import record over the archive as it is now (service.ts `storeRecord`), under the lock. */
    private val recordAfter: suspend () -> Unit,
    /** The plays changed: an open record reads them again. */
    private val changed: () -> Unit,
    /** Starts the job that calls [run] (a direct call in tests). */
    private val launch: () -> Unit,
    /** Stops the job, queued, running or waiting to come back. */
    private val cancelJob: () -> Unit,
    /** A run held by a limit comes back at this time (epoch ms). */
    private val wakeAt: (Long) -> Unit = {},
    /** A run held for a network comes back as a job waiting for one (the Wi-Fi-only setting now, and how long it waits first). */
    private val waitForNetwork: (wifiOnly: Boolean, delayMs: Long) -> Unit = { _, _ -> },
    /** The app's Wi-Fi-only setting, as it changes (a job waiting for a network takes a change: [applyWifiOnly]). */
    private val wifiOnly: Flow<Boolean> = emptyFlow(),
    /** A job still waiting for a network, waiting by the setting given. */
    private val applyWifiOnly: suspend (Boolean) -> Unit = {},
    /** Where the setting is followed; none in tests. */
    scope: CoroutineScope? = null,
    /** Whether lookups could go now ([LengthHold.NETWORK], [LengthHold.NO_PROXY]: no): asked before planning a network-waiting run. */
    private val gate: suspend () -> LengthHold? = { null },
    /** Network-waiting runs in a row held again for want of one, kept across the app's death ([networkBackoffMs]). */
    private val loadHolds: () -> Int = { 0 },
    private val saveHolds: (Int) -> Unit = {},
    /** The day's lookups, written back once a slice. */
    private val budget: LookupBudget? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** A monotonic clock (a slice's time, the refreshes'). */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val gapMs: Long = LENGTH_GAP_MS,
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
        recap: LastFmRecapRepository,
        api: LastFmApiClient,
        sync: SyncPreferencesManager,
    ) : this(
        context = context,
        dao = dao,
        lengths = lengths,
        own = own,
        store = store,
        imports = imports,
        writes = writes,
        recap = recap,
        api = api,
        sync = sync,
        budget = LookupBudget(DAILY_LOOKUPS, load = { store.lookupBudget }, save = { store.lookupBudget = it }),
    )

    private constructor(
        context: Context,
        dao: RecordPlayDao,
        lengths: RecordLengthDao,
        own: WebLibraryExportDao,
        store: RecordImportStore,
        imports: RecordImports,
        writes: RecordWrites,
        recap: LastFmRecapRepository,
        api: LastFmApiClient,
        sync: SyncPreferencesManager,
        budget: LookupBudget,
    ) : this(
        context = context,
        dao = dao,
        lengths = lengths,
        own = own,
        store = store,
        imports = imports,
        writes = writes,
        recap = recap,
        sync = sync,
        budget = budget,
        // No proxy, or no network the Wi-Fi-only setting allows: no lookup (the fill goes on all the same)
        gate = {
            when {
                !api.hasProxy -> LengthHold.NO_PROXY
                !LengthsNetwork.ready(sync.preferences.first().wifiOnly) { LengthsNetwork.state(context, it) } -> LengthHold.NETWORK
                else -> null
            }
        },
    )

    private constructor(
        context: Context,
        dao: RecordPlayDao,
        lengths: RecordLengthDao,
        own: WebLibraryExportDao,
        store: RecordImportStore,
        imports: RecordImports,
        writes: RecordWrites,
        recap: LastFmRecapRepository,
        sync: SyncPreferencesManager,
        budget: LookupBudget,
        gate: suspend () -> LengthHold?,
    ) : this(
        newStore = { DaoLengthsStore(dao, lengths, store) },
        reader = GatedLengthReader(BudgetedLengthReader(ProxyLengthReader(ask = recap::songLength), budget), gate),
        library = { libraryLengths(own) },
        writes = writes,
        recordAfter = imports::recordAfterLengths,
        changed = imports::archiveWasChanged,
        launch = { RecordLengthsWorker.start(context) },
        cancelJob = { RecordLengthsWorker.cancel(context) },
        wakeAt = { RecordLengthsWorker.wakeAt(context, it) },
        waitForNetwork = { wifiOnly, delayMs -> RecordLengthsWorker.waitForNetwork(context, wifiOnly, delayMs) },
        wifiOnly = sync.preferences.map { it.wifiOnly },
        applyWifiOnly = { RecordLengthsWorker.applyWifiOnly(context, it) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "the lengths pass's setting follower failed", e) }),
        gate = gate,
        loadHolds = { store.lengthsNetworkHolds },
        saveHolds = { store.lengthsNetworkHolds = it },
        budget = budget,
    )

    private val lock = Any()

    /** The pass running in this process, to stop and wait for... */
    private var running: Job? = null

    /** ...and its token: only the run holding it may go on, ask for a wake-up, or let the card go. */
    private var current: Any? = null

    /** The Wi-Fi-only setting as last seen changing (a run held for a network waits by it); null: not read yet. */
    private val wifiOnlyNow = MutableStateFlow<Boolean?>(null)

    init {
        scope?.launch {
            wifiOnly.distinctUntilChanged().collect {
                wifiOnlyNow.value = it
                // A failure here never ends the following (the next change is applied all the same)
                try {
                    applyWifiOnly(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "the Wi-Fi-only setting didn't reach the lengths pass's waiting job", e)
                }
            }
        }
    }

    /** service.ts `timeSongs`: the pass, in the background, at once (the free fill needs no network). */
    fun start() = launch()

    /**
     * service.ts `pauseLengths`: stops the pass and waits for it to let go of the lock (another writer is next); whether one was
     * running. The job and any wake-up go in the same step as the run's token, so a run ending meanwhile can't ask for one after.
     */
    suspend fun pause(): Boolean {
        val job = synchronized(lock) {
            current = null
            cancelJob()
            running
        } ?: return false
        job.cancelAndJoin()
        return true
    }

    /** The person's Stop (service.ts `stopLastfm`): the pass ends, and nothing runs again by itself. */
    fun stop() {
        synchronized(lock) {
            current = null
            cancelJob()
            running
        }?.cancel()
    }

    /**
     * The pass, by the job: slices until nothing is left, Last.fm stops it, a limit holds it (it comes back by itself when that
     * lifts), or it is stopped ([pause], [stop], the system). Registered before it reads anything, so a stop never misses it.
     *
     * [waited]: started by the job waiting for a network ([RecordLengthsWorker.waitForNetwork]). Such a run asks the gate before
     * planning (its free fill ran in the run that queued it), and held again it backs off: a network the job's constraint takes but
     * the lookups can't use (one not validated, say) never loops it. A run asked for (opening the record, a run of the import) waits
     * for a network with no delay and leaves the count alone, so it never grows the backoff, however often it comes.
     */
    internal suspend fun run(waited: Boolean = false) {
        val token = Any()
        var end: RunEnd? = null
        coroutineScope {
            val job = launch(start = CoroutineStart.LAZY) { end = slices(token, waited) }
            synchronized(lock) {
                running = job
                current = token
            }
            try {
                job.start()
                job.join()
            } finally {
                synchronized(lock) {
                    if (running === job) running = null
                    // Still this run's (no Remove or Stop since): a wake-up, or a wait for a network; in the lock a stop takes
                    if (current === token) {
                        end?.retryAt?.let(wakeAt)
                        end?.let { e ->
                            if (e.network) {
                                val delayMs = if (waited) networkBackoffMs((loadHolds() + 1).also(saveHolds)) else 0L
                                waitForNetwork(wifiOnlyNow.value ?: e.wifiOnly, delayMs)
                            } else if (loadHolds() != 0) {
                                saveHolds(0)
                            }
                        }
                        current = null
                    }
                }
            }
        }
        // The setting may have changed while the run ended: the wait just queued takes it (the job's own update, never a restart)
        if (end?.network == true) {
            try {
                wifiOnly.firstOrNull()?.let { applyWifiOnly(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the Wi-Fi-only setting didn't reach the lengths pass's waiting job", e)
            }
        }
    }

    /** How a run ended, when it is to come back: at [retryAt] (a limit), or once there is a [network] ([wifiOnly]: unmetered). */
    private data class RunEnd(val retryAt: Long?, val network: Boolean = false, val wifiOnly: Boolean = true)

    private fun isCurrent(token: Any) = synchronized(lock) { current === token }

    /** The run's slices; when to come back, if a hold stopped it. */
    private suspend fun slices(token: Any, waited: Boolean): RunEnd? {
        // Timed songs not on screen yet, and when they last were
        var dirty = false
        var shownAt: Long? = null
        var timed = 0
        // Waiting for a network and still without one: nothing to plan (the run that queued it filled what needed none)
        if (waited) {
            when (gate()) {
                LengthHold.NETWORK -> {
                    Log.i(TAG, "the lengths pass waits for a network still: nothing planned")
                    return RunEnd(null, network = true, wifiOnly = wifiOnly.firstOrNull() ?: true)
                }
                LengthHold.NO_PROXY -> return RunEnd(null)
                else -> {}
            }
        }
        // (Held by a limit or without a network, it still plans and fills what needs no lookup: the first ask stops it)
        writes.beginLengths(token)
        try {
            val store = newStore()
            val began = clock()
            val state = writes.mutex.withLock {
                // Stopped (or another run took over) while it waited for the lock: nothing read, nothing written
                if (!isCurrent(token)) return null
                Lengths.plan(store, now() / 1000, library())
            }
            Log.i(TAG, "the lengths pass planned ${state.left} songs to ask in ${clock() - began} ms")
            while (true) {
                var show = false
                val r = writes.mutex.withLock {
                    if (!isCurrent(token)) return null
                    val r = Lengths.slice(state, store, reader, now() / 1000, Lengths.SLICE, sliceMs, gapMs, busyMs, clock) { n ->
                        // As each slice's rows are written: a slice stopped part way has still changed the hours
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
                budget?.flush()
                if (show) {
                    writes.showLengths()
                    changed()
                }
                if (r.left == 0 || r.stopped != null) {
                    val why = when {
                        r.stopped == null -> "is done"
                        r.hold == LengthHold.LIMIT -> "waits until ${r.retryAt} (a limit)"
                        r.hold == LengthHold.NETWORK -> "waits for a network"
                        r.hold == LengthHold.NO_PROXY -> "has no proxy to ask through"
                        else -> "stopped: ${r.stopped}"
                    }
                    Log.i(TAG, "the lengths pass $why: $timed rows timed, ${r.left} songs left")
                    if (r.hold == LengthHold.NETWORK) return RunEnd(null, network = true, wifiOnly = wifiOnly.firstOrNull() ?: true)
                    return RunEnd(r.retryAt.takeIf { r.hold == LengthHold.LIMIT })
                }
            }
        } finally {
            budget?.flush()
            writes.endLengths(token)
            // Stopped part way with songs timed since the record last read them
            if (dirty) changed()
        }
    }

    companion object {
        private const val TAG = "YourRecord"

        /** service.ts `LENGTHS_REFRESH_MS`: while the pass runs, the hours on screen are worked out again at most this often. */
        const val REFRESH_MS = 2 * 60_000L

        /**
         * The lookups one device may make in a rolling day. They go through Stash's Last.fm proxy, on the proxy's one key, the same
         * key every listener's scrobbling relies on: a whole history's songs must never spend its standing in one go. Most played
         * first, so the first day puts most of the hours right, and the rest follow day by day.
         */
        const val DAILY_LOOKUPS = 2_000

        /**
         * At least this long between lookups that reached the network: the proxy allows 120 lookups a minute per address
         * (`LOOKUP_RL`), shared with every other lookup the app makes and every device behind the same address.
         */
        const val LENGTH_GAP_MS = 1_000L

        /** After a rate limit (the proxy's 429, or Last.fm's 29 behind it), no lookups for this long. */
        const val LIMITED_WAIT_MS = 30 * 60_000L

        /** A network-waiting run held again waits this long before its job may run again, doubling, at most [NETWORK_BACKOFF_MAX_MS]. */
        const val NETWORK_BACKOFF_MS = 15 * 60_000L
        const val NETWORK_BACKOFF_MAX_MS = 6 * 3_600_000L

        /** How long the wait after the [n]th network-waiting run in a row held again is held off (none before any). */
        internal fun networkBackoffMs(n: Int): Long =
            if (n <= 0) 0L else minOf(NETWORK_BACKOFF_MAX_MS, NETWORK_BACKOFF_MS shl minOf(n - 1, 10))

        /** The library's song lengths (spec §7, step 2), folded by song key. */
        internal suspend fun libraryLengths(own: WebLibraryExportDao): Map<String, Long> =
            Scrobbles.libraryLengths(own.libraryLengths().map { Triple(it.title, it.artist, it.durationMs.toDouble()) })
    }
}

/**
 * The pass's length reader ([LengthReader]) over the Last.fm recap's one-song ask ([LastFmRecapRepository.songLength]: through
 * Stash's proxy only, its answers cached on the phone): the web's `httpLengthReader` with its Worker. A rate limit is a limit
 * ([LengthHold.LIMIT]), never waited out in place; no proxy is a hold too, never a song's failure.
 */
internal class ProxyLengthReader(
    private val ask: suspend (artist: String, title: String) -> LastFmSongLength,
    private val now: () -> Long = System::currentTimeMillis,
) : LengthReader {
    override suspend fun look(artist: String, track: String): LengthAnswer = when (val r = ask(artist, track)) {
        is LastFmSongLength.Known -> LengthAnswer(r.ms, hit = r.cached)
        is LastFmSongLength.None -> LengthAnswer(null, hit = r.cached)
        LastFmSongLength.Busy -> throw LastfmError(LastfmFailure.BUSY)
        LastFmSongLength.Limited -> throw LastfmError(LastfmFailure.BUSY, hold = LengthHold.LIMIT, retryAt = now() + RecordLengths.LIMITED_WAIT_MS)
        LastFmSongLength.Unavailable -> throw LastfmError(LastfmFailure.UNAVAILABLE)
        LastFmSongLength.NoProxy -> throw LastfmError(LastfmFailure.UNAVAILABLE, hold = LengthHold.NO_PROXY)
    }
}

/**
 * Whether the network now is one the lengths pass's lookups may use: online (validated, as SyncScheduler counts a network), and
 * unmetered when the app is Wi-Fi only.
 */
internal object LengthsNetwork {
    /** The default network, as the lookups see it. */
    enum class State {
        /** None, or one that can't reach the internet. */
        NONE,

        /** Metered, and the app is Wi-Fi only. */
        UNFIT,

        /** Connected, not validated yet (a network just back: WorkManager may start a job waiting for it before then). */
        VALIDATING,

        /** One the lookups may use. */
        FITS,
    }

    /** A network still validating is given this long before the run is held for want of one. */
    const val VALIDATION_GRACE_MS = 30_000L

    fun state(context: Context, wifiOnly: Boolean): State {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return State.NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return State.NONE
        fun has(c: Int) = caps.hasCapability(c)
        return stateOf(
            wifiOnly = wifiOnly,
            internet = has(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET),
            unmetered = has(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            // API 30+: a metered network the carrier lets go unmetered for now, which JobScheduler counts as unmetered too
            temporarilyUnmetered = android.os.Build.VERSION.SDK_INT >= 30 && has(android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED),
            validated = has(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    /** [state], from the default network's capabilities. */
    internal fun stateOf(wifiOnly: Boolean, internet: Boolean, unmetered: Boolean, temporarilyUnmetered: Boolean, validated: Boolean): State = when {
        !internet -> State.NONE
        wifiOnly && !unmetered && !temporarilyUnmetered -> State.UNFIT
        validated -> State.FITS
        else -> State.VALIDATING
    }

    /**
     * Whether the lookups may go now ([state] read for [wifiOnly]): a network that fits at once, or one still validating that fits
     * within [graceMs]. None, or a metered one when Wi-Fi only, is no at once.
     */
    suspend fun ready(wifiOnly: Boolean, graceMs: Long = VALIDATION_GRACE_MS, stepMs: Long = 1_000L, state: (Boolean) -> State): Boolean {
        var waited = 0L
        while (true) {
            when (state(wifiOnly)) {
                State.FITS -> return true
                State.NONE, State.UNFIT -> return false
                State.VALIDATING -> if (waited >= graceMs) return false
            }
            delay(stepMs)
            waited += stepMs
        }
    }
}
