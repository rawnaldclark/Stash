package com.stash.core.data.audio

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.tuning.SongTuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each song's own tuning as playback and Now Playing read it (true tuning, spec 2026-11-21): [observe] follows the
 * stored reading, so a measurement that lands while the song plays is heard and shown at once; [measureAgain] is
 * the listener's "Measure again".
 */
@Singleton
class SongTunings @Inject constructor(
    private val trackDao: TrackDao,
    private val measurer: TuningMeasurer,
) {
    /** The song's tuning, and each change to it. A song that isn't in the library reads as not measured. */
    fun observe(trackId: Long): Flow<SongTuning> =
        trackDao.observeTuning(trackId)
            .map { row -> if (row == null) SongTuning.NotMeasured else SongTuning.of(row.cents, row.confidence, row.measuredAt) }
            .distinctUntilChanged()

    /**
     * Measures the song's file now and stores what it reads, whatever was stored before.
     *
     * @return false when the song has no file on this phone to measure (a stream, or a `content://` path).
     */
    suspend fun measureAgain(trackId: Long): Boolean {
        val file = trackDao.getById(trackId)?.filePath?.let(::File)?.takeIf { it.exists() } ?: return false
        when (val r = measurer.measure(file)) {
            is TuningMeasurer.Result.Measured -> trackDao.updateTuning(trackId, r.cents, r.confidence, System.currentTimeMillis())
            is TuningMeasurer.Result.Failed -> trackDao.markTuningFailed(trackId, System.currentTimeMillis())
        }
        return true
    }
}
