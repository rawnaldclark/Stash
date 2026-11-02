package com.stash.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.stash.feature.settings.components.SettingsValueRow
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsChoiceSheet
import com.stash.feature.settings.components.SettingsChoice
import com.stash.core.data.prefs.MixRotation
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsToggleRow

/** How many of Home's sections are showing (Community has its own switch, not the hidden set). */
internal fun SettingsUiState.homeSectionsShown(): Int = homeSectionOrder.count { section ->
    if (section == HomeSection.COMMUNITY) communityOn else section !in homeSectionsHidden
}

/**
 * One movable entry of the Home layout editor: [section] with its movers' state, and [under], the section that goes wherever
 * it goes (Your record's mixes, always directly under the card: [com.stash.core.data.prefs.movedHomeSectionOrder]), shown
 * indented beneath it with a switch and no movers.
 */
internal data class HomeLayoutEntry(
    val section: HomeSection,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val under: HomeSection? = null,
)

/**
 * [order] as the editor's entries. The movers count only the sections with a place of their own, so "Your record" just
 * above its own mixes can still move down (past the next section, with them) and is never offered a move that does nothing.
 */
internal fun homeLayoutEntries(order: List<HomeSection>): List<HomeLayoutEntry> {
    val movable = order.filter { it != HomeSection.RECORD_MIXES }
    return movable.mapIndexed { i, section ->
        HomeLayoutEntry(
            section = section,
            canMoveUp = i > 0,
            canMoveDown = i < movable.lastIndex,
            under = HomeSection.RECORD_MIXES.takeIf { section == HomeSection.YOUR_RECORD && it in order },
        )
    }
}

/**
 * Settings > Appearance > Home layout: Home's sections in order, each with
 * up/down movers and a show switch, then Show Liked Songs. Its own page so
 * Appearance stays one screen. Your record's mixes sit indented under
 * Your record with a switch only: they go wherever the card goes.
 */
@Composable
fun SettingsHomeLayoutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    rotationViewModel: HomeMixRotationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rotation by rotationViewModel.rotation.collectAsStateWithLifecycle()
    val unplayedFirst by rotationViewModel.unplayedFirst.collectAsStateWithLifecycle()
    var showRotationSheet by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(title = stringResource(R.string.settings_home_layout_section), onBack = onBack, modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_home_layout_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        SettingsGroupCard(
            rows = homeLayoutEntries(uiState.homeSectionOrder).map { entry ->
                {
                    val section = entry.section
                    // Community's switch is its own, off by default (spec 2026-09-26 §3), not the hidden set.
                    val community = section == HomeSection.COMMUNITY
                    HomeSectionRow(
                        label = section.displayLabel(),
                        subtitle = section.subtitle(),
                        shown = if (community) uiState.communityOn else section !in uiState.homeSectionsHidden,
                        canMoveUp = entry.canMoveUp,
                        canMoveDown = entry.canMoveDown,
                        onMoveUp = { viewModel.onHomeSectionMoved(section, up = true) },
                        onMoveDown = { viewModel.onHomeSectionMoved(section, up = false) },
                        onShownChange = { shown ->
                            if (community) viewModel.onCommunityOnChanged(shown) else viewModel.onHomeSectionHiddenChanged(section, hide = !shown)
                        },
                    )
                    // Your record's mixes: in the same row group (no divider), indented, a switch only. They go where the
                    // card goes, and stay on Home when the card is switched off.
                    entry.under?.let { under ->
                        HomeSectionUnderRow(
                            label = under.displayLabel(),
                            subtitle = under.subtitle(),
                            shown = under !in uiState.homeSectionsHidden,
                            onShownChange = { shown -> viewModel.onHomeSectionHiddenChanged(under, hide = !shown) },
                        )
                    }
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

        // Made for you and Mood & decades: how often they reshuffle, and whether the
        // mixes you've barely played lead. Favorites (long-press a mix) always lead.
        SettingsSectionLabel(stringResource(R.string.settings_home_mixes_section))
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsValueRow(
                        title = stringResource(R.string.settings_mix_rotation_title),
                        value = rotation.label(),
                        onClick = { showRotationSheet = true },
                    )
                },
                {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_unplayed_mixes_first_title),
                        subtitle = stringResource(R.string.settings_unplayed_mixes_first_subtitle),
                        checked = unplayedFirst,
                        onCheckedChange = rotationViewModel::setUnplayedFirst,
                    )
                },
            ),
        )
        Text(
            text = stringResource(R.string.settings_home_mixes_favorite_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (showRotationSheet) {
            SettingsChoiceSheet(
                title = stringResource(R.string.settings_mix_rotation_title),
                choices = MixRotation.entries.map { SettingsChoice(it, it.label()) },
                selected = rotation,
                onSelect = rotationViewModel::setRotation,
                onDismiss = { showRotationSheet = false },
                footer = stringResource(R.string.settings_mix_rotation_footer),
            )
        }
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
    HomeSection.YOUR_RECORD -> stringResource(R.string.settings_home_section_your_record)
    HomeSection.RECORD_MIXES -> stringResource(R.string.settings_home_section_record_mixes)
}

/** The line under a section's name, for the sections that need one. */
@Composable
private fun HomeSection.subtitle(): String? = when (this) {
    HomeSection.COMMUNITY -> stringResource(R.string.settings_home_section_community_desc)
    HomeSection.YOUR_LISTENING -> stringResource(R.string.settings_home_section_listening_desc)
    HomeSection.YOUR_RECORD -> stringResource(R.string.settings_home_section_record_desc)
    HomeSection.RECORD_MIXES -> stringResource(R.string.settings_home_section_record_mixes_desc)
    else -> null
}

/**
 * A section that hangs off the row above it (Your record's mixes under the card): indented under its name, with the show
 * switch and no movers, since it goes wherever the row above goes.
 */
@Composable
private fun HomeSectionUnderRow(
    label: String,
    subtitle: String?,
    shown: Boolean,
    onShownChange: (Boolean) -> Unit,
) {
    val showDesc = stringResource(R.string.settings_home_section_show_desc, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // One step in from the name above; the same end padding as HomeSectionRow, so both switches line up.
            .padding(start = SettingsRowPadH + UnderRowIndent, end = 6.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
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
        com.stash.core.ui.components.StashSwitch(
            checked = shown,
            onCheckedChange = onShownChange,
            modifier = Modifier.semantics { contentDescription = showDesc },
        )
    }
}

/** How far the record's mixes sit in from the card's row in the Home layout editor. */
private val UnderRowIndent = 20.dp

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

@Composable
private fun MixRotation.label(): String = stringResource(
    when (this) {
        MixRotation.OFF -> R.string.settings_mix_rotation_off
        MixRotation.DAILY -> R.string.settings_mix_rotation_daily
        MixRotation.EVERY_3_DAYS -> R.string.settings_mix_rotation_3_days
        MixRotation.WEEKLY -> R.string.settings_mix_rotation_weekly
    },
)
