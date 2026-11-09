package com.stash.feature.community

import android.os.SystemClock
import com.stash.core.data.community.CommunityHeard
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlaybackSource
import com.stash.core.model.PlayerState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Counts how long a Community post's songs play when they were started from Community, and marks the post heard
 * ([CommunityHeard.mark]) after [HEARD_AFTER_MS] of it: then you may vote on it (contract 2026-11-03).
 *
 * - Started from the post: its Play or Shuffle ([begin], then the queue under [PlaybackSource.Community] for the
 *   post). Any of its songs counts. A song post's Play grows a radio around the song ([expectRadio]): the song counts
 *   on there, the radio's other songs don't. Only that radio: one started anywhere else, even on the same song, is
 *   playback from elsewhere.
 * - Started from the Community tab's Play all or Shuffle ([beginChart], then the queue under [CHART_SOURCE], and
 *   [chartPost] for each post as its songs go in): each song counts for the post it came from, so 30 s of one post's
 *   songs marks that post and no other. A song two of its posts hold counts for both.
 * - Playing time only: a pause or buffering stops the count, play resumes it, and it adds up over every stretch
 *   (the same post started again carries on from where it was). A seek or a skip adds nothing; the count is the
 *   time the songs actually played, not where they got to.
 * - Playback started anywhere else ends it, even with the same song: the source changes.
 *
 * App-wide, so the count goes on when the post's page closes. Nothing is kept across a restart. A listen this misses
 * (the same song from the Library, a queue that came back after a restart) is found in the listening history instead,
 * once it has counted as a listen there (CommunityRepository.heardFromHistory).
 */
