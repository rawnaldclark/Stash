package com.stash.core.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.stash.core.auth.TokenManager
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.workers.DiscoveryDownloadWorker
import com.stash.core.data.sync.workers.TrackDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Who asked for the queue to run. */
enum class ResumeTrigger {
    /** Something on its own: a cold start, the app coming back, a sync that left songs, a retry later. */
    AUTO,

    /** The user: Resume, Download now. Clears the pause and the out-of-space hold, and runs on any network. */
    USER,

    /**
     * Retry all / Retry group: the user's tap, so it clears the pause and the out-of-space hold and
     * takes over a waiting run, but it downloads under the automatic rules (the Sync tab's Wi-Fi
     * only, a battery that isn't low): the run takes the whole queue, not just the retried songs,
     * and that must not go over mobile data the user said not to use.
     */
    RETRY,
}

/** What the download queue is doing, for the Downloads screen, the Sync tab and the tests. */
sealed interface QueueRunState {
    /** A download runs now. */
    data object Running : QueueRunState

    /** A run waits for Wi-Fi (the Sync tab's Wi-Fi only) and the phone isn't on Wi-Fi. */
    data object WaitingForWifi : QueueRunState

    /** A run waits for any connection and the phone has none. */
    data object WaitingForConnection : QueueRunState

    /** A run is scheduled for [atMs]: a retry after a failure, or YouTube asked us to wait. */
    data class RetryAt(val atMs: Long) : QueueRunState

    /** A run is about to start (or waits behind a sync's other steps, or for the battery). */
    data object Scheduled : QueueRunState

    /** The user paused downloads. */
    data object Paused : QueueRunState

    /** A download ran out of space; nothing runs until Resume. */
    data object StorageFull : QueueRunState

    /** Nothing runs and nothing is scheduled. */
    data object Idle : QueueRunState
}

/** The phone's connection, as a download's network constraint sees it. */
enum class NetworkKind { NONE, METERED, UNMETERED }

/**
 * The one place that decides whether the download queue runs, outside a sync (plan 2026-11-08,
 * item A1). Before, songs a sync queued downloaded only inside a sync: once its download step
 * stopped (Cancel, a network blip, a full phone) they sat in "Up next" until the next full sync,
 * which could be a day away, or never.
 *
 * [resume] starts the resume run ([TrackDownloadWorker] in its resume mode, unique work
 * [TrackDownloadWorker.RESUME_WORK_NAME]) for the sync's rows, and the drain outside a sync
 * ([MusicRepository.resumeWaitingDownloads]) for the rows the user asked for, so one call covers
 * both. [pause] stops both and the sync's own download step, and keeps them stopped.
 *
 * Constraints follow the sync's own download step: an automatic start waits for Wi-Fi when the
 * Sync tab's Wi-Fi only is on, and for a battery that isn't low; the user's start runs on any
 * network. The policy copies [MusicRepository]'s drain start: never cancel a song in progress,
 * never stack a second waiting run.
 */
