package com.stash.feature.nowplaying.session

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.community.Station
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R

/**
 * Hosted once for the whole app, beside the player: the room's lines (why a follow or a vote didn't go) as toasts,
 * and the sheet after leaving a session. It shares [SessionRoomViewModel] with the room and the mini player's
 * close: they all sit under the activity's own ViewModel store.
 */
@Composable
fun SessionLeaveHost(viewModel: SessionRoomViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val note = when (state.note) {
        SessionRoomViewModel.Note.LISTEN_FIRST -> stringResource(R.string.room_vote_listen_first)
        SessionRoomViewModel.Note.TRY_AGAIN -> stringResource(R.string.room_vote_try_again)
        null -> null
    }
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.messageShown() }
    }
    LaunchedEffect(note) {
        note?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.noteShown() }
    }
    state.parting?.let { station -> LeaveSheet(station, viewModel) }
}

/**
 * After leaving a session you stayed in for 2 minutes or more (UX spec 2026-11-20 §4.6), once for that session: how
 * was the station (its vote), and Follow if you don't already. One tap closes it; nothing here is required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveSheet(station: Station, viewModel: SessionRoomViewModel) {
    val ground = StashTheme.extendedColors.elevatedSurface
    ModalBottomSheet(
        onDismissRequest = viewModel::partingDone,
        containerColor = ground,
        properties = com.stash.core.ui.components.sheetBarsFollow(ground),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.room_parting_title, station.name),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            StationVote(viewModel)
            Spacer(Modifier.height(16.dp))
            // Follow, for a station you don't follow yet; once followed it says so and can be undone.
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (station.following) 0.14f else 0.22f), CircleShape)
                    .clickable(role = Role.Button, onClick = viewModel::follow)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (station.following) R.string.room_following else R.string.room_parting_follow),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (station.following) 0.7f else 1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = viewModel::partingDone) { Text(stringResource(R.string.room_parting_done)) }
        }
    }
}
