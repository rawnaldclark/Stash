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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.db.dao.LibraryHealthBucket
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.lyrics.LyricsFetchStatus
import com.stash.feature.settings.FlacUpgradeUiState
import com.stash.feature.settings.FlacUpgradeViewModel
import com.stash.feature.settings.R

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
    lyricsViewModel: LyricsFetchViewModel = hiltViewModel(),
    flacViewModel: FlacUpgradeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lyricsStatus by lyricsViewModel.status.collectAsStateWithLifecycle()
    val flacState by flacViewModel.state.collectAsStateWithLifecycle()

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
            text = stringResource(R.string.settings_library_health_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        SectionHeader(title = stringResource(R.string.settings_library_health_section_breakdown))

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

        if (state.restorableDownloads > 0 || state.restoreQueued != null) {
            RestoreDownloadsSection(
                missing = state.restorableDownloads,
                queued = state.restoreQueued,
                onRestore = viewModel::restoreMissingDownloads,
                onDismiss = viewModel::dismissRestoreResult,
            )
            Spacer(Modifier.height(20.dp))
        }

        VerifyLibrarySection(
            status = state.verification,
            onRunVerification = viewModel::runVerification,
        )

        Spacer(Modifier.height(20.dp))

        QualityInfoRefreshSection(onClick = viewModel::runQualityInfoBackfill)

        Spacer(Modifier.height(20.dp))

        FlacUpgradeSection(state = flacState, onRun = flacViewModel::runNow)

        Spacer(Modifier.height(20.dp))

        LyricsFetchSection(
            status = lyricsStatus,
            onFetch = lyricsViewModel::fetchLyrics,
        )

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
                contentDescription = stringResource(R.string.settings_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.settings_library_health_title),
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
            text = stringResource(R.string.settings_library_health_empty_hint),
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
                    text = stringResource(R.string.settings_library_health_total_downloaded),
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
    val avgKbpsLabel = if (bucket.avgKbps > 0.0) stringResource(R.string.settings_library_health_avg_kbps, bucket.avgKbps.toInt()) else ""

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
            val kbpsBucketLabel = if (bucket.kbpsBucket.equals("unknown", ignoreCase = true)) {
                stringResource(R.string.settings_library_health_format_unknown)
            } else {
                bucket.kbpsBucket
            }
            Text(
                text = "${humanizeFormat(bucket.format)} $kbpsBucketLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.settings_library_health_count_and_pct, bucket.trackCount, pctLabel),
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
                text = avgKbpsLabel,
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

    SectionHeader(title = stringResource(R.string.settings_library_health_section_backfill))

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (status) {
                BackfillStatus.Idle -> {
                    Text(
                        text = pluralStringResource(
                            R.plurals.settings_library_health_backfill_idle_desc,
                            unknownCount,
                            unknownCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRunBackfill) {
                        Text(stringResource(R.string.settings_library_health_scan_files))
                    }
                }
                is BackfillStatus.Running -> {
                    val total = status.total.coerceAtLeast(1)
                    Text(
                        text = stringResource(
                            R.string.settings_library_health_scanning_progress,
                            status.processed,
                            status.total,
                        ),
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
                        text = pluralStringResource(
                            R.plurals.settings_library_health_scan_complete,
                            status.total,
                            status.processed,
                            status.total,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (unknownCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.settings_library_health_scan_unreadable,
                                unknownCount,
                                unknownCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRunBackfill) {
                            Text(stringResource(R.string.settings_library_health_retry_scan))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Offered only when the library remembers downloads this device no longer
 * has — restoring a backup onto a fresh install is the case that produces
 * them in bulk, since the archive carries the library and not the audio.
 */
@Composable
private fun RestoreDownloadsSection(
    missing: Int,
    queued: Int?,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionHeader(title = stringResource(R.string.settings_library_health_section_missing_downloads))

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (queued != null) {
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_library_health_restore_queued,
                        queued,
                        queued,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDismiss) { Text(stringResource(R.string.settings_library_health_done)) }
            } else {
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_library_health_missing_downloads_desc,
                        missing,
                        missing,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRestore) {
                    Text(
                        pluralStringResource(
                            R.plurals.settings_library_health_download_again_button,
                            missing,
                            missing,
                        ),
                    )
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
    SectionHeader(title = stringResource(R.string.settings_library_health_section_verify_library))

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (status) {
                LibraryVerificationStatus.Idle -> {
                    Text(
                        text = stringResource(R.string.settings_library_health_verify_idle_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRunVerification) {
                        Text(stringResource(R.string.settings_library_health_verify_button))
                    }
                }
                is LibraryVerificationStatus.Running -> {
                    val total = status.total.coerceAtLeast(1)
                    Text(
                        text = stringResource(
                            R.string.settings_library_health_checking_library_progress,
                            status.step,
                            status.total,
                        ),
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
                    val r = status.reconciliation
                    val a = status.adoption
                    val missingFiles = pluralStringResource(
                        R.plurals.settings_library_health_verified_missing_files,
                        r.filesMissing,
                        r.filesMissing,
                    )
                    val staleEntries = pluralStringResource(
                        R.plurals.settings_library_health_verified_stale_entries,
                        r.orphansSwept,
                        r.orphansSwept,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_library_health_verified_summary,
                            missingFiles,
                            staleEntries,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (a.adopted > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.settings_library_health_adopted_songs,
                                a.adopted,
                                a.adopted,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else if (a.scanned > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.settings_library_health_scanned_tracks_none_matched,
                                a.scanned,
                                a.scanned,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRunVerification) {
                        Text(stringResource(R.string.settings_library_health_verify_again_button))
                    }
                }
                is LibraryVerificationStatus.Failed -> {
                    Text(
                        text = stringResource(
                            R.string.settings_library_health_verification_failed,
                            status.message,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRunVerification) {
                        Text(stringResource(R.string.settings_library_health_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityInfoRefreshSection(onClick: () -> Unit) {
    val context = LocalContext.current
    SectionHeader(title = stringResource(R.string.settings_library_health_section_quality_info))
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_library_health_refresh_quality_info_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_library_health_refresh_quality_info_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onClick()
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.settings_library_health_refreshing_quality_info_toast),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Text(stringResource(R.string.settings_library_health_refresh_button))
            }
        }
    }
}

@Composable
private fun FlacUpgradeSection(
    state: FlacUpgradeUiState,
    onRun: () -> Unit,
) {
    SectionHeader(title = stringResource(R.string.settings_library_health_section_flac_upgrades))
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_library_health_flac_upgrades_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            state.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
            }
            state.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(4.dp))
            if (state.active) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Button(onClick = onRun) { Text(stringResource(R.string.settings_library_health_check_upgrades_button)) }
            }
        }
    }
}

@Composable
private fun LyricsFetchSection(
    status: LyricsFetchStatus,
    onFetch: () -> Unit,
) {
    SectionHeader(title = stringResource(R.string.settings_library_health_section_lyrics))
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (status) {
                LyricsFetchStatus.Idle, LyricsFetchStatus.Queued -> {
                    Text(
                        text = stringResource(R.string.settings_library_health_fetch_lyrics_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (status == LyricsFetchStatus.Queued) {
                            stringResource(R.string.settings_library_health_fetch_lyrics_queued_desc)
                        } else {
                            stringResource(R.string.settings_library_health_fetch_lyrics_idle_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onFetch) { Text(stringResource(R.string.settings_library_health_fetch_lyrics_button)) }
                }
                is LyricsFetchStatus.Running -> {
                    Text(
                        text = if (status.total > 0) {
                            stringResource(
                                R.string.settings_library_health_fetching_lyrics_progress,
                                status.done,
                                status.total,
                            )
                        } else {
                            stringResource(R.string.settings_library_health_fetching_lyrics)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (status.total > 0) {
                        LinearProgressIndicator(
                            progress = { status.done.toFloat() / status.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is LyricsFetchStatus.Done -> {
                    val updated = status.fetched + status.upgraded
                    Text(
                        text = if (updated > 0) {
                            pluralStringResource(
                                R.plurals.settings_library_health_lyrics_done_updated,
                                updated,
                                updated,
                            )
                        } else {
                            stringResource(R.string.settings_library_health_lyrics_done_nothing)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (status.notFound > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.settings_library_health_lyrics_not_found,
                                status.notFound,
                                status.notFound,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (status.bailed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_library_health_lyrics_bailed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onFetch) { Text(stringResource(R.string.settings_library_health_fetch_again_button)) }
                }
            }
        }
    }
}

@Composable
private fun humanizeFormat(format: String): String = when (format.lowercase()) {
    "aac" -> "AAC"
    "opus" -> "Opus"
    "mp3" -> "MP3"
    "flac" -> "FLAC"
    "vorbis" -> "Vorbis"
    "unknown" -> stringResource(R.string.settings_library_health_format_unknown)
    else -> format
}
