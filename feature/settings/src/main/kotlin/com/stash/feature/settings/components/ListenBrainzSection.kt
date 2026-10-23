package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** ListenBrainz's brand orange. */
private val ListenBrainzOrange = Color(0xFFEB743B)

/**
 * ListenBrainz on Accounts & Sync, in the same card as every other account.
 *
 * Auth is a pasted user token rather than an OAuth handshake, so Connect opens
 * a small dialog for it instead of keeping a text field on the screen. The
 * token is validated before it is stored, so a typo fails in the dialog rather
 * than silently never scrobbling; the dialog closes once it connects.
 *
 * Connecting starts from now: Stash keeps every play it has recorded, and
 * submitting that backlog would flood the listener's history (the drain has a
 * cutoff). The dialog says so in one line.
 *
 * Pure presentation; the caller owns all state.
 */
@Composable
fun ListenBrainzAccountCard(
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
    var showTokenDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is ListenBrainzState.Connected) showTokenDialog = false
    }
    if (showTokenDialog) {
        ListenBrainzTokenDialog(
            state = state,
            tokenInput = tokenInput,
            onTokenInputChange = onTokenInputChange,
            onConnect = onConnect,
            onDismiss = {
                // Leave nothing behind: reopening shows an empty field, not the
                // last attempt and its error.
                onTokenInputChange("")
                showTokenDialog = false
            },
        )
    }

    val (statusText, tone) = when (state) {
        is ListenBrainzState.Connected ->
            stringResource(R.string.settings_listenbrainz_connected) to AccountStatusTone.CONNECTED
        // While the dialog is up it shows the check and the error itself.
        else -> stringResource(R.string.settings_account_not_connected) to AccountStatusTone.NEUTRAL
    }
    AccountCard(
        serviceName = stringResource(R.string.settings_listenbrainz_title),
        icon = Icons.Rounded.Headphones,
        accentColor = ListenBrainzOrange,
        statusText = statusText,
        statusTone = tone,
        modifier = modifier,
        extraContent = (state as? ListenBrainzState.Connected)?.let { connected ->
            {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_listenbrainz_now_playing_title),
                    checked = connected.nowPlayingEnabled,
                    onCheckedChange = onNowPlayingToggle,
                )
                ScrobbleQueueRow(
                    pending = connected.pendingListens,
                    draining = isDraining,
                    message = null,
                    onSendNow = onSyncNow,
                )
            }
        },
    ) {
        if (state is ListenBrainzState.Connected) {
            AccountDisconnectButton(onDisconnect)
        } else {
            AccountConnectButton(ListenBrainzOrange, onClick = { showTokenDialog = true })
        }
    }
}

@Composable
private fun ListenBrainzTokenDialog(
    state: ListenBrainzState,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val validating = state is ListenBrainzState.Validating
    // Bound to local state, not the ViewModel's flow: a flow-backed value lags a
    // frame behind typing and the field drops characters / jumps the cursor.
    var text by rememberSaveable { mutableStateOf(tokenInput) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.settings_listenbrainz_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_listenbrainz_paste_token),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onTokenInputChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_listenbrainz_user_token_label)) },
                    singleLine = true,
                    enabled = !validating,
                    isError = state is ListenBrainzState.Error,
                    supportingText = (state as? ListenBrainzState.Error)?.let { { Text(it.message) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConnect() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConnect, enabled = text.isNotBlank() && !validating) {
                Text(
                    if (validating) {
                        stringResource(R.string.settings_listenbrainz_validating)
                    } else {
                        stringResource(R.string.settings_account_connect)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}
