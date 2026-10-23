package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.ui.unit.dp
import com.stash.core.data.prefs.LyricsSourcePreference
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.constants.StashConstants
import com.stash.feature.settings.R
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsSegmented
import com.stash.feature.settings.components.SettingsToggleRow
import kotlin.math.roundToInt

/**
 * The Playback spoke of the hub-and-spoke Settings redesign.
 *
 * This re-homes the original `StashConstants.STREAMING_ENGINE_ENABLED` block from
 * the monolithic `SettingsScreen.kt`: the Online/Offline mode picker plus the
 * streaming toggles (cellular, YouTube fallback, antra-only). This is a
 * behavior-preserving relocation + restyle — every control calls the SAME
 * [SettingsViewModel] method the old screen used; no logic is changed.
 */
@Composable
fun SettingsPlaybackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    lyricsViewModel: LyricsSettingsViewModel = hiltViewModel(),
) {
    val lyricsSourcePreference by lyricsViewModel.sourcePreference.collectAsStateWithLifecycle()
    val lyricsRecheckState by lyricsViewModel.recheckState.collectAsStateWithLifecycle()
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    val streamOnCellular by viewModel.streamOnCellular.collectAsStateWithLifecycle()
    val forceYouTubeFallback by viewModel.forceYouTubeFallback.collectAsStateWithLifecycle()
    val forceQbdlxOnly by viewModel.forceQbdlxOnly.collectAsStateWithLifecycle()
    val skipQobuz by viewModel.skipQobuz.collectAsStateWithLifecycle()
    // Gates developer instruments out of release builds. Read from the installed
    // app's own flags rather than a module BuildConfig: it is the actual property we
    // care about ("is this a debuggable install"), and it needs no build-file change.
    val context = LocalContext.current
    val isDebuggableBuild = remember(context) {
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    val autoplayRadioEnabled by viewModel.autoplayRadioEnabled.collectAsStateWithLifecycle()
    val crossfadeEnabled by viewModel.crossfadeEnabled.collectAsStateWithLifecycle()
    val crossfadeDurationMs by viewModel.crossfadeDurationMs.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.settings_playback_title), onBack = onBack, modifier = modifier) {
        if (StashConstants.STREAMING_ENGINE_ENABLED) {
            SettingsSectionLabel(stringResource(R.string.settings_streaming_section))
            SettingsGroupCard(
                rows = buildList {
                    add {
                        Box(modifier = Modifier.padding(10.dp)) {
                            SettingsSegmented(
                                options = listOf(
                                    stringResource(R.string.settings_mode_stream_only),
                                    stringResource(R.string.settings_mode_download),
                                ),
                                selectedIndex = if (streamingEnabled) 0 else 1,
                                onSelect = { viewModel.onStreamingToggle(it == 0) },
                            )
                        }
                    }
                    add {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_stream_on_cellular_title),
                            subtitle = stringResource(R.string.settings_stream_on_cellular_desc),
                            checked = streamOnCellular,
                            onCheckedChange = viewModel::onStreamOnCellularToggle,
                        )
                    }
                    add {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_stream_via_youtube_title),
                            subtitle = stringResource(R.string.settings_stream_via_youtube_desc),
                            checked = forceYouTubeFallback,
                            onCheckedChange = viewModel::setForceYouTubeFallback,
                        )
                    }
                    // Force-Qobuz is a DEVELOPER instrument and is debug-only below.
                    // Shipping one of these cost a real outage: a force toggle left on
                    // in a release install silently disabled lossless, and the hunt for
                    // it went as far as dex-dumping the APK. Users never see a switch
                    // whose only effect is to break their audio.
                    if (isDebuggableBuild) {
                        add {
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_force_qobuz_debug_title),
                                subtitle = stringResource(R.string.settings_force_qobuz_debug_desc),
                                checked = forceQbdlxOnly,
                                onCheckedChange = viewModel::setForceQbdlxOnly,
                            )
                        }
                        // Same debug-only gate: Qobuz answers almost every track, so this
                        // is the only way to watch Tidal serve a stream or a download.
                        add {
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_skip_qobuz_debug_title),
                                subtitle = stringResource(R.string.settings_skip_qobuz_debug_desc),
                                checked = skipQobuz,
                                onCheckedChange = viewModel::setSkipQobuz,
                            )
                        }
                    }
                },
            )
        } else {
            Text(
                text = stringResource(R.string.settings_streaming_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Autoplay and crossfade apply to streamed and downloaded tracks alike, so
        // they sit outside the streaming-engine gate.
        SettingsSectionLabel(stringResource(R.string.settings_playing_section))
        SettingsGroupCard(
            rows = buildList {
                // The queue's end seeds a song radio (streams, so it needs a connection).
                add {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_autoplay_radio_title),
                        subtitle = stringResource(R.string.settings_autoplay_radio_desc),
                        checked = autoplayRadioEnabled,
                        onCheckedChange = viewModel::onAutoplayRadioToggle,
                    )
                }
                add {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_crossfade_title),
                        subtitle = stringResource(R.string.settings_crossfade_desc),
                        checked = crossfadeEnabled,
                        onCheckedChange = viewModel::onCrossfadeToggle,
                    )
                }
                if (crossfadeEnabled) {
                    add {
                        CrossfadeDurationRow(
                            seconds = (crossfadeDurationMs / 1000L).toInt().coerceIn(1, 12),
                            onSecondsChange = { viewModel.onCrossfadeDurationChange(it * 1000L) },
                        )
                    }
                }
            },
        )

        SettingsSectionLabel(stringResource(R.string.settings_lyrics_section))
        val lrcOnly = lyricsSourcePreference == LyricsSourcePreference.LRC_ONLY
        SettingsGroupCard(
            rows = buildList {
                add {
                    Column(modifier = Modifier.padding(10.dp)) {
                        SettingsSegmented(
                            options = listOf(
                                stringResource(R.string.settings_lyrics_apple_music),
                                stringResource(R.string.settings_lyrics_lrc_only),
                            ),
                            selectedIndex = if (lrcOnly) 1 else 0,
                            onSelect = {
                                lyricsViewModel.setSourcePreference(
                                    if (it == 1) LyricsSourcePreference.LRC_ONLY else LyricsSourcePreference.APPLE_MUSIC,
                                )
                            },
                        )
                        Text(
                            text = stringResource(
                                if (lrcOnly) R.string.settings_lyrics_lrc_only_desc else R.string.settings_lyrics_apple_music_desc,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 5.dp, end = 5.dp, top = 8.dp),
                        )
                    }
                }
                // The one way to walk the whole library for word-synced lyrics: user-started,
                // never automatic. Hidden under LRC-only, where there is nothing word-synced to find.
                if (!lrcOnly) {
                    add {
                        LyricsRecheckRow(
                            state = lyricsRecheckState,
                            onStart = lyricsViewModel::startRecheck,
                            onCancel = lyricsViewModel::cancelRecheck,
                        )
                    }
                }
            },
        )
    }
}

/**
 * Duration slider row for the Crossfade section: 1–12 s in whole-second steps
 * (Material [Slider] `steps` counts internal stops, so 10 → 12 positions).
 * Stateless; the caller owns persistence.
 */
@Composable
private fun CrossfadeDurationRow(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_crossfade_duration_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = pluralStringResource(R.plurals.settings_crossfade_duration_seconds, seconds, seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = seconds.toFloat(),
            onValueChange = { onSecondsChange(it.roundToInt()) },
            valueRange = 1f..12f,
            steps = 10,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}
