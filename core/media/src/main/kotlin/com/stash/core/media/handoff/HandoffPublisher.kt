package com.stash.core.media.handoff

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.handoff.HandoffChannel
import com.stash.core.data.weblink.handoff.HandoffPrefs
import com.stash.core.data.weblink.handoff.PublishOutcome
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the publisher compares between two player states (spec §8.1): the song, its place, play/pause, shuffle, repeat and the
 * "Playing from" label, plus where the song was at [atMs] (a monotonic clock), so a seek shows up as a jump.
 */
internal data class HandoffSnap(
    val trackKey: String,
    val index: Int,
    val playing: Boolean,
    val shuffle: Boolean,
    val repeat: String,
    val from: String,
    val positionMs: Long,
    val atMs: Long,
    val queueSig: Int,
) {
    /** Where the song should be at [at] if nothing happened. */
    fun expectedAt(at: Long, rate: Double): Long = if (playing) positionMs + ((at - atMs) * rate).toLong() else positionMs

    companion object {
        fun of(s: PlayerState, atMs: Long): HandoffSnap? {
            val t = s.currentTrack ?: return null
            return HandoffSnap(
                trackKey = "${t.id}|${t.title}|${t.artist}",
                index = s.currentIndex,
                playing = s.isPlaying || s.isBuffering,
                shuffle = s.isShuffleEnabled,
                repeat = s.repeatMode.name,
                from = s.source.displayLabel,
                positionMs = s.positionMs,
                atMs = atMs,
                queueSig = queueSignature(s),
            )
        }

        /** The queue's identity: its songs in play order and, shuffled, their timeline slots. Not the songs' changing fields. */
        fun queueSignature(s: PlayerState): Int {
            var h = s.queue.size
            for (t in s.queue) h = 31 * h + (if (t.id != 0L) t.id.hashCode() else (t.title + "|" + t.artist).hashCode())
            return 31 * h + (s.shuffleTimelineSlots?.hashCode() ?: 0)
        }
    }
}

/** The publish rules (spec §8.1), pure: what counts as a change worth telling the other devices. */
internal object HandoffTriggers {
    /** A position more than this far from where the song should be is a seek. */
    const val SEEK_TOLERANCE_MS = 3_000L

    /** Track change, index, play/pause (also end of queue and a sleep-timer pause), shuffle, repeat, label, or a seek. */
    fun nowChanged(prev: HandoffSnap?, next: HandoffSnap, rate: Double): Boolean {
        if (prev == null) return true
        if (prev.trackKey != next.trackKey || prev.index != next.index || prev.playing != next.playing ||
            prev.shuffle != next.shuffle || prev.repeat != next.repeat || prev.from != next.from
        ) {
            return true
        }
        return abs(next.positionMs - prev.expectedAt(next.atMs, rate)) > SEEK_TOLERANCE_MS
    }
}

/**
 * Publishes this phone's handoff state (spec §8.1) on events only: a track change, play/pause, a seek, a sleep-timer pause or
 * the end of the queue (`now`, debounced [NOW_DEBOUNCE_MS]), a queue edit (`queue`, debounced [QUEUE_DEBOUNCE_MS], skipped when
 * its id is unchanged), and going to the background ([flush], at once). There is no timer of any kind while it plays (battery,
 * owner's decision); a playing state is extrapolated by the reader from the server's stamp.
 *
 * Nothing goes out until this phone has played since the service started (a paused session restored at start isn't a
 * playback). Offline or unreachable, only the newest state is kept and retried when the network is there, behind a backoff
 * that doubles with each failure (15 s … 10 min, jittered, at most [MAX_RETRIES] retries in a row); a `429` holds every write
 * until its `Retry-After` has passed, then sends the newest state once. Work runs on [io], never on the main thread.
 */
