package com.stash.core.media.computer

import android.os.SystemClock
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.weblink.WebSongs
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.remote.ComputerCommand
import com.stash.core.data.weblink.remote.ComputerLink
import com.stash.core.data.weblink.remote.ComputerLive
import com.stash.core.data.weblink.remote.ComputerWire
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a computer was playing when this phone took the music back: [songs] from the playing one on, [positionMs] into it. */
data class ComputerQueue(val songs: List<WireSong>, val positionMs: Long, val repeat: RepeatMode, val from: String)

/** A linked computer as "Play on" lists it. [playing]: what it plays now, or null; [live] false: Stash isn't open there. */
data class ComputerOutput(val device: String, val name: String, val live: Boolean, val playing: String?)

/**
 * This phone as the remote for Stash on a linked computer (owner, 2026-11-24: "browsing on my phone but playing through my pc
 * app … add to queue from my phone while using it as a remote"). The computer keeps the queue; this phone shows what it plays
 * and sends it what the listener does.
 *
 * - **When**: while a computer is [attached]. That happens by itself when a linked computer plays and this phone doesn't (open
 *   the app and it shows what the computer plays), or when the listener picks the computer in "Play on" (this phone's music
 *   moves there). It ends when the listener picks "This phone", when this phone starts playing by itself, or when the computer
 *   goes (Stash closed there: nothing is heard from it for a minute).
 * - **What goes there**: the player's own calls ([com.stash.core.media.PlayerRepositoryImpl] asks [handles] first): play, pause,
 *   skip, seek, a tapped list (its songs become the computer's queue), Play next, Add to queue, and the queue's edits (remove,
 *   move, jump to a song), shuffle and repeat. Songs only this phone can play (a file with no ids, SoundCloud, a cloud file) are
 *   left out ([WebSongs]); a list of nothing but those plays here instead.
 * - **What the app shows**: [view], the computer's state as this phone's [PlayerState] (its queue from the playing song on),
 *   and [position], moving on while it plays. A song this phone sent is shown as this phone's row, so its heart and its
 *   download work; others as the computer describes them.
 */
@Singleton
class ComputerRemote internal constructor(
    private val link: ComputerLink,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** A song's row in this library, or null; never makes one. */
    private val find: suspend (WireSong) -> Track? = { null },
) {
    @Inject constructor(link: ComputerLink, matcher: HandoffSongMatcher) : this(
        link,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        SystemClock::elapsedRealtime,
        { matcher.find(it)?.toDomain() },
    )

    private val chosen = MutableStateFlow<String?>(null)
    /** Computer sessions the listener stepped away from ("This phone"): not taken up again by themselves. */
    private val declined: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    /** This phone's songs as sent, by their words: the computer names them back the same way. */
    private val sent = ConcurrentHashMap<String, Track>()
    /** The computer's songs looked up in this library, by their words: the row, or null (looked for, not held). */
    private val known = MutableStateFlow<Map<String, Track?>>(emptyMap())
    private val looking: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var phoneActive = false

    /** The computer this phone is the remote for, as it last said, or null. */
    val attached: StateFlow<ComputerLive?> = combine(chosen, link.computers) { device, all -> device?.let(all::get) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The attached computer's state as this phone's player state, or null while none is attached. */
    val view: StateFlow<PlayerState?> = combine(attached.onEach { it?.let(::lookUp) }, known) { live, _ -> live?.let(::stateOf) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // The remote for a computer: the socket stays open in the background too, for its notification and lock screen.
        attached.map { it != null }.distinctUntilChanged().onEach(link::hold).launchIn(scope)
    }

    /** The linked computers for "Play on". */
    val outputs: StateFlow<List<ComputerOutput>> = combine(link.linkedComputers, link.computers) { names, live ->
        names.map { (id, name) ->
            val st = live[id]?.takeIf { fresh(it) }?.state
            ComputerOutput(id, name, st != null, st?.song?.takeIf { st.playing }?.title)
        }.sortedBy { it.name.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The position on the attached computer, moving on while it plays (four times a second, like the player's). */
    val position: Flow<Long> = flow {
        while (true) {
            current()?.let { emit(positionOf(it)) }
            delay(POSITION_TICK_MS)
        }
    }.distinctUntilChanged()

    /**
     * Follows this phone's own player: while it plays, no computer is taken up by itself, and the moment it starts to play
     * (the listener chose this phone, or a computer went away), this phone stops being a remote. Called once by the player.
     */
    fun watchPhone(phone: StateFlow<PlayerState>) {
        phone.map { it.playWhenReady || it.isPlaying }.distinctUntilChanged().onEach { active ->
            phoneActive = active
            if (active) chosen.value = null else takeUp()
        }.launchIn(scope)
        link.computers.onEach { if (!phoneActive) takeUp() }.launchIn(scope)
    }

    /** Takes up a computer that plays while this phone doesn't (one the listener didn't step away from). */
    private fun takeUp() {
        if (chosen.value != null && current() != null) return
        val playing = link.computers.value.values.firstOrNull { fresh(it) && it.state.playing && it.state.song != null && it.state.sid !in declined }
        chosen.value = playing?.device ?: chosen.value?.takeIf { it in link.computers.value }
    }

    /** Where the attached computer is in its song now, or null. */
    fun positionNow(): Long? = current()?.let(::positionOf)

    /** The attached computer, when it is heard from; null otherwise (the player then plays here). */
    fun current(): ComputerLive? = chosen.value?.let { link.computers.value[it] }?.takeIf(::fresh)

    /**
     * "Play on" picked [device]: this phone's queue [queue] (the playing song at [index], [positionMs] into it, playing if
     * [playing]) moves there, and this phone is its remote. With nothing playing here, the computer carries on with its own.
     */
    fun pick(device: String, queue: List<Track>, index: Int, positionMs: Long, playing: Boolean, source: PlaybackSource): Boolean {
        val live = link.computers.value[device]?.takeIf(::fresh) ?: return false
        declined.remove(live.state.sid)
        chosen.value = device
        if (queue.isNotEmpty() && index in queue.indices) start(device, queue, index, positionMs, playing, source)
        return true
    }

    /**
     * "This phone": the computer pauses, and this phone stops being its remote until the listener picks it again. Returns what
     * the computer was playing (its queue from the playing song on, and where in it), so the music carries on here; null when
     * it wasn't playing.
     */
    fun leave(): ComputerQueue? {
        val live = current()
        chosen.value = null
        if (live == null) return null
        declined.add(live.state.sid)
        link.sendToComputer(live.device, ComputerCommand.Pause)
        val st = live.state
        val song = st.song?.takeIf { st.playing } ?: return null
        val items = st.items.map { it.song }
        val songs = if (st.items.firstOrNull()?.iid == st.iid) items else listOf(song) + items
        return ComputerQueue(songs, positionOf(live), repeatOf(st.repeat), st.from?.takeIf { it.isNotBlank() } ?: live.name)
    }

    // ------------------------------------------------------------------------------------------ the player's calls

    fun play() = send(ComputerCommand.Play)
    fun pause() = send(ComputerCommand.Pause)
    fun next() = send(ComputerCommand.Next)
    fun previous() = send(ComputerCommand.Prev)
    fun seekTo(positionMs: Long) = send(ComputerCommand.Seek(positionMs))
    fun setVolume(volume: Float) = send(ComputerCommand.Volume(volume.toDouble()))
    fun toggleShuffle(): Boolean = current()?.let { send(ComputerCommand.Shuffle(!it.state.shuffle)) } ?: false

    fun cycleRepeat(): Boolean = current()?.let {
        send(ComputerCommand.Repeat(when (it.state.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }))
    } ?: false

    /** A tapped list: its songs become the computer's queue, from [startIndex]. False when none of them can play there. */
    fun setQueue(tracks: List<Track>, startIndex: Int, source: PlaybackSource, positionMs: Long = 0L): Boolean {
        val live = current() ?: return false
        return start(live.device, tracks, startIndex, positionMs, true, source)
    }

    /** A search result, played there by its YouTube id (asked of the computer's add-ons first). */
    fun playItem(item: TrackItem): Boolean {
        val live = current() ?: return false
        val song = WireSong(
            title = item.title,
            artist = item.artist,
            album = item.album,
            durationMs = (item.durationSeconds * 1000).toLong().takeIf { it > 0 },
            refs = mapOf("youtube" to item.videoId),
            artwork = listOfNotNull(item.thumbnailUrl?.takeIf { it.startsWith("https://") }),
        )
        link.sendToComputer(live.device, ComputerCommand.Start(listOf(song), 0, 0, true, null))
        return true
    }

    /** Play next ([next]) or Add to queue. False when none of [tracks] can play there. */
    fun add(tracks: List<Track>, next: Boolean): Boolean {
        val live = current() ?: return false
        val songs = songsOf(tracks)
        if (songs.isEmpty()) return false
        // Play next keeps the listener's order: the last chunk goes first, each lands right after the playing song.
        val chunks = songs.chunked(ComputerWire.MAX_SONGS).take(MAX_CHUNKS)
        (if (next) chunks.reversed() else chunks).forEach { link.sendToComputer(live.device, ComputerCommand.Add(it, next)) }
        return true
    }

    /** The queue's row [index] (in [view]'s queue: the playing song is 0) taken out. */
    fun removeAt(index: Int): Boolean = itemAt(index)?.let { send(ComputerCommand.Remove(it)) } ?: false

    /** The queue's row [from] moved to [to] (both in [view]'s queue). */
    fun move(from: Int, to: Int): Boolean {
        val live = current() ?: return false
        val iid = itemAt(from) ?: return false
        return send(ComputerCommand.Move(iid, live.state.at + to.coerceAtLeast(0)))
    }

    /** The queue's row [index] played now. */
    fun skipTo(index: Int): Boolean = itemAt(index)?.let { send(ComputerCommand.SkipTo(it)) } ?: false

    // ------------------------------------------------------------------------------------------ inside

    private fun send(c: ComputerCommand): Boolean {
        val live = current() ?: return false
        link.sendToComputer(live.device, c)
        return true
    }

    private fun itemAt(index: Int): String? = current()?.state?.items?.getOrNull(index)?.iid

    private fun start(device: String, tracks: List<Track>, startIndex: Int, positionMs: Long, play: Boolean, source: PlaybackSource): Boolean {
        // From the tapped song on: the computer takes 100 songs a command, the rest follow as additions.
        val from = tracks.drop(startIndex.coerceIn(0, tracks.size))
        val songs = songsOf(from)
        if (songs.isEmpty()) return false
        val first = songs.take(ComputerWire.MAX_SONGS)
        // The tapped song may be one the computer can't play: then the queue starts on the next one, from its start.
        val tappedPlays = from.firstOrNull()?.let(WebSongs::playsOnWeb) == true
        val label = source.displayLabel.takeIf { it.isNotBlank() }
        link.sendToComputer(device, ComputerCommand.Start(first, 0, if (tappedPlays) positionMs else 0L, play, label))
        songs.drop(ComputerWire.MAX_SONGS).chunked(ComputerWire.MAX_SONGS).take(MAX_CHUNKS - 1)
            .forEach { link.sendToComputer(device, ComputerCommand.Add(it, false)) }
        return true
    }

    /** [tracks] as songs the computer can play, each remembered so it shows as this phone's row when the computer names it. */
    private fun songsOf(tracks: List<Track>): List<WireSong> = tracks.mapNotNull { t ->
        WebSongs.songOf(t)?.takeUnless { it.phoneOnly }?.also { if (t.id > 0) sent[key(it.title, it.artist)] = t }
    }

    private fun fresh(live: ComputerLive) = clock() - live.receivedAt < STALE_MS

    private fun positionOf(live: ComputerLive): Long {
        val st = live.state
        val moved = if (st.playing && !st.loading) clock() - live.receivedAt else 0L
        val at = st.positionMs + moved
        return if (st.durationMs > 0) at.coerceAtMost(st.durationMs) else at
    }

    private fun stateOf(live: ComputerLive): PlayerState {
        val st = live.state
        val queue = st.items.map { trackOf(it.iid, it.song) }
        val playingTrack = st.song?.let { song -> queue.firstOrNull()?.takeIf { st.items.firstOrNull()?.iid == st.iid } ?: trackOf(st.iid, song) }
        return PlayerState(
            currentTrack = playingTrack,
            isPlaying = st.playing && !st.loading,
            positionMs = positionOf(live),
            durationMs = st.durationMs,
            isShuffleEnabled = st.shuffle,
            repeatMode = repeatOf(st.repeat),
            queue = queue,
            currentIndex = 0,
            isStreaming = true,
            isBuffering = st.loading,
            playWhenReady = st.playing,
            source = st.from?.let { PlaybackSource.Handoff(it) } ?: PlaybackSource.Unknown,
            queueSongsBefore = st.at,
            queueSongsAfter = (st.length - st.at - queue.size).coerceAtLeast(0),
            queuePosition = st.at,
        )
    }

    /**
     * [live]'s songs this phone hasn't looked for yet, looked up in its library: a song the computer plays that this library
     * holds shows as its row (its heart, its download, its mark in the lists). Nothing is added to the library.
     */
    private fun lookUp(live: ComputerLive) {
        val songs = (listOfNotNull(live.state.song) + live.state.items.map { it.song })
            .distinctBy { key(it.title, it.artist) }
            .filter { key(it.title, it.artist).let { k -> !sent.containsKey(k) && k !in known.value && k !in looking } }
        if (songs.isEmpty()) return
        scope.launch {
            for (song in songs) {
                val k = key(song.title, song.artist)
                if (k in known.value || !looking.add(k)) continue
                try {
                    val row = find(song)?.takeIf { it.id > 0 }
                    known.update { m -> (if (m.size >= MAX_KNOWN) emptyMap() else m) + (k to row) }
                } finally {
                    looking.remove(k)
                }
            }
        }
    }

    /** This phone's row for the song (one it sent, or one its library holds), else the song as the computer describes it. */
    private fun trackOf(iid: String?, song: WireSong): Track = key(song.title, song.artist).let { sent[it] ?: known.value[it] } ?: Track(
        id = syntheticId(iid, song),
        title = song.title,
        artist = song.artist,
        album = song.album.orEmpty(),
        durationMs = song.durationMs ?: 0L,
        source = MusicSource.YOUTUBE,
        youtubeId = song.youtubeId,
        albumArtUrl = song.artwork.firstOrNull(),
        isrc = song.isrc,
    )

    companion object {
        /** A remote with no linked computer (tests, and screens built without one). */
        fun unlinked(): ComputerRemote = ComputerRemote(
            object : ComputerLink {
                override val computers = MutableStateFlow<Map<String, ComputerLive>>(emptyMap())
                override val linkedComputers = MutableStateFlow<Map<String, String>>(emptyMap())
                override fun sendToComputer(device: String, command: ComputerCommand) = Unit
            },
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            SystemClock::elapsedRealtime,
        )

        /** A computer not heard from for this long has gone (it says what it plays at least every 20 s). */
        internal const val STALE_MS = 65_000L
        internal const val POSITION_TICK_MS = 250L

        /** A list goes over as at most this many commands of 100 songs. */
        internal const val MAX_CHUNKS = 5

        /** Looked-up songs kept; past this the list starts again. */
        private const val MAX_KNOWN = 2_000

        private fun repeatOf(mode: String) = when (mode) { "all" -> RepeatMode.ALL; "one" -> RepeatMode.ONE; else -> RepeatMode.OFF }

        internal fun key(title: String, artist: String) = title.trim().lowercase() + "\u0000" + artist.trim().lowercase()

        /** Negative, so it never names a library row, and the same for the same queue item. */
        internal fun syntheticId(iid: String?, song: WireSong): Long =
            -(1_000_000_000L + ((iid ?: (song.title + song.artist)).hashCode().toLong() and 0x7fffffffL))
    }
}
