package com.stash.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.DownloadMode
import com.stash.core.model.QualityTier
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.feature.settings.components.BetaPill
import com.stash.feature.settings.components.SettingsChoice
import com.stash.feature.settings.components.SettingsChoiceSheet
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsToggleRow
import com.stash.feature.settings.components.SettingsValueRow

/** The sheet a quality row on this screen has open. */
private enum class QualitySheet { WIFI, CELLULAR, DOWNLOAD_MODE, FLAC_TIER, YOUTUBE }

/**
 * Settings > Audio & Quality. Short by design: every choice is one row showing
 * its current value (the options, with their sizes, are a tap away in a sheet),
 * and where lossless comes from lives on its own page.
 *
 *  - Streaming: the Lossless switch, Lossless sources, per-network FLAC tier,
 *    Save Data, Prefer Tidal AAC.
 *  - Downloads: the format, and only the switches that format uses.
 *  - Output: Hi-Res output and the Equalizer.
 *
 * Every control calls the same [SettingsViewModel] method as before.
 */
@Composable
fun SettingsAudioQualityScreen(
    onBack: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToLosslessSources: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val qbdlxExpired by viewModel.qbdlxExpired.collectAsStateWithLifecycle()
    val tidalAacFallback by viewModel.tidalAacFallback.collectAsStateWithLifecycle()
    val preferTidalAacStreaming by viewModel.preferTidalAacStreaming.collectAsStateWithLifecycle()
    val flacViewModel: FlacUpgradeViewModel = hiltViewModel()
    val autoFlacUpgrade by flacViewModel.autoEnabled.collectAsStateWithLifecycle()
    val quotaViewModel: LosslessQuotaViewModel = hiltViewModel()
    val losslessToday by quotaViewModel.line.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<QualitySheet?>(null) }

    val lossless = uiState.losslessEnabled
    val mode = uiState.downloadMode
    val saveData = uiState.streamingSaveData

    SettingsScaffold(title = stringResource(R.string.settings_audio_quality_title), onBack = onBack, modifier = modifier) {
        // -- Streaming ---------------------------------------------------------
        SettingsSectionLabel(stringResource(R.string.settings_streaming_section))
        val streaming = mutableListOf<@Composable () -> Unit>()
        streaming += {
            SettingsToggleRow(
                title = stringResource(R.string.settings_lossless_title),
                // Three-way, because keying "FLAC" on the toggle alone asserted it
                // for every user with nothing configured (the day-one state).
                subtitle = when {
                    !lossless -> stringResource(R.string.settings_lossless_subtitle_off)
                    qbdlxExpired -> stringResource(R.string.settings_lossless_subtitle_no_source)
                    else -> stringResource(R.string.settings_lossless_subtitle_active)
                },
                checked = lossless,
                onCheckedChange = viewModel::onLosslessEnabledChanged,
            )
        }
        // AAC downloads use the daily allowance too, so the page shows for them as well.
        if (lossless || mode != DownloadMode.OPUS) {
            streaming += {
                SettingsNavRow(
                    title = stringResource(R.string.settings_lossless_sources_title),
                    subtitle = if (qbdlxExpired) {
                        stringResource(R.string.settings_lossless_sources_needs_setup)
                    } else {
                        losslessToday
                    },
                    onClick = onNavigateToLosslessSources,
                )
            }
        }
        if (lossless) {
            // Save Data is the master override: it forces CD on every network,
            // so the per-network rows read CD and step aside while it is on.
            streaming += {
                SettingsValueRow(
                    title = stringResource(R.string.settings_on_wifi),
                    value = (if (saveData) LosslessQualityTier.CD else uiState.streamingWifiTier).shortLabel(),
                    enabled = !saveData,
                    onClick = { sheet = QualitySheet.WIFI },
                )
            }
            streaming += {
                SettingsValueRow(
                    title = stringResource(R.string.settings_on_cellular),
                    value = (if (saveData) LosslessQualityTier.CD else uiState.streamingCellularTier).shortLabel(),
                    enabled = !saveData,
                    onClick = { sheet = QualitySheet.CELLULAR },
                )
            }
        }
        streaming += {
            SettingsToggleRow(
                title = stringResource(R.string.settings_save_data_title),
                // Lossless stays lossless: Save Data is the LOWEST lossless tier,
                // not a drop to lossy. With lossless off it trims JioSaavn and YouTube.
                subtitle = stringResource(
                    if (lossless) R.string.settings_save_data_lossless_subtitle else R.string.settings_save_data_subtitle,
                ),
                checked = saveData,
                onCheckedChange = viewModel::onStreamingSaveDataChanged,
                titleTrailing = { BetaPill() },
            )
        }
        if (lossless) {
            // Only with Lossless on: the Tidal rung is a lossless-relay rung.
            streaming += {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_prefer_tidal_aac_streaming_title),
                    subtitle = stringResource(R.string.settings_prefer_tidal_aac_streaming_subtitle),
                    checked = preferTidalAacStreaming,
                    onCheckedChange = viewModel::onPreferTidalAacStreamingChanged,
                )
            }
            // One switch for streaming and downloads. With Lossless downloads it
            // sits with them below; otherwise it is here, for streaming.
            if (mode != DownloadMode.LOSSLESS) {
                streaming += { TidalAacFallbackRow(tidalAacFallback, viewModel::onTidalAacFallbackChanged) }
            }
        }
        SettingsGroupCard(rows = streaming)

        // -- Downloads ---------------------------------------------------------
        SettingsSectionLabel(stringResource(R.string.settings_downloads_section))
        val downloads = mutableListOf<@Composable () -> Unit>()
        downloads += {
            SettingsValueRow(
                title = stringResource(R.string.settings_download_quality_title),
                value = stringResource(mode.titleRes()),
                onClick = { sheet = QualitySheet.DOWNLOAD_MODE },
            )
        }
        if (mode == DownloadMode.LOSSLESS) {
            downloads += {
                SettingsValueRow(
                    title = stringResource(R.string.settings_flac_quality_title),
                    value = uiState.losslessQualityTier.shortLabel(),
                    onClick = { sheet = QualitySheet.FLAC_TIER },
                )
            }
            downloads += {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_auto_flac_upgrade_title),
                    subtitle = stringResource(R.string.settings_auto_flac_upgrade_subtitle),
                    checked = autoFlacUpgrade,
                    onCheckedChange = flacViewModel::setAutoEnabled,
                )
            }
            downloads += { TidalAacFallbackRow(tidalAacFallback, viewModel::onTidalAacFallbackChanged) }
        }
        if (mode == DownloadMode.LOSSLESS || mode == DownloadMode.AAC) {
            downloads += {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_lossy_fallback_title),
                    subtitle = stringResource(
                        if (mode == DownloadMode.AAC) {
                            R.string.settings_lossy_fallback_switch_label_aac
                        } else {
                            R.string.settings_lossy_fallback_switch_label
                        },
                    ),
                    checked = uiState.youtubeFallbackEnabled,
                    onCheckedChange = viewModel::onYoutubeFallbackChanged,
                )
            }
        }
        // The YouTube quality: everything in Opus mode, the last rung of the lossy fallback otherwise.
        if (mode == DownloadMode.OPUS || uiState.youtubeFallbackEnabled) {
            downloads += {
                SettingsValueRow(
                    title = stringResource(R.string.settings_youtube_quality_title),
                    value = uiState.audioQuality.displayLabel(),
                    onClick = { sheet = QualitySheet.YOUTUBE },
                )
            }
        }
        SettingsGroupCard(rows = downloads)

        // -- Output ------------------------------------------------------------
        SettingsSectionLabel(stringResource(R.string.settings_output_section))
        SettingsGroupCard(
            rows = listOf(
                { HiResOutputRow() },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_equalizer_title),
                        onClick = onNavigateToEqualizer,
                    )
                },
            ),
        )
    }

    val tiers = listOf(LosslessQualityTier.MAX, LosslessQualityTier.HI_RES, LosslessQualityTier.CD)
        .map { SettingsChoice(it, it.displayLabel(), it.sizeHint()) }
    val dismiss = { sheet = null }
    when (sheet) {
        QualitySheet.WIFI -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_streaming_on_wifi_title),
            choices = tiers,
            selected = uiState.streamingWifiTier,
            onSelect = viewModel::onStreamingWifiTierChanged,
            onDismiss = dismiss,
        )
        QualitySheet.CELLULAR -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_streaming_on_cellular_title),
            choices = tiers,
            selected = uiState.streamingCellularTier,
            onSelect = viewModel::onStreamingCellularTierChanged,
            onDismiss = dismiss,
        )
        QualitySheet.DOWNLOAD_MODE -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_download_quality_title),
            choices = DownloadMode.entries.map {
                SettingsChoice(it, stringResource(it.titleRes()), stringResource(it.descriptionRes()))
            },
            selected = mode,
            onSelect = viewModel::onDownloadModeChanged,
            onDismiss = dismiss,
        )
        QualitySheet.FLAC_TIER -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_flac_quality_title),
            choices = tiers,
            selected = uiState.losslessQualityTier,
            onSelect = viewModel::onLosslessQualityTierChanged,
            onDismiss = dismiss,
        )
        QualitySheet.YOUTUBE -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_youtube_quality_title),
            choices = QualityTier.entries.map {
                SettingsChoice(
                    it,
                    it.displayLabel(),
                    stringResource(R.string.settings_audio_quality_bitrate_size, it.bitrateKbps, it.sizeMbPerMinute),
                )
            },
            selected = uiState.audioQuality,
            onSelect = viewModel::onQualityChanged,
            onDismiss = dismiss,
            footer = stringResource(R.string.settings_download_quality_rates_desc),
        )
        null -> Unit
    }
}

