package com.stash.feature.community.sessions

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.model.community.CrowdFace
import com.stash.core.model.community.LiveSession
import com.stash.core.model.community.Station
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.components.personColor
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.community.CoverArt
import com.stash.feature.community.R
import java.time.ZoneId
import java.util.Locale

/*
 * Sessions' cards on Home (UX spec 2026-11-20 §3, the Home mockup): the round cards of the stations you follow and
 * the wide Live now cards, each a window into a room. Tapping one on the air is the join: no preview, no confirm.
 * In the app's own family: glass cards with a hairline, the theme's type, and people in their room colours.
 */

/** The red of everything on the air: the dot, a live station's ring, the LIVE badge's dot. */
internal val LiveRed = Color(0xFFF05252)
private val LivePink = Color(0xFFF472B6)

/** Dark ink on a person's colour, as a room's faces. */
internal val FaceInk = Color(0xFF10101A)

/** "LIVE" as words on the page: soft red on the dark themes, a deep one on the light theme (4.5:1 on both). */
@Composable
internal fun liveInk(): Color = if (LocalIsDarkTheme.current) Color(0xFFF4A3A3) else Color(0xFFB91C1C)

/** The width of a Live now card: one whole card and the start of the next on a phone, so the row reads as a row. */
private val LiveCardWidth = 296.dp
private val LiveArtSize = 84.dp
private val StationCircleSize = 64.dp
private val StationCardWidth = 70.dp

/**
 * Home's two rows, above Community's posts: Your stations (when you follow any), then Live now with a way to every
 * session. With nobody live, Live now is one line saying so: the way in to Sessions stays.
 */
@Composable
internal fun SessionsRows(
    rows: SessionsRows,
    tuningIn: String?,
    now: Long,
    onTuneIn: (LiveSession) -> Unit,
    onStation: (Station) -> Unit,
    onAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (rows.yourStations.isNotEmpty()) {
            RowLabel(stringResource(R.string.sessions_your_stations), Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(rows.yourStations, key = { it.id }) { station ->
                    StationCircle(station, tuning = tuningIn == station.id, now = now, onClick = { onStation(station) })
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        LiveNowHeader(count = rows.liveCount, anyLive = rows.live.isNotEmpty(), onAll = onAll)
        if (rows.live.isEmpty()) {
            Text(
                stringResource(R.string.sessions_nobody_live),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rows.live, key = { it.station.id }) { session ->
                    LiveCard(session, tuning = tuningIn == session.station.id, onClick = { onTuneIn(session) })
                }
            }
        }
    }
}

/** A row's small label in capitals: "YOUR STATIONS". */
@Composable
internal fun RowLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

/** ● LIVE NOW · 6 stations, and All sessions at the end (as a section header's action). The dot is still with nobody live. */
@Composable
private fun LiveNowHeader(count: Int?, anyLive: Boolean, onAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(8.dp, pulsing = anyLive, color = if (anyLive) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        RowLabel(stringResource(R.string.sessions_live_now), color = if (anyLive) liveInk() else MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null && count > 0) {
            Text(
                " · " + pluralStringResource(R.plurals.sessions_live_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.sessions_all),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button, onClick = onAll).padding(4.dp),
        )
    }
}

/** The on-air dot. It breathes (2 s) unless animations are off; drawn in the layer, so nothing recomposes for it. */
@Composable
internal fun LiveDot(size: Dp, modifier: Modifier = Modifier, pulsing: Boolean = true, color: Color = LiveRed) {
    val dot = modifier.size(size).background(color, CircleShape)
    if (!pulsing || rememberReducedMotion()) {
        Box(dot)
        return
    }
    val breath by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "liveDot",
    )
    Box(Modifier.graphicsLayer { alpha = breath }.then(dot))
}

/**
 * One of Your stations. On the air: the live ring, the cover of the song playing, "LIVE · 14". Off the air: quieter,
 * the station's own colour and letter, and when it's next on ("8:30 AM", "Fri 9:00 PM") if it keeps hours.
 */
