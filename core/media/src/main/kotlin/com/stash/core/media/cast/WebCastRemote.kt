package com.stash.core.media.cast

import com.stash.core.data.weblink.remote.BrowserState
import com.stash.core.data.weblink.remote.RemoteCards
import com.stash.core.data.weblink.remote.RemoteCommand
import com.stash.core.data.weblink.remote.RemoteItem
import com.stash.core.data.weblink.remote.RemotePhase
import com.stash.core.data.weblink.remote.RemoteWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One linked browser as a cast receiver (Remote v2, spec 2026-11-03 §2.2): [CastSessionPlayer] drives it exactly as it drives a
 * speaker, the phone keeping the queue and the browser holding the song it plays and the one after it.
 *
 * - **What it says** ([send], through `WebRemote.sendTo`): [load] is `load`, [setNext] an `upNext` (queue edits within
 *   [UP_NEXT_COALESCE_MS] go as one), [playNext] a `jump` when the browser says it holds that song (`next`), else false so the
 *   wrapper loads it; play, pause, seek and volume are the v1 commands, [stop] is `stop`. It never sends v1 next or prev.
 * - **What it hears** ([onState], from the browser's `stash-remote-state`): [status] is built from the last state: `iid` (an id
 *   this phone handed it) is the content id, `phase` the player state (`loading` or a play waiting for a click is buffering,
 *   `ended` is idle and finished, `failed` idle with an error the queue's recovery skips), and the position moves on while it
 *   plays, as the card's does ([RemoteCards]).
 * - **When it ends** ([onEnd], once): the browser went away (closed, or silent for [RemoteCards.STALE_MS]) and stayed away for
 *   [CLOSED_GRACE_MS]; this phone's own socket stayed down for [LOST_GRACE_MS] from the drop; someone at the browser picked their
 *   own music (its state no longer names this phone as driver); or the first song was never answered within
 *   [NO_ANSWER_MS]. A browser that comes back inside a grace carries on.
 *
 * Main thread only (its listeners are called there, as [CastRemote] says); [scope] runs on it.
 */
class WebCastRemote(
    /** The browser's device id. */
    val device: String,
    /** Its label, as this phone verified it ("Chrome on Windows"). */
    val name: String,
    /** This phone's device id: the driver a browser it drives names. */
    private val me: String,
    private val send: (RemoteCommand) -> Unit,
    private val scope: CoroutineScope,
    /** The phone's monotonic clock, the one [BrowserState.receivedAt] and [BrowserState.lostAt] are on. */
    private val clock: () -> Long,
    private val onEnd: (End) -> Unit,
) : CastRemote {

    /** Why a session ended by itself. */
    enum class End {
        /** The browser closed (its socket went, or it went silent) and didn't come back. */
        CLOSED,

        /** This phone's connection stayed down. */
        LOST,

        /** Someone at the browser picked their own music, or the tab was reloaded: it isn't playing this phone's queue. */
        TOOK_OVER,

        /** The browser never said it had the first song. */
        NO_ANSWER,
    }

    /** How the session stands, for "Play on", Now Playing and the mini player. */
    enum class Link {
        /** The browser plays (or holds) this phone's songs. */
        READY,

        /** The first song went out and the browser hasn't said it has it yet: "Starting on Chrome on Windows…". */
        STARTING,

        /** The browser waits for a click in its tab before it can start the music. */
        NEEDS_CLICK,

        /** The browser, or this phone's connection, is gone for now: "Reconnecting to Chrome…". */
        RECONNECTING,
    }

    private val _link = MutableStateFlow(Link.READY)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val listeners = mutableListOf<() -> Unit>()

    /** The browser's last state (with this phone's own commands applied on top until it answers), or null before any. */
    private var last: BrowserState? = null

    /** The browser session that has played this phone's songs: another one (a reload) holds none of them. */
    private var drivenSid: String? = null

    /** The wire id of the last `load`, and whether the browser has ever named one this phone loaded. */
    private var loadedId: String? = null
    private var answered = false

    /** What [setNext] asked for (sent after the coalescing window), and the id of the last `upNext` that went out. */
    private var nextMedia: CastMedia? = null
    private var sentNextId: String? = null
    private var upNextJob: Job? = null

    private var noAnswerJob: Job? = null
    private var staleJob: Job? = null
    private var graceJob: Job? = null
    private var graceFor: End? = null
    private var ended = false

    /** The volume before [setMuted] (v1 has no mute: it is volume 0). */
    private var unmutedVolume: Double? = null

    /** Wire id → the wrapper's content id, for the ids handed to this browser (its `iid` and `next` can only be one of them). */
    private val contentIds = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_IDS
    }

    // ── What the wrapper reads ─────────────────────────────────────────

    override val status: CastStatus
        get() {
            val b = last ?: return CastStatus.EMPTY
            val s = b.state
            val volume = (s.volume ?: 1.0).toFloat()
            val ours = s.driver == me && s.iid != null
            val contentId = if (ours) contentIds[s.iid] else null
            if (contentId == null) {
                return CastStatus(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.NONE, null, 0L, 0L, volume, false, s.volume != null)
            }
            val (state, idle) = when {
                s.phase == RemotePhase.FAILED -> CastStatus.PlayerState.IDLE to CastStatus.IdleReason.ERROR
                s.phase == RemotePhase.ENDED -> CastStatus.PlayerState.IDLE to CastStatus.IdleReason.FINISHED
                s.pendingPlay || s.phase == RemotePhase.LOADING -> CastStatus.PlayerState.BUFFERING to CastStatus.IdleReason.NONE
                s.playing -> CastStatus.PlayerState.PLAYING to CastStatus.IdleReason.NONE
                else -> CastStatus.PlayerState.PAUSED to CastStatus.IdleReason.NONE
            }
            return CastStatus(state, idle, contentId, positionOf(b, state), s.durationMs, volume, volume == 0f, s.volume != null)
        }

    /** The browser's position, moved on by the time since its state while it plays, never past the song's end. */
    private fun positionOf(b: BrowserState, state: CastStatus.PlayerState): Long {
        val s = b.state
        val p = if (state == CastStatus.PlayerState.PLAYING) {
            s.positionMs + ((clock() - b.receivedAt).coerceAtLeast(0) * s.rate).toLong()
        } else {
            s.positionMs
        }
        return if (s.durationMs > 0) minOf(p, s.durationMs) else p
    }

    // ── What the wrapper says ──────────────────────────────────────────

    override fun load(media: CastMedia, startPositionMs: Long, autoplay: Boolean) {
        if (ended) return
        val song = media.song ?: return // a speaker's media: a browser finds songs by their Song, never a LAN URL
        dropUpNext()
        sentNextId = null
        val id = wireId(media.contentId)
        loadedId = id
        send(RemoteCommand.Load(RemoteItem(id, song), startPositionMs.coerceAtLeast(0), autoplay, media.from))
        if (!answered && noAnswerJob == null) {
            _link.value = Link.STARTING
            noAnswerJob = scope.launch {
                delay(NO_ANSWER_MS)
                if (!answered) end(End.NO_ANSWER)
            }
        }
    }

    override fun setNext(media: CastMedia?) {
        if (ended) return
        nextMedia = media
        if (upNextJob?.isActive == true) return // one window, the latest wins
        upNextJob = scope.launch {
            delay(UP_NEXT_COALESCE_MS)
            flushUpNext()
        }
    }

    private fun flushUpNext() {
        upNextJob = null
        val m = nextMedia
        val song = m?.song
        val id = m?.let { wireId(it.contentId) }
        sentNextId = id.takeIf { song != null }
        send(RemoteCommand.UpNext(if (id != null && song != null) listOf(RemoteItem(id, song)) else emptyList()))
    }

    /** A load replaces the browser's whole queue: an `upNext` still waiting is for the song before it. */
    private fun dropUpNext() {
        upNextJob?.cancel()
        upNextJob = null
        nextMedia = null
    }

    /** True when the browser holds the queued song (its state's `next`): it moves on at once, already buffered. */
    override fun playNext(): Boolean {
        if (ended || upNextJob?.isActive == true) return false
        val want = sentNextId ?: return false
        val s = last?.state ?: return false
        if (s.driver != me || s.next != want) return false
        send(RemoteCommand.Jump(want))
        loadedId = want
        sentNextId = null
        return true
    }

    override fun play() = command(RemoteCommand.Play)
    override fun pause() = command(RemoteCommand.Pause)
    override fun seekTo(positionMs: Long) = command(RemoteCommand.Seek(positionMs.coerceAtLeast(0)))

    override fun stop() {
        if (ended) return
        dropUpNext()
        sentNextId = null
        loadedId = null
        send(RemoteCommand.Stop)
    }

    override fun setVolume(level: Float) {
        unmutedVolume = null
        command(RemoteCommand.Volume(level.toDouble().coerceIn(0.0, 1.0)))
    }

    override fun setMuted(muted: Boolean) {
        if (muted) {
            unmutedVolume = last?.state?.volume ?: 1.0
            command(RemoteCommand.Volume(0.0))
        } else {
            command(RemoteCommand.Volume(unmutedVolume ?: 1.0))
            unmutedVolume = null
        }
    }

    /** A v1 command: sent, and taken at once into [status] (the wrapper reads it right after) until the browser answers. */
    private fun command(c: RemoteCommand) {
        if (ended) return
        send(c)
        last = last?.let { RemoteCards.optimistic(it, c, clock()) }
    }

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    // ── What the browser says ──────────────────────────────────────────

    /**
     * The browser's latest state as `WebRemote` has it: null when it isn't live (its socket closed, `ready` didn't list it, the
     * relay said it is offline), [BrowserState.lostAt] set when it is this phone's socket that is down.
     */
    fun onState(b: BrowserState?) {
        if (ended) return
        when {
            b == null -> startGrace(End.CLOSED, CLOSED_GRACE_MS)
            b.lostAt != null -> {
                last = b
                startGrace(End.LOST, (LOST_GRACE_MS - (clock() - b.lostAt!!)).coerceAtLeast(0))
            }
            else -> {
                if (!took(b)) heard(b)
                if (ended) return
            }
        }
        notifyListeners()
    }

    /** True (and the session ends) when the browser no longer plays this phone's queue. */
    private fun took(b: BrowserState): Boolean {
        val s = b.state
        val sid = drivenSid ?: return false
        return when {
            s.sid != sid -> end(End.TOOK_OVER).let { true } // reloaded: the new page holds nothing of ours
            s.driver != me -> end(End.TOOK_OVER).let { true } // its own music, or another phone's
            else -> false
        }
    }

    private fun heard(b: BrowserState) {
        val s = b.state
        last = b
        cancelGrace()
        if (s.driver == me) drivenSid = s.sid
        if (!answered && s.driver == me && loadedId != null && s.iid != null && contentIds.containsKey(s.iid)) {
            answered = true
            noAnswerJob?.cancel()
            noAnswerJob = null
        }
        _link.value = when {
            !answered && loadedId != null -> Link.STARTING
            s.driver == me && (s.pendingPlay || (s.needsGesture && s.phase == RemotePhase.LOADING)) -> Link.NEEDS_CLICK
            else -> Link.READY
        }
        // A tab that froze sends nothing more: a minute of silence counts as gone, then the grace.
        staleJob?.cancel()
        staleJob = scope.launch {
            delay((RemoteCards.STALE_MS - (clock() - b.receivedAt)).coerceAtLeast(0))
            startGrace(End.CLOSED, CLOSED_GRACE_MS)
            notifyListeners()
        }
    }

    private fun startGrace(reason: End, ms: Long) {
        staleJob?.cancel()
        staleJob = null
        _link.value = Link.RECONNECTING
        if (graceJob?.isActive == true && graceFor == reason) return // already counting this one down
        graceJob?.cancel()
        graceFor = reason
        graceJob = scope.launch {
            delay(ms)
            end(reason)
        }
    }

    private fun cancelGrace() {
        graceJob?.cancel()
        graceJob = null
        graceFor = null
    }

    /** Ends the session once: every timer stops and [onEnd] hears why. Called for the reasons in [End] only. */
    private fun end(reason: End) {
        if (ended) return
        dispose()
        onEnd(reason)
    }

    /** Stops every timer and takes nothing more (the session ended, or was ended from this phone). */
    fun dispose() {
        ended = true
        noAnswerJob?.cancel()
        staleJob?.cancel()
        graceJob?.cancel()
        upNextJob?.cancel()
    }

    /** The wire id for the wrapper's [contentId] (its `<mediaId>#<n>` fits §4's id; anything else is made to). */
    private fun wireId(contentId: String): String {
        val id = if (RemoteWire.ITEM_ID.matches(contentId)) {
            contentId
        } else {
            contentId.replace(NOT_ID, "_").takeLast(ID_MAX).ifEmpty { "_" }
        }
        contentIds[id] = contentId
        return id
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    companion object {
        /** Queue edits within this window go to the browser as one `upNext` (§4 "Big plays and the limits"). */
        const val UP_NEXT_COALESCE_MS = 300L

        /** "Starting on Chrome on Windows…" lasts this long before "didn't answer" (§3). */
        const val NO_ANSWER_MS = 10_000L

        /** A browser gone is waited for this long, as "Reconnecting to Chrome…", before the music comes back to the phone (§3). */
        const val CLOSED_GRACE_MS = 15_000L

        /** This phone's socket down: the browser plays on (it holds the next song); give up after this, as Cast does (§3). */
        const val LOST_GRACE_MS = 60_000L

        /** The ids remembered: the current song and the next are all a browser can name. */
        private const val MAX_IDS = 32

        private const val ID_MAX = 64
        private val NOT_ID = Regex("[^A-Za-z0-9_.:#-]")
    }
}
