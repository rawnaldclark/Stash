package com.stash.feature.nowplaying.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.listen.ListenTogetherController.RequestAnswer
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.listen.RoomMember
import com.stash.core.ui.components.personColor
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R

/** A small filled button for a row: Request, Next, Queue. */
@Composable
private fun RowButton(text: String, onClick: () -> Unit, enabled: Boolean = true, strong: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val ground = if (strong) scheme.primary else scheme.onSurface.copy(alpha = 0.10f)
    val ink = if (strong) scheme.onPrimary else scheme.onSurface
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = ink.copy(alpha = if (enabled) 1f else 0.5f),
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(if (enabled) ground else ground.copy(alpha = ground.alpha * 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun SongLines(title: String, artist: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A listener's Request sheet (mockup: Request a song): where the host is, this listener's requests with their
 * states, and a search of this phone's library with Request on each result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestSheet(room: ListenTogetherState.InRoom, viewModel: SessionDjViewModel, onDismiss: () -> Unit) {
    val ground = StashTheme.extendedColors.elevatedSurface
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val mine = remember(room.queue, room.requests, room.myId) { myRequests(room) }
    val waiting = myWaiting(room)
    val full = waiting >= MAX_WAITING
    val host = hostName(room) ?: stringResource(R.string.dj_the_host)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ground,
        properties = com.stash.core.ui.components.sheetBarsFollow(ground),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(stringResource(R.string.dj_request_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                val session = room.session
                if (session != null) {
                    Text(
                        stringResource(
                            when (hostPresence(session)) {
                                HostPresence.BOOTH -> R.string.dj_presence_booth
                                HostPresence.BOOTH_AUTO -> R.string.dj_presence_booth_auto
                                HostPresence.AWAY_WAIT -> R.string.dj_presence_away_wait
                                HostPresence.AWAY_AUTO -> R.string.dj_presence_away_auto
                            },
                            host,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (mine.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.dj_your_requests), Modifier.weight(1f))
                        Text(
                            stringResource(R.string.dj_waiting_of, waiting, MAX_WAITING),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(mine, key = { (if (it.accepted) "q" else "w") + it.queueIndex + it.track.title + it.track.artist }) { request ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SongLines(request.track.title, request.track.artist, Modifier.weight(1f))
                        if (request.accepted) {
                            Text(
                                stringResource(R.string.dj_state_up_next),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(onClick = { viewModel.takeBack(request.track, request.queueIndex) }) {
                                Text(stringResource(R.string.dj_take_back))
                            }
                        } else {
                            Text(
                                stringResource(R.string.dj_state_waiting),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.dj_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            items(results, key = { it.id }) { track ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SongLines(track.title, track.artist, Modifier.weight(1f))
                    RowButton(stringResource(R.string.room_request), onClick = { viewModel.request(track) }, enabled = !full)
                }
            }
            item {
                Text(
                    when {
                        full -> stringResource(R.string.dj_request_full, MAX_WAITING)
                        query.trim().length >= 2 && results.isEmpty() -> stringResource(R.string.dj_search_none)
                        else -> stringResource(R.string.dj_request_foot, MAX_WAITING, host)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
            }
        }
    }
}

/**
 * The booth's requests (mockup: The booth): the ones waiting, each with Next, Queue and Pass, and the host's
 * Auto-accept. A co-host sees and answers them too, without the switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestsSheet(room: ListenTogetherState.InRoom, viewModel: SessionDjViewModel, onDismiss: () -> Unit) {
    val ground = StashTheme.extendedColors.elevatedSurface
    val waiting = remember(room.requests, room.members, room.names) { waitingRequests(room) }
    val autoAccept = room.session?.autoAccept == true
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ground,
        properties = com.stash.core.ui.components.sheetBarsFollow(ground),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (waiting.isEmpty()) stringResource(R.string.dj_requests_title)
                    else pluralStringResource(R.plurals.dj_requests_waiting, waiting.size, waiting.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (room.role == SessionRole.HOST) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Switch) { viewModel.setAutoAccept(!autoAccept) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.dj_auto_accept), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.dj_auto_accept_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoAccept, onCheckedChange = null)
                    }
                }
            }
            if (waiting.isEmpty()) {
                item {
                    Text(
                        stringResource(if (autoAccept) R.string.dj_requests_none_auto else R.string.dj_requests_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(waiting, key = { it.id }) { request ->
                Column(Modifier.fillMaxWidth()) {
                    Text(request.track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (request.from ?: stringResource(R.string.listen_someone)) + " · " + request.track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RowButton(stringResource(R.string.dj_answer_next), onClick = { viewModel.answer(request.id, RequestAnswer.NEXT) })
                        RowButton(stringResource(R.string.dj_answer_queue), onClick = { viewModel.answer(request.id, RequestAnswer.QUEUE) }, strong = false)
                        RowButton(stringResource(R.string.dj_answer_pass), onClick = { viewModel.answer(request.id, RequestAnswer.PASS) }, strong = false)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * What the host can do to [member], under their row in the crowd's list: make or unmake a co-host, and boot (which
 * asks first: they can't come back to this session).
 */
@Composable
internal fun MemberActionsRow(member: RoomMember, actions: MemberActions, viewModel: SessionDjViewModel, onDone: () -> Unit) {
    var confirmBoot by remember { mutableStateOf(false) }
    val name = member.name?.takeIf(String::isNotBlank) ?: stringResource(R.string.listen_someone)
    Row(Modifier.fillMaxWidth().padding(start = 68.dp, end = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.canCohost) {
            RowButton(
                stringResource(if (actions.isCohost) R.string.dj_cohost_remove else R.string.dj_cohost_make),
                onClick = { viewModel.setCohost(member.id, !actions.isCohost); onDone() },
                strong = !actions.isCohost,
            )
        } else {
            Text(
                stringResource(R.string.dj_cohosts_full),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (actions.canBoot) RowButton(stringResource(R.string.dj_boot), onClick = { confirmBoot = true }, strong = false)
    }
    if (confirmBoot) {
        AlertDialog(
            onDismissRequest = { confirmBoot = false },
            title = { Text(stringResource(R.string.dj_boot_title, name)) },
            text = { Text(stringResource(R.string.dj_boot_body)) },
            confirmButton = {
                TextButton(onClick = { confirmBoot = false; viewModel.boot(member.id); onDone() }) {
                    Text(stringResource(R.string.dj_boot), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmBoot = false }) { Text(stringResource(R.string.np_tuning_cancel)) } },
        )
    }
}

/** The room's line for the host and co-hosts: how many requests wait. Tapping opens them. */
@Composable
internal fun RequestsLine(room: ListenTogetherState.InRoom, ink: androidx.compose.ui.graphics.Color, muted: androidx.compose.ui.graphics.Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val waiting = room.requests.size
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (waiting > 0) {
            Text(
                waiting.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.clip(CircleShape).background(personColor("requests")).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(
            when {
                waiting > 0 -> pluralStringResource(R.plurals.dj_requests_line, waiting, waiting)
                room.session?.autoAccept == true -> stringResource(R.string.dj_requests_line_auto)
                else -> stringResource(R.string.dj_requests_line_none)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (waiting > 0) ink else muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
