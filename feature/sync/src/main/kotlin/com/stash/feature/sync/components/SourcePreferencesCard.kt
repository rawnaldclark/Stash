package com.stash.feature.sync.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Source dashboard card. Used by both Spotify and YouTube.
 *
 * A compact, non-expanding stats surface: brand bar + name + Connected pill,
 * a [stats] numbers row, the sync-[modeChips], and a "Manage ›" row that
 * navigates to the full playlist screen. The per-playlist toggle list no
 * longer lives here — it moved to a dedicated Manage screen.
 *
 * @param name       "Spotify" / "YouTube Music"
 * @param brandColor Brand bar color (spotifyGreen or youtubeRed)
 * @param connected  Whether the source is connected (green Connected vs red Disconnected chip)
 * @param stats      Numbers row (e.g. "42 MIXES · AUTO   5/30 PLAYLISTS   1.2k LIKED").
 * @param modeChips  Refresh/Accumulate chip row (renders nothing before the first sync).
 * @param onManage   Navigate to the Manage playlists screen for this source.
 */
@Composable
fun SourcePreferencesCard(
    name: String,
    brandColor: Color,
    connected: Boolean,
    stats: @Composable () -> Unit,
    modeChips: @Composable () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ec = StashTheme.extendedColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ec.glassBackground,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, ec.glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
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

                Spacer(Modifier.height(12.dp))
                stats()
                Spacer(Modifier.height(14.dp))
                modeChips()

                // Manage › — the only route into the per-playlist list.
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ec.glassBorder),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onManage)
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Manage playlists",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Manage $name playlists",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
