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
 * Offline, only the newest state is kept and retried once when the network comes back; a `429` holds every write until its
 * `Retry-After` has passed, then sends the newest state once. Work runs on [io], never on the main thread.
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
) {
    private var lastState: PlayerState? = null
    private var lastSnap: HandoffSnap? = null
    private var publishedQueueId: String? = null
    private var queueDirty = false
    private var nowJob: Job? = null
    private var queueJob: Job? = null
    private var retryJob: Job? = null
    private var blockedUntil = 0L

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
        if (prev == null || prev.playing != snap.playing || (snap.playing && prev.trackKey != snap.trackKey)) onPlayed()
        if (!allowed()) return
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
        if (!allowed()) return
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
        val state = lastState ?: return
        val snap = lastSnap ?: return
        if (!allowed()) return
        val gen = ++generation
        val wait = blockedUntil - clock()
        if (wait > 0) {
            // Rate-limited: one send of the newest state once the wait is over.
            scheduleRetry(gen, includeQueue) { delay(wait) }
            return
        }
        send(gen, state, snap, includeQueue)
    }

    private suspend fun send(gen: Int, state: PlayerState, snap: HandoffSnap, includeQueue: Boolean) {
        sendLock.withLock {
            if (gen != generation) return // something newer is on its way
            val alreadyPublished = publishedQueueId
            val (queueId, outcome) = withContext(io) {
                if (!channel.linked()) return@withContext null to PublishOutcome.NotLinked
                val position = snap.expectedAt(clock(), rate()).let { p -> state.durationMs.takeIf { it > 0 }?.let { minOf(p, it) } ?: p }
                val docs = HandoffDocsBuilder.build(state, songs(state.queue), position, rate())
                    ?: return@withContext null to PublishOutcome.Refused("empty")
                // The queue goes when its debounce is over (or with a pause or the background), and only when its id changed.
                val queue = docs.queue.takeIf { it.id != alreadyPublished && (includeQueue || alreadyPublished == null) }
                queue?.id to channel.publish(docs.now, queue)
            }
            when (outcome) {
                is PublishOutcome.Sent -> {
                    sent++
                    if (queueId != null) publishedQueueId = queueId
                    if (includeQueue) queueDirty = false
                }
                PublishOutcome.Offline -> scheduleRetry(gen, includeQueue) { awaitNetwork() }
                is PublishOutcome.RateLimited -> {
                    blockedUntil = clock() + outcome.retryAfterMs
                    scheduleRetry(gen, includeQueue) { delay(outcome.retryAfterMs) }
                }
                PublishOutcome.NotLinked, is PublishOutcome.Refused -> Log.i(TAG, "handoff not published: $outcome")
            }
        }
    }

    /** One retry of the newest state after [wait]; dropped when a newer publish went out meanwhile. */
    private fun scheduleRetry(gen: Int, includeQueue: Boolean, wait: suspend () -> Unit) {
        retryJob?.cancel()
        retryJob = scope.launch {
            wait()
            if (gen != generation) return@launch
            val state = lastState ?: return@launch
            val snap = lastSnap ?: return@launch
            if (!allowed()) return@launch
            val next = ++generation
            send(next, state, snap, includeQueue || queueDirty)
        }
    }

    companion object {
        const val TAG = "WebLinkHandoff"
        const val NOW_DEBOUNCE_MS = 2_000L
        const val QUEUE_DEBOUNCE_MS = 10_000L
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
