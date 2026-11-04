package com.stash.core.media.cast

import android.os.SystemClock
import com.stash.core.data.weblink.remote.BrowserLink
import com.stash.core.data.weblink.remote.RemoteCards
import com.stash.core.media.listen.ListenTogetherController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A linked browser as "Play on" lists it (Remote v2, spec 2026-11-03 §3). */
data class BrowserOutput(
    val device: String,
    /** "Chrome on Windows". */
    val name: String,
    val kind: Kind,
    /** What it plays, for "Playing Pink + White". */
    val title: String?,
) {
    enum class Kind {
        /** Its tab is open and it can play this phone's music. */
        READY,

        /** It plays its own music now ([title]); picking it plays this phone's instead (owner's decision D5). */
        PLAYING,

        /** Its tab was never clicked: it will hold the song until it is ("Click the Stash tab once first"). */
        NEEDS_CLICK,

        /** An older tab without Remote v2 ("Reload the Stash tab to play there"): it can't be picked. */
        RELOAD,

        /** Linked, but no tab is open ("Open play.stashfm.app to play there"): it can't be picked. */
        NOT_OPEN,
    }

    val canPick: Boolean get() = kind != Kind.RELOAD && kind != Kind.NOT_OPEN
}

/** The browser this phone's queue plays on, and how that stands. */
data class BrowserSession(val device: String, val name: String, val link: WebCastRemote.Link) {
    /** "Chrome on Windows" → "Chrome", for "Reconnecting to Chrome…" and "Click the Stash tab once in Chrome". */
    val app: String get() = RemoteCards.appName(name)
}

/** One line for the user when a browser session ends by itself, or skips songs (a snackbar). */
sealed interface BrowserNotice {
    /** The browser closed: the music is back on this phone, paused. Offers Play. */
    data class Closed(val name: String) : BrowserNotice

    /** This phone's connection stayed down: the music is back on this phone, paused. Offers Play. */
    data class Lost(val name: String) : BrowserNotice

    /** Someone at the browser picked their own music. */
    data class OwnMusic(val name: String) : BrowserNotice

    /** The browser never said it had the song: the music stayed on this phone. */
    data class NoAnswer(val name: String) : BrowserNotice

    /** The browser was unlinked (from this phone or from it): the music is back on this phone, paused. Offers Play. */
    data class Unlinked(val name: String) : BrowserNotice

    /** Songs only this phone has were stepped over (once a session). */
    data class Skipped(val count: Int) : BrowserNotice
}

/**
 * The linked browsers this phone can play its queue on (Remote v2, spec 2026-11-03 §2.2): the "Play on" sheet lists [browsers],
 * [connect] picks one, and the playback service plays through [remote] while it is set, exactly as through a Chromecast's
 * ([CastDevices.remote]). It pins the remote socket while a browser plays, so it lives on in the background.
 *
 * - **One output at a time**: a browser and a Chromecast exclude each other, the newest pick wins (picking a browser ends the
 *   speaker's session, a speaker connecting ends the browser's); Listen Together refuses a browser and ends one that plays.
 * - **Ending** ([WebCastRemote.End]): "This phone" ([disconnect]) carries the music on here (owner's decision D2); a browser that
 *   went away, was unlinked, or a connection that stayed down, leaves it here paused; each says so once ([notices]).
 *
 * Main thread only.
 */
