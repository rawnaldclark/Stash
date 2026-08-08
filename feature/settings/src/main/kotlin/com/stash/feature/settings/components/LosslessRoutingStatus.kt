package com.stash.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ROUTING status block for the lossless source chain.
 *
 * Shows the live chain. As of 2026-07-31 that is Qobuz, full stop — amz and arcod
 * were parked, and kennyy/squid before them.
 *
 * Two rules this block has to follow, both learned the hard way:
 *  - **Say what is true right now.** It previously advertised "amz.squid.wtf fills
 *    in when a track isn't on Qobuz", which stayed on screen after amz was parked.
 *    A panel that explains where the user's audio comes from is worse than useless
 *    when it is wrong.
 *  - **Name what the user recognises, not our plumbing.** "Qobuz" is a service they
 *    can look up; `amz.squid.wtf` is a proxy hostname that means nothing to them and
 *    exposes our supply chain. When a source is parked, its row goes — do not leave
 *    it greyed out as decoration.
 *
 * Visual: mono caps header, indented `↳` rows, small status dots.
 *
 * Honesty caveat: no ping/health telemetry yet, so we never claim "live" — we
 * use "active" (= reachable in the resolver chain) and "fallback".
 */
@Composable
internal fun LosslessRoutingStatus(
    modifier: Modifier = Modifier,
) {
    val mono = FontFamily.Monospace
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "ROUTING",
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        // Direct Qobuz is the primary lossless source; amz.squid.wtf (Amazon
        // Music) is an independent fallback for tracks Qobuz doesn't carry.
        RoutingRow(
            host = "Qobuz",
            configured = true,
            statusLabel = "active",
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Lossless comes from Qobuz. Misses try JioSaavn AAC 320 before falling " +
                "back to YouTube, shown as \"via YT\" while it plays.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Single row inside [LosslessRoutingStatus]: indent arrow, host name,
 * status dot + label. Optional action link (e.g. "solve captcha →") on
 * the right when the source needs user setup.
 */
@Composable
internal fun RoutingRow(
    host: String,
    configured: Boolean,
    statusLabel: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val mono = FontFamily.Monospace
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↳",
            fontFamily = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = host,
            fontFamily = mono,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Status dot — filled-primary when configured, outlined-muted when not.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (configured) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                )
                .border(
                    width = if (configured) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusLabel,
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = actionLabel,
                fontFamily = mono,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}
