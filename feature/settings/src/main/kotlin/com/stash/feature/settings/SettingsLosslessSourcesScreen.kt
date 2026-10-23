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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.components.LosslessRoutingStatus
import com.stash.feature.settings.components.SettingsScaffold

/**
 * Settings > Audio & Quality > Lossless sources: where FLAC comes from, kept
 * off the main quality screen so it stays short. The routing rows, today's
 * shared allowance, the supporter unlock, the listener's own Qobuz account,
 * and (collapsed) Advanced: a custom endpoint, a BitChord server, the captcha
 * cookie, and resetting lossless attempts.
 */
@Composable
fun SettingsLosslessSourcesScreen(
    onBack: () -> Unit,
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
    val quotaViewModel: LosslessQuotaViewModel = hiltViewModel()
    val losslessToday by quotaViewModel.line.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.settings_lossless_sources_title), onBack = onBack, modifier = modifier) {
        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
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

            }
        }
        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
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
