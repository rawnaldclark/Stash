package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.feature.settings.LastFmAuthState
import com.stash.feature.settings.R

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
    firstArtistOnly: Boolean = false,
    onFirstArtistOnlyChange: (Boolean) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            LastFmAuthState.NotConfigured -> {
                Text(
                    text = stringResource(R.string.settings_lastfm_not_configured_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_lastfm_not_configured_body),
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
                        text = stringResource(R.string.settings_lastfm_scrobble_plays),
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
                        Text(stringResource(R.string.settings_lastfm_connect))
                    }
                }
            }
            is LastFmAuthState.AwaitingAuth -> {
                Text(
                    text = stringResource(R.string.settings_lastfm_waiting_approval_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_lastfm_waiting_approval_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_lastfm_finish_connecting))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
            is LastFmAuthState.Connected -> {
                Text(
                    text = stringResource(R.string.settings_lastfm_connected_as, state.username),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.pendingScrobbles > 0) {
                        stringResource(R.string.settings_lastfm_queued_scrobbles, state.pendingScrobbles)
                    } else {
                        stringResource(R.string.settings_lastfm_up_to_date)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_lastfm_first_artist_only_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_lastfm_first_artist_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = firstArtistOnly,
                        onCheckedChange = onFirstArtistOnlyChange,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // "Sync scrobbles now" — manual drain. Useful right after
                // the Last.fm connect handshake when accumulated local
                // plays can leave hundreds queued up.
                Button(
                    onClick = onSyncScrobblesNow,
                    enabled = !isScrobbleDraining && state.pendingScrobbles > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            isScrobbleDraining -> stringResource(R.string.settings_lastfm_syncing)
                            state.pendingScrobbles == 0 -> stringResource(R.string.settings_lastfm_nothing_to_sync)
                            else -> stringResource(R.string.settings_lastfm_sync_now)
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
                    Text(stringResource(R.string.settings_account_disconnect))
                }
            }
            is LastFmAuthState.Error -> {
                Text(
                    text = stringResource(R.string.settings_lastfm_couldnt_connect),
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
                    Text(stringResource(R.string.settings_lastfm_dismiss))
                }
            }
        }
    }
}
