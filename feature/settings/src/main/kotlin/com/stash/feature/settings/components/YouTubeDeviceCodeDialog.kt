package com.stash.feature.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.auth.model.DeviceCodeState
import com.stash.core.ui.theme.StashTheme

/**
 * Dialog displayed during the YouTube device-code authorization flow.
 *
 * Presents the user code prominently so the user can enter it at
 * [DeviceCodeState.verificationUrl]. Provides convenience buttons to
 * copy the code to the clipboard and open the verification URL in a
 * browser. A progress indicator signals that polling is in progress.
 *
 * @param deviceCodeState The current device-code state containing the user code and URL.
 * @param onDismiss       Callback invoked when the user dismisses the dialog.
 */
@Composable
fun YouTubeDeviceCodeDialog(
    deviceCodeState: DeviceCodeState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val extendedColors = StashTheme.extendedColors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = "Connect YouTube Music",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Go to the URL below and enter this code:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // User code displayed prominently in a bordered surface
                Surface(
                    color = extendedColors.glassBackground,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, extendedColors.glassBorderBright),
                ) {
                    Text(
                        text = deviceCodeState.userCode,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                        ),
                        color = extendedColors.youtubeRed,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = deviceCodeState.verificationUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    OutlinedButton(
                        onClick = { copyToClipboard(context, deviceCodeState.userCode) },
                        border = BorderStroke(1.dp, extendedColors.glassBorderBright),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy code",
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text("Copy Code")
                    }

                    Button(
                        onClick = { openBrowser(context, deviceCodeState.verificationUrl) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = extendedColors.youtubeRed,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInBrowser,
                            contentDescription = "Open browser",
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text("Open Browser")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Polling progress indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(16.dp)
                            .width(16.dp),
                        color = extendedColors.youtubeRed,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Waiting for authorization...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Copies [text] to the system clipboard with label "YouTube Code".
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("YouTube Code", text))
}

/**
 * Launches the default browser to the given [url].
 */
private fun openBrowser(context: Context, url: String) {
    val fullUrl = if (url.startsWith("http")) url else "https://$url"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
    context.startActivity(intent)
}
