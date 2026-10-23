package com.stash.feature.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.stash.core.data.sync.workers.UpdateCheckWorker
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsInfoRow
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsGroupCard

/**
 * The About & Help spoke of the hub-and-spoke Settings redesign.
 *
 * Behavior-preserving relocation of the Diagnostics section (crash-report
 * share + diagnostics-bundle preview nav) and the About section (version /
 * license value rows + check-for-updates) from the monolithic
 * [SettingsScreen]. Every callback and intent-building branch is copied
 * verbatim; only the group headers and value rows are restyled onto the
 * shared component kit ([SettingsSectionLabel], [SettingsInfoRow]).
 */
@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
    onNavigateToDiagnosticsPreview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.settings_about_title), onBack = onBack, modifier = modifier) {
        val context = LocalContext.current
        // Crash-to-file: writes uncaught exceptions to cacheDir/crashes/ so the
        // user can attach the latest one to an email / Discord / GitHub issue.
        // Zero network, zero auto-upload. Refreshed on every entry so a share
        // that resolves the issue flips the row off on the next visit.
        LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

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
                            context.getString(R.string.settings_update_on_latest, installedVersion)
                        UpdateCheckWorker.OUTCOME_UPDATE_AVAILABLE ->
                            context.getString(R.string.settings_update_available, tag ?: context.getString(R.string.settings_update_see_releases))
                        else -> context.getString(R.string.settings_update_check_failed)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
        }

        SettingsSectionLabel(stringResource(R.string.settings_about_section))
        SettingsGroupCard(
            rows = listOf(
                { SettingsInfoRow(label = stringResource(R.string.settings_version_label), value = installedVersion) },
                { SettingsInfoRow(label = stringResource(R.string.settings_license_label), value = "GPL-3.0") },
                {
                    SettingsNavRow(
                        title = if (checking) {
                            stringResource(R.string.settings_checking_updates)
                        } else {
                            stringResource(R.string.settings_check_updates)
                        },
                        onClick = {
                            if (!checking) {
                                checking = true
                                UpdateCheckWorker.enqueueOneTimeCheck(context, manual = true)
                                Toast.makeText(context, context.getString(R.string.settings_checking_updates_toast), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                },
            ),
        )

        SettingsSectionLabel(stringResource(R.string.settings_help_section))
        val shareCrash: () -> Unit = shareCrash@{
            val target = viewModel.latestCrashShareTarget()
            if (target == null) {
                Toast.makeText(context, context.getString(R.string.settings_no_crash_report_toast), Toast.LENGTH_SHORT).show()
                return@shareCrash
            }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, target.contentUri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.settings_crash_report_subject))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(send, context.getString(R.string.settings_share_crash_report_chooser))
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(chooser) }
                .onFailure {
                    Toast.makeText(context, context.getString(R.string.settings_no_app_share_report_toast), Toast.LENGTH_SHORT).show()
                }
        }
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_share_diagnostics),
                        subtitle = stringResource(R.string.settings_bundle_recent_logs),
                        onClick = onNavigateToDiagnosticsPreview,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_share_latest_crash),
                        subtitle = if (uiState.hasCrashReport) {
                            stringResource(R.string.settings_attach_most_recent_crash)
                        } else {
                            stringResource(R.string.settings_no_recent_crashes)
                        },
                        onClick = { if (uiState.hasCrashReport) shareCrash() },
                    )
                },
            ),
        )
    }
}
