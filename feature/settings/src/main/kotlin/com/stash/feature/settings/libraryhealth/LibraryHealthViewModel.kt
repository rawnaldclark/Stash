package com.stash.feature.settings.libraryhealth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.LibraryHealthBucket
import com.stash.core.data.db.dao.TrackDao
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val trackDao: TrackDao,
    private val metadataExtractor: AudioDurationExtractor,
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
                val rows = runCatching { trackDao.getRowsNeedingFormatBackfill() }
                    .onFailure { Log.w(TAG, "getRowsNeedingFormatBackfill failed", it) }
                    .getOrDefault(emptyList())

                if (rows.isEmpty()) {
                    _state.update { it.copy(backfill = BackfillStatus.Done(processed = 0, total = 0)) }
                    return@withContext
                }

                _state.update { it.copy(backfill = BackfillStatus.Running(processed = 0, total = rows.size)) }

                var processed = 0
                var written = 0
                for (row in rows) {
                    val meta = metadataExtractor.extract(row.filePath)
                    if (meta != null && meta.format != "unknown" && meta.bitrateKbps > 0) {
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
                            it.copy(backfill = BackfillStatus.Running(processed = processed, total = rows.size))
                        }
                    }
                }

                Log.i(TAG, "backfill complete: processed=$processed written=$written")
                _state.update { it.copy(backfill = BackfillStatus.Done(processed = written, total = rows.size)) }
            }
            refresh()
        }
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
