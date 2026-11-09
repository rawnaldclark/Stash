package com.stash.feature.sync

import com.stash.core.model.DownloadWaitReason
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.DownloadManagementRow
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.DownloadCancellationController
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.QueueRunState
import com.stash.core.data.sync.ResumeTrigger
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus as QueueStatus
import com.stash.data.download.DownloadManager
import com.stash.data.download.model.DownloadProgress
import com.stash.data.download.model.DownloadStatus as PipelineStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DownloadManagementTab { QUEUE, HISTORY }

enum class DownloadManagementAction { CANCEL, RETRY, NONE }

/** The button beside the Downloads screen's status line. */
enum class QueueButton {
    /** Downloads run, or are about to: stop them until Resume. */
    PAUSE,

    /** A run waits for Wi-Fi or a retry: start it now, on any network. */
    DOWNLOAD_NOW,

    /** Paused, out of space, or songs queued with nothing set to run them. */
    RESUME,
}

data class DownloadManagementItem(
    val queueId: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val playlistName: String?,
    val status: QueueStatus,
    val progress: Float?,
    val phaseLabel: String,
    val createdAt: Long,
    val completedAt: Long?,
    val errorMessage: String?,
    val action: DownloadManagementAction,
    /** Why a waiting row waits (kept on the row), or null: not waiting, or from before reasons were kept. */
    val waitReason: DownloadWaitReason? = null,
    @param:androidx.annotation.StringRes val phaseLabelRes: Int = 0,
    /** Queued by a sync: in Online mode it downloads only once the user switches to Offline. */
    val fromSync: Boolean = false,
)

data class DownloadManagementUiState(
    val isLoading: Boolean = true,
    val selectedTab: DownloadManagementTab = DownloadManagementTab.QUEUE,
    val active: List<DownloadManagementItem> = emptyList(),
    val history: List<DownloadManagementItem> = emptyList(),
    val message: String? = null,
    /** What the queue is doing ([DownloadQueueResumer.runState]). */
    val runState: QueueRunState = QueueRunState.Idle,
    /** Download mode is Online: a sync's songs wait for Offline. */
    val onlineMode: Boolean = false,
    /** Failed songs Retry all failed retries (History tab). */
    val retryableFailedCount: Int = 0,
) {
    val downloading: List<DownloadManagementItem>
        get() = active.filter { it.status == QueueStatus.IN_PROGRESS }
    val queued: List<DownloadManagementItem>
        get() = active.filter { it.status == QueueStatus.PENDING && !(onlineMode && it.fromSync) }

    /**
     * Online mode: the songs a sync queued. Nothing downloads them until the user switches to
     * Offline, so they get their own section instead of sitting in "Up next" as if they were next.
     */
    val offlineOnly: List<DownloadManagementItem>
        get() = if (onlineMode) active.filter { it.status == QueueStatus.PENDING && it.fromSync } else emptyList()

    /** The button beside the status line, or none. */
    val queueButton: QueueButton?
        get() = queueButtonFor(runState, downloading.size + queued.size)
    val waiting: List<DownloadManagementItem>
        get() = active.filter { it.status == QueueStatus.WAITING_FOR_LOSSLESS }

    /** The waiting section's heading: what they all wait for, or just "Waiting" when it differs. */
    val waitingLabel: String
        get() {
            // A row from before reasons were kept waits for lossless.
            val reasons = waiting.map { it.waitReason ?: DownloadWaitReason.LOSSLESS }.distinct()
            return if (reasons.size > 1) "Waiting" else when (reasons.singleOrNull()) {
                DownloadWaitReason.AAC -> "Not available as AAC yet"
                DownloadWaitReason.RESET -> "Waiting for the daily reset"
                DownloadWaitReason.TIDAL_BUSY -> "Waiting for Tidal"
                DownloadWaitReason.FALLBACK -> "Waiting for Lossy fallback"
                DownloadWaitReason.LOSSLESS, null -> "Waiting for lossless"
            }
        }

    @androidx.annotation.StringRes
    fun waitingLabelRes(): Int {
        val reasons = waiting.map { it.waitReason ?: DownloadWaitReason.LOSSLESS }.distinct()
        return if (reasons.size > 1) R.string.sync_downloads_waiting_generic else when (reasons.singleOrNull()) {
            DownloadWaitReason.AAC -> R.string.sync_downloads_waiting_aac
            DownloadWaitReason.RESET -> R.string.sync_downloads_waiting_reset
            DownloadWaitReason.TIDAL_BUSY -> R.string.sync_downloads_waiting_tidal
            DownloadWaitReason.FALLBACK -> R.string.sync_downloads_waiting_fallback
            DownloadWaitReason.LOSSLESS, null -> R.string.sync_downloads_waiting_lossless
        }
    }
}

