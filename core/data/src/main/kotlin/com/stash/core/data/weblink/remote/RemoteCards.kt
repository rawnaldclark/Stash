package com.stash.core.data.weblink.remote

/**
 * A linked browser's last state, as this phone received it ([receivedAt]: this phone's monotonic clock).
 *
 * @property lostAt set while this phone's own socket is down and it drives this browser (Remote v2): the state is the last one
 *   heard, kept for the grace instead of forgotten; the browser may well be playing on (spec 2026-11-03 §3).
 */
data class BrowserState(val device: String, val name: String, val state: RemoteState, val receivedAt: Long, val lostAt: Long? = null)

/**
 * What the "Playing on Chrome" card and its sheet show (spec 2026-10-10 §6.7): one browser that advertises itself, its song, and
 * where it is. The position moves on while it plays: [positionAt].
 *
 * @property deviceName the browser's label, as this phone verified it ("Chrome on Windows").
 * @property app the browser alone ("Chrome"), for "Click the Stash tab once in Chrome".
 * @property volume 0–1, or null when that browser can't set its volume (the sheet shows no slider).
 */
data class RemoteCard(
    val device: String,
    val deviceName: String,
    val app: String,
    val title: String,
    val artist: String,
    val art: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val rate: Double,
    val volume: Float?,
    val canSeek: Boolean,
    val needsGesture: Boolean,
    /** This phone's monotonic clock when [positionMs] was true. */
    val readAt: Long,
) {
    /** "Playing on Chrome on Windows" / "Paused on …", or the one click the browser needs before it can start the music. */
    val status: String
        get() = when {
            needsGesture -> "Click the Stash tab once in $app"
            playing -> "Playing on $deviceName"
            else -> "Paused on $deviceName"
        }

    /** Where the song is at [elapsed] (this phone's monotonic clock), never past its end. */
    fun positionAt(elapsed: Long): Long {
        if (!playing) return positionMs
        val p = positionMs + ((elapsed - readAt).coerceAtLeast(0) * rate).toLong()
        return if (durationMs > 0) minOf(p, durationMs) else p
    }
}

/** The card's rules, pure (unit-tested): which browser it shows, and when a browser counts as gone. */
object RemoteCards {
    /** A browser heartbeats every 5 s playing and 20 s paused: a minute of silence means its tab froze or closed. */
    const val STALE_MS = 65_000L

    /** "Chrome on Windows" → "Chrome"; a renamed browser keeps its whole name. */
    fun appName(label: String): String = label.substringBefore(" on ").trim().ifEmpty { label }

    /**
     * The card for the browsers that advertise, or null: only fresh states with a song (nothing to control otherwise); a browser
     * that plays wins over a paused one, then the newest. Never a browser this phone plays its own queue on ([driven], or one
     * whose state names this phone, [me], as its driver): the mini player is that one's remote (Remote v2 §6).
     */
    fun pick(browsers: Collection<BrowserState>, now: Long, driven: String? = null, me: String? = null): RemoteCard? {
        val b = browsers
            .filter { now - it.receivedAt <= STALE_MS && it.state.title != null && it.state.artist != null }
            .filter { it.device != driven && (me == null || it.state.driver != me) }
            .maxWithOrNull(compareBy<BrowserState>({ it.state.playing }, { it.receivedAt }))
            ?: return null
        val s = b.state
        return RemoteCard(
            device = b.device,
            deviceName = b.name,
            app = appName(b.name),
            title = s.title!!,
            artist = s.artist!!,
            art = s.art,
            playing = s.playing,
            positionMs = if (s.durationMs > 0) minOf(s.positionMs, s.durationMs) else s.positionMs,
            durationMs = s.durationMs,
            rate = s.rate,
            volume = s.volume?.toFloat(),
            canSeek = s.canSeek && s.durationMs > 0,
            needsGesture = s.needsGesture,
            readAt = b.receivedAt,
        )
    }

    /**
     * What the card shows right after a command, before the browser's next state confirms it (a tap feels instant): a pause
     * stops the clock where it is, a play starts it (unless the tab needs its click), a seek or a volume moves at once.
     */
    fun optimistic(b: BrowserState, command: RemoteCommand, now: Long): BrowserState {
        val s = b.state
        val here = if (s.playing) s.positionMs + ((now - b.receivedAt).coerceAtLeast(0) * s.rate).toLong() else s.positionMs
        val next = when (command) {
            RemoteCommand.Pause -> s.copy(playing = false, positionMs = here)
            RemoteCommand.Play -> if (s.needsGesture) s else s.copy(playing = true, positionMs = here)
            is RemoteCommand.Seek -> s.copy(positionMs = command.positionMs)
            is RemoteCommand.Volume -> s.copy(volume = command.volume.coerceIn(0.0, 1.0))
            // What these lead to is only known from the browser's next state (the cast wrapper waits for it).
            RemoteCommand.Next, RemoteCommand.Prev, is RemoteCommand.Load, is RemoteCommand.UpNext, is RemoteCommand.Jump,
            RemoteCommand.Stop, is RemoteCommand.Release -> return b
        }
        return b.copy(state = next, receivedAt = now)
    }
}
