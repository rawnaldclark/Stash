package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.download.lyrics.LyricsUpgradeTrigger
import com.stash.data.lyrics.LyricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Backs the "Lyrics" section on the Playback settings screen. Kept separate from [SettingsViewModel]. */
@HiltViewModel
class LyricsSettingsViewModel @Inject constructor(
    lyricsPreference: LyricsPreference,
    private val lyricsRepository: LyricsRepository,
    private val upgradeTrigger: LyricsUpgradeTrigger,
) : ViewModel() {

    val sourcePreference: StateFlow<LyricsSourcePreference> = lyricsPreference.sourcePreference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LyricsSourcePreference.APPLE_MUSIC)

    /** The "Re-check library for word-synced lyrics" row: idle / waiting for Wi-Fi / checking n of m. */
    val recheckState: StateFlow<LyricsRecheckUiState> = combine(
        upgradeTrigger.recheckStatus,
        lyricsPreference.recheckFinishedAt,
        lyricsPreference.recheckCursor,
    ) { status, finishedAt, cursor -> lyricsRecheckUiState(status, finishedAt, cursor) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LyricsRecheckUiState.Idle())

    /** After the user confirmed: one unique, paced background sweep (a no-op while one is going). */
    fun startRecheck() = upgradeTrigger.enqueueLibraryRecheck()

    /** Stops the sweep; it keeps its place, so the next start carries on. */
    fun cancelRecheck() = upgradeTrigger.cancelLibraryRecheck()

    /** Switching to LRC_ONLY wipes stored TTML and queues affected tracks for re-fetch — see [LyricsRepository.setSourcePreference]. */
    fun setSourcePreference(preference: LyricsSourcePreference) {
        // NonCancellable: the LRC-only cleanup (sidecar deletes, TTML wipe, then the pref write) must
        // finish even if the user leaves Settings and viewModelScope is cancelled mid-way.
        viewModelScope.launch {
            withContext(NonCancellable) { lyricsRepository.setSourcePreference(preference) }
        }
    }
}