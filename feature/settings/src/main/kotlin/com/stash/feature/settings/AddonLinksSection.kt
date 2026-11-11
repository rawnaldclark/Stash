package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.data.download.lossless.tidal.OwnTidalServer
import com.stash.data.download.lossless.addon.AddonLinks
import com.stash.data.download.lossless.tidal.TidalDebugSettings
import com.stash.data.download.lossless.tidal.TidalWires
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings › Lossless sources › Advanced, add-ons phase 2 (plan §5.4, §5.5): "Add from a link",
 * the old Custom Tidal server field read-only with Remove when one is set, and on debuggable
 * builds the Tidal wire instruments.
 */
@HiltViewModel
class AddonLinksViewModel @Inject constructor(
    private val links: AddonLinks,
    private val own: OwnTidalServer,
    private val debug: TidalDebugSettings,
    private val wires: TidalWires,
) : ViewModel() {

    /** Where the Add flow stands. */
    sealed interface Step {
        data object Idle : Step
        data object Checking : Step
        data class Ready(val preview: AddonLinks.Preview) : Step
        data class Refused(val message: String) : Step
        data object Added : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    val added: StateFlow<List<AddonLinks.Added>> =
        links.added.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The host of the user's own Tidal server, when one is set (never the full URL: it can carry a path token). */
    val ownServerHost: StateFlow<String?> = own.endpoint
        .map { url -> url?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/') }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Debug builds only ([TidalDebugSettings.honored]). */
    val debugHonored: Boolean get() = debug.honored
    val debugState: StateFlow<TidalDebugSettings.State> = debug.state

    private val _wireLine = MutableStateFlow("")
    /** The diagnostics line ("v1 add-on", "legacy relay", …), refreshed on demand. */
    val wireLine: StateFlow<String> = _wireLine.asStateFlow()

    fun check(link: String) {
        if (link.isBlank()) return
        _step.value = Step.Checking
        viewModelScope.launch {
            _step.value = when (val o = links.preview(link)) {
                is AddonLinks.Outcome.Ready -> Step.Ready(o.preview)
                is AddonLinks.Outcome.Refused -> Step.Refused(o.message)
                AddonLinks.Outcome.Added -> Step.Added
            }
        }
    }

    fun add(preview: AddonLinks.Preview, token: String?) {
        viewModelScope.launch {
            _step.value = when (val o = links.add(preview, token)) {
                is AddonLinks.Outcome.Refused -> Step.Refused(o.message)
                else -> Step.Added
            }
        }
    }

    fun reset() { _step.value = Step.Idle }

    fun remove(manifestUrl: String) {
        viewModelScope.launch { links.remove(manifestUrl) }
    }

    /** Removes the user's own Tidal server and its token: the rung goes back to Stash's. */
    fun removeOwnServer() {
        viewModelScope.launch { own.remove() }
    }

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

/** "Add from a link" and the user's add-ons. */
@Composable
internal fun AddonLinksSection(viewModel: AddonLinksViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val added by viewModel.added.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var link by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = stringResource(R.string.settings_addons_link_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.settings_addons_link_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it
                if (step !is AddonLinksViewModel.Step.Checking) viewModel.reset()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_addons_link_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                focusManager.clearFocus()
                viewModel.check(link)
            }),
        )
        when (val s = step) {
            AddonLinksViewModel.Step.Idle -> TextButton(onClick = { viewModel.check(link) }, enabled = link.isNotBlank()) {
                Text(stringResource(R.string.settings_addons_link_check))
            }
            AddonLinksViewModel.Step.Checking -> Text(
                text = stringResource(R.string.settings_addons_link_checking),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            is AddonLinksViewModel.Step.Refused -> Text(
                text = s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            AddonLinksViewModel.Step.Added -> Text(
                text = stringResource(R.string.settings_addons_link_added),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            is AddonLinksViewModel.Step.Ready -> AddonPreview(
                preview = s.preview,
                onAdd = { token ->
                    viewModel.add(s.preview, token)
                    link = ""
                },
                onCancel = viewModel::reset,
            )
        }
        added.forEach { a ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = a.name, style = MaterialTheme.typography.bodyMedium)
                    if (!a.loaded) {
                        Text(
                            text = stringResource(R.string.settings_addons_not_loaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { viewModel.remove(a.manifestUrl) }) { Text(stringResource(R.string.settings_addons_remove)) }
            }
        }
    }
}

@Composable
private fun AddonPreview(preview: AddonLinks.Preview, onAdd: (String?) -> Unit, onCancel: () -> Unit) {
    var token by remember(preview) { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "${preview.name} ${preview.version}", style = MaterialTheme.typography.bodyLarge)
        preview.description?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (preview.offers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_addons_offers, preview.offers.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_addons_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (preview.needsToken) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_access_token_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAdd(token.takeIf { it.isNotBlank() }) }) { Text(stringResource(R.string.settings_addons_add)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_addons_cancel)) }
        }
    }
}

/**
 * The old Custom Tidal server field (plan §5.5, release N): hidden when empty; when set, read-only
 * with Remove. Release N+1 removes it.
 */
@Composable
internal fun OwnTidalServerRow(viewModel: AddonLinksViewModel = hiltViewModel()) {
    val host by viewModel.ownServerHost.collectAsStateWithLifecycle()
    val h = host ?: return
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.settings_bitchord_server_label), style = MaterialTheme.typography.bodyMedium)
            Text(text = h, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = viewModel::removeOwnServer) { Text(stringResource(R.string.settings_addons_remove)) }
    }
}

/**
 * DEBUG BUILDS ONLY (plan §5.4): force the Tidal rung's wire, and stand an origin in for the
 * signed config's Tidal server. Never shown on a release build, which ignores both anyway.
 */
@Composable
internal fun TidalWireDebugRows(viewModel: AddonLinksViewModel = hiltViewModel()) {
    if (!viewModel.debugHonored) return
    val state by viewModel.debugState.collectAsStateWithLifecycle()
    val line by viewModel.wireLine.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(state) { viewModel.refreshWireLine() }
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
