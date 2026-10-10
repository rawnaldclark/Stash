package com.stash.feature.settings.weblink

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.weblink.mirror.Dir
import com.stash.core.data.weblink.mirror.FirstMergeChoice
import com.stash.core.data.weblink.mirror.Kind
import com.stash.core.data.weblink.mirror.LikesQuestion
import com.stash.core.data.weblink.mirror.LocalPlaylist
import com.stash.core.data.weblink.mirror.MirrorChange
import com.stash.core.data.weblink.mirror.MirrorEngine
import com.stash.core.data.weblink.mirror.MirrorLibrary
import com.stash.core.data.weblink.mirror.MirrorRun
import com.stash.core.data.weblink.mirror.MirrorScheduler
import com.stash.core.data.weblink.mirror.MirrorStatus
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsSectionLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings › Link Stash on the web › Mirror between your devices (spec §2.4): per kind Off / Both ways / Phone → web / Web →
 * phone, Choose playlists (+ Mirror new playlists too), and the first-merge question for likes. The settings belong to the link:
 * changing them here changes them for every device.
 */
@HiltViewModel
class MirrorViewModel @Inject constructor(
    private val engine: MirrorEngine,
    private val library: MirrorLibrary,
    private val scheduler: MirrorScheduler,
) : ViewModel() {
    val status: StateFlow<MirrorStatus> = engine.status

    private val _playlists = MutableStateFlow<List<LocalPlaylist>?>(null)

    /** This phone's playlists for the chooser (null while they load). */
    val playlists: StateFlow<List<LocalPlaylist>?> = _playlists.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Reads the saved state, then runs once (the screen came back). */
    fun refresh() {
        viewModelScope.launch {
            engine.load()
            if (engine.status.value.config?.anyOn == true) engine.sync()
        }
    }

    fun setDir(kind: Kind, dir: Dir) = act { engine.configure(MirrorChange(dirs = mapOf(kind to dir))) }

    fun loadPlaylists() {
        viewModelScope.launch { _playlists.value = runCatching { library.playlists() }.getOrDefault(emptyList()) }
    }

    fun setPlaylist(id: Long, on: Boolean) = act {
        engine.configure(if (on) MirrorChange(add = listOf(id)) else MirrorChange(remove = listOf(id)))
    }

    fun setNewOnes(on: Boolean) = act { engine.configure(MirrorChange(newOnes = on)) }

    fun answer(choice: FirstMergeChoice) = act { engine.answer(choice) }

    fun messageShown() {
        _message.value = null
    }

    private fun act(block: suspend () -> MirrorRun) {
        viewModelScope.launch {
            when (val r = block()) {
                is MirrorRun.Failed -> _message.value = r.message
                is MirrorRun.Retry -> {
                    _message.value = r.message
                    scheduler.changed() // it goes when the network is back
                }
                else -> Unit
            }
        }
    }
}

fun dirLabel(d: Dir): String = when (d) {
    Dir.OFF -> "Off"
    Dir.BOTH -> "Both ways"
    Dir.TO_WEB -> "Phone → web"
    Dir.TO_PHONE -> "Web → phone"
}

