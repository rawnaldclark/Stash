package com.stash.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.HomeSection
import com.stash.core.ui.components.rememberReorderableColumnState
import com.stash.core.ui.components.reorderHandle
import com.stash.core.ui.components.reorderableColumn
import com.stash.core.ui.components.reorderableRow
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
import com.stash.feature.settings.components.settingsDivider

/** How many of Home's sections are showing (Community has its own switch, not the hidden set). */
internal fun SettingsUiState.homeSectionsShown(): Int = homeSectionOrder.count { section ->
    if (section == HomeSection.COMMUNITY) communityOn else section !in homeSectionsHidden
}

/**
 * One movable entry of the Home layout editor: [section], whether it can go up or down a place (TalkBack's Move up / Move
 * down), and [under], the section that goes wherever it goes (Your record's mixes, always directly under the card:
 * [com.stash.core.data.prefs.movedHomeSectionOrder]), shown indented beneath it with a switch and no handle.
 */
internal data class HomeLayoutEntry(
    val section: HomeSection,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val under: HomeSection? = null,
)

/**
 * [order] as the editor's entries. The moves count only the sections with a place of their own, so "Your record" just
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
 * Settings > Appearance > Home layout: Home's sections in order, each with a
 * show switch and a handle to drag it, the queue's way, then Show Liked
 * Songs. Its own page so Appearance stays one screen. Your record's mixes sit
 * indented under Your record with a switch only: they go wherever the card goes.
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
    // The page's own scroll, so holding a dragged section near the top or bottom can scroll it.
    val scrollState = rememberScrollState()
    SettingsScaffold(
        title = stringResource(R.string.settings_home_layout_section),
        onBack = onBack,
        modifier = modifier,
        scrollState = scrollState,
    ) {
        Text(
            text = stringResource(R.string.settings_home_layout_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        val order = uiState.homeSectionOrder
        // Dragged by the handle like a song in the queue; the drop saves the order once, as one move.
        val reorder = rememberReorderableColumnState(
            keys = homeLayoutEntries(order).map { it.section },
            scrollState = scrollState,
            onMove = viewModel::onHomeSectionDropped,
        )
        // The rows as shown, a drag's moves included, with the record's mixes under the card when the order has them.
        val entries = homeLayoutEntries(
            if (HomeSection.RECORD_MIXES in order) reorder.rows + HomeSection.RECORD_MIXES else reorder.rows,
        )
        SettingsGroupCard(
            rows = listOf(
                {
                    // All the sections in one of the card's rows, each drawing its own hairline above it, so the
                    // hairlines move with the rows as they slide.
                    Column(modifier = Modifier.fillMaxWidth().reorderableColumn(reorder)) {
                        entries.forEachIndexed { i, entry ->
                            key(entry.section) {
                                val section = entry.section
                                val held = reorder.isDragging(section)
                                // Community's switch is its own, off by default (spec 2026-09-26 §3), not the hidden set.
                                val community = section == HomeSection.COMMUNITY
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .reorderableRow(reorder, section)
                                        // The queue's held row: opaque, so the rows it passes over don't show through.
                                        .then(
                                            if (held) Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                            else Modifier,
                                        ),
                                ) {
                                    // Always laid out, so a row is the same height wherever it goes; none shows above
                                    // the first row or on the held one.
                                    HorizontalDivider(
                                        color = if (i > 0 && !held) settingsDivider else Color.Transparent,
                                        thickness = 1.dp,
                                    )
                                    HomeSectionRow(
                                        label = section.displayLabel(),
                                        subtitle = section.subtitle(),
                                        shown = if (community) uiState.communityOn else section !in uiState.homeSectionsHidden,
                                        held = held,
                                        canMoveUp = entry.canMoveUp && !reorder.isDragging,
                                        canMoveDown = entry.canMoveDown && !reorder.isDragging,
                                        onMoveUp = { viewModel.onHomeSectionMoved(section, up = true) },
                                        onMoveDown = { viewModel.onHomeSectionMoved(section, up = false) },
                                        onShownChange = { shown ->
                                            if (community) viewModel.onCommunityOnChanged(shown) else viewModel.onHomeSectionHiddenChanged(section, hide = !shown)
                                        },
                                        handleModifier = Modifier.reorderHandle(reorder, section),
                                    )
                                    // Your record's mixes: in the same row (no hairline), indented, a switch only. They go
                                    // where the card goes, dragged with it, and stay on Home when the card is switched off.
                                    entry.under?.let { under ->
                                        HomeSectionUnderRow(
                                            label = under.displayLabel(),
                                            subtitle = under.subtitle(),
                                            shown = under !in uiState.homeSectionsHidden,
                                            onShownChange = { shown -> viewModel.onHomeSectionHiddenChanged(under, hide = !shown) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            ),
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
    HomeSection.NEW_FROM_ARTISTS -> stringResource(R.string.settings_home_section_new_from_artists)
    HomeSection.BECAUSE_YOU_LIKE -> stringResource(R.string.settings_home_section_because_you_like)
}

/** The line under a section's name, for the sections that need one. */
@Composable
private fun HomeSection.subtitle(): String? = when (this) {
    HomeSection.COMMUNITY -> stringResource(R.string.settings_home_section_community_desc)
    HomeSection.YOUR_LISTENING -> stringResource(R.string.settings_home_section_listening_desc)
    HomeSection.YOUR_RECORD -> stringResource(R.string.settings_home_section_record_desc)
    HomeSection.RECORD_MIXES -> stringResource(R.string.settings_home_section_record_mixes_desc)
    HomeSection.NEW_FROM_ARTISTS -> stringResource(R.string.settings_home_section_new_from_artists_desc)
    HomeSection.BECAUSE_YOU_LIKE -> stringResource(R.string.settings_home_section_because_you_like_desc)
    else -> null
}

/**
 * A section that hangs off the row above it (Your record's mixes under the card): indented under its name, with the show
 * switch and no handle, since it goes wherever the row above goes.
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
            // One step in from the name above; the same end padding as HomeSectionRow and a handle's room after the
            // switch, so both switches line up.
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
        Spacer(modifier = Modifier.size(HandleSize))
    }
}

/** How far the record's mixes sit in from the card's row in the Home layout editor. */
private val UnderRowIndent = 20.dp

/** The drag handle's touch target: the queue's. */
private val HandleSize = 48.dp

/**
 * One row of the Home layout editor: name, show switch, and the queue's drag handle at the end ([handleModifier] makes it
 * drag the row). The drag is touch only, so TalkBack and switch access move the row with its Move up / Move down actions,
 * one place at a time, as the arrows did.
 */
@Composable
private fun HomeSectionRow(
    label: String,
    subtitle: String? = null,
    shown: Boolean,
    held: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onShownChange: (Boolean) -> Unit,
    handleModifier: Modifier,
) {
    val showDesc = stringResource(R.string.settings_home_section_show_desc, label)
    val moveUp = stringResource(R.string.settings_home_section_move_up, label)
    val moveDown = stringResource(R.string.settings_home_section_move_down, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One stop for TalkBack (the name and the line under it, with the moves); the switch stays a stop of its own.
            .semantics(mergeDescendants = true) {
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction(moveUp) { onMoveUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction(moveDown) { onMoveDown(); true })
                }
            }
            .padding(start = SettingsRowPadH, end = 6.dp, top = 4.dp, bottom = 4.dp),
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
        Box(
            modifier = Modifier.size(HandleSize).then(handleModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = null, // TalkBack moves the row with its Move up / Move down actions
                tint = if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp),
            )
        }
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