internal class HandoffPublishEngine(
    private val scope: CoroutineScope,
    private val channel: HandoffChannel,
    private val songs: suspend (List<Track>) -> List<WireSong?>,
    /** The feature flag, the "Pick up where you left off" switch, and not in a Listen Together session. */
    private val allowed: () -> Boolean,
    /** A monotonic clock (ms). */
    private val clock: () -> Long,
    private val rate: () -> Double,
    /** Suspends until the network is back. */
    private val awaitNetwork: suspend () -> Unit,
    /** This phone played (a play/pause edge or a new song while playing): its wall clock, for "own last playback". */
    private val onPlayed: () -> Unit,
    private val io: CoroutineDispatcher,
    /** 0 ≤ x < 1, for the backoff's jitter. */
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    private var lastState: PlayerState? = null
    private var lastSnap: HandoffSnap? = null

    /** `"<scope>|<queue id>"` of the last queue the server took (a rotation or re-link changes the scope: send it again). */
    private var publishedQueueId: String? = null
    private var queueDirty = false

    /** A queue publish was superseded by a newer `now` before it went: the next send carries it (N3). */
    private var wantQueue = false
    private var nowJob: Job? = null
    private var queueJob: Job? = null
    private var retryJob: Job? = null
    private var blockedUntil = 0L

    /**
     * Nothing is published until this phone has played since the service started (B1): the first state of a fresh service is
     * usually the last session restored *paused*, which isn't a playback and mustn't look newer than another device's.
     */
    private var armed = false

    /** Sends in a row that didn't reach the Worker; each one doubles the wait before the next try (B2). */
    private var failures = 0
    private var offline = false
    private var backoffUntil = 0L

    /** The window's songs for the last queue (by its signature and window start): a track change doesn't re-read 2,000 rows. */
    private var songsCache: Triple<Int, Int, List<WireSong?>>? = null

    /** Bumped by every publish attempt, so a retry only sends when nothing newer went out meanwhile. */
    private var generation = 0
    private val sendLock = Mutex()

    /** Number of publishes sent (tests). */
    var sent = 0
        private set

    /** Called for every player state (main thread). Cheap: a few comparisons; the work is debounced onto [io]. */
    fun onState(s: PlayerState) {
        val snap = HandoffSnap.of(s, clock())
        val prev = lastSnap
        lastState = s
        lastSnap = snap
        if (snap == null) return
        // A playback: starting, a new song while playing, or the pause that ends one. A paused state restored at start is none.
        val started = snap.playing && (prev == null || !prev.playing || prev.trackKey != snap.trackKey)
        val stopped = prev?.playing == true && !snap.playing
        if (started || stopped) onPlayed()
        if (snap.playing) armed = true
        if (!armed || !allowed()) return
        if (prev != null && prev.queueSig != snap.queueSig) {
            queueDirty = true
            queueJob?.cancel()
            queueJob = scope.launch {
                delay(QUEUE_DEBOUNCE_MS)
                publish(includeQueue = true)
            }
        }
        if (HandoffTriggers.nowChanged(prev, snap, rate())) {
            nowJob?.cancel()
            nowJob = scope.launch {
                delay(NOW_DEBOUNCE_MS)
                // A pause or the end of the queue: the user may be leaving, so a pending queue edit goes along.
                val withQueue = queueDirty && lastSnap?.playing == false
                if (withQueue) queueJob?.cancel()
                publish(includeQueue = withQueue)
            }
        }
    }

    /** The app went to the background (spec §8.1): what's pending goes now; a playing state is re-anchored. */
    fun flush() {
        val snap = lastSnap ?: return
        if (!armed || !allowed()) return
        val pending = nowJob?.isActive == true || queueJob?.isActive == true || queueDirty
        if (!pending && !snap.playing) return
        nowJob?.cancel()
        queueJob?.cancel()
        scope.launch { publish(includeQueue = queueDirty) }
    }

    fun stop() {
        nowJob?.cancel()
        queueJob?.cancel()
        retryJob?.cancel()
    }

    private suspend fun publish(includeQueue: Boolean) {
        if (lastState == null || lastSnap == null || !allowed()) return
        if (includeQueue) wantQueue = true
        val gen = ++generation
        val until = maxOf(blockedUntil, backoffUntil)
        if (until > clock() || offline) {
            // Rate-limited or backing off: one send of the newest state once the wait is over (and the network is there).
            scheduleRetry(gen)
            return
        }
        send(gen)
    }

    private class Sendout(val outcome: PublishOutcome, val queueKey: String?, val scopeMoved: Boolean)

    private suspend fun send(gen: Int) {
        sendLock.withLock {
            if (gen != generation) return // something newer is on its way
            val state = lastState ?: return
            val snap = lastSnap ?: return
            val includeQueue = wantQueue
            val alreadyPublished = publishedQueueId
            val result = withContext(io) {
                try {
                    if (!channel.linked()) return@withContext Sendout(PublishOutcome.NotLinked, null, false)
                    val before = channel.scope()
                    val window = HandoffDocsBuilder.window(state)
                    val position = snap.expectedAt(clock(), rate()).let { p -> state.durationMs.takeIf { it > 0 }?.let { minOf(p, it) } ?: p }
                    val docs = HandoffDocsBuilder.build(state, windowSongs(state, snap, window), position, rate(), window.first)
                        ?: return@withContext Sendout(PublishOutcome.Refused("empty"), null, false)
                    // The queue goes when its debounce is over (or with a pause or the background), and only when its id changed;
                    // the first publish, and the first after a rotation or a re-link (the server dropped the slots), always.
                    val key = "$before|${docs.queue.id}"
                    val newScope = alreadyPublished == null || alreadyPublished.substringBefore('|') != before.toString()
                    val queue = docs.queue.takeIf { key != alreadyPublished && (includeQueue || newScope) }
                    val outcome = channel.publish(docs.now, queue)
                    val after = channel.scope()
                    Sendout(outcome, queue?.let { key }, after != before)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A database or store failure while building: never a crash in the playback service (B3).
                    Log.w(TAG, "handoff not built: ${e.javaClass.simpleName}")
                    Sendout(PublishOutcome.Refused("error"), null, false)
                }
            }
            when (val outcome = result.outcome) {
                is PublishOutcome.Sent -> {
                    sent++
                    failures = 0
                    offline = false
                    backoffUntil = 0L
                    if (result.queueKey != null) publishedQueueId = result.queueKey
                    wantQueue = false
                    if (includeQueue) queueDirty = false
                    if (result.scopeMoved) {
                        // The write went through a key catch-up or rotation: the slots were dropped, so the queue goes again.
                        publishedQueueId = null
                        scope.launch { publish(includeQueue = true) }
                    }
                }
                PublishOutcome.Offline -> {
                    failures++
                    offline = true
                    backoffUntil = clock() + backoffMs(failures)
                    if (failures <= MAX_RETRIES) scheduleRetry(gen) else Log.i(TAG, "handoff: Worker unreachable, waiting for the next event")
                }
                is PublishOutcome.RateLimited -> {
                    blockedUntil = clock() + outcome.retryAfterMs
                    scheduleRetry(gen)
                }
                PublishOutcome.NotLinked, is PublishOutcome.Refused -> Log.i(TAG, "handoff not published: $outcome")
            }
        }
    }

    /** The songs of [window] for this queue, read once per queue (and window) rather than on every event (S6). */
    private suspend fun windowSongs(state: PlayerState, snap: HandoffSnap, window: IntRange): List<WireSong?> {
        songsCache?.let { (sig, start, list) -> if (sig == snap.queueSig && start == window.first && list.size == window.count()) return list }
        val list = if (window.isEmpty()) emptyList() else songs(state.queue.subList(window.first, window.last + 1))
        songsCache = Triple(snap.queueSig, window.first, list)
        return list
    }

    /** 15 s, 30 s, 1 min, 2 min … at most 10 min, each ±20 %. */
    private fun backoffMs(n: Int): Long {
        val base = minOf(BACKOFF_BASE_MS shl (n - 1).coerceIn(0, 10), BACKOFF_MAX_MS)
        return (base * (0.8 + 0.4 * jitter())).toLong()
    }

    /**
     * One retry of the newest state once the network is there (when the last try found none) and any wait is over; dropped
     * when a newer publish went out meanwhile. The wait grows with every failure, so a Worker that can't be reached (a captive
     * portal, DNS, a 5xx) costs a handful of tries, never a loop.
     */
    private fun scheduleRetry(gen: Int) {
        retryJob?.cancel()
        retryJob = scope.launch {
            if (offline) awaitNetwork()
            val wait = maxOf(blockedUntil, backoffUntil) - clock()
            if (wait > 0) delay(wait)
            if (gen != generation) return@launch
            if (lastState == null || lastSnap == null || !allowed()) return@launch
            val next = ++generation
            send(next)
        }
    }

    companion object {
        const val TAG = "WebLinkHandoff"
        const val NOW_DEBOUNCE_MS = 2_000L
        const val QUEUE_DEBOUNCE_MS = 10_000L
        const val BACKOFF_BASE_MS = 15_000L
        const val BACKOFF_MAX_MS = 10 * 60_000L

        /** Retries scheduled in a row after failures; past that only a new event tries again (still behind the backoff). */
        const val MAX_RETRIES = 4
    }
}

