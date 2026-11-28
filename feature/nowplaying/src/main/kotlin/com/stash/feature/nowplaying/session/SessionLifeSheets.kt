package com.stash.feature.nowplaying.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.listen.SongStats
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R

@Composable
private fun TrayLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** One text face in the tray, over its name ("/shrug"). A screen reader says the name, not the face's characters. */
@Composable
private fun FaceButton(id: String, onClick: () -> Unit) {
    val face = faceText(id)
    if (face != null) {
        val name = faceName(id)
        Column(
            Modifier
                .heightIn(min = 52.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = name.drop(1) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(face, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/**
 * The React tray (UX spec §4.1): everything a person can send in one place. The text faces, each over its name
 * ("/shrug"), the big ones in their own row, then the 10 phrases. It stays open after a reaction (people send
 * several) and closes after a phrase. [greet]: "Hi all" is lit, for a newcomer's first seconds.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReactTray(greet: Boolean, onReact: (String) -> Unit, onSay: (String) -> Unit, onDismiss: () -> Unit) {
    val ground = StashTheme.extendedColors.elevatedSurface
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ground,
        properties = com.stash.core.ui.components.sheetBarsFollow(ground),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.room_react), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val (big, small) = RoomProtocol.FACES.partition(::isBigFace)
            FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                small.forEach { id -> FaceButton(id) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onReact(id) } }
            }
            TrayLabel(stringResource(R.string.life_big))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                big.forEach { id -> FaceButton(id) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onReact(id) } }
            }
            TrayLabel(stringResource(R.string.life_say))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoomProtocol.PHRASES.forEach { id ->
                    val words = phraseRes(id)
                    val lit = greet && id == "hi"
                    val scheme = MaterialTheme.colorScheme
                    if (words != null) Text(
                        stringResource(words),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = if (lit) scheme.onPrimary else scheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .background(if (lit) scheme.primary else scheme.onSurface.copy(alpha = 0.08f))
                            .clickable(role = Role.Button) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSay(id)
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** One feed line's words. */
@Composable
private fun feedText(line: FeedLine): String? = when (line) {
    is FeedLine.Joined -> stringResource(R.string.life_joined, line.name ?: stringResource(R.string.listen_someone))
    is FeedLine.Said -> phraseRes(line.phrase)?.let { words ->
        if (line.count > 1) stringResource(R.string.life_said_many, stringResource(words), line.count)
        else stringResource(R.string.life_said, line.name ?: stringResource(R.string.listen_someone), stringResource(words))
    }
    is FeedLine.Favourite -> stringResource(R.string.life_favourite_line, line.title)
    is FeedLine.Back -> stringResource(R.string.life_back, line.name ?: stringResource(R.string.listen_someone))
    FeedLine.LastSong -> stringResource(R.string.life_last_song)
    is FeedLine.OffDecks -> stringResource(R.string.decks_feed_off, line.name ?: stringResource(R.string.listen_someone))
}

/** The feed under the crowd: the last few lines, newest at the bottom, older ones fading. */
@Composable
internal fun FeedLines(feed: Feed, ink: Color, modifier: Modifier = Modifier) {
    if (feed.lines.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val last = feed.lines.lastIndex
        feed.lines.forEachIndexed { i, entry ->
            key(entry.id) {
                val text = feedText(entry.line)
                val fade by animateFloatAsState(targetValue = 1f - 0.22f * (last - i), label = "feed")
                if (text != null) Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(fade.coerceIn(0.2f, 1f)),
                )
            }
        }
    }
}

/** Under the song: "Crowd favourite" once it is one, and how many saved it. Nothing until there's something to say. */
@Composable
internal fun SongCrowdLine(stats: SongStats?, accent: Color, muted: Color, modifier: Modifier = Modifier) {
    if (stats == null || (!stats.favourite && stats.saves == 0)) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stats.favourite) {
            Row(
                Modifier.clip(CircleShape).border(1.dp, accent.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp).clearAndSetSemantics { })
                Text(
                    stringResource(R.string.life_favourite),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                )
            }
        }
        if (stats.saves > 0) {
            Text(
                pluralStringResource(R.plurals.life_saved, stats.saves, stats.saves),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
        }
    }
}
