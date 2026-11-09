package com.stash.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.blocklist.BlockSource
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.FailedDownloadRow
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadFailureType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One UI group on the Failed Downloads screen: a [DownloadFailureType] plus
 * every row currently classified under it. Groups with no rows are omitted
 * upstream (see [FailedDownloadsViewModel.uiState]).
 */
data class FailedDownloadsGroup(
    val type: DownloadFailureType,
    val rows: List<FailedDownloadRow>,
)

/**
 * State for the Failed Downloads viewer.
 *
 * @property isLoading True until the first emission of [DownloadQueueDao.getFailedDownloads]
 *                     has been mapped — the initial [stateIn] seed.
 * @property groups    Failed rows pre-grouped by [DownloadFailureType], ordered by
 *                     [FailureReasonDisplayOrder]. Empty groups are filtered out.
 */
data class FailedDownloadsUiState(
    val isLoading: Boolean = true,
    val groups: List<FailedDownloadsGroup> = emptyList(),
) {
    /** Total failed-download row count across every visible group. */
    val totalCount: Int get() = groups.sumOf { it.rows.size }
}

/**
 * ViewModel for the Failed Downloads viewer.
 *
 * Subscribes to [DownloadQueueDao.getFailedDownloads] and groups the rows by
 * [DownloadFailureType] in [FailureReasonDisplayOrder] for the UI. All
 * retry actions route through the DAO's atomic claim helpers (Task 6) so
 * concurrent retries can never enqueue the same row twice: a row is only
 * handed to [SingleTrackDownloadEnqueuer] if its `UPDATE … WHERE
 * status = 'FAILED'` predicate caught it.
 *
 * - [retry]: single-row retry — claims one row, enqueues if the claim hit.
 * - [retryGroup]: retry every currently-FAILED row in one failure category.
 * - [retryAll]: retry every currently-FAILED row (non-match failures only).
 *   Both hand the claimed rows to [DownloadQueueResumer.retryClaimed]: one run
 *   for all of them, under the queue's rules, instead of a job per song.
 * - [block]: resolve the track and route it through [BlocklistGuard.block]
 *   tagged as [BlockSource.FAILED_DOWNLOADS].
 */
@HiltViewModel
class FailedDownloadsViewModel @Inject constructor(
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val downloadEnqueuer: SingleTrackDownloadEnqueuer,
    private val blocklistGuard: BlocklistGuard,
    private val resumer: DownloadQueueResumer,
) : ViewModel() {

    val uiState: StateFlow<FailedDownloadsUiState> =
        downloadQueueDao.getFailedDownloads()
            .map { rows ->
                FailedDownloadsUiState(
                    isLoading = false,
                    groups = FailureReasonDisplayOrder.mapNotNull { type ->
                        val groupRows = rows.filter { it.failureType == type }
                        if (groupRows.isEmpty()) null
                        else FailedDownloadsGroup(type, groupRows)
                    },
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FailedDownloadsUiState(),
            )

    /**
     * Retry a single failed row. Routes through the DAO's atomic claim helper:
     * we only call [SingleTrackDownloadEnqueuer.enqueue] if the row was still
     * `FAILED` at claim time. If something else (a group retry, a manual
     * resync) already moved it back to PENDING, this becomes a no-op.
     */
    fun retry(queueId: Long) = viewModelScope.launch {
        val claimed = downloadQueueDao.atomicallyClaimForRetry(queueId)
        // SQL UPDATE matches at most one row by PK; claim returns 1 (won) or 0 (lost race / already advanced).
        if (claimed == 1) downloadEnqueuer.enqueue(queueId)
    }

    /**
     * Retry every currently-FAILED row whose [DownloadFailureType] matches
     * [type]. The DAO claim helper returns just the row ids whose status
     * actually transitioned FAILED → PENDING — those are the ones we enqueue.
     */
    fun retryGroup(type: DownloadFailureType) = viewModelScope.launch {
        resumer.retryClaimed(downloadQueueDao.atomicallyClaimGroupForRetry(type))
    }

    /**
     * Retry every currently-FAILED row whose `failure_type` is a retryable
     * download failure (i.e. excludes `NONE` and `NO_MATCH`, which the DAO
     * helper handles).
     */
    fun retryAll() = viewModelScope.launch {
        resumer.retryClaimed(downloadQueueDao.atomicallyClaimAllForRetry())
    }

    /**
     * Block the track behind a failed row. Resolves the track via [TrackDao]
     * and tags the block with [BlockSource.FAILED_DOWNLOADS] so the audit
     * trail records where the action came from. If the track has already
     * been deleted (race with another screen), this is a no-op.
     */
    fun block(trackId: Long) = viewModelScope.launch {
        val track = trackDao.getById(trackId) ?: return@launch
        blocklistGuard.block(track, BlockSource.FAILED_DOWNLOADS)
    }
}
