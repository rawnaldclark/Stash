package com.stash.data.download.lossless

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.tidal.TidalAacResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Eager-bound singleton that observes these signals and enqueues a
 * [LosslessRetryWorker] on each transition that could plausibly turn
 * a previously-null lossless resolution into a non-null one:
 *
 *  1. [AggregatorRateLimiter.circuitResetEvents] emits any source id
 *     (a source's breaker cooled off on its own).
 *  2. [LosslessQuota]: the day's lossless allowance ran out. Downloads with
 *     the lossy fallback off wait for FLAC, so a sweep is booked for just after
 *     the reset, under its own unique name ([RESET_WORK_NAME]) so an earlier
 *     sweep already queued cannot swallow it. It re-queues without resolving
 *     ([LosslessRetryWorker.KEY_AT_RESET]). Every spent answer books again
 *     ([LosslessQuota.spentAnswers]): a sweep that ran before the relay's day
 *     turned over, whose downloads came back capped, gets another for the reset
 *     it reports. The wait is on the relay's clock ([LosslessQuota.nowSec]).
 *  3. The Download quality changed.
 *  4. Tidal was busy, paced or failing for a download that then waited: a Tidal sweep that only
 *     re-queues the downloads that waited for Tidal ([LosslessRetryWorker.KEY_TIDAL_BUSY_ONLY]).
 *
 * No periodic polling — these signals cover every state transition
 * that matters. Worker is enqueued under a unique work name with
 * [ExistingWorkPolicy.KEEP] so multiple triggers within a short window
 * collapse to a single sweep; the Tidal sweep keeps one too, but never
 * one that is running or about to start ([tidalPolicy]).
 *
 * Lives in `:data:download` rather than `:core:data` because
 * [LosslessRetryWorker] is module-local here and `:data:download` →
 * `:core:data` (not the other way). `StashApplication` calls [start]
 * once during `onCreate()`; the explicit-`start()` shape (rather than
 * an eager `init { }`) keeps unit tests free to install the test
 * dispatcher / scope before collectors are wired up.
 */
@Singleton
class LosslessRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    losslessPrefs: LosslessSourcePreferences,
    rateLimiter: AggregatorRateLimiter,
    private val quota: LosslessQuota,
    private val tidalAacResolver: TidalAacResolver,
) {

    /**
     * Test seam: unit tests overwrite this with the `runTest` scope
     * before letting [start] register the collectors, so virtual time
     * (`advanceUntilIdle` / `advanceTimeBy`) drains the debounce window
     * deterministically. Production paths leave the app-scoped supervisor.
     */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Test seam: unit tests stub this to return a MockK [WorkManager]
     * without going through `WorkManager.getInstance(context)` (which
     * routes through `WorkManagerImpl` and trips the "not initialised"
     * guard inside `every { }` recording blocks before the static stub
     * is installed). Production paths leave the default lambda — matches
     * the rest of the codebase's pattern (see
     * `StashApplication.maybeEnqueueQualityBackfill`,
     * `YtDlpUpdateWorker.schedulePeriodicUpdate`, etc.). No Hilt
     * `@Provides` exists for `WorkManager` and adding one solely for
     * this consumer would be more friction than it's worth.
     */
    internal var workManagerProvider: () -> WorkManager = {
        WorkManager.getInstance(context)
    }

    /** Wall clock for the Tidal sweep's booking (WorkManager's own clock is the system's too); tests drive it. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    private val losslessPrefsRef = losslessPrefs
    private val rateLimiterRef = rateLimiter

    /**
     * Wire up the reactive triggers on the current [scope].
     * `StashApplication` calls this once during `onCreate()`; tests
     * call it after overwriting the [scope] / [workManagerProvider]
     * seams so collectors run on the test scheduler.
     *
     * NOT idempotent — re-invoking registers a second set of collectors
     * on the same scope and would double-enqueue per signal. The single
     * call from `StashApplication.onCreate()` is the only one for
     * production builds; tests call it exactly once per test method.
     */
    fun start() {
        // 1. Circuit-breaker reset events. Already a SharedFlow with no
        // replay, fires only on transition (manual reset OR timeout-driven
        // self-reset detected via the wasCircuitBroken latch).
        rateLimiterRef.circuitResetEvents
            .onEach { enqueue() }
            .launchIn(scope)

        // 2. The day's lossless allowance ran out: one sweep just after the reset. KEEP on its
        // own name, so later answers the same day (each re-reports "spent") book nothing new
        // while that sweep waits; once it has run, the next spent answer books the next one.
        merge(
            quota.state
                .map { a -> a?.takeIf { it.exhausted }?.resetsAtSec }
                .distinctUntilChanged()
                .filterNotNull(),
            quota.spentAnswers,
        )
            .onEach { resetsAtSec ->
                val waitSec = (resetsAtSec - quota.nowSec()).coerceAtLeast(0) + RESET_SLACK_SEC
                enqueue(initialDelayMs = waitSec * 1000L, name = RESET_WORK_NAME, atReset = true)
            }
            .launchIn(scope)

        // 3. The Download quality changed: what was waiting waited under the old mode's rules
        // (no FLAC, or no AAC), so look again under the new one. Opus re-queues everything. Its
        // own name, so a sweep already waiting (paced, or booked for the reset) can't swallow it,
        // and REPLACE: a sweep booked for the previous mode is replaced by one for this mode.
        losslessPrefsRef.downloadMode
            .distinctUntilChanged()
            .drop(1)
            .onEach { enqueue(name = MODE_WORK_NAME, policy = ExistingWorkPolicy.REPLACE) }
            .launchIn(scope)

        // 4. Tidal was busy, paced or failing for a download that then waited (whatever the
        // fallback): a Tidal sweep for when it should be free again, which only re-queues the
        // downloads that waited for Tidal. Its own name; a sweep already booked covers every song
        // that waits before it starts, but one running or about to start does not ([tidalPolicy]).
        tidalAacResolver.retryEvents
            .onEach { atMs ->
                val now = nowMs()
                enqueue(
                    initialDelayMs = (atMs - now).coerceAtLeast(0),
                    name = TIDAL_WORK_NAME,
                    tidalBusyOnly = true,
                    policy = tidalPolicy(now),
                )
            }
            .launchIn(scope)
    }

    /**
     * How a Tidal sweep is booked, from WorkManager's own record of [TIDAL_WORK_NAME].
     *
     * KEEP while a booked sweep has not started and won't for more than [TIDAL_KEEP_MARGIN_MS]: it
     * re-queues every song waiting for Tidal when it starts, and the download that asked writes
     * its row back as waiting well before then (its time is at least the resolver's 15 s out).
     * A sweep BLOCKED behind a running one counts as ahead: it waits its own delay after that one.
     *
     * Otherwise APPEND_OR_REPLACE: a new sweep, after the current one if there is one. Under KEEP,
     * a sweep that was RUNNING (or about to start) swallowed the booking: it had already read the
     * waiting rows, so the song that asked was not among them, and nothing else brought it back.
     * Each booking still waits for the time the resolver asked (never under its 15 s floor), and
     * at most one sweep waits behind another, as the next booking sees it ahead and keeps it.
     */
    private fun tidalPolicy(now: Long): ExistingWorkPolicy {
        val sweeps = try {
            workManagerProvider().getWorkInfosForUniqueWork(TIDAL_WORK_NAME).get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable: booking behind whatever is there costs at most one extra sweep.
            Log.w(TAG, "Tidal sweep state unreadable (${e.javaClass.simpleName}); booking after it")
            return ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        val oneAhead = sweeps.any {
            it.state == WorkInfo.State.BLOCKED ||
                (it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis - now > TIDAL_KEEP_MARGIN_MS)
        }
        return if (oneAhead) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE
    }

    private fun enqueue(
        initialDelayMs: Long = 0,
        name: String = LosslessRetryWorker.UNIQUE_WORK_NAME,
        atReset: Boolean = false,
        tidalBusyOnly: Boolean = false,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val request = OneTimeWorkRequestBuilder<LosslessRetryWorker>()
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .apply {
                if (atReset) {
                    setInputData(workDataOf(LosslessRetryWorker.KEY_AT_RESET to true))
                    // Still spent when it runs (the relay's day not yet over): look again soon.
                    setBackoffCriteria(BackoffPolicy.LINEAR, RESET_RETRY_BACKOFF_SEC, TimeUnit.SECONDS)
                }
                if (tidalBusyOnly) setInputData(workDataOf(LosslessRetryWorker.KEY_TIDAL_BUSY_ONLY to true))
            }
            .build()
        workManagerProvider().enqueueUniqueWork(name, policy, request)
    }

    internal companion object {
        /** The sweep after a Download quality change (see trigger 3). */
        const val MODE_WORK_NAME = "lossless-retry-mode"

        /** The sweep for when Tidal should be free again (see trigger 4). */
        const val TIDAL_WORK_NAME = "lossless-retry-tidal"

        /** The reset sweep's unique work name (see trigger 2). */
        const val RESET_WORK_NAME = "lossless-retry-reset"

        /** Past the reset, so the relay's day has turned over on its clock too. */
        const val RESET_SLACK_SEC = 120L

        /** A reset sweep that found the day still spent retries after this (linear: 5, 10, 15 min, …). */
        const val RESET_RETRY_BACKOFF_SEC = 300L

        /**
         * A booked Tidal sweep due sooner than this may read the waiting rows before the download
         * that just asked has written its own back (a few ms after asking), so a new one is booked
         * behind it instead of keeping it ([tidalPolicy]). Well under the resolver's 15 s floor.
         */
        const val TIDAL_KEEP_MARGIN_MS = 5_000L

        private const val TAG = "LosslessRetryScheduler"
    }
}
