package com.stash.feature.home.banner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * v0.9.35: "re-tagging library" Home banner. Surfaces the long-running
 * [com.stash.data.download.backfill.MetadataBackfillWorker] sweep on
 * upgrade so users know why disk IO / yt-dlp activity is happening.
 *
 * State machine (see [metadataBackfillBannerStateFor] for inputs → state):
 *  - [MetadataBackfillBannerState.Running] — worker is iterating;
 *    shows `processed/total` headline + thin progress bar.
 *  - [MetadataBackfillBannerState.Finished] — worker drained the queue;
 *    shows a completion summary for a 2-second pulse, then the
 *    [onFinishedAcknowledged] callback flips state back to IDLE and
 *    the banner vanishes.
 *  - [MetadataBackfillBannerState.Hidden] — early return; steady state.
 *
 * Visual treatment mirrors [WaitingForLosslessBanner] so the two banners
 * read as siblings on the Home screen — tertiary-tinted Surface, same
 * 12dp rounded corners and 1dp border at 35% accent alpha.
 */
@Composable
fun MetadataBackfillBanner(
    state: MetadataBackfillBannerState,
    onFinishedAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is MetadataBackfillBannerState.Hidden) return

    val accent = MaterialTheme.colorScheme.tertiary

    if (state is MetadataBackfillBannerState.Finished) {
        // 2-second "Done" pulse: ack-callback flips MetadataBackfillState
        // back to IDLE, which makes the snapshot Flow emit IDLE, which
        // maps to Hidden, which causes this composable to early-return.
        LaunchedEffect(state) {
            delay(2_000)
            onFinishedAcknowledged()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (state) {
                MetadataBackfillBannerState.Hidden -> Unit
                is MetadataBackfillBannerState.Running -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(com.stash.core.ui.R.string.status_retagging_library),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${state.processed}/${state.total}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    Text(
                        text = stringResource(com.stash.core.ui.R.string.desc_retagging_progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = {
                            // total is guaranteed > 0 by metadataBackfillBannerStateFor
                            state.processed.toFloat() / state.total.toFloat()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.20f),
                    )
                }
                is MetadataBackfillBannerState.Finished -> {
                    Text(
                        text = stringResource(com.stash.core.ui.R.string.status_library_retagged),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.safSkipped > 0) {
                        Text(
                            text = stringResource(com.stash.core.ui.R.string.desc_retagging_saf_skipped, state.safSkipped),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
