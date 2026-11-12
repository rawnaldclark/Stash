package com.stash.feature.settings.addons

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.addons.OfficialEntry
import com.stash.core.ui.components.StashSwitch
import com.stash.core.ui.components.rememberReorderableColumnState
import com.stash.core.ui.components.reorderHandle
import com.stash.core.ui.components.reorderableColumn
import com.stash.core.ui.components.reorderableRow
import com.stash.data.download.lossless.addon.AddonOverview
import com.stash.feature.settings.R
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.settingsDivider
import kotlinx.coroutines.delay

/**
 * Settings › Add-ons (add-ons plan phase 4 §2.1, §2.8): where songs come from, and in what order.
 *
 * **Play order**: Your Qobuz account (only when one is connected), Tidal, and each add-on that
 * plays songs, in the order Stash asks them, for streaming and for downloads alike. A row is dragged
 * by its handle, Home layout's way (TalkBack moves it with Move up / Move down); the first drag
 * saves the order. Each add-on has a switch, and says Official or Not made by Stash. Tapping a row
 * opens its page.
 *
 * **Get add-ons**: Add from a link (paste, or scan a code), and the official add-ons not added yet.
 */
@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    onOpenAddon: (key: String) -> Unit,
    /** Opens Add from a link; with a link, straight at its preview (an official add-on's Add). */
    onAddFromLink: (link: String?) -> Unit,
    onOpenLosslessSources: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddonsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val highlighted by viewModel.highlighted.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    SaveFailedToast(saveFailed)
    // The new add-on's row stays tinted for a moment, then the mark is spent.
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(HIGHLIGHT_MS)
            viewModel.onHighlightShown()
        }
    }
    AddonsContent(
        state = state,
        highlightedEntry = highlighted,
        onBack = onBack,
        onOpen = { row -> if (row.kind == AddonOverview.Kind.QOBUZ) onOpenLosslessSources() else onOpenAddon(row.key) },
        onAddFromLink = onAddFromLink,
        onDropped = viewModel::onDropped,
        onNudged = viewModel::onNudged,
        onSwitched = viewModel::onSwitched,
        onRemove = viewModel::onRemove,
        modifier = modifier,
    )
}

