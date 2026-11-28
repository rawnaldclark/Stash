package com.stash.feature.settings.components

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.StringRes
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.data.download.lossless.ProbeState
import com.stash.data.download.lossless.RoutingRow
import com.stash.data.download.lossless.RoutingState
import com.stash.feature.settings.R
import java.util.Date

/**
 * ROUTING status block for the lossless source chain.
 *
 * Renders [rows] verbatim and in their order — they are built in
 * [com.stash.data.download.lossless.LosslessAvailability] from the same predicates
 * the source and downloads read, so this block cannot claim a path the resolver
 * does not have. They come in the order a song asks them, which is what the `↳`
 * rows say; never sort them here. No hardcoded rows here, ever.
 *
 * Name what the user recognises, not our plumbing. When a source is retired, its
 * row goes — do not leave it greyed out as decoration.
 *
 * The rows describe CONFIGURATION, not liveness: a connected account inside its
 * dead-cooldown must not flicker to "offline". Liveness is an on-demand snapshot
 * layered on top: [probe] holds the last "Check again" result per row id, and the
 * dot and pill follow it once one exists. Before a check, rows read as they always did.
 *
 * Visual: mono caps header, indented `↳` rows, small status dots.
 */
@Composable
internal fun LosslessRoutingStatus(
    rows: List<RoutingRow>,
    probe: Map<String, ProbeState>,
    checking: Boolean,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return // pre-first-emission; the header alone would read as "no sources"
    val mono = FontFamily.Monospace
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_lossless_routing_header),
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
                probe = probe[row.id],
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            // Never enumerate sources here: the rows above are the authority, and a
            // copy that names paths ends up crediting a user with ones they don't have.
            text = stringResource(R.string.settings_lossless_routing_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onCheck, enabled = !checking) {
            Text(
                stringResource(
                    if (checking) R.string.settings_sources_checking else R.string.settings_sources_check_again,
                ),
            )
        }
    }
}

@StringRes
private fun ProbeState.labelRes(): Int = when (this) {
    ProbeState.Ready -> R.string.source_state_ready
    ProbeState.NotAnswering -> R.string.source_state_not_answering
    ProbeState.Checking -> R.string.settings_sources_checking
    ProbeState.Paused -> R.string.source_state_paused
    is ProbeState.LimitReached -> R.string.source_state_limit_reached
    ProbeState.Off -> R.string.source_state_off
}

/**
 * Single row inside [LosslessRoutingStatus]: indent arrow, host name,
 * status dot + label. [probe] null = no check has run: the dot and label show configuration.
 */
@Composable
private fun RoutingRowLine(
    host: String,
    configured: Boolean,
    statusLabel: String,
    probe: ProbeState?,
) {
    val mono = FontFamily.Monospace
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    val dotColor = when (probe) {
        ProbeState.Ready -> scheme.primary
        ProbeState.NotAnswering -> scheme.error
        ProbeState.Checking, ProbeState.Paused, is ProbeState.LimitReached -> scheme.tertiary
        ProbeState.Off -> Color.Transparent
        null -> if (configured) scheme.primary else Color.Transparent
    }
    val hollow = dotColor == Color.Transparent

    // "Resets at 6:00 PM", or "Resets tomorrow at 10:00 AM" when the reset lands on the next local day.
    val resets = (probe as? ProbeState.LimitReached)?.let {
        val time = DateFormat.getTimeFormat(context).format(Date(it.resetsAtMs))
        stringResource(
            if (DateUtils.isToday(it.resetsAtMs)) R.string.source_limit_resets_today
            else R.string.source_limit_resets_tomorrow,
            time,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↳",
            fontFamily = mono,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = host,
                fontFamily = mono,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
            )
            resets?.let {
                Text(
                    text = it,
                    fontFamily = mono,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
                .border(
                    width = if (hollow) 1.dp else 0.dp,
                    color = scheme.onSurfaceVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = probe?.let { stringResource(it.labelRes()) } ?: statusLabel,
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = if (probe == ProbeState.NotAnswering) scheme.error else scheme.onSurfaceVariant,
        )
    }
}