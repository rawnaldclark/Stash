package com.stash.core.media.listening

import android.util.Log
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.media.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
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
 *   - If the user switches tracks before the threshold hits, no event is
 *     recorded — matching Last.fm's "not a play" convention.
 */
@Singleton
class ListeningRecorder @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val listeningEventDao: ListeningEventDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingFireJob: Job? = null

    /** Must be called exactly once from Application.onCreate. */
    fun start() {
        scope.launch {
            // Drop repeats on the SAME track id so we only react to track
            // transitions. Pause/resume mid-track re-emits the same state
            // but with different positionMs — those shouldn't restart the
            // countdown. Different track id always wins.
            playerRepository.playerState
                .distinctUntilChangedBy { it.currentTrack?.id }
                .collect { state ->
                    pendingFireJob?.cancel()
                    val track = state.currentTrack ?: return@collect
                    val threshold = thresholdFor(track.durationMs)
                    val sessionStart = System.currentTimeMillis()
                    pendingFireJob = scope.launch {
                        delay(threshold)
                        val nowPlaying = playerRepository.playerState.value.currentTrack?.id
                        if (nowPlaying == track.id) {
                            runCatching {
                                listeningEventDao.insert(
                                    ListeningEventEntity(
                                        trackId = track.id,
                                        startedAt = sessionStart,
                                        scrobbled = false,
                                    ),
                                )
                            }.onFailure { Log.w(TAG, "Failed to insert listening event", it) }
                        }
                    }
                }
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