/**
 * The button for [state] with [activeCount] songs downloading or queued:
 *
 * | State | Button |
 * |---|---|
 * | Running, or about to | Pause |
 * | Waiting for Wi-Fi, or for a retry | Download now (any network) |
 * | Waiting for a connection | none: nothing would help |
 * | Paused, with songs queued | Resume downloads |
 * | Out of space | Resume downloads |
 * | Songs queued, nothing set to run them | Resume downloads |
 */
internal fun queueButtonFor(state: QueueRunState, activeCount: Int): QueueButton? = when (state) {
    // Paused over an empty queue: Resume would start nothing.
    QueueRunState.Paused -> QueueButton.RESUME.takeIf { activeCount > 0 }
    QueueRunState.StorageFull -> QueueButton.RESUME
    QueueRunState.Running, QueueRunState.Scheduled -> QueueButton.PAUSE.takeIf { activeCount > 0 }
    QueueRunState.WaitingForWifi, is QueueRunState.RetryAt -> QueueButton.DOWNLOAD_NOW.takeIf { activeCount > 0 }
    QueueRunState.WaitingForConnection -> null
    QueueRunState.Idle -> QueueButton.RESUME.takeIf { activeCount > 0 }
}

/** What the screen shows beside the rows: the queue's state, the mode and the retry count. */
private data class QueueContext(
    val runState: QueueRunState,
    val onlineMode: Boolean,
    val retryableFailedCount: Int,
)

