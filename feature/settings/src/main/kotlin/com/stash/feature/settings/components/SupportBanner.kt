package com.stash.feature.settings.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.stash.core.ui.ambient.ALBUM_CROSSFADE_MS
import com.stash.core.ui.ambient.ALBUM_CROSSFADE_REDUCED_MS
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R

/**
 * Settings' Support card, at the top of the hub (owner 2026-11-01: "hasn't changed since MVP"). The Settings glass
 * (the group cards' fill, hairline and shape) with, while a song plays, a soft wash of the album colour from the top
 * and the album's accent on the bar and the button; neutral (the theme's ink) otherwise, crossfading as the rest of
 * the app's chrome does.
 *
 * Top to bottom: the two people who make Stash (their pictures, overlapping) beside "Support Stash" and one line;
 * this month's goal, "$168 of $200 this month" over a thin bar, when the site gave a number ([SupportCardState.goal]);
 * the perk in one line, opening the Ko-fi claim ([SupportPerk.Offer]); and one Donate button, which opens the
 * [DonateSheet]. A supporter ([SupportPerk.Supporter]) gets thanks and their status instead of the ask, and a quieter
 * "Donate again". "Star on GitHub" lives in Settings › About now.
 *
 * [playing]: a song is playing (the wordmark's rule), so the card wears its album colour. Every link the sheet
 * shows goes through [onOpenUrl]; [onOpenPerk] opens Lossless sources, where the claim is.
 */
@Composable
fun SupportBanner(
    state: SupportCardState,
    playing: Boolean,
    onOpenUrl: (String) -> Unit,
    onOpenPerk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDonateSheet by rememberSaveable { mutableStateOf(false) }
    if (showDonateSheet) {
        DonateSheet(onOpenUrl = onOpenUrl, onDismiss = { showDonateSheet = false })
    }

    val ext = StashTheme.extendedColors
    val scheme = MaterialTheme.colorScheme
    val chrome = LocalAlbumChrome.current
    val fadeMs = if (rememberReducedMotion()) ALBUM_CROSSFADE_REDUCED_MS else ALBUM_CROSSFADE_MS
    val tint by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(fadeMs, easing = FastOutSlowInEasing),
        label = "supportCardTint",
    )
    // Neutral: the theme's own ink (white on dark, aubergine on light) with the page's colour on it.
    val accent = lerp(scheme.onSurface, chrome.accent, tint)
    val onAccent = lerp(scheme.surface, chrome.onAccent, tint)
    val ink = lerp(scheme.onSurfaceVariant, chrome.ink, tint)
    val supporter = state.perk == SupportPerk.Supporter
    val shape = MaterialTheme.shapes.large

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ext.glassBackground)
            // The album's wash, read at draw time so its crossfade only redraws: nothing while no song plays.
            .drawBehind {
                val wash = chrome.accent.copy(alpha = WASH_ALPHA * tint)
                drawRect(Brush.verticalGradient(listOf(wash, Color.Transparent)))
            }
            .border(BorderStroke(1.dp, ext.glassBorder), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Makers()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(if (supporter) R.string.settings_support_thanks_title else R.string.settings_support_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(if (supporter) R.string.settings_support_thanks_line else R.string.settings_support_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        state.goal?.let { GoalMeter(it, fill = accent) }
        if (state.perk == SupportPerk.Offer) PerkLine(ink = ink, onClick = onOpenPerk)
        if (supporter) {
            OutlinedButton(
                onClick = { showDonateSheet = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                border = BorderStroke(1.dp, ext.glassBorderBright),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface),
            ) {
                Icon(Icons.Rounded.Favorite, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_support_donate_again), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Button(
                onClick = { showDonateSheet = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
            ) {
                Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_support_donate), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * rawn and Evo, the two people the donations go to (the same pictures as the [DonateSheet]'s cards): 36 dp circles,
 * the second tucked behind the first, each ringed in the page colour so the overlap reads. Decorative: the sheet
 * names them.
 */
@Composable
private fun Makers() {
    Row(Modifier.clearAndSetSemantics { }) {
        Avatar(R.drawable.donate_avatar_rawn, Modifier.zIndex(1f))
        Avatar(R.drawable.donate_avatar_evo, Modifier.offset(x = -AvatarOverlap))
    }
}

@Composable
private fun Avatar(@DrawableRes picture: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(picture),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(AvatarSize)
            .clip(CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
    )
}

/**
 * "$168 of $200 this month" (the amount in the strong ink) over a 6 dp bar filled in [fill]. TalkBack reads the
 * sentence and the bar as one progress indicator.
 */
@Composable
private fun GoalMeter(goal: GoalLine, fill: Color) {
    val ext = StashTheme.extendedColors
    val sentence = stringResource(R.string.settings_support_goal, goal.raised, goal.target)
    val text = buildAnnotatedString {
        append(sentence)
        val at = sentence.indexOf(goal.raised)
        if (at >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface), at, at + goal.raised.length)
        }
    }
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = sentence
            progressBarRangeInfo = ProgressBarRangeInfo(goal.fraction, 0f..1f)
        },
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(GoalBarHeight)
                .clip(CircleShape)
                .background(ext.glassBorderBright),
        ) {
            if (goal.fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(goal.fraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(fill),
                )
            }
        }
    }
}

/** The perk, one line with the lossless glyph in the album's ink, tapping through to the Ko-fi claim. */
@Composable
private fun PerkLine(ink: Color, onClick: () -> Unit) {
    val label = stringResource(R.string.settings_support_perk_open)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.settings_support_perk),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = StashTheme.extendedColors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

private val AvatarSize = 36.dp
private val AvatarOverlap = 10.dp
private val GoalBarHeight = 6.dp

/** How strong the album's wash is at the top of the card while a song plays. */
private const val WASH_ALPHA = 0.16f
