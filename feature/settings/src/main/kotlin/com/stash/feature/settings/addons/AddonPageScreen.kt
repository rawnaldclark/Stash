package com.stash.feature.settings.addons

import android.icu.text.MessageFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.StashSwitch
import com.stash.data.download.lossless.addon.AddonOverview
import com.stash.feature.settings.LosslessQuotaViewModel
import com.stash.feature.settings.R
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel

/**
 * One add-on's page (add-ons plan phase 4 §2.9), and the Tidal row's (§2.10): who made it and where
 * it runs, its switch, how it is doing in plain words, what Stash uses from it, what its server can
 * see, where it is asked in the play order, its allowance, and for an add-on the user added,
 * Replace link and Remove. Remove always asks first.
 *
 * The page closes by itself when its add-on is gone (removed here, or its row no longer exists).
 */
@Composable
fun AddonPageScreen(
    /** The row's key in the play order: `tidal`, or `addon:<id>`. */
    addonKey: String,
    onBack: () -> Unit,
    /** Opens Add from a link to replace the link of the add-on with this handle. */
    onReplaceLink: (entryKey: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddonPageViewModel = hiltViewModel(),
    quotaViewModel: LosslessQuotaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val tidalToday by quotaViewModel.line.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    SaveFailedToast(saveFailed)
    val row = state.row(addonKey)
    // Gone (removed, or it never was): back to the list. Not before the rows have been read once.
    LaunchedEffect(state.loaded, row == null) {
        if (state.loaded && row == null) onBack()
    }
    if (row == null) {
        SettingsScaffold(title = stringResource(R.string.settings_addons_title), onBack = onBack, modifier = modifier) {}
        return
    }
    LaunchedEffect(row.key, row.status) { viewModel.look(row) }
    val place = state.order.indexOfFirst { it.key == row.key }
    AddonPageContent(
        row = row,
        place = place,
        places = state.order.size,
        live = live,
        tidalToday = tidalToday.takeIf { row.kind == AddonOverview.Kind.TIDAL && !row.ownServer },
        onBack = onBack,
        onSwitched = { viewModel.onSwitched(row, it) },
        onNudged = { up -> viewModel.onNudged(row, up) },
        onReplaceLink = { row.entryKey?.let(onReplaceLink) },
        onRemove = { viewModel.onRemove(row) },
        onAgreed = { viewModel.onAgreed(row) },
        modifier = modifier,
    )
}

/** [AddonPageScreen] without its view models, for tests. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddonPageContent(
    row: AddonOverview.Row,
    /** Its index in the play order; negative when it has no place in it. */
    place: Int,
    places: Int,
    live: AddonPageViewModel.Live,
    /** Tidal's daily allowance line, for Stash's own Tidal. */
    tidalToday: String?,
    onBack: () -> Unit,
    onSwitched: (Boolean) -> Unit,
    onNudged: (up: Boolean) -> Unit,
    onReplaceLink: () -> Unit,
    onRemove: () -> Unit,
    onAgreed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = row.title()
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(title = title, onBack = onBack, modifier = modifier) {
        // Who it is, and its switch.
        SettingsGroupCard(
            rows = listOfNotNull<@Composable () -> Unit>(
                {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AddonAvatar(title)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            row.version?.let {
                                Text(
                                    text = stringResource(R.string.settings_addons_version, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            row.markLine()?.let {
                                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (row.canSwitch) {
                            val switchDesc = stringResource(R.string.settings_addons_switch_desc, title)
                            StashSwitch(
                                checked = row.on,
                                onCheckedChange = onSwitched,
                                modifier = Modifier.semantics { contentDescription = switchDesc },
                            )
                        }
                    }
                },
                { StatusRow(row, live, onReview = { review = true }) },
            ),
        )

        // What it does.
        val about = when {
            row.kind == AddonOverview.Kind.TIDAL && row.ownServer -> stringResource(R.string.settings_addons_tidal_own_desc)
            row.kind == AddonOverview.Kind.TIDAL -> stringResource(R.string.settings_addons_tidal_stash_desc)
            row.kind == AddonOverview.Kind.QOBUZ -> stringResource(R.string.settings_addons_qobuz_desc)
            else -> row.summary
        }
        if (row.uses.isNotEmpty() || about != null) {
            SettingsSectionLabel(stringResource(R.string.settings_addons_page_does))
            SettingsGroupCard(
                rows = listOf(
                    {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            about?.let {
                                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                            if (row.uses.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.uses.forEach { UsePill(stringResource(useText(it))) }
                                }
                            }
                            if (row.ownServer) {
                                row.host?.let { InfoLine(stringResource(R.string.settings_addons_tidal_own_server), it) }
                                if (live.ownTokenSaved) {
                                    Text(
                                        text = stringResource(R.string.settings_addons_tidal_own_token),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                ),
            )
        }

        // What it can see: the sheet's own sentences, for as long as it is added.
        if (row.kind == AddonOverview.Kind.ADDON && (row.sees.isNotEmpty() || row.mark == AddonOverview.Mark.NOT_MADE_BY_STASH)) {
            SettingsSectionLabel(stringResource(R.string.settings_addons_page_sees))
            SettingsGroupCard(
                rows = listOf(
                    {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_addons_sheet_sees_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(seesText(row.sees)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.settings_addons_sheet_never_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_addons_sheet_never),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ),
            )
        }

        // Where it is asked. Alone in the order there is no first or last to speak of, and nowhere to move.
        if (place >= 0 && places > 1) {
            SettingsSectionLabel(stringResource(R.string.settings_addons_page_order))
            SettingsGroupCard(
                rows = listOf(
                    {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = SettingsRowPadH, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = MessageFormat.format(stringResource(R.string.settings_addons_page_asked), place + 1, places),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onNudged(true) }, enabled = place > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.settings_addons_page_move_up))
                            }
                            TextButton(onClick = { onNudged(false) }, enabled = place < places - 1, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.settings_addons_page_move_down))
                            }
                        }
                    },
                ),
            )
        }

        // Its allowance: Tidal's daily line, or what a metered add-on says.
        val allowance = tidalToday ?: live.quota?.let { q ->
            val limit = q.limit
            if (limit != null && limit > 0) stringResource(R.string.settings_addons_page_used_of, q.used, limit)
            else stringResource(R.string.settings_addons_page_used, q.used)
        }
        if (allowance != null) {
            SettingsSectionLabel(stringResource(R.string.settings_addons_page_allowance))
            SettingsGroupCard(
                rows = listOf(
                    {
                        Text(
                            text = allowance,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
                        )
                    },
                ),
            )
        }

        // Replace link and Remove, for what the user added; "Use Stash's Tidal instead" for their own server.
        val actions = buildList<@Composable () -> Unit> {
            if (row.userAdded) {
                add { SettingsNavRow(title = stringResource(R.string.settings_addons_replace_link), onClick = onReplaceLink) }
            }
            if (row.canRemove) {
                add {
                    ActionRow(
                        text = stringResource(if (row.ownServer) R.string.settings_addons_tidal_use_stash else R.string.settings_addons_remove),
                        color = MaterialTheme.colorScheme.error,
                        onClick = { confirmRemove = true },
                    )
                }
            }
        }
        if (actions.isNotEmpty()) {
            Spacer(modifier = Modifier.size(2.dp))
            SettingsGroupCard(rows = actions)
        }
    }

    if (confirmRemove) {
        RemoveAddonDialog(
            row = row,
            title = title,
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }

    if (review) {
        NotMadeByStashSheet(
            name = title,
            host = row.host.orEmpty(),
            sees = row.sees,
            more = true,
            onConfirm = {
                review = false
                onAgreed()
            },
            onDismiss = { review = false },
        )
    }
}

/** How the add-on is doing, in plain words (§2.9), with what to do about it when there is something to do. */
@Composable
private fun StatusRow(row: AddonOverview.Row, live: AddonPageViewModel.Live, onReview: () -> Unit) {
    val (headline, detail) = when (row.status) {
        AddonOverview.Status.OFF -> stringResource(R.string.settings_addons_status_off) to null
        AddonOverview.Status.TURNED_OFF_BY_STASH -> stringResource(blockedText(row.blockReason)) to null
        AddonOverview.Status.WANTS_MORE ->
            stringResource(R.string.settings_addons_status_wants_more) to stringResource(R.string.settings_addons_status_wants_more_detail)
        AddonOverview.Status.NEEDS_NEW_LINK ->
            stringResource(R.string.settings_addons_status_new_link) to stringResource(R.string.settings_addons_status_new_link_detail)
        AddonOverview.Status.NEEDS_NEWER_STASH ->
            stringResource(R.string.settings_addons_status_newer) to stringResource(R.string.settings_addons_status_newer_detail)
        AddonOverview.Status.ONLY_BITCHORD ->
            stringResource(R.string.settings_addons_refused_only_bitchord) to stringResource(R.string.settings_addons_status_only_bitchord_detail)
        AddonOverview.Status.CHANGED ->
            stringResource(R.string.settings_addons_status_changed) to stringResource(R.string.settings_addons_status_changed_detail)
        AddonOverview.Status.REPLACED ->
            stringResource(R.string.settings_addons_status_replaced) to stringResource(R.string.settings_addons_status_replaced_detail)
        AddonOverview.Status.NOT_REACHABLE ->
            stringResource(R.string.settings_addons_not_loaded) to stringResource(R.string.settings_addons_status_unreachable_detail)
        // On, with a manifest, but not being asked right now: say so, and why.
        AddonOverview.Status.WORKING -> if (live.paused) {
            stringResource(R.string.settings_addons_status_paused) to stringResource(R.string.settings_addons_status_paused_detail)
        } else {
            stringResource(R.string.settings_addons_status_working) to null
        }
    }
    val wrong = row.status != AddonOverview.Status.WORKING && row.status != AddonOverview.Status.OFF
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyMedium,
                color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            detail?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (row.status == AddonOverview.Status.WANTS_MORE) {
            TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_addons_page_review))
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A row that does something final (Remove): its words in [color], no chevron. */
@Composable
private fun ActionRow(text: String, color: Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
    }
}
