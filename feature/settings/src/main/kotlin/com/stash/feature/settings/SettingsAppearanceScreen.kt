package com.stash.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stash.feature.settings.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.StartTab
import com.stash.core.model.ThemeMode
import com.stash.core.model.UiScale
import com.stash.core.ui.theme.LocalUiScaleMax
import com.stash.core.ui.theme.StashBackground
import com.stash.core.ui.theme.StashBackgroundLight
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextPrimaryLight
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.core.ui.theme.StashTextSecondaryLight
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsSegmented
import com.stash.feature.settings.components.SettingsSliderRow
import com.stash.feature.settings.components.SettingsToggleRow

/**
 * Appearance category screen. Replaces the old RadioButton theme list
 * (SettingsScreen.kt:1089-1139) with three tappable T1 theme-preview thumbnails
 * (Dark / Light / Follow system). The underlying control is the SAME existing
 * callback: [SettingsViewModel.onThemeChanged]. Below the thumbnails, a
 * "Pure black (AMOLED)" switch upgrades whichever dark theme is in effect
 * to true #000000 backgrounds.
 */
@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemeThumbnail(
                label = stringResource(R.string.settings_theme_dark),
                selected = uiState.themeMode == ThemeMode.DARK,
                darkPalette = true,
                followSystem = false,
                onClick = { viewModel.onThemeChanged(ThemeMode.DARK) },
                modifier = Modifier.weight(1f),
            )
            ThemeThumbnail(
                label = stringResource(R.string.settings_theme_light),
                selected = uiState.themeMode == ThemeMode.LIGHT,
                darkPalette = false,
                followSystem = false,
                onClick = { viewModel.onThemeChanged(ThemeMode.LIGHT) },
                modifier = Modifier.weight(1f),
            )
            ThemeThumbnail(
                label = stringResource(R.string.settings_theme_follow_system),
                selected = uiState.themeMode == ThemeMode.SYSTEM,
                darkPalette = false,
                followSystem = true,
                onClick = { viewModel.onThemeChanged(ThemeMode.SYSTEM) },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = stringResource(R.string.settings_theme_follow_system_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
        )

        Spacer(Modifier.height(20.dp))
        SettingsSectionLabel(stringResource(R.string.settings_display_size_section))

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

        Spacer(Modifier.height(20.dp))
        SettingsSectionLabel(stringResource(R.string.settings_dark_theme_section))

        SettingsToggleRow(
            title = stringResource(R.string.settings_amoled_title),
            subtitle = stringResource(R.string.settings_amoled_subtitle),
            checked = uiState.amoledDark,
            onCheckedChange = viewModel::onAmoledDarkChanged,
        )

        Spacer(Modifier.height(20.dp))
        SettingsSectionLabel(stringResource(R.string.settings_now_playing_section))

        SettingsToggleRow(
            title = stringResource(R.string.settings_ambient_motion_title),
            subtitle = stringResource(R.string.settings_ambient_motion_subtitle),
            checked = uiState.ambientAnimationEnabled,
            onCheckedChange = viewModel::onAmbientAnimationEnabledChanged,
        )

        Text(
            text = stringResource(R.string.settings_ambient_style_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (uiState.ambientAnimationEnabled) 1f else 0.38f),
            // Lines up with the switch rows' titles above.
            modifier = Modifier.padding(start = com.stash.feature.settings.components.SettingsRowPadH, end = com.stash.feature.settings.components.SettingsRowPadH, top = 4.dp, bottom = 8.dp),
        )
        SettingsSegmented(
            options = listOf(
                stringResource(R.string.settings_ambient_style_soft_glow),
                stringResource(R.string.settings_ambient_style_classic),
            ), // AmbientStyle order
            selectedIndex = uiState.ambientStyle.ordinal,
            onSelect = { viewModel.onAmbientStyleChanged(com.stash.core.data.prefs.AmbientStyle.entries[it]) },
            enabled = uiState.ambientAnimationEnabled,
        )
        Text(
            text = if (uiState.ambientAnimationEnabled) {
                stringResource(R.string.settings_ambient_style_desc)
            } else {
                stringResource(R.string.settings_ambient_style_disabled_desc)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
        )

        Spacer(Modifier.height(20.dp))
        SettingsSectionLabel(stringResource(R.string.settings_open_stash_on_section))
        SettingsSegmented(
            options = listOf(
                stringResource(R.string.settings_start_tab_home),
                stringResource(R.string.settings_start_tab_library),
                stringResource(R.string.settings_start_tab_search),
            ), // StartTab order
            selectedIndex = startTab.ordinal,
            onSelect = { viewModel.onStartTabChanged(StartTab.entries[it]) },
        )
        Text(
            text = stringResource(R.string.settings_start_tab_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
        )

        Spacer(Modifier.height(20.dp))
        SettingsSectionLabel(stringResource(R.string.settings_home_layout_section))
        Text(
            text = stringResource(R.string.settings_home_layout_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        uiState.homeSectionOrder.forEachIndexed { index, section ->
            // Community's switch is its own, off by default (spec 2026-09-26 §3), not the hidden set.
            val community = section == com.stash.core.data.prefs.HomeSection.COMMUNITY
            HomeSectionRow(
                label = section.displayLabel(),
                subtitle = when {
                    community -> stringResource(R.string.settings_home_section_community_desc)
                    section == com.stash.core.data.prefs.HomeSection.YOUR_LISTENING ->
                        stringResource(R.string.settings_home_section_listening_desc)
                    else -> null
                },
                shown = if (community) uiState.communityOn else section !in uiState.homeSectionsHidden,
                canMoveUp = index > 0,
                canMoveDown = index < uiState.homeSectionOrder.lastIndex,
                onMoveUp = { viewModel.onHomeSectionMoved(section, up = true) },
                onMoveDown = { viewModel.onHomeSectionMoved(section, up = false) },
                onShownChange = { shown ->
                    if (community) viewModel.onCommunityOnChanged(shown) else viewModel.onHomeSectionHiddenChanged(section, hide = !shown)
                },
            )
        }

        SettingsToggleRow(
            title = stringResource(R.string.settings_home_show_liked_title),
            subtitle = stringResource(R.string.settings_home_show_liked_subtitle),
            checked = uiState.showLikedOnHome,
            onCheckedChange = viewModel::onShowLikedOnHomeChanged,
            // Hiding the whole section above overrides this switch — grey it
            // out rather than leave a live-looking control that does nothing.
            enabled = com.stash.core.data.prefs.HomeSection.YOUR_PLAYLISTS !in uiState.homeSectionsHidden,
        )
    }
}

@Composable
private fun com.stash.core.data.prefs.HomeSection.displayLabel(): String = when (this) {
    com.stash.core.data.prefs.HomeSection.DISCOVER -> stringResource(R.string.settings_home_section_discover)
    com.stash.core.data.prefs.HomeSection.YOUR_PLAYLISTS -> stringResource(R.string.settings_home_section_your_playlists)
    com.stash.core.data.prefs.HomeSection.NEW_RELEASES -> stringResource(R.string.settings_home_section_new_releases)
    com.stash.core.data.prefs.HomeSection.QOBUZ_PLAYLISTS -> stringResource(R.string.settings_home_section_qobuz_playlists)
    com.stash.core.data.prefs.HomeSection.TOP_ALBUMS -> stringResource(R.string.settings_home_section_top_albums)
    com.stash.core.data.prefs.HomeSection.MADE_FOR_YOU -> stringResource(R.string.settings_home_section_made_for_you)
    com.stash.core.data.prefs.HomeSection.RADIOS -> stringResource(R.string.settings_home_section_radios)
    com.stash.core.data.prefs.HomeSection.MOOD_DECADES -> stringResource(R.string.settings_home_section_mood_decades)
    com.stash.core.data.prefs.HomeSection.COMMUNITY -> stringResource(R.string.settings_home_section_community)
    com.stash.core.data.prefs.HomeSection.YOUR_LISTENING -> stringResource(R.string.settings_home_section_your_listening)
}

/** One row of the Home layout editor: name, up/down movers, show switch. */
@Composable
private fun HomeSectionRow(
    label: String,
    subtitle: String? = null,
    shown: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onShownChange: (Boolean) -> Unit,
) {
    val showDesc = stringResource(R.string.settings_home_section_show_desc, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (shown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.settings_home_section_move_up, label),
                tint = if (canMoveUp) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.settings_home_section_move_down, label),
                tint = if (canMoveDown) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
            )
        }
        com.stash.core.ui.components.StashSwitch(
            checked = shown,
            onCheckedChange = onShownChange,
            modifier = Modifier.semantics { contentDescription = showDesc },
        )
    }
}

