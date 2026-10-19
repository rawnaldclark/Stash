package com.stash.feature.sync

import com.stash.core.model.DownloadWaitReason
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.DownloadManagementRow
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.sync.DownloadCancellationController
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DownloadManagementTab { QUEUE, HISTORY }

enum class DownloadManagementAction { CANCEL, RETRY, NONE }

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
)

data class DownloadManagementUiState(
    val isLoading: Boolean = true,
    val selectedTab: DownloadManagementTab = DownloadManagementTab.QUEUE,
    val active: List<DownloadManagementItem> = emptyList(),
    val history: List<DownloadManagementItem> = emptyList(),
    val message: String? = null,
) {
    val downloading: List<DownloadManagementItem>
        get() = active.filter { it.status == QueueStatus.IN_PROGRESS }
    val queued: List<DownloadManagementItem>
        get() = active.filter { it.status == QueueStatus.PENDING }
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
}

@HiltViewModel
class DownloadManagementViewModel @Inject constructor(
    private val downloadQueueDao: DownloadQueueDao,
    private val downloadManager: DownloadManager,
    private val downloadEnqueuer: SingleTrackDownloadEnqueuer,
    private val cancellationController: DownloadCancellationController,
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

    val uiState: StateFlow<DownloadManagementUiState> = combine(
        downloadQueueDao.observeActiveDownloadRows(),
        historyItems,
        selectedTab,
        progressByTrackId,
        message,
    ) { active, history, tab, progress, currentMessage ->
        DownloadManagementUiState(
            isLoading = false,
            selectedTab = tab,
            active = active.map { it.toItem(progress[it.trackId]) },
            history = history,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DownloadManagementUiState(),
    )

    init {
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
        message.value = if (cancelled) "Download cancelled" else "Download could not be cancelled"
    }

    fun retry(queueId: Long) = viewModelScope.launch {
        if (downloadQueueDao.atomicallyClaimForRetry(queueId) == 1) {
            downloadEnqueuer.enqueue(queueId)
            message.value = "Download queued"
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}

private fun DownloadManagementRow.toItem(progress: DownloadProgress?): DownloadManagementItem {
    val pipeline = progress?.status
    val waitReason = DownloadWaitReason.fromCode(errorMessage).takeIf { status == QueueStatus.WAITING_FOR_LOSSLESS }
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
        createdAt = createdAt,
        completedAt = completedAt,
        // A waiting row's error_message is its reason code, not an error.
        errorMessage = errorMessage.takeIf { waitReason == null },
        waitReason = waitReason,
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

/** What a waiting row says, by why it waits. */
internal fun waitLabel(reason: DownloadWaitReason?): String = when (reason) {
    DownloadWaitReason.AAC -> "Not available as AAC yet"
    DownloadWaitReason.RESET -> "Waiting for the daily reset"
    DownloadWaitReason.TIDAL_BUSY -> "Tidal is busy. Trying again shortly."
    DownloadWaitReason.FALLBACK -> "Waiting for Lossy fallback to be on"
    DownloadWaitReason.LOSSLESS, null -> "Waiting for a lossless source"
}
