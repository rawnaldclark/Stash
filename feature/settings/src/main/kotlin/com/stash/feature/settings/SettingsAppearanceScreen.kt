package com.stash.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.AmbientStyle
import com.stash.core.data.prefs.StartTab
import com.stash.core.model.ThemeMode
import com.stash.core.model.UiScale
import com.stash.core.ui.theme.LocalUiScaleMax
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsSegmented
import com.stash.feature.settings.components.SettingsSliderRow
import com.stash.feature.settings.components.SettingsToggleRow

/** Theme order in the segmented control. */
private val ThemeOptions = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)

/**
 * Settings > Appearance, one screen: theme (Dark / Light / System, with Pure
 * black under it while a dark theme can show), display size, the Now Playing
 * backdrop, the tab Stash opens on, and Home layout (its own page).
 */
@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
    onOpenHomeLayout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val startTab by viewModel.startTab.collectAsStateWithLifecycle()
    val uiScale by viewModel.uiScale.collectAsStateWithLifecycle()
    // This window's top of the range: a narrow phone can't take 1.6x (see UiScale.maxFor). A
    // choice above it, made on a wider window, shows (and applies) as the cap.
    val maxScale = LocalUiScaleMax.current
    // Dragging only moves the draft: committing live would lay the whole app out again under the
    // finger. The store gets the value once, on release (or Reset).
    var draftScale by remember(uiScale, maxScale) { mutableFloatStateOf(minOf(uiScale, maxScale)) }

    SettingsScaffold(title = stringResource(R.string.settings_appearance_title), onBack = onBack, modifier = modifier) {
        SettingsSectionLabel(stringResource(R.string.settings_theme_section))
        val theme = mutableListOf<@Composable () -> Unit>()
        theme += {
            SegmentedRow {
                SettingsSegmented(
                    options = listOf(
                        stringResource(R.string.settings_theme_dark),
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_system),
                    ),
                    selectedIndex = ThemeOptions.indexOf(uiState.themeMode).coerceAtLeast(0),
                    onSelect = { viewModel.onThemeChanged(ThemeOptions[it]) },
                )
            }
        }
        // AMOLED only ever applies to dark; forced Light has nothing for it to do.
        if (uiState.themeMode != ThemeMode.LIGHT) {
            theme += {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_amoled_title),
                    subtitle = stringResource(R.string.settings_amoled_subtitle),
                    checked = uiState.amoledDark,
                    onCheckedChange = viewModel::onAmoledDarkChanged,
                )
            }
        }
        SettingsGroupCard(rows = theme)

        SettingsSectionLabel(stringResource(R.string.settings_display_size_section))
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_ui_scale_title),
                        valueLabel = String.format(java.util.Locale.US, "%.2fx", draftScale),
                        subtitle = stringResource(R.string.settings_ui_scale_subtitle),
                        value = draftScale,
                        onValueChange = { draftScale = UiScale.snap(it).coerceAtMost(maxScale) },
                        onValueChangeFinished = { viewModel.onUiScaleChanged(draftScale) },
                        valueRange = UiScale.MIN..maxScale,
                        steps = UiScale.sliderSteps(maxScale), // a detent every 0.05, 1.00 among them
                        onReset = {
                            draftScale = UiScale.DEFAULT
                            viewModel.onUiScaleChanged(UiScale.DEFAULT)
                        },
                        resetEnabled = draftScale != UiScale.DEFAULT,
                    )
                },
            ),
        )

        SettingsSectionLabel(stringResource(R.string.settings_now_playing_section))
        val nowPlaying = mutableListOf<@Composable () -> Unit>()
        nowPlaying += {
            SettingsToggleRow(
                title = stringResource(R.string.settings_ambient_motion_title),
                subtitle = stringResource(R.string.settings_ambient_motion_subtitle),
                checked = uiState.ambientAnimationEnabled,
                onCheckedChange = viewModel::onAmbientAnimationEnabledChanged,
            )
        }
        // The style only means something while the backdrop moves.
        if (uiState.ambientAnimationEnabled) {
            nowPlaying += {
                SegmentedRow {
                    SettingsSegmented(
                        options = listOf(
                            stringResource(R.string.settings_ambient_style_soft_glow),
                            stringResource(R.string.settings_ambient_style_classic),
                        ), // AmbientStyle order
                        selectedIndex = uiState.ambientStyle.ordinal,
                        onSelect = { viewModel.onAmbientStyleChanged(AmbientStyle.entries[it]) },
                    )
                }
            }
        }
        nowPlaying += {
            SettingsToggleRow(
                title = stringResource(R.string.settings_cycle_album_art_title),
                subtitle = stringResource(R.string.settings_cycle_album_art_subtitle),
                checked = uiState.cycleAlbumArt,
                onCheckedChange = viewModel::onCycleAlbumArtChanged,
            )
        }
        SettingsGroupCard(rows = nowPlaying)

        SettingsSectionLabel(stringResource(R.string.settings_open_stash_on_section))
        SettingsGroupCard(
            rows = listOf(
                {
                    SegmentedRow {
                        SettingsSegmented(
                            options = listOf(
                                stringResource(R.string.settings_start_tab_home),
                                stringResource(R.string.settings_start_tab_library),
                                stringResource(R.string.settings_start_tab_search),
                            ), // StartTab order
                            selectedIndex = startTab.ordinal,
                            onSelect = { viewModel.onStartTabChanged(StartTab.entries[it]) },
                        )
                    }
                },
            ),
        )

        SettingsSectionLabel(stringResource(R.string.settings_home_section))
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_home_layout_section),
                        subtitle = stringResource(
                            R.string.settings_home_layout_summary,
                            uiState.homeSectionsShown(),
                            uiState.homeSectionOrder.size,
                        ),
                        onClick = onOpenHomeLayout,
                    )
                },
            ),
        )
    }
}

/** A segmented control as a card row, inset like the other rows. */
@Composable
private fun SegmentedRow(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(10.dp)) { content() }
}
