package com.stash.feature.record.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.Track
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.record.R
import com.stash.feature.record.bridge.text
import kotlinx.coroutines.launch

/** The page's side gutter (a playlist page's). */
private val Gutter = 20.dp

/**
 * One mix of Your record (stash-player `src/lib/ui/spotify/MixView.svelte`), laid out like a playlist: the cover mosaic, "Mix
 * from your record", its title and its line, how many songs and about how long; Play, Shuffle, Save as playlist; every song a
 * row that plays the mix from it. A normal screen: the mini player and the tab bar stay. A mix the taste no longer makes says so,
 * with the way back to the record ([onOpenRecord]).
 */
@Composable
fun RecordMixScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenRecord: () -> Unit,
    viewModel: RecordMixViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel) {
        launch { viewModel.lines.collect { launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } } }
        viewModel.playNotes.collect { launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it.text(resources)) } }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            RecordMixViewModel.State.Looking -> Unit
            RecordMixViewModel.State.Gone -> Gone(onBack, onOpenRecord)
            is RecordMixViewModel.State.Found -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomChromePadding(120.dp)),
            ) {
                item(key = "header") {
                    Header(
                        s = s,
                        saved = saved,
                        saving = saving,
                        onBack = onBack,
                        onPlay = { viewModel.play() },
                        onShuffle = viewModel::shuffle,
                        onSave = viewModel::save,
                        onOpenSaved = { saved?.let { onOpenPlaylist(it.id) } },
                    )
                }
                itemsIndexed(s.mix.items, key = { i, it -> "${it.key}#$i" }) { i, song ->
                    DetailTrackRow(
                        track = rowOf(song, s.covers.getOrNull(i), i),
                        trackNumber = i + 1,
                        isPlaying = false,
                        onClick = { viewModel.play(i) },
                        onLongPress = {},
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomChromePadding(8.dp)))
    }
}

/** A mix's song as a row shows it: your own row's words and length, else the history's; the cover found. */
private fun rowOf(s: MixSong, cover: String?, i: Int): Track = Track(
    id = s.lib?.trackId ?: -(i + 1L),
    title = s.title,
    artist = s.artist,
    album = s.album.orEmpty(),
    durationMs = s.lib?.durationMs ?: 0,
    albumArtUrl = cover,
    dateAdded = 0,
)

@Composable
private fun Header(
    s: RecordMixViewModel.State.Found,
    saved: RecordMixViewModel.Saved?,
    saving: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    val title = s.mix.title
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            MixTile(tileCovers(s.mix.items, s.covers), seed = title, modifier = Modifier.fillMaxSize())
            // The page's ground under the words, as a playlist page fades its cover
            Box(
                Modifier.fillMaxWidth().height(160.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier.statusBarsPadding().padding(start = 8.dp, top = 8.dp).align(Alignment.TopStart).size(48.dp)
                    .background(StashTheme.extendedColors.glassBackground, CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.record_mix_back), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
            Text(
                stringResource(R.string.record_mix_eyebrow).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.heading,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (s.line.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(s.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.record_mix_dot, pluralStringResource(R.plurals.record_mix_songs, s.mix.items.size, s.mix.items.size), s.length),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            val playLabel = stringResource(R.string.record_mix_play_label, title)
            val shuffleLabel = stringResource(R.string.record_mix_shuffle_label, title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f).semantics { contentDescription = playLabel },
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { IconLabel(Icons.Default.PlayArrow, stringResource(R.string.record_mix_play)) }
                OutlinedButton(
                    onClick = onShuffle,
                    modifier = Modifier.weight(1f).semantics { contentDescription = shuffleLabel },
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { IconLabel(Icons.Default.Shuffle, stringResource(R.string.record_mix_shuffle)) }
            }
            Spacer(Modifier.height(8.dp))
            if (saved != null) {
                TextButton(onClick = onOpenSaved, modifier = Modifier.fillMaxWidth()) {
                    IconLabel(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.record_mix_open_saved, saved.name))
                }
            } else {
                TextButton(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    IconLabel(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(if (saving) R.string.record_mix_saving else R.string.record_mix_save))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun IconLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** MixView.svelte's "no such mix": what happened, and the way back to Your record. */
@Composable
private fun Gone(onBack: () -> Unit, onOpenRecord: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.record_mix_back), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.record_mix_gone),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        TextButton(onClick = onOpenRecord, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.record_mix_gone_back))
        }
        Spacer(Modifier.weight(1.4f))
    }
}
