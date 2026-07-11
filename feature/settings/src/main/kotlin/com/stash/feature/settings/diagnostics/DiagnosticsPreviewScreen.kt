package com.stash.feature.settings.diagnostics

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.R
import com.stash.core.ui.components.GlassCard

/**
 * Diagnostics preview — shows the user the full, already-redacted diagnostics
 * report before they share it, so "no passwords or tokens" is verifiable rather
 * than asserted. The text + shareable bundle come from
 * [DiagnosticsPreviewViewModel] (a thin wrapper over `DiagnosticsBundleBuilder`).
 *
 * Share mirrors Settings' crash-report ACTION_SEND exactly (content:// URI with
 * FLAG_GRANT_READ, wrapped in a chooser, Toast fallback). Copy drops the plain
 * text onto the clipboard for pasting into chat / a GitHub issue.
 */
@Composable
fun DiagnosticsPreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Pre-resolve strings used in non-Composable callbacks (Intent builders).
    val diagnosticsSubject = stringResource(R.string.label_diagnostics_subject)
    val shareDiagnosticsTitle = stringResource(R.string.label_share_diagnostics_title)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // Top bar: back + title (mirrors LibraryHealthScreen's Header).
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.title_diagnostics),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(8.dp))

        when {
            state.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.error ?: stringResource(R.string.error_diagnostics_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::rebuild) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }

            else -> {
                Text(
                    text = stringResource(R.string.desc_diagnostics_review),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Spacer(Modifier.height(12.dp))

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Text(
                        text = state.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        enabled = state.bundle != null,
                        onClick = {
                            val uri = state.bundle?.contentUri ?: return@Button
                            // Mirrors SettingsScreen's crash-report ACTION_SEND:
                            // content:// URI + FLAG_GRANT_READ behind a chooser,
                            // with a Toast fallback when nothing can handle it.
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(
                                    android.content.Intent.EXTRA_SUBJECT,
                                    diagnosticsSubject,
                                )
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = android.content.Intent.createChooser(send, shareDiagnosticsTitle)
                                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                            runCatching { context.startActivity(chooser) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        "No app available to share.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_share))
                    }

                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(state.text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_copy))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
