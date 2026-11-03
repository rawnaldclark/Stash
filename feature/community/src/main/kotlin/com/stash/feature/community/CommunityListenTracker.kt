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
 * Counts how long a Community post's songs play when they were started from the post, and marks the post heard
 * ([CommunityHeard.mark]) after [HEARD_AFTER_MS] of it: then you may vote on it (contract 2026-11-03).
 *
 * - Started from the post: its Play or Shuffle ([begin], then the queue under [PlaybackSource.Community] for the
 *   post). Any of its songs counts; a song post's radio (it grows around the song, under [PlaybackSource.Radio])
 *   doesn't, though going back to the song does.
 * - Playing time only: a pause or buffering stops the count, play resumes it, and it adds up over every stretch
 *   (the same post started again carries on from where it was). A seek or a skip adds nothing; the count is the
 *   time the songs actually played, not where they got to.
 * - Playback started anywhere else ends it, even with the same song: the source changes.
 *
 * App-wide, so the count goes on when the post's page closes. Nothing is kept across a restart.
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

    /** The post whose songs were last started from it. [radio] is a radio grown from one of its songs. */
    private class Session(val postId: String, val songs: Set<Long>) {
        /** The player has had the post's queue: anything else from now on is playback started elsewhere. */
        var reached = false
        var radio: PlaybackSource.Radio? = null
    }

    private var session: Session? = null

    /** Playing time banked per post, so far. */
    private val playedMs = HashMap<String, Long>()

    /** When the stretch counting now began, or null when nothing counts. */
    private var countingSince: Long? = null

    /** Fires when the stretch counting now reaches [HEARD_AFTER_MS]. */
    private var due: Job? = null

    private var watching: Job? = null

    /** The player's last state seen, read again by [begin]: the post's queue may be playing already (Play again). */
    private var last: PlayerState? = null

    /**
     * The post's [songs] (their track ids) are about to be queued under [PlaybackSource.Community] for [postId].
     * Call it before the queue is set, on the main thread. Not for your own post, nor one already heard.
     */
    fun begin(postId: String, songs: Collection<Long>) {
        bank()
        session = Session(postId, songs.toSet())
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

    private fun onState(state: PlayerState) {
        bank()
        val s = session ?: return
        val song = state.currentTrack?.id
        val source = state.source
        val onPostQueue = when (source) {
            is PlaybackSource.Community -> source.postId == s.postId
            // A radio started from one of the post's songs while it played (a song post's Play does this at once).
            is PlaybackSource.Radio -> source == s.radio || (song in s.songs).also { if (it) s.radio = source }
            else -> false
        }
        if (!onPostQueue) {
            // Before the post's queue arrives the old one still shows; once it has, this is playback from elsewhere.
            if (s.reached) session = null
            return
        }
        s.reached = true
        if (state.isPlaying && song in s.songs) count(s)
    }

    private fun count(s: Session) {
        countingSince = nowMs()
        val left = (HEARD_AFTER_MS - playedMs.getOrDefault(s.postId, 0L)).coerceAtLeast(0L)
        due = scope.launch {
            delay(left)
            due = null
            bank()
            if (playedMs.getOrDefault(s.postId, 0L) >= HEARD_AFTER_MS && session === s) {
                session = null
                playedMs.remove(s.postId)
                onHeard(s.postId)
            }
        }
    }

    /** Stops the stretch counting now, if any, and adds it to its post. */
    private fun bank() {
        due?.cancel()
        due = null
        val since = countingSince ?: return
        countingSince = null
        val id = session?.postId ?: return
        playedMs[id] = playedMs.getOrDefault(id, 0L) + (nowMs() - since).coerceAtLeast(0L)
    }

    companion object {
        /** How long a post's songs must play before you can vote on it (contract 2026-11-03). */
        const val HEARD_AFTER_MS = 30_000L
    }
}
