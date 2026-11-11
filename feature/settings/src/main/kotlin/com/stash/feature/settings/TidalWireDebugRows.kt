package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.data.download.lossless.tidal.TidalDebugSettings
import com.stash.data.download.lossless.tidal.TidalWires
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DEBUG BUILDS ONLY: the Tidal wire instruments of Settings › Lossless sources › Advanced (add-ons
 * plan phase 2 §5.4). All that is left under Advanced since the add-ons got their own page
 * (Settings › Add-ons, phase 4 §2.1); a release build shows nothing there.
 */
@HiltViewModel
class TidalWireDebugViewModel @Inject constructor(
    private val debug: TidalDebugSettings,
    private val wires: TidalWires,
) : ViewModel() {

    /** Debug builds only ([TidalDebugSettings.honored]). */
    val honored: Boolean get() = debug.honored
    val state: StateFlow<TidalDebugSettings.State> = debug.state

    private val _wireLine = MutableStateFlow("")

    /** The diagnostics line ("v1 add-on", "legacy relay", …), refreshed on demand. */
    val wireLine: StateFlow<String> = _wireLine.asStateFlow()

    fun setWire(wire: TidalDebugSettings.Wire) {
        viewModelScope.launch {
            debug.setWire(wire)
            refreshWireLine()
        }
    }

    /** Blank or invalid clears the override. */
    fun setOrigin(raw: String) {
        viewModelScope.launch {
            debug.setOrigin(raw.takeIf { it.isNotBlank() })
            refreshWireLine()
        }
    }

    fun refreshWireLine() {
        viewModelScope.launch { _wireLine.value = wires.describe() }
    }
}

/**
 * DEBUG BUILDS ONLY (plan §5.4): force the Tidal rung's wire, and stand an origin in for the
 * signed config's Tidal server. Never shown on a release build, which ignores both anyway.
 */
@Composable
internal fun TidalWireDebugRows(viewModel: TidalWireDebugViewModel = hiltViewModel()) {
    if (!viewModel.honored) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val line by viewModel.wireLine.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state) { viewModel.refreshWireLine() }
    var origin by rememberSaveable(state.origin) { mutableStateOf(state.origin.orEmpty()) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(text = "Tidal wire (debug build)", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Now: $line",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TidalDebugSettings.Wire.entries.forEach { w ->
                FilterChip(
                    selected = state.wire == w,
                    onClick = { viewModel.setWire(w) },
                    label = {
                        Text(
                            when (w) {
                                TidalDebugSettings.Wire.AUTO -> "Auto"
                                TidalDebugSettings.Wire.LEGACY -> "Legacy"
                                TidalDebugSettings.Wire.V1 -> "v1"
                            },
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            value = origin,
            onValueChange = { origin = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Owner server override (origin)") },
            placeholder = { Text("http://127.0.0.1:8797") },
            supportingText = { Text("Stands in for the signed config's Tidal server. Blank clears it.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                viewModel.setOrigin(origin)
                focusManager.clearFocus()
            }),
        )
    }
}
