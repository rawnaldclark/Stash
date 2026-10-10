package com.stash.core.media.handoff

import android.os.SystemClock
import android.util.Log
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.WebLinkStatus
import com.stash.core.data.weblink.handoff.HandoffChannel
import com.stash.core.data.weblink.handoff.HandoffPrefs
import com.stash.core.data.weblink.handoff.NowRead
import com.stash.core.data.weblink.handoff.StashNow
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.media.PlayerRepository
import com.stash.core.model.weblink.HandoffTiming
import com.stash.core.model.weblink.SongKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The continue card's content (spec §2.5): another device's state, where its song is at [readAtElapsed] ([positionMs],
 * extrapolated from the server's stamp while it plays), and whether that device is still playing.
 */
data class HandoffOffer(
    val device: String,
    val deviceName: String,
    val serverAt: Long,
    val now: StashNow,
    val song: WireSong,
    val positionMs: Long,
    /** The other device was playing when it last published, and the position is still inside the song. */
    val stillPlaying: Boolean,
    /** This phone's monotonic clock when [positionMs] was worked out. */
    val readAtElapsed: Long,
) {
    /**
     * What a dismissal remembers: the device and *what* it offered (its queue and song), not when it said so. A browser
     * republishes every minute while it plays, so a key with the server's stamp would bring a dismissed card back on the next
     * foreground (Phase 4 review S2). A new song or queue there is a new offer.
     */
    val key: String get() = dismissKey(device, now)

    /** Where the song is at [elapsed] (this phone's monotonic clock), never past its end. */
    fun positionAt(elapsed: Long): Long {
        if (!stillPlaying) return positionMs
        val p = positionMs + ((elapsed - readAtElapsed).coerceAtLeast(0) * now.rate).toLong()
        val d = song.durationMs ?: return p
        return minOf(p, d)
    }
}

/**
 * A dismissal's key for [device]'s state [now] (sync-v1 §4): `"<device>|<queueId>|<keyOf(song)>"`, kept on this phone only. It
 * stays dismissed while that device plays on in the same song and queue; another song or queue from it is a new offer.
 */
fun dismissKey(device: String, now: StashNow): String = "$device|${now.queueId}|${now.song?.let(SongKey::keyOf).orEmpty()}"

/** The offer rules (spec §2.5, §8.2; sync-v1 §4), pure. */
object HandoffOfferPicker {
    /**
     * This phone's own last playback in server time: its own `now` slot's stamp, or its last play by its own clock moved onto
     * the server's ([localNow] read with [NowRead.serverTime]), whichever is later.
     */
    fun ownLastAt(read: NowRead, lastPlayedLocal: Long, localNow: Long): Long {
        val local = if (lastPlayedLocal > 0) lastPlayedLocal + (read.serverTime - localNow) else 0L
        return maxOf(read.ownServerAt ?: 0L, local)
    }

    fun pick(read: NowRead, ownLastAt: Long, playingHere: Boolean, dismissed: Collection<String>, readAtElapsed: Long): HandoffOffer? {
        // A dismissed state stays dismissed through that device's later republishes of it (same queue, same place).
        val candidates = read.states.filter { dismissKey(it.device, it.now) !in dismissed }
            .map { HandoffTiming.Candidate(it.device, it.serverAt, it.now.song != null) }
        val device = HandoffTiming.pickOffer(candidates, read.me, read.serverTime, ownLastAt, playingHere, dismissed) ?: return null
        val st = read.states.first { it.device == device }
        val song = st.now.song ?: return null
        val pos = HandoffTiming.extrapolate(st.now.playing, st.now.positionMs, st.now.rate, song.durationMs, st.serverAt, read.serverTime)
        // Past the song's end the device was probably killed: offered as paused where it last said it was.
        val raw = st.now.positionMs + kotlin.math.floor(maxOf(0L, read.serverTime - st.serverAt).toDouble() * st.now.rate).toLong()
        val stillPlaying = st.now.playing && song.durationMs.let { d -> d == null || raw <= d }
        return HandoffOffer(st.device, st.deviceName, st.serverAt, st.now, song, pos, stillPlaying, readAtElapsed)
    }
}

/**
 * The continue card's state (spec §2.5, §8.2). Checked when the app comes to the foreground, at most every
 * [MIN_CHECK_GAP_MS]: one `GET slots/now`, nothing in the background and nothing on a timer. Offline or not linked: no card.
 */
@Singleton
class HandoffOffers @Inject constructor(
    private val channel: HandoffChannel,
    private val prefs: HandoffPrefs,
    private val config: WebLinkConfig,
    private val player: PlayerRepository,
    private val restorer: HandoffRestorer,
    linkRepository: WebLinkRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var lastCheck = Long.MIN_VALUE / 2

    private val _offer = MutableStateFlow<HandoffOffer?>(null)
    val offer: StateFlow<HandoffOffer?> = _offer.asStateFlow()

    /** "2 songs that aren't playable here were left out." once, after a restore. */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    init {
        // Unlinked, removed, or "Pick up where you left off" switched off: the card goes at once, not at the next foreground
        // (a card left over after Unlink everything used to play its one song alone; Phase 4 review S3).
        scope.launch {
            combine(prefs.enabled, linkRepository.status) { on, status -> on && status !is WebLinkStatus.NotLinked }
                .collect { usable -> if (!usable) _offer.value = null }
        }
    }

    /** The app came to the foreground (ProcessLifecycleOwner ON_START). */
    fun onForeground() {
        if (!config.enabled) return
        scope.launch { check(force = false) }
    }

    internal suspend fun check(force: Boolean) = lock.withLock {
        if (!prefs.enabled.value) {
            _offer.value = null
            return@withLock
        }
        val t = SystemClock.elapsedRealtime()
        if (!force && t - lastCheck < MIN_CHECK_GAP_MS) return@withLock
        lastCheck = t
        val read = channel.readNow()
        if (read == null) {
            _offer.value = null // offline or not linked: no card (spec §12)
            return@withLock
        }
        val playing = player.playerState.value.isPlaying
        val own = HandoffOfferPicker.ownLastAt(read, prefs.lastPlayedAtLocal, System.currentTimeMillis())
        val offer = HandoffOfferPicker.pick(read, own, playing, prefs.dismissed, SystemClock.elapsedRealtime())
        if (offer != null) Log.i(TAG, "offer from ${offer.deviceName}: playing=${offer.stillPlaying} at ${offer.positionMs} ms")
        _offer.value = offer
    }

    /** ✕ on the card: this exact state is never offered again. */
    fun dismiss() {
        val o = _offer.value ?: return
        prefs.dismiss(o.key)
        _offer.value = null
    }

    /** This phone started playing: the card goes (its state would be older than this phone's now). */
    fun clear() {
        _offer.value = null
    }

    fun noteShown() {
        _note.value = null
    }

    /** ▶ on the card: continue that queue here. */
    suspend fun accept(): RestoreResult {
        val o = _offer.value ?: return RestoreResult.Failed
        _offer.value = null
        // A card left on screen after an unlink or the switch going off can't be taken (S3).
        if (!prefs.enabled.value || !channel.linked()) return RestoreResult.Failed
        // Dismissed too, so a later foreground doesn't offer the state just taken (this phone's own publish soon outdates it).
        prefs.dismiss(o.key)
        val result = try {
            restorer.restore(o, o.positionAt(SystemClock.elapsedRealtime())) { _note.value = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "handoff restore failed: ${e.javaClass.simpleName}")
            RestoreResult.Failed
        }
        when (result) {
            RestoreResult.NothingPlayable -> _note.value = "None of those songs can be played here."
            RestoreResult.Failed -> _note.value = "Couldn't continue here."
            RestoreResult.Started -> Unit
        }
        return result
    }

    private companion object {
        const val TAG = "WebLinkHandoff"

        /** Spec §8.2: at most every 30 s. */
        const val MIN_CHECK_GAP_MS = 30_000L
    }
}
