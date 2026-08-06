package com.stash.feature.settings.libraryhealth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.db.dao.LibraryHealthBucket
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.theme.StashTheme

/**
 * Library Health screen — answers "what's actually in my library?" by
 * histograming downloaded tracks across (codec, bitrate-band) buckets read
 * straight from the DB.
 *
 * Two reasons this exists:
 *  1. Verifying yield of the MAX (format-141) experiment — switch tier,
 *     re-download a sample, watch the AAC ~256 / Opus ~160 / AAC ~128 split
 *     change in real time.
 *  2. Backfilling pre-v0.8.1 rows whose `file_format` / `quality_kbps`
 *     columns sat at defaults because the sync writer never populated them.
 */
@Composable
fun LibraryHealthScreen(
    onNavigateBack: () -> Unit,
    viewModel: LibraryHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Header(onBack = onNavigateBack)

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Format and bitrate breakdown of every downloaded track. Use this to " +
                "verify what your sync is actually pulling down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        SectionHeader(title = "Library breakdown")

        if (state.buckets.isEmpty()) {
            EmptyHint()
        } else {
            HistogramCard(buckets = state.buckets)
        }

        Spacer(Modifier.height(20.dp))

        BackfillSection(
            status = state.backfill,
            buckets = state.buckets,
            onRunBackfill = viewModel::runBackfill,
        )

        Spacer(Modifier.height(20.dp))

        QualityInfoRefreshSection(onClick = viewModel::runQualityInfoBackfill)

        BackfillSection(
            status = state.backfill,
            buckets = state.buckets,
            onRunBackfill = viewModel::runBackfill,
        )

        Spacer(Modifier.height(20.dp))

        VerifyLibrarySection(
            status = state.verification,
            onRunVerification = viewModel::runVerification,
        )

        Spacer(Modifier.height(20.dp))

        QualityInfoRefreshSection(onClick = viewModel::runQualityInfoBackfill)

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Library Health",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EmptyHint() {
    GlassCard {
        Text(
            text = "No downloaded tracks yet. Run a sync first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HistogramCard(buckets: List<LibraryHealthBucket>) {
    val total = buckets.sumOf { it.trackCount }.coerceAtLeast(1)

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Total summary row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Total downloaded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$total",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            buckets.forEach { bucket ->
                BucketRow(bucket = bucket, totalForPercent = total)
            }
        }
    }
}

@Composable
private fun BucketRow(bucket: LibraryHealthBucket, totalForPercent: Int) {
    val pct = bucket.trackCount.toFloat() / totalForPercent.toFloat()
    val pctLabel = "%.1f%%".format(pct * 100)
    val avgKbpsLabel = if (bucket.avgKbps > 0.0) " · avg ${bucket.avgKbps.toInt()} kbps" else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${humanizeFormat(bucket.format)} ${bucket.kbpsBucket}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${bucket.trackCount} · $pctLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        // Percentage bar — solid fill, no shimmer.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(StashTheme.extendedColors.elevatedSurface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = pct)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        if (avgKbpsLabel.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = avgKbpsLabel.trimStart(' ', '·', ' '),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackfillSection(
    status: BackfillStatus,
    buckets: List<LibraryHealthBucket>,
    onRunBackfill: () -> Unit,
) {
    // Only worth showing the backfill button if there's at least one
    // legacy "unknown" row left to clean up.
    val unknownCount = buckets
        .filter { it.format == "opus" && it.kbpsBucket == "unknown" }
        .sumOf { it.trackCount }

    if (unknownCount == 0 && status is BackfillStatus.Idle) return

    SectionHeader(title = "Backfill metadata")

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (status) {
                BackfillStatus.Idle -> {
                    Text(
                        text = "$unknownCount tracks were downloaded before v0.8.1 and " +
                            "still show as unknown format. Run a one-time scan to read " +
                            "their actual codec and bitrate from disk.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRunBackfill) {
                        Text("Scan files")
                    }
                }
                is BackfillStatus.Running -> {
                    val total = status.total.coerceAtLeast(1)
                    Text(
                        text = "Scanning… ${status.processed} / ${status.total}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { status.processed.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is BackfillStatus.Done -> {
                    Text(
                        text = "Scan complete: ${status.processed} of ${status.total} " +
                            "tracks updated.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (unknownCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "$unknownCount tracks still couldn't be read — likely " +
                                "missing on disk or unsupported by MediaMetadataRetriever.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRunBackfill) {
                            Text("Retry scan")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerifyLibrarySection(
    status: LibraryVerificationStatus,
    onRunVerification: () -> Unit,
) {
    SectionHeader(title = "Verify library")

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (status) {
                LibraryVerificationStatus.Idle -> {
                    Text(
                        text = "Reconcile the download queue against your library and " +
                            "rebuild the track and storage stats shown on the Sync tab. " +
                            "Doesn't touch playlists or trigger any network sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRunVerification) {
                        Text("Verify")
                    }
                }
                is LibraryVerificationStatus.Running -> {
                    val total = status.total.coerceAtLeast(1)
                    Text(
                        text = "Checking library… ${status.step} / ${status.total}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { status.step.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is LibraryVerificationStatus.Done -> {
                    Text(
                        text = "Verified — ${status.result.unqueuedRequeued} track(s) requeued, " +
                            "${status.result.orphansSwept} stale entr${if (status.result.orphansSwept == 1) "y" else "ies"} cleaned up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRunVerification) {
                        Text("Verify again")
                    }
                }
                is LibraryVerificationStatus.Failed -> {
                    Text(
                        text = "Verification couldn't finish: ${status.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRunVerification) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityInfoRefreshSection(onClick: () -> Unit) {
    val context = LocalContext.current
    SectionHeader(title = "Quality info")
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Refresh quality info",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Re-extract bit-depth + sample-rate for FLAC tracks where it's missing. " +
                    "Runs in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onClick()
                    android.widget.Toast.makeText(
                        context,
                        "Refreshing quality info — running in background.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Text("Refresh")
            }
        }
    }
}

private fun humanizeFormat(format: String): String = when (format.lowercase()) {
    "aac" -> "AAC"
    "opus" -> "Opus"
    "mp3" -> "MP3"
    "flac" -> "FLAC"
    "vorbis" -> "Vorbis"
    "unknown" -> "Unknown"
    else -> format
}
