package com.stash.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.AmbientStyle
import com.stash.core.data.prefs.FullArtBlend
import com.stash.core.data.prefs.FullArtPan
import com.stash.core.data.prefs.NpTitleAlign
import com.stash.core.data.prefs.TitleButton
import com.stash.core.data.prefs.NowPlayingPreference
import com.stash.core.data.prefs.StartTab
import com.stash.core.model.ThemeMode
import com.stash.core.model.UiScale
import com.stash.core.ui.theme.LocalUiScaleMax
import com.stash.feature.settings.components.SettingsChoice
import com.stash.feature.settings.components.SettingsChoiceSheet
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsValueRow
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
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
    val fullArtZoom by viewModel.fullArtZoom.collectAsStateWithLifecycle()
    val fullArtPan by viewModel.fullArtPan.collectAsStateWithLifecycle()
    val discogsArt by viewModel.discogsArt.collectAsStateWithLifecycle()
    val titleAlign by viewModel.titleAlign.collectAsStateWithLifecycle()
    val titleButton by viewModel.titleButton.collectAsStateWithLifecycle()
    val fullArtBlend by viewModel.fullArtBlend.collectAsStateWithLifecycle()
    var titleButtonSheet by rememberSaveable { mutableStateOf(false) }
    // As the display size: the slider moves a draft, stored once on release.
    var draftZoom by remember(fullArtZoom) { mutableFloatStateOf(fullArtZoom) }

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
        nowPlaying += {
            SettingsToggleRow(
                title = stringResource(R.string.settings_discogs_art_title),
                subtitle = stringResource(R.string.settings_discogs_art_subtitle),
                checked = discogsArt,
                onCheckedChange = viewModel::onDiscogsArtChanged,
            )
        }
        nowPlaying += {
            LabeledSegmented(
                title = stringResource(R.string.settings_title_align_title),
                subtitle = null,
                options = listOf(
                    stringResource(R.string.settings_title_align_center),
                    stringResource(R.string.settings_title_align_left),
                ), // NpTitleAlign order
                selectedIndex = titleAlign.ordinal,
                onSelect = { viewModel.onTitleAlignChanged(NpTitleAlign.entries[it]) },
            )
        }
        nowPlaying += {
            SettingsValueRow(
                title = stringResource(R.string.settings_title_button_title),
                value = titleButtonLabel(titleButton),
                subtitle = stringResource(R.string.settings_title_button_subtitle),
                onClick = { titleButtonSheet = true },
            )
        }
        SettingsGroupCard(rows = nowPlaying)

        // Full art (the toggle in Now Playing's top row): how close it shows the album, how fast
        // each page pans across what the screen can't show, and what it melts into.
        SettingsSectionLabel(stringResource(R.string.settings_full_art_section))
        val fullArt = mutableListOf<@Composable () -> Unit>()
        fullArt += {
            SettingsSliderRow(
                title = stringResource(R.string.settings_full_art_zoom_title),
                valueLabel = String.format(java.util.Locale.US, "%.1fx", draftZoom),
                subtitle = stringResource(R.string.settings_full_art_zoom_subtitle),
                value = draftZoom,
                onValueChange = { draftZoom = Math.round(it * 10f) / 10f },
                onValueChangeFinished = { viewModel.onFullArtZoomChanged(draftZoom) },
                valueRange = NowPlayingPreference.FULL_ART_ZOOM_MIN..NowPlayingPreference.FULL_ART_ZOOM_MAX,
                steps = 9, // a detent every 0.1
                onReset = {
                    draftZoom = NowPlayingPreference.FULL_ART_ZOOM_DEFAULT
                    viewModel.onFullArtZoomChanged(NowPlayingPreference.FULL_ART_ZOOM_DEFAULT)
                },
                resetEnabled = draftZoom != NowPlayingPreference.FULL_ART_ZOOM_DEFAULT,
            )
        }
        fullArt += {
            LabeledSegmented(
                title = stringResource(R.string.settings_full_art_pan_title),
                subtitle = stringResource(R.string.settings_full_art_pan_subtitle),
                options = listOf(
                    stringResource(R.string.settings_full_art_pan_off),
                    stringResource(R.string.settings_full_art_pan_slow),
                    stringResource(R.string.settings_full_art_pan_normal),
                    stringResource(R.string.settings_full_art_pan_fast),
                ), // FullArtPan order
                selectedIndex = fullArtPan.ordinal,
                onSelect = { viewModel.onFullArtPanChanged(FullArtPan.entries[it]) },
            )
        }
        fullArt += {
            LabeledSegmented(
                title = stringResource(R.string.settings_full_art_blend_title),
                subtitle = stringResource(R.string.settings_full_art_blend_subtitle),
                options = listOf(
                    stringResource(R.string.settings_full_art_blend_blurred),
                    stringResource(R.string.settings_full_art_blend_solid),
                ), // FullArtBlend order
                selectedIndex = fullArtBlend.ordinal,
                onSelect = { viewModel.onFullArtBlendChanged(FullArtBlend.entries[it]) },
            )
        }
        SettingsGroupCard(rows = fullArt)

        if (titleButtonSheet) {
            SettingsChoiceSheet(
                title = stringResource(R.string.settings_title_button_title),
                choices = TitleButton.entries.map { SettingsChoice(it, titleButtonLabel(it)) },
                selected = titleButton,
                onSelect = viewModel::onTitleButtonChanged,
                onDismiss = { titleButtonSheet = false },
            )
        }

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

/** A row's title (and subtitle) over a segmented control, inset like the other rows. */
@Composable
private fun LabeledSegmented(
    title: String,
    subtitle: String?,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        SettingsSegmented(options = options, selectedIndex = selectedIndex, onSelect = onSelect)
    }
}

/** What Settings calls each choice for the button beside Now Playing's title. */
@Composable
private fun titleButtonLabel(button: TitleButton): String = stringResource(
    when (button) {
        TitleButton.SLEEP_TIMER -> R.string.settings_title_button_sleep_timer
        TitleButton.NONE -> R.string.settings_title_button_none
        TitleButton.LYRICS -> R.string.settings_title_button_lyrics
        TitleButton.QUEUE -> R.string.settings_title_button_queue
        TitleButton.RADIO -> R.string.settings_title_button_radio
        TitleButton.PLAYBACK_SPEED -> R.string.settings_title_button_speed
        TitleButton.SAVE_TO_PLAYLIST -> R.string.settings_title_button_save
        TitleButton.SHARE -> R.string.settings_title_button_share
        TitleButton.DOWNLOAD -> R.string.settings_title_button_download
        TitleButton.VIEW_ALBUM -> R.string.settings_title_button_album
    },
)

/** A segmented control as a card row, inset like the other rows. */
@Composable
private fun SegmentedRow(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(10.dp)) { content() }
}
