package com.stash.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.stash.core.data.sync.workers.UpdateCheckWorker
import com.stash.core.ui.components.GlassCard
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsValueRow

/**
 * The About & Help spoke of the hub-and-spoke Settings redesign.
 *
 * Behavior-preserving relocation of the Diagnostics section (crash-report
 * share + diagnostics-bundle preview nav) and the About section (version /
 * license value rows + check-for-updates) from the monolithic
 * [SettingsScreen]. Every callback and intent-building branch is copied
 * verbatim; only the group headers and value rows are restyled onto the
 * shared component kit ([SettingsSectionLabel], [SettingsValueRow]).
 */
@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
    onNavigateToDiagnosticsPreview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(title = "About & Help", onBack = onBack, modifier = modifier) {
        // -- Diagnostics section ----------------------------------------------
        // Crash-to-file: writes uncaught exceptions to cacheDir/crashes/ so
        // the user can attach the latest one to an email / Discord / GitHub
        // issue. Zero network, zero auto-upload. Disabled when no files
        // exist; LaunchedEffect refreshes liveness on every entry so a
        // share that resolves the issue immediately flips the button off
        // on the next screen visit.
        val context = LocalContext.current
        LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

        SettingsSectionLabel("Diagnostics")

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Share latest crash report",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (uiState.hasCrashReport) {
                        "Attach the most recent crash log to email or chat. Stays on device until you share."
                    } else {
                        "No recent crashes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    enabled = uiState.hasCrashReport,
                    onClick = {
                        val target = viewModel.latestCrashShareTarget()
                        if (target == null) {
                            Toast.makeText(context, "No crash report to share.", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_STREAM, target.contentUri)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Stash crash report")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = android.content.Intent.createChooser(send, "Share crash report")
                            .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        runCatching { context.startActivity(chooser) }
                            .onFailure {
                                Toast.makeText(context, "No app available to share the report.", Toast.LENGTH_SHORT).show()
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Share latest crash report")
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Share diagnostics",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Bundle recent logs, sync history, and connection status (no passwords) so a dev can debug your issue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onNavigateToDiagnosticsPreview,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Share diagnostics")
                }
            }
        }

        // -- About section ----------------------------------------------------
        SettingsSectionLabel("About")

        val installedVersion = remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "0.3.5-beta.1"
        }

        // The check runs in a worker, so the tap alone can't report anything.
        // Every no-update path used to return a bare success and the user was
        // left with "Checking for updates…" and silence (#377) — so watch the
        // work and say what it concluded.
        var checking by remember { mutableStateOf(false) }
        LaunchedEffect(context) {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(UpdateCheckWorker.UNIQUE_ONE_SHOT_NAME)
                .collect { infos ->
                    val info = infos.lastOrNull() ?: return@collect
                    if (!info.state.isFinished) return@collect
                    // Only speak for a check this screen started. A cold-start
                    // check finishing in the background must stay quiet.
                    if (!checking) return@collect
                    checking = false
                    val outcome = info.outputData.getString(UpdateCheckWorker.KEY_OUTCOME)
                    val tag = info.outputData.getString(UpdateCheckWorker.KEY_LATEST_TAG)
                    val message = when (outcome) {
                        UpdateCheckWorker.OUTCOME_UP_TO_DATE ->
                            "You're on the latest version ($installedVersion)"
                        UpdateCheckWorker.OUTCOME_UPDATE_AVAILABLE ->
                            "Update available: ${tag ?: "see Releases"}"
                        else -> "Couldn't check for updates — try again later"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsValueRow(label = "Version", value = installedVersion)
                Spacer(modifier = Modifier.height(8.dp))
                SettingsValueRow(label = "License", value = "GPL-3.0")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        checking = true
                        UpdateCheckWorker.enqueueOneTimeCheck(context, manual = true)
                        Toast.makeText(context, "Checking for updates…", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(if (checking) "Checking…" else "Check for updates")
                }
            }
        }
    }
}