@Singleton
class BrowserOutputs internal constructor(
    private val link: BrowserLink,
    private val castDevices: CastDevices,
    private val together: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    @Inject constructor(link: BrowserLink, castDevices: CastDevices, together: ListenTogetherController) :
        this(link, castDevices, together.active, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), SystemClock::elapsedRealtime)

    /** Why a session was ended from this phone. */
    enum class Stop {
        /** "This phone" in the sheet: the music carries on here. */
        THIS_PHONE,

        /** A Chromecast took over. */
        SPEAKER,

        /** A Listen Together room started. */
        LISTEN_TOGETHER,

        /** The playback service stopped (a web session is never resumed: the phone resumes locally, as ever). */
        SERVICE,
    }

    private class Active(val remote: WebCastRemote, val jobs: List<Job>)

    private var active: Active? = null

    private val _remote = MutableStateFlow<CastRemote?>(null)

    /** The browser the playback service plays through, or null. */
    val remote: StateFlow<CastRemote?> = _remote.asStateFlow()

    private val _session = MutableStateFlow<BrowserSession?>(null)

    /** The browser this phone's queue plays on, for the sheet, Now Playing, the mini player and the notification; or null. */
    val session: StateFlow<BrowserSession?> = _session.asStateFlow()

    private val _notices = MutableSharedFlow<BrowserNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<BrowserNotice> = _notices.asSharedFlow()

    private val _served = MutableStateFlow(false)

    /** The playback service plays through a browser right now: it says so as it starts and as it is back on the phone. */
    val served: StateFlow<Boolean> = _served.asStateFlow()

    /** The space has a linked browser: "Play on" shows even on a phone without Cast. */
    val hasBrowser: StateFlow<Boolean> = link.linked.map { it.isNotEmpty() }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Every linked browser, open tab or not, with what picking it would do; fresh every few seconds while watched. A tab whose
     * last word was `bye` (closing or reloading) is not open.
     */
    val browsers: StateFlow<List<BrowserOutput>> =
        combine(link.linked, link.live, _session, ticker()) { linked, live, session, _ ->
            val now = clock()
            linked.map { (device, name) ->
                val b = live[device]?.takeIf {
                    !it.state.bye && (now - it.receivedAt <= RemoteCards.STALE_MS || device == session?.device)
                }
                val s = b?.state
                val kind = when {
                    s == null -> BrowserOutput.Kind.NOT_OPEN
                    !s.canDrive -> BrowserOutput.Kind.RELOAD
                    s.needsGesture -> BrowserOutput.Kind.NEEDS_CLICK
                    s.playing && s.title != null -> BrowserOutput.Kind.PLAYING
                    else -> BrowserOutput.Kind.READY
                }
                BrowserOutput(device, name, kind, s?.title)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Playing from": the queue's source label (the player repository says), sent with each song. */
    @Volatile var sourceLabel: () -> String? = { null }

    /** Whether the music carries on when it comes back to this phone; the service reads it once, as [remote] turns null. */
    private var resumeOnPhone = false

    /** The one "Skipped N songs" a session gets. */
    private var skippedNoted = false

    init {
        // The newest pick wins: a speaker that connects while a browser plays takes the music. Only a new one counts: the
        // speaker a browser pick is replacing is still connected for a moment, as it was.
        scope.launch {
            castDevices.remote.drop(1).filterNotNull().collect { if (active != null) end(Stop.SPEAKER) }
        }
        scope.launch {
            together.collect { if (it && active != null) end(Stop.LISTEN_TOGETHER) }
        }
    }

    /**
     * Plays this phone's queue on [device]: true when it is now the output. False during Listen Together, before the remote socket
     * knows this phone, or for a browser that can't be driven (no tab open, an older tab, a tab that just said `bye`).
     */
    fun connect(device: String): Boolean {
        if (together.value) return false
        val me = link.deviceId ?: return false
        active?.let { if (it.remote.device == device) return true }
        val b = link.live.value[device]?.takeIf { it.state.canDrive && !it.state.bye } ?: return false
        val name = link.linked.value[device] ?: return false // unlinked a moment ago
        // Another browser plays now: it lets go, and the service moves the music to this one where it was (no pause between).
        active?.let { old ->
            link.release(old.remote.device, pause = true)
            close(old)
        }
        link.pin(device)
        lateinit var remote: WebCastRemote
        remote = WebCastRemote(
            device = device,
            name = name,
            me = me,
            send = { link.sendTo(device, it) },
            scope = scope,
            clock = clock,
            onEnd = { reason -> onEnded(remote, reason) },
        )
        remote.onState(b)
        active = Active(
            remote,
            listOf(
                scope.launch { link.live.map { it[device] }.distinctUntilChanged().collect { remote.onState(it) } },
                scope.launch { remote.link.collect { _session.value = BrowserSession(device, name, it) } },
                // Unlinked, from here or from the browser: nothing more can reach it, so the music comes back at once.
                scope.launch {
                    link.linked.first { device !in it }
                    onUnlinked(remote)
                },
            ),
        )
        skippedNoted = false
        resumeOnPhone = false
        _session.value = BrowserSession(device, name, remote.link.value)
        _remote.value = remote
        // A speaker playing now lets go (the service has already moved the music off it); one still connecting is cancelled,
        // or it would take the music from the browser picked after it once it connects.
        if (castDevices.remote.value != null || castDevices.connection.value is CastConnection.Connecting) castDevices.disconnect()
        return true
    }

    /** "This phone": the browser lets go and pauses; the music carries on here (D2). */
    fun disconnect() = end(Stop.THIS_PHONE)

    /** Ends the session from this phone: the browser lets go and pauses. */
    fun end(reason: Stop) {
        val a = active ?: return
        link.release(a.remote.device, pause = true)
        finish(a, resume = reason == Stop.THIS_PHONE)
    }

    /** The service, as [remote] turns null: whether to carry on playing here. Read once. */
    fun takeResumeOnPhone(): Boolean = resumeOnPhone.also { resumeOnPhone = false }

    /** The playback service started ([on]) or stopped playing through a browser. */
    fun noteServed(on: Boolean) {
        _served.value = on
        if (!on) _phoneOnly.value = emptySet()
    }

    private val _phoneOnly = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * While a browser plays the queue: the queue rows (by the Track id the queue shows) it can't play, which the playback
     * service steps over there. The queue dims these, so what it shows is what the service does. Empty on this phone.
     */
    val phoneOnly: StateFlow<Set<Long>> = _phoneOnly.asStateFlow()

    /** The playback service looked the queue up for the browser: [ids] are the rows it will step over. */
    fun notePhoneOnly(ids: Set<Long>) {
        _phoneOnly.value = ids
    }

    /** The cast wrapper stepped over [count] songs only this phone has: said once a session. */
    fun noteSkipped(count: Int) {
        if (active == null || skippedNoted || count <= 0) return
        skippedNoted = true
        _notices.tryEmit(BrowserNotice.Skipped(count))
    }

    private fun onEnded(remote: WebCastRemote, reason: WebCastRemote.End) {
        val a = active?.takeIf { it.remote === remote } ?: return
        when (reason) {
            WebCastRemote.End.CLOSED -> _notices.tryEmit(BrowserNotice.Closed(remote.name))
            WebCastRemote.End.LOST -> _notices.tryEmit(BrowserNotice.Lost(remote.name))
            WebCastRemote.End.TOOK_OVER -> _notices.tryEmit(BrowserNotice.OwnMusic(remote.name))
            WebCastRemote.End.NO_ANSWER -> _notices.tryEmit(BrowserNotice.NoAnswer(remote.name))
        }
        // One that took over isn't ours to tell (a release only counts from its driver); any other is told, in case it is
        // only slow: a late answer must not start the music there.
        if (reason != WebCastRemote.End.TOOK_OVER) link.release(remote.device, pause = true)
        // A browser that never answered never had the music: it stays here as it was. One that went away leaves it here paused.
        finish(a, resume = reason == WebCastRemote.End.NO_ANSWER)
    }

    /** The browser left the space: it can't be told to let go, and the music comes back here paused. */
    private fun onUnlinked(remote: WebCastRemote) {
        val a = active?.takeIf { it.remote === remote } ?: return
        _notices.tryEmit(BrowserNotice.Unlinked(remote.name))
        finish(a, resume = false)
    }

    private fun finish(a: Active, resume: Boolean) {
        close(a)
        link.unpin()
        _session.value = null
        resumeOnPhone = resume
        _remote.value = null // last: the service reads resumeOnPhone as it sees this
    }

    private fun close(a: Active) {
        a.jobs.forEach { it.cancel() }
        a.remote.dispose()
        if (active === a) active = null
    }

    private fun ticker() = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    private companion object {
        /** How often the sheet re-checks which tabs are still open. */
        const val TICK_MS = 5_000L
    }
}
