package com.stash.core.media.cast

import android.util.Log
import com.stash.core.data.weblink.remote.BrowserState
import com.stash.core.data.weblink.remote.RemoteCards
import com.stash.core.data.weblink.remote.RemoteCommand
import com.stash.core.data.weblink.remote.RemoteItem
import com.stash.core.data.weblink.remote.RemotePhase
import com.stash.core.data.weblink.remote.RemoteState
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
 * - **What it says again** (sync-v1 §5.7 Driving): a driven browser is known by its `driver`, never by its session id, which
 *   changes on each of its reconnects and whenever a phone comes on (and commands sealed for the old one are dropped). So on a
 *   new session id, or once a browser that was away is heard again, it is told what it missed: the song when `iid` isn't it,
 *   the one after it when `next` isn't it, then the play, pause, seek and volume sent since it was last heard. Each song gets
 *   [NO_ANSWER_MS] to be named; one that isn't is sent once more, then reported as an error the queue skips.
 * - **When it ends** ([onEnd], once): the tab said `bye` as it closed or reloaded (at once, no grace); the browser went away
 *   without one (its socket went, or it was silent for [RemoteCards.STALE_MS]) and stayed away for [CLOSED_GRACE_MS]; this
 *   phone's own socket stayed down for [LOST_GRACE_MS] from the drop; someone at the browser picked their own music, or the tab
 *   reloaded without a bye (its state no longer names this phone as driver, once it had played this phone's songs); or the first
 *   song was never answered within [NO_ANSWER_MS]. A browser that comes back inside a grace carries on.
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
        /** The tab said `bye` (closed or reloaded), or the browser went (its socket, or silence) and didn't come back. */
        CLOSED,

        /** This phone's connection stayed down. */
        LOST,

        /** Someone at the browser picked their own music, or the tab reloaded without a bye: it isn't playing this phone's queue. */
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

    /** The browser session last heard from. A new one (a reconnect, a phone that came on) dropped what was sealed for the old. */
    private var sid: String? = null

    /** The browser, or this phone's connection, was away since it was last heard: what went out meanwhile may be lost. */
    private var away = false

    /**
     * The browser has named a song this phone handed it, with this phone as driver: it plays this phone's queue. Only from then
     * does a state naming another driver, or none, end the session (a stale state from before this session never counts).
     */
    private var driving = false

    /** A song this phone handed the browser, as a `load` of it again says it. */
    private class Sent(val item: RemoteItem, val startMs: Long, val autoplay: Boolean)

    /** The song the browser should be on: the last load, a jump, or the queued song it moved on to by itself. */
    private var current: Sent? = null

    /** "Playing from", as the last load said it. */
    private var from: String? = null

    /** A `stop` went out since the last load: a browser that missed it is told again. */
    private var stopped = false

    /** What [setNext] asked for (sent after the coalescing window). */
    private var nextMedia: CastMedia? = null
    private var upNextJob: Job? = null

    /** The song of the last `upNext` that went out (null: an empty one, or none yet), and whether one went out since the load. */
    private var next: RemoteItem? = null
    private var upNextSent = false

    /**
     * Play-or-pause, seek and volume sent since the browser was last heard in the same session, the latest of each in the order
     * sent: they may not have landed. Taken as heard when a state of the same session comes; said again on a new one.
     */
    private val unheard = mutableListOf<RemoteCommand>()

    /** The wait for the browser to name [current]. */
    private var answerJob: Job? = null

    /** [current]'s wire id when it was never named, even sent twice: an error in [status], which the queue's recovery skips. */
    private var failedId: String? = null

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

    /**
     * Each id handed to the browser as the next song this session → that song ([songOf]). A queue that shifted while the browser
     * was away queues the same song again under a new id the browser never got; the id it holds is still that song ([movedOnTo]).
     */
    private val nextSongs = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_IDS
    }

    // ── What the wrapper reads ─────────────────────────────────────────

    override val status: CastStatus
        get() {
            val b = last
            val volume = (b?.state?.volume ?: 1.0).toFloat()
            val canSetVolume = b?.state?.volume != null
            // Never answered, even sent twice: the wrapper reports a remote error, and the queue steps over it.
            val failed = failedId?.let { contentIds[it] }
            if (failed != null) {
                return CastStatus(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.ERROR, failed, 0L, 0L, volume, volume == 0f, canSetVolume)
            }
            if (b == null) return CastStatus.EMPTY
            val s = b.state
            val ours = s.driver == me && s.iid != null
            val contentId = if (ours) contentIds[s.iid] else null
            if (contentId == null) {
                return CastStatus(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.NONE, null, 0L, 0L, volume, false, canSetVolume)
            }
            val (state, idle) = when {
                s.phase == RemotePhase.FAILED -> CastStatus.PlayerState.IDLE to CastStatus.IdleReason.ERROR
                s.phase == RemotePhase.ENDED -> CastStatus.PlayerState.IDLE to CastStatus.IdleReason.FINISHED
                s.pendingPlay || s.phase == RemotePhase.LOADING -> CastStatus.PlayerState.BUFFERING to CastStatus.IdleReason.NONE
                s.playing -> CastStatus.PlayerState.PLAYING to CastStatus.IdleReason.NONE
                else -> CastStatus.PlayerState.PAUSED to CastStatus.IdleReason.NONE
            }
            val position = positionOf(b, plays = state == CastStatus.PlayerState.PLAYING)
            return CastStatus(state, idle, contentId, position, s.durationMs, volume, volume == 0f, canSetVolume)
        }

    /** The browser's position, moved on by the time since its state while it [plays], never past the song's end. */
    private fun positionOf(b: BrowserState, plays: Boolean): Long {
        val s = b.state
        val p = if (plays) s.positionMs + ((clock() - b.receivedAt).coerceAtLeast(0) * s.rate).toLong() else s.positionMs
        return if (s.durationMs > 0) minOf(p, s.durationMs) else p
    }

    // ── What the wrapper says ──────────────────────────────────────────

    override fun load(media: CastMedia, startPositionMs: Long, autoplay: Boolean) {
        if (ended) return
        val song = media.song ?: return // a speaker's media: a browser finds songs by their Song, never a LAN URL
        dropUpNext()
        next = null
        upNextSent = false
        stopped = false
        failedId = null
        // A load says where and whether it plays: what was sent before it about the song before is over. Volume isn't.
        unheard.retainAll { it is RemoteCommand.Volume }
        val sent = Sent(RemoteItem(wireId(media.contentId), song), startPositionMs.coerceAtLeast(0), autoplay)
        current = sent
        from = media.from
        log("load ${sent.item.id} at ${sent.startMs} play=${sent.autoplay} (wrapper)")
        send(RemoteCommand.Load(sent.item, sent.startMs, sent.autoplay, from))
        awaitAnswer()
        if (!driving) _link.value = Link.STARTING
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
        val item = if (m != null && song != null) RemoteItem(wireId(m.contentId), song) else null
        if (item != null) nextSongs[item.id] = songOf(m!!.contentId)
        next = item
        upNextSent = true
        send(RemoteCommand.UpNext(listOfNotNull(item)))
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
        val n = next ?: return false
        val s = last?.state ?: return false
        if (s.driver != me || s.next != n.id) return false
        log("jump ${n.id} (wrapper)")
        send(RemoteCommand.Jump(n.id))
        // A jump keeps the play state: said again as a load, it says the same. What was sent about the song before is over.
        current = Sent(n, 0L, autoplay = s.playing)
        next = null
        upNextSent = false
        stopped = false
        failedId = null
        unheard.retainAll { it is RemoteCommand.Volume }
        awaitAnswer()
        return true
    }

    override fun play() = command(RemoteCommand.Play)
    override fun pause() = command(RemoteCommand.Pause)
    override fun seekTo(positionMs: Long) = command(RemoteCommand.Seek(positionMs.coerceAtLeast(0)))

    override fun stop() {
        if (ended) return
        dropUpNext()
        current = null
        next = null
        upNextSent = false
        stopped = true
        failedId = null
        answerJob?.cancel()
        answerJob = null
        unheard.retainAll { it is RemoteCommand.Volume }
        send(RemoteCommand.Stop)
    }

    override fun setVolume(level: Float) {
        unmutedVolume = null
        volume(level.toDouble().coerceIn(0.0, 1.0), "setVolume")
    }

    override fun setMuted(muted: Boolean) {
        if (muted) {
            // A second mute while muted keeps the volume to come back to: the browser's volume is 0 by then.
            if (unmutedVolume == null) unmutedVolume = last?.state?.volume ?: 1.0
            volume(0.0, "setMuted(true)")
        } else {
            volume(unmutedVolume ?: 1.0, "setMuted(false)")
            unmutedVolume = null
        }
    }

    /** A volume command, logged with [why] (the next "volume 0" in a diagnostics log says where it came from). */
    private fun volume(level: Double, why: String) {
        if (ended) return
        log("volume $level ($why)")
        command(RemoteCommand.Volume(level))
    }

    /** A v1 command: sent, kept until heard, and taken at once into [status] (the wrapper reads it right after). */
    private fun command(c: RemoteCommand) {
        if (ended) return
        send(c)
        unheard.removeAll { kindOf(it) == kindOf(c) } // only the latest of each kind is worth saying again
        unheard += c
        last = last?.let { RemoteCards.optimistic(it, c, clock()) }
        // Until the browser names the song it was handed, a seek or a play-or-pause is where and whether that song starts: a
        // load of it sent again (unanswered, or after a reconnect) says that, not what the first load said.
        val cur = current ?: return
        if (last?.state?.iid == cur.item.id) return
        current = when (c) {
            is RemoteCommand.Seek -> Sent(cur.item, c.positionMs, cur.autoplay)
            RemoteCommand.Play -> Sent(cur.item, cur.startMs, autoplay = true)
            RemoteCommand.Pause -> Sent(cur.item, cur.startMs, autoplay = false)
            else -> cur
        }
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
     *
     * A state saying `bye` is the tab's last word as it closes or reloads (sync-v1 §5.7): the session ends at once as
     * [End.CLOSED], with no grace to wait out, whatever driver it names (the tab is gone either way). Only from the browser
     * session last heard: a bye with another session id is one the relay replayed, and is ignored. It was sealed ahead of
     * time, so nothing else in it is taken (its position can be a heartbeat old; the phone's own estimate stands).
     */
    fun onState(b: BrowserState?) {
        if (ended) return
        when {
            b == null -> startGrace(End.CLOSED, CLOSED_GRACE_MS)
            b.state.bye -> {
                if (b.state.sid == sid) end(End.CLOSED)
                return
            }
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

    /**
     * True (and the session ends) when a browser that played this phone's songs names another driver, or none: someone there
     * picked their own music, or the tab was reloaded. Its session id is never compared: it changes on each reconnect.
     */
    private fun took(b: BrowserState): Boolean {
        if (!driving || b.state.driver == me) return false
        end(End.TOOK_OVER)
        return true
    }

    private fun heard(b: BrowserState) {
        val s = b.state
        val before = last
        val missed = away || (sid != null && s.sid != sid)
        away = false
        sid = s.sid
        last = b
        cancelGrace()
        if (s.driver == me && s.iid != null) {
            s.iid?.takeIf { it != current?.item?.id }?.let(::movedOnTo)
            if (contentIds.containsKey(s.iid)) driving = true
            if (s.iid == current?.item?.id) {
                answerJob?.cancel()
                answerJob = null
                failedId = null
            }
        }
        if (missed) catchUp(before, s) else unheard.clear()
        _link.value = when {
            !driving && current != null -> Link.STARTING
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

    /**
     * The browser plays [iid], not [current]. When [iid] was handed to it as the next song and the wrapper still has that song
     * next, it moved on to it by itself: that is the song it should be on now. The wrapper may have queued that song again under
     * a new id the browser never got (the queue shifted while it was away: an edit, the window sliding, a radio splice); [iid]
     * then stands for the new id, so the wrapper sees the song it queued start, and nothing is loaded again (the song before
     * from its end, then this one from 0:00).
     */
    private fun movedOnTo(iid: String) {
        val n = next
        val queued = nextMedia
        val queuedSong = queued?.song
        val item = when {
            queued != null && queuedSong != null && nextSongs[iid] == songOf(queued.contentId) -> {
                if (contentIds[iid] != queued.contentId) log("moved on to $iid: the song queued now as ${queued.contentId}")
                contentIds[iid] = queued.contentId
                dropUpNext() // an upNext still waiting would queue the same song after itself
                RemoteItem(iid, queuedSong)
            }
            n != null && iid == n.id -> n
            else -> return
        }
        current = Sent(item, 0L, autoplay = true)
        next = null
        upNextSent = false
    }

    /**
     * The browser missed what went out (sync-v1 §5.7 Driving): its session changed, or it (or this phone) was away. It is told
     * again: the song when `iid` isn't it (where it was, this phone's own commands taken in), the song after it when `next`
     * isn't it, then the latest play-or-pause, seek and volume not yet heard (each harmless twice). A `stop` it missed is
     * said again while it still plays one of this phone's songs.
     */
    private fun catchUp(before: BrowserState?, s: RemoteState) {
        val c = current
        if (c == null) {
            if (stopped && s.driver == me && s.iid != null && s.phase != RemotePhase.ENDED) send(RemoteCommand.Stop)
            resend(unheard.filterIsInstance<RemoteCommand.Volume>())
            return
        }
        val reload = s.driver != me || s.iid != c.item.id
        var toggle: RemoteCommand? = null
        if (reload) {
            val (position, play) = startOf(c, before)
            val why = "catch-up: iid=${s.iid} current=${c.item.id}"
            if (s.driver == me && s.next == c.item.id && position == 0L) {
                // It holds that song next, already buffered: a jump keeps the preload a load would throw away. A jump keeps
                // the play state, so whether it plays is said after it when that differs.
                log("jump ${c.item.id} ($why)")
                send(RemoteCommand.Jump(c.item.id))
                if (play != s.playing) toggle = if (play) RemoteCommand.Play else RemoteCommand.Pause
            } else {
                log("load ${c.item.id} at $position play=$play ($why)")
                send(RemoteCommand.Load(c.item, position, play, from))
            }
            awaitAnswer()
        }
        if (upNextSent && (reload || s.next != next?.id)) send(RemoteCommand.UpNext(listOfNotNull(next)))
        // A load said where and whether it plays (a jump, where, and the play or pause after it): only the volume is left.
        resend(listOfNotNull(toggle) + if (reload) unheard.filterIsInstance<RemoteCommand.Volume>() else unheard.toList())
    }

    /**
     * Where and whether [c] starts when it goes out again: the latest seek and play-or-pause since it went out, else where the
     * browser was on it ([before], when it named it), else as it went ([command] keeps that in line until the browser names it).
     */
    private fun startOf(c: Sent, before: BrowserState?): Pair<Long, Boolean> {
        val was = before?.takeIf { it.state.driver == me && it.state.iid == c.item.id }
        val seek = unheard.filterIsInstance<RemoteCommand.Seek>().lastOrNull()
        val toggle = unheard.lastOrNull { it == RemoteCommand.Play || it == RemoteCommand.Pause }
        val position = seek?.positionMs ?: was?.let { positionOf(it, it.state.playing) } ?: c.startMs
        val play = toggle?.let { it == RemoteCommand.Play } ?: was?.state?.playing ?: c.autoplay
        return position to play
    }

    /** Sends [commands] again: unheard until a state of this session comes, and shown at once as before. */
    private fun resend(commands: List<RemoteCommand>) {
        unheard.clear()
        for (c in commands) {
            if (c is RemoteCommand.Volume) log("volume ${c.volume} (catch-up re-send)")
            send(c)
            unheard += c
            last = last?.let { RemoteCards.optimistic(it, c, clock()) }
        }
    }

    /**
     * Waits [NO_ANSWER_MS] for the browser to name [current]. Unanswered, the first song ends the session ([End.NO_ANSWER]: the
     * music never left the phone); a later one is sent once more, then, still unanswered, reported as an error the queue's
     * recovery steps over. The wait stops while the browser or this phone is away, and starts again with the catch-up.
     */
    private fun awaitAnswer() {
        answerJob?.cancel()
        answerJob = null
        if (away) return // the catch-up sends it and waits
        answerJob = scope.launch {
            delay(NO_ANSWER_MS)
            if (!driving) {
                end(End.NO_ANSWER)
                return@launch
            }
            val c = current ?: return@launch
            val (position, play) = startOf(c, last)
            log("load ${c.item.id} at $position play=$play (answer re-send)")
            send(RemoteCommand.Load(c.item, position, play, from))
            delay(NO_ANSWER_MS)
            failedId = c.item.id
            notifyListeners()
        }
    }

    private fun startGrace(reason: End, ms: Long) {
        staleJob?.cancel()
        staleJob = null
        answerJob?.cancel() // it can't answer while away; the catch-up sends the song again and waits anew
        answerJob = null
        away = true
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
        answerJob?.cancel()
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

    /** What went out and why, under the device's id: ids and numbers only, never a song's words. */
    private fun log(message: String) {
        Log.i(TAG, "$device: $message")
    }

    companion object {
        /** Queue edits within this window go to the browser as one `upNext` (§4 "Big plays and the limits"). */
        const val UP_NEXT_COALESCE_MS = 300L

        /** "Starting on Chrome on Windows…" lasts this long before "didn't answer" (§3); each later song waits as long (twice). */
        const val NO_ANSWER_MS = 10_000L

        /**
         * A browser gone without a `bye` (a network drop, not a closed tab) is waited for this long, as "Reconnecting to
         * Chrome…", before the music comes back to the phone (§3). A driven tab tries again after 1 s, then every 2 s; a 10 s
         * cut on the device run was back at 16.5 s, past the 15 s this used to be.
         */
        const val CLOSED_GRACE_MS = 30_000L

        /** This phone's socket down: the browser plays on (it holds the next song); give up after this, as Cast does (§3). */
        const val LOST_GRACE_MS = 60_000L

        /** The ids remembered: the current song and the next are all a browser can name. */
        private const val MAX_IDS = 32

        /** The song in a wrapper's content id: its media id, before the `#` and the load counter ([CastMedia.contentId]). */
        private fun songOf(contentId: String): String = contentId.substringBeforeLast('#')

        private const val TAG = "WebCastRemote"

        private const val ID_MAX = 64
        private val NOT_ID = Regex("[^A-Za-z0-9_.:#-]")

        /** Play and pause are one kind (the latest says whether it plays); seek and volume each another. */
        private fun kindOf(c: RemoteCommand): Int = when (c) {
            RemoteCommand.Play, RemoteCommand.Pause -> 0
            is RemoteCommand.Seek -> 1
            else -> 2
        }
    }
}
