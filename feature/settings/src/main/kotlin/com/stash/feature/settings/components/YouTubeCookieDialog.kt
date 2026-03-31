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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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
                text = "Connect YouTube Music",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = "Paste your YouTube Music cookies to connect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "How to get your cookies:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "1. Open a Private/Incognito window\n" +
                        "2. Go to music.youtube.com and log in\n" +
                        "3. Press F12 to open DevTools\n" +
                        "4. Go to Network tab, click any request\n" +
                        "5. Find 'Cookie' in Request Headers\n" +
                        "6. Copy the ENTIRE cookie value\n\n" +
                        "Must contain: SAPISID, LOGIN_INFO, SID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = cookieValue,
                    onValueChange = { cookieValue = it },
                    label = { Text("Cookie") },
                    placeholder = { Text("Paste your cookie here") },
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
                    Text("Connect")
                }
            }
        },
        dismissButton = {
            if (!isValidating) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