@Composable
private fun ThemeThumbnail(
    label: String,
    selected: Boolean,
    darkPalette: Boolean,
    followSystem: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentRing = MaterialTheme.colorScheme.primary
    val previewShape = RoundedCornerShape(12.dp)
    val selectionRing = if (selected) {
        Modifier.border(2.dp, accentRing, previewShape)
    } else {
        Modifier.border(1.dp, StashTheme.extendedColors.glassBorder, previewShape)
    }

    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(previewShape)
                .then(selectionRing),
        ) {
            if (followSystem) {
                // Vertical split reads as "both" — robust, no fragile diagonal clipping.
                Row(modifier = Modifier.fillMaxSize()) {
                    PalettePreview(
                        dark = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    PalettePreview(
                        dark = false,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                PalettePreview(dark = darkPalette, modifier = Modifier.fillMaxSize())
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(accentRing),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accentRing else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A tiny mock app screen rendered in one palette (dark or light). */
@Composable
private fun PalettePreview(
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (dark) StashBackground else StashBackgroundLight
    val textPrimary = if (dark) StashTextPrimary else StashTextPrimaryLight
    val textSecondary = if (dark) StashTextSecondary else StashTextSecondaryLight
    val accent = if (dark) MaterialTheme.colorScheme.primary else StashPurple

    Column(
        modifier = modifier
            .background(background)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Title bar
        Box(
            Modifier
                .height(6.dp)
                .fillMaxWidth(0.5f)
                .clip(RoundedCornerShape(3.dp))
                .background(textPrimary),
        )
        // Two thinner text bars
        Box(
            Modifier
                .height(4.dp)
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(2.dp))
                .background(textSecondary),
        )
        Box(
            Modifier
                .height(4.dp)
                .fillMaxWidth(0.65f)
                .clip(RoundedCornerShape(2.dp))
                .background(textSecondary),
        )
        Spacer(Modifier.weight(1f))
        // Progress pill
        Box(
            Modifier
                .height(5.dp)
                .fillMaxWidth(0.7f)
                .clip(CircleShape)
                .background(accent),
        )
    }
}
