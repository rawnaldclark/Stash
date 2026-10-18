package com.stash.core.data.listening

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.stash.core.data.db.dao.EventStamp
import com.stash.core.data.db.dao.ListeningHistoryDao
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stamps the local day and minute (v59) onto listens recorded before v59, once, at the first start
 * after the upgrade (and after a restore of an older backup). Called from
 * `ListeningRecorder.start()` next to the play-stats backfill.
 *
 * The zone is the phone's zone now, the best there is for an old row: a listen heard abroad keeps
 * the day of home. New listens are stamped where they're heard.
 *
 * Batches of [BATCH] rows, one transaction each, walking ids upward, so it never revisits a row and
 * always ends: a row it couldn't stamp is left for the next start, not retried in a loop. Until a
 * row is stamped the queries read its UTC day (`COALESCE(local_day, started_at / 86400000)`).
 */
@Singleton
class ListeningDayBackfill @VisibleForTesting internal constructor(
    private val dao: ListeningHistoryDao,
    private val zone: () -> ZoneId,
) {

    @Inject
    constructor(dao: ListeningHistoryDao) : this(dao, { ZoneId.systemDefault() })

    /** Stamps every unstamped listen. Returns how many were stamped. */
    suspend fun run(): Int {
        // Nothing to do on almost every start: one index lookup, not a walk of the table.
        if (dao.countUnstamped() == 0) return 0
        val z = zone()
        var afterId = 0L
        var stamped = 0
        while (true) {
            val batch = dao.unstampedAfter(afterId, BATCH)
            if (batch.isEmpty()) break
            dao.stampAll(
                batch.map { row ->
                    val s = ListeningDay.localStamp(row.startedAt, z)
                    EventStamp(eventId = row.id, day = s.day, minute = s.minute)
                },
            )
            stamped += batch.size
            afterId = batch.last().id
            if (batch.size < BATCH) break
        }
        if (stamped > 0) Log.i(TAG, "stamped the local day of $stamped earlier listens")
        return stamped
    }

    companion object {
        private const val TAG = "ListeningDayBackfill"

        @VisibleForTesting
        internal const val BATCH = 1000
    }
}
