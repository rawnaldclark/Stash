package com.stash.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.stash.data.download.lossless.RoutingRow
import com.stash.data.download.lossless.RoutingState

/**
 * ROUTING status block for the lossless source chain.
 *
 * Renders [rows] verbatim — they are built in
 * [com.stash.data.download.lossless.LosslessAvailability] from the same predicates
 * the source and downloads read, so this block cannot claim a path the resolver
 * does not have. It used to hardcode a single "Qobuz — active" row, which was only
 * ever true while a token pool shipped.
 *
 * Two rules this block has to follow, both learned the hard way:
 *  - **Say what is true right now.** It previously advertised a second source that
 *    "fills in when a track isn't on Qobuz", and that line stayed on screen long
 *    after the source was gone. A panel that explains where the user's audio comes
 *    from is worse than useless when it is wrong. Hence: no hardcoded rows here, ever.
 *  - **Name what the user recognises, not our plumbing.** "Qobuz" is a service they
 *    can look up; a proxy hostname means nothing to them and exposes our supply
 *    chain. When a source is retired, its row goes — do not leave it greyed out as
 *    decoration.
 *
 * Visual: mono caps header, indented `↳` rows, small status dots.
 *
 * Honesty caveat: rows describe CONFIGURATION, not liveness. There is still no
 * health telemetry — that arrives with the relay's `/v1/status` in Plan B — and a
 * connected account inside its dead-cooldown must not flicker to "offline", so a
 * row never claims a path is reachable, only that it is set up.
 */
@Composable
internal fun LosslessRoutingStatus(
    rows: List<RoutingRow>,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return // pre-first-emission; the header alone would read as "no sources"
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
        rows.forEach { row ->
            RoutingRowLine(
                host = row.label,
                configured = row.state != RoutingState.NOT_CONFIGURED,
                statusLabel = row.detail,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            // Never enumerate sources here: the rows above are the authority, and a
            // copy that names paths ends up crediting a user with ones they don't have.
            text = "Lossless comes from the sources above. Misses try JioSaavn before " +
                "falling back to YouTube, shown as \"via YT\" while it plays.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Single row inside [LosslessRoutingStatus]: indent arrow, host name,
 * status dot + label.
 */
@Composable
private fun RoutingRowLine(
    host: String,
    configured: Boolean,
    statusLabel: String,
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
    }
}
