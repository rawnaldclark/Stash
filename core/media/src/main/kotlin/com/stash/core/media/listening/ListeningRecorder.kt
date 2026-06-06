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

    // Tracks the last known position for repeat-one detection.
    private var lastPositionMs = 0L

    /** Must be called exactly once from Application.onCreate. */
    fun start() {
        // ── Collector 1: track-change transitions ─────────────────────
        scope.launch {
            playerRepository.playerState
                .distinctUntilChangedBy { it.currentTrack?.id }
                .collect { state ->
                    val previousPending = pending
                    if (previousPending != null) {
                        previousPending.job.cancel()
                        if (!previousPending.firedFlag.get()) {
                            val skipAt = System.currentTimeMillis()
                            val position = previousPending.positionAtScheduleMs
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

                    val track = state.currentTrack ?: return@collect
                    schedulePendingFire(track.id, track.artist, track.title, track.album, track.durationMs)
                }
        }

        // ── Collector 2: repeat-one loop detection ────────────────────
        scope.launch {
            playerRepository.playerState
                .collect { state ->
                    val track = state.currentTrack ?: run {
                        lastPositionMs = 0L
                        return@collect
                    }

                    val positionJumpedBack = lastPositionMs > 10_000L && state.positionMs < lastPositionMs - 5_000L
                    val isRepeatOne = state.repeatMode == RepeatMode.ONE

                    Log.d(TAG, "repeat-check: repeatMode=$isRepeatOne lastPos=$lastPositionMs currentPos=${state.positionMs} jumpedBack=$positionJumpedBack")

                    if (isRepeatOne && positionJumpedBack) {
                        Log.d(TAG, "repeat detected for track ${track.id} — scheduling new fire")
                        // The track looped — cancel any existing pending fire
                        // (the previous loop's threshold may not have fired yet
                        // if the track is shorter than the threshold) and
                        // start a fresh countdown for this new loop.
                        pending?.job?.cancel()
                        pending = null
                        schedulePendingFire(track.id, track.artist, track.title, track.album, track.durationMs)
                    }

                    lastPositionMs = state.positionMs
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
            if (nowPlaying == trackId) {
                firedFlag.set(true)
                runCatching {
                    listeningEventDao.insert(
                        ListeningEventEntity(
                            trackId = trackId,
                            startedAt = sessionStart,
                            scrobbled = false,
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