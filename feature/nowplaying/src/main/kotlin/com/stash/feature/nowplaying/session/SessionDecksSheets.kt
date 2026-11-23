package com.stash.feature.nowplaying.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.personColor
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R

/** Where this phone stands on the decks, in words; null when it isn't on them or in the line. */
@Composable
internal fun deckStanding(ui: DecksUi): String? = when (ui.place) {
    DeckPlace.ON_DECKS -> when {
        ui.turnsAway == 0 -> stringResource(R.string.decks_your_song)
        !ui.myReady -> stringResource(R.string.decks_pick_needed)
        ui.turnsAway == 1 -> stringResource(R.string.decks_up_next)
        else -> pluralStringResource(R.plurals.decks_turn_in, ui.turnsAway ?: 0, ui.turnsAway ?: 0)
    }
    DeckPlace.IN_LINE -> stringResource(R.string.decks_in_line, ui.linePlace)
    DeckPlace.OFF, DeckPlace.HOST -> null
}

/**
 * The decks card (mockup: Decks), under the crowd while the decks are open: who is on, in the order of their turns,
 * with NOW and NEXT; then the open seats and the line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DecksCard(ui: DecksUi, ink: Color, muted: Color, modifier: Modifier = Modifier) {
    val ext = StashTheme.extendedColors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(ext.glassBackground).border(1.dp, ext.glassBorder, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.decks_title),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = muted,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.seats.forEach { seat ->
                val name = when {
                    seat.me -> stringResource(R.string.room_you)
                    else -> seat.name ?: stringResource(R.string.listen_someone)
                }
                val tag = when {
                    seat.now -> stringResource(R.string.decks_now)
                    seat.next -> stringResource(R.string.decks_next)
                    else -> null
                }
                Row(
                    Modifier
                        .clip(CircleShape)
                        .border(1.dp, if (seat.now) personColor(seat.id) else ink.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = if (seat.ready) 1f else 0.55f), maxLines = 1)
                    // The round starts with the host: said in words, as the crowd band says it.
                    if (seat.host) Text(stringResource(R.string.room_host), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                    if (tag != null) {
                        Text(tag, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = personColor(seat.id), maxLines = 1)
                    }
                }
            }
        }
        val spots = if (ui.openSeats > 0) pluralStringResource(R.plurals.decks_open_seats, ui.openSeats, ui.openSeats) else stringResource(R.string.decks_full)
        Text(
            if (ui.waiting > 0) spots + " · " + pluralStringResource(R.plurals.decks_waiting, ui.waiting, ui.waiting) else spots,
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

/**
 * A listener's decks sheet: join with a song (or the line, when the seats are taken), see where they stand, change
 * their ready song, and step down. The songs are this phone's library, as in Request a song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeckSheet(ui: DecksUi, viewModel: SessionDjViewModel, onDismiss: () -> Unit) {
    val ground = StashTheme.extendedColors.elevatedSurface
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val seated = ui.place == DeckPlace.ON_DECKS || ui.place == DeckPlace.IN_LINE
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ground,
        properties = com.stash.core.ui.components.sheetBarsFollow(ground),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(if (seated) R.string.decks_your_pick else R.string.decks_join),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    deckStanding(ui) ?: stringResource(if (ui.openSeats > 0) R.string.decks_join_body else R.string.decks_join_body_line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.dj_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(results, key = { it.id }) { track ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        stringResource(if (seated) R.string.decks_pick_this else R.string.decks_join_with),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(role = Role.Button) { viewModel.deckPick(track); onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            item {
                Column(Modifier.padding(bottom = 20.dp)) {
                    Text(stringResource(R.string.decks_foot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (seated) {
                        TextButton(onClick = { viewModel.leaveDecks(); onDismiss() }) {
                            Text(stringResource(if (ui.place == DeckPlace.IN_LINE) R.string.decks_leave_line else R.string.decks_leave), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
