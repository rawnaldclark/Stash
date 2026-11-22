package com.stash.feature.community.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.community.Station
import com.stash.core.model.community.StationLive
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.MinTouch
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.components.personColor
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.community.CommunitySnackbar
import com.stash.feature.community.CoverArt
import com.stash.feature.community.ErrorCard
import com.stash.feature.community.R
import com.stash.feature.community.RowSkeleton
import com.stash.feature.community.StateCard
import com.stash.feature.community.sessionsNoteText
import java.time.ZoneId

/**
 * A station's page (Sessions step 2): where its `stashfm.app/s/…` link lands. On the air: LIVE with how many are
 * listening and the song, and Tune in. Off the air: when it's next on, if it keeps hours. Either way its name,
 * host, vibe line, followers and score, and Follow. A station that's gone, hidden, or asked for while Sessions is
 * off says "This station isn't available". Your own opens Your station from here.
 */
@Composable
fun StationScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onYourStation: () -> Unit,
    viewModel: StationViewModel = hiltViewModel(),
    sessions: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val sessionsState by sessions.state.collectAsStateWithLifecycle()
    // Back from the room, or from the background: whether it's still on the air.
    LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose {} }
    CommunitySnackbar(message, viewModel::messageShown)
    CommunitySnackbar(sessionsNoteText(sessionsState.note), sessions::noteShown)
    FirstTuneIn(sessionsState.naming, onTuneIn = sessions::confirmName, onDismiss = sessions::cancelName)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.community_back))
            }
            Text(
                stringResource(R.string.station_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        val gutter = Modifier.padding(horizontal = 20.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = bottomChromePadding(120.dp))) {
            when (val s = state) {
                StationViewModel.UiState.Loading -> repeat(3) { RowSkeleton() }
                StationViewModel.UiState.Failed -> ErrorCard(onRetry = viewModel::load, modifier = gutter)
                StationViewModel.UiState.Unavailable -> StateCard(
                    icon = Icons.Rounded.CloudOff,
                    title = stringResource(R.string.station_unavailable),
                    body = stringResource(R.string.station_unavailable_body),
                    action = null,
                    onAction = null,
                    modifier = gutter,
                )
                is StationViewModel.UiState.Loaded -> StationPage(
                    station = s.station,
                    live = s.live,
                    tuning = sessionsState.tuningIn == s.station.id,
                    onTuneIn = { sessions.tuneIn(s.station.copy(live = s.live), onOpenPlayer) },
                    onFollow = viewModel::follow,
                    onYourStation = onYourStation,
                )
            }
        }
    }
}

@Composable
private fun StationPage(
    station: Station,
    live: StationLive?,
    tuning: Boolean,
    onTuneIn: () -> Unit,
    onFollow: () -> Unit,
    onYourStation: () -> Unit,
) {
    val ext = StashTheme.extendedColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = MaterialTheme.shapes.extraLarge
    val chrome = LocalAlbumChrome.current
    val config = LocalConfiguration.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).background(ext.glassBackground).border(1.dp, ext.glassBorder, shape).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(personColor(station.id)), contentAlignment = Alignment.Center) {
                    Text(station.initial(), color = FaceInk, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics { })
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        station.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 25.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    station.hostName?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(R.string.station_hosted_by, it), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            station.vibe?.takeIf { it.isNotBlank() }?.let { vibe ->
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.sessions_vibe, vibe), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.station_followers, station.followers, station.followers),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                Score(station.score)
            }
        }

        // On the air: what's playing and how many are there. Off the air: when it's next on, if it keeps hours.
        Row(
            Modifier.fillMaxWidth().clip(shape).background(ext.glassBackground).border(1.dp, ext.glassBorder, shape).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (live != null) {
                Box(Modifier.size(64.dp)) {
                    CoverArt(listOfNotNull(live.track?.artUrl), Modifier.fillMaxSize(), corner = 12.dp)
                    if (tuning) {
                        Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(8.dp)
                        Spacer(Modifier.width(7.dp))
                        RowLabel(stringResource(R.string.sessions_live_now), color = liveInk())
                    }
                    live.track?.let {
                        Text(it.nowLine(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        when {
                            live.full -> stringResource(R.string.sessions_full)
                            live.listeners <= 0 -> stringResource(R.string.sessions_just_host)
                            else -> pluralStringResource(R.plurals.sessions_listening, live.listeners, live.listeners)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            } else {
                val next = onAirLabel(station.next, System.currentTimeMillis(), ZoneId.systemDefault(), config.locales[0])
                Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.station_off_air), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        next?.let { stringResource(R.string.station_next_on, it) } ?: stringResource(R.string.station_off_air_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
        }

        if (live != null && !station.mine) {
            // Tune in: the join, as a Live now card's tap is. A full session waits.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(chrome.accent.copy(alpha = if (live.full) 0.4f else 1f))
                    .clickable(enabled = !live.full && !tuning, role = Role.Button, onClick = onTuneIn),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(if (live.full) R.string.sessions_full else R.string.sessions_tune_in),
                    style = MaterialTheme.typography.titleMedium,
                    color = chrome.onAccent,
                )
            }
        }
        // Follow; your own station opens its page instead.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (station.following) 0.14f else 0.22f), CircleShape)
                .clickable(role = Role.Button, onClick = if (station.mine) onYourStation else onFollow),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(
                    when {
                        station.mine -> R.string.sessions_your_station
                        station.following -> R.string.sessions_following
                        else -> R.string.station_follow
                    },
                ),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (station.following && !station.mine) 0.7f else 1f),
            )
        }
    }
}
