package com.stash.data.download.lossless

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.tidal.TidalAacResolver
import com.stash.data.download.lossless.relay.LosslessRelayClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Eager-bound singleton that observes three signals and enqueues a
 * [LosslessRetryWorker] on each transition that could plausibly turn
 * a previously-null lossless resolution into a non-null one:
 *
 *  1. The user's captcha cookie value changes (fresh squid attempt
 *     possible).
 *  2. [QobuzSource.lastKnownBadCookie] clears or rotates (squid self-
 *     recovered, or user pasted a different value than the rejected one).
 *  3. [AggregatorRateLimiter.circuitResetEvents] emits any source id
 *     (kennyy outage cleared, or user manually reset the breaker).
 *  4. [LosslessRelayClient.pacedEvents]: the relay asked a download to wait
 *     (streams first). Enqueued with that wait as its initial delay; the
 *     worker then retries on WorkManager's backoff while the pool stays paced.
 *  5. [LosslessQuota]: the day's lossless allowance ran out. Downloads with
 *     the lossy fallback off wait for FLAC, so a sweep is booked for just after
 *     the reset, under its own unique name ([RESET_WORK_NAME]) so an earlier
 *     sweep already queued cannot swallow it. It re-queues without resolving
 *     ([LosslessRetryWorker.KEY_AT_RESET]). Every spent answer books again
 *     ([LosslessQuota.spentAnswers]): a sweep that ran before the relays' day
 *     turned over, whose downloads came back capped, gets another for the reset
 *     they report. The wait is on the relays' clock ([LosslessQuota.nowSec]).
 *
 * No periodic polling — these signals cover every state transition
 * that matters. Worker is enqueued under a unique work name with
 * [ExistingWorkPolicy.KEEP] so multiple triggers within a short window
 * collapse to a single sweep.
 *
 * Lives in `:data:download` rather than `:core:data` because
 * [LosslessRetryWorker] is module-local here and `:data:download` →
 * `:core:data` (not the other way). `StashApplication` calls [start]
 * once during `onCreate()`; the explicit-`start()` shape (rather than
 * an eager `init { }`) keeps unit tests free to install the test
 * dispatcher / scope before collectors are wired up.
 */
@Singleton
@OptIn(FlowPreview::class)
class LosslessRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    losslessPrefs: LosslessSourcePreferences,
    qobuzSource: QobuzSource,
    rateLimiter: AggregatorRateLimiter,
    private val relayClient: LosslessRelayClient,
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

    private val losslessPrefsRef = losslessPrefs
    private val qobuzSourceRef = qobuzSource
    private val rateLimiterRef = rateLimiter

    /**
     * Wire up the three reactive triggers on the current [scope].
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
        // 1. Cookie value changes. distinctUntilChanged + drop(1) drop the
        // initial DataStore replay so we don't enqueue at boot for no
        // reason. debounce coalesces a programmatic clear-then-set
        // sequence (rare in practice but cheap insurance) into one sweep.
        losslessPrefsRef.captchaCookieValue
            .distinctUntilChanged()
            .drop(1)
            .debounce(COOKIE_DEBOUNCE_MS)
            .onEach { enqueue() }
            .launchIn(scope)

        // 2. lastKnownBadCookie transitions. drop(1) skips the StateFlow
        // initial value; StateFlow already conflates equal consecutive
        // values internally (no distinctUntilChanged needed — the operator
        // is a deprecated no-op on StateFlow). Both null and non-null
        // transitions matter:
        //  - non-null → null: squid confirmed self-recovered.
        //  - non-null → different non-null: a previously-rejected cookie
        //    was replaced; the previously-deferred set may now resolve.
        qobuzSourceRef.lastKnownBadCookie
            .drop(1)
            .onEach { enqueue() }
            .launchIn(scope)

        // 3. Circuit-breaker reset events. Already a SharedFlow with no
        // replay, fires only on transition (manual reset OR timeout-driven
        // self-reset detected via the wasCircuitBroken latch).
        rateLimiterRef.circuitResetEvents
            .onEach { enqueue() }
            .launchIn(scope)

        // 4. The relay paced a download. KEEP: while a sweep is already waiting or
        // running, it covers this track too.
        relayClient.pacedEvents
            .onEach { waitSec -> enqueue(initialDelaySec = waitSec) }
            .launchIn(scope)

        // 5. The day's lossless allowance ran out: one sweep just after the reset. KEEP on its
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
                enqueue(initialDelaySec = waitSec, name = RESET_WORK_NAME, atReset = true)
            }
            .launchIn(scope)

        // 6. The Download quality changed: what was waiting waited under the old mode's rules
        // (no FLAC, or no AAC), so look again under the new one. Opus re-queues everything. Its
        // own name, so a sweep already waiting (paced, or booked for the reset) can't swallow it,
        // and REPLACE: a sweep booked for the previous mode is replaced by one for this mode.
        losslessPrefsRef.downloadMode
            .distinctUntilChanged()
            .drop(1)
            .onEach { enqueue(name = MODE_WORK_NAME, policy = ExistingWorkPolicy.REPLACE) }
            .launchIn(scope)

        // 7. Tidal was busy, paced or failing for a download that then waited (whatever the
        // fallback): one sweep for when it should be free again. Its own name, KEEP: a sweep
        // already booked for this covers every song that waited for it.
        tidalAacResolver.retryEvents
            .onEach { atMs ->
                val waitSec = ((atMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                enqueue(initialDelaySec = waitSec, name = TIDAL_WORK_NAME)
            }
            .launchIn(scope)
    }

    private fun enqueue(
        initialDelaySec: Long = 0,
        name: String = LosslessRetryWorker.UNIQUE_WORK_NAME,
        atReset: Boolean = false,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val request = OneTimeWorkRequestBuilder<LosslessRetryWorker>()
            .setInitialDelay(initialDelaySec, TimeUnit.SECONDS)
            .apply {
                if (atReset) {
                    setInputData(workDataOf(LosslessRetryWorker.KEY_AT_RESET to true))
                    // Still spent when it runs (the relays' day not yet over): look again soon.
                    setBackoffCriteria(BackoffPolicy.LINEAR, RESET_RETRY_BACKOFF_SEC, TimeUnit.SECONDS)
                }
            }
            .build()
        workManagerProvider().enqueueUniqueWork(name, policy, request)
    }

    internal companion object {
        /**
         * Debounce window for cookie pastes. Long enough to coalesce a
         * programmatic clear-then-set sequence (most DataStore writes
         * land within tens of ms of each other), short enough that the
         * user doesn't notice a delay between pasting and the sweep
         * firing.
         */
        const val COOKIE_DEBOUNCE_MS = 1_000L

        /** The sweep after a Download quality change (see trigger 6). */
        const val MODE_WORK_NAME = "lossless-retry-mode"

        /** The sweep for when Tidal should be free again (see trigger 7). */
        const val TIDAL_WORK_NAME = "lossless-retry-tidal"

        /** The reset sweep's unique work name (see trigger 5). */
        const val RESET_WORK_NAME = "lossless-retry-reset"

        /** Past the reset, so the relays' day has turned over on their clock too. */
        const val RESET_SLACK_SEC = 120L

        /** A reset sweep that found the day still spent retries after this (linear: 5, 10, 15 min, …). */
        const val RESET_RETRY_BACKOFF_SEC = 300L
    }
}
