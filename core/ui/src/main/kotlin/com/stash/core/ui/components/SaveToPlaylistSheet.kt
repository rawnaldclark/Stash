package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.common.extensions.pluralize
import java.text.Normalizer
import java.util.Locale

/**
 * Lightweight model for the playlist picker. Keeps the UI component
 * independent of the full [com.stash.core.model.Playlist] model.
 */
data class PlaylistInfo(
    val id: Long,
    val name: String,
    val trackCount: Int,
)

/**
 * With fewer playlists than this the whole list fits on screen without
 * scrolling (rows are ~64dp, the sheet opens about half-height), so a search
 * field would only add clutter. From here on, finding one by scrolling is slower
 * than typing.
 */
internal const val PLAYLIST_SEARCH_MIN_PLAYLISTS = 8

/** Lowercase and strip accents, so "cafe" finds "Café" and "BEYONCE" finds "Beyoncé". */
internal fun String.foldForSearch(): String =
    Normalizer.normalize(trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

/** The playlists whose name contains [query] (case- and accent-insensitive); all of them when it is blank. */
internal fun filterPlaylists(playlists: List<PlaylistInfo>, query: String): List<PlaylistInfo> {
    val needle = query.foldForSearch()
    if (needle.isEmpty()) return playlists
    return playlists.filter { it.name.foldForSearch().contains(needle) }
}

/**
 * Bottom sheet that lets the user save a track to an existing custom
 * playlist or create a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToPlaylistSheet(
    playlists: List<PlaylistInfo>,
    onSaveToPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val showSearch = playlists.size >= PLAYLIST_SEARCH_MIN_PLAYLISTS
    // Ignore any typed text if the list shrinks below the threshold (the field is gone).
    // With the search field the sheet opens full height: typing needs the room above the keyboard,
    // and a half-height sheet would hide the first matches behind the field and the Create row.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = showSearch)
    val visiblePlaylists = if (showSearch) filterPlaylists(playlists, query) else playlists

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Title + divider stay above the scroll region so the user always
        // knows what sheet they're on.
        Text(
            text = "Save to Playlist",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (showSearch) {
            SearchFilterBar(
                query = query,
                onQueryChanged = { query = it },
                onClear = { query = "" },
                placeholder = "Search playlists",
                autoFocus = false,
            )
        }

        // LazyColumn (not a plain Column) so the list scrolls cleanly when
        // the user has many playlists. A static Column inside ModalBottomSheet
        // clips overflow — the bug that hid the "Create New Playlist" row.
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // "Create New Playlist" pinned at the top so it's always the
            // first option the user sees, no scrolling required. Matches the
            // Spotify / YT Music convention for playlist pickers.
            item(key = "create-new") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCreateDialog = true }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Create New Playlist",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (visiblePlaylists.isEmpty()) {
                item(key = "no-matches") {
                    Text(
                        text = "No playlists match \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    )
                }
            }

            items(visiblePlaylists, key = { it.id }) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSaveToPlaylist(playlist.id)
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = pluralize(playlist.trackCount, "track"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                onCreatePlaylist(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

/**
 * Simple dialog for naming a new playlist.
 */
@Composable
fun CreatePlaylistDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
