package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stash.core.ui.R
import com.stash.core.ui.theme.StashTheme

/**
 * Two-tile picker for the one mode switch: "Online" vs "Offline" (called
 * "Stream only" / "Download" until 2026-11-01; the preference and its names
 * in code kept the old words). Offline: only downloaded songs play, while Sync
 * still saves switched-on playlists over the network. Online: everything plays
 * and what isn't downloaded streams. Each tile says so in one short line
 * under its word ("Streams and plays downloads" / "Downloads only"); the
 * sheet's old explainer paragraph is gone (2026-11-04). Selected tile is
 * filled with primary-tinted glass; the other is muted. Tapping either
 * fires [onSelect] with the chosen mode (`true` = Online, `false` =
 * Offline). Re-tapping the already-selected tile is a no-op via the
 * caller's responsibility (we still emit, which the caller can dedupe).
 *
 * Used in the bottom sheet that opens from the [StreamingModeChip] on
 * Home and on the artist/album heroes; the Sync tab's switch and the
 * Settings › Playback segmented control write the same preference. The
 * owner keeps all of them on purpose (2026-09-19): a flip should never
 * need a trip back to one page.
 *
 * Stays presentation-only: no state, no DI, no preference reading.
 * Callers hand in the current value and a callback.
 */
@Composable
fun OnlineOfflinePicker(
    streamingEnabled: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // IntrinsicSize.Min + fillMaxHeight: the Online caption can wrap to two lines on
    // a narrow phone or a large font while "Downloads only" stays on one; both tiles
    // still take the taller one's height so they read as a pair.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeTile(
            icon = Icons.Default.CloudQueue,
            label = "Online",
            caption = stringResource(R.string.mode_online_caption),
            selected = streamingEnabled,
            onClick = { onSelect(true) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        ModeTile(
            icon = Icons.Default.OfflinePin,
            label = "Offline",
            caption = stringResource(R.string.mode_offline_caption),
            selected = !streamingEnabled,
            onClick = { onSelect(false) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun ModeTile(
    icon: ImageVector,
    label: String,
    caption: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val accent = MaterialTheme.colorScheme.primary

    // Selected: primary-tinted glass + accent border. Unselected: muted
    // glass surface, lower opacity content so the active tile reads as
    // the answer at a glance.
    val bg: Color
    val borderColor: Color
    val contentColor: Color
    if (selected) {
        bg = accent.copy(alpha = 0.16f)
        borderColor = accent.copy(alpha = 0.55f)
        contentColor = MaterialTheme.colorScheme.onSurface
    } else {
        bg = extendedColors.glassBackground
        borderColor = extendedColors.glassBorder
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    }

    // Icon + word on the first line, as before; the caption sits under them so what a
    // mode does is written on the tile you tap. The caption is quieter than the word
    // (onSurfaceVariant) and dims a little more on the tile that isn't picked, but
    // stays readable there: it is what you read before choosing.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (selected) 1f else 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}
