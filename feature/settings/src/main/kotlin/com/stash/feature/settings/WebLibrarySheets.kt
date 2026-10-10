package com.stash.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblink.inbox.SendTarget
import com.stash.feature.settings.weblink.LibraryPickerSheet
import com.stash.feature.settings.weblink.PickerAction

/**
 * The export picker (link-sync spec §2.3) and what follows it: Save as file (the system file picker, then the file), or Send to
 * a linked browser ("Send to…" asks which when there are several). Shown while [WebLibraryExportViewModel.picker] is open;
 * used by Library & Storage › Backup and by Link Stash on the web.
 */
@Composable
fun WebLibraryExportSheet(vm: WebLibraryExportViewModel) {
    val picker by vm.picker.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    if (state !is WebLibraryExportState.Idle) WebLibraryExportDialog(state, vm::dismiss)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    var choosingTarget by remember { mutableStateOf(false) }
    val p = picker ?: return
    val sendActions = when (p.targets.size) {
        0 -> emptyList()
        1 -> listOf(PickerAction("Send to ${p.targets[0].name}", { vm.send(p.targets[0]) }))
        else -> listOf(PickerAction("Send to…", { choosingTarget = true }))
    }
    LibraryPickerSheet(
        title = "What to include",
        pick = p.pick,
        onPick = vm::updatePick,
        actions = listOf(
            PickerAction("Save as file", {
                vm.chooseFile()
                saveLauncher.launch(WebLibraryFile.fileName(System.currentTimeMillis()))
            }, primary = true),
        ) + sendActions,
        onDismiss = vm::closePicker,
        playsDetail = if (p.pick.plays >= WebLibraryFile.MAX_PLAYS) "your last %,d".format(WebLibraryFile.MAX_PLAYS) else null,
        busy = p.sending,
        message = p.message,
    )
    if (choosingTarget) {
        TargetChooser(p.targets, onPick = { choosingTarget = false; vm.send(it) }, onDismiss = { choosingTarget = false })
    }
}

@Composable
private fun TargetChooser(targets: List<SendTarget>, onPick: (SendTarget) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text("Send to", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                for (t in targets) TextButton(onClick = { onPick(t) }) { Text(t.name) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Import from Stash on the web": the picker with what the file holds, the wait, and what it added (spec §2.3). */
@Composable
fun WebLibraryImportSheets(vm: WebLibraryImportViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        WebLibraryImportState.Idle -> Unit
        is WebLibraryImportState.Choosing -> LibraryPickerSheet(
            title = "What to import",
            intro = WebLibraryImportViewModel.introOf(s.content),
            pick = s.pick,
            onPick = vm::updatePick,
            actions = listOf(PickerAction("Import", vm::import, primary = true)),
            onDismiss = vm::dismiss,
        )
        WebLibraryImportState.Reading, WebLibraryImportState.Importing -> AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text(if (s == WebLibraryImportState.Reading) "Reading the file" else "Adding to your library", style = MaterialTheme.typography.titleLarge) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is WebLibraryImportState.Done -> AlertDialog(
            onDismissRequest = vm::dismiss,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("Imported", style = MaterialTheme.typography.titleLarge) },
            text = { Text(WebLibraryImportViewModel.resultText(s.result), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } },
        )
        is WebLibraryImportState.Failed -> AlertDialog(
            onDismissRequest = vm::dismiss,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("Couldn't import", style = MaterialTheme.typography.titleLarge) },
            text = { Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } },
        )
    }
}

/** Working / done / sent / failed for "Export for Stash on the web". Not dismissable while the file is being written. */
@Composable
internal fun WebLibraryExportDialog(state: WebLibraryExportState, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = { if (state !is WebLibraryExportState.Working) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = stringResource(
                    when (state) {
                        is WebLibraryExportState.Done -> R.string.web_library_export_done_title
                        is WebLibraryExportState.Sent -> R.string.web_library_sent_title
                        WebLibraryExportState.Failed -> R.string.web_library_export_failed_title
                        else -> R.string.web_library_export_working_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            when (state) {
                is WebLibraryExportState.Done -> {
                    val r = state.result
                    Text(
                        text = stringResource(
                            R.string.web_library_export_done_body,
                            resources.getQuantityString(R.plurals.web_library_export_likes, r.likes, r.likes),
                            resources.getQuantityString(R.plurals.web_library_export_playlists, r.playlists, r.playlists),
                            resources.getQuantityString(R.plurals.web_library_export_plays, r.plays, r.plays),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is WebLibraryExportState.Sent -> {
                    val r = state.result
                    Text(
                        text = stringResource(
                            R.string.web_library_sent_body,
                            state.to,
                            resources.getQuantityString(R.plurals.web_library_export_likes, r.likes, r.likes),
                            resources.getQuantityString(R.plurals.web_library_export_playlists, r.playlists, r.playlists),
                            resources.getQuantityString(R.plurals.web_library_export_plays, r.plays, r.plays),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                WebLibraryExportState.Failed -> Text(
                    text = stringResource(R.string.web_library_export_failed_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (state !is WebLibraryExportState.Working) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.web_library_export_ok)) }
            }
        },
    )
}