@Composable
private fun StationCircle(station: Station, tuning: Boolean, now: Long, onClick: () -> Unit) {
    val live = station.live
    val interaction = remember { MutableInteractionSource() }
    val ring = if (live != null) Brush.sweepGradient(listOf(LiveRed, LivePink, LiveRed)) else SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
    Column(
        Modifier
            .width(StationCardWidth)
            .alpha(if (live != null) 1f else 0.7f)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = live?.full != true,
                role = Role.Button,
                onClickLabel = stringResource(if (live != null) R.string.sessions_tune_in else R.string.sessions_open_station),
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(StationCircleSize).background(ring, CircleShape).padding(3.dp)) {
            // A gap in the page's colour between the ring and the picture.
            Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(2.dp)) {
                if (live != null) {
                    CoverArt(listOfNotNull(live.track?.artUrl), Modifier.fillMaxSize(), corner = StationCircleSize)
                } else {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(personColor(station.id)), contentAlignment = Alignment.Center) {
                        Text(station.initial(), color = FaceInk, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics { })
                    }
                }
                if (tuning) TuningIn(Modifier.clip(CircleShape))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            station.name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val config = LocalConfiguration.current
        val status = when {
            live != null && live.full -> stringResource(R.string.sessions_full)
            live != null -> stringResource(R.string.sessions_live_listeners, live.listeners)
            else -> onAirLabel(station.next, now, ZoneId.systemDefault(), config.locales[0])?.uppercase(config.locales[0])
                ?: stringResource(R.string.sessions_off_air)
        }
        Text(
            status,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
            color = if (live != null) liveInk() else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A session on the air, as a window into its room: the cover of the song playing with LIVE on it, the station's name
 * (and a mark when you follow it), its vibe line, the song, then the first faces in their room colours, how many are
 * listening and the station's score. A full one says Full and takes no tap.
 */
@Composable
private fun LiveCard(session: LiveSession, tuning: Boolean, onClick: () -> Unit) {
    val ext = StashTheme.extendedColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = MaterialTheme.shapes.extraLarge
    val station = session.station
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(LiveCardWidth)
            .alpha(if (session.full) 0.6f else 1f)
            .pressScale(interaction)
            .clip(shape)
            .background(ext.glassBackground)
            .border(1.dp, ext.glassBorder, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !session.full,
                role = Role.Button,
                onClickLabel = stringResource(R.string.sessions_tune_in),
                onClick = onClick,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(LiveArtSize)) {
                CoverArt(listOfNotNull(session.track?.artUrl), Modifier.fillMaxSize(), corner = 12.dp)
                LiveBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                if (tuning) TuningIn(Modifier.clip(MaterialTheme.shapes.medium))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        station.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (station.following) FollowingMark()
                }
                station.vibe?.takeIf { it.isNotBlank() }?.let { vibe ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.sessions_vibe, vibe),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                session.track?.let { track ->
                    Spacer(Modifier.height(6.dp))
                    Text(track.nowLine(), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (session.crowd.isNotEmpty()) {
                Faces(session.crowd, size = 24.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when {
                    session.full -> stringResource(R.string.sessions_full)
                    session.listeners <= 0 -> stringResource(R.string.sessions_just_host)
                    else -> pluralStringResource(R.plurals.sessions_listening, session.listeners, session.listeners)
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Score(station.score)
        }
    }
}

/** "LIVE" over a cover: white on a dark pill, whatever the cover and the theme. */
@Composable
internal fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.66f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).background(LiveRed, CircleShape))
        Text(
            stringResource(R.string.sessions_live),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** A small outlined "Following" on the card of a station you follow. */
@Composable
internal fun FollowingMark() {
    Text(
        stringResource(R.string.sessions_following),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * The first faces of a room, the host first, each in their room colour with their initial, overlapping. TalkBack
 * skips them: the line beside them says how many are there.
 */
@Composable
internal fun Faces(crowd: List<CrowdFace>, size: Dp, modifier: Modifier = Modifier) {
    val gap = MaterialTheme.colorScheme.background
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(-(size * 0.21f))) {
        for (face in crowd.take(MAX_FACES)) {
            Box(
                Modifier.size(size).border(2.dp, gap, CircleShape).padding(1.dp).clip(CircleShape).background(personColor(face.id)),
                contentAlignment = Alignment.Center,
            ) {
                // Its own line height: the theme's (24 sp) is taller than a small face, and pushed the letter down and out.
                val letter = (size.value * 0.42f).sp
                Text(face.i?.take(1)?.uppercase() ?: "?", color = FaceInk, fontWeight = FontWeight.Bold, fontSize = letter, lineHeight = letter * 1.2f, maxLines = 1)
            }
        }
    }
}

/** How many faces a card shows (the Worker sends up to 4). */
private const val MAX_FACES = 4

/** A station's score, "▲ 48": TalkBack hears "48 votes". */
@Composable
internal fun Score(score: Int, modifier: Modifier = Modifier) {
    val spoken = pluralStringResource(com.stash.core.ui.R.plurals.votes, score, score)
    Text(
        stringResource(R.string.sessions_score, score),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier.semantics { contentDescription = spoken },
    )
}

/** Over a card's picture while its session is being joined. */
@Composable
private fun TuningIn(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
    }
}
