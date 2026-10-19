package com.stash.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.DownloadMode
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.feature.settings.R
import com.stash.feature.settings.components.AudioQualityPicker
import com.stash.feature.settings.components.BetaPill
import com.stash.feature.settings.components.LosslessRoutingStatus
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsPickerRow
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsToggleRow

/**
 * The Audio & Quality spoke of the hub-and-spoke Settings redesign.
 *
 * This re-homes the original "Audio Quality" + "Lossless audio card" block
 * from the monolithic `SettingsScreen.kt`: the download-tier picker (shown
 * only when lossless is off), the lossless toggle, the routing status,
 * the lossless-quality picker, the YouTube-fallback
 * expander, and the Advanced (captcha cookie + reset) expander — plus the
 * Equalizer nav. This is a behavior-preserving relocation + restyle: every
 * control calls the SAME [SettingsViewModel] method the old screen used; no
 * logic is changed. The legacy `bringIntoViewRequester` (deep-link scroll
 * affordance) is dropped — it is not needed on a dedicated category screen.
 */
@Composable
fun SettingsAudioQualityScreen(
    onBack: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToSquidWtfCaptcha: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val qbdlxExpired by viewModel.qbdlxExpired.collectAsStateWithLifecycle()
    val qobuzConnectedEmail by viewModel.qobuzConnectedEmail.collectAsStateWithLifecycle()
    val qobuzHasLogin by viewModel.qobuzHasLogin.collectAsStateWithLifecycle()
    val losslessRouting by viewModel.losslessRouting.collectAsStateWithLifecycle()
    val qobuzConnecting by viewModel.qobuzConnecting.collectAsStateWithLifecycle()
    val qobuzConnectError by viewModel.qobuzConnectError.collectAsStateWithLifecycle()
    val customEndpoint by viewModel.customEndpoint.collectAsStateWithLifecycle()
    val customEndpointError by viewModel.customEndpointError.collectAsStateWithLifecycle()
    val customEndpointTest by viewModel.customEndpointTest.collectAsStateWithLifecycle()
    val bitchordEndpoint by viewModel.bitchordEndpoint.collectAsStateWithLifecycle()
    val bitchordEndpointError by viewModel.bitchordEndpointError.collectAsStateWithLifecycle()
    val bitchordHasToken by viewModel.bitchordHasToken.collectAsStateWithLifecycle()
    val tidalAacFallback by viewModel.tidalAacFallback.collectAsStateWithLifecycle()
    val flacViewModel: FlacUpgradeViewModel = hiltViewModel()
    val autoFlacUpgrade by flacViewModel.autoEnabled.collectAsStateWithLifecycle()
    val quotaViewModel: LosslessQuotaViewModel = hiltViewModel()
    val losslessToday by quotaViewModel.line.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.settings_audio_quality_title), onBack = onBack, modifier = modifier) {
        // Downloads: what every download saves (Lossless, AAC 320, Opus) and the switches
        // that mode uses. Streaming is the Lossless card's.
        DownloadQualityCard(
            mode = uiState.downloadMode,
            onModeChanged = viewModel::onDownloadModeChanged,
            flacTier = uiState.losslessQualityTier,
            onFlacTierChanged = viewModel::onLosslessQualityTierChanged,
            autoFlacUpgrade = autoFlacUpgrade,
            onAutoFlacUpgradeChanged = flacViewModel::setAutoEnabled,
            tidalAacFallback = tidalAacFallback,
            onTidalAacFallbackChanged = viewModel::onTidalAacFallbackChanged,
            lossyFallback = uiState.youtubeFallbackEnabled,
            onLossyFallbackChanged = viewModel::onYoutubeFallbackChanged,
            youTubeQuality = uiState.audioQuality,
            onYouTubeQualityChanged = viewModel::onQualityChanged,
        )

        // (a) Streaming with lossless off: Save Data means "the lowest JioSaavn and YouTube
        // audio quality", and the toggle must not vanish with the lossless card — that is
        // exactly the user it is for.
        if (!uiState.losslessEnabled) {
            SettingsSectionLabel(stringResource(R.string.settings_audio_quality_section))
            GlassCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_save_data_title),
                        subtitle = stringResource(R.string.settings_save_data_subtitle),
                        checked = uiState.streamingSaveData,
                        onCheckedChange = viewModel::onStreamingSaveDataChanged,
                        titleTrailing = { BetaPill() },
                    )
                }
            }
        }

        // (b) Lossless card.
        SettingsSectionLabel(stringResource(R.string.settings_lossless_section))
        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_lossless_title),
                    // Three-way, because keying "FLAC routing active" on the toggle
                    // alone asserted it for every user with nothing configured — the
                    // day-one state — twenty lines above three "not connected" rows.
                    subtitle = when {
                        !uiState.losslessEnabled ->
                            stringResource(R.string.settings_lossless_subtitle_off)
                        qbdlxExpired ->
                            stringResource(R.string.settings_lossless_subtitle_no_source)
                        else -> stringResource(R.string.settings_lossless_subtitle_active)
                    },
                    checked = uiState.losslessEnabled,
                    onCheckedChange = viewModel::onLosslessEnabledChanged,
                )

                AnimatedVisibility(
                    // AAC downloads use the daily allowance too, so its line shows for them as well.
                    visible = uiState.losslessEnabled || uiState.downloadMode != DownloadMode.OPUS,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // ROUTING block — one row per configured lossless path,
                        // built in LosslessAvailability so this list and the
                        // resolver read the same predicates.
                        LosslessRoutingStatus(rows = losslessRouting)

                        // The daily lossless allowance (500 free a day, shared by the relays).
                        // Hidden when no relay is configured or nothing is known yet.
                        losslessToday?.let { line ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Unlimited for Ko-fi supporters: the claim, or the relays' "supporter" status.
                        SupporterSection()

                        // Direct Qobuz — direct www.qobuz.com Hi-Res FLAC, the
                        // primary lossless source. The badge shows when no
                        // lossless path is configured (`LosslessAvailability
                        // .qbdlxEnabled`). No per-source toggle: a stale saved `false` with
                        // no UI to flip it back would kill lossless silently.
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Same gate as the card subtitle.
                            if (qbdlxExpired) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_qobuz_no_source_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            // -- Bring your own Qobuz account -------------
                            // The user's own paid subscription: the one
                            // credential guaranteed to serve FLAC, since we
                            // mint + sign its token under a matching app_id.
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.settings_qobuz_account_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            // Keyed on hasLogin, NOT on the email: a token migrated
                            // from the old paste field has no email, and keying on
                            // that showed its owner a sign-in form with no way to
                            // remove the token when it went dead.
                            if (qobuzHasLogin) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = qobuzConnectedEmail?.let { stringResource(R.string.settings_connected_as, it) }
                                        ?: stringResource(R.string.settings_qobuz_connected_token),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TextButton(onClick = viewModel::onDisconnectQobuz) {
                                    Text(stringResource(R.string.settings_disconnect))
                                }
                            } else {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.settings_qobuz_signin_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                var qobuzEmail by remember { mutableStateOf("") }
                                var qobuzPassword by remember { mutableStateOf("") }
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = qobuzEmail,
                                    onValueChange = { qobuzEmail = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.settings_qobuz_email_label)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = qobuzPassword,
                                    onValueChange = { qobuzPassword = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.settings_password_label)) },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                )
                                qobuzConnectError?.let { err ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = { viewModel.onConnectQobuz(qobuzEmail, qobuzPassword) },
                                    enabled = !qobuzConnecting,
                                ) {
                                    if (qobuzConnecting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.settings_connecting))
                                    } else {
                                        Text(stringResource(R.string.settings_connect))
                                    }
                                }
                            }
                        }

                        // -- Tidal AAC when a song has no FLAC ----------------
                        // Streaming uses it too. With Lossless downloads it is in the Downloads
                        // card above; otherwise it is shown here, for lossless streaming.
                        if (uiState.downloadMode != DownloadMode.LOSSLESS) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_tidal_aac_fallback_title),
                                subtitle = stringResource(R.string.settings_tidal_aac_fallback_subtitle),
                                checked = tidalAacFallback,
                                onCheckedChange = viewModel::onTidalAacFallbackChanged,
                            )
                        }

                        // -- Streaming quality block --------------------------
                        // Per-network tier for *streaming* playback (distinct
                        // from the download tier above). Save Data is the master
                        // override: when on, both pickers are dimmed + inert and
                        // policy forces CD — the lowest lossless tier — on every network.
                        if (uiState.losslessEnabled) {
                            Spacer(modifier = Modifier.height(14.dp))
                            SettingsSectionLabel(stringResource(R.string.settings_streaming_section))
                            GlassCard {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    val saveData = uiState.streamingSaveData
                                    val pickerAlpha = if (saveData) 0.4f else 1f

                                    Text(
                                        text = stringResource(R.string.settings_on_wifi),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Column(
                                        modifier = Modifier
                                            .alpha(pickerAlpha)
                                            .selectableGroup(),
                                    ) {
                                        listOf(
                                            LosslessQualityTier.MAX,
                                            LosslessQualityTier.HI_RES,
                                            LosslessQualityTier.CD,
                                        ).forEach { tier ->
                                            SettingsPickerRow(
                                                selected = uiState.streamingWifiTier == tier,
                                                title = tier.displayLabel(),
                                                subtitle = tier.sizeHint(),
                                                onClick = {
                                                    if (!saveData) viewModel.onStreamingWifiTierChanged(tier)
                                                },
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = stringResource(R.string.settings_on_cellular),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Column(
                                        modifier = Modifier
                                            .alpha(pickerAlpha)
                                            .selectableGroup(),
                                    ) {
                                        listOf(
                                            LosslessQualityTier.MAX,
                                            LosslessQualityTier.HI_RES,
                                            LosslessQualityTier.CD,
                                        ).forEach { tier ->
                                            SettingsPickerRow(
                                                selected = uiState.streamingCellularTier == tier,
                                                title = tier.displayLabel(),
                                                subtitle = tier.sizeHint(),
                                                onClick = {
                                                    if (!saveData) viewModel.onStreamingCellularTierChanged(tier)
                                                },
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    SettingsToggleRow(
                                        title = stringResource(R.string.settings_save_data_title),
                                        // Lossless stays lossless: Save Data is the LOWEST lossless
                                        // tier, not a drop to lossy. Less than FLAC is the lossless
                                        // toggle's job, and then this same switch trims JioSaavn and YouTube.
                                        subtitle = stringResource(R.string.settings_save_data_lossless_subtitle),
                                        checked = uiState.streamingSaveData,
                                        onCheckedChange = viewModel::onStreamingSaveDataChanged,
                                        titleTrailing = { BetaPill() },
                                    )
                                }
                            }

                        }

                        // -- Advanced expander row (chevron + label) -----------
                        var advancedExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (advancedExpanded) 90f else 0f,
                            label = "advancedChevron",
                        )
                        val advancedExpandedDesc = stringResource(if (advancedExpanded) R.string.settings_state_expanded else R.string.settings_state_collapsed)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { advancedExpanded = !advancedExpanded }
                                .semantics {
                                    role = Role.Button
                                    // Spec §Accessibility: announce collapsed/expanded
                                    // state to screen readers.
                                    stateDescription = advancedExpandedDesc
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null, // parent Row carries role + stateDescription + label
                                modifier = Modifier.graphicsLayer(rotationZ = chevronRotation),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.settings_advanced),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        AnimatedVisibility(
                            visible = advancedExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // -- Custom lossless endpoint -----------------
                                // Outranks every relay from runtime config in
                                // QbdlxFileUrlRouter, so a typo here silently
                                // costs the user their first lossless attempt —
                                // hence Test, and hence commit-on-Done.
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_custom_relay_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                // The VM's committed value is the seed AND the
                                // comparison for commit(): re-keying on it means the
                                // field snaps to what was actually stored WHEN THE
                                // STORED VALUE CHANGES — a trailing slash normalises
                                // to the same string, so that edit is swallowed by
                                // distinctUntilChanged and the slash stays on screen.
                                // Saveable, not remember: MainActivity declares no
                                // configChanges, so a rotation mid-URL threw the
                                // half-typed draft away. A relay base is not a secret.
                                var endpointDraft by rememberSaveable(customEndpoint) {
                                    mutableStateOf(customEndpoint.orEmpty())
                                }
                                // The error lives in the VM and outlives this subtree:
                                // collapsing Advanced and reopening re-seeds the draft
                                // from storage but left the rejection on screen — an
                                // empty field painted red. Clear it on (re)entry.
                                LaunchedEffect(Unit) { viewModel.onCustomEndpointEdited() }
                                var endpointWasFocused by remember { mutableStateOf(false) }
                                val focusManager = LocalFocusManager.current
                                val commitEndpoint = {
                                    val draft = endpointDraft.trim()
                                    // Per keystroke this would persist `https://re` as a
                                    // base and route every resolve at it. The guard skips
                                    // the common no-op; it does NOT make this idempotent,
                                    // since the Done path commits and then clears focus
                                    // before `customEndpoint` has re-emitted. The repeat
                                    // is an identical write, collapsed downstream.
                                    if (draft != customEndpoint.orEmpty()) {
                                        viewModel.onCustomEndpointCommitted(draft)
                                    }
                                }
                                OutlinedTextField(
                                    value = endpointDraft,
                                    onValueChange = {
                                        endpointDraft = it
                                        // Otherwise the red "Must be an https:// URL"
                                        // outlives the text it judged — including onto
                                        // an empty field after a collapse/reopen.
                                        viewModel.onCustomEndpointEdited()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // `endpointWasFocused` gates the initial unfocused
                                        // callback, which would otherwise commit "".
                                        .onFocusChanged { state ->
                                             if (endpointWasFocused && !state.isFocused) commitEndpoint()
                                            endpointWasFocused = state.isFocused
                                        },
                                    label = { Text(stringResource(R.string.settings_custom_endpoint_label)) },
                                    singleLine = true,
                                    isError = customEndpointError != null,
                                    placeholder = { Text(stringResource(R.string.settings_url_placeholder)) },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            commitEndpoint()
                                            focusManager.clearFocus()
                                        },
                                    ),
                                )
                                customEndpointError?.let { err ->
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val testing =
                                        customEndpointTest == SettingsViewModel.EndpointTestState.TESTING
                                    TextButton(
                                        onClick = viewModel::onTestCustomEndpoint,
                                        enabled = customEndpoint != null && !testing,
                                    ) {
                                        Text(stringResource(R.string.settings_test))
                                    }
                                    // Reachability, not health: any HTTP reply counts.
                                    when (customEndpointTest) {
                                        SettingsViewModel.EndpointTestState.TESTING -> Text(
                                            text = stringResource(R.string.settings_testing),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        SettingsViewModel.EndpointTestState.REACHABLE -> Text(
                                            text = stringResource(R.string.settings_reachable),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StashTheme.extendedColors.success,
                                        )
                                        SettingsViewModel.EndpointTestState.UNREACHABLE -> Text(
                                            text = stringResource(R.string.settings_not_reachable),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        SettingsViewModel.EndpointTestState.IDLE -> Unit
                                    }
                                }


                                // -- Tidal via the user's own BitChord server -----
                                // The second lossless rung (after Qobuz). Same
                                // commit-on-Done / focus-loss rule as the endpoint
                                // above: a half-typed base must never be stored.
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.settings_bitchord_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                var bitchordDraft by rememberSaveable(bitchordEndpoint) {
                                    mutableStateOf(bitchordEndpoint.orEmpty())
                                }
                                LaunchedEffect(Unit) { viewModel.onBitchordEndpointEdited() }
                                var bitchordWasFocused by remember { mutableStateOf(false) }
                                val commitBitchord = {
                                    val draft = bitchordDraft.trim()
                                    if (draft != bitchordEndpoint.orEmpty()) {
                                        viewModel.onBitchordEndpointCommitted(draft)
                                    }
                                }
                                OutlinedTextField(
                                    value = bitchordDraft,
                                    onValueChange = {
                                        bitchordDraft = it
                                        viewModel.onBitchordEndpointEdited()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { state ->
                                            if (bitchordWasFocused && !state.isFocused) commitBitchord()
                                            bitchordWasFocused = state.isFocused
                                        },
                                    label = { Text(stringResource(R.string.settings_bitchord_server_label)) },
                                    singleLine = true,
                                    isError = bitchordEndpointError != null,
                                    placeholder = { Text(stringResource(R.string.settings_url_placeholder)) },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            commitBitchord()
                                            focusManager.clearFocus()
                                        },
                                    ),
                                )
                                bitchordEndpointError?.let { err ->
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                // Write-only: a stored token is never shown back,
                                // only that one is set. Blank + Done clears it.
                                var tokenDraft by remember { mutableStateOf("") }
                                OutlinedTextField(
                                    value = tokenDraft,
                                    onValueChange = { tokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.settings_access_token_label)) },
                                    supportingText = {
                                        Text(stringResource(if (bitchordHasToken) R.string.settings_bitchord_token_saved else R.string.settings_bitchord_token_hint))
                                    },
                                    singleLine = true,
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            viewModel.onBitchordTokenCommitted(tokenDraft)
                                            tokenDraft = ""
                                            focusManager.clearFocus()
                                        },
                                    ),
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.settings_squid_captcha_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = uiState.squidWtfCaptchaCookie,
                                    onValueChange = viewModel::onSquidWtfCaptchaCookieChanged,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.settings_squid_captcha_label)) },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.settings_squid_captcha_placeholder)) },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = viewModel::onResetLosslessRateLimiter,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_reset_lossless_attempts),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // (c) Hi-Res output: how the audio leaves the phone (USB DAC sample-rate
        // matching / bit-perfect on Android 14+), with a live signal-path line.
        HiResOutputSection()

        // (d) Effects.
        SettingsSectionLabel(stringResource(R.string.settings_effects_section))
        SettingsNavRow(
            title = stringResource(R.string.settings_equalizer_title),
            onClick = onNavigateToEqualizer,
        )
    }
}

@Composable
internal fun LosslessQualityTier.displayLabel(): String = when (this) {
    LosslessQualityTier.MAX -> stringResource(R.string.settings_lossless_tier_max)
    LosslessQualityTier.HI_RES -> stringResource(R.string.settings_lossless_tier_hires)
    LosslessQualityTier.CD -> stringResource(R.string.settings_lossless_tier_cd)
}

@Composable
internal fun LosslessQualityTier.sizeHint(): String = when (this) {
    LosslessQualityTier.MAX -> stringResource(R.string.settings_lossless_tier_max_hint)
    LosslessQualityTier.HI_RES -> stringResource(R.string.settings_lossless_tier_hires_hint)
    LosslessQualityTier.CD -> stringResource(R.string.settings_lossless_tier_cd_hint)
}
