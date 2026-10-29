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
import com.stash.feature.settings.R

/**
 * Dialog for entering YouTube Music cookies to authenticate.
 *
 * Displays step-by-step instructions for extracting cookies from the user's
 * browser session on music.youtube.com. The cookie string must contain a
 * SAPISID value which is used for SAPISIDHASH authentication with InnerTube.
 *
 * @param isValidating Whether the cookie is currently being validated (shows a spinner).
 * @param errorMessage Error message to display, or null if there is no error.
 * @param onConnect    Callback with the entered cookie value when the user taps Connect.
 * @param onDismiss    Callback when the dialog is dismissed.
 */
@Composable
fun YouTubeCookieDialog(
    isValidating: Boolean,
    errorMessage: String?,
    onConnect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var cookieValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.settings_youtube_connect_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_youtube_cookie_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.settings_youtube_how_to_get_cookies),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.settings_youtube_how_to_get_cookies_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = cookieValue,
                    onValueChange = { cookieValue = it },
                    label = { Text(stringResource(R.string.settings_youtube_cookie_label)) },
                    placeholder = { Text(stringResource(R.string.settings_youtube_cookie_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
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
                    onClick = { onConnect(cookieValue.trim()) },
                    enabled = cookieValue.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.settings_account_connect))
                }
            }
        },
        dismissButton = {
            if (!isValidating) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        },
    )
}
