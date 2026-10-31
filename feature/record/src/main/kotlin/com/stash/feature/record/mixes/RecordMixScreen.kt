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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import com.stash.core.ui.components.bottomChromePadding
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
    val hearts by viewModel.hearts.collectAsStateWithLifecycle(initialValue = null)
    var menuFor by remember { mutableStateOf<MenuSong?>(null) }
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    // The words in the screen's language (the app's own language setting reaches the Activity, not the Application)
    val locales = LocalConfiguration.current.locales
    val context = LocalContext.current
    LaunchedEffect(locales) { viewModel.useWords(locales.toLanguageTags(), ResourceMixWords(context)) }
    LaunchedEffect(viewModel) {
        launch {
            viewModel.lines.collect { line ->
                val text = when (line) {
                    is RecordMixViewModel.Line.Saved -> resources.getString(R.string.record_mix_saved, line.name)
                    RecordMixViewModel.Line.SaveFailed -> resources.getString(R.string.record_mix_save_failed)
                }
                launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
            }
        }
        launch { viewModel.songLines.collect { launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it.text(resources)) } } }
        viewModel.playNotes.collect { launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it.text(resources)) } }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            // The way back always shows: while the mix is made, and when it can't be
            RecordMixViewModel.State.Looking -> Message(onBack, text = null, action = null, onAction = {})
            RecordMixViewModel.State.Gone ->
                Message(onBack, stringResource(R.string.record_mix_gone), stringResource(R.string.record_mix_gone_back), onOpenRecord)
            RecordMixViewModel.State.Failed ->
                Message(onBack, stringResource(R.string.record_mix_failed), stringResource(R.string.record_error_try_again), viewModel::retry)
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
                    // MixView.svelte's rows: play the mix from here, Like, and the song menu (a long press)
                    val liked = hearts?.liked(song) == true
                    RecordSongRow(
                        song = song,
                        cover = s.covers.getOrNull(i),
                        number = i + 1,
                        square = RowSquare.NOTE,
                        liked = liked,
                        colors = RowColorsOf.app(),
                        onPlay = { viewModel.play(i) },
                        onLike = { viewModel.toggleLike(i, liked) },
                        onMenu = { menuFor = viewModel.menuFor(i) },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomChromePadding(8.dp)))
    }
    RecordSongMenu(menuFor, viewModel.actions, onDismiss = { menuFor = null })
}

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
            // A dark disc under a white arrow: a mix's mosaic is often light (a playlist page's glass vanished on Rumours)
            IconButton(
                onClick = onBack,
                modifier = Modifier.statusBarsPadding().padding(start = 8.dp, top = 8.dp).align(Alignment.TopStart).size(48.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.record_mix_back), tint = Color.White)
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

/**
 * The page with no mix to show: the way back always, and what happened with what to do (MixView.svelte's "no such mix" and its
 * way back to Your record; a read that failed and Try again). Nothing but the way back while the mix is made.
 */
@Composable
private fun Message(onBack: () -> Unit, text: String?, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.record_mix_back), tint = MaterialTheme.colorScheme.onSurface)
        }
        if (text != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            if (action != null) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(action) }
            }
            Spacer(Modifier.weight(1.4f))
        }
    }
}