/** [AddonsScreen] without its view model, for tests. */
@Composable
internal fun AddonsContent(
    state: AddonOverview.State,
    highlightedEntry: String?,
    onBack: () -> Unit,
    onOpen: (AddonOverview.Row) -> Unit,
    onAddFromLink: (String?) -> Unit,
    onDropped: (key: String, to: Int) -> Unit,
    onNudged: (key: String, up: Boolean) -> Unit,
    onSwitched: (AddonOverview.Row, Boolean) -> Unit,
    /** Remove on a row Stash turned off, after the user confirmed it. */
    onRemove: (AddonOverview.Row) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The page's own scroll, so holding a dragged row near the top or bottom can scroll it.
    val scrollState = rememberScrollState()
    // The row whose Remove was tapped: it asks first.
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsScaffold(
        title = stringResource(R.string.settings_addons_title),
        onBack = onBack,
        modifier = modifier,
        scrollState = scrollState,
    ) {
        if (state.unreadable) {
            // Not an empty list: the add-ons the user added are still stored, they just couldn't be opened.
            SettingsGroupCard(
                rows = listOf(
                    {
                        Text(
                            text = stringResource(R.string.settings_addons_unreadable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = SettingsRowPadH, vertical = 12.dp),
                        )
                    },
                ),
            )
        }
        if (state.order.isNotEmpty()) {
            SettingsSectionLabel(stringResource(R.string.settings_addons_order_section))
            // One row (Tidal alone, for most people) has nothing to be dragged past: no word about
            // dragging, and no handle on it.
            val canReorder = state.order.size > 1
            if (canReorder) {
                Text(
                    text = stringResource(R.string.settings_addons_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            val rows = state.order.associateBy { it.key }
            // Dragged by the handle like a song in the queue; the drop saves the order once, as one move.
            val reorder = rememberReorderableColumnState(
                keys = state.order.map { it.key },
                scrollState = scrollState,
                onMove = onDropped,
            )
            SettingsGroupCard(
                rows = listOf(
                    {
                        // All the rows in one of the card's rows, each drawing its own hairline above it, so the
                        // hairlines move with the rows as they slide.
                        Column(modifier = Modifier.fillMaxWidth().reorderableColumn(reorder)) {
                            // The rows as shown, a drag's moves included. A key the list no longer has (it changed
                            // while a row was held) draws nothing.
                            val shown = reorder.rows.filter { it in rows }
                            shown.forEachIndexed { i, rowKey ->
                                val row = rows.getValue(rowKey)
                                key(rowKey) {
                                    val held = reorder.isDragging(rowKey)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .reorderableRow(reorder, rowKey)
                                            // The queue's held row: opaque, so the rows it passes over don't show through.
                                            .then(if (held) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier),
                                    ) {
                                        // Always laid out, so a row is the same height wherever it goes; none shows
                                        // above the first row or on the held one.
                                        HorizontalDivider(
                                            color = if (i > 0 && !held) settingsDivider else Color.Transparent,
                                            thickness = 1.dp,
                                        )
                                        AddonRow(
                                            row = row,
                                            highlighted = row.entryKey != null && row.entryKey == highlightedEntry,
                                            onOpen = { onOpen(row) },
                                            onSwitched = { onSwitched(row, it) },
                                            onRemove = { removing = row.key },
                                            held = held,
                                            canMoveUp = i > 0 && !reorder.isDragging,
                                            canMoveDown = i < shown.lastIndex && !reorder.isDragging,
                                            onMoveUp = { onNudged(rowKey, true) },
                                            onMoveDown = { onNudged(rowKey, false) },
                                            handleModifier = if (canReorder) Modifier.reorderHandle(reorder, rowKey) else null,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    {
                        Text(
                            text = stringResource(R.string.settings_addons_order_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = SettingsRowPadH, vertical = 12.dp),
                        )
                    },
                ),
            )
        }

        if (state.others.isNotEmpty()) {
            SettingsSectionLabel(stringResource(R.string.settings_addons_other_section))
            SettingsGroupCard(
                rows = state.others.map { row ->
                    {
                        AddonRow(
                            row = row,
                            highlighted = row.entryKey != null && row.entryKey == highlightedEntry,
                            onOpen = { onOpen(row) },
                            onSwitched = { onSwitched(row, it) },
                            onRemove = { removing = row.key },
                        )
                    }
                },
            )
        }

        SettingsSectionLabel(stringResource(R.string.settings_addons_get_section))
        SettingsGroupCard(
            rows = listOf<@Composable () -> Unit>(
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_addons_link_title),
                        subtitle = stringResource(R.string.settings_addons_link_subtitle),
                        leadingIcon = Icons.Rounded.Link,
                        onClick = { onAddFromLink(null) },
                    )
                },
            ) + state.official.map { entry -> { OfficialRow(entry, onAdd = { onAddFromLink(entry.manifestUrl) }) } },
        )
        PageEndSpace()
    }

    val toRemove = removing?.let { key -> (state.order + state.others).firstOrNull { it.key == key } }
    if (toRemove != null) {
        RemoveAddonDialog(
            row = toRemove,
            title = toRemove.title(),
            onConfirm = {
                removing = null
                onRemove(toRemove)
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * One add-on (or one of the two named rows): its name, its mark and anything wrong with it, its
 * switch, and in the play order the queue's drag handle at the end ([handleModifier] makes it drag
 * the row). Tapping the text opens its page. The drag is touch only, so TalkBack and switch access
 * move the row with its Move up / Move down actions, one place at a time.
 *
 * An add-on Stash turned off has no switch: its row says so in one fixed sentence and offers Remove
 * (plan §1.6), which asks first.
 */
@Composable
private fun AddonRow(
    row: AddonOverview.Row,
    highlighted: Boolean,
    onOpen: () -> Unit,
    onSwitched: (Boolean) -> Unit,
    onRemove: () -> Unit = {},
    held: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    /** Null: a row with no place in the order, so no handle. */
    handleModifier: Modifier? = null,
) {
    val title = row.title()
    val mark = row.markLine()
    val problem = row.problemLine()
    val moveUp = stringResource(R.string.settings_addons_move_up, title)
    val moveDown = stringResource(R.string.settings_addons_move_down, title)
    val switchDesc = stringResource(R.string.settings_addons_switch_desc, title)
    val removeDesc = stringResource(R.string.settings_addons_remove_desc, title)
    val tint by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(400),
        label = "addedAddon",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .heightIn(min = 56.dp)
            .padding(end = if (handleModifier != null) 6.dp else SettingsRowPadH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                // One stop for TalkBack (the name and the lines under it, with the moves); the switch stays a stop of its own.
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    customActions = buildList {
                        if (canMoveUp) add(CustomAccessibilityAction(moveUp) { onMoveUp(); true })
                        if (canMoveDown) add(CustomAccessibilityAction(moveDown) { onMoveDown(); true })
                    }
                }
                .padding(start = SettingsRowPadH, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mark != null) {
                Text(
                    text = mark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (problem != null) {
                Text(text = problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (row.canSwitch) {
            StashSwitch(
                checked = row.on,
                onCheckedChange = onSwitched,
                modifier = Modifier.semantics { contentDescription = switchDesc },
            )
        } else if (row.status == AddonOverview.Status.TURNED_OFF_BY_STASH && row.canRemove) {
            TextButton(
                onClick = onRemove,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.semantics { contentDescription = removeDesc },
            ) {
                Text(stringResource(R.string.settings_addons_remove))
            }
        }
        if (handleModifier != null) {
            Box(modifier = Modifier.size(HandleSize).testTag(ADDON_DRAG_HANDLE_TAG).then(handleModifier), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = null, // TalkBack moves the row with its Move up / Move down actions
                    tint = if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** An official add-on not added yet: its name, its Official mark with the one line the signed list says about it, and Add. */
@Composable
private fun OfficialRow(entry: OfficialEntry, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onAdd)
            .padding(horizontal = SettingsRowPadH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AddonAvatar(entry.name, size = 32.dp)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = stringResource(R.string.settings_addons_official_summary, entry.summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_addons_add),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** The drag handle's touch target: the queue's. */
private val HandleSize = 48.dp

/** On each row's drag handle, so a test can count them: the handle says nothing to TalkBack (its row has the moves). */
internal const val ADDON_DRAG_HANDLE_TAG = "addon-drag-handle"

/** How long a just-added add-on's row stays tinted. */
private const val HIGHLIGHT_MS = 2_500L
