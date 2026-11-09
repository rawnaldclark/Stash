package com.stash.feature.sync.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.ApprovedDownloads
import com.stash.feature.sync.R

/**
 * "Downloading 12 approved songs", with a determinate bar and "3 of 12 done",
 * on Failed Matches while approved matches download in the background. The
 * approved rows leave the list at once, so this is what says they're on their
 * way (and that leaving the screen doesn't stop them). Renders nothing when
 * no run is going.
 *
 * TalkBack reads it as one item: the title, the detail line, then the bar's
 * own percentage.
 */
@Composable
internal fun ApprovedDownloadsBanner(
    downloads: ApprovedDownloads,
    modifier: Modifier = Modifier,
) {
    if (!downloads.active) return
    val title = pluralStringResource(R.plurals.sync_approved_downloads_title, downloads.total, downloads.total)
    val extendedColors = StashTheme.extendedColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { downloads.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val finished = downloads.done + downloads.failed
            val detail = if (downloads.failed > 0) {
                pluralStringResource(
                    R.plurals.sync_approved_downloads_detail_failed,
                    downloads.failed,
                    finished,
                    downloads.total,
                    downloads.failed,
                )
            } else {
                stringResource(R.string.sync_approved_downloads_detail_base, finished, downloads.total)
            }
            Text(
                text = stringResource(R.string.sync_approved_downloads_keep_downloading_note, detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
