package com.stash.core.ui.components.streaming

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.common.constants.StashConstants
import com.stash.core.ui.theme.StashTheme

/**
 * Compact status chip rendered in the Home top bar (between the Stash
 * wordmark and the GitHub-issue icon). Shows the current playback mode
 * — "Online" or "Offline" — with a matching glyph, and routes taps to
 * [onClick] so the host screen can pop the bottom sheet that holds the
 * picker proper.
 *
 * Visibility is gated on [StashConstants.STREAMING_ENGINE_ENABLED] for
 * consistency with the rest of the streaming surfaces; when the flag
 * is off the composable renders nothing.
 *
 * Visual: pill-shaped glass with a primary-tinted accent border. Stays
 * intentionally small so it doesn't compete with the wordmark or the
 * trailing utility icons in the top row.
 */
@Composable
fun StreamingModeChip(
    streamingEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!StashConstants.STREAMING_ENGINE_ENABLED) return

    val extendedColors = StashTheme.extendedColors
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (streamingEnabled) Icons.Default.CloudQueue else Icons.Default.OfflinePin,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (streamingEnabled) stringResource(com.stash.core.ui.R.string.mode_online) else stringResource(com.stash.core.ui.R.string.mode_offline),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
