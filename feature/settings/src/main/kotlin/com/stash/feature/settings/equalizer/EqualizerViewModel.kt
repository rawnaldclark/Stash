// EqualizerViewModel.kt
package com.stash.feature.settings.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.NamedPreset
import com.stash.core.media.equalizer.PresetCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class EqUiState(
  val enabled: Boolean = false,
  val gainsDb: FloatArray = FloatArray(5),
  val preampDb: Float = 0f,
  val bassBoostDb: Float = 0f,
  val activePresetId: String = "flat",
  val allPresets: List<NamedPreset> = PresetCatalog.builtIn,
) {
  // FloatArray uses reference equality by default, so override with contentEquals.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EqUiState) return false
    return enabled == other.enabled &&
      gainsDb.contentEquals(other.gainsDb) &&
      preampDb == other.preampDb &&
      bassBoostDb == other.bassBoostDb &&
      activePresetId == other.activePresetId &&
      allPresets == other.allPresets
  }
  override fun hashCode(): Int {
    var r = enabled.hashCode()
    r = 31 * r + gainsDb.contentHashCode()
    r = 31 * r + preampDb.hashCode()
    r = 31 * r + bassBoostDb.hashCode()
    r = 31 * r + activePresetId.hashCode()
    r = 31 * r + allPresets.hashCode()
    return r
  }
}

@HiltViewModel
class EqualizerViewModel @Inject constructor(
  private val controller: EqController,
) : ViewModel() {
  val uiState: StateFlow<EqUiState> = controller.state.map { s ->
    EqUiState(
      enabled = s.enabled,
      gainsDb = s.gainsDb,
      preampDb = s.preampDb,
      bassBoostDb = s.bassBoostDb,
      activePresetId = s.presetId,
      allPresets = PresetCatalog.allFor(s.customPresets),
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, EqUiState())

  fun onToggle(enabled: Boolean) = controller.setEnabled(enabled)
  fun onBandChanged(band: Int, dB: Float) = controller.setBandGain(band, dB)
  fun onPreampChanged(dB: Float) = controller.setPreampDb(dB)
  fun onBassBoostChanged(dB: Float) = controller.setBassBoostDb(dB)
  fun onPresetSelected(id: String) = controller.setPreset(id)
  fun onSaveCurrentPreset(name: String) = controller.saveCurrentAsPreset(name)
  fun onDeletePreset(id: String) = controller.deleteCustomPreset(id)
}
