package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.stash.core.ui.R
import com.stash.core.ui.theme.StashTheme

/**
 * Two-tile mode picker for streaming Online vs Offline. Selected tile is
 * filled with primary-tinted glass; the other is muted. Tapping either
 * fires [onSelect] with the chosen mode (`true` = streaming, `false` =
 * offline). Re-tapping the already-selected tile is a no-op via the
 * caller's responsibility (we still emit, which the caller can dedupe).
 *
 * Used in two places:
 *  - The "Playback" section of [SettingsScreen] as the canonical home.
 *  - The bottom sheet that opens from the [StreamingModeChip] on Home,
 *    so quick flips and Settings show the same control surface.
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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeTile(
            icon = Icons.Default.CloudQueue,
            label = stringResource(R.string.mode_online),
            selected = streamingEnabled,
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f),
        )
        ModeTile(
            icon = Icons.Default.OfflinePin,
            label = stringResource(R.string.mode_offline),
            selected = !streamingEnabled,
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeTile(
    icon: ImageVector,
    label: String,
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

    // Horizontal layout — icon + label on one line. Half the vertical
    // height of the original two-stack tile while keeping the same
    // "two big tap targets" affordance.
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
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
}
