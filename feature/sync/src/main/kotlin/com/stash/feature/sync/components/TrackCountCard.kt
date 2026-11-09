package com.stash.feature.sync.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.R
import com.stash.feature.sync.SourceStatCell
import com.stash.feature.sync.SyncStatusInfo
import java.text.NumberFormat

/**
 * The track count + FLAC card, under the account cards on the Sync tab (back 2026-11-04: the owner
 * missed it after the Sync tab overhaul, #737, dropped the old SyncStatusCard).
 *
 * ```
 * 1,204                        18.4 GB
 * Songs in your library        On this phone
 * 214 FLAC                     6.1 GB FLAC
 * ● 812 Spotify   ● 392 YouTube
 * ```
 *
 * The song numbers follow the mode, as Library's Songs tab does ([SyncStatusInfo.countsInLibrary]):
 * Online counts the songs in your library, downloaded or not; Offline counts downloaded songs. The
 * FLAC and GB numbers are always files on this phone (the disk walk). One dot per connected account,
 * a connected account with no songs shows 0. A last "Other" dot holds the songs that belong to no one
 * account (a Spotify export's imports, your own files, cloud songs), only when there are any, so the
 * dots don't read short of the total ("20,300 songs, 300 Spotify"). No status dot or "last sync" line:
 * the hero already says "synced 2h ago", and History has each run.
 *
 * The account cards' glass (18 dp corners, no hairline), the Space Grotesk numerals of
 * [SourceStatCell], the brand dots of [AccountCard]. Read-only; TalkBack reads it as one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrackCountCard(
    status: SyncStatusInfo,
    spotifyConnected: Boolean,
    youTubeConnected: Boolean,
    soundCloudConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val other = stringResource(R.string.sync_count_other)
    val otherDot = MaterialTheme.colorScheme.onSurfaceVariant
    val sources = buildList {
        if (spotifyConnected) add(Triple(StashTheme.extendedColors.spotifyGreen, stringResource(R.string.sync_source_spotify), status.spotifyTracks))
        if (youTubeConnected) add(Triple(StashTheme.extendedColors.youtubeRed, stringResource(R.string.sync_source_youtube), status.youTubeTracks))
        if (soundCloudConnected) add(Triple(SoundCloudOrange, stringResource(R.string.sync_source_soundcloud), status.soundCloudTracks))
        if (status.otherTracks > 0) add(Triple(otherDot, other, status.otherTracks))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(StashTheme.extendedColors.glassBackground)
            .semantics(mergeDescendants = true) {}
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            CountStat(
                number = NumberFormat.getIntegerInstance().format(status.totalTracks),
                label = stringResource(
                    if (status.countsInLibrary) R.string.sync_count_in_library else R.string.sync_count_downloaded,
                ),
                // Per-stat gating, as the old card learned: a library can have FLAC files whose
                // Room size is 0, so each FLAC line shows on its own number.
                flac = status.flacTracks.takeIf { it > 0 }?.let {
                    stringResource(R.string.sync_count_flac, NumberFormat.getIntegerInstance().format(it))
                },
                alignEnd = false,
                modifier = Modifier.weight(1f),
            )
            // Left out while it's 0: the disk walk hasn't finished yet, or nothing is downloaded. "0 B"
            // says nothing either way, and before the walk ends it isn't true.
            if (status.storageUsedBytes > 0) {
                CountStat(
                    number = formatBytes(status.storageUsedBytes),
                    label = stringResource(R.string.sync_count_on_phone),
                    flac = status.flacStorageBytes.takeIf { it > 0 }?.let {
                        stringResource(R.string.sync_count_flac, formatBytes(it))
                    },
                    alignEnd = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (sources.isNotEmpty()) {
            // Wraps to a second line at a large font rather than squeezing the numbers.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEach { (color, name, songs) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Box(Modifier.padding(start = 6.dp)) {
                            SourceStatCell(number = NumberFormat.getIntegerInstance().format(songs), label = name)
                        }
                    }
                }
            }
        }
    }
}

/** A big number, its label under it, and an optional FLAC line in the accent. */
@Composable
private fun CountStat(
    number: String,
    label: String,
    flac: String?,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = number,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = align,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
        )
        if (flac != null) {
            Text(
                text = flac,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = align,
            )
        }
    }
}

/**
 * A byte count as "18.4 GB" (1024-based, one decimal), as the old card and Settings' storage row
 * show it.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeIndex = digitGroups.coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, safeIndex.toDouble()), units[safeIndex])
}

/** SoundCloud's orange, as its account card on this tab draws it. */
private val SoundCloudOrange = Color(0xFFFF5500)
