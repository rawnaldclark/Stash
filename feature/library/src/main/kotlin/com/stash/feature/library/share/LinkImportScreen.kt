package com.stash.feature.library.share

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.extensions.pluralize
import com.stash.core.data.link.LinkKind
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatDuration
import com.stash.core.ui.util.formatTotalDuration

/**
 * A pasted or shared Spotify / YouTube link: its cover full width as a shared mix shows it, what it is and how long, a
 * Download switch and Add to library, then its songs. Once added, Open goes to the playlist it became.
 */
@Composable
fun LinkImportScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: LinkImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = when (val st = state) {
        LinkImportUiState.Loading -> {
            BareScreen(onBack) { CircularProgressIndicator() }
            return
        }
        is LinkImportUiState.Failed -> {
            BareScreen(onBack) {
                Text(st.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (st.retryable) { Spacer(Modifier.height(16.dp)); Button(onClick = viewModel::load) { Text("Retry") } }
            }
            return
        }
        is LinkImportUiState.Ready -> st
    }
    val p = s.preview
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
        item {
            SharedHeroCover(listOfNotNull(p.coverUrl), onBack)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    "${p.service} ${kindWord(p.kind)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    p.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val total = p.songs.sumOf { it.durationMs ?: 0L }
                Text(
                    listOfNotNull(
                        p.byline,
                        pluralize(p.songs.size, "song").takeIf { p.kind != LinkKind.SONG },
                        total.takeIf { it > 0 }?.let { if (p.kind == LinkKind.SONG) formatDuration(it) else formatTotalDuration(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.partial) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (p.service == "Spotify") {
                            "Only the first ${p.songs.size} songs could be read. Connect Spotify in Settings to add the whole playlist."
                        } else {
                            "Only the first ${p.songs.size} songs could be read."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StashTheme.extendedColors.textTertiary,
                    )
                }
                Spacer(Modifier.height(16.dp))
                DownloadRow(
                    on = s.download,
                    enabled = s.progress == null && s.added == null,
                    kind = p.kind,
                    onChange = viewModel::setDownload,
                )
                Spacer(Modifier.height(12.dp))
                Actions(s, onOpenPlaylist, viewModel::add)
                if (p.fromList != null && s.added == null && s.progress == null) {
                    TextButton(onClick = viewModel::openList) { Text("Add the whole playlist instead") }
                }
                s.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (p.kind != LinkKind.SONG) itemsIndexed(p.songs) { i, song -> LinkSongRow(i + 1, song) }
    }
}

private fun kindWord(kind: LinkKind) = when (kind) {
    LinkKind.PLAYLIST -> "playlist"
    LinkKind.ALBUM -> "album"
    LinkKind.SONG -> "song"
}

/** Download: keep the playlist it becomes on this phone (its Download switch, turned on with the add). */
@Composable
private fun DownloadRow(on: Boolean, enabled: Boolean, kind: LinkKind, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Download", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (kind == LinkKind.SONG) "Keep songs from links on this phone" else "Keep these songs on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Actions(s: LinkImportUiState.Ready, onOpenPlaylist: (Long) -> Unit, onAdd: () -> Unit) {
    val progress = s.progress
    val added = s.added
    when {
        progress != null -> Column {
            val (done, total) = progress
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Adding… $done of ${pluralize(total, "song")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        added != null -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (added.songsAdded > 0) "Added ${pluralize(added.songsAdded, "song")} to your library" else "Already all in your library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onOpenPlaylist(added.playlistId) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Open in your library") }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onAdd,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                // In the library already (pasted before): adding again brings only what it lacks.
                Text(if (s.existingId != null && s.preview.kind != LinkKind.SONG) "Add what's new" else "Add to library")
            }
            if (s.existingId != null) {
                OutlinedButton(
                    onClick = { onOpenPlaylist(s.existingId) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Open") }
            }
        }
    }
}

/** A song of the link: its number, cover, title and artist, and length. */
@Composable
private fun LinkSongRow(number: Int, song: WireSong) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.bodyMedium,
            color = StashTheme.extendedColors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(24.dp),
        )
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))) {
            SharedCover(song.artwork.take(1), Modifier.fillMaxSize(), quiet = true)
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        song.durationMs?.takeIf { it > 0 }?.let {
            Text(formatDuration(it), style = MaterialTheme.typography.bodySmall, color = StashTheme.extendedColors.textTertiary)
        }
    }
}