@Singleton
class DownloadQueueResumer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadQueueDao: DownloadQueueDao,
    private val queueState: DownloadQueueState,
    private val streamingPreference: StreamingPreference,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val tokenManager: TokenManager,
    private val musicRepository: MusicRepository,
    private val syncScheduler: SyncScheduler,
    private val syncNotificationManager: SyncNotificationManager,
    private val singleTrackDownloadEnqueuer: SingleTrackDownloadEnqueuer,
) {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    private val lastForegroundResume = AtomicLong(0L)

    /**
     * Runs whatever is waiting. [ResumeTrigger.AUTO] does nothing while paused or out of space;
     * [ResumeTrigger.USER] clears both first. Online mode never downloads a sync's rows (the
     * sync's own step refuses them there too), but the drain of rows the user asked for runs in
     * either mode, as it always has. [initialDelay] holds the resume run back (a sync that left
     * songs, YouTube asking us to wait); the drain starts only on an undelayed call.
     */
    suspend fun resume(trigger: ResumeTrigger, initialDelay: Duration = Duration.ZERO, rateLimitRound: Int = 0) {
        if (trigger != ResumeTrigger.AUTO) {
            clearHold()
        } else if (queueState.isPaused() || queueState.storageFull.value) {
            return
        }
        if (!streamingPreference.current() &&
            downloadQueueDao.hasResumableSyncDownload(connectedDownloadSources(tokenManager))
        ) {
            enqueueResumeRun(trigger, initialDelay, rateLimitRound)
        }
        if (initialDelay == Duration.ZERO) musicRepository.resumeWaitingDownloads(tap = trigger == ResumeTrigger.USER)
    }

    /**
     * Ends a Pause and an out-of-space hold without starting anything: "Sync now", whose own
     * download step takes the songs.
     */
    suspend fun clearHold() {
        queueState.setPaused(false)
        queueState.setStorageFull(false)
        syncNotificationManager.cancelDownloadsPaused()
    }

    /**
     * The app came to the foreground: [resume] on its own, at most once a minute. Covers phones
     * that drop scheduled jobs when the app is swiped away.
     */
    suspend fun onForeground(nowMs: Long = System.currentTimeMillis()) {
        val last = lastForegroundResume.get()
        if (nowMs - last < FOREGROUND_INTERVAL_MS || !lastForegroundResume.compareAndSet(last, nowMs)) return
        resume(ResumeTrigger.AUTO)
    }

    /**
     * Pause: stops the resume run, the drain and the sync's own download step, and keeps them
     * stopped (across a cold start too) until Resume, "Sync now" or a new download tap. The rows
     * stay queued; a song stopped half way goes back to waiting (its worker hands its claim back).
     */
    suspend fun pause() {
        queueState.setPaused(true)
        workManager.cancelUniqueWork(TrackDownloadWorker.RESUME_WORK_NAME)
        workManager.cancelUniqueWork(DiscoveryDownloadWorker.UNIQUE_WORK_NAME)
        // Single retries too (their backoff, a rate-limit round, a lossless re-queue): a song
        // stopped half way hands its claim back as it stops.
        workManager.cancelAllWorkByTag(SingleTrackDownloadEnqueuer.TAG)
        if (isSyncDownloadRunning()) syncScheduler.cancelSync()
        syncNotificationManager.cancelDownloadProgress()
    }

    /**
     * Retry all / Retry group, after the rows were claimed back to PENDING ([ids]): one resume
     * ([ResumeTrigger.RETRY]) instead of a job per song. A row neither run would take (a song from
     * a service that isn't connected any more) still gets its own retry, as before. In Online mode
     * a sync's row only goes back to the queue: it downloads once the user switches to Offline,
     * which is where the Downloads screen lists it.
     */
    suspend fun retryClaimed(ids: List<Long>) {
        if (ids.isEmpty()) return
        // Read who takes what before anything starts: a run that claims a row first would hide it here.
        val takenByResume = downloadQueueDao.getAllPendingBySources(connectedDownloadSources(tokenManager)).mapTo(HashSet()) { it.id }
        val takenByDrain = downloadQueueDao.pendingDiscoveryDownloads().mapTo(HashSet()) { it.id }
        resume(ResumeTrigger.RETRY)
        ids.filterNot { it in takenByResume || it in takenByDrain }.forEach { singleTrackDownloadEnqueuer.enqueue(it) }
    }

    /** Whether the sync chain's own download step is running: it takes the same rows as the resume run. */
    suspend fun isSyncDownloadRunning(): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.UNIQUE_WORK_NAME).first()
            .any { SyncScheduler.DOWNLOAD_STEP_TAG in it.tags && it.state == WorkInfo.State.RUNNING }

    /**
     * Enqueues the resume run under the drain's rules ([MusicRepository]'s startDiscoveryDrain):
     * - the user's start takes over when nothing runs (REPLACE: a waiting run's Wi-Fi rule or
     *   delay goes, nothing is cancelled), queues behind a running one, or leaves a run already
     *   waiting there, which reads the queue when it starts;
     * - an automatic start never cancels anything: it starts a run or queues one behind the
     *   running one, and leaves a waiting one alone, unless that one waits for Wi-Fi this start
     *   no longer needs and nothing runs.
     * One caller at a time: a cold start and the app coming to the front can both read "nothing
     * waiting" at once.
     */
    private suspend fun enqueueResumeRun(trigger: ResumeTrigger, initialDelay: Duration, rateLimitRound: Int) = RESUME_LOCK.withLock {
        val runs = workManager.getWorkInfosForUniqueWorkFlow(TrackDownloadWorker.RESUME_WORK_NAME).first()
        val running = runs.any { it.state == WorkInfo.State.RUNNING }
        val waiting = runs.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val constraints = when (trigger) {
            ResumeTrigger.USER -> userConstraints()
            ResumeTrigger.RETRY, ResumeTrigger.AUTO -> autoConstraints()
        }
        val policy = resumePolicy(trigger, running, waiting.map { it.constraints.requiredNetworkType }, constraints.requiredNetworkType)
            ?: return@withLock
        Log.i(TAG, "resume run: trigger=$trigger policy=$policy delay=$initialDelay network=${constraints.requiredNetworkType} round=$rateLimitRound")
        TrackDownloadWorker.enqueueResume(context, constraints, policy, initialDelay, rateLimitRound).await()
    }

    /**
     * The user's own start (Resume, Download now): any connection, and no battery rule. With
     * battery-not-low, Resume on a low battery read as a button that did nothing (the run waited
     * while the screen said it was about to start).
     */
    private fun userConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * An automatic start's constraints: the Sync tab's Wi-Fi only, exactly as a scheduled sync's
     * download step, and a battery that isn't low. Read fresh each time: a waiting run keeps the
     * rule it was created with.
     */
    suspend fun autoConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (syncPreferencesManager.preferences.first().wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * What the queue is doing, live: the downloads running or waiting (the sync's download step,
     * the resume run, the drain, single retries), the pause, the out-of-space hold and the phone's
     * connection.
     */
    fun runState(): Flow<QueueRunState> {
        val wm = workManager
        val work = combine(
            wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.UNIQUE_WORK_NAME),
            wm.getWorkInfosForUniqueWorkFlow(TrackDownloadWorker.RESUME_WORK_NAME),
            wm.getWorkInfosForUniqueWorkFlow(DiscoveryDownloadWorker.UNIQUE_WORK_NAME),
            wm.getWorkInfosByTagFlow(SingleTrackDownloadEnqueuer.TAG),
        ) { chain, resume, drain, single ->
            chain.filter { SyncScheduler.DOWNLOAD_STEP_TAG in it.tags } + resume + drain + single
        }
        // The clock re-reads the state every few seconds: "Trying again at 14:20" moves on once
        // 14:20 has passed, instead of staying until something else changes.
        return combine(work, queueState.paused, queueState.storageFull, networkKind(), clock()) { infos, paused, full, network, now ->
            runStateOf(
                paused = paused,
                storageFull = full,
                running = infos.any { it.state == WorkInfo.State.RUNNING },
                waiting = infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                    .map { WaitingRun(it.constraints.requiredNetworkType, it.nextScheduleTimeMillis) },
                network = network,
                nowMs = now,
            )
        }.distinctUntilChanged()
    }

    /** The time now, again every [CLOCK_TICK_MS] while collected. */
    private fun clock(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(CLOCK_TICK_MS)
        }
    }

    /** The default network, each time it changes; NONE while there is none. Registered while collected. */
    private fun networkKind(): Flow<NetworkKind> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun current(): NetworkKind {
            val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return NetworkKind.NONE
            return kindOf(caps)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(kindOf(caps))
            }

            override fun onLost(network: Network) {
                trySend(NetworkKind.NONE)
            }
        }
        // The connection as it is now, first: an assumed NONE until the callback answered flashed
        // "Waiting for a connection" on the screen's first frame.
        trySend(current())
        val registered = cm != null && runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun kindOf(caps: NetworkCapabilities): NetworkKind = when {
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkKind.NONE
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkKind.UNMETERED
        else -> NetworkKind.METERED
    }

    /** One waiting download run: the network it needs, and when it may start (Long.MAX_VALUE: unknown). */
    data class WaitingRun(val network: NetworkType, val nextRunAtMs: Long)

    companion object {
        private const val TAG = "DownloadQueueResumer"

        /** The app coming to the front resumes at most this often. */
        const val FOREGROUND_INTERVAL_MS = 60_000L

        /** A sync's download step that left songs: the resume run starts this long after. */
        val AFTER_SYNC_DELAY: Duration = 2.minutes

        /** YouTube asked us to wait (HTTP 429, its bot check): the next try, this long after. */
        val RATE_LIMIT_DELAY: Duration = 15.minutes

        /** How often [runState] re-reads the time. */
        const val CLOCK_TICK_MS = 15_000L

        private val RESUME_LOCK = Mutex()

        /**
         * The enqueue policy for a start by [trigger], or null to keep what is there. [waitingNetworks]
         * are the networks the runs waiting to start need; [network] is what this start needs.
         */
        fun resumePolicy(
            trigger: ResumeTrigger,
            running: Boolean,
            waitingNetworks: List<NetworkType>,
            network: NetworkType,
        ): ExistingWorkPolicy? = when (trigger) {
            ResumeTrigger.USER, ResumeTrigger.RETRY -> when {
                !running -> ExistingWorkPolicy.REPLACE
                waitingNetworks.isEmpty() -> ExistingWorkPolicy.APPEND_OR_REPLACE
                else -> null
            }
            ResumeTrigger.AUTO -> when {
                waitingNetworks.isEmpty() -> ExistingWorkPolicy.APPEND_OR_REPLACE
                !running && network == NetworkType.CONNECTED && NetworkType.UNMETERED in waitingNetworks ->
                    ExistingWorkPolicy.REPLACE
                else -> null
            }
        }

        /** The queue's state from what runs and waits; pure, for the tests. */
        fun runStateOf(
            paused: Boolean,
            storageFull: Boolean,
            running: Boolean,
            waiting: List<WaitingRun>,
            network: NetworkKind,
            nowMs: Long,
        ): QueueRunState {
            if (storageFull) return QueueRunState.StorageFull
            if (paused) return QueueRunState.Paused
            if (running) return QueueRunState.Running
            if (waiting.isEmpty()) return QueueRunState.Idle
            // A run that takes any network can start on mobile data; only Wi-Fi-only runs wait for Wi-Fi.
            val onlyWifiRuns = waiting.all { it.network == NetworkType.UNMETERED }
            if (onlyWifiRuns && network != NetworkKind.UNMETERED) return QueueRunState.WaitingForWifi
            if (network == NetworkKind.NONE) return QueueRunState.WaitingForConnection
            val next = waiting.map { it.nextRunAtMs }.filter { it != Long.MAX_VALUE && it > nowMs }.minOrNull()
            return if (next != null) QueueRunState.RetryAt(next) else QueueRunState.Scheduled
        }
    }
}
