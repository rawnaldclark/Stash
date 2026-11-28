package com.stash.feature.nowplaying.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.ServerMessage
import com.stash.core.ui.components.personColor
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R
import com.stash.feature.nowplaying.listen.initials
import kotlinx.coroutines.flow.SharedFlow

/** Dark ink on a person's colour, as every face in a room. */
internal val FaceInk = Color(0xFF10101A)

/** The host's crown and ring. */
private val Crown = Color(0xFFFBBF24)

private val BoothFace = 38.dp
private val FloorFace = 32.dp

/** How far a floor face overlaps the one before it. */
private val FloorOverlap = 6.dp

/** How many faces the floor shows before "+N" (the mockup's four, on a phone's width). */
internal const val FLOOR_FITS = 5

/** A text face on its way up: [frames] as [faceFrames] gives them. [animated]: a big one, drawn larger, stepping between its frames. */
private data class Floater(val id: Long, val frames: List<String>, val from: String, val x: Float, val animated: Boolean = false)

/** How many times a big face steps between its frames on the way up. */
private const val FACE_STEPS = 6

private const val MAX_FLOATERS = 10

/**
 * The room's crowd in one band (UX spec 2026-11-20 §4.3, the room mockup): the booth on the left (the host under
 * a crown, then co-hosts, each ringed), a hairline, then the floor's faces overlapping in the order they joined
 * and "+N" for the rest, with a line of names under them. A tap shows everyone.
 *
 * Reactions rise from the face that sent them, at most [ReactionGate]'s four a second; one from someone past
 * "+N" rises from there. With animations off ([animate] false) they show above the face and fade without
 * travelling.
 */
