package com.stash.feature.settings.weblink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/** One playlist in the picker: its key (the export's playlist id, or the file's), name, songs, and whether it's a shared mix. */
data class PickerPlaylist(val key: String, val name: String, val songs: Int, val sharedMix: Boolean)

/**
 * The picker's state (link-sync spec §2.3): what the library or file holds, and what is ticked. A part with nothing in it shows
 * unticked and can't be ticked.
 */
data class LibraryPick(
    val likes: Int,
    val plays: Int,
    val playlists: List<PickerPlaylist>,
    val likesOn: Boolean = true,
    val playsOn: Boolean = true,
    val ticked: Set<String> = playlists.mapTo(HashSet()) { it.key },
) {
    val takesLikes: Boolean get() = likesOn && likes > 0
    val takesPlays: Boolean get() = playsOn && plays > 0
    val tickedPlaylists: List<PickerPlaylist> get() = playlists.filter { it.key in ticked }

    /** Nothing ticked that holds anything: Save and Send wait. */
    val isEmpty: Boolean get() = !takesLikes && !takesPlays && tickedPlaylists.isEmpty()
}

/** A button at the bottom of the picker ("Save as file", "Send to Chrome on Windows", "Import"). */
data class PickerAction(val label: String, val onClick: () -> Unit, val primary: Boolean = false)

/**
 * "What to include" (spec §2.3), shared by Export for Stash on the web, Send my library to a browser, Import from Stash on the
 * web and a received send: Likes and Plays with their counts, and Playlists › a checklist with All / None. [intro] is the line
 * above ("This file has 1,204 likes, 5,000 plays and 14 playlists").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPickerSheet(
    title: String,
    pick: LibraryPick,
    onPick: (LibraryPick) -> Unit,
    actions: List<PickerAction>,
    onDismiss: () -> Unit,
    intro: String? = null,
    /** "your last 5,000" on the export (spec §2.3); the count otherwise. */
    playsDetail: String? = null,
    busy: Boolean = false,
    message: String? = null,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            if (choosing) {
                BackHandler { choosing = false }
                PlaylistChecklist(pick, onPick, onBack = { choosing = false })
                return@Column
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            intro?.let {
                Spacer(Modifier.padding(top = 4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.padding(top = 8.dp))
            CheckRow(
                label = "Likes",
                detail = count(pick.likes),
                checked = pick.takesLikes,
                enabled = pick.likes > 0 && !busy,
                onChange = { onPick(pick.copy(likesOn = it)) },
            )
            CheckRow(
                label = "Plays",
                detail = playsDetail ?: count(pick.plays),
                checked = pick.takesPlays,
                enabled = pick.plays > 0 && !busy,
                onChange = { onPick(pick.copy(playsOn = it)) },
            )
            val n = pick.playlists.size
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(enabled = n > 0 && !busy) { choosing = true }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Playlists", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    if (n == 0) "none" else "${pick.tickedPlaylists.size} of $n",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // No chevron on an empty row: there is nothing to open.
                if (n > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Choose playlists", tint = StashTheme.extendedColors.textTertiary)
                else Spacer(Modifier.width(24.dp))
            }
            message?.let {
                Spacer(Modifier.padding(top = 8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (busy) {
                Spacer(Modifier.padding(top = 8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.padding(top = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in actions) {
                    if (a.primary) {
                        Button(onClick = a.onClick, enabled = !busy && !pick.isEmpty, modifier = Modifier.fillMaxWidth()) {
                            Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        OutlinedButton(onClick = a.onClick, enabled = !busy && !pick.isEmpty, modifier = Modifier.fillMaxWidth()) {
                            Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(24.dp))
    }
}

/** Playlists ›: each with its song count, a followed shared mix labelled, All / None at the top. */
@Composable
private fun PlaylistChecklist(pick: LibraryPick, onPick: (LibraryPick) -> Unit, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text("Playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onPick(pick.copy(ticked = pick.playlists.mapTo(HashSet()) { it.key })) }) { Text("All") }
        TextButton(onClick = { onPick(pick.copy(ticked = emptySet())) }) { Text("None") }
    }
    HorizontalDivider(color = StashTheme.extendedColors.glassBorder)
    LazyColumn(Modifier.heightIn(max = 480.dp)) {
        items(pick.playlists, key = { it.key }) { p ->
            val on = p.key in pick.ticked
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = on, role = Role.Checkbox) { checked ->
                        onPick(pick.copy(ticked = if (checked) pick.ticked + p.key else pick.ticked - p.key))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(if (p.songs == 1) "1 song" else "%,d songs".format(p.songs))
                            if (p.sharedMix) append(" · Shared mix")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun count(n: Int) = "%,d".format(n)
