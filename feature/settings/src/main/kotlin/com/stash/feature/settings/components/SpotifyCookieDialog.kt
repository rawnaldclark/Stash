package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.stash.core.ui.R

/**
 * Dialog for entering a Spotify sp_dc cookie to authenticate.
 *
 * Displays step-by-step instructions for extracting the sp_dc cookie from the
 * user's browser, a text field for pasting the value, and a Connect button that
 * triggers validation. An optional error message is shown below the text field
 * when the cookie is invalid or expired.
 *
 * @param isValidating Whether the cookie is currently being validated (shows a spinner).
 * @param errorMessage Error message to display, or null if there is no error.
 * @param onConnect Callback with the entered cookie value when the user taps Connect.
 * @param onDismiss Callback when the dialog is dismissed.
 */
@Composable
fun SpotifyCookieDialog(
    isValidating: Boolean,
    errorMessage: String?,
    onConnect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var cookieValue by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.dialog_title_connect_spotify),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dialog_body_spotify_cookie),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.label_how_to_get_cookie),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_spotify_cookie_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = cookieValue,
                    onValueChange = { cookieValue = it },
                    label = { Text(stringResource(R.string.label_sp_dc_cookie)) },
                    placeholder = { Text(stringResource(R.string.hint_paste_sp_dc_cookie)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isValidating,
                    isError = errorMessage != null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Username is optional: the sp_dc exchange resolves the account
                // server-side, and library/playlist sync runs over the cookie
                // session (not the username). Offered only for a nicer
                // "Connected as …" label and as a fallback for the newer
                // opaque access tokens that carry no username.
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.label_spotify_username_optional)) },
                    placeholder = { Text(stringResource(R.string.hint_spotify_username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isValidating,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_spotify_username_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        },
        confirmButton = {
            if (isValidating) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Button(
                    onClick = { onConnect(cookieValue.trim(), username.trim()) },
                    // Cookie is the only requirement; username is optional.
                    enabled = cookieValue.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.action_connect))
                }
            }
        },
        dismissButton = {
            if (!isValidating) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
