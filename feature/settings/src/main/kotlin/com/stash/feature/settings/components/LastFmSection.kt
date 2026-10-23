package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.feature.settings.LastFmAuthState
import com.stash.feature.settings.R

/** Last.fm's brand red. */
private val LastFmRed = Color(0xFFD51007)

/**
 * Last.fm on Accounts & Sync, in the same card as every other account. Its
 * sign-in is a browser round-trip, so the card has one state the others don't:
 * waiting for the listener to approve Stash on last.fm, where Finish leads.
 *
 * Renders nothing in a build without a Last.fm API key (there is nothing the
 * listener can do about that). Once connected, the card carries Last.fm's own
 * settings: loving hearted songs, first-artist-only scrobbles, and the queue.
 *
 * Pure presentation; the caller owns all state.
 */
@Composable
fun LastFmAccountCard(
    state: LastFmAuthState,
    onConnect: () -> Unit,
    onFinish: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    onSyncScrobblesNow: () -> Unit,
    isScrobbleDraining: Boolean,
    drainMessage: String?,
    firstArtistOnly: Boolean,
    onFirstArtistOnlyChange: (Boolean) -> Unit,
    loveEnabled: Boolean,
    onLoveToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == LastFmAuthState.NotConfigured) return
    val (statusText, tone) = when (state) {
        is LastFmAuthState.Connected ->
            stringResource(R.string.settings_lastfm_connected_as, state.username) to AccountStatusTone.CONNECTED
        is LastFmAuthState.AwaitingAuth ->
            stringResource(R.string.settings_lastfm_waiting_approval_body) to AccountStatusTone.NEUTRAL
        is LastFmAuthState.Error -> state.message to AccountStatusTone.ERROR
        else -> stringResource(R.string.settings_account_not_connected) to AccountStatusTone.NEUTRAL
    }
    AccountCard(
        serviceName = "Last.fm",
        icon = Icons.Rounded.Radio,
        accentColor = LastFmRed,
        statusText = statusText,
        statusTone = tone,
        modifier = modifier,
        extraContent = (state as? LastFmAuthState.Connected)?.let { connected ->
            {
                LikeMirrorToggleRow(
                    title = stringResource(R.string.settings_like_mirror_lastfm_title),
                    checked = loveEnabled,
                    onToggle = onLoveToggle,
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_lastfm_first_artist_only_title),
                    subtitle = stringResource(R.string.settings_lastfm_first_artist_only_desc),
                    checked = firstArtistOnly,
                    onCheckedChange = onFirstArtistOnlyChange,
                )
                ScrobbleQueueRow(
                    pending = connected.pendingScrobbles,
                    draining = isScrobbleDraining,
                    message = drainMessage,
                    onSendNow = onSyncScrobblesNow,
                )
            }
        },
    ) {
        when (state) {
            is LastFmAuthState.Connected -> AccountDisconnectButton(onDisconnect)
            is LastFmAuthState.AwaitingAuth -> Column(horizontalAlignment = Alignment.End) {
                AccountConnectButton(
                    LastFmRed,
                    onFinish,
                    label = stringResource(R.string.settings_lastfm_finish_connecting),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) }
            }
            else -> AccountConnectButton(LastFmRed, onConnect)
        }
    }
}

/**
 * What's waiting to go to a scrobbling service, with Send now. Says nothing
 * when the queue is empty and nothing just happened: an empty queue is the
 * normal state and needs no row of its own.
 */
@Composable
internal fun ScrobbleQueueRow(
    pending: Int,
    draining: Boolean,
    message: String?,
    onSendNow: () -> Unit,
) {
    if (pending == 0 && message == null && !draining) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SettingsRowPadH, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message ?: pluralStringResource(R.plurals.settings_scrobbles_waiting, pending, pending),
            style = MaterialTheme.typography.bodyMedium,
            color = if (message != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (draining) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.padding(12.dp).size(20.dp),
            )
        } else if (pending > 0) {
            TextButton(onClick = onSendNow) { Text(stringResource(R.string.settings_scrobbles_send_now)) }
        }
    }
}
