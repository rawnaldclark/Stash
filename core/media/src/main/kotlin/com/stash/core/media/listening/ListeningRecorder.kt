package com.stash.core.media.listening

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.lastfm.LastFmScrobbler
import com.stash.core.data.listen.Listen
import com.stash.core.data.listen.ListenSinkCoordinator
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackSkipEventEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.mediaToWallMs
import com.stash.core.media.wallToMediaMs
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the playback state and records a [ListeningEventEntity] each
 * time the user listens to a track long enough for it to "count" as a
 * play: half the track, at least 30 s and at most 4 minutes (see [thresholdFor]).
 *
 * The recorder runs on an app-scoped [CoroutineScope] so it keeps working
 * when screens are recreated. [start] should be called once from the
 * [com.stash.app.StashApplication] onCreate.
 *
 * Invariants:
 *   - Exactly one ListeningEventEntity per (track play session).
 *   - The threshold counts time actually PLAYING, as Last.fm defines a
 *     scrobble: a pause (or buffering) stops the countdown and play
 *     resumes it. A song played for 3 s and left paused never counts.
 *     It is measured in song time, so at 2x half the song still counts.
 *   - Switching tracks cancels the pending fire; the new track starts
 *     its own countdown.
 *   - If the user switches tracks before the threshold hits, no
 *     ListeningEventEntity is recorded — matching Last.fm's "not a
 *     play" convention. v0.9.16: a [TrackSkipEventEntity] IS recorded
 *     instead, feeding the skip-rate penalty in
 *     [com.stash.core.data.mix.MixGenerator].
 *   - When repeat-one is active, a position reset back to near zero
 *     on the same track is treated as a new play session so each loop
 *     counts as a separate scrobble.
 */
