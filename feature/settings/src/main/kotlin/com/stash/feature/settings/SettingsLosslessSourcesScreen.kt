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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.GlassCard
import com.stash.feature.settings.components.LosslessRoutingStatus
import com.stash.feature.settings.components.SettingsScaffold

/**
 * Settings > Audio & Quality > Lossless sources: where FLAC comes from, kept
 * off the main quality screen so it stays short. The routing rows, today's
 * free allowance, the supporter unlock, the listener's own Qobuz account,
 * and (collapsed) Advanced: Add from a link (the user's own add-ons), the listener's own Tidal
 * server read-only when one is set, and the debug-build Tidal wire instruments.
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
    val quotaViewModel: LosslessQuotaViewModel = hiltViewModel()
    val losslessToday by quotaViewModel.line.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.settings_lossless_sources_title), onBack = onBack, modifier = modifier) {
        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ROUTING block — one row per configured lossless path,
                // built in LosslessAvailability so this list and the
                // resolver read the same predicates.
                LosslessRoutingStatus(rows = losslessRouting)

                // The daily lossless allowance (300 free a day, counted by Stash's Tidal
                // relay). Hidden when no Tidal relay is configured or nothing is known yet.
                losslessToday?.let { line ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Unlimited for Ko-fi supporters: the claim, or the relay's "supporter" status.
                SupporterSection()

                // Direct Qobuz — the listener's own account, signed
                // locally against www.qobuz.com. The warning shows when no
                // lossless path is configured (`LosslessAvailability
                // .anySourceConfigured`). No per-source toggle: a stale saved `false` with
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
                        // -- Add-ons phase 2 (plan §5.4, §5.5) -------------
                        // The old Custom Tidal server field: hidden when empty, read-only
                        // with Remove when set (removed in the next release). Then "Add
                        // from a link", and the Tidal wire instruments on debug builds.
                        OwnTidalServerRow()
                        AddonLinksSection()
                        TidalWireDebugRows()
                    }
                }
            }
        }
    }
}
