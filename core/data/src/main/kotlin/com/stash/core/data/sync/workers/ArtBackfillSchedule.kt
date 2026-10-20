package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Two numbers; never part of a backup. */
private val Context.artBackfillScheduleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "art_backfill_schedule",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * When [ArtBackfillWorker] last ran and the newest candidate row it saw, so a
 * run is skipped when there is nothing new to do.
 *
 * The worker is enqueued on every cold start and after every mix refresh
 * (which itself runs on every cold start and after each discovery drain).
 * Before this, every one of those enqueues walked up to 500 rows through
 * Last.fm again, the same rows each time. Now a run goes ahead only when:
 *  - a candidate row newer than the last run's newest exists (a new stub or
 *    download needs art: served at once, so a fresh mix still fills in), or
 *  - [MIN_INTERVAL_MS] has passed since the last run (older rows whose
 *    back-off ended get their retry, a few times a day at most).
 */
@Singleton
class ArtBackfillSchedule internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.artBackfillScheduleDataStore)

    /** The last run's start time and newest candidate id; zeros when it never ran. */
    data class State(val lastRunAtMs: Long, val watermarkId: Long)

    suspend fun read(): State = try {
        val prefs = dataStore.data.first()
        State(prefs[LAST_RUN_AT] ?: 0L, prefs[WATERMARK] ?: 0L)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unreadable: behave as never run, which runs the backfill. Never block it.
        Log.w(TAG, "couldn't read the art backfill schedule; running", e)
        State(0L, 0L)
    }

    /** Records a run that started at [startedAtMs] having seen [newestCandidateId]. Best effort. */
    suspend fun recordRun(startedAtMs: Long, newestCandidateId: Long) {
        try {
            dataStore.edit {
                it[LAST_RUN_AT] = startedAtMs
                it[WATERMARK] = maxOf(it[WATERMARK] ?: 0L, newestCandidateId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't record the art backfill run", e)
        }
    }

    internal companion object {
        private const val TAG = "ArtBackfillSchedule"
        private val LAST_RUN_AT = longPreferencesKey("last_run_at")
        private val WATERMARK = longPreferencesKey("watermark_id")

        /** How often a run with no new rows may go ahead: retries of older rows. */
        const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

        /**
         * Whether a run should go ahead. [newestCandidateId] is the newest row
         * any pass would look at (null: none at all, nothing to do). A last run
         * stamped in the future (the clock moved back) counts as long ago.
         */
        fun shouldRun(state: State, newestCandidateId: Long?, nowMs: Long): Boolean {
            if (newestCandidateId == null) return false
            if (newestCandidateId > state.watermarkId) return true
            return nowMs - state.lastRunAtMs !in 0 until MIN_INTERVAL_MS
        }
    }
}
