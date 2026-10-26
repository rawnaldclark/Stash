package com.stash.feature.record.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.stash.feature.record.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.ui.components.BlurredCoverBackdrop
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashElevation

/**
 * Home's Your record card (stash-player HomeCard.svelte and RecordCard.svelte), passed into Home as a slot like Your
 * listening, so feature/home doesn't depend on this module. Dark in every theme, like Daily Discover: its label's cover
 * blurred under a scrim, or Stash's purple.
 *
 * - With [RecordCard.OWN_FOR_RECORD] plays: a preview of the record, in its own numbers. A disc out of a sleeve of the albums
 *   played most, its label the most played album's cover; the hours set large; since when and how many plays; the artist
 *   played most; the top artists' pictures in an overlapping row. The whole card opens the record ([onOpen]).
 * - Before that, once the import exists ([RecordCard.INVITE_ENABLED]): "Press your record" (an empty record out of a plain
 *   sleeve, Get started, which opens the record), with a × that hides it on this phone. Until then, nothing.
 *
 * Composes to nothing until the history is read. Reads nothing when Home comes back: the card's numbers change only when the
 * history does ([RecordCardSource]).
 */
@Composable
fun YourRecordSection(
    onOpen: () -> Unit,
    viewModel: YourRecordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state ?: return
    if (s == RecordCardState.Hidden) return
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        when (s) {
            is RecordCardState.Record -> RecordPreviewCard(s, onOpen, Modifier.padding(horizontal = SidePadding))
            RecordCardState.Invite -> InviteCard(onOpen, viewModel::hideInvite, Modifier.padding(horizontal = SidePadding))
            RecordCardState.Hidden -> Unit
        }
    }
}

/** The card's frame: Daily Discover's family (the large shape, the hero's lift, a blurred cover under a scrim). */
@Composable
private fun CardFrame(
    backdrop: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    overlay: @Composable () -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Box(
        modifier
            .fillMaxWidth()
            .shadow(StashElevation.Hero, shape, clip = false)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier),
    ) {
        BlurredCoverBackdrop(model = backdrop, modifier = Modifier.matchParentSize())
        Row(
            modifier = Modifier.fillMaxWidth().padding(CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        overlay()
    }
}

@Composable
private fun RecordPreviewCard(s: RecordCardState.Record, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val view = s.view
    val label = s.albumArt.firstOrNull()
    val locale = LocalConfiguration.current.locales[0]
    val hours = RecordCard.hours(view.listenedMs)
    val hoursCount = hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val hoursWord = pluralStringResource(R.plurals.record_hours, hoursCount)
    val plays = pluralStringResource(R.plurals.record_plays, view.plays, RecordCard.count(view.plays.toLong(), locale))
    val year = RecordCard.sinceYear(view)
    val line = if (year != null) stringResource(R.string.record_since, year, plays) else plays
    val eyebrow = stringResource(R.string.record_eyebrow)
    val mostPlayed = view.topArtist?.let { stringResource(R.string.record_most_played, it) }
    val opens = stringResource(R.string.record_opens)
    val spoken = listOfNotNull("$eyebrow, ${RecordCard.count(hours, locale)} $hoursWord, $line.", mostPlayed?.let { "$it." }, opens).joinToString(" ")
    CardFrame(
        backdrop = label,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        onClick = onOpen,
        onClickLabel = stringResource(R.string.record_open),
    ) {
        RecordArtwork(
            sleeve = view.albums.mapIndexed { i, a -> SleeveTile(seed = a.album, cover = s.albumArt.getOrNull(i)) },
            label = label,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.78f), maxLines = 1)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.035).em, color = Color.White)) {
                        append(RecordCard.count(hours, locale))
                    }
                    append(" ")
                    append(hoursWord)
                },
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SpaceGrotesk, lineHeight = 38.sp),
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (view.topArtist != null && mostPlayed != null) {
                Text(
                    emphasised(mostPlayed, view.topArtist),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (view.artists.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Faces(view.artists, s.artistArt)
            }
        }
    }
}

/** The top artists' pictures, overlapping, the first on top; initials until a picture comes. */
@Composable
private fun Faces(artists: List<String>, pictures: List<String?>) {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        artists.forEachIndexed { i, name ->
            Box(
                Modifier
                    .zIndex((artists.size - i).toFloat())
                    .size(FaceSize)
                    .clip(CircleShape)
                    .border(2.dp, FaceRing, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Placeholder(name, Modifier.fillMaxSize())
                Text(RecordCard.initials(name), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White, maxLines = 1)
                pictures.getOrNull(i)?.let { url ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** [text] with [part] (where it is) set bold and white: the artist's name in "Most played: …", in any language's order. */
private fun emphasised(text: String, part: String) = buildAnnotatedString {
    append(text)
    val at = text.lastIndexOf(part)
    if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White), at, at + part.length)
}

/**
 * "Press your record": an empty record out of a plain sleeve, Get started, and the × that hides it. No chips for the ways in
 * (Spotify, Last.fm): neither can come in until the import exists, and a promise the card can't keep is a dead end.
 */
@Composable
private fun InviteCard(onOpen: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    CardFrame(
        backdrop = null,
        modifier = modifier,
        overlay = {
            Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = onHide, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.record_invite_hide),
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    ) {
        RecordArtwork(sleeve = null, label = null, modifier = Modifier.clearAndSetSemantics { })
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 20.dp)) {
            val title = stringResource(R.string.record_invite_title)
            val body = stringResource(R.string.record_invite_body)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "$title. $body" },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF111111)),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text(stringResource(R.string.record_invite_start), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp))
            }
        }
    }
}

private val SidePadding = 20.dp
private val CardPadding = 14.dp
private val FaceSize = 28.dp

/** The faces' ring: the card's dark, so each picture reads as its own (the web's rgb(13 11 22 / .85)). */
private val FaceRing = Color(0xD90D0B16)
