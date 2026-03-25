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
    onConnect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var cookieValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = {
            Text(
                text = "Connect Spotify",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = "To connect your Spotify account, paste your sp_dc cookie below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "How to get your sp_dc cookie:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "1. Go to open.spotify.com in your browser\n" +
                        "2. Log in to your Spotify account\n" +
                        "3. Press F12 to open DevTools\n" +
                        "4. Go to Application > Cookies\n" +
                        "5. Copy the value of 'sp_dc'",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = cookieValue,
                    onValueChange = { cookieValue = it },
                    label = { Text("sp_dc cookie") },
                    placeholder = { Text("Paste your sp_dc cookie here") },
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
