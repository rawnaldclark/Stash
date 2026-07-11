package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.core.ui.R
import com.stash.feature.settings.LastFmAuthState

/**
 * Renders the Last.fm connection card. Four states from the VM drive
 * the layout: NotConfigured (disabled explanation), Disconnected
 * (Connect button), AwaitingAuth (Finish connecting after browser),
 * Connected (username + pending scrobbles + Disconnect), Error
 * (message + Dismiss).
 *
 * Relocated verbatim from the monolithic `SettingsScreen.kt` as part of the
 * hub-and-spoke Settings redesign — behavior-preserving, no logic changes.
 */
@Composable
fun LastFmSection(
    state: LastFmAuthState,
    onConnect: () -> Unit,
    onFinish: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
    onSyncScrobblesNow: () -> Unit,
    isScrobbleDraining: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            LastFmAuthState.NotConfigured -> {
                Text(
                    text = stringResource(R.string.status_lastfm_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.desc_lastfm_no_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LastFmAuthState.Disconnected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.label_scrobble_plays),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedButton(
                        onClick = onConnect,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(stringResource(R.string.action_connect_lastfm))
                    }
                }
            }
            is LastFmAuthState.AwaitingAuth -> {
                Text(
                    text = stringResource(R.string.status_lastfm_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.desc_lastfm_awaiting_auth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_finish_connecting))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            is LastFmAuthState.Connected -> {
                Text(
                    text = stringResource(R.string.status_lastfm_connected, state.username),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.pendingScrobbles > 0) {
                        stringResource(R.string.status_lastfm_pending, state.pendingScrobbles)
                    } else {
                        stringResource(R.string.status_lastfm_up_to_date)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // "Sync scrobbles now" — manual drain. Useful right after
                // the Last.fm connect handshake when cold-start import +
                // accumulated local plays can leave hundreds queued up.
                Button(
                    onClick = onSyncScrobblesNow,
                    enabled = !isScrobbleDraining && state.pendingScrobbles > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            isScrobbleDraining -> "Syncing…"
                            state.pendingScrobbles == 0 -> "Nothing to sync"
                            else -> "Sync scrobbles now"
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Disconnect")
                }
            }
            is LastFmAuthState.Error -> {
                Text(
                    text = stringResource(R.string.error_lastfm_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}
