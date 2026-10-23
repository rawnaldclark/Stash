package com.stash.feature.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.auth.model.AuthState
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R

/**
 * A reusable card displaying a streaming service's connection status.
 *
 * Shows the service icon and name on the left with the accent colour, the
 * current connection status (with user display name when connected), and a
 * connect/disconnect button. A loading spinner replaces the button while
 * the [AuthState.Connecting] state is active.
 *
 * @param serviceName   Human-readable service name (e.g. "Spotify").
 * @param icon          Material icon representing the service.
 * @param accentColor   Brand colour for the service (green for Spotify, red for YouTube).
 * @param authState     Current authentication state for this service.
 * @param onConnect     Callback invoked when the user taps "Connect".
 * @param onDisconnect  Callback invoked when the user taps "Disconnect".
 * @param modifier      Optional [Modifier] for the root layout.
 * @param expired       True when the last sync found this connected account's
 *   session expired. The card then says so and leads with a "Reconnect" button
 *   (the same sign-in as [onConnect]), keeping Disconnect as a secondary text
 *   button. Ignored unless [authState] is Connected; see [accountStatus].
 * @param extraContent  Optional per-service sub-settings rendered inside the
 *   same card, below a subtle divider (e.g. YouTube Music's likes and history
 *   switches), so they read as properties of the connection. Null = none.
 */
@Composable
fun AccountConnectionCard(
    serviceName: String,
    icon: ImageVector,
    accentColor: Color,
    authState: AuthState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    needsAction: Boolean = false,
    needsActionLabel: String = "Finish connecting",
    onNeedsAction: (() -> Unit)? = null,
    expired: Boolean = false,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val status = accountStatus(authState, expired)
    AccountCard(
        serviceName = serviceName,
        icon = icon,
        accentColor = accentColor,
        statusText = status.text,
        statusTone = status.tone,
        modifier = modifier,
        busy = authState is AuthState.Connecting,
        extraContent = extraContent,
    ) {
        when {
            authState !is AuthState.Connected -> AccountConnectButton(accentColor, onConnect)
            status.offerReconnect -> {
                // Expired session: signing in again is the next step, so it
                // leads; Disconnect stays reachable underneath for someone
                // done with it.
                Column(horizontalAlignment = Alignment.End) {
                    AccountConnectButton(
                        accentColor,
                        onConnect,
                        label = stringResource(R.string.settings_account_reconnect),
                    )
                    AccountDisconnectButton(onDisconnect)
                }
            }
            needsAction && onNeedsAction != null ->
                AccountConnectButton(accentColor, onNeedsAction, label = needsActionLabel)
            else -> AccountDisconnectButton(onDisconnect)
        }
    }
}

/**
 * The shell every account on Accounts & Sync shares: brand icon, name, one
 * status line, a trailing action, and optional sub-settings under a hairline.
 * [AccountConnectionCard] fills it from an [AuthState]; accounts with their own
 * sign-in flow (Last.fm, ListenBrainz) fill it directly so they look the same.
 *
 * @param busy  Shows a spinner in place of [action] (a sign-in in flight).
 */
@Composable
fun AccountCard(
    serviceName: String,
    icon: ImageVector,
    accentColor: Color,
    statusText: String,
    statusTone: AccountStatusTone,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = extendedColors.glassBackground,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = serviceName,
                    tint = accentColor,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = serviceName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = when (statusTone) {
                            AccountStatusTone.CONNECTED -> extendedColors.success
                            AccountStatusTone.EXPIRED -> extendedColors.warning
                            AccountStatusTone.ERROR -> MaterialTheme.colorScheme.error
                            AccountStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                AnimatedVisibility(visible = busy, enter = fadeIn(), exit = fadeOut()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = accentColor,
                        strokeWidth = 2.dp,
                    )
                }
                AnimatedVisibility(visible = !busy, enter = fadeIn(), exit = fadeOut()) {
                    action()
                }
            }
            if (extraContent != null) {
                HorizontalDivider(color = extendedColors.glassBorder, thickness = 1.dp)
                extraContent()
            }
        }
    }
}

/** The filled, brand-coloured Connect (or Reconnect / Finish) button. */
@Composable
fun AccountConnectButton(
    accentColor: Color,
    onClick: () -> Unit,
    label: String = stringResource(R.string.settings_account_connect),
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White),
    ) {
        Text(label)
    }
}

/**
 * Disconnect, kept quiet: a connected card's status is what matters, and a row
 * of red outlined buttons down the screen read as alarms.
 */
@Composable
fun AccountDisconnectButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.settings_account_disconnect))
    }
}
