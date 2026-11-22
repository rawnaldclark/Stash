package com.stash.feature.nowplaying.session

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.community.Station
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.personColor
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R
import com.stash.feature.nowplaying.listen.LivePill
import com.stash.feature.nowplaying.listen.nameOf
import com.stash.feature.nowplaying.ui.GlowingProgressBar
import com.stash.feature.nowplaying.ui.npInk
import kotlinx.coroutines.flow.SharedFlow

/** What the player knows about the room's song on this phone: the room's own state has the rest. */
internal class RoomSong(
    val title: String,
    val artist: String,
    /** The cover as this phone has it (a file's own art, or the stream's). */
    val art: Any?,
    val durationMs: Long,
    /** The player means to play (not "is playing": that drops while a stream buffers). */
    val playing: Boolean,
    val progress: () -> Float,
    val elapsedMs: () -> Long,
    /** In Liked Songs; null when the song can't be liked (no library row yet). */
    val liked: Boolean?,
)

/**
 * A station's session, in place of the player's own content (UX spec 2026-11-20 §4, the room mockup). From the top:
 * collapse, the station with LIVE and the count, Follow and the menu; the vibe line; the song (its cover smaller than
 * the player's, so the crowd fits), whose pick it is and a heart; the progress; the crowd in one band, with reactions
 * rising from whoever sent them; Up next on one line; then the bar.
 *
 * A listener's bar is Request (waiting for step 3) and React, and they can't seek: the room sets the pace. The host
 * has the player's own controls ([hostControls]) there instead, and End session in the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionRoom(
    room: ListenTogetherState.InRoom,
    song: RoomSong,
    reactions: SharedFlow<ServerMessage.Reaction>,
    animate: Boolean,
    onCollapse: () -> Unit,
    onReact: (String) -> Unit,
    onRejoin: () -> Unit,
    onLike: () -> Unit,
    onSeek: (Long) -> Unit,
    onEnd: () -> Unit,
    hostControls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionRoomViewModel = hiltViewModel(),
    dj: SessionDjViewModel = hiltViewModel(),
    life: SessionLifeViewModel = hiltViewModel(),
    recap: SessionRecapViewModel = hiltViewModel(),
) {
    val feed by life.feed.collectAsStateWithLifecycle()
    val greet by life.greet.collectAsStateWithLifecycle()
    var showTray by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRequest by remember { mutableStateOf(false) }
    var showRequests by remember { mutableStateOf(false) }
    val station = state.station
    // The recap of this session carries the station's name.
    androidx.compose.runtime.LaunchedEffect(station?.id, station?.name) { station?.let { recap.noteStation(it.id, it.name) } }
    val context = LocalContext.current
    val chrome = LocalAlbumChrome.current
    val ink = npInk()
    val muted = ink.copy(alpha = 0.66f)
    // Why a follow or a vote didn't go is said by SessionLeaveHost, once for the app.
    var showEveryone by remember { mutableStateOf(false) }
    var showUpNext by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val crowd = remember(room.members, room.hostId, room.session?.cohosts) { crowdOf(room, FLOOR_FITS) }
    val listeners = room.session?.listeners ?: 0

    Column(modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // ── The header ──────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.room_collapse), tint = ink)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    station?.name ?: stringResource(R.string.room_station),
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(LiveRed, CircleShape))
                    Text(
                        stringResource(R.string.room_live),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                        color = liveInk(),
                    )
                    Text(
                        "· " + if (listeners > 0) pluralStringResource(R.plurals.room_listening, listeners, listeners) else stringResource(R.string.room_no_listeners),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                    )
                }
            }
            if (station != null && !station.mine && !room.isHost) {
                FollowPill(following = station.following, ink = ink, onClick = viewModel::follow)
            }
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.room_menu), tint = ink)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (room.isHost) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.room_end), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmEnd = true },
                        )
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.room_leave)) }, onClick = { menu = false; viewModel.leave() })
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.room_share)) },
                        onClick = {
                            menu = false
                            val text = context.getString(R.string.room_share_text, station?.name ?: context.getString(R.string.room_station), room.url)
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            runCatching { context.startActivity(Intent.createChooser(send, null)) }
                        },
                    )
                    if (station != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.room_about)) }, onClick = { menu = false; showAbout = true })
                    }
                }
            }
        }
        // ── The vibe line; for a host who's paused, what that means ───────────────
        val vibe = station?.vibe?.takeIf { it.isNotBlank() }
        val hint = if (room.isHost && !song.playing) stringResource(R.string.room_host_paused) else vibe?.let { stringResource(R.string.room_vibe, it) }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
            )
        }

        // ── The cover: what's left of the height, up to the mockup's size ─────────
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            val side = min(min(maxWidth, maxHeight), ArtMax)
            Box(
                Modifier.size(side).clip(RoundedCornerShape(18.dp)).background(StashTheme.extendedColors.glassBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = muted, modifier = Modifier.size(40.dp))
                // The phone's own cover; without one, the room's (a cover host's link only).
                val art = song.art ?: room.track?.artUrl?.takeIf(ShareConfig::isAllowedCover)
                if (art != null) AsyncImage(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }

        // ── The song, whose pick it is, and the heart ─────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, lineHeight = 28.sp),
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            song.liked?.let { liked ->
                val haptics = LocalHapticFeedback.current
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(1.dp, ink.copy(alpha = 0.18f), CircleShape)
                        .clickable(role = Role.Button, onClickLabel = stringResource(if (liked) R.string.room_unsave else R.string.room_save)) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            // A save is counted by the room ("9 saved this"), once a person; who saved is never shown.
                            if (!liked) { life.saved(); recap.saved() }
                            onLike()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = stringResource(if (liked) R.string.room_saved else R.string.room_save),
                        tint = if (liked) chrome.accent else ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        // "Last song": the host is ending the session when this one finishes.
        if (room.session?.lastSong == true) {
            Text(
                stringResource(R.string.life_last_song),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                color = liveInk(),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(CircleShape)
                    .border(1.dp, liveInk().copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        pickOf(room)?.let { pick ->
            PickChip(pick, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        SongCrowdLine(room.songStats, accent = chrome.accent, muted = muted, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

        // ── Where the song is: a listener follows the room, the host can seek ─────
        GlowingProgressBar(
            progress = song.progress,
            accentColor = { chrome.accent },
            elapsedMs = song.elapsedMs,
            totalMs = song.durationMs,
            onSeek = onSeek,
            seekable = room.isHost,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            center = {
                if (!room.isHost) LivePill(pausedLocally = room.pausedLocally, accent = chrome.accent, ink = ink, onRejoin = onRejoin)
            },
        )

        // ── The crowd ─────────────────────────────────────────────────────────────
        CrowdBand(
            crowd = crowd,
            myId = room.myId,
            reactions = reactions,
            animate = animate,
            onShowEveryone = { showEveryone = true },
            modifier = Modifier.padding(top = 10.dp),
        )

        // ── The feed: who came in, what people say, crowd favourites ───────────────
        FeedLines(feed, ink = muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))

        // ── Up next, on one line ──────────────────────────────────────────────────
        UpNextLine(room, ink = ink, muted = muted, onClick = { showUpNext = true }, modifier = Modifier.padding(top = 10.dp))

        // ── Requests, for the host and co-hosts ────────────────────────────────
        if (answersRequests(room)) {
            RequestsLine(room, ink = ink, muted = muted, onClick = { showRequests = true }, modifier = Modifier.padding(top = 4.dp))
        }

        // ── The bar ───────────────────────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        if (room.isHost) {
            hostControls()
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // A listener asks for a song; a co-host's own adds go straight in from any song menu, and they
                // answer requests from the line above.
                Row(
                    Modifier.weight(1f).height(52.dp).clip(CircleShape).background(ink)
                        .clickable(role = Role.Button) { if (answersRequests(room)) showRequests = true else showRequest = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val on = MaterialTheme.colorScheme.background
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = on, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(if (answersRequests(room)) R.string.dj_requests_title else R.string.room_request),
                        style = MaterialTheme.typography.titleMedium,
                        color = on,
                    )
                }
                // A co-host can skip the song: the host's phone does it.
                if (room.role == com.stash.core.media.listen.SessionRole.COHOST) {
                    IconButton(onClick = dj::skip, modifier = Modifier.size(52.dp).clip(CircleShape).border(1.dp, ink.copy(alpha = 0.35f), CircleShape)) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.dj_skip), tint = ink)
                    }
                }
                ReactPill(ink = ink, onClick = { showTray = true })
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showTray) ReactTray(greet = greet, onReact = onReact, onSay = life::say, onDismiss = { showTray = false })
    if (showEveryone) EveryoneSheet(room, crowd, dj, onDismiss = { showEveryone = false })
    if (showRequest && !answersRequests(room)) RequestSheet(room, dj, onDismiss = { showRequest = false })
    if (showRequests && answersRequests(room)) RequestsSheet(room, dj, onDismiss = { showRequests = false })
    if (showUpNext) UpNextSheet(room, onDismiss = { showUpNext = false })
    if (showAbout && station != null) AboutStation(station, viewModel, onDismiss = { showAbout = false })
    if (confirmEnd) {
        val last = room.session?.lastSong == true
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.room_end_title)) },
            text = {
                Column {
                    Text(
                        if (listeners > 0) pluralStringResource(R.plurals.room_end_body, listeners, listeners) else stringResource(R.string.room_end_body_empty),
                    )
                    // After this song: everyone sees "Last song" and the session ends when it finishes.
                    if (room.track != null) {
                        TextButton(
                            onClick = { confirmEnd = false; dj.lastSong(!last) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text(stringResource(if (last) R.string.room_end_not_after else R.string.room_end_after)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; onEnd() }) { Text(stringResource(R.string.room_end_now), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.np_tuning_cancel)) } },
        )
    }
}

/** The cover's size in the mockup: smaller than the player's, so the crowd and Up next fit under it. */
private val ArtMax = 232.dp

