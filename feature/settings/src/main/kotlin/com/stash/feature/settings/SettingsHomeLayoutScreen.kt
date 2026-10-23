package com.stash.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.HomeSection
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsToggleRow

/** How many of Home's sections are showing (Community has its own switch, not the hidden set). */
internal fun SettingsUiState.homeSectionsShown(): Int = homeSectionOrder.count { section ->
    if (section == HomeSection.COMMUNITY) communityOn else section !in homeSectionsHidden
}

/**
 * Settings > Appearance > Home layout: Home's sections in order, each with
 * up/down movers and a show switch, then Show Liked Songs. Its own page so
 * Appearance stays one screen.
 */
@Composable
fun SettingsHomeLayoutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(R.string.settings_home_layout_section), onBack = onBack, modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_home_layout_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        SettingsGroupCard(
            rows = uiState.homeSectionOrder.mapIndexed { index, section ->
                {
                    // Community's switch is its own, off by default (spec 2026-09-26 §3), not the hidden set.
                    val community = section == HomeSection.COMMUNITY
                    HomeSectionRow(
                        label = section.displayLabel(),
                        subtitle = when {
                            community -> stringResource(R.string.settings_home_section_community_desc)
                            section == HomeSection.YOUR_LISTENING ->
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
            },
        )
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_home_show_liked_title),
                        subtitle = stringResource(R.string.settings_home_show_liked_subtitle),
                        checked = uiState.showLikedOnHome,
                        onCheckedChange = viewModel::onShowLikedOnHomeChanged,
                        // Hiding the whole section above overrides this switch — grey it
                        // out rather than leave a live-looking control that does nothing.
                        enabled = HomeSection.YOUR_PLAYLISTS !in uiState.homeSectionsHidden,
                    )
                },
            ),
        )
    }
}

@Composable
private fun HomeSection.displayLabel(): String = when (this) {
    HomeSection.DISCOVER -> stringResource(R.string.settings_home_section_discover)
    HomeSection.YOUR_PLAYLISTS -> stringResource(R.string.settings_home_section_your_playlists)
    HomeSection.NEW_RELEASES -> stringResource(R.string.settings_home_section_new_releases)
    HomeSection.QOBUZ_PLAYLISTS -> stringResource(R.string.settings_home_section_qobuz_playlists)
    HomeSection.TOP_ALBUMS -> stringResource(R.string.settings_home_section_top_albums)
    HomeSection.MADE_FOR_YOU -> stringResource(R.string.settings_home_section_made_for_you)
    HomeSection.RADIOS -> stringResource(R.string.settings_home_section_radios)
    HomeSection.MOOD_DECADES -> stringResource(R.string.settings_home_section_mood_decades)
    HomeSection.COMMUNITY -> stringResource(R.string.settings_home_section_community)
    HomeSection.YOUR_LISTENING -> stringResource(R.string.settings_home_section_your_listening)
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
            .padding(start = SettingsRowPadH, end = 6.dp, top = 4.dp, bottom = 4.dp),
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
