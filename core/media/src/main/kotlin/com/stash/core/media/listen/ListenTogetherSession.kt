package com.stash.core.media.listen

import android.util.Log
import androidx.media3.common.MediaItem
import com.stash.core.data.listen.CloseReason
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.listen.RoomConnection
import com.stash.core.data.listen.RoomConnector
import com.stash.core.data.listen.RoomEvent
import com.stash.core.data.share.ShareResult
import com.stash.core.media.PlayNext
import com.stash.core.media.R
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomPhase
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.listen.RoomState
import com.stash.core.model.listen.RoomTimeline
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Listen Together engine (spec docs/superpowers/specs/2026-09-24-listen-together-design.md §4–§6).
 * One per playback service, on the service's main-thread scope. Reads [ListenTogetherController]'s
 * commands, talks to the room through a [RoomConnection], and drives the service's player through
 * [SessionPlayer]. Nobody's player advances on its own: every song change comes from the room.
 */
class ListenTogetherSession(
    private val scope: CoroutineScope,
    private val controller: ListenTogetherController,
    private val player: SessionPlayer,
    private val catalog: SessionCatalog,
    private val api: RoomApiClient,
    private val connector: RoomConnector,
    private val autoplayRadio: suspend () -> Boolean,
    private val displayName: suspend () -> String?,
    /** Monotonic local time in ms (SystemClock.elapsedRealtime in the service). */
    private val clock: () -> Long,
    /** This phone's remembered "Let guests add songs" (on until the user turns it off). */
    private val guestsAddPreference: suspend () -> Boolean = { true },
) {
    private var connection: RoomConnection? = null
    /**
     * Everything one session launches runs here, and teardown cancels it: work still pending from a
     * session (an advance waiting on radio, a scheduled start) must never reach the next one.
     */
    private var sessionScope: CoroutineScope? = null
    private var pingJob: Job? = null
    private var startJob: Job? = null
    private var driftJob: Job? = null

    private var code: String? = null
    private var url: String? = null
    private var myId: String? = null
    private var token: String? = null
    private var room: RoomState? = null
    private var isHost = false
    /**
     * A host Load the room hasn't got yet: the first one (sent on the welcome) or one dropped while the socket
     * was down. Built on the flush, so the first Load reads where the song is then, not before the handshake.
     */
    private var pendingLoad: (() -> ClientMessage.Load)? = null
    /**
     * Songs this phone has sent the room that it hasn't echoed yet. While one is on its way the room is not stopped,
     * however it looks: a room still showing no song, or an ended one, is only waiting for that echo.
     */
    private var loadsInFlight = 0
    /** The room has welcomed this socket, so what's sent now is read as this member's. False from the drop until then. */
    private var welcomed = false
    /** [clock] when this session began: pings carry time since then, never device uptime. */
    private var sessionStartClock = 0L

    private val clockSync = ClockSync()
    private val drift = DriftController()
    private var timeline: ServerMessage.TimelineUpdate? = null
    private var applied: ServerMessage.TimelineUpdate? = null
    private var loadedKey = NONE
    /** The song whose item is in the player, set once it's loaded. Player events for any other song are stale. */
    private var itemKey = NONE
    private var readyKey = NONE
    private var unavailableKey = NONE
    /**
     * The song (trackKey) the room is preparing: from its prepare until its first timeline. The room starts every song it
     * prepares, so a phone that hasn't paused means to play it, however its own player looks ([meansToPlay]).
     */
    private var roomPreparingKey = NONE
    /** [clock] when this phone began preparing [loadedKey]: a host gives up on it [HOST_PREPARE_MS] later ([giveUpIfStillLooking]). */
    private var preparedAt = 0L
    private var prepareLimitJob: Job? = null
    private var lastStatus: String? = null
    private var reconnecting = false
    private var versionMismatch = false
    private val history = ArrayDeque<SharedTrack>()
    private var radioTriedKey = NONE
    /**
     * Songs the host couldn't play since the last one it could. Past [MAX_UNPLAYABLE_IN_A_ROW] the room stops instead
     * of running down the queue in seconds (no network, Offline with nothing downloaded); the host's Play or Next
     * starts it again.
     */
    private var unplayableInARow = 0
    /** The song (trackKey) this phone has said "Skipped … can't play it" about: once each, whether the room paused on it first or not. */
    private var skipNoticeKey = NONE
    /** This phone's "Let guests add songs": read as a session starts, sent in every hello, changed by the host's switch. */
    private var myGuestsAdd = true
    /**
     * Play next / Add to queue from the menus, gathered for a moment so a whole "Play selected next" (one call per
     * song) leaves as one message: the room closes a socket over 20 messages a second. Each entry: the items and
     * whether they are Play next. One reader per session, so batches leave in the order they were tapped.
     */
    private var addInbox: Channel<Pair<List<MediaItem>, Boolean>>? = null
    /** Adds the socket couldn't take, as (songs, is Play next): they leave after the next welcome. */
    private val heldAdds = mutableListOf<Pair<List<SharedTrack>, Boolean>>()
    /** Batches sent and not fully answered, oldest first. The room answers every `add`, in order, and the toast waits for that. */
    private val awaitingAdds = ArrayDeque<AddsOnTheirWay>()

    // Named notices (SessionEvent): who's been seen, their names (kept after they leave), and
    // leaves waiting out a grace period so a network blip doesn't read as goodbye and hello.
    private val seenMembers = HashSet<String>()
    private val memberNames = HashMap<String, String?>()
    private val pendingLeaves = HashMap<String, Job>()
    /**
     * This phone was paused locally (unplug, a call, a listener's own Pause). It stays paused until the user
     * acts: a listener follows the room silently (new songs load but don't play) until [rejoin], Play or a
     * focus-regain resume. A host has sent pause: until the room's paused timeline arrives, a playing one
     * (the room ignores transport while preparing, or a new song started) gets the pause again; the host's
     * Play or focus-regain resume clears it.
     */
    private var pausedLocally = false

    fun start(): Job = scope.launch { for (command in controller.commands) handle(command) }

    /**
     * The service is going away: leave quietly, with no restore. A restore on sessionEnds would drive the
     * dying service's player and could start it again; the next launch's cold-start restore puts the
     * user's queue back instead (it was never saved over).
     */
    fun shutdown() = teardown(message = null, restore = false)

    private suspend fun handle(command: Command) {
        when (command) {
            Command.Host -> if (code == null) host()
            is Command.Join -> {
                if (command.code == code) return
                if (code != null) leave()
                join(command.code)
            }
            Command.Leave -> leave()
            Command.End -> end()
            is Command.React -> send(ClientMessage.React(command.emoji))
            is Command.Suggestion -> send(ClientMessage.SuggestionAction(command.id, if (command.add) "add" else "dismiss"))
            is Command.MakeHost -> send(ClientMessage.MakeHost(command.memberId))
            // Shown at once, so a swiped or dragged row doesn't flick back while the room's echo is on its way.
            is Command.SetQueue -> if (isHost && room != null) {
                setQueue(command.queue.take(MAX_QUEUE), command.qv ?: room?.qv)
                publish()
            }
            is Command.Unqueue -> unqueue(command.track, command.at)
            is Command.SetGuestsAdd -> setGuestsAdd(command.on)
            Command.Rejoin -> rejoin()
        }
    }

    // ── Starting ──────────────────────────────────────────────────────────────

    private suspend fun host() {
        controller.publish(ListenTogetherState.Connecting(hosting = true))
        val name = displayName()
        val created = (api.create(name) as? ShareResult.Ok)?.value
        if (created == null) {
            controller.publish(ListenTogetherState.Idle)
            controller.message("Couldn't start a session. Check your connection and try again.")
            return
        }
        // Read the user's queue before the session touches the player: it becomes the room's (spec §4).
        val mine = player.userQueue()
        val first = mine.current?.let { catalog.sharedTrackFor(it) }
        val upcoming = mine.upcoming.take(MAX_QUEUE).mapNotNull { catalog.sharedTrackFor(it) }
        // The song kept playing through the create call and the lookups: read where it is now.
        pendingLoad = first?.let { f -> { ClientMessage.Load(f, player.positionMs, upcoming) } }
        // Built from the code, like a listener's: a room made on the old workers.dev host (stashfm.app unreachable)
        // still gets a stashfm.app invite.
        enter(created.code, ShareLinks.roomUrl(created.code), name, hostKey = created.hostKey, asHost = true)
    }

    private suspend fun join(code: String) {
        controller.publish(ListenTogetherState.Connecting(hosting = false))
        enter(code, ShareLinks.roomUrl(code), displayName(), hostKey = null, asHost = false)
    }

    private suspend fun enter(code: String, url: String, name: String?, hostKey: String?, asHost: Boolean) {
        this.code = code
        this.url = url
        isHost = asHost
        sessionStartClock = clock()
        controller.setActive(true) // gates PlayerRepositoryImpl's saves before anything moves
        myGuestsAdd = guestsAddPreference()
        player.saveUserPosition()
        player.events = playerEvents
        player.enterSession(asHost, interceptor)
        player.pause()
        Log.i(TAG, "entering room ${code.take(2)}… as ${if (asHost) "host" else "listener"}")
        // The host key goes out only until the room hands back a token (spec §2–§3).
        val session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope = session
        val conn = connector.connect(code, session) {
            ClientMessage.Hello(name, hostKey.takeIf { token == null }, token, RoomProtocol.VERSION, myGuestsAdd)
        }
        connection = conn
        session.launch { conn.events.collect { onEvent(it) } }
        val inbox = Channel<Pair<List<MediaItem>, Boolean>>(Channel.UNLIMITED)
        addInbox = inbox
        session.launch { gatherAdds(inbox) }
    }

    // ── The room ──────────────────────────────────────────────────────────────

    private suspend fun onEvent(event: RoomEvent) {
        when (event) {
            RoomEvent.Connected -> { reconnecting = false; publish(); startPings() }
            RoomEvent.Reconnecting -> { // keep playing from the last timeline (§6)
                reconnecting = true
                welcomed = false
                awaitingAdds.clear() // their answers went with the socket; the welcome's queue shows what got in
                publish()
            }
            is RoomEvent.Closed -> teardown(
                when (event.reason) {
                    // No welcome yet: the room was already closed when we joined (spec §6).
                    CloseReason.ENDED -> if (myId == null) "This session has ended" else "Session ended"
                    CloseReason.FULL -> "This session is full"
                    CloseReason.GAVE_UP -> "Lost connection to the session"
                    CloseReason.REPLACED -> "You rejoined this session somewhere else"
                },
            )
            is RoomEvent.Message -> onMessage(event.message, event.receivedAt)
        }
    }

    /** [receivedAt] is when RoomClient got the frame (same clock as [clock]), so a pong's RTT excludes our queueing. */
    private suspend fun onMessage(m: ServerMessage, receivedAt: Long) {
        when (m) {
            is ServerMessage.Welcome -> {
                myId = m.memberId
                token = m.token
                welcomed = true
                val before = loadedKey
                applyState(m.state)
                // A reconnect: the room forgot our status with the old socket, so say it again,
                // unless applyState just started a new song (that reports for itself).
                if (loadedKey == before && loadedKey != NONE) when (loadedKey) {
                    unavailableKey -> report(UNAVAILABLE, force = true)
                    readyKey -> report(READY, force = true)
                }
                val pending = pendingLoad?.invoke()
                pendingLoad = null
                // Keeps the version a dropped Load was built from; the very first one was built before the room existed.
                if (isHost) pending?.let { load(it.track, it.positionMs, it.queue, it.why, it.qv ?: room?.qv) } // may set pendingLoad again
                if (heldAdds.isNotEmpty()) {
                    val held = heldAdds.mergedRuns()
                    heldAdds.clear()
                    deliver(held)
                }
                moveOnIfStopped()
            }
            is ServerMessage.StateSync -> applyState(m.state)
            is ServerMessage.Pong -> {
                val first = clockSync.offsetMs == null
                clockSync.onPong(m.c + sessionStartClock, m.r, receivedAt) // back on the local clock, like receivedAt
                if (first) applyTimeline()
            }
            is ServerMessage.TimelineUpdate -> {
                // The room sends a song's first timeline when it starts it: it is no longer preparing.
                if (m.trackKey == roomPreparingKey) roomPreparingKey = NONE
                onTimeline(m)
            }
            is ServerMessage.Prepare -> {
                val by = m.by
                loadsInFlight = (loadsInFlight - 1).coerceAtLeast(0)
                roomPreparingKey = m.trackKey
                // The host's phone couldn't play the last song and moved everyone on: say so on every phone, the host's too.
                if (by != null && m.why == UNAVAILABLE) {
                    noticeSkipped(by)
                } else if (by != null && by != myId) when (m.why) {
                    "skip", "pick" -> notice(SessionEvent.Kind.SKIPPED, by)
                    "back" -> notice(SessionEvent.Kind.WENT_BACK, by)
                }
                room = room?.copy(track = m.track, trackKey = m.trackKey)
                prepare(m.trackKey, m.track, m.positionMs)
            }
            is ServerMessage.Members -> { noticeMembers(m.members); room = room?.copy(members = m.members); publish() }
            is ServerMessage.Suggestions -> { room = room?.copy(suggestions = m.suggestions); publish() }
            // The host's own edits already set room.queue; the echo brings the adders ("by") the room stamped, and the
            // version the next whole queue this phone sends is built from.
            is ServerMessage.QueueUpdate -> {
                room = room?.let { it.copy(queue = m.queue, qv = m.qv ?: it.qv) }
                publish()
                moveOnIfStopped()
            }
            // "Sam added Xtal" for everyone but Sam, who hears what became of their add.
            is ServerMessage.Added -> if (m.by == myId) {
                onAddAnswered(m)
            } else if (m.count > 0) {
                controller.event(SessionEvent(SessionEvent.Kind.ADDED, m.by, memberNames[m.by], song = m.track?.title, count = m.count))
            }
            is ServerMessage.SettingsUpdate -> { room = room?.copy(guestsAdd = m.guestsAdd); publish() }
            is ServerMessage.Reaction -> controller.reaction(m)
            is ServerMessage.Ended -> teardown("Session ended")
        }
    }

    private suspend fun applyState(s: RoomState) {
        val wasHost = isHost
        val oldHost = room?.host
        room = s
        loadsInFlight = 0 // this state is the truth; a Load dropped with the socket goes again from pendingLoad
        roomPreparingKey = if (s.phase.kind == RoomPhase.PREPARING) s.trackKey else NONE
        for (m in s.members) { seenMembers.add(m.id); memberNames[m.id] = m.name } // a snapshot names nobody new
        val newHost = s.host
        if (oldHost != null && newHost != null && newHost != oldHost) notice(SessionEvent.Kind.HOSTING, newHost)
        isHost = s.host != null && s.host == myId
        if (isHost != wasHost) player.enterSession(isHost, interceptor) // makeHost or a 60 s handover
        // The song ended while nobody could move the room on (the old host left), or the new host can't play it: they do.
        // Handed the room while still looking for its song, the room now waits for this phone: within the same limit.
        if (isHost && !wasHost) when {
            s.trackKey == loadedKey && cantPlayCurrentSong() -> advanceNow(UNAVAILABLE)
            songFinished() -> advanceNow()
            s.trackKey == loadedKey -> giveUpIfStillLooking(loadedKey)
        }
        publish()
        val track = s.track ?: return
        if (s.trackKey != loadedKey) prepare(s.trackKey, track, s.timeline.positionMs)
        onTimeline(ServerMessage.TimelineUpdate(s.rev, s.trackKey, s.timeline.positionMs, s.timeline.atRoomMs, s.timeline.playing))
    }

    // ── One song ──────────────────────────────────────────────────────────────

    private suspend fun prepare(key: Int, track: SharedTrack, positionMs: Long) {
        if (key == loadedKey) return
        timeline = null
        loadedKey = key
        readyKey = NONE
        unavailableKey = NONE
        lastStatus = null
        versionMismatch = false
        applied = null
        startJob?.cancel()
        stopDrift()
        player.pause()
        // Every role keeps the time: a guest handed the room mid-handshake is held to the same limit.
        preparedAt = clock()
        prepareLimitJob?.cancel()
        prepareLimitJob = sessionScope?.launch {
            delay(HOST_PREPARE_MS)
            giveUpIfStillLooking(key)
        }
        publish()
        Log.i(TAG, "prepare #$key '${track.title}'")
        // Inline in the event collector, so every later message waits for it: keep it a DB lookup,
        // never make it a network call.
        val item = catalog.mediaItemFor(track)
        if (key != loadedKey) return // a newer song arrived while this one was being looked up
        if (item == null) { markUnavailable(key, "no playable item for '${track.title}'"); return }
        player.load(item, positionMs)
        itemKey = key
    }

    private val playerEvents = object : SessionPlayerEvents {
        override fun onReady() {
            val key = loadedKey
            if (itemKey != key || key == NONE || key == unavailableKey) return
            if (readyKey != key) {
                readyKey = key
                unplayableInARow = 0
                val want = room?.track?.durationMs
                val have = player.durationMs
                versionMismatch = want != null && have != null && abs(have - want) > VERSION_TOLERANCE_MS
                report(READY, force = true)
                publish()
                applyTimeline()
            } else if (lastStatus == BUFFERING) {
                report(READY)
            }
        }

        override fun onBuffering() {
            if (itemKey != loadedKey) return
            if (readyKey != NONE && readyKey == loadedKey && timeline?.playing == true) report(BUFFERING)
        }

        override fun onEnded() {
            if (itemKey != loadedKey) return
            if (isHost) advanceNow()
        }

        override fun onError() {
            if (itemKey != loadedKey) return
            if (loadedKey != NONE) markUnavailable(loadedKey, "the player failed")
        }

        override fun onExternalPause() {
            pausedLocally = true
            startJob?.cancel()
            if (isHost) send(ClientMessage.Pause) // the whole room pauses with the host
            publish()
        }

        override fun onExternalResume() {
            if (!isHost) return rejoin()
            // After a call a host resumes the room, like a normal player resumes. The room's reply starts us.
            player.pause()
            pausedLocally = false
            publish()
            send(ClientMessage.Play)
        }
    }

    /** Catch up with the room: start where it is now, or sit at its paused position. */
    private fun rejoin() {
        if (code == null) return
        pausedLocally = false
        applied = null
        player.pause() // an external resume may have started the player on its own
        publish()
        applyTimeline()
    }

    /** "This song isn't available to you": report it, stay silent, wait for the next prepare (spec §6). */
    private fun markUnavailable(key: Int, why: String) {
        if (unavailableKey == key) return
        Log.w(TAG, "song #$key unavailable: $why")
        unavailableKey = key
        startJob?.cancel()
        stopDrift()
        player.pause()
        report(UNAVAILABLE, force = true)
        publish()
        // The host can't play it: move the room on, don't stall it, and every phone says why. A room the host paused (say
        // while this song was still being looked for) stays paused: the next song waits for their Play.
        if (isHost) {
            unplayableInARow++
            advanceNow(UNAVAILABLE, keepPaused = roomPausedOn(key))
        }
    }

    /** The room sits paused on song [key]: its timeline says so, and it isn't preparing it (a prepared song always starts). */
    private fun roomPausedOn(key: Int): Boolean {
        val t = timeline ?: return false
        return t.trackKey == key && !t.playing && roomPreparingKey != key
    }

    /**
     * What this phone means to do, which every play/pause control reads as its playWhenReady ([SessionPlayer.setPlayIntent]):
     * play while the room plays this song or is about to start it, unless this phone paused or can't play it. The local
     * player is no guide: it stays paused until the song is ready and the room's start time comes.
     */
    private fun meansToPlay(): Boolean {
        val key = loadedKey
        if (pausedLocally || key == NONE || key == unavailableKey) return false
        if (roomPreparingKey == key) return true
        val t = timeline ?: return false
        return t.trackKey == key && t.playing
    }

    /** After anything that may change [meansToPlay]: every play/pause control reads it. */
    private fun intentChanged() {
        if (code == null) return
        player.setPlayIntent(meansToPlay())
    }

    /**
     * The room waits for its host's phone before it starts a song (room.js HOST_PREPARE_MS, owner decision 2026-11-07),
     * since only the host moves the room on: a song the host doesn't have yet must not play for the guests alone. So the
     * host can't take as long as its resolver would (every source, then retries: ~40 s for a song none of them has). It
     * gives up [HOST_PREPARE_MS] after it began preparing the song and reports it unplayable, which moves everyone on (or
     * stops the room) with the same notices as a song it couldn't find at all. Ready by then, the room starts it for
     * everyone together. Called when that time is up, and when this phone is handed the room mid-handshake.
     */
    private fun giveUpIfStillLooking(key: Int) {
        if (!isHost || key != loadedKey || readyKey == key || unavailableKey == key) return
        if (clock() - preparedAt < HOST_PREPARE_MS) return
        markUnavailable(key, "not ready ${HOST_PREPARE_MS} ms after its prepare")
    }

    /**
     * This guest has the song and the room is holding it for the host's phone, so the session bar says "Waiting for
     * Rawn…" rather than leave the spinner unexplained.
     */
    private fun waitingForHost(): Boolean {
        val r = room ?: return false
        val key = loadedKey
        if (isHost || key == NONE || roomPreparingKey != key || readyKey != key) return false
        val host = r.members.firstOrNull { it.id == r.host } ?: return false
        return host.status != MEMBER_OK && host.status != UNAVAILABLE
    }

    private fun cantPlayCurrentSong() = loadedKey != NONE && unavailableKey == loadedKey

    /** Moving the room on from here: so every phone hears about a song the host couldn't play, however late. */
    private fun moveOnReason() = if (cantPlayCurrentSong()) UNAVAILABLE else "end"

    /**
     * The room's song has played out, even if the player no longer says so. When a last song ends the room pauses at
     * its end, [applyTimeline] seeks there, and that takes the player out of ENDED for good: it settles on READY, because
     * only a playing position can reach the end again. So a room paused at the end counts too. [END_SLOP_MS]: the room's
     * position can land just short of the end, by what drift correction lets go. A pause by hand in the last second reads
     * the same, and that's a fair price: an add moves on from it.
     */
    private fun songFinished(): Boolean {
        if (player.ended) return true
        val t = timeline ?: return false
        val duration = player.durationMs ?: return false
        return !t.playing && t.trackKey == loadedKey && t.positionMs >= duration - END_SLOP_MS
    }

    /**
     * "Skipped ‹Song›: ‹Host› can't play it" about the room's current song, once on this phone: the room may pause on it
     * first and move on only later. The chip shows only on the art view and the song's adder may be anywhere in the app,
     * so they get a toast too.
     */
    private fun noticeSkipped(hostId: String) {
        val r = room ?: return
        if (skipNoticeKey == r.trackKey) return
        skipNoticeKey = r.trackKey
        val song = r.track
        controller.event(SessionEvent(SessionEvent.Kind.SKIPPED_UNAVAILABLE, hostId, memberNames[hostId], song = song?.title))
        if (song == null || song.addedBy != myId || hostId == myId) return
        val host = memberNames[hostId]
        if (host != null) controller.message(R.string.lt_your_song_skipped_by, song.title, host)
        else controller.message(R.string.lt_your_song_skipped, song.title)
    }

    private fun onTimeline(t: ServerMessage.TimelineUpdate) {
        val current = timeline
        if (current != null && current.trackKey == t.trackKey && t.rev < current.rev) return // out of order
        val by = t.by
        if (by != null && by != myId && current != null && current.playing != t.playing) {
            // The host paused the room on a song it can't play, having nowhere to go: no new song is coming to say why.
            if (!t.playing && by == room?.host && hostCantPlay()) noticeSkipped(by)
            else notice(if (t.playing) SessionEvent.Kind.RESUMED else SessionEvent.Kind.PAUSED, by)
        }
        timeline = t
        room = room?.copy(timeline = RoomTimeline(t.positionMs, t.atRoomMs, t.playing))
        applyTimeline()
        publish() // the controls' intent, and "Waiting for …" ends as the room starts the song
    }

    private fun hostCantPlay(): Boolean {
        val r = room ?: return false
        return r.members.firstOrNull { it.id == r.host }?.status == UNAVAILABLE
    }

    private fun applyTimeline() {
        val t = timeline ?: return
        if (pausedLocally && isHost) {
            if (t.playing) { send(ClientMessage.Pause); return } // the room ignored our pause while preparing
            pausedLocally = false
            publish()
        }
        if (t.trackKey != readyKey || t.trackKey == unavailableKey) return // the ready handler comes back here
        if (applied?.sameAs(t) == true) return // e.g. a state after a reconnect: keep playing
        if (pausedLocally) { // paused on this phone: follow the room silently; rejoin() starts it
            applied = null; startJob?.cancel(); stopDrift(); player.pause()
            if (!t.playing) player.seekTo(t.positionMs)
            return
        }
        val roomNow = clockSync.roomNow(clock())
        if (t.playing && roomNow == null) return // the first pong comes back here
        applied = t
        startJob?.cancel()
        stopDrift()
        player.pause()
        if (!t.playing) {
            player.seekTo(t.positionMs)
            return
        }
        // Start at atRoomMs while it's still ahead. Otherwise (a mid-song join or a late ready)
        // start on the next whole room second at least JOIN_LEAD_MS away, from where the song will
        // be at that moment, not where it was when we arrived (spec §4).
        val startAt = if (t.atRoomMs > roomNow!!) t.atRoomMs else nextWholeSecond(roomNow + JOIN_LEAD_MS)
        player.seekTo(t.positionMs + (startAt - t.atRoomMs))
        startJob = sessionScope?.launch {
            val wait = startAt - (clockSync.roomNow(clock()) ?: startAt)
            if (wait > 0) delay(wait)
            player.play()
            Log.i(TAG, "start #${t.trackKey} at room $startAt")
            startDrift()
        }
    }

    private fun startDrift() {
        driftJob?.cancel()
        driftJob = sessionScope?.launch {
            while (true) {
                delay(DRIFT_TICK_MS)
                if (pausedLocally) continue
                val t = timeline ?: return@launch
                if (!t.playing || t.trackKey != readyKey) return@launch
                if (clock() < seekHoldUntil) continue // let a seek finish rebuffering before judging drift again
                val roomNow = clockSync.roomNow(clock()) ?: continue
                val expected = t.positionMs + (roomNow - t.atRoomMs)
                val duration = player.durationMs
                if (duration != null && expected >= duration) continue // past the end: the host moves the room on
                val result = drift.onTick(player.positionMs - expected, clock())
                when (val action = result.action) {
                    DriftAction.None -> Unit
                    is DriftAction.Speed -> player.setSpeed(action.speed)
                    DriftAction.Seek -> {
                        // A seek ends any speed nudge: without this the player stays at e.g. 0.97x and oscillates.
                        player.setSpeed(1f)
                        player.seekTo(expected)
                        seekHoldUntil = clock() + SEEK_HOLD_MS
                    }
                }
                if (result.drifting) report(DRIFTING) else if (lastStatus == DRIFTING) report(READY)
            }
        }
    }

    /** After a drift seek, drift ticks skip until this local time while the player rebuffers. */
    private var seekHoldUntil = 0L

    private fun stopDrift() {
        driftJob?.cancel()
        driftJob = null
        drift.reset()
        seekHoldUntil = 0L
        player.setSpeed(1f)
    }

    private fun startPings() {
        pingJob?.cancel()
        pingJob = sessionScope?.launch {
            repeat(FIRST_PINGS) { send(ping()); delay(FIRST_PING_GAP_MS) }
            while (true) { delay(PING_EVERY_MS); send(ping()) }
        }
    }

    private fun ping() = ClientMessage.Ping(clock() - sessionStartClock)

    private fun report(status: String, force: Boolean = false) {
        if (!force && status == lastStatus) return
        lastStatus = status
        send(ClientMessage.Status(status, loadedKey.takeIf { it != NONE }))
    }

    // ── Hosting ───────────────────────────────────────────────────────────────

    private val interceptor = object : SessionInterceptor {
        override fun onPlay() {
            if (!isHost) return rejoin() // a listener's headset, notification or lock-screen play
            pausedLocally = false // the host chose to play: don't pause the room again
            unplayableInARow = 0
            // Play on a song that has ended or that this phone can't play would start nothing here, and a room with
            // no song and none on its way has nothing to play (guests may have queued some): move the room on instead.
            // A song still on its way must not be skipped: the room hasn't echoed the first Load for a moment after a
            // session starts.
            val nothingOnItsWay = room?.track == null && loadsInFlight == 0 && pendingLoad == null
            if (player.ended || cantPlayCurrentSong() || nothingOnItsWay) advanceNow(moveOnReason()) else send(ClientMessage.Play)
            intentChanged()
        }
        override fun onPause() {
            if (isHost) {
                // Kept until the room's paused timeline arrives: a pause it ignores mid-handshake is sent again.
                pausedLocally = true
                send(ClientMessage.Pause)
                // Before returning: ListenTogetherPlayer reads the state again as this returns, and must read "paused".
                intentChanged()
                return
            }
            player.pause() // a listener pauses only their own phone, like an unplug
            playerEvents.onExternalPause()
        }
        override fun onSeek(positionMs: Long) { if (isHost) send(ClientMessage.Seek(positionMs)) }
        override fun onNext() {
            if (!isHost) return
            hostChoseToMove()
            advanceNow("skip")
        }
        override fun onPrevious() {
            if (!isHost) return
            hostChoseToMove()
            previous()
        }
        override fun onAdd(items: List<MediaItem>) {
            // The Play next tag rides the MediaController round trip (PlayNext); shuffle is off in a session, so a
            // Play next always arrives tagged and an Add to queue never does.
            items.partitionRuns { PlayNext.isTagged(it) }.forEach { (run, next) -> addInbox?.trySend(run to next) }
        }
        override fun onSet(items: List<MediaItem>, startIndex: Int) {
            if (!isHost) {
                if (canAddDirectly()) controller.message(R.string.lt_only_host_changes_song)
                else controller.message("Leave the session to play something else")
                return
            }
            hostChoseToMove()
            sessionScope?.launch { replace(items, startIndex) }
        }
    }

    /** A pause still waiting for the room's reply must not land on the song the host just chose, and the count starts over. */
    private fun hostChoseToMove() {
        pausedLocally = false
        unplayableInARow = 0
        intentChanged()
    }

    /**
     * [advance] started now, not on a later turn of the main thread: a Load is out (and counted) before the next room
     * message can see a stopped room and move it on too, which would skip a song.
     */
    private fun advanceNow(why: String = "end", keepPaused: Boolean = false) =
        sessionScope?.launch(start = CoroutineStart.UNDISPATCHED) { advance(why, keepPaused) }

    /**
     * Something to play has arrived in a room that has stopped: its last song ended, the host can't play it, or it
     * never had one. Adds don't pass through the host any more, so nobody would know to tap Play. Not after too many
     * unplayable songs in a row: the host stopped that on purpose, and every add would burn one more song.
     */
    private fun moveOnIfStopped() {
        val r = room ?: return
        if (!isHost || r.queue.isEmpty() || loadsInFlight > 0 || pendingLoad != null) return
        if (unplayableInARow >= MAX_UNPLAYABLE_IN_A_ROW) return
        if (songFinished() || cantPlayCurrentSong() || r.track == null) {
            pausedLocally = false
            advanceNow(moveOnReason())
            publish()
        }
    }

    /**
     * The song ended or the host skipped: the next queued song, else autoplay radio once per song, else idle (§4).
     * [keepPaused]: the host had paused the room, so the song moved to is paused as it starts ([pausedLocally]).
     */
    private suspend fun advance(why: String = "end", keepPaused: Boolean = false) {
        val r = room ?: return
        if (why == UNAVAILABLE && unplayableInARow >= MAX_UNPLAYABLE_IN_A_ROW) return stopOnUnplayable(r)
        val current = r.track
        val next = r.queue.firstOrNull()
        if (next != null) {
            current?.let(::remember)
            if (keepPaused) pausedLocally = true
            load(next, 0, r.queue.drop(1), why)
            return
        }
        // Read the preference itself: shouldAutoplayRadio only says yes while a song is still playing.
        val tryRadio = current != null && radioTriedKey != r.trackKey && autoplayRadio()
        if (tryRadio) radioTriedKey = r.trackKey
        val station = if (tryRadio) catalog.radioAfter(current!!) else emptyList()
        // That can take seconds. The host may have moved on or handed over, and a guest's add may have arrived: a
        // station built on the room as it was would read to the room as the host removing that add.
        val now = room ?: return
        if (!isHost || loadsInFlight > 0 || now.trackKey != r.trackKey) return
        if (now.queue.isNotEmpty()) return advance(why, keepPaused)
        if (station.isNotEmpty()) {
            remember(current!!)
            if (keepPaused) pausedLocally = true
            load(station.first(), 0, station.drop(1), if (why == UNAVAILABLE) why else "radio")
            return
        }
        when {
            why == UNAVAILABLE -> stopOnUnplayable(now)
            timeline?.playing == true -> send(ClientMessage.Pause) // idle, paused at the end of the last song
        }
    }

    /**
     * The host can't play the room's song and there's nowhere to move to, or it has skipped too many in a row. The room
     * mustn't play on for the guests alone, with nothing to move it on at the end, so it pauses here. The room ignores a
     * pause while preparing (and the timeline is null until it's done), so [pausedLocally] sends it again then. Left
     * unset on a room already paused: it would pause the next song. The guests hear why from that pause ([onTimeline]),
     * and the host gets a toast, because the chip shows only on the art view and they may be anywhere in the app.
     */
    private fun stopOnUnplayable(r: RoomState) {
        myId?.let(::noticeSkipped)
        r.track?.title?.let { song ->
            controller.message(if (r.queue.isEmpty()) R.string.lt_stopped_cant_play else R.string.lt_stopped_cant_play_more, song)
        }
        if (timeline?.playing != false) {
            pausedLocally = true
            send(ClientMessage.Pause)
        }
        publish()
    }

    private fun previous() {
        val r = room ?: return
        if (player.positionMs > RESTART_THRESHOLD_MS || history.isEmpty()) {
            send(ClientMessage.Seek(0))
            return
        }
        load(history.removeLast(), 0, (listOfNotNull(r.track) + r.queue).take(MAX_QUEUE), "back")
    }

    /**
     * Reads [inbox] for the whole session: takes one add, then keeps taking whatever else arrives within
     * [ADD_GATHER_MS] (up to [ADD_MAX_WAIT_MS] in all), and sends that as one batch. "Play selected next" calls
     * Play next once per song, a few ms apart, so 25 songs leave as one message instead of 25.
     */
    private suspend fun gatherAdds(inbox: Channel<Pair<List<MediaItem>, Boolean>>) {
        for (first in inbox) {
            val batch = mutableListOf(first)
            val started = clock()
            while (clock() - started < ADD_MAX_WAIT_MS) {
                delay(ADD_GATHER_MS)
                val more = generateSequence { inbox.tryReceive().getOrNull() }.toList()
                if (more.isEmpty()) break
                batch += more
            }
            add(batch)
        }
    }

    /** A room that knows guest adds takes this phone's adds straight in: it hosts, or the host lets guests add. */
    private fun canAddDirectly(): Boolean {
        val r = room ?: return false
        return r.v >= RoomProtocol.VERSION && (isHost || r.guestsAdd)
    }

    /**
     * One gathered batch of Play next / Add to queue, each run with its kind, in tap order. Only as many songs as the
     * room could ever hold are looked up, however many were selected: a whole library would otherwise leave as more
     * messages than the room allows in a second, and it would close the socket.
     */
    private suspend fun add(batch: List<Pair<List<MediaItem>, Boolean>>) {
        var lookups = MAX_QUEUE
        val beyond = (batch.sumOf { it.first.size } - MAX_QUEUE).coerceAtLeast(0)
        val runs = batch
            .mapNotNull { (items, next) ->
                val take = items.take(lookups)
                lookups -= take.size
                take.mapNotNull { catalog.sharedTrackFor(it) }.takeIf { it.isNotEmpty() }?.let { it to next }
            }
            // One message per kind, not per tap: this is what keeps a whole selection under the room's rate limit.
            .mergedRuns()
        if (runs.isNotEmpty()) deliver(runs, beyond)
    }

    /**
     * Sends [runs] to the room, or holds what the socket can't take until the next welcome. A room that knows guest adds
     * gets them as `add`, and answers each one: the toast comes from that answer ([onAddAnswered]), so it can't claim
     * what didn't happen. An older Worker gets what it knows ([deliverToOlderRoom]). [beyond]: songs selected past what
     * was looked up, so the toast knows not all went in.
     */
    private fun deliver(runs: List<Pair<List<SharedTrack>, Boolean>>, beyond: Int = 0) {
        val r = room
        if (r == null || !welcomed) return hold(runs)
        if (r.v < RoomProtocol.VERSION) return deliverToOlderRoom(r, runs)
        val direct = isHost || r.guestsAdd
        // Not sending what can't go in: the room enforces these too, so this only saves the messages.
        var left = when {
            !direct -> MAX_SUGGESTIONS
            isHost -> MAX_QUEUE
            else -> (GUEST_MAX_WAITING - r.queue.count { it.addedBy == myId }).coerceAtLeast(0)
        }
        val sent = AddsOnTheirWay(wanted = runs.sumOf { it.first.size } + beyond, allNext = runs.all { it.second })
        val unsent = mutableListOf<Pair<List<SharedTrack>, Boolean>>()
        for ((tracks, next) in runs) {
            val take = tracks.take(left)
            left -= take.size
            for (chunk in take.chunked(ADD_CHUNK)) {
                if (unsent.isEmpty() && connection?.send(ClientMessage.Add(chunk, next)) == true) sent.unanswered++
                else unsent += chunk to next
            }
        }
        sent.wanted -= unsent.sumOf { it.first.size }
        if (sent.unanswered > 0) awaitingAdds.addLast(sent)
        else if (unsent.isEmpty()) confirmAdds(sent) // none of it could go: a guest's 20 are already waiting
        if (unsent.isNotEmpty()) hold(unsent)
    }

    /** The host's songs at the end of the queue (the next whole queue carries them if the socket is down), a guest's first 3 as suggestions. */
    private fun deliverToOlderRoom(r: RoomState, runs: List<Pair<List<SharedTrack>, Boolean>>) {
        if (isHost) {
            setQueue((r.queue + runs.flatMap { it.first }).take(MAX_QUEUE))
            controller.message(R.string.lt_added_queue)
            return
        }
        val songs = runs.flatMap { it.first }.take(MAX_SUGGESTIONS)
        val sent = songs.takeWhile { connection?.send(ClientMessage.Suggest(it)) == true }.size
        if (sent > 0) suggestedMessage(r)
        if (sent < songs.size) hold(listOf(songs.drop(sent) to false))
    }

    private fun hold(runs: List<Pair<List<SharedTrack>, Boolean>>) {
        heldAdds += runs
        controller.message(R.string.lt_add_waiting)
    }

    private fun onAddAnswered(m: ServerMessage.Added) {
        val batch = awaitingAdds.firstOrNull() ?: return
        batch.taken += m.count
        m.suggested?.let { batch.suggested = (batch.suggested ?: 0) + it }
        if (--batch.unanswered > 0) return
        awaitingAdds.removeFirst()
        confirmAdds(batch)
    }

    private fun confirmAdds(batch: AddsOnTheirWay) {
        val r = room ?: return
        val suggested = batch.suggested
        when {
            suggested != null -> if (suggested > 0) suggestedMessage(r) else controller.message(R.string.lt_suggestions_full)
            batch.taken >= batch.wanted -> controller.message(if (batch.allNext) R.string.lt_added_next else R.string.lt_added_queue)
            !isHost && r.queue.count { it.addedBy == myId } >= GUEST_MAX_WAITING ->
                controller.quantityMessage(R.plurals.lt_guest_cap, GUEST_MAX_WAITING, GUEST_MAX_WAITING)
            else -> controller.message(R.string.lt_queue_full)
        }
    }

    private fun suggestedMessage(r: RoomState) {
        val host = r.members.firstOrNull { it.id == r.host }?.name ?: r.host?.let { memberNames[it] }
        if (host != null) controller.message(R.string.lt_suggested_to, host) else controller.message(R.string.lt_suggested)
    }

    /** A guest takes their own song out (the host edits through [Command.SetQueue]); shown at once, like the host's swipe. */
    private fun unqueue(track: SharedTrack, at: Int) {
        val r = room ?: return
        if (r.v < RoomProtocol.VERSION || (!isHost && track.addedBy != myId)) return
        val i = if (r.queue.getOrNull(at) == track) at else r.queue.indexOf(track)
        if (i >= 0) room = r.copy(queue = r.queue.filterIndexed { j, _ -> j != i })
        send(ClientMessage.Unqueue(track, at))
        publish()
    }

    /** The host's switch. The caller remembers it; the room hears it now while this phone hosts, else in the next hello. */
    private fun setGuestsAdd(on: Boolean) {
        myGuestsAdd = on
        if (isHost && (room?.v ?: 1) >= RoomProtocol.VERSION) send(ClientMessage.Settings(on))
        publish()
    }

    private suspend fun replace(items: List<MediaItem>, startIndex: Int) {
        val index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val first = items.getOrNull(index)?.let { catalog.sharedTrackFor(it) } ?: return
        val rest = items.drop(index + 1).take(MAX_QUEUE).mapNotNull { catalog.sharedTrackFor(it) }
        room?.track?.let(::remember)
        // The room keeps other people's adds in front of a new album, so this phone must too: a skip before the room's
        // echo sends the whole queue from here, and a song missing from it reads as the host removing it. The message
        // itself carries only the album. The host's own adds can't be told from the old album here, so they aren't kept.
        val kept = room?.queue.orEmpty().filter { it.addedBy != null && it.addedBy != myId }
        load(first, 0, rest, "pick")
        room = room?.copy(queue = (kept + rest).take(MAX_QUEUE))
    }

    /**
     * [why] lets the other phones say "Rawn skipped" (skip, pick), "Rawn went back" (back), "Skipped …: Rawn can't
     * play it" (unavailable), or nothing (end, radio). [builtFrom]: the room's queue version [queue] came from, so the
     * room puts back what guests added or removed since.
     */
    private fun load(
        track: SharedTrack,
        positionMs: Long,
        queue: List<SharedTrack>,
        why: String? = null,
        builtFrom: Int? = room?.qv,
    ) {
        room = room?.copy(queue = queue)
        loadsInFlight++
        val msg = ClientMessage.Load(track, positionMs, queue, why, builtFrom)
        if (connection?.send(msg) != true) pendingLoad = { msg } // the socket is down: the next welcome sends it
    }

    /** [built]: the room version [queue] came from. An Up next edit says which it saw, which may be older than the newest. */
    private fun setQueue(queue: List<SharedTrack>, built: Int? = room?.qv) {
        room = room?.copy(queue = queue)
        send(ClientMessage.Queue(queue, built))
    }

    private fun remember(track: SharedTrack) {
        if (history.lastOrNull() == track) return
        history.addLast(track)
        if (history.size > MAX_HISTORY) history.removeFirst()
    }

    // ── Leaving ───────────────────────────────────────────────────────────────

    private fun leave() {
        val r = room
        if (isHost && r != null) {
            // Hand the room to the longest-joined listener instead of leaving it hostless for 60 s.
            r.members.filter { it.id != myId }.minByOrNull { it.joinedAt }?.let { send(ClientMessage.MakeHost(it.id)) }
        }
        teardown(message = null)
    }

    private fun end() {
        if (isHost) send(ClientMessage.End)
        teardown("Session ended")
    }

    private fun teardown(message: String?, restore: Boolean = true) {
        if (code == null) return
        Log.i(TAG, "leaving the room${message?.let { " ($it)" }.orEmpty()}")
        // Close before cancelling: RoomClient runs in sessionScope and aborts its socket when that is cancelled,
        // which would drop the End or MakeHost frame queued a moment ago.
        connection?.close()
        connection = null
        sessionScope?.cancel() // events, pings, start, drift and any advance, add or replace still running
        sessionScope = null
        addInbox?.close()
        addInbox = null
        stopDrift()
        player.events = null
        player.exitSession()
        code = null; url = null; myId = null; token = null; room = null; isHost = false; pendingLoad = null; loadsInFlight = 0
        welcomed = false; unplayableInARow = 0; skipNoticeKey = NONE; heldAdds.clear(); awaitingAdds.clear()
        timeline = null; applied = null; loadedKey = NONE; itemKey = NONE; readyKey = NONE; unavailableKey = NONE; lastStatus = null
        roomPreparingKey = NONE; preparedAt = 0L; prepareLimitJob = null // the job died with sessionScope
        reconnecting = false; versionMismatch = false; history.clear(); radioTriedKey = NONE
        seenMembers.clear(); memberNames.clear(); pendingLeaves.clear() // their jobs died with sessionScope
        pausedLocally = false
        clockSync.reset()
        // exitSession has already stopped and emptied the player. With restore, PlayerRepositoryImpl puts the
        // user's queue back, paused, and saves nothing until then (restorePending).
        controller.setActive(false, restore)
        controller.publish(ListenTogetherState.Idle)
        message?.let(controller::message)
    }

    private fun send(message: ClientMessage) {
        connection?.send(message)
    }

    /** Someone new joined; someone gone for [LEAVE_GRACE_MS] left (a blip that comes back says nothing). */
    private fun noticeMembers(now: List<RoomMember>) {
        val before = room?.members.orEmpty().map { it.id }.toSet()
        val ids = now.map { it.id }.toSet()
        for (m in now) {
            memberNames[m.id] = m.name
            pendingLeaves.remove(m.id)?.cancel()
            if (seenMembers.add(m.id) && m.id != myId) notice(SessionEvent.Kind.JOINED, m.id)
        }
        for (gone in before - ids) {
            if (gone == myId || gone in pendingLeaves) continue
            val job = sessionScope?.launch {
                delay(LEAVE_GRACE_MS)
                pendingLeaves.remove(gone)
                notice(SessionEvent.Kind.LEFT, gone)
            } ?: continue
            pendingLeaves[gone] = job
        }
    }

    private fun notice(kind: SessionEvent.Kind, memberId: String) =
        controller.event(SessionEvent(kind, memberId, memberNames[memberId]))

    private fun publish() {
        intentChanged()
        val code = code ?: return
        val url = url ?: return
        val r = room
        val id = myId
        if (r == null || id == null) {
            controller.publish(ListenTogetherState.Connecting(hosting = isHost))
            return
        }
        controller.publish(
            ListenTogetherState.InRoom(
                code = code, url = url, myId = id, isHost = isHost, hostId = r.host,
                members = r.members, suggestions = r.suggestions, reconnecting = reconnecting,
                unavailable = cantPlayCurrentSong(),
                waitingForHost = waitingForHost(),
                versionMismatch = versionMismatch,
                pausedLocally = pausedLocally,
                track = r.track,
                queue = r.queue,
                qv = r.qv,
                names = memberNames.toMap(),
                roomKnowsAdds = r.v >= RoomProtocol.VERSION,
                guestsAdd = r.v >= RoomProtocol.VERSION && r.guestsAdd,
                myGuestsAdd = myGuestsAdd,
                hostAppOutdated = r.v >= RoomProtocol.VERSION &&
                    r.members.firstOrNull { it.id == r.host }?.let { (it.v ?: 1) < RoomProtocol.VERSION } == true,
            ),
        )
    }

    private fun ServerMessage.TimelineUpdate.sameAs(o: ServerMessage.TimelineUpdate) =
        trackKey == o.trackKey && positionMs == o.positionMs && atRoomMs == o.atRoomMs && playing == o.playing

    /** One batch of adds on its way to the room: how many were wanted and what the room's answers have said so far. */
    private class AddsOnTheirWay(var wanted: Int, val allNext: Boolean) {
        var unanswered = 0
        var taken = 0
        /** Null until the room says it turned them into suggestions. */
        var suggested: Int? = null
    }

    private companion object {
        const val TAG = "ListenTogether"
        const val NONE = -1
        const val MAX_QUEUE = 200
        const val MAX_SUGGESTIONS = 3
        const val MAX_UNPLAYABLE_IN_A_ROW = 3
        const val END_SLOP_MS = 1_000L
        /** One guest's songs waiting in the room's queue (owner decision 7); keep in sync with MAX_GUEST_WAITING in room.js. */
        const val GUEST_MAX_WAITING = 20
        /** Songs per `add` message, well under the room's 256 KB frame even with long titles and cover links. */
        const val ADD_CHUNK = 50
        const val ADD_GATHER_MS = 400L
        const val ADD_MAX_WAIT_MS = 2_000L
        const val MAX_HISTORY = 50
        const val LEAVE_GRACE_MS = 15_000L
        const val JOIN_LEAD_MS = 1_000L
        /**
         * How long the room waits for the host's phone to get a song before it is skipped for everyone
         * ([giveUpIfStillLooking]). Keep in sync with HOST_PREPARE_MS in room.js, whose own cap (HOST_WAIT_CAP_MS, 25 s)
         * only ends the wait for a host that never says.
         */
        const val HOST_PREPARE_MS = 20_000L
        const val DRIFT_TICK_MS = 1_000L
        const val SEEK_HOLD_MS = 2_000L
        const val FIRST_PINGS = 5
        const val FIRST_PING_GAP_MS = 200L
        const val PING_EVERY_MS = 30_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val VERSION_TOLERANCE_MS = 2_000L
        const val READY = "ready"
        /** A member's status in the room's lists for one who reported [READY] (room.js STATUSES). */
        const val MEMBER_OK = "ok"
        const val BUFFERING = "buffering"
        const val UNAVAILABLE = "unavailable"
        const val DRIFTING = "drifting"

        fun nextWholeSecond(ms: Long): Long = (ms + 999) / 1000 * 1000

        /** Neighbouring runs of the same kind as one, each with all its songs, in order. */
        fun List<Pair<List<SharedTrack>, Boolean>>.mergedRuns(): List<Pair<List<SharedTrack>, Boolean>> =
            partitionRuns { it.second }.map { (entries, next) -> entries.flatMap { it.first } to next }

        /** [this] split into runs of neighbours with the same [key], in order: (run, key). */
        fun <T, K> List<T>.partitionRuns(key: (T) -> K): List<Pair<List<T>, K>> {
            val runs = mutableListOf<Pair<MutableList<T>, K>>()
            for (x in this) {
                val k = key(x)
                if (runs.isNotEmpty() && runs.last().second == k) runs.last().first += x else runs += mutableListOf(x) to k
            }
            return runs
        }
    }
}
