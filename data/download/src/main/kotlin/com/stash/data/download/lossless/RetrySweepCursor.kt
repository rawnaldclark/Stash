package com.stash.data.download.lossless

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the retry sweep stopped: the queue id of the last waiting row it checked. A pass checks
 * at most [LosslessRetryWorker.MAX_LOOKUPS_PER_PASS] rows, so without this every pass began
 * at the oldest rows (the ones that keep missing) and the newer ones were never reached. The
 * next pass starts after this id and wraps around. 0 = from the start.
 */
@Singleton
class RetrySweepCursor internal constructor(private val prefs: SharedPreferences?) {

    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    @Volatile private var memory = 0L

    fun get(): Long = prefs?.getLong(KEY, 0L) ?: memory

    fun set(queueId: Long) {
        if (prefs != null) prefs.edit().putLong(KEY, queueId).apply() else memory = queueId
    }

    companion object {
        private const val PREFS = "lossless_retry_sweep"
        private const val KEY = "cursor_queue_id"

        /** Tests: no SharedPreferences. */
        internal fun inMemory() = RetrySweepCursor(null)
    }
}
