package com.stash.feature.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.prefs.StreamingQualityPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The streaming switches as they stand, for the Streaming quality sheet. */
internal data class StreamingQualityState(
    val lossless: Boolean,
    /** Lossless is on but nothing can serve it yet: Settings says so under the switch too. */
    val noLosslessSource: Boolean,
    val saveData: Boolean,
    val preferTidalAac: Boolean,
)

/**
 * Now Playing's Streaming quality sheet (#716): the streaming switches of Settings › Audio &
 * Quality, changed without leaving the song. It reads and writes the very preferences Settings
 * does, so the two always show the same thing; nothing here is a setting of its own.
 *
 * A change is heard from the next song: the song playing keeps its stream, since switching it
 * would restart it, and the next one is resolved again under the new setting
 * ([PlayerRepository.reresolveNextStream]).
 */
@HiltViewModel
class StreamingQualityViewModel @Inject constructor(
    private val losslessPrefs: LosslessSourcePreferences,
    private val qualityPrefs: StreamingQualityPreferences,
    losslessAvailability: LosslessAvailability,
    streamingPreference: StreamingPreference,
    private val playerRepository: PlayerRepository,
    private val userActionScope: UserActionScope,
) : ViewModel() {

    /**
     * Songs stream (Stream only), so the ⋮ menu offers the sheet. In Download mode nothing
     * streams and these switches change nothing. Eager, so the menu is right when it opens.
     */
    val streams: StateFlow<Boolean> = streamingPreference.enabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Null until read; eager, so the sheet opens on the real values, not a flash of defaults. */
    internal val state: StateFlow<StreamingQualityState?> = combine(
        losslessPrefs.enabled,
        losslessAvailability.qbdlxEnabled,
        qualityPrefs.saveData,
        qualityPrefs.preferTidalAac,
    ) { lossless, sourceSetUp, saveData, preferTidalAac ->
        StreamingQualityState(
            lossless = lossless,
            noLosslessSource = !sourceSetUp,
            saveData = saveData,
            preferTidalAac = preferTidalAac,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onLosslessChanged(on: Boolean) = change { losslessPrefs.setEnabled(on) }

    fun onSaveDataChanged(on: Boolean) = change { qualityPrefs.setSaveData(on) }

    fun onPreferTidalAacChanged(on: Boolean) = change { qualityPrefs.setPreferTidalAac(on) }

    /**
     * Writes the setting, then has the next song resolved again under it. In the app-wide user
     * action scope: closing the sheet, or Now Playing, right after a tap must not cancel either.
     */
    private fun change(write: suspend () -> Unit) {
        userActionScope.scope.launch {
            write()
            playerRepository.reresolveNextStream()
        }
    }
}
