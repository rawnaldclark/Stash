package com.stash.feature.community.sessions

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.community.Station
import com.stash.core.model.share.ShareLinks
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.MinTouch
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.components.personColor
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.community.CommunitySnackbar
import com.stash.feature.community.ErrorCard
import com.stash.feature.community.R
import com.stash.feature.community.RowSkeleton
import com.stash.feature.community.StateCard
import java.time.ZoneId

/**
 * Your station (UX spec 2026-11-20 §3, the Your station mockup): its face, name, followers and votes; its link,
 * with Share; its vibe line; Go live now; then when it would be deleted, and Delete. A station hidden by votes or taken
 * down says so. The hours it goes live by itself, Auto-accept and regulars arrive with later steps.
 */
@Composable
fun YourStationScreen(
    onBack: () -> Unit,
    onGoLive: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: YourStationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    CommunitySnackbar(state.message, viewModel::messageShown)
    // Deleted: there's nothing left to show.
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    val onAir = (room as? ListenTogetherState.InRoom)?.let { it.session != null && it.isHost } == true
    var editing by rememberSaveable { mutableStateOf<Field?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.community_back))
            }
            Text(
                stringResource(R.string.sessions_your_station),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        val gutter = Modifier.padding(horizontal = 20.dp)
        val station = state.station
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = bottomChromePadding(120.dp)),
        ) {
            when {
                station != null -> StationBody(
                    station = station,
                    onAir = onAir,
                    busy = state.saving,
                    onRename = { editing = Field.NAME },
                    onEditVibe = { editing = Field.VIBE },
                    onGoLive = if (onAir) onOpenPlayer else onGoLive,
                    onDelete = viewModel::askDelete,
                )
                state.loading -> repeat(3) { RowSkeleton() }
                state.failed -> ErrorCard(onRetry = viewModel::load, modifier = gutter)
                else -> StateCard(
                    icon = Icons.Rounded.Podcasts,
                    title = stringResource(R.string.station_none_title),
                    body = stringResource(R.string.station_none_body),
                    action = stringResource(R.string.sessions_go_live),
                    onAction = onGoLive,
                    modifier = gutter,
                )
            }
        }
    }

    val station = state.station
    when (editing) {
        Field.NAME -> if (station != null) {
            EditDialog(
                title = stringResource(R.string.station_rename_title),
                label = stringResource(R.string.station_name_label),
                initial = station.name,
                max = NAME_MAX,
                allowBlank = false,
                onDismiss = { editing = null },
                onSave = { viewModel.rename(it); editing = null },
            )
        }
        Field.VIBE -> if (station != null) {
            EditDialog(
                title = stringResource(R.string.station_vibe_title),
                label = stringResource(R.string.station_vibe_label),
                initial = station.vibe.orEmpty(),
                max = VIBE_MAX,
                allowBlank = true,
                onDismiss = { editing = null },
                onSave = { viewModel.setVibe(it); editing = null },
            )
        }
        null -> Unit
    }
    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.station_delete_title)) },
            text = { Text(stringResource(if (onAir) R.string.station_delete_body_live else R.string.station_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) { Text(stringResource(R.string.station_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.station_cancel)) } },
        )
    }
}

private enum class Field { NAME, VIBE }

/** The Worker's limits (UTF-16 units, as String.length counts). */
private const val NAME_MAX = 40
private const val VIBE_MAX = 60

@Composable
private fun StationBody(
    station: Station,
    onAir: Boolean,
    busy: Boolean,
    onRename: () -> Unit,
    onEditVibe: () -> Unit,
    onGoLive: () -> Unit,
    onDelete: () -> Unit,
) {
    val ext = StashTheme.extendedColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = MaterialTheme.shapes.extraLarge
    val context = LocalContext.current
    val config = LocalConfiguration.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (stationStanding(station)) {
            StationStanding.HIDDEN -> Notice(stringResource(R.string.station_hidden))
            StationStanding.REMOVED -> Notice(stringResource(R.string.station_removed))
            StationStanding.FINE -> Unit
        }
        Column(Modifier.fillMaxWidth().clip(shape).background(ext.glassBackground).border(1.dp, ext.glassBorder, shape).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(personColor(station.id)), contentAlignment = Alignment.Center) {
                    Text(station.initial(), color = FaceInk, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics { })
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        station.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pluralStringResource(R.plurals.station_followers, station.followers, station.followers) +
                            " · " + stringResource(R.string.station_votes, station.up, station.down),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SmallButton(stringResource(R.string.station_rename), enabled = !busy, onClick = onRename)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(ext.glassBackground).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stationLinkText(station.id),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val shareText = stringResource(R.string.station_share_text, station.name, ShareLinks.stationUrl(station.id))
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                }) { Text(stringResource(R.string.station_share)) }
            }
        }
        // The vibe line: what Live now's card and the room say under the station's name.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ext.glassBackground)
                .border(1.dp, ext.glassBorder, shape)
                .clickable(enabled = !busy, role = Role.Button, onClick = onEditVibe)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.station_vibe_label), style = MaterialTheme.typography.labelMedium, color = muted)
                Spacer(Modifier.height(2.dp))
                val vibe = station.vibe?.takeIf { it.isNotBlank() }
                Text(
                    vibe?.let { stringResource(R.string.sessions_vibe, it) } ?: stringResource(R.string.station_vibe_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (vibe != null) MaterialTheme.colorScheme.onSurface else muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.station_edit),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        GoLiveButton(onAir = onAir, enabled = !busy && !station.removed, onClick = onGoLive)
        Spacer(Modifier.height(8.dp))
        // Not while it's on the air: being live is what keeps it.
        deleteDay(station.deleteAt.takeIf { !onAir }, ZoneId.systemDefault(), config.locales[0])?.let { day ->
            Text(
                stringResource(R.string.station_delete_note, day),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), CircleShape)
                .clickable(enabled = !busy, role = Role.Button, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.station_delete),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** A line the owner should see first: hidden by votes, or taken down. */
@Composable
private fun Notice(text: String) {
    val shape = MaterialTheme.shapes.large
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** ● Go live now: the screen's main action, in the album colour. On the air it opens the session instead. */
@Composable
private fun GoLiveButton(onAir: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val chrome = LocalAlbumChrome.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(chrome.accent.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        LiveDot(9.dp, pulsing = onAir)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (onAir) R.string.sessions_on_air else R.string.station_go_live_now),
            style = MaterialTheme.typography.titleMedium,
            color = chrome.onAccent,
        )
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = MinTouch).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** One line of text to change, with its limit counted as the Worker counts it. Save waits for something sendable. */
@Composable
private fun EditDialog(
    title: String,
    label: String,
    initial: String,
    max: Int,
    allowBlank: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // Local, synchronous state: a field fed from a flow drops keystrokes.
    var text by rememberSaveable { mutableStateOf(initial) }
    val ok = allowBlank || text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace('\n', ' ').take(max) },
                label = { Text(label) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.station_count, text.length, max), color = Color.Unspecified) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onSave(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = ok) { Text(stringResource(R.string.station_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.station_cancel)) } },
    )
}