@Singleton
class ListeningRecorder @VisibleForTesting internal constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val listeningEventDao: ListeningEventDao,
    private val trackSkipEventDao: TrackSkipEventDao,
    private val scrobbler: LastFmScrobbler,
    private val listenSinks: ListenSinkCoordinator,
    private val scope: CoroutineScope,
    /** Monotonic clock for listening time. Tests pass the test scheduler's time. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    @Inject
    constructor(
        playerRepository: PlayerRepository,
        musicRepository: MusicRepository,
        listeningEventDao: ListeningEventDao,
        trackSkipEventDao: TrackSkipEventDao,
        scrobbler: LastFmScrobbler,
        listenSinks: ListenSinkCoordinator,
    ) : this(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        listeningEventDao = listeningEventDao,
        trackSkipEventDao = trackSkipEventDao,
        scrobbler = scrobbler,
        listenSinks = listenSinks,
        // One thread at a time: the track and play-state collectors both read and write `pending`.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    )

    /**
     * One play session of [track]. [claimed] is a one-shot handoff between the
     * threshold job and a transition. Only the side that atomically claims it
     * may act. [job] counts down the listening time still needed and runs only
     * while the track plays; [playedMs] banks the time of earlier play stretches.
     * Both are song time: at 2x, 10 s of playing covers 20 s of the song.
     */
    private class PendingFire(
        val track: Track,
        val sessionStart: Long,
        val claimed: AtomicBoolean,
        val positionAtScheduleMs: Long,
    ) {
        var job: Job? = null
        var playedMs = 0L
        var playingSinceMs = 0L
        /** The playback speed of the current play stretch. */
        var speed = 1f
    }

    private var pending: PendingFire? = null

    /** Must be called exactly once from Application.onCreate. */
    fun start() {
        scope.launch {
            try {
                listeningEventDao.backfillMissingTrackStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backfill track play stats", e)
            }
            startTrackChangeCollector()
            startRepeatCollector()
            startSpeedCollector()
        }
    }

    // ── Collector 1: track-change transitions ─────────────────────
    private fun startTrackChangeCollector() {
        scope.launch {
            // React to track transitions, plus play/pause edges so a song shown
            // paused (a restart's ghost, a queue put back after Listen Together)
            // starts its session only when it actually plays. Position ticks
            // re-emit the same key and are dropped.
            var lastTrackId: Long? = null
            playerRepository.playerState
                .distinctUntilChangedBy { it.currentTrack?.id to it.isPlaying }
                .collect { state ->
                    val trackChanged = state.currentTrack?.id != lastTrackId
                    lastTrackId = state.currentTrack?.id
                    // 1. Claim the previous session for this transition.
                    //    If completion already claimed it, leave its job
                    //    alive so persistence + listen recording can finish.
                    val previousPending = pending
                    if (trackChanged && previousPending != null) {
                        if (previousPending.claimed.compareAndSet(false, true)) {
                            previousPending.job?.cancel()
                            val skipAt = System.currentTimeMillis()
                            val position = previousPending.positionAtScheduleMs
                            // Inline (rather than `scope.launch { ... }`) so
                            // the suspend insert completes within the collect
                            // tick — keeps the skip ordered with respect to
                            // the next track's scheduling and lets the test
                            // harness observe the insert without an extra
                            // dispatcher round-trip.
                            runCatching {
                                trackSkipEventDao.insert(
                                    TrackSkipEventEntity(
                                        trackId = previousPending.track.id,
                                        skippedAt = skipAt,
                                        positionMs = position,
                                    ),
                                )
                            }.onFailure { Log.w(TAG, "skip insert failed", it) }
                        }
                    }
                    if (trackChanged) pending = null

                    // 2. The session starts on the track's first real play. Nothing is
                    //    announced or counted for a song only shown paused; a pause stops
                    //    the countdown and play resumes it within the same session.
                    val track = state.currentTrack ?: return@collect
                    val current = pending
                    when {
                        !state.isPlaying -> current?.let { pauseCountdown(it) }
                        current == null -> schedulePendingFire(track)
                        else -> resumeCountdown(current)
                    }
                }
        }
    }

    // ── Collector 2: repeat-one loop detection ────────────────────
    private fun startRepeatCollector() {
        scope.launch {
            var lastPositionMs = 0L
            var lastTrackId = -1L
            playerRepository.currentPosition
                .collect { positionMs ->
                    val state = playerRepository.playerState.value
                    val track = state.currentTrack ?: run {
                        lastPositionMs = 0L
                        lastTrackId = -1L
                        return@collect
                    }

                    // Reset position tracking on track change so a manual
                    // switch from a far-into track A to track B doesn't
                    // misfire as a repeat-one loop.
                    if (track.id != lastTrackId) {
                        lastPositionMs = 0L
                        lastTrackId = track.id
                        return@collect
                    }

                    val isRepeatOne = state.repeatMode == RepeatMode.ONE
                    // Require near-zero landing position (< 5s) to distinguish
                    // a genuine loop restart from a user scrubbing backwards.
                    val positionJumpedBack = lastPositionMs > 10_000L
                        && positionMs < 5_000L

                    if (isRepeatOne && positionJumpedBack) {
                        Log.d(TAG, "repeat detected for track ${track.id} — scheduling new fire")
                        pending?.takeIf { it.claimed.compareAndSet(false, true) }?.job?.cancel()
                        pending = null
                        // Paused (a scrub to 0 or "previous" while paused): the next play edge starts the session.
                        if (state.isPlaying) schedulePendingFire(track)
                    }

                    lastPositionMs = positionMs
                }
        }
    }

    // ── Collector 3: playback speed ───────────────────────────────
    /**
     * Counted in playing time, a song at 2x would only reach its threshold
     * (half the song) as it ends, and lose that race to the next song, which
     * records a skip. So a speed change banks the stretch played so far at
     * the old speed and counts the rest down at the new one.
     */
    private fun startSpeedCollector() {
        scope.launch {
            playerRepository.playbackSpeed.collect {
                val playing = pending?.takeIf { it.job != null } ?: return@collect
                pauseCountdown(playing)
                resumeCountdown(playing)
            }
        }
    }

    /**
     * Starts a play session for the given track and its countdown. Shared by
     * both the track-change collector and the repeat-one loop detector so the
     * insert + now-playing logic stays in one place. Callers run while the
     * track plays.
     */
    private fun schedulePendingFire(track: Track) {
        val sessionStart = System.currentTimeMillis()
        val session = PendingFire(
            track = track,
            sessionStart = sessionStart,
            claimed = AtomicBoolean(false),
            positionAtScheduleMs = playerRepository.playerState.value.positionMs,
        )
        pending = session
        resumeCountdown(session)
        scope.launch {
            scrobbler.notifyNowPlaying(
                artist = track.artist,
                track = track.title,
                album = track.album.takeIf { it.isNotBlank() },
            )
            listenSinks.notifyNowPlaying(
                Listen(
                    // No listening_events row exists yet — a now-playing listen
                    // has not finished, so it has no id and never needs one.
                    eventId = 0L,
                    artist = track.artist,
                    title = track.title,
                    album = track.album.takeIf { it.isNotBlank() },
                    durationMs = track.durationMs,
                    startedAtMs = sessionStart,
                ),
            )
        }
    }

    /** Play started or resumed: count down the listening time still needed. */
    private fun resumeCountdown(session: PendingFire) {
        if (session.job != null || session.claimed.get()) return
        session.playingSinceMs = nowMs()
        session.speed = playerRepository.playbackSpeed.value
        val remaining = (thresholdFor(session.track.durationMs) - session.playedMs).coerceAtLeast(0L)
        session.job = scope.launch {
            delay(mediaToWallMs(remaining, session.speed))
            recordListen(session)
        }
    }

    /** Paused or buffering: stop the countdown and bank the time played so far. */
    private fun pauseCountdown(session: PendingFire) {
        val job = session.job ?: return
        if (session.claimed.get()) return // the listen is already being recorded; let it finish
        job.cancel()
        session.job = null
        session.playedMs += wallToMediaMs(nowMs() - session.playingSinceMs, session.speed)
    }

    /** Enough listening time has played: record the listen, unless a transition claimed it first. */
    private suspend fun recordListen(session: PendingFire) {
        val track = session.track
        val nowPlaying = playerRepository.playerState.value.currentTrack?.id
        if (nowPlaying != track.id || !session.claimed.compareAndSet(false, true)) return
        try {
            val persistedTrackId = musicRepository.ensureTrackPersisted(track)
            val completedAt = System.currentTimeMillis()
            listeningEventDao.recordCompletedListen(
                ListeningEventEntity(
                    trackId = persistedTrackId,
                    startedAt = session.sessionStart,
                    scrobbled = false,
                    // v0.9.13: insert IS the completion event — recorder only fires
                    // after threshold delay. AutoSaveScrobbler reads completed_at.
                    completedAt = completedAt,
                ),
            )
            // Push the new listen to the generic sinks (ListenBrainz).
            // Last.fm has its own Flow-driven scrobbler and needs no nudge.
            listenSinks.onListenRecorded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record completed listen", e)
        }
    }

    /**
     * Last.fm scrobble threshold: minimum of 4 minutes OR half the track.
     * For very short tracks we floor at 30s so a 45-second song still
     * needs a reasonable listen. Tracks with unknown duration get 30s.
     */
    private fun thresholdFor(durationMs: Long): Long {
        if (durationMs <= 0) return 30_000L
        val half = durationMs / 2
        val fourMin = 4L * 60 * 1000
        return half.coerceIn(30_000L, fourMin)
    }

    companion object {
        private const val TAG = "ListeningRecorder"
    }
}
