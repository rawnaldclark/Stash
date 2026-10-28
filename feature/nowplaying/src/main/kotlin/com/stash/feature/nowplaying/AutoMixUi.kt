package com.stash.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.AutoMixMode
import com.stash.core.data.prefs.AutoMixPreference
import com.stash.core.media.automix.AutoMixStateBus
import com.stash.feature.nowplaying.ui.npInk
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class AutoMixUiState(
    val enabled: Boolean = false,
    val mode: AutoMixMode = AutoMixMode.BEAT_SYNC,
    val isMixing: Boolean = false,
    val outgoingBpm: Float? = null,
    val incomingBpm: Float? = null,
    val sharedBpm: Float? = null,
)

@HiltViewModel
class AutoMixViewModel @Inject constructor(
    private val preference: AutoMixPreference,
    stateBus: AutoMixStateBus,
) : ViewModel() {
    val state: StateFlow<AutoMixUiState> = combine(
        preference.enabled,
        preference.mode,
        stateBus.state,
    ) { enabled, mode, runtime ->
        AutoMixUiState(
            enabled = enabled,
            mode = mode,
            isMixing = runtime.isMixing,
            outgoingBpm = runtime.outgoingBpm,
            incomingBpm = runtime.incomingBpm,
            sharedBpm = runtime.sharedBpm,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AutoMixUiState())

    fun setEnabled(value: Boolean) { viewModelScope.launch { preference.setEnabled(value) } }
    fun setMode(value: AutoMixMode) { viewModelScope.launch { preference.setMode(value) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoMixSheet(
    state: AutoMixUiState,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (AutoMixMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AutoMix", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        "DJ-style transitions between songs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(Modifier.height(18.dp))
            AutoMixModeRow(
                "Beat Sync",
                "Sync the songs first, keep them beat-locked for several bars, then ease the new song home.",
                state.mode == AutoMixMode.BEAT_SYNC,
                state.enabled,
            ) { onModeChange(AutoMixMode.BEAT_SYNC) }
            AutoMixModeRow(
                "Smooth",
                "A longer blend between the songs' audible boundaries, without changing tempo.",
                state.mode == AutoMixMode.SMOOTH,
                state.enabled,
            ) { onModeChange(AutoMixMode.SMOOTH) }
            Spacer(Modifier.height(12.dp))
            Text(
                "If a pair cannot be mixed confidently, Stash keeps your normal Crossfade or gapless setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutoMixModeRow(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AutoMixMixingLine(state: AutoMixUiState, modifier: Modifier = Modifier, color: Color = npInk()) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        val text = when {
            state.outgoingBpm != null && state.incomingBpm != null &&
                state.outgoingBpm.roundToInt() != state.incomingBpm.roundToInt() ->
                "Mixing · ${state.outgoingBpm.roundToInt()} → ${state.incomingBpm.roundToInt()} BPM"
            state.sharedBpm != null -> "Mixing · ${state.sharedBpm.roundToInt()} BPM"
            state.outgoingBpm != null -> "Mixing · ${state.outgoingBpm.roundToInt()} BPM"
            state.incomingBpm != null -> "Mixing · ${state.incomingBpm.roundToInt()} BPM"
            else -> "Mixing"
        }
        Text(text, color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
