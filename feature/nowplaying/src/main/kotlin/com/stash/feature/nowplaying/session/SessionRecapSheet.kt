package com.stash.feature.nowplaying.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.listen.SessionRecapRecorder
import com.stash.core.model.listen.RecapSong
import com.stash.core.model.listen.SessionRecap
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The screens' door to the recap recorder: the recap of a session that just ended, and what the room tells it. */
@HiltViewModel
class SessionRecapViewModel @Inject constructor(private val recorder: SessionRecapRecorder) : ViewModel() {
    val ended: StateFlow<SessionRecap?> = recorder.ended
    fun shown() = recorder.shown()
    fun show(recap: SessionRecap) = recorder.show(recap)
    fun saved() = recorder.saved()
    fun noteStation(id: String, name: String) = recorder.noteStation(id, name)
}

/** A recap's length in whole minutes, as its two numbers: hours and minutes. */
internal fun recapDuration(ms: Long): Pair<Int, Int> {
    val minutes = (ms / 60_000L).toInt().coerceAtLeast(0)
    return minutes / 60 to minutes % 60
}

/** The recap of a session that ended while this phone was in it. One for the app, in the scaffold. */
@Composable
fun SessionRecapHost(viewModel: SessionRecapViewModel = hiltViewModel()) {
    val recap by viewModel.ended.collectAsStateWithLifecycle()
    recap?.let { RecapSheet(it, onDismiss = viewModel::shown) }
}

@Composable
private fun durationText(ms: Long): String {
    val (h, m) = recapDuration(ms)
    return if (h > 0) stringResource(R.string.recap_hours_minutes, h, m) else pluralStringResource(R.plurals.recap_minutes, m, m)
}

@Composable
private fun Fact(number: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(number, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun SongList(heading: String, songs: List<RecapSong>, detail: @Composable (RecapSong) -> String?) {
    if (songs.isEmpty()) return
    Text(
        heading,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() },
    )
    songs.forEach { song ->
        val extra = detail(song)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (extra != null) Text(extra, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

/**
 * The recap (mockups: Recap, Host recap). A listener's: time listened, songs, people, their picks and how the room
 * took them, crowd favourites, what they saved. A host's: time on the air, people and the most at once, regulars,
 * requests played, favourites, the most saved song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecapSheet(recap: SessionRecap, onDismiss: () -> Unit) {
    val ground = StashTheme.extendedColors.elevatedSurface
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ground,
        properties = com.stash.core.ui.components.sheetBarsFollow(ground),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            val station = recap.stationName ?: stringResource(R.string.room_station)
            Text(
                if (recap.wasHost) stringResource(R.string.recap_title_host) else stringResource(R.string.recap_title, station),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (recap.wasHost) station
                else recap.hostName?.let { stringResource(R.string.recap_with, it) } ?: stringResource(R.string.session_ended_plain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact(durationText(recap.listenedMs), stringResource(if (recap.wasHost) R.string.recap_on_air else R.string.recap_listened), Modifier.weight(1f))
                Fact(recap.songs.toString(), pluralStringResource(R.plurals.recap_songs, recap.songs), Modifier.weight(1f))
                Fact(recap.people.toString(), pluralStringResource(R.plurals.recap_people, recap.people), Modifier.weight(1f))
            }
            if (recap.wasHost) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fact(recap.peak.toString(), stringResource(R.string.recap_peak), Modifier.weight(1f))
                    Fact(recap.requestsPlayed.toString(), pluralStringResource(R.plurals.recap_requests, recap.requestsPlayed), Modifier.weight(1f))
                    Fact(recap.regulars.toString(), pluralStringResource(R.plurals.recap_regulars, recap.regulars), Modifier.weight(1f))
                }
                val gained = recap.newFollowers ?: 0
                if (gained > 0) {
                    Text(
                        pluralStringResource(R.plurals.recap_new_followers, gained, gained),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                recap.mostSaved?.let { song ->
                    SongList(stringResource(R.string.recap_most_saved), listOf(song)) { pluralStringResource(R.plurals.life_saved, it.saves, it.saves) }
                }
            } else {
                SongList(stringResource(R.string.recap_your_picks), recap.myPicks) { song ->
                    if (song.reactions > 0) pluralStringResource(R.plurals.recap_reacted, song.reactions, song.reactions) else null
                }
            }
            SongList(stringResource(R.string.recap_favourites), recap.favourites) { null }
            if (!recap.wasHost) SongList(stringResource(R.string.recap_saved), recap.saved) { null }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.recap_done)) }
        }
    }
}
