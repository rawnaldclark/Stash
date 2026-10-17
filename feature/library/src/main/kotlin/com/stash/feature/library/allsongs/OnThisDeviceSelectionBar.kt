package com.stash.feature.library.allsongs

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.extensions.pluralize
import com.stash.core.ui.components.ListenTogetherRole
import com.stash.core.ui.components.LocalListenTogetherRole
import com.stash.core.ui.selection.SelectionAction
import com.stash.core.ui.selection.SelectionScaffoldOverlay
import com.stash.core.ui.selection.SelectionState

/**
 * The bottom bar for songs selected on "On this device": Play next, Add to queue, Share (the files
 * themselves) and Delete (the files, after a confirm). Rendered by LibraryScreen while that chip is
 * the active one, over Library's one [SelectionState].
 */
@Composable
fun BoxScope.OnThisDeviceSelectionBar(
    selection: SelectionState,
    viewModel: AllSongsViewModel,
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // A search that hides selected songs must not leave them selected: Delete acts on what is shown.
    LaunchedEffect(query) { selection.clear() }
    // derivedStateOf, not remember keyed on the ids: it re-runs when the selection changes
    // whatever type selectedIds is.
    val selected by remember(songs) { derivedStateOf { songs.filter { it.id in selection.selectedIds } } }

    // Scanned songs can't be queued into a Listen Together session (they stay on this phone).
    val canQueue = LocalListenTogetherRole.current == ListenTogetherRole.NONE
    val actions = listOfNotNull(
        SelectionAction("play_next", "Play next", Icons.Default.PlaylistPlay) {
            viewModel.playNext(selected); selection.clear()
        }.takeIf { canQueue },
        SelectionAction("add_queue", "Add to queue", Icons.Default.PlaylistAdd) {
            viewModel.addToQueue(selected); selection.clear()
        }.takeIf { canQueue },
        SelectionAction("share", "Share", Icons.Default.Share) {
            val toShare = selected.take(MAX_SHARE)
            if (selected.size > MAX_SHARE) viewModel.say("Sharing the first $MAX_SHARE of ${selected.size}")
            if (!shareSongs(context, toShare)) viewModel.say("Couldn't open the share sheet")
            selection.clear()
        },
        SelectionAction("delete", "Delete", Icons.Default.Delete) {
            confirmDelete = true
        },
    )
    SelectionScaffoldOverlay(
        selection = selection,
        allIds = remember(songs) { songs.map { it.id } },
        actions = actions,
    )

    if (confirmDelete) {
        val doomed = selected
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${pluralize(doomed.size, "song")}?") },
            text = { Text("The files are deleted from your phone. This can't be undone.") },
            confirmButton = {
                TextButton(
                    enabled = doomed.isNotEmpty(),
                    onClick = {
                        viewModel.deleteSongs(doomed)
                        confirmDelete = false
                        selection.clear()
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}