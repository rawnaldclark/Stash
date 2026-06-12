package com.stash.core.media.listening

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.lastfm.LastFmScrobbler
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackSkipEventEntity
import com.stash.core.media.PlayerRepository
import com.stash.core.model.RepeatMode
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
 * play (Last.fm convention: ≥30s for tracks longer than 60s, or ≥50% of
 * a shorter track).
 *
 * The recorder runs on an app-scoped [CoroutineScope] so it keeps working
 * when screens are recreated. [start] should be called once from the
 * [com.stash.app.StashApplication] onCreate.
 *
 * Invariants:
 *   - Exactly one ListeningEventEntity per (track play session).
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
    private val listeningEventDao: ListeningEventDao,
    private val trackSkipEventDao: TrackSkipEventDao,
    private val scrobbler: LastFmScrobbler,
    private val scope: CoroutineScope,
) {

    @Inject
    constructor(
        playerRepository: PlayerRepository,
        listeningEventDao: ListeningEventDao,
        trackSkipEventDao: TrackSkipEventDao,
        scrobbler: LastFmScrobbler,
    ) : this(
        playerRepository = playerRepository,
        listeningEventDao = listeningEventDao,
        trackSkipEventDao = trackSkipEventDao,
        scrobbler = scrobbler,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /**
     * Pending threshold-fire metadata. We keep the [firedFlag] alongside
     * the [job] because [Job.cancel] completes the job synchronously —
     * `isCompleted` cannot distinguish "delay body ran" from "delay was
     * cancelled mid-flight". The flag is set inside the delay body
     * BEFORE the listening_events insert, so a track-change collector
     * reading `firedFlag.get()` after `cancel()` sees the truthful state.
     */
    private data class PendingFire(
        val trackId: Long,
        val sessionStart: Long,
        val job: Job,
        val firedFlag: AtomicBoolean,
        val positionAtScheduleMs: Long,
    )

    private var pending: PendingFire? = null

    /** Must be called exactly once from Application.onCreate. */
    fun start() {
        // ── Collector 1: track-change transitions ─────────────────────
        scope.launch {
            // Drop repeats on the SAME track id so we only react to track
            // transitions. Pause/resume mid-track re-emits the same state
            // but with different positionMs — those shouldn't restart the
            // countdown. Different track id always wins.
            playerRepository.playerState
                .distinctUntilChangedBy { it.currentTrack?.id }
                .collect { state ->
                    // 1. Snapshot previous pending and record a skip if
                    //    its delayed-fire never completed (cancellation
                    //    completes the Job either way, so isCompleted is
                    //    unreliable — we use an AtomicBoolean flag set
                    //    inside the delay block right before the insert).
                    val previousPending = pending
                    if (previousPending != null) {
                        previousPending.job.cancel()
                        if (!previousPending.firedFlag.get()) {
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
                                        trackId = previousPending.trackId,
                                        skippedAt = skipAt,
                                        positionMs = position,
                                    ),
                                )
                            }.onFailure { Log.w(TAG, "skip insert failed", it) }
                        }
                    }
                    pending = null

                    // 2. Schedule the new track's threshold-fire.
                    val track = state.currentTrack ?: return@collect
                    schedulePendingFire(track.id, track.artist, track.title, track.album, track.durationMs)
                }
        }

        // ── Collector 2: repeat-one loop detection ────────────────────
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
                        pending?.job?.cancel()
                        pending = null
                        schedulePendingFire(
                            track.id, track.artist, track.title,
                            track.album, track.durationMs,
                        )
                    }

                    lastPositionMs = positionMs
                }
        }
    }

    /**
     * Schedules a threshold-fire for the given track. Shared by both the
     * track-change collector and the repeat-one loop detector so the
     * insert + now-playing logic stays in one place.
     */
    private fun schedulePendingFire(
        trackId: Long,
        artist: String,
        title: String,
        album: String,
        durationMs: Long,
    ) {
        val sessionStart = System.currentTimeMillis()
        val threshold = thresholdFor(durationMs)
        val firedFlag = AtomicBoolean(false)
        val job = scope.launch {
            delay(threshold)
            val nowPlaying = playerRepository.playerState.value.currentTrack?.id
            // Mark fired BEFORE the insert so a race with
            // the next track-change collector observes
            // the correct state when it reads .get().
            if (nowPlaying == trackId) {
                firedFlag.set(true)
                runCatching {
                    listeningEventDao.insert(
                        ListeningEventEntity(
                            trackId = trackId,
                            startedAt = sessionStart,
                            scrobbled = false,
                            // v0.9.13: insert IS the completion event — recorder only fires
                            // after threshold delay. AutoSaveScrobbler reads completed_at.
                            completedAt = sessionStart,
                        ),
                    )
                }.onFailure { Log.w(TAG, "Failed to insert listening event", it) }
            }
        }
        pending = PendingFire(
            trackId = trackId,
            sessionStart = sessionStart,
            job = job,
            firedFlag = firedFlag,
            positionAtScheduleMs = playerRepository.playerState.value.positionMs,
        )
        scope.launch {
            scrobbler.notifyNowPlaying(
                artist = artist,
                track = title,
                album = album.takeIf { it.isNotBlank() },
            )
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