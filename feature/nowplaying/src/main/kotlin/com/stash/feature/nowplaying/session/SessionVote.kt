package com.stash.feature.nowplaying.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.VotePill
import com.stash.feature.nowplaying.R

/**
 * A station's vote: Community's pill (▲ score ▼), on the station the room is in or, on the sheet after leaving, the
 * one just left. Until the station is heard (2 minutes in its sessions) the arrows that would cast a vote are dimmed
 * and "Listen 2 minutes to vote" sits under the pill; a tap on one says the same. Your own station's pill takes no
 * taps and shows the score alone.
 */
@Composable
internal fun StationVote(viewModel: SessionRoomViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val heardVersion by viewModel.heardVersion.collectAsStateWithLifecycle()
    val pill = remember(state.station, state.parting, heardVersion) { viewModel.votePill() } ?: return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VotePill(pill, onVote = viewModel::vote)
        if (pill.enabled && pill.listenFirst) {
            Text(
                stringResource(R.string.room_vote_listen_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
