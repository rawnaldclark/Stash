package com.stash.feature.library.allsongs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.extensions.pluralize
import com.stash.core.ui.components.TrackListItem
import com.stash.core.ui.components.TrackRowFitScope
import com.stash.core.ui.components.VerticalScrollbar
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.selection.SelectionState

/**
 * Library's "On this device" page: the songs found in the folders the user chose. Library's header
 * owns search, sort and shuffle (they reach [viewModel] through LibraryScreen); this page owns the
 * list, the folder management and the scan status. Selection is Library's one [SelectionState];
 * its bar is [OnThisDeviceSelectionBar].
 */
@Composable
fun OnThisDeviceTab(
    viewModel: AllSongsViewModel,
    selection: SelectionState,
    alphabetical: Boolean,
    onClearSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var showFolders by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.rescanOnOpen() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (folders.isEmpty()) "Songs from folders you choose" else pluralize(songs.size, "song"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showFolders = true }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Folders")
            }
        }
        if (scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (songs.isEmpty() && !scanning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp)
                    .padding(bottom = bottomChromePadding(0.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (query.isNotBlank()) {
                    Text(
                        text = "No songs match \"$query\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearSearch) { Text("Clear search") }
                } else {
                    Text(
                        text = if (folders.isEmpty()) "Pick the folders to scan for music" else "No songs found in your folders",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { if (folders.isEmpty()) picker.launch(null) else showFolders = true }) {
                        Text(if (folders.isEmpty()) "Choose folders" else "Manage folders")
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TrackRowFitScope {
                    LazyColumn(
                        state = listState,
                        // While selecting, the bottom selection bar takes the mini player's place.
                        contentPadding = PaddingValues(
                            bottom = bottomChromePadding(if (selection.isActive) 140.dp else 0.dp),
                        ),
                    ) {
                        items(songs, key = { it.id }) { track ->
                            TrackListItem(
                                track = track,
                                onClick = {
                                    if (selection.isActive) selection.toggle(track.id)
                                    else viewModel.play(track.id)
                                },
                                isPlaying = track.id == playingId,
                                onLongPress = { if (!selection.isActive) selection.enter(track.id) },
                                selectionActive = selection.isActive,
                                selected = selection.isSelected(track.id),
                            )
                        }
                    }
                }
                // The letter bubble only means something when the list runs A–Z.
                VerticalScrollbar(
                    state = listState,
                    sectionLabel = if (alphabetical) titleSectionLabel(songs) else null,
                )
            }
        }
    }

    if (showFolders) {
        FoldersDialog(
            folders = folders,
            scanning = scanning,
            onAddFolder = { picker.launch(null) },
            onRemoveFolder = viewModel::removeFolder,
            onRescan = viewModel::rescan,
            onDismiss = { showFolders = false },
        )
    }
}