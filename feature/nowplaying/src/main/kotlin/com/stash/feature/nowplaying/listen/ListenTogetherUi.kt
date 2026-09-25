package com.stash.feature.nowplaying.listen

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.listen.ServerMessage
import com.stash.core.ui.theme.StashTheme
import kotlin.random.Random
import kotlinx.coroutines.flow.SharedFlow

/** Starting a session: the only thing to decide is the name others will see (spec §1: no accounts). */
@Composable
fun ListenTogetherStartDialog(name: String, onNameChange: (String) -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listen together") },
        text = {
            Column {
                Text(
                    "Friends who open your invite hear what you play, in time with you. You stay in control and can hand it over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Show my name as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Android share sheet with the invite link (`https://…/l/{code}`, an App Link). */
fun shareInvite(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Listen with me on Stash: $url")
    context.startActivity(Intent.createChooser(send, "Invite friends").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Who's in the room, the host marked, each with a status dot (spec §5). The host hands control over from here. */
@Composable
fun WhosListeningBar(
    room: ListenTogetherState.InRoom,
    accent: Color,
    onMakeHost: (String) -> Unit,
    onOpenSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(room.members, key = { it.id }) { member ->
                MemberPill(
                    member = member,
                    isHost = member.id == room.hostId,
                    isMe = member.id == room.myId,
                    accent = accent,
                    onMakeHost = if (room.isHost && member.id != room.myId) ({ onMakeHost(member.id) }) else null,
                )
            }
        }
        if (room.isHost && room.suggestions.isNotEmpty()) {
            TextButton(onClick = onOpenSuggestions) { Text("Suggestions (${room.suggestions.size})") }
        }
    }
}

@Composable
private fun MemberPill(member: RoomMember, isHost: Boolean, isMe: Boolean, accent: Color, onMakeHost: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val (dot, status) = when (member.status) {
        "ok" -> accent to "in sync"
        "unavailable" -> MaterialTheme.colorScheme.error to "can't play this song"
        else -> StashTheme.extendedColors.warning to "catching up" // buffering or drifting
    }
    val label = buildString {
        append(member.name ?: "Someone")
        if (isMe) append(" (you)")
        if (isHost) append(" · host")
    }
    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            modifier = Modifier
                .clickable(enabled = onMakeHost != null, onClickLabel = "Make host") { menu = true }
                .clearAndSetSemantics { contentDescription = "$label, $status" },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Make host") }, onClick = { menu = false; onMakeHost?.invoke() })
        }
    }
}

/** The one-line notices from spec §4 and §6, most urgent first. */
@Composable
fun ListenTogetherNotice(room: ListenTogetherState.InRoom, modifier: Modifier = Modifier) {
    val text = when {
        room.reconnecting -> "Reconnecting…"
        room.unavailable -> "This song isn't available to you"
        room.versionMismatch -> "Your version may be a few seconds off"
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 4.dp),
    )
}

/**
 * A listener's controls: no play, pause, skip or seek (spec §5). Volume stays their own. While an unplug
 * or a call has paused this phone ([ListenTogetherState.InRoom.pausedLocally]), a chip catches it up.
 */
@Composable
fun ListenerControls(
    pausedLocally: Boolean,
    onRejoin: () -> Unit,
    onLeave: () -> Unit,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The chip's height is always reserved, so the controls don't jump when a call or an unplug pauses this phone.
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (pausedLocally) {
                AssistChip(
                    onClick = onRejoin,
                    label = { Text("Paused — tap to rejoin") },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onLeave) { Text("Leave") }
            Spacer(Modifier.width(16.dp))
            ReactionButton(onReact = onReact)
        }
    }
}

@Composable
fun ReactionButton(onReact: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.AddReaction, contentDescription = "React") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                RoomProtocol.EMOJI.forEach { emoji ->
                    TextButton(onClick = { open = false; onReact(emoji) }) { Text(emoji, fontSize = 22.sp) }
                }
            }
        }
    }
}

private data class Floater(val id: Long, val emoji: String, val lane: Float)

private const val MAX_FLOATERS = 12

/** Reactions float up and fade (spec §5). */
@Composable
fun FloatingReactions(reactions: SharedFlow<ServerMessage.Reaction>, modifier: Modifier = Modifier) {
    val live = remember { mutableStateListOf<Floater>() }
    LaunchedEffect(reactions) {
        var next = 0L
        reactions.collect { r ->
            if (live.size >= MAX_FLOATERS) live.removeAt(0)
            live += Floater(next++, r.emoji, Random.nextFloat())
        }
    }
    Box(modifier) {
        live.forEach { f ->
            key(f.id) {
                val progress = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    progress.animateTo(1f, tween(durationMillis = 2_400, easing = LinearOutSlowInEasing))
                    live.remove(f)
                }
                Text(
                    text = f.emoji,
                    fontSize = 32.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        // Read in the draw phase: a frame of the float doesn't recompose the screen.
                        .graphicsLayer {
                            translationX = (24 + f.lane * 240).dp.toPx()
                            translationY = -(120 + 320 * progress.value).dp.toPx()
                            alpha = 1f - progress.value
                        },
                )
            }
        }
    }
}

/** The host's suggestions tray (spec §5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsSheet(room: ListenTogetherState.InRoom, onAnswer: (id: String, add: Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = StashTheme.extendedColors.elevatedSurface) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 36.dp)) {
            item { Text("Suggestions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp)) }
            if (room.suggestions.isEmpty()) {
                item { Text("Nothing waiting", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(room.suggestions, key = { it.id }) { s ->
                val from = room.members.firstOrNull { it.id == s.from }?.name ?: "Someone"
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${s.track.artist} · from $from",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onAnswer(s.id, false) }) { Text("Dismiss") }
                    Button(onClick = { onAnswer(s.id, true) }) { Text("Add") }
                }
            }
        }
    }
}
