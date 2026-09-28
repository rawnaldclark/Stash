package com.stash.core.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stash.core.data.sync.workers.DailySyncTriggerWorker
import com.stash.core.data.sync.workers.DiffWorker
import com.stash.core.data.sync.workers.PlaylistFetchWorker
import com.stash.core.data.sync.workers.SyncFinalizeWorker
import com.stash.core.data.sync.workers.TrackDownloadWorker
import com.stash.core.model.SyncTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the WorkManager sync chain: Fetch -> Diff -> Download -> Finalize.
 *
 * All sync work is enqueued as a unique work chain under [UNIQUE_WORK_NAME] so
 * that only one sync can run at a time. The daily schedule is a separate periodic
 * trigger under [TRIGGER_WORK_NAME] ([DailySyncTriggerWorker]) that starts the
 * chain at the chosen time each day; manual syncs start it immediately with
 * relaxed constraints.
 *
 * Manual sync only requires a network connection (any type) so the user's
 * explicit intent is honored. Scheduled syncs may additionally require an
 * unmetered network and sufficient battery.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateManager: SyncStateManager,
) {

    companion object {
        /** Unique work name for the sync chain. Only one chain at a time. */
        const val UNIQUE_WORK_NAME = "stash_daily_sync"

        /** Unique work name for the periodic trigger that starts the daily sync. */
        const val TRIGGER_WORK_NAME = "stash_daily_sync_trigger"

        /** Tag on the fetch step of a chain the trigger started; "Sync now" chains lack it. */
        const val SCHEDULED_TAG = "sync_scheduled"
        private const val TAG = "SyncScheduler"
    }

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /**
     * Schedules a daily sync to run at the specified [hour] and [minute] on
     * days enabled in [days].
     *
     * Enqueues only the periodic trigger ([TRIGGER_WORK_NAME]): its first run is
     * the next [hour]:[minute] on an enabled day, then one every 24 h, and each
     * run starts the sync chain ([DailySyncTriggerWorker]). This used to enqueue
     * the chain itself as one-time work, which nothing re-armed after it ran, and
     * whose delay of up to a day made "Sync now" ignore every tap.
     *
     * [ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE] so a changed time takes
     * effect. If [days] is empty, cancels the trigger instead of enqueuing; a
     * scheduled sync already queued still runs, as editing the days can pass
     * through none.
     *
     * @param hour     Hour of day (0-23).
     * @param minute   Minute of hour (0-59).
     * @param wifiOnly Unused: the trigger reads Wi-Fi-only fresh on every run, so
     *                 a change applies without rescheduling. Kept so callers
     *                 compile unchanged.
     * @param days     Day-of-week bitmask. Defaults to every day for back-compat.
     */
    fun scheduleDailySync(
        hour: Int,
        minute: Int,
        wifiOnly: Boolean = true,
        days: DayOfWeekSet = DayOfWeekSet.EVERY_DAY,
    ) {
        val delayMs = computeDelayToNextSync(hour, minute, days)
        if (delayMs == null) {
            Log.d(TAG, "scheduleDailySync: no enabled days, cancelling the daily trigger")
            workManager.cancelUniqueWork(TRIGGER_WORK_NAME)
            return
        }
        enqueueTrigger(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE) {
            setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        Log.d(TAG, "Daily sync trigger scheduled (first run in ${delayMs}ms)")
    }

    /**
     * App start: gives Auto-sync its daily trigger if it has none. Until the
     * trigger existed, Auto-sync was a one-time delayed chain, so an upgrading
     * user has no trigger yet. [ExistingPeriodicWorkPolicy.KEEP] leaves an
     * existing trigger's time alone. The old delayed chain may still be queued:
     * it runs once, and the trigger skips a day's sync while it's queued or
     * running ([startScheduledSync]). With Auto-sync off (or no days), cancels
     * a leftover trigger, which would only wake the app to do nothing.
     */
    fun ensureDailySync(prefs: SyncPreferences) {
        val delayMs = if (prefs.autoSyncEnabled) {
            computeDelayToNextSync(prefs.syncHour, prefs.syncMinute, DayOfWeekSet(prefs.syncDays))
        } else {
            null
        }
        if (delayMs == null) {
            workManager.cancelUniqueWork(TRIGGER_WORK_NAME)
            return
        }
        enqueueTrigger(ExistingPeriodicWorkPolicy.KEEP) {
            setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        Log.d(TAG, "Daily sync trigger ensured (KEEP, first run in ${delayMs}ms if new)")
    }

    /**
     * Starts the scheduled sync now; [DailySyncTriggerWorker] calls this on a
     * sync day. Scheduled constraints: an unmetered network when [wifiOnly]
     * (else any), and battery not low.
     *
     * Leaves a running sync, a queued "Sync now" and the old schedule's chain
     * alone, so a manual sync is never turned into a Wi-Fi-only one. An earlier
     * scheduled chain still waiting (for its network, or between steps) is
     * replaced: it takes today's Wi-Fi rule instead of blocking every day's run.
     * [ExistingWorkPolicy.KEEP] drops this one if a sync was enqueued meanwhile.
     */
    suspend fun startScheduledSync(wifiOnly: Boolean) {
        val chain = chainInfos()
        val replace = chain.isWaitingScheduledSync()
        if (!replace && chain.any { !it.state.isFinished }) {
            Log.i(TAG, "Scheduled sync due, but a sync is already queued or running — skipping")
            return
        }
        if (replace) workManager.cancelUniqueWork(UNIQUE_WORK_NAME) // runs before the enqueue below
        Log.i(TAG, "Starting the scheduled sync (wifiOnly=$wifiOnly, replacing a waiting one: $replace)")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresBatteryNotLow(true)
            .build()
        enqueueChain(initialDelayMs = 0, constraints = constraints, trigger = SyncTrigger.SCHEDULED)
    }

    /**
     * The Wi-Fi-only switch changed: a scheduled sync still waiting takes the new
     * rule now, instead of waiting on the old one. Nothing else is touched.
     */
    suspend fun applyWifiOnlyToWaitingSync(wifiOnly: Boolean) {
        if (chainInfos().isWaitingScheduledSync()) startScheduledSync(wifiOnly)
    }

    /** A chain the trigger started with no step running: waiting for its network, or between steps. */
    private fun List<WorkInfo>.isWaitingScheduledSync(): Boolean =
        any { SCHEDULED_TAG in it.tags } &&
            any { !it.state.isFinished } &&
            none { it.state == WorkInfo.State.RUNNING }

    /**
     * Pins the trigger's next run to the next [hour]:[minute]; each trigger run
     * calls this. A periodic job otherwise counts 24 h from when its last run
     * ended, so every late start (Doze defers jobs) pushes the time later for
     * good, and a daylight-saving change shifts it an hour.
     *
     * [ExistingPeriodicWorkPolicy.UPDATE] leaves the running trigger running;
     * the override covers the next run only, hence every run sets it again.
     */
    fun anchorNextTrigger(hour: Int, minute: Int) {
        val delayMs = computeDelayToNextSync(hour, minute) ?: return
        enqueueTrigger(ExistingPeriodicWorkPolicy.UPDATE) {
            setNextScheduleTimeOverride(System.currentTimeMillis() + delayMs)
        }
    }

    /**
     * Triggers a sync immediately without any initial delay.
     *
     * Uses relaxed constraints (any network, no battery requirement) because
     * the user explicitly requested this sync. Replaces a sync chain with no
     * step running (not started, or paused between steps); ignored while a
     * step runs.
     */
    fun triggerManualSync() {
        // Guard against re-triggering a sync that's already running. Without
        // this, a second tap (e.g. an impatient user during a long fetch on
        // a large library) called enqueueChain -> REPLACE, which cancels the
        // in-flight PlaylistFetchWorker outright — every partially-completed
        // playlist/mix fetch throws JobCancellationException and the whole
        // chain restarts from zero. A user who taps repeatedly because sync
        // LOOKS stalled was actually the one preventing it from ever
        // finishing. isSyncInProgress() checks WorkManager's live state
        // directly rather than trusting caller-side flags, so this holds
        // even if the UI's own isSyncing guard is bypassed or stale.
        // Only a RUNNING step counts, though. A chain that hasn't started, or is
        // paused between steps (downloads waiting for Wi-Fi, or after a process
        // restart), has nothing in flight and shows no sync on the Sync tab: the
        // guard used to count those, and ignored every tap without a word.
        if (isSyncInProgress()) {
            Log.i(TAG, "Manual sync requested, but a sync is already running — ignoring")
            return
        }

        Log.i(TAG, "Manual sync triggered by user")
        // Immediately signal the UI that sync is starting so the button
        // shows progress feedback even before WorkManager picks up the work.
        syncStateManager.onAuthenticating()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        enqueueChain(initialDelayMs = 0, constraints = constraints, trigger = SyncTrigger.MANUAL)
    }

    /**
     * True while a chain step is RUNNING. A chain paused between steps doesn't
     * count: replacing it just fetches again, and the downloads it queued stay
     * queued. Blocks on a query of WorkManager's own database.
     */
    private fun isSyncInProgress(): Boolean =
        workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            .any { it.state == WorkInfo.State.RUNNING }

    /**
     * The sync chain's steps; empty when there's none. Suspends rather than
     * blocks: a blocking read deadlocks inside a worker when WorkManager runs
     * it on its own task thread (as its test driver does).
     */
    private suspend fun chainInfos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()

    /**
     * Cancels the sync chain, running or waiting: the Sync tab's Stop. The
     * daily schedule ([TRIGGER_WORK_NAME]) stays, so the next scheduled sync
     * still runs.
     */
    fun cancelSync() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        syncStateManager.reset()
    }

    /**
     * Turns the daily sync off: cancels the trigger, and a scheduled chain that
     * hasn't started (one the trigger queued, or one the old one-time schedule
     * left, the only chains with a delay). A sync that has started finishes,
     * and a queued "Sync now" still runs.
     */
    suspend fun cancelDailySync() {
        workManager.cancelUniqueWork(TRIGGER_WORK_NAME)
        val chain = chainInfos()
        val scheduled = chain.any { SCHEDULED_TAG in it.tags || it.initialDelayMillis > 0 }
        val unstarted = chain.all { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (scheduled && unstarted) workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * Computes the number of milliseconds until the next firing time at
     * [hour]:[minute] on a day enabled in [days].
     *
     * If the target time at hour:minute has already passed today (or today is
     * not enabled in [days]), advances day by day until a matching enabled day
     * is found, up to 7 hops.
     *
     * Returns null if [days] is empty (bitmask == 0) — caller should not enqueue.
     *
     * @param hour     Hour of day (0-23).
     * @param minute   Minute of hour (0-59).
     * @param days     Day-of-week bitmask. Defaults to every day for back-compat.
     * @param clock    Time source. Defaults to system clock; tests inject a fixed clock.
     */
    fun computeDelayToNextSync(
        hour: Int,
        minute: Int,
        days: DayOfWeekSet = DayOfWeekSet.EVERY_DAY,
        clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
    ): Long? {
        if (days.isEmpty) return null

        val now = java.time.ZonedDateTime.now(clock)
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        var hops = 0
        while (!days.contains(target.dayOfWeek) && hops < 7) {
            target = target.plusDays(1)
            hops++
        }
        if (hops >= 7) return null  // defensive — should be unreachable when bitmask non-empty

        return java.time.Duration.between(now, target).toMillis()
    }

    /**
     * Enqueues the daily trigger: periodic every 24 h, with no constraints of
     * its own (the chain it starts carries them), so it runs on time offline too.
     */
    private fun enqueueTrigger(
        policy: ExistingPeriodicWorkPolicy,
        configure: PeriodicWorkRequest.Builder.() -> Unit,
    ) {
        workManager.enqueueUniquePeriodicWork(
            TRIGGER_WORK_NAME,
            policy,
            PeriodicWorkRequestBuilder<DailySyncTriggerWorker>(24, TimeUnit.HOURS)
                .apply(configure)
                .build(),
        )
    }

    /**
     * Builds and enqueues the four-worker chain: Fetch -> Diff -> Download -> Finalize.
     *
     * @param initialDelayMs Delay before the first worker (PlaylistFetchWorker) starts.
     * @param constraints    WorkManager constraints applied to the network-heavy workers
     *                       (Fetch, Diff, Download). The Finalize worker uses no
     *                       constraints since it only writes local state.
     * @param trigger        What started the sync; the fetch step records it in the
     *                       sync history. A scheduled sync enqueues with
     *                       [ExistingWorkPolicy.KEEP], so it never cancels a sync
     *                       already queued or running; a manual one with REPLACE.
     */
    private fun enqueueChain(
        initialDelayMs: Long,
        constraints: Constraints,
        trigger: SyncTrigger = SyncTrigger.MANUAL,
    ) {
        val fetchWork = OneTimeWorkRequestBuilder<PlaylistFetchWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(PlaylistFetchWorker.KEY_TRIGGER to trigger.name))
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS,
            )
            .apply {
                if (initialDelayMs > 0) {
                    setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                }
                // One tag marks the chain: WorkManager keeps the fetch step's
                // record while any later step is unfinished.
                if (trigger == SyncTrigger.SCHEDULED) addTag(SCHEDULED_TAG)
            }
            .addTag("sync_fetch")
            .build()

        val diffWork = OneTimeWorkRequestBuilder<DiffWorker>()
            .setConstraints(constraints)
            // WorkManager hands a chained worker its own input merged with the
            // fetch step's output, so the diff step learns a "Sync now" here.
            .setInputData(workDataOf(DiffWorker.KEY_MANUAL_SYNC to (trigger == SyncTrigger.MANUAL)))
            .addTag("sync_diff")
            .build()

        val downloadWork = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .setConstraints(constraints)
            .addTag("sync_download")
            .build()

        val finalizeWork = OneTimeWorkRequestBuilder<SyncFinalizeWorker>()
            .addTag("sync_finalize")
            .build()

        val policy =
            if (trigger == SyncTrigger.SCHEDULED) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        workManager
            .beginUniqueWork(UNIQUE_WORK_NAME, policy, fetchWork)
            .then(diffWork)
            .then(downloadWork)
            .then(finalizeWork)
            .enqueue()

        Log.d(TAG, "Sync chain enqueued (delay=${initialDelayMs}ms)")
    }
}
