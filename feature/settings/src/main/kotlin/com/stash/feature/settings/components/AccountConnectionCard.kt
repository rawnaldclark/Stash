package com.stash.feature.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
 *   same card, below a subtle divider. Used by e.g. YouTube Music to nest the
 *   "Send plays to YouTube Music" toggle directly under its connect row so
 *   the sync feature visually reads as a property of the connection rather
 *   than a separate settings row living in an unrelated card.
 *   Null ( = no extras) is the default; most services pass nothing.
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
    val extendedColors = StashTheme.extendedColors
    val status = accountStatus(authState, expired)

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
            // Service icon with accent colour
            Icon(
                imageVector = icon,
                contentDescription = serviceName,
                tint = accentColor,
                modifier = Modifier.size(32.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Service name and connection status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = serviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = status.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status.tone) {
                        AccountStatusTone.CONNECTED -> extendedColors.success
                        AccountStatusTone.EXPIRED -> extendedColors.warning
                        AccountStatusTone.ERROR -> MaterialTheme.colorScheme.error
                        AccountStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // Action button or loading indicator
            AnimatedVisibility(
                visible = authState is AuthState.Connecting,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = accentColor,
                    strokeWidth = 2.dp,
                )
            }

            AnimatedVisibility(
                visible = authState !is AuthState.Connecting,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                when (authState) {
                    is AuthState.Connected -> {
                        if (status.offerReconnect) {
                            // Expired session: signing in again is the next
                            // step, so it leads; Disconnect stays reachable
                            // underneath, quieter, for someone done with it.
                            Column(horizontalAlignment = Alignment.End) {
                                Button(
                                    onClick = onConnect,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accentColor,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text(stringResource(R.string.settings_account_reconnect))
                                }
                                TextButton(
                                    onClick = onDisconnect,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Text(stringResource(R.string.settings_account_disconnect))
                                }
                            }
                        } else if (needsAction && onNeedsAction != null) {
                            Button(
                                onClick = onNeedsAction,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(needsActionLabel)
                            }
                        } else {
                            OutlinedButton(
                                onClick = onDisconnect,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(stringResource(R.string.settings_account_disconnect))
                            }
                        }
                    }
                    else -> {
                        Button(
                            onClick = onConnect,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(stringResource(R.string.settings_account_connect))
                        }
                    }
                }
            }
            } // close Row
            if (extraContent != null) {
                HorizontalDivider(
                    color = extendedColors.glassBorder,
                    thickness = 1.dp,
                )
                extraContent()
            }
        } // close Column
    }
}
