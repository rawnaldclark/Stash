package com.stash.feature.settings.libraryhealth

import android.text.format.Formatter
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.library.duplicates.DuplicateKind
import com.stash.core.model.Track
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.TrackListItem
import com.stash.feature.settings.R

/**
 * Library Health → Possible duplicates: pairs of rows that may be one song, each with the two rows
 * (art, source dots, length, downloaded state, a FLAC badge), a tick on the row to keep, Merge and
 * "Not a duplicate". A merge that would delete a file asks once first ("Remove the extra copy?").
 */
@Composable
fun PossibleDuplicatesScreen(
    onNavigateBack: () -> Unit,
    viewModel: PossibleDuplicatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = when (message) {
            DuplicatesMessage.MERGED -> R.string.settings_duplicates_merged
            DuplicatesMessage.MERGED_FILE_REMOVED -> R.string.settings_duplicates_merged_file_removed
            DuplicatesMessage.NOT_MERGED -> R.string.settings_duplicates_not_merged
            DuplicatesMessage.DISMISSED -> R.string.settings_duplicates_dismissed
        }
        viewModel.messageShown()
        snackbarHostState.showSnackbar(context.getString(text))
    }

    state.confirming?.let { pair ->
        val going = pair.going
        val size = if (going.fileSizeBytes > 0) Formatter.formatShortFileSize(context, going.fileSizeBytes) else null
        val what = listOfNotNull(going.fileFormat.uppercase(), size).joinToString(", ")
        AlertDialog(
            onDismissRequest = viewModel::cancelConfirm,
            title = { Text(stringResource(R.string.settings_duplicates_confirm_title)) },
            text = { Text(stringResource(R.string.settings_duplicates_confirm_text, pair.kept.title, what)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMerge) { Text(stringResource(R.string.settings_duplicates_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelConfirm) { Text(stringResource(R.string.settings_duplicates_cancel)) }
            },
        )
    }

    // Transparent: the NavHost already paints the app's background.
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { Header(onBack = onNavigateBack) }
            item {
                Text(
                    text = stringResource(R.string.settings_duplicates_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            state.lastRun?.let { run ->
                item {
                    Text(
                        text = lastRunText(run),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            when {
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.pairs.isEmpty() -> item {
                    GlassCard {
                        Text(
                            text = stringResource(R.string.settings_duplicates_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                        )
                    }
                }
                else -> items(state.pairs, key = { it.key }) { pair ->
                    PairCard(
                        pair = pair,
                        working = pair.key in state.working,
                        onKeep = { viewModel.chooseKeeper(pair.key, it) },
                        onMerge = { viewModel.merge(pair.key) },
                        onNotDuplicate = { viewModel.notADuplicate(pair.key) },
                    )
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun PairCard(
    pair: PairUi,
    working: Boolean,
    onKeep: (Long) -> Unit,
    onMerge: () -> Unit,
    onNotDuplicate: () -> Unit,
) {
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                text = stringResource(kindText(pair.kind)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            KeepRow(track = pair.first, keep = pair.keepId == pair.first.id, mayKeep = pair.mayKeep(pair.first.id), onKeep = onKeep)
            KeepRow(track = pair.second, keep = pair.keepId == pair.second.id, mayKeep = pair.mayKeep(pair.second.id), onKeep = onKeep)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onNotDuplicate, enabled = !working) {
                    Text(stringResource(R.string.settings_duplicates_not_duplicate))
                }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onMerge, enabled = !working) {
                    Text(stringResource(R.string.settings_duplicates_merge))
                }
            }
        }
    }
}

/** One row of a pair; its tick marks the row to keep. A row holding the song's only file can't be unticked. */
@Composable
private fun KeepRow(track: Track, keep: Boolean, mayKeep: Boolean, onKeep: (Long) -> Unit) {
    TrackListItem(
        track = track,
        onClick = { if (mayKeep) onKeep(track.id) },
        selectionActive = true,
        selected = keep,
        showAvailabilityTag = true,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.settings_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.settings_duplicates_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Tidied on <date>: N merged, M unused mix songs removed." */
@Composable
internal fun lastRunText(run: com.stash.core.data.library.duplicates.DuplicateCleanupState.LastRun): String {
    val context = LocalContext.current
    val date = android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(run.at))
    return stringResource(R.string.settings_duplicates_last_run, date, run.merged, run.removed)
}

private fun kindText(kind: DuplicateKind): Int = when (kind) {
    DuplicateKind.SAME_SONG -> R.string.settings_duplicates_kind_same_song
    DuplicateKind.TWO_UPLOADS -> R.string.settings_duplicates_kind_two_uploads
    DuplicateKind.TWO_FILES -> R.string.settings_duplicates_kind_two_files
    DuplicateKind.VERSION_WORDS -> R.string.settings_duplicates_kind_version_words
}