@Singleton
class CommunityListenTracker internal constructor(
    private val playerState: Flow<PlayerState>,
    private val onHeard: suspend (postId: String) -> Unit,
    private val scope: CoroutineScope,
    /** Monotonic, in ms. Tests pass the test scheduler's time. */
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    @Inject
    constructor(player: PlayerRepository, heard: CommunityHeard) : this(
        playerState = player.playerState,
        onHeard = heard::mark,
        // The player's state changes on the main thread; begin() is called there too.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    /**
     * The queue last started from Community: [queue] is its [PlaybackSource.Community.postId], the post's id or
     * [CHART] for the chart. [radio] is the radio a song post's own Play grew around its song.
     */
    private class Session(val queue: String) {
        /** Each song's track id → the posts it counts for (one, unless two of the chart's posts hold the song). */
        val songs = HashMap<Long, MutableSet<String>>()

        /** The player has had the queue: anything else from now on is playback started elsewhere. */
        var reached = false

        /** The post's Play is starting a radio ([expectRadio]): the first radio to follow its queue is that one. */
        var radioDue = false
        var radio: PlaybackSource.Radio? = null

        fun add(postId: String, ids: Collection<Long>) = ids.forEach { id -> songs.getOrPut(id) { HashSet() } += postId }
    }

    /** A stretch being counted now: since when, and for which posts. */
    private class Stretch(val since: Long, val posts: Set<String>)

    private var session: Session? = null

    /** Playing time banked per post, so far. */
    private val playedMs = HashMap<String, Long>()

    /** The stretch counting now, or null when nothing counts. */
    private var counting: Stretch? = null

    /** Fires when the stretch counting now takes one of its posts to [HEARD_AFTER_MS]. */
    private var due: Job? = null

    private var watching: Job? = null

    /** The player's last state seen, read again by [begin]: the post's queue may be playing already (Play again). */
    private var last: PlayerState? = null

    /**
     * The post's [songs] (their track ids) are about to be queued under [PlaybackSource.Community] for [postId].
     * Call it before the queue is set, on the main thread. Not for your own post, nor one already heard.
     */
    fun begin(postId: String, songs: Collection<Long>) = start(Session(postId).also { it.add(postId, songs) })

    /**
     * The Community tab's Play all or Shuffle is about to set its queue under [CHART_SOURCE]; each post's songs follow
     * with [chartPost]. Call it before the queue is set, on the main thread.
     */
    fun beginChart() = start(Session(CHART))

    /**
     * [postId]'s [songs] (their track ids) are going into the chart's queue ([beginChart]): 30 s of them marks that
     * post. Nothing once other playback has started since. On the main thread; not for your own post, nor one heard.
     */
    fun chartPost(postId: String, songs: Collection<Long>) {
        val s = session?.takeIf { it.queue == CHART } ?: return
        s.add(postId, songs)
        last?.let(::onState) // the song playing now may be one of them
    }

    private fun start(s: Session) {
        bank()
        session = s
        if (watching == null) {
            watching = scope.launch {
                playerState
                    .distinctUntilChanged { a, b ->
                        a.currentTrack?.id == b.currentTrack?.id && a.isPlaying == b.isPlaying && a.source == b.source
                    }
                    .collect { last = it; onState(it) }
            }
        } else {
            last?.let(::onState)
        }
    }

    /**
     * The post's own Play is about to start a radio around its song (on the main thread, right before
     * `PlayerRepository.startRadio`): the first radio to follow the post's queue is that one, and counts as the post's.
     */
    fun expectRadio(postId: String) {
        session?.takeIf { it.queue == postId && it.radio == null }?.radioDue = true
    }

    /** That radio didn't start: no radio counts for the post. */
    fun noRadio(postId: String) {
        session?.takeIf { it.queue == postId }?.radioDue = false
    }

    private fun onState(state: PlayerState) {
        bank()
        val s = session ?: return
        val source = state.source
        val onQueue = when (source) {
            is PlaybackSource.Community -> source.postId == s.queue
            // The radio the post's own Play started around its song (expectRadio), and no other.
            is PlaybackSource.Radio -> source == s.radio || s.radioDue.also { if (it) { s.radio = source; s.radioDue = false } }
            else -> false
        }
        if (!onQueue) {
            // Before the queue arrives the old one still shows; once it has, this is playback from elsewhere.
            if (s.reached) session = null
            return
        }
        s.reached = true
        val posts = state.currentTrack?.id?.let { s.songs[it] }
        if (state.isPlaying && !posts.isNullOrEmpty()) count(s, posts.toSet())
    }

    private fun count(s: Session, posts: Set<String>) {
        counting = Stretch(nowMs(), posts)
        val left = posts.minOf { (HEARD_AFTER_MS - playedMs.getOrDefault(it, 0L)).coerceAtLeast(0L) }
        due = scope.launch {
            delay(left)
            due = null
            bank()
            if (session !== s) return@launch
            val heard = posts.filter { playedMs.getOrDefault(it, 0L) >= HEARD_AFTER_MS }
            heard.forEach { id ->
                playedMs.remove(id)
                s.songs.values.forEach { it.remove(id) }
            }
            // Every post heard: the session is over. Otherwise the song may count on for another post that holds it.
            if (s.songs.values.all { it.isEmpty() }) session = null else last?.let(::onState)
            heard.forEach { onHeard(it) }
        }
    }

    /** Stops the stretch counting now, if any, and adds it to each of its posts. */
    private fun bank() {
        due?.cancel()
        due = null
        val stretch = counting ?: return
        counting = null
        val ms = (nowMs() - stretch.since).coerceAtLeast(0L)
        stretch.posts.forEach { playedMs[it] = playedMs.getOrDefault(it, 0L) + ms }
    }

    companion object {
        /** How long a post's songs must play before you can vote on it (contract 2026-11-03). */
        const val HEARD_AFTER_MS = 30_000L

        /** The chart's [PlaybackSource.Community.postId]: no post's (post ids are 8 letters and digits). */
        internal const val CHART = ""

        /**
         * What the Community tab's Play all and Shuffle queue under, so the tracker tells their queue from any other.
         * No label: Now Playing shows no "Playing from" line for it, as before.
         */
        val CHART_SOURCE = PlaybackSource.Community(postId = CHART, label = "")
    }
}
