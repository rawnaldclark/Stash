package com.stash.feature.settings.libraryhealth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.library.duplicates.DuplicateCleanupState
import com.stash.core.data.library.duplicates.DuplicateKind
import com.stash.core.data.library.duplicates.DuplicatePair
import com.stash.core.data.library.duplicates.PossibleDuplicates
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library Health → Possible duplicates: the pairs [PossibleDuplicates] offers, the row the user
 * picks to keep in each, Merge (with the one "Remove the extra copy?" when both rows hold a file)
 * and "Not a duplicate". Also feeds Library Health's own "Possible duplicates" card (count and the
 * one-time cleanup's summary).
 *
 * All database and file work runs on [io], off the main thread; the list is read again after each
 * change, so a pair that a merge made stale never lingers.
 */
@HiltViewModel
class PossibleDuplicatesViewModel @Inject constructor(
    private val duplicates: PossibleDuplicates,
    private val cleanupState: DuplicateCleanupState,
    private val musicRepository: MusicRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PossibleDuplicatesState())
    val state: StateFlow<PossibleDuplicatesState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            cleanupState.lastRun.collect { run -> _state.update { it.copy(lastRun = run) } }
        }
    }

    /** Reads the pairs again; the user's keeper choices survive for pairs still offered. */
    fun reload() {
        viewModelScope.launch {
            val pairs = withContext(io) {
                try {
                    duplicates.find()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't read possible duplicates", e)
                    null
                }
            }
            _state.update { s ->
                if (pairs == null) {
                    s.copy(loading = false)
                } else {
                    val chosen = s.pairs.associate { it.key to it.keepId }
                    s.copy(
                        loading = false,
                        pairs = pairs.map { p ->
                            val ui = PairUi(p)
                            chosen[ui.key]?.takeIf { id -> ui.mayKeep(id) }?.let { ui.copy(keepId = it) } ?: ui
                        },
                    )
                }
            }
        }
    }

    /**
     * Reads the pairs again unless the first read is still under way: Library Health calls it each
     * time it comes back into view, so its count follows merges made on the Possible duplicates screen.
     */
    fun refresh() {
        if (!_state.value.loading) reload()
    }

    /** The user picks [trackId] as the row to keep in pair [key]; ignored for a row that must go. */
    fun chooseKeeper(key: String, trackId: Long) {
        _state.update { s ->
            s.copy(pairs = s.pairs.map { if (it.key == key && it.mayKeep(trackId)) it.copy(keepId = trackId) else it })
        }
    }

    /** Merge pressed: asks the one confirmation first when a file would be removed, else merges. */
    fun merge(key: String) {
        val pair = _state.value.pairs.firstOrNull { it.key == key } ?: return
        if (pair.removesAFile) {
            _state.update { it.copy(confirming = pair) }
        } else {
            doMerge(pair)
        }
    }

    /** "Remove the extra copy?" answered yes. */
    fun confirmMerge() {
        val pair = _state.value.confirming ?: return
        _state.update { it.copy(confirming = null) }
        doMerge(pair)
    }

    fun cancelConfirm() = _state.update { it.copy(confirming = null) }

    /** "Not a duplicate": the pair is never offered again. */
    fun notADuplicate(key: String) {
        val pair = _state.value.pairs.firstOrNull { it.key == key } ?: return
        _state.update { s -> s.copy(pairs = s.pairs.filterNot { it.key == key }, message = DuplicatesMessage.DISMISSED) }
        viewModelScope.launch {
            withContext(io) {
                try {
                    duplicates.dismiss(pair.first.id, pair.second.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't remember the dismissal", e)
                }
            }
            reload()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun doMerge(pair: PairUi) {
        if (pair.key in _state.value.working) return
        _state.update { it.copy(working = it.working + pair.key) }
        viewModelScope.launch {
            val result = withContext(io) {
                try {
                    duplicates.merge(keeperId = pair.keepId, otherId = pair.goId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "merge failed", e)
                    PossibleDuplicates.MergeResult.Refused
                }
            }
            val message = when (result) {
                is PossibleDuplicates.MergeResult.Merged -> {
                    // The extra copy's file is gone: the player drops it like any deleted song.
                    if (result.removedFile) musicRepository.announceTrackGone(result.removedId)
                    if (result.removedFile) DuplicatesMessage.MERGED_FILE_REMOVED else DuplicatesMessage.MERGED
                }
                PossibleDuplicates.MergeResult.Refused -> DuplicatesMessage.NOT_MERGED
            }
            _state.update { it.copy(working = it.working - pair.key, message = message) }
            reload()
        }
    }

    companion object {
        private const val TAG = "PossibleDuplicatesVM"

        /** Where the database and file work runs; a test points it at its Main test dispatcher. */
        internal var io: CoroutineDispatcher = Dispatchers.IO
    }
}

/** What Possible duplicates shows. */
data class PossibleDuplicatesState(
    val loading: Boolean = true,
    val pairs: List<PairUi> = emptyList(),
    /** The one-time cleanup's last run, for the summary line; null before it ran. */
    val lastRun: DuplicateCleanupState.LastRun? = null,
    /** A pair waiting for "Remove the extra copy?". */
    val confirming: PairUi? = null,
    /** Pairs being merged now. */
    val working: Set<String> = emptySet(),
    val message: DuplicatesMessage? = null,
)

/** A one-off line for the screen's snackbar. */
enum class DuplicatesMessage { MERGED, MERGED_FILE_REMOVED, NOT_MERGED, DISMISSED }

/**
 * One offered pair as the screen shows it. [first] is the suggested keeper and [second] the other;
 * [keepId] is the row the user keeps (the suggestion until they pick the other).
 */
data class PairUi(
    val pair: DuplicatePair,
    val keepId: Long = pair.keeper.id,
) {
    val key: String = "${pair.keeper.id}-${pair.other.id}"
    val kind: DuplicateKind get() = pair.kind
    val first: Track = pair.keeper.toDomain()
    val second: Track = pair.other.toDomain()

    /** The row that goes if the user merges now. */
    val goId: Long get() = if (keepId == first.id) second.id else first.id

    /** Both rows hold a different file: merging deletes one, after the one confirmation. */
    val removesAFile: Boolean get() = pair.removesAFile

    /** Whether the user may keep [trackId]: when only one row holds a file, that row stays. */
    fun mayKeep(trackId: Long): Boolean = when (trackId) {
        pair.keeper.id -> pair.mayKeep(pair.keeper)
        pair.other.id -> pair.mayKeep(pair.other)
        else -> false
    }

    /** The row that goes, as the confirmation names it. */
    val going: Track get() = if (goId == first.id) first else second

    /** The row that stays. */
    val kept: Track get() = if (keepId == first.id) first else second
}