@Composable
private fun TidalAacFallbackRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsToggleRow(
        title = stringResource(R.string.settings_tidal_aac_fallback_title),
        subtitle = stringResource(R.string.settings_tidal_aac_fallback_subtitle),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun LosslessQualityTier.displayLabel(): String = when (this) {
    LosslessQualityTier.MAX -> stringResource(R.string.settings_lossless_tier_max)
    LosslessQualityTier.HI_RES -> stringResource(R.string.settings_lossless_tier_hires)
    LosslessQualityTier.CD -> stringResource(R.string.settings_lossless_tier_cd)
}

/** The tier's name alone, for a row's value ("Hi-Res"); the sheet shows the full label. */
@Composable
internal fun LosslessQualityTier.shortLabel(): String = when (this) {
    LosslessQualityTier.MAX -> stringResource(R.string.settings_lossless_tier_max_short)
    LosslessQualityTier.HI_RES -> stringResource(R.string.settings_lossless_tier_hires_short)
    LosslessQualityTier.CD -> stringResource(R.string.settings_lossless_tier_cd_short)
}

@Composable
internal fun LosslessQualityTier.sizeHint(): String = when (this) {
    LosslessQualityTier.MAX -> stringResource(R.string.settings_lossless_tier_max_hint)
    LosslessQualityTier.HI_RES -> stringResource(R.string.settings_lossless_tier_hires_hint)
    LosslessQualityTier.CD -> stringResource(R.string.settings_lossless_tier_cd_hint)
}

@Composable
private fun QualityTier.displayLabel(): String = when (this) {
    QualityTier.MAX -> stringResource(R.string.settings_quality_tier_max)
    QualityTier.BEST -> stringResource(R.string.settings_quality_tier_best)
    QualityTier.HIGH -> stringResource(R.string.settings_quality_tier_high)
    QualityTier.NORMAL -> stringResource(R.string.settings_quality_tier_normal)
    QualityTier.LOW -> stringResource(R.string.settings_quality_tier_low)
}

private fun DownloadMode.titleRes(): Int = when (this) {
    DownloadMode.LOSSLESS -> R.string.settings_download_mode_lossless
    DownloadMode.AAC -> R.string.settings_download_mode_aac
    DownloadMode.OPUS -> R.string.settings_download_mode_opus
}

private fun DownloadMode.descriptionRes(): Int = when (this) {
    DownloadMode.LOSSLESS -> R.string.settings_download_mode_lossless_desc
    DownloadMode.AAC -> R.string.settings_download_mode_aac_desc
    DownloadMode.OPUS -> R.string.settings_download_mode_opus_desc
}
