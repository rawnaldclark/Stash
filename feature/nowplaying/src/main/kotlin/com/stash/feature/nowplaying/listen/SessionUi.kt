package com.stash.feature.nowplaying.listen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionEvent
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.share.SharedTrack
import com.stash.core.ui.theme.StashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

// ── Faces ─────────────────────────────────────────────────────────────────────

/** One colour per person, stable from their member id: their face, reactions and picks all wear it. */
internal val PERSON_COLORS = listOf(
    Color(0xFFA78BFA), Color(0xFFF472B6), Color(0xFF22D3EE), Color(0xFFFBBF24),
    Color(0xFF34D399), Color(0xFFFB923C), Color(0xFF60A5FA), Color(0xFFA3E635),
)

internal fun personColor(memberId: String): Color = PERSON_COLORS[Math.floorMod(memberId.hashCode(), PERSON_COLORS.size)]

/** "Maya Lopez" → "ML", "Pixel5" → "P". The room knows names only, so faces are initials. */
internal fun initials(name: String?): String =
    name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** "Maya", "Maya, Sam", "Maya, Sam +2". */
internal fun nameList(names: List<String>): String = when {
    names.size <= 2 -> names.joinToString(", ")
    else -> "${names[0]}, ${names[1]} +${names.size - 2}"
}

/** A member's name, also for someone who has left (their picks and suggestions still carry their id). */
internal fun ListenTogetherState.InRoom.nameOf(id: String): String? = members.firstOrNull { it.id == id }?.name ?: names[id]

internal val HOST_RING = Color(0xFFFBBF24)
private val FACE_INK = Color(0xFF0D0D18)

@Composable
fun PersonAvatar(memberId: String, name: String?, size: Dp, modifier: Modifier = Modifier, isHost: Boolean = false) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(personColor(memberId))
            .border(2.dp, if (isHost) HOST_RING else StashTheme.extendedColors.elevatedSurface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = FACE_INK, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
    }
}