@Composable
internal fun CrowdBand(
    crowd: Crowd,
    myId: String,
    reactions: SharedFlow<ServerMessage.Reaction>,
    animate: Boolean,
    onShowEveryone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = StashTheme.extendedColors
    val shape = MaterialTheme.shapes.extraLarge
    val gap = MaterialTheme.colorScheme.background
    // Where each face sits on screen (its centre, px from the window's start), and the band itself ([0] its start,
    // [1] its width): a reaction's place is worked out from them when it arrives. Plain holders, not state: nothing
    // draws from them.
    val faceX = remember { HashMap<String, Float>() }
    val band = remember { FloatArray(2) }
    val floaters = remember { mutableStateListOf<Floater>() }
    LaunchedEffect(reactions) {
        val gate = ReactionGate()
        var next = 0L
        reactions.collect { r ->
            // One this app doesn't know (a newer app's face) shows as nothing.
            val frames = faceFrames(r.emoji) ?: return@collect
            if (!gate.allow(System.currentTimeMillis())) return@collect
            if (floaters.size >= MAX_FLOATERS) floaters.removeAt(0)
            // Someone past "+N" (or not placed yet) rises from the "+N" bubble, or the band's end.
            val x = (faceX[r.from] ?: faceX[MORE])?.let { it - band[0] } ?: (band[1] * 0.8f)
            floaters += Floater(next++, frames, r.from, x, animated = frames.size > 1)
        }
    }
    Box(modifier.fillMaxWidth().onGloballyPositioned { band[0] = it.positionInRoot().x; band[1] = it.size.width.toFloat() }) {
        val names = crowdLine(crowd, myId)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ext.glassBackground)
                .border(1.dp, ext.glassBorder, shape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.room_everyone), onClick = onShowEveryone)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = names },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (crowd.booth.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (seat in crowd.booth) BoothSeat(seat, gap, Modifier.onGloballyPositioned { faceX[seat.member.id] = it.centreOnScreen() })
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(1.dp).height(BoothFace).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    crowd.floor.forEachIndexed { i, member ->
                        Face(
                            member = member,
                            size = FloorFace,
                            ring = gap,
                            modifier = Modifier.offset(x = -FloorOverlap * i).onGloballyPositioned { faceX[member.id] = it.centreOnScreen() },
                        )
                    }
                    if (crowd.more > 0) {
                        Box(
                            Modifier
                                .offset(x = -FloorOverlap * crowd.floor.size)
                                .size(FloorFace)
                                .border(2.dp, gap, CircleShape)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
                                .onGloballyPositioned { faceX[MORE] = it.centreOnScreen() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.room_more, crowd.more),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    names,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
        // The reactions, over the band and above it. Decorative: TalkBack skips them.
        val density = LocalDensity.current
        Box(Modifier.matchParentSize().clearAndSetSemantics { }) {
            floaters.forEach { f ->
                key(f.id) {
                    val progress = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        progress.animateTo(1f, tween(durationMillis = if (animate) 2_600 else 1_200, easing = LinearOutSlowInEasing))
                        floaters.remove(f)
                    }
                    val rise = with(density) { 84.dp.toPx() }
                    // A big one steps between its frames; only a change of frame recomposes, not each frame of the rise.
                    val frame by remember { derivedStateOf { if (f.animated && animate) (progress.value * FACE_STEPS).toInt() % f.frames.size else f.frames.lastIndex } }
                    Box(
                        Modifier
                            // Read in the draw phase: a frame of the rise doesn't recompose the room. A pill is as wide
                            // as its face, so it is centred on the face that sent it by its own size.
                            .graphicsLayer {
                                val p = progress.value
                                translationX = f.x - size.width / 2f
                                translationY = if (animate) -rise * p - size.height / 2f else -size.height
                                // In over the first eighth, then fading out as it climbs.
                                alpha = if (p < 0.12f) p / 0.12f else 1f - (p - 0.12f) / 0.88f
                                val grow = if (f.animated) 1.3f else 1f
                                val s = (if (p < 0.12f) 0.6f + 0.4f * (p / 0.12f) else 1f) * grow
                                scaleX = s
                                scaleY = s
                                // A big one rocks a little as it climbs.
                                if (f.animated && animate) rotationZ = 5f * kotlin.math.sin(p * 4f * Math.PI.toFloat())
                            }
                            .heightIn(min = 30.dp)
                            .background(Color(0xE60B0B12), CircleShape)
                            .border(2.dp, personColor(f.from), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(f.frames[frame], color = Color(0xFFE8E8F0), fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** The key of the "+N" bubble among the faces' places. No member id is this short. */
private const val MORE = "+"

/** A face's centre, in px from the window's start. */
private fun LayoutCoordinates.centreOnScreen(): Float = positionInRoot().x + size.width / 2f

/** The host under a crown with the gold ring, or a co-host with a quieter ring; "host" or "co-host" under the face. */
@Composable
private fun BoothSeat(seat: Seat, gap: Color, modifier: Modifier = Modifier) {
    val ring = if (seat.host) Crown else personColor(seat.member.id).copy(alpha = 0.55f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = 6.dp).size(BoothFace + 6.dp).border(2.dp, ring, CircleShape).padding(3.dp)) {
                Face(seat.member, size = BoothFace, ring = gap)
            }
            if (seat.host) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = Crown, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            stringResource(if (seat.host) R.string.room_host else R.string.room_cohost),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** One person: their room colour and initials, ringed in the ground's colour so overlapping faces read apart. */
@Composable
internal fun Face(member: RoomMember, size: Dp, ring: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        Box(
            Modifier.matchParentSize().border(2.dp, ring, CircleShape).padding(2.dp).clip(CircleShape).background(personColor(member.id)),
            contentAlignment = Alignment.Center,
        ) {
            val letter = (size.value * 0.36f).sp
            Text(
                initials(member.name),
                color = FaceInk,
                fontWeight = FontWeight.Bold,
                fontSize = letter,
                lineHeight = letter * 1.2f,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        // A regular at this station: a small star on the face's corner.
        if (member.regular) {
            val star = size * 0.42f
            Box(
                Modifier.align(Alignment.BottomEnd).size(star).background(ring, CircleShape).padding(1.dp).background(RegularGold, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = FaceInk, modifier = Modifier.size(star * 0.7f).clearAndSetSemantics { })
            }
        }
    }
}

/** The regular's star. */
private val RegularGold = Color(0xFFF2C14E)

/**
 * "You, Lee, Jo and 8 more": this phone first when it's on the floor, then the floor's first names, then how many
 * others are there. With nobody on the floor: "Nobody's tuned in yet".
 */
@Composable
internal fun crowdLine(crowd: Crowd, myId: String): String {
    val you = stringResource(R.string.room_you)
    val someone = stringResource(R.string.listen_someone)
    if (crowd.floor.isEmpty() && crowd.more == 0) return stringResource(R.string.room_nobody)
    // "You" leads when this phone is on the floor, wherever it joined (even past "+N"): the line is about who you're with.
    val onFloor = crowd.booth.none { it.member.id == myId } && crowd.everyone.any { it.id == myId }
    val others = crowd.floor.filter { it.id != myId }.map { it.name?.takeIf(String::isNotBlank) ?: someone }
    val named = if (onFloor) listOf(you) + others.take(NAMES_SHOWN - 1) else others.take(NAMES_SHOWN)
    val rest = crowd.floor.size + crowd.more - named.size
    val list = named.joinToString(", ")
    return if (rest > 0) pluralStringResource(R.plurals.room_names_more, rest, list, rest) else list
}

private const val NAMES_SHOWN = 3