/** "LIVE" as words over the player's ground: soft red on dark, deep red on the light theme. */
@Composable
internal fun liveInk(): Color = if (com.stash.feature.nowplaying.ui.npIsLight()) Color(0xFFB91C1C) else Color(0xFFF4A3A3)

@Composable
private fun FollowPill(following: Boolean, ink: Color, onClick: () -> Unit) {
    Text(
        stringResource(if (following) R.string.room_following else R.string.room_follow),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = if (following) ink.copy(alpha = 0.7f) else ink,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, ink.copy(alpha = if (following) 0.14f else 0.22f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** "Lee's pick", in Lee's colour with their face: whose song the room is playing. */
@Composable
private fun PickChip(pick: Pick, modifier: Modifier = Modifier) {
    val color = personColor(pick.memberId)
    val label = when {
        pick.mine -> stringResource(R.string.room_pick_yours)
        pick.name.isNullOrBlank() -> stringResource(R.string.room_pick_someone)
        else -> stringResource(R.string.room_pick, pick.name)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(start = 3.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(18.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                Text(
                    com.stash.feature.nowplaying.listen.initials(pick.name).take(1),
                    color = FaceInk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
        }
    }
}

/** UP NEXT and the next song, on one line; a tap shows the songs ahead. */
@Composable
private fun UpNextLine(room: ListenTogetherState.InRoom, ink: Color, muted: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ext = StashTheme.extendedColors
    val shape = MaterialTheme.shapes.large
    val next = room.queue.firstOrNull()
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ext.glassBackground)
            .border(1.dp, ext.glassBorder, shape)
            .clickable(enabled = next != null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SongArt(next, Modifier.size(38.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.room_up_next).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
                color = muted,
            )
            Text(
                next?.let { "${it.title} · ${it.artist}" } ?: stringResource(R.string.room_up_next_empty),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (next != null) ink else muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (next != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SongArt(track: SharedTrack?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(StashTheme.extendedColors.glassBackground), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        track?.artUrl?.takeIf(ShareConfig::isAllowedCover)?.let {
            AsyncImage(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** React: the six reactions, in a tray that stays open for quick repeat taps. */
@Composable
private fun ReactPill(ink: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .width(140.dp)
            .height(52.dp)
            .clip(CircleShape)
            .border(1.dp, ink.copy(alpha = 0.18f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.AddReaction, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.room_react), style = MaterialTheme.typography.titleMedium, color = ink)
    }
}

/** Everyone in the room, by name: the booth first, then the floor in the order they joined. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EveryoneSheet(room: ListenTogetherState.InRoom, crowd: Crowd, dj: SessionDjViewModel, onDismiss: () -> Unit) {
    val ground = StashTheme.extendedColors.elevatedSurface
    val cohosts = room.session?.cohosts.orEmpty()
    // The host taps someone for Co-host or Boot: one person's row open at a time.
    var open by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ground, properties = com.stash.core.ui.components.sheetBarsFollow(ground)) {
        Text(
            pluralStringResource(R.plurals.room_here, crowd.everyone.size, crowd.everyone.size),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
        )
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 16.dp)) {
            itemsIndexed(crowd.everyone, key = { _, m -> m.id }) { _, member ->
                val actions = memberActions(room, member.id)
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (actions != null) Modifier.clickable(role = Role.Button) { open = if (open == member.id) null else member.id } else Modifier)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Face(member, size = 36.dp, ring = ground)
                    Text(
                        when {
                            member.id == room.myId -> stringResource(R.string.room_you)
                            else -> member.name?.takeIf(String::isNotBlank) ?: stringResource(R.string.listen_someone)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val part = when {
                        member.id == room.hostId -> R.string.room_host
                        member.id in cohosts -> R.string.room_cohost
                        else -> null
                    }
                    if (part != null) {
                        Text(stringResource(part), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (actions != null && open == member.id) {
                    MemberActionsRow(member, actions, dj, onDone = { open = null })
                }
            }
        }
    }
}

/** The songs ahead, and whose pick each is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpNextSheet(room: ListenTogetherState.InRoom, onDismiss: () -> Unit) {
    val ground = StashTheme.extendedColors.elevatedSurface
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ground, properties = com.stash.core.ui.components.sheetBarsFollow(ground)) {
        Text(
            stringResource(R.string.room_up_next),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
        )
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 16.dp)) {
            itemsIndexed(room.queue.take(UP_NEXT_SHOWN)) { _, track ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SongArt(track, Modifier.size(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Whose pick: a listener's or a co-host's. The host's own songs say nothing.
                    val by = track.addedBy?.takeIf { it != room.hostId }
                    if (by != null) {
                        val name = if (by == room.myId) stringResource(R.string.room_you) else room.nameOf(by) ?: stringResource(R.string.listen_someone)
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = personColor(by),
                            maxLines = 1,
                        )
                    }
                }
            }
            val rest = room.queue.size - UP_NEXT_SHOWN
            if (rest > 0) {
                item {
                    Text(
                        pluralStringResource(R.plurals.room_up_next_more, rest, rest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** A room's Up next shows this many songs ahead; the rest are counted. */
private const val UP_NEXT_SHOWN = 30

/** About this station: who hosts it, its line, and how it stands. */
@Composable
private fun AboutStation(station: Station, viewModel: SessionRoomViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(station.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                station.hostName?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.room_about_host, it)) }
                station.vibe?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.room_vibe, it)) }
                // The pill under it has the score: this line is the followers alone.
                Text(
                    pluralStringResource(R.plurals.room_about_followers, station.followers, station.followers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The station's vote: dimmed until you've listened 2 minutes in its sessions.
                StationVote(viewModel, Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.room_close)) } },
    )
}
