package com.stash.feature.record.taste

import android.content.Context
import android.util.Log
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.prefs.RecordInMixesPreference
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Use your record in mixes" (owner decision D2; stash-player `src/lib/discover/record-switch.ts`): the switch, and today's Daily
 * Discover made again once when it is flipped, so the change shows today, not tomorrow. A flip waits [PAUSE_MS] for another (off,
 * then on again, is no change); then, when it changed what the mix was last made with and there is a taste for it to change,
 * Daily Discover is made again in today's order (once it has been made at all: the next refresh makes the first one).
 */
@Singleton
class RecordInMixes internal constructor(
    private val switch: RecordInMixesPreference,
    private val hasTaste: suspend () -> Boolean,
    /** Today's Daily Discover made again (its id), in today's order; false when there is none to make again. */
    private val rebuild: suspend () -> Boolean,
    scope: CoroutineScope? = null,
    private val pauseMs: Long = PAUSE_MS,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        switch: RecordInMixesPreference,
        taste: RecordTaste,
        recipes: StashMixRecipeDao,
    ) : this(
        switch = switch,
        hasTaste = taste::has,
        rebuild = {
            val dd = RecordTaste.dailyDiscover(recipes)
            if (dd?.playlistId == null) {
                false
            } else {
                StashMixRefreshWorker.enqueueOneTime(context, dd.id, sameDay = true)
                true
            }
        },
    )

    /** One flip at a time, in the order they came: the last one asked is the one kept. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val on: Flow<Boolean> = switch.enabled

    /** What Daily Discover was last made with (null until the first flip: what the switch said then). */
    private var made: Boolean? = null
    private var timer: Job? = null

    /** The switch set to [value] (a tap): kept, and Daily Discover made again once the flips settle. */
    fun set(value: Boolean): Job = scope.launch {
        if (made == null) made = switch.current()
        switch.setEnabled(value)
        timer?.cancel()
        timer = scope.launch {
            delay(pauseMs)
            settle()
        }
    }

    private suspend fun settle() {
        val want = switch.current()
        if (want == made) return
        made = want
        try {
            if (hasTaste() && rebuild()) Log.i(TAG, "Use your record in mixes is ${if (want) "on" else "off"}: today's Daily Discover made again")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Daily Discover couldn't be made again after the switch: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "YourRecord"

        /** record-switch.ts `SWITCH_PAUSE_MS`. */
        const val PAUSE_MS = 1_500L
    }
}
