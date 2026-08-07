package com.stash.feature.settings.libraryhealth

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.LibraryHealthBucket
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.workers.QualityInfoBackfillWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the Library Health screen.
 *
 * Two responsibilities:
 *  1. Surface the current downloaded-library breakdown (format × kbps band)
 *     so the user can see what they have at a glance and measure format-141
 *     yield empirically when running the MAX-tier experiment.
 *  2. Run a one-time on-device backfill that ffprobes (via
 *     `MediaMetadataRetriever`) every track still sitting at the historical
 *     `file_format = "opus"` / `quality_kbps = 0` defaults, writing the
 *     real values back. After the backfill the breakdown reflects truth.
 */
@HiltViewModel
class LibraryHealthViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val trackDao: TrackDao,
    private val metadataExtractor: AudioDurationExtractor,
    private val fileOrganizer: com.stash.data.download.files.FileOrganizer,
    private val reconciliationUseCase: com.stash.core.data.library.LibraryReconciliationUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryHealthState())
    val state: StateFlow<LibraryHealthState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val buckets = withContext(Dispatchers.IO) {
                runCatching { trackDao.getLibraryHealthBuckets() }
                    .onFailure { Log.w(TAG, "getLibraryHealthBuckets failed", it) }
                    .getOrDefault(emptyList())
            }
            _state.update { it.copy(buckets = buckets) }
        }
    }

    /**
     * Walks every downloaded track that's still at default format/kbps,
     * reads the file's actual codec/bitrate, and writes them to the DB.
     * Idempotent — safe to re-run; rows already populated are skipped by
     * the SQL filter, not by per-row checks.
     */
    fun runBackfill() {
        if (_state.value.backfill is BackfillStatus.Running) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Two passes back-to-back. Pass 1 (format/bitrate) reads from
                // MMR; pass 2 (file size) reads from disk via File.length().
                // Both deduplicated at the DAO level — re-running is idempotent.
                val formatRows = runCatching { trackDao.getRowsNeedingFormatBackfill() }
                    .onFailure { Log.w(TAG, "getRowsNeedingFormatBackfill failed", it) }
                    .getOrDefault(emptyList())
                val sizeRows = runCatching { trackDao.getRowsNeedingSizeBackfill() }
                    .onFailure { Log.w(TAG, "getRowsNeedingSizeBackfill failed", it) }
                    .getOrDefault(emptyList())

                val total = formatRows.size + sizeRows.size
                if (total == 0) {
                    _state.update { it.copy(backfill = BackfillStatus.Done(processed = 0, total = 0)) }
                    return@withContext
                }

                _state.update { it.copy(backfill = BackfillStatus.Running(processed = 0, total = total)) }

                var processed = 0
                var written = 0

                // ── Pass 1: format + bitrate via MMR ────────────────────
                for (row in formatRows) {
                    val meta = metadataExtractor.extract(row.filePath)
                    // Mirror the v0.9.1 download-path fix: write the format
                    // whenever it's known, even when MMR couldn't compute a
                    // bitrate. FLAC is variable-bitrate and MMR routinely
                    // returns 0 for it, so the prior gate `bitrateKbps > 0`
                    // skipped every misclassified-FLAC row — the exact
                    // scenario this backfill exists to fix. Library Health's
                    // bucket UI already renders qualityKbps=0 as `—`.
                    if (meta != null && meta.format != "unknown") {
                        runCatching {
                            trackDao.setFormatAndQuality(
                                trackId = row.id,
                                fileFormat = meta.format,
                                qualityKbps = meta.bitrateKbps,
                            )
                            written++
                        }.onFailure { e ->
                            Log.w(TAG, "setFormatAndQuality failed for trackId=${row.id}", e)
                        }
                    }
                    processed++
                    if (processed % 25 == 0) {
                        _state.update {
                            it.copy(backfill = BackfillStatus.Running(processed = processed, total = total))
                        }
                    }
                }

                // ── Pass 2: file_size_bytes via File.length() ───────────
                // Many older download paths didn't populate this column.
                // Without a real size SUM(file_size_bytes) understates the
                // Home "Storage" stat — typically by ~70-80% when most of
                // the library is legacy rows. Reading directly from disk
                // is cheap (~ microseconds per file) and exact.
                for (row in sizeRows) {
                    val sizeBytes = runCatching { java.io.File(row.filePath).length() }
                        .getOrDefault(0L)
                    if (sizeBytes > 0) {
                        runCatching {
                            trackDao.setFileSize(trackId = row.id, sizeBytes = sizeBytes)
                            written++
                        }.onFailure { e ->
                            Log.w(TAG, "setFileSize failed for trackId=${row.id}", e)
                        }
                    }
                    processed++
                    if (processed % 25 == 0) {
                        _state.update {
                            it.copy(backfill = BackfillStatus.Running(processed = processed, total = total))
                        }
                    }
                }

                Log.i(TAG, "backfill complete: processed=$processed written=$written (format=${formatRows.size}, size=${sizeRows.size})")
                _state.update { it.copy(backfill = BackfillStatus.Done(processed = written, total = total)) }
            }
            refresh()
        }
    }

    /**
     * Reconciles the download queue against the library — sweeps orphaned
     * queue rows, resets exhausted/stale retries, and re-queues undownloaded
     * tracks with no active queue entry — then refreshes disk-truth size
     * stats. The same pass [com.stash.core.data.sync.workers.TrackDownloadWorker]
     * runs at the start of every sync, exposed here as a standalone action
     * so the user can rebuild queue/stat state without a full sync.
     *
     * ViewModel-scoped like [runBackfill] rather than WorkManager-backed:
     * this is DB housekeeping (no network, no per-file MMR reads), so it's
     * expected to finish in well under a minute even on a large library. If
     * that assumption turns out wrong in practice, promote this to a Worker
     * the way runQualityInfoBackfill already is.
     */
    fun runVerification() {
        if (_state.value.verification is LibraryVerificationStatus.Running) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    verification = LibraryVerificationStatus.Running(
                        step = 0,
                        total = com.stash.core.data.library.LibraryReconciliationUseCase.TOTAL_STEPS,
                    ),
                )
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    reconciliationUseCase.reconcile(
                        onProgress = { step, total ->
                            _state.update { it.copy(verification = LibraryVerificationStatus.Running(step, total)) }
                        },
                        checkFileExists = fileOrganizer::fileExists,
                    )
                }
                Log.i(TAG, "verification complete: swept=${result.orphansSwept} " +
                    "staleResumed=${result.staleResumed} filesMissing=${result.filesMissing} " +
                    "requeued=${result.unqueuedRequeued}")
                _state.update { it.copy(verification = LibraryVerificationStatus.Done(result)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "runVerification failed", e)
                _state.update { it.copy(verification = LibraryVerificationStatus.Failed(e.message ?: "Unknown error")) }
            }
        }
    }

    /**
     * Enqueues [QualityInfoBackfillWorker] without WorkManager constraints
     * — the user explicitly opted in by tapping the row, so we don't gate
     * on battery state. The worker self-re-enqueues if the library has
     * more than 500 lossless rows missing quality info.
     */
    fun runQualityInfoBackfill() {
        WorkManager.getInstance(appContext).enqueue(
            OneTimeWorkRequestBuilder<QualityInfoBackfillWorker>().build()
        )
    }

    companion object {
        private const val TAG = "LibraryHealthVM"
    }
}