/**
 * The app's handoff publisher: [HandoffPublishEngine] fed by [PlayerRepository.playerState], started by the playback service
 * ([start]) for as long as it lives and flushed when the app goes to the background ([onBackground]). Only when "Link Stash
 * on the web" is built in, this phone is linked and "Pick up where you left off" is on; never during Listen Together (the
 * player then holds the room's song, not this phone's queue).
 */
@Singleton
class HandoffPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val player: dagger.Lazy<PlayerRepository>,
    private val channel: HandoffChannel,
    private val prefs: HandoffPrefs,
    private val config: WebLinkConfig,
    private val listenTogether: ListenTogetherController,
    private val trackDao: TrackDao,
) {
    private var engine: HandoffPublishEngine? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (!config.enabled || job?.isActive == true) return
        val e = HandoffPublishEngine(
            scope = scope,
            channel = channel,
            songs = ::songsFor,
            allowed = { prefs.enabled.value && !listenTogether.active.value },
            clock = SystemClock::elapsedRealtime,
            rate = { player.get().playbackSpeed.value.toDouble() },
            awaitNetwork = ::awaitNetwork,
            onPlayed = { prefs.lastPlayedAtLocal = System.currentTimeMillis() },
            io = Dispatchers.IO,
        )
        engine = e
        job = scope.launch { player.get().playerState.collect { e.onState(it) } }
    }

    fun stop() {
        job?.cancel()
        job = null
        engine?.stop()
        engine = null
    }

    /** The app went to the background (ProcessLifecycleOwner ON_STOP). */
    fun onBackground() {
        engine?.flush()
    }

    /** The queue's songs with their library rows (ISRC, Spotify id, source), in play order. */
    private suspend fun songsFor(queue: List<Track>): List<WireSong?> {
        val rows = trackDao.getByIds(queue.map { it.id }.filter { it > 0 }.distinct()).associateBy { it.id }
        return queue.map { t -> HandoffDocsBuilder.songOf(rows[t.id]?.toDomain() ?: t) }
    }

    private suspend fun awaitNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        callbackFlow {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(Unit)
                }
            }
            try {
                cm.registerDefaultNetworkCallback(cb)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trySend(Unit) // can't watch: try once anyway
            }
            awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
        }.first()
    }
}
