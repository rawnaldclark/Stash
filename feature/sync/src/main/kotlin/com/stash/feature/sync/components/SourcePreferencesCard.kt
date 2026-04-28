package com.stash.feature.sync.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Generic Source Preferences card. Used by both Spotify and YouTube.
 *
 * Collapsed: brand bar + name + connection chip + status-pill row + summary line.
 * Expanded: brand bar + name + connection chip + [expandedContent] slot.
 *
 * @param name             "Spotify" / "YouTube Music"
 * @param brandColor       Brand bar color (spotifyGreen or youtubeRed)
 * @param connected        Whether the source is connected (green Connected vs red Disconnected chip)
 * @param statusPills      Composable slot for 4-5 [StatusPill]s. Caller decides content + tints.
 * @param summaryLine      e.g. "5 of 35 playlists · 1,247 tracks"
 * @param expandedContent  Body shown when the card is expanded.
 * @param initiallyExpanded Whether the card starts expanded (defaults to false).
 */
@Composable
fun SourcePreferencesCard(
    name: String,
    brandColor: Color,
    connected: Boolean,
    statusPills: @Composable () -> Unit,
    summaryLine: String,
    expandedContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val ec = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = ec.glassBackground,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, ec.glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .animateContentSize(),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(brandColor),
            )
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusPill(
                            text = if (connected) "Connected" else "Disconnected",
                            brandColor = if (connected) ec.success else Color(0xFFEF4444),
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (!expanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        statusPills()
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = summaryLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    expandedContent()
                }
            }
        }
    }
}
