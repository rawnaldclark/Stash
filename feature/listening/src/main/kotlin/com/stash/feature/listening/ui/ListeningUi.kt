package com.stash.feature.listening.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.listening.ListeningRange
import com.stash.core.ui.components.CrispChipRow
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.listening.stats.ListeningInsights

/** Side gutter shared with Home and Library (20 dp). */
internal val Gutter = 20.dp

/** The screens' app bar: back, a title, and optional actions on the right. */
@Composable
internal fun ListeningTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** The range chips (7 days · 30 days · 3 months · 12 months · All time). */
@Composable
internal fun RangeChips(selected: ListeningRange, onSelect: (ListeningRange) -> Unit, modifier: Modifier = Modifier) {
    val ranges = ListeningInsights.RANGES
    CrispChipRow(
        chips = ranges.map { it.label },
        selected = selected.label,
        onSelect = { label -> ranges.firstOrNull { it.label == label }?.let(onSelect) },
        modifier = modifier,
    )
}

/**
 * A card on the stats screens: the design system's glass (GlassCard's colours, border and
 * shape) with the screen gutter around it.
 */
@Composable
internal fun StatsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = StashTheme.extendedColors
    val shape = MaterialTheme.shapes.large
    val inner: @Composable () -> Unit = { Column(Modifier.padding(contentPadding), content = content) }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = colors.glassBackground,
            border = BorderStroke(1.dp, colors.glassBorder),
            content = inner,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = colors.glassBackground,
            border = BorderStroke(1.dp, colors.glassBorder),
            content = inner,
        )
    }
}

/** A section title inside the screen, with an optional action ("See all") on the right. */
@Composable
internal fun StatsSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = Gutter, end = Gutter - 4.dp, top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(4.dp),
            )
        }
    }
}

/** Cover art (a file on the phone first, else the URL, upgraded), or a placeholder glyph. */
@Composable
internal fun ArtImage(
    path: String?,
    url: String?,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    placeholder: ImageVector = Icons.Default.MusicNote,
) {
    val colors = StashTheme.extendedColors
    Box(
        modifier = modifier.size(size).clip(shape).background(colors.elevatedSurface),
        contentAlignment = Alignment.Center,
    ) {
        val model = path ?: ArtUrlUpgrader.upgrade(url)
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(placeholder, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(size * 0.4f))
        }
    }
}

/** The artist placeholder glyph. */
internal val ArtistGlyph: ImageVector get() = Icons.Rounded.Person

/**
 * One ranked row: a big rank numeral (Space Grotesk), the art, title and subtitle, and the
 * numbers on the right ("23 plays" over "1 h 12 m"). TalkBack reads it as one sentence.
 */
@Composable
internal fun RankedRow(
    rank: Int,
    title: String,
    subtitle: String,
    plays: String,
    time: String?,
    onClick: () -> Unit,
    art: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = StashTheme.extendedColors
    val spoken = buildString {
        append("Number $rank, $title")
        if (subtitle.isNotEmpty()) append(", $subtitle")
        append(", $plays")
        if (time != null) append(", $time")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = Gutter, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = if (rank >= 100) 17.sp else 20.sp),
            color = if (rank == 1) MaterialTheme.colorScheme.primary else colors.textTertiary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            // Grows with the font size, so "100" never clips at large text.
            modifier = Modifier.width(34.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
        )
        Spacer(Modifier.width(10.dp))
        art()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            Text(plays, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (time != null) {
                Text(time, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 1)
            }
        }
    }
}

/** The footnote under estimated numbers. */
@Composable
internal fun EstimatedFootnote(modifier: Modifier = Modifier) {
    Text(
        text = "Some listening time is estimated from song length.",
        style = MaterialTheme.typography.bodySmall,
        color = StashTheme.extendedColors.textTertiary,
        modifier = modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 16.dp),
    )
}

/** Circle for artists, rounded square for songs and albums. */
internal val CircleArt: Shape = CircleShape
internal val SquareArt: Shape = RoundedCornerShape(8.dp)