@HiltViewModel
class DownloadManagementViewModel @Inject constructor(
    private val downloadQueueDao: DownloadQueueDao,
    private val downloadManager: DownloadManager,
    private val downloadEnqueuer: SingleTrackDownloadEnqueuer,
    private val cancellationController: DownloadCancellationController,
    private val resumer: DownloadQueueResumer,
    streamingPreference: StreamingPreference,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context? = null,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(DownloadManagementTab.QUEUE)
    private val progressByTrackId = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    private val message = MutableStateFlow<String?>(null)

    // Mapped once per history emission, not once per progress tick. Progress
    // updates arrive several times a second per active download and history
    // never depends on them, so mapping 100 rows inside the combine below was
    // pure waste on every tick.
    private val historyItems = downloadQueueDao.observeDownloadHistory(100)
        .map { rows -> rows.map { it.toItem(null) } }

    // The status line reads WorkManager and the connection: a failure there never blanks the list.
    private val queueContext = combine(
        resumer.runState().catch { emit(QueueRunState.Idle) },
        streamingPreference.enabled.catch { emit(false) }.distinctUntilChanged(),
        downloadQueueDao.observeRetryableFailedCount().catch { emit(0) },
    ) { runState, online, retryable -> QueueContext(runState, online, retryable) }

    val uiState: StateFlow<DownloadManagementUiState> = combine(
        downloadQueueDao.observeActiveDownloadRows(),
        historyItems,
        combine(selectedTab, message) { tab, msg -> tab to msg },
        progressByTrackId,
        queueContext,
    ) { active, history, (tab, currentMessage), progress, context ->
        DownloadManagementUiState(
            isLoading = false,
            selectedTab = tab,
            active = active.map { it.toItem(progress[it.trackId]) },
            history = history,
            message = currentMessage,
            runState = context.runState,
            onlineMode = context.onlineMode,
            retryableFailedCount = context.retryableFailedCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DownloadManagementUiState(),
    )

    init {
        viewModelScope.launch { cancellationController.releaseDeadClaims() }
        viewModelScope.launch {
            downloadManager.progress.collect { update ->
                progressByTrackId.value = when (update.status) {
                    PipelineStatus.COMPLETED,
                    PipelineStatus.FAILED,
                    PipelineStatus.UNMATCHED,
                    PipelineStatus.DEFERRED,
                    -> progressByTrackId.value - update.trackId
                    else -> progressByTrackId.value + (update.trackId to update)
                }
            }
        }
    }

    fun selectTab(tab: DownloadManagementTab) {
        selectedTab.value = tab
    }

    fun cancel(queueId: Long) = viewModelScope.launch {
        val cancelled = cancellationController.cancel(queueId)
        message.value = context?.getString(if (cancelled) R.string.sync_downloads_msg_cancelled else R.string.sync_downloads_msg_cancel_failed)
            ?: (if (cancelled) "Download cancelled" else "Download could not be cancelled")
    }

    /** Cancels every waiting and running download. */
    fun clearQueue() = viewModelScope.launch {
        val cancelled = cancellationController.cancelAll()
        message.value = if (cancelled == 0) {
            context?.getString(R.string.sync_downloads_msg_nothing_to_cancel) ?: "Nothing to cancel"
        } else {
            context?.resources?.getQuantityString(R.plurals.sync_downloads_msg_cancelled_count, cancelled, cancelled)
                ?: "Cancelled $cancelled ${if (cancelled == 1) "download" else "downloads"}"
        }
    }

    /** Removes finished and cancelled downloads from History; failed ones stay, so they can be retried. */
    fun clearHistory() = viewModelScope.launch {
        downloadQueueDao.deleteCompleted()
        message.value = context?.getString(R.string.sync_downloads_msg_history_cleared) ?: "History cleared"
    }

    /** Pause: downloads stop and stay stopped until Resume (the songs stay queued). */
    fun pause() = viewModelScope.launch {
        resumer.pause()
    }

    /** Resume downloads / Download now: the queue runs now, on any network, out of a pause. */
    fun resumeDownloads() = viewModelScope.launch {
        resumer.resume(ResumeTrigger.USER)
    }

    /** Retry all failed: every failed song back to the queue, run once rather than a job per song. */
    fun retryAllFailed() = viewModelScope.launch {
        resumer.retryClaimed(downloadQueueDao.atomicallyClaimAllForRetry())
    }

    fun retry(queueId: Long) = viewModelScope.launch {
        if (downloadQueueDao.atomicallyClaimForRetry(queueId) == 1) {
            // A tap ends a Pause, or the retry would wait for Resume without saying so.
            resumer.clearHold()
            downloadEnqueuer.enqueue(queueId)
            message.value = context?.getString(R.string.sync_downloads_msg_queued) ?: "Download queued"
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}

private fun DownloadManagementRow.toItem(progress: DownloadProgress?): DownloadManagementItem {
    val pipeline = progress?.status
    val waitReason = DownloadWaitReason.fromCode(errorMessage).takeIf { status == QueueStatus.WAITING_FOR_LOSSLESS }
    val phaseLabelRes = when {
        status == QueueStatus.SKIPPED -> R.string.sync_downloads_phase_cancelled
        pipeline == PipelineStatus.MATCHING -> R.string.sync_downloads_phase_matching
        pipeline == PipelineStatus.DOWNLOADING -> R.string.sync_downloads_phase_downloading
        pipeline == PipelineStatus.PROCESSING -> R.string.sync_downloads_phase_processing
        pipeline == PipelineStatus.TAGGING -> R.string.sync_downloads_phase_tagging
        status == QueueStatus.IN_PROGRESS -> R.string.sync_downloads_phase_in_progress
        status == QueueStatus.PENDING -> R.string.sync_downloads_phase_queued
        status == QueueStatus.WAITING_FOR_LOSSLESS -> waitLabelRes(waitReason)
        status == QueueStatus.COMPLETED -> R.string.sync_downloads_phase_downloaded
        status == QueueStatus.FAILED && failureType == DownloadFailureType.NO_MATCH -> R.string.sync_downloads_phase_needs_review
        status == QueueStatus.FAILED -> R.string.sync_downloads_phase_failed
        else -> 0
    }
    return DownloadManagementItem(
        queueId = queueId,
        trackId = trackId,
        title = title,
        artist = artist,
        albumArtUrl = albumArtUrl,
        playlistName = album.takeIf(String::isNotBlank),
        status = status,
        progress = progress?.progress?.coerceIn(0f, 1f),
        phaseLabel = when {
            status == QueueStatus.SKIPPED -> "Cancelled"
            pipeline == PipelineStatus.MATCHING -> "Finding a match"
            pipeline == PipelineStatus.DOWNLOADING -> "Downloading"
            pipeline == PipelineStatus.PROCESSING -> "Processing"
            pipeline == PipelineStatus.TAGGING -> "Adding metadata"
            status == QueueStatus.IN_PROGRESS -> "In progress"
            status == QueueStatus.PENDING -> "Queued"
            status == QueueStatus.WAITING_FOR_LOSSLESS -> waitLabel(waitReason)
            status == QueueStatus.COMPLETED -> "Downloaded"
            status == QueueStatus.FAILED && failureType == DownloadFailureType.NO_MATCH -> "Needs review"
            status == QueueStatus.FAILED -> "Failed"
            else -> status.name.lowercase().replaceFirstChar(Char::titlecase)
        },
        phaseLabelRes = phaseLabelRes,
        createdAt = createdAt,
        completedAt = completedAt,
        // A waiting row's error_message is its reason code, not an error.
        errorMessage = errorMessage.takeIf { waitReason == null },
        waitReason = waitReason,
        fromSync = syncId != null,
        action = when {
            // WAITING_FOR_LOSSLESS is an active queue row the user can be
            // parked on indefinitely (no lossless source has it yet) — it
            // needs a way out, and markCancelledIfActive already accepts it.
            status == QueueStatus.PENDING ||
                status == QueueStatus.IN_PROGRESS ||
                status == QueueStatus.WAITING_FOR_LOSSLESS -> DownloadManagementAction.CANCEL
            // Un-cancel. Without this a cancelled row is a dead end: nothing
            // re-enqueues it (getUnqueuedTrackIds skips SKIPPED on purpose),
            // so a mis-tap on Cancel was unrecoverable from this screen.
            status == QueueStatus.SKIPPED -> DownloadManagementAction.RETRY
            status == QueueStatus.FAILED && failureType != DownloadFailureType.NO_MATCH -> DownloadManagementAction.RETRY
            else -> DownloadManagementAction.NONE
        },
    )
}

@androidx.annotation.StringRes
internal fun waitLabelRes(reason: DownloadWaitReason?): Int = when (reason) {
    DownloadWaitReason.AAC -> R.string.sync_downloads_waiting_aac_item
    DownloadWaitReason.RESET -> R.string.sync_downloads_waiting_reset_item
    DownloadWaitReason.TIDAL_BUSY -> R.string.sync_downloads_waiting_tidal_item
    DownloadWaitReason.FALLBACK -> R.string.sync_downloads_waiting_fallback_item
    DownloadWaitReason.LOSSLESS, null -> R.string.sync_downloads_waiting_lossless_item
}

/** What a waiting row says, by why it waits. */
internal fun waitLabel(reason: DownloadWaitReason?): String = when (reason) {
    DownloadWaitReason.AAC -> "Not available as AAC yet"
    DownloadWaitReason.RESET -> "Waiting for the daily reset"
    DownloadWaitReason.TIDAL_BUSY -> "Tidal is busy. Trying again shortly."
    DownloadWaitReason.FALLBACK -> "Waiting for Lossy fallback to be on"
    DownloadWaitReason.LOSSLESS, null -> "Waiting for a lossless source"
}
