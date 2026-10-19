package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.stash.feature.settings.R

/** Connection state for the ListenBrainz section. Mirrors LastFmAuthState's shape. */
sealed interface ListenBrainzState {
    data object Disconnected : ListenBrainzState
    data object Validating : ListenBrainzState
    data class Connected(val pendingListens: Int, val nowPlayingEnabled: Boolean) : ListenBrainzState
    data class Error(val message: String) : ListenBrainzState
}

/**
 * ListenBrainz scrobbling.
 *
 * Auth is a pasted user token rather than an OAuth handshake, so this section is
 * simpler than [LastFmSection] — no browser round-trip, no callback. The token is
 * validated against the service before it is stored, so a typo fails here rather
 * than silently never scrobbling.
 *
 * The copy is explicit that connecting starts from now. Stash keeps every play it
 * has ever recorded, and submitting that backlog would flood a user's ListenBrainz
 * history with thousands of listens they never asked to import — so the drain has a
 * cutoff, and the UI says so instead of leaving people wondering why their history
 * did not appear.
 *
 * Pure presentation; the caller owns all state.
 */
@Composable
fun ListenBrainzSection(
    state: ListenBrainzState,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onNowPlayingToggle: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    isDraining: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_listenbrainz_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.settings_listenbrainz_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (state) {
            is ListenBrainzState.Disconnected, is ListenBrainzState.Error -> {
                if (state is ListenBrainzState.Error) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = stringResource(R.string.settings_listenbrainz_paste_token),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = onTokenInputChange,
                    label = { Text(stringResource(R.string.settings_listenbrainz_user_token_label)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onConnect,
                    enabled = tokenInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_account_connect))
                }
            }

            is ListenBrainzState.Validating -> {
                Text(
                    text = stringResource(R.string.settings_listenbrainz_validating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            is ListenBrainzState.Connected -> {
                Text(
                    text = stringResource(R.string.settings_listenbrainz_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.pendingListens > 0) {
                        stringResource(R.string.settings_listenbrainz_queued, state.pendingListens)
                    } else {
                        // Says "from now on" deliberately: a user who connects and
                        // then checks their ListenBrainz history should understand
                        // why their older plays are not there.
                        stringResource(R.string.settings_listenbrainz_up_to_date)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_listenbrainz_now_playing_title),
                    subtitle = stringResource(R.string.settings_listenbrainz_now_playing_subtitle),
                    checked = state.nowPlayingEnabled,
                    onCheckedChange = onNowPlayingToggle,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onSyncNow,
                    enabled = !isDraining && state.pendingListens > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            isDraining -> stringResource(R.string.settings_lastfm_syncing)
                            state.pendingListens == 0 -> stringResource(R.string.settings_lastfm_nothing_to_sync)
                            else -> stringResource(R.string.settings_listenbrainz_sync_now)
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
        }
    }
}