/** Up to four faces overlapping, host first, then "+n". */
@Composable
private fun FaceStack(members: List<RoomMember>, hostId: String?, faceSize: Dp = 28.dp) {
    val ordered = members.sortedBy { if (it.id == hostId) 0 else 1 }
    val shown = ordered.take(4)
    val step = faceSize * 0.7f
    Box(Modifier.width(faceSize + step * (shown.size - 1).coerceAtLeast(0)).height(faceSize)) {
        shown.forEachIndexed { i, m ->
            PersonAvatar(m.id, m.name, faceSize, Modifier.offset(x = step * i), isHost = m.id == hostId)
        }
    }
    if (ordered.size > 4) {
        Text(
            "+${ordered.size - 4}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// ── The bar on Now Playing ────────────────────────────────────────────────────

/** "Listening with Maya, Sam": who's here, who hosts. Tapping it opens the Session sheet. */
@Composable
fun SessionBar(room: ListenTogetherState.InRoom, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val others = room.members.filter { it.id != room.myId }
    val host = room.members.firstOrNull { it.id == room.hostId }
    val title = if (others.isEmpty()) "Waiting for friends to join" else "Listening with ${nameList(others.map { it.name ?: "Someone" })}"
    val waiting = if (room.isHost) room.suggestions.size else 0
    val subtitle = when {
        room.reconnecting -> "Reconnecting…"
        waiting > 0 -> if (waiting == 1) "1 suggestion waiting" else "$waiting suggestions waiting"
        room.isHost -> "You're hosting · ${room.members.size} in the room"
        host != null -> "${host.name ?: "Someone"} is hosting"
        else -> "The host will be right back"
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(StashTheme.extendedColors.glassBackground)
            .border(1.dp, StashTheme.extendedColors.glassBorderBright, shape)
            .clickable(onClickLabel = "Open the session", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FaceStack(room.members, room.hostId)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                // A waiting suggestion needs the host, so it stands out; everything else is quiet.
                color = if (waiting > 0 && !room.reconnecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (waiting > 0 && !room.reconnecting) FontWeight.SemiBold else null,
                maxLines = 1,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Sam's pick" under the song title while a suggested song plays; nothing for the host's own songs. */
@Composable
fun PickLine(room: ListenTogetherState.InRoom, modifier: Modifier = Modifier) {
    val by = room.track?.addedBy ?: return
    if (by == room.hostId) return
    val name = if (by == room.myId) "Your" else "${room.nameOf(by) ?: "A friend"}'s"
    Row(modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PersonAvatar(by, room.nameOf(by), 18.dp)
        Spacer(Modifier.width(6.dp))
        Text("$name pick", style = MaterialTheme.typography.labelMedium, color = personColor(by))
    }
}

/**
 * A listener's link to the room: LIVE while in sync, "Back to live" while paused on this phone
 * (an unplug, a call, or their own pause). Tapping it rejoins.
 */
@Composable
fun LivePill(pausedLocally: Boolean, accent: Color, onRejoin: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    if (pausedLocally) {
        Row(
            modifier
                .clip(shape)
                .border(1.5.dp, accent, shape)
                .clickable(onClickLabel = "Rejoin the room", onClick = onRejoin)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("BACK TO LIVE", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        }
    } else {
        Row(
            modifier.clip(shape).background(accent.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("LIVE", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        }
    }
}

// ── Named notices ─────────────────────────────────────────────────────────────

internal fun eventText(e: SessionEvent, myId: String?): String {
    val who = e.name ?: "Someone"
    return when (e.kind) {
        SessionEvent.Kind.JOINED -> "$who joined"
        SessionEvent.Kind.LEFT -> "$who left"
        SessionEvent.Kind.SKIPPED -> "$who skipped"
        SessionEvent.Kind.WENT_BACK -> "$who went back"
        SessionEvent.Kind.PAUSED -> "$who paused"
        SessionEvent.Kind.RESUMED -> "$who resumed"
        SessionEvent.Kind.HOSTING -> if (e.memberId == myId) "You're hosting now" else "$who is hosting now"
    }
}

/** "Maya joined", "Rawn skipped": a short chip with the person's face, the newest replacing the last. */
@Composable
fun SessionEventChip(events: SharedFlow<SessionEvent>, myId: String?, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<SessionEvent?>(null) }
    var shown by remember { mutableStateOf<SessionEvent?>(null) } // kept through the exit fade
    LaunchedEffect(events) {
        events.collect { e ->
            current = e
            shown = e
        }
    }
    LaunchedEffect(current) {
        if (current != null) { delay(3_000); current = null }
    }
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val e = shown ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .padding(top = 8.dp)
                .clip(shape)
                .background(StashTheme.extendedColors.elevatedSurface.copy(alpha = 0.92f))
                .border(1.dp, StashTheme.extendedColors.glassBorderBright, shape)
                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonAvatar(e.memberId, e.name, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(eventText(e, myId), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ── The Session sheet ─────────────────────────────────────────────────────────

/** `R2YHWS55` → `R2YH-WS55`, easier to read out. */
internal fun formatCode(code: String): String = code.chunked(4).joinToString("-")

private fun statusWord(status: String): String? = when (status) {
    "ok" -> null
    "unavailable" -> "can't play this song"
    else -> "catching up" // buffering or drifting
}

/** Everything about the session in one place: people, what's next and whose it is, suggestions, invite, leave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    state: ListenTogetherState,
    onDismiss: () -> Unit,
    onAnswer: (id: String, add: Boolean) -> Unit,
    onMakeHost: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onShare: (String) -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
) {
    // Right after Start the state is still Idle for a moment (the command is on its way), so Idle only
    // closes the sheet at once after a session was live; a start that never happens closes it after 10 s.
    var seenSession by remember { mutableStateOf(false) }
    val idle = state is ListenTogetherState.Idle
    LaunchedEffect(idle) {
        if (idle) {
            delay(if (seenSession) 0 else 10_000)
            onDismiss()
        } else {
            seenSession = true
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = StashTheme.extendedColors.elevatedSurface) {
        when (state) {
            is ListenTogetherState.InRoom -> RoomContent(state, onAnswer, onMakeHost, onCopyLink, onShare, onLeave, onEnd)
            else -> Column(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                val joining = state is ListenTogetherState.Connecting && !state.hosting
                Text(if (joining) "Joining…" else "Starting your session…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RoomContent(
    room: ListenTogetherState.InRoom,
    onAnswer: (id: String, add: Boolean) -> Unit,
    onMakeHost: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onShare: (String) -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
) {
    val host = room.members.firstOrNull { it.id == room.hostId }
    val nameOf = { id: String -> room.nameOf(id) }
    val count = room.members.size
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        item {
            Text("Listening together", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "$count ${if (count == 1) "person" else "people"} · " +
                    if (room.isHost) "you're hosting" else "${host?.name ?: "the host"} is hosting",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                room.members.sortedBy { if (it.id == room.hostId) 0 else 1 }.forEach { m ->
                    Person(m, room, onMakeHost = if (room.isHost && m.id != room.myId) ({ onMakeHost(m.id) }) else null)
                }
            }
        }

        // What needs you comes first: the host's waiting suggestions, or a listener's own.
        val mine = room.suggestions.filter { it.from == room.myId }
        if (room.isHost && room.suggestions.isNotEmpty()) {
            item { SectionLabel("SUGGESTIONS · ${room.suggestions.size}") }
            items(room.suggestions, key = { it.id }) { s ->
                val from = nameOf(s.from) ?: "Someone"
                SongRow(s.track, detail = "from $from", detailColor = personColor(s.from)) {
                    TextButton(onClick = { onAnswer(s.id, false) }) { Text("Dismiss") }
                    Button(onClick = { onAnswer(s.id, true) }) { Text("Play next") }
                }
            }
        } else if (!room.isHost && mine.isNotEmpty()) {
            item { SectionLabel("YOUR SUGGESTIONS") }
            items(mine, key = { it.id }) { s ->
                SongRow(s.track, detail = "Waiting for ${host?.name ?: "the host"}")
            }
        }

        item { SectionLabel("UP NEXT") }
        if (room.queue.isEmpty()) {
            item { Text("Nothing queued yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(room.queue.take(MAX_UP_NEXT)) { t ->
            SongRow(t) { t.addedBy?.let { PersonAvatar(it, nameOf(it), 22.dp) } }
        }
        if (room.queue.size > MAX_UP_NEXT) {
            item { Text("+${room.queue.size - MAX_UP_NEXT} more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        item {
            Spacer(Modifier.height(16.dp))
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(StashTheme.extendedColors.glassBackground)
                    .border(1.dp, StashTheme.extendedColors.glassBorder, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Invite friends", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        formatCode(room.code),
                        style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 1.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = "Room code ${room.code.toList().joinToString(" ")}" },
                    )
                }
                TextButton(onClick = { onCopyLink(room.url) }) { Text("Copy link") }
                TextButton(onClick = { onShare(room.url) }) { Text("Share") }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onLeave, modifier = Modifier.weight(1f)) { Text("Leave") }
                if (room.isHost) {
                    OutlinedButton(
                        onClick = onEnd,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("End for everyone") }
                }
            }
        }
    }
}

private const val MAX_UP_NEXT = 20

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Person(m: RoomMember, room: ListenTogetherState.InRoom, onMakeHost: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val isHost = m.id == room.hostId
    val label = (m.name ?: "Someone") + if (m.id == room.myId) " (you)" else ""
    val status = statusWord(m.status)
    Box {
        Column(
            Modifier
                .widthIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = onMakeHost != null, onClickLabel = "Make host") { menu = true }
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(label, if (isHost) "host" else null, status).joinToString(", ")
                }
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PersonAvatar(m.id, m.name, 40.dp, isHost = isHost)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp))
            if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = StashTheme.extendedColors.warning, maxLines = 1)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Make ${m.name ?: "them"} the host") }, onClick = { menu = false; onMakeHost?.invoke() })
        }
    }
}

/** A song in the sheet. Songs carry no artwork, so the tile takes a colour from the title. */
@Composable
private fun SongRow(
    track: SharedTrack,
    detail: String? = null,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val c = personColor(track.title + track.artist)
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(c.copy(alpha = 0.85f), c.copy(alpha = 0.35f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.MusicNote, contentDescription = null, tint = FACE_INK.copy(alpha = 0.6f), modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildAnnotatedString {
                    append(track.artist)
                    if (detail != null) {
                        append(" · ")
                        withStyle(SpanStyle(color = detailColor)) { append(detail) }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}