/** The block under Linked devices. */
@Composable
fun MirrorSection(status: MirrorStatus, vm: MirrorViewModel) {
    var picking by remember { mutableStateOf<Kind?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val cfg = status.config
    SettingsSectionLabel("Mirror between your devices")
    Text(
        "Off keeps a separate library on each. What mirrors is end-to-end encrypted.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val rows = mutableListOf<@Composable () -> Unit>()
    rows += {
        SettingsNavRow(
            title = "Likes",
            subtitle = dirLabel(cfg?.likes?.dir ?: Dir.OFF) + when {
                status.waiting -> " · Waiting for ${status.otherName} to share its likes."
                status.question != null -> " · Choose how to combine them"
                else -> ""
            },
            leadingIcon = Icons.Outlined.Favorite,
            onClick = { picking = Kind.LIKES },
        )
    }
    rows += {
        val n = cfg?.ids?.size ?: 0
        SettingsNavRow(
            title = "Playlists",
            subtitle = dirLabel(cfg?.playlists?.dir ?: Dir.OFF) + if (cfg?.playlists?.dir != Dir.OFF && cfg != null) " · $n chosen" else "",
            leadingIcon = Icons.AutoMirrored.Outlined.QueueMusic,
            onClick = { picking = Kind.PLAYLISTS },
        )
    }
    if (cfg != null && cfg.playlists.dir != Dir.OFF) {
        rows += {
            SettingsNavRow(
                title = "Choose playlists",
                subtitle = "Spotify and YouTube Music playlists go to the web only, read-only there.",
                onClick = {
                    vm.loadPlaylists()
                    choosing = true
                },
            )
        }
    }
    rows += {
        SettingsNavRow(
            title = "Plays",
            subtitle = dirLabel(cfg?.plays?.dir ?: Dir.OFF) + if (cfg?.plays?.dir?.let { it == Dir.BOTH || it == Dir.TO_PHONE } == true) " · Plays from the web show in History, never in your Stash Mixes" else "",
            leadingIcon = Icons.Outlined.History,
            onClick = { picking = Kind.PLAYS },
        )
    }
    SettingsGroupCard(rows = rows)
    status.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    picking?.let { kind ->
        DirDialog(
            kind = kind,
            current = cfg?.of(kind)?.dir ?: Dir.OFF,
            onPick = {
                picking = null
                vm.setDir(kind, it)
            },
            onDismiss = { picking = null },
        )
    }
    if (choosing) {
        val list by vm.playlists.collectAsStateWithLifecycle()
        PlaylistChooser(
            playlists = list,
            chosen = status.mirrored.filterKeys { cfg?.ids?.contains(it) == true }.values.toSet(),
            newOnes = cfg?.newOnes == true,
            onToggle = vm::setPlaylist,
            onNewOnes = vm::setNewOnes,
            onDismiss = { choosing = false },
        )
    }
    status.question?.let { q -> FirstMergeDialog(q, onAnswer = vm::answer) }
}

@Composable
private fun DirDialog(kind: Kind, current: Dir, onPick: (Dir) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(kind.wire.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                for (d in Dir.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = d == current, onClick = { onPick(d) })
                        Text(dirLabel(d), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (kind == Kind.PLAYLISTS) {
                    Text(
                        "Playlists that sync from Spotify or YouTube Music go phone → web only, whatever this says.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PlaylistChooser(
    playlists: List<LocalPlaylist>?,
    chosen: Set<Long>,
    newOnes: Boolean,
    onToggle: (Long, Boolean) -> Unit,
    onNewOnes: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text("Choose playlists", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mirror new playlists too", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = newOnes, onCheckedChange = onNewOnes)
                }
                if (playlists == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(playlists, key = { it.id }) { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        buildString {
                                            append(if (p.songs == 1) "1 song" else "${p.songs} songs")
                                            if (p.ro) append(" · From your phone, read-only on the web")
                                            if (p.follow != null) append(" · Shared mix")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = p.id in chosen, onCheckedChange = { onToggle(p.id, it) })
                            }
                        }
                    }
                    Text(
                        "Switching one off stops mirroring it and leaves every copy where it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * "Your likes: 212 on Chrome on Windows, 1,204 here" (spec §2.4). Combine is preselected; a choice that removes says how many, in
 * red, and needs a second tap.
 */
@Composable
private fun FirstMergeDialog(q: LikesQuestion, onAnswer: (FirstMergeChoice) -> Unit) {
    var choice by remember(q.since) { mutableStateOf(FirstMergeChoice.COMBINE) }
    var confirming by remember(q.since) { mutableStateOf(false) }
    // "Use the browser's" removes the phone's own extras (the Spotify / YouTube Music ones stay liked); "Use this phone's" removes
    // the browser's extras there.
    val removesHere = (q.here - q.both - q.externalHere).coerceAtLeast(0)
    val removesThere = q.there - q.both
    AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text("Your likes: ${q.there} on ${q.thereName}, ${q.here} here", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                @Composable
                fun option(c: FirstMergeChoice, title: String, detail: String, red: Boolean = false) {
                    Row(Modifier.fillMaxWidth().clickable { choice = c; confirming = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(selected = choice == c, onClick = { choice = c; confirming = false })
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (q.oneWay) {
                    option(FirstMergeChoice.COMBINE, "Add ${q.thereName}'s to this phone's", "${q.combined} likes here")
                    option(FirstMergeChoice.THEIRS, "Replace this phone's with ${q.thereName}'s", "Removes $removesHere likes here", red = removesHere > 0)
                } else {
                    option(FirstMergeChoice.COMBINE, "Combine them", "Keep both, ${q.combined} likes everywhere")
                    option(FirstMergeChoice.THEIRS, "Use ${q.thereName}'s", "Removes $removesHere likes here", red = removesHere > 0)
                    option(FirstMergeChoice.MINE, "Use this phone's", "Removes $removesThere likes on ${q.thereName}", red = removesThere > 0)
                }
                if (q.externalHere > 0) {
                    Text(
                        "Likes from Spotify or YouTube Music stay liked on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            val removes = (choice == FirstMergeChoice.THEIRS && removesHere > 0) || (choice == FirstMergeChoice.MINE && removesThere > 0)
            TextButton(
                onClick = {
                    if (removes && !confirming) confirming = true else onAnswer(choice)
                },
                colors = if (confirming) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(if (confirming) "Yes, remove them" else "Turn on") }
        },
    )
}
