package com.stash.feature.settings.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R
import com.stash.feature.settings.STASH_GITHUB_URL

/**
 * Settings' Support card, at the top of the hub (owner 2026-11-01: "hasn't changed since MVP"). The Settings glass
 * (the group cards' fill, hairline and shape) with, while a song plays, the album's wash from the top and its accent
 * on the bar and the button; neutral (the theme's ink) otherwise. How far is `AlbumChrome.tint`, the app-wide fade
 * the wordmark and the tab bar follow, so the card is already in step when Settings opens and turns with them.
 *
 * Top to bottom: the two people who make Stash (their pictures, overlapping) beside "Support Stash" and one line;
 * this month's goal, "$168 of $200 this month" over a thin bar, when the site gave a number ([SupportCardState.goal]);
 * and one Donate button, which opens the [DonateSheet]. A supporter ([SupportCardState.supporter]) gets thanks and
 * their status instead of the ask, and a quieter "Donate again". Right of it, a small outlined "Star" opens Stash on
 * GitHub (owner 2026-11-04: back beside Donate, where people see it; Settings › About keeps its row, which is where
 * Settings search sends "github"). The supporter claim lives in Lossless sources (owner 2026-11-03: a perk line here
 * only repeated it).
 *
 * Every link the sheet shows goes through [onOpenUrl].
 */
@Composable
fun SupportBanner(
    state: SupportCardState,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDonateSheet by rememberSaveable { mutableStateOf(false) }
    if (showDonateSheet) {
        DonateSheet(onOpenUrl = onOpenUrl, onDismiss = { showDonateSheet = false })
    }

    val ext = StashTheme.extendedColors
    val scheme = MaterialTheme.colorScheme
    val chrome = LocalAlbumChrome.current
    val tint = chrome.tint
    // Neutral: the theme's own ink (white on dark, aubergine on light) with the page's colour on it.
    val accent = lerp(scheme.onSurface, chrome.accent, tint)
    val onAccent = lerp(scheme.surface, chrome.onAccent, tint)
    val ink = lerp(scheme.onSurfaceVariant, chrome.ink, tint)
    val shape = MaterialTheme.shapes.large

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ext.glassBackground)
            // The album's wash (AlbumChrome.wash, its own strength) from the top, read at draw time so a song change
            // only redraws it; none while no song plays.
            .drawBehind {
                val wash = chrome.wash
                drawRect(Brush.verticalGradient(listOf(wash.copy(alpha = wash.alpha * chrome.tint), Color.Transparent)))
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
                    text = stringResource(if (state.supporter) R.string.settings_support_thanks_title else R.string.settings_support_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(if (state.supporter) R.string.settings_support_thanks_line else R.string.settings_support_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        state.goal?.let { GoalMeter(it, fill = accent) }
        // Donate takes the width; Star takes only what its word needs. Both share one minimum height so the pair
        // lines up (48 dp beside the filled Donate, 44 dp beside the quieter "Donate again").
        val buttonMinHeight = if (state.supporter) 44.dp else 48.dp
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.supporter) {
                OutlinedButton(
                    onClick = { showDonateSheet = true },
                    modifier = Modifier.weight(1f).heightIn(min = buttonMinHeight),
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
                    modifier = Modifier.weight(1f).heightIn(min = buttonMinHeight),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                ) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_support_donate), style = MaterialTheme.typography.labelLarge)
                }
            }
            StarButton(onClick = { onOpenUrl(STASH_GITHUB_URL) }, iconTint = ink, minHeight = buttonMinHeight)
        }
    }
}

/**
 * "★ Star": the "Donate again" outline (bright hairline, the theme's ink for the word) so the card keeps one filled
 * button. Narrower padding than a default button: it sits beside Donate and should read as the smaller action. The
 * word on screen is "Star"; TalkBack reads "Star Stash on GitHub", the About row's title, since a bare "Star" could
 * mean favourite.
 *
 * The word has its own string, `settings_support_star_button`: the translations of the older `settings_support_star`
 * are phrases ("Mit Stern markieren"), and since this button is measured before Donate they squeezed it until German
 * at 360 dp broke "Spenden" mid-word. One line and a 120 dp cap keep a long translation of the new one from doing it
 * again; it ellipsizes instead.
 */
@Composable
private fun StarButton(onClick: () -> Unit, iconTint: Color, minHeight: Dp) {
    val spoken = stringResource(R.string.settings_about_star)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = minHeight).widthIn(max = 120.dp),
        border = BorderStroke(1.dp, StashTheme.extendedColors.glassBorderBright),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.settings_support_star_button),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = spoken },
        )
    }
}

/**
 * rawn and Evo, the two people the donations go to (the same pictures as the [DonateSheet]'s cards): 36 dp circles,
 * the second tucked behind the first, each ringed in the page colour so the overlap reads. Decorative: the sheet
 * names them.
 */
@Composable
private fun Makers() {
    Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(-AvatarOverlap)) {
        Avatar(R.drawable.donate_avatar_rawn, Modifier.zIndex(1f))
        Avatar(R.drawable.donate_avatar_evo)
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

private val AvatarSize = 36.dp
private val AvatarOverlap = 10.dp
private val GoalBarHeight = 6.dp
