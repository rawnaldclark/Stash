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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.sync.workers.UpdateCheckWorker
import com.stash.core.ui.R
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

    SettingsScaffold(title = stringResource(R.string.title_about_help), onBack = onBack, modifier = modifier) {
        // -- Diagnostics section ----------------------------------------------
        // Crash-to-file: writes uncaught exceptions to cacheDir/crashes/ so
        // the user can attach the latest one to an email / Discord / GitHub
        // issue. Zero network, zero auto-upload. Disabled when no files
        // exist; LaunchedEffect refreshes liveness on every entry so a
        // share that resolves the issue immediately flips the button off
        // on the next screen visit.
        val context = LocalContext.current
        LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

        // Pre-resolve strings used in non-Composable callbacks (Intent builders, Toast calls).
        val crashReportSubject = stringResource(R.string.label_crash_report_subject)
        val shareCrashReportTitle = stringResource(R.string.label_share_crash_report_title)
        val noAppAvailableMsg = stringResource(R.string.status_no_app_available)
        val checkingUpdatesMsg = stringResource(R.string.status_checking_updates)

        SettingsSectionLabel(stringResource(R.string.section_diagnostics))

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.label_share_crash_report),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (uiState.hasCrashReport) {
                        "Attach the most recent crash log to email or chat. Stays on device until you share."
                    } else {
                        stringResource(R.string.label_no_recent_crashes)
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
                            putExtra(android.content.Intent.EXTRA_SUBJECT, crashReportSubject)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = android.content.Intent.createChooser(send, shareCrashReportTitle)
                            .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        runCatching { context.startActivity(chooser) }
                            .onFailure {
                                Toast.makeText(context, noAppAvailableMsg, Toast.LENGTH_SHORT).show()
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.label_share_crash_report))
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.label_share_diagnostics),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.desc_share_diagnostics),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onNavigateToDiagnosticsPreview,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(stringResource(R.string.label_share_diagnostics))
                }
            }
        }

        // -- About section ----------------------------------------------------
        SettingsSectionLabel(stringResource(R.string.section_about))

        val installedVersion = remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "0.3.5-beta.1"
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsValueRow(label = stringResource(R.string.label_version), value = installedVersion)
                Spacer(modifier = Modifier.height(8.dp))
                SettingsValueRow(label = stringResource(R.string.label_license), value = "GPL-3.0")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        UpdateCheckWorker.enqueueOneTimeCheck(context)
                        Toast.makeText(context, checkingUpdatesMsg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.action_check_updates))
                }
            }
        }
    }
}