/**
 * UI state for the Library Health screen. [buckets] is the histogram
 * served by the DAO (already grouped by format + kbps band, sorted by
 * count desc). [backfill] tracks the one-time fixup pass for legacy rows.
 */
data class LibraryHealthState(
    val buckets: List<LibraryHealthBucket> = emptyList(),
    val backfill: BackfillStatus = BackfillStatus.Idle,
    val verification: LibraryVerificationStatus = LibraryVerificationStatus.Idle,
)

/**
 * Lifecycle of the metadata-backfill action. [Running.processed] /
 * [Running.total] drive the progress indicator; [Done.processed] is the
 * count of rows that actually got new values written (some files are
 * missing on disk and are skipped without erroring).
 */
sealed interface BackfillStatus {
    data object Idle : BackfillStatus
    data class Running(val processed: Int, val total: Int) : BackfillStatus
    data class Done(val processed: Int, val total: Int) : BackfillStatus
}

/**
 * Lifecycle of the standalone library-reconciliation action. Mirrors
 * [BackfillStatus]'s shape; [Running] uses step/total (housekeeping
 * steps) rather than processed/total (rows) since reconciliation has no
 * natural per-row unit to report.
 */
sealed interface LibraryVerificationStatus {
    data object Idle : LibraryVerificationStatus
    data class Running(val step: Int, val total: Int) : LibraryVerificationStatus
    data class Done(val result: com.stash.core.data.library.ReconciliationResult) : LibraryVerificationStatus
    data class Failed(val message: String) : LibraryVerificationStatus
}
