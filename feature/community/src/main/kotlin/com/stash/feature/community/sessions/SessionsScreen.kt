package com.stash.feature.community.sessions

import com.stash.core.model.listen.SessionRecap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.community.LiveSession
import com.stash.core.model.community.Station
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
import com.stash.feature.community.rankWidthFor
import com.stash.feature.community.sessionsNoteText

/**
 * Sessions (UX spec 2026-11-20 §3, the Sessions mockup): every station on the air. The live ones you follow come
 * first, then everyone else as the Worker ranks them (votes plus listeners). A row is the join, as Home's cards are.
 * The top bar has Go live (it reads "On the air" and opens the booth while this phone is) and, once you have a
 * station, its face: Your station. Pull to refresh. Upcoming waits for step 6.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onGoLive: () -> Unit,
    onYourStation: () -> Unit,
    /** Opens a kept recap again. */
    onOpenRecap: (SessionRecap) -> Unit = {},
    viewModel: SessionsViewModel = hiltViewModel(),
    recent: RecentRecapsViewModel = hiltViewModel(),
    upcomingModel: UpcomingViewModel = hiltViewModel(),
    onOpenStation: (String) -> Unit = {},
) {
    val upcoming by upcomingModel.upcoming.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { upcomingModel.load(); onPauseOrDispose {} }
    val recaps by recent.recaps.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { viewModel.onShown(all = true); onPauseOrDispose {} }
    CommunitySnackbar(state.message, viewModel::messageShown)
    CommunitySnackbar(sessionsNoteText(state.note), viewModel::noteShown)
    FirstTuneIn(state.naming, onTuneIn = viewModel::confirmName, onDismiss = viewModel::cancelName)
    val ui = sessionsList(state)
    val onAir = (room as? ListenTogetherState.InRoom)?.let { it.session != null && it.isHost } == true

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.community_back))
            }
            Text(
                stringResource(R.string.sessions_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (ui != SessionsListUi.Off) {
                GoLivePill(onAir = onAir, onClick = if (onAir) onOpenPlayer else onGoLive)
                state.mine?.let { mine ->
                    Spacer(Modifier.width(4.dp))
                    YourStationFace(mine, onClick = onYourStation)
                }
            }
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomChromePadding(120.dp))) {
                val gutter = Modifier.padding(horizontal = 20.dp)
                when (ui) {
                    SessionsListUi.Loading -> items(SKELETON_ROWS) { RowSkeleton() }
                    SessionsListUi.Failed -> item(key = "error") { ErrorCard(onRetry = viewModel::retry, modifier = gutter) }
                    SessionsListUi.Off -> item(key = "off") {
                        StateCard(
                            icon = Icons.Rounded.CloudOff,
                            title = stringResource(R.string.sessions_off_title),
                            body = stringResource(R.string.sessions_off_body),
                            action = null,
                            onAction = null,
                            modifier = gutter,
                        )
                    }
                    SessionsListUi.Empty -> item(key = "empty") {
                        StateCard(
                            icon = Icons.Rounded.Podcasts,
                            title = stringResource(R.string.sessions_nobody_live),
                            body = stringResource(R.string.sessions_empty_body),
                            action = stringResource(if (onAir) R.string.sessions_on_air else R.string.sessions_go_live),
                            onAction = if (onAir) onOpenPlayer else onGoLive,
                            modifier = gutter,
                        )
                    }
                    is SessionsListUi.Ready -> {
                        if (ui.following.isNotEmpty()) {
                            item(key = "following_label") { ListLabel(stringResource(R.string.sessions_you_follow)) }
                            items(ui.following, key = { "f" + it.station.id }) { session ->
                                SessionRow(
                                    session = session,
                                    rank = null,
                                    rankWidth = 0.dp,
                                    tuning = state.tuningIn == session.station.id,
                                    onClick = { viewModel.tuneIn(session, onOpenPlayer) },
                                )
                            }
                        }
                        if (ui.everyone.isNotEmpty()) {
                            item(key = "everyone_label") {
                                // With none you follow on the air, the one list is simply who's live.
                                ListLabel(stringResource(if (ui.following.isEmpty()) R.string.sessions_live_now else R.string.sessions_everyone))
                            }
                            items(ui.everyone, key = { "e" + it.session.station.id }) { ranked ->
                                SessionRow(
                                    session = ranked.session,
                                    rank = ranked.rank,
                                    rankWidth = rankWidthFor(ui.everyone.last().rank),
                                    tuning = state.tuningIn == ranked.session.station.id,
                                    onClick = { viewModel.tuneIn(ranked.session, onOpenPlayer) },
                                )
                            }
                        }
                    }
                }
                // Upcoming: stations that go live on their own within a day.
                if (upcoming.isNotEmpty() && ui != SessionsListUi.Loading && ui != SessionsListUi.Off) {
                    item(key = "upcoming_label") { ListLabel(stringResource(R.string.sessions_upcoming)) }
                    items(upcoming, key = { "u" + it.id }) { station -> UpcomingRow(station, onClick = { onOpenStation(station.id) }) }
                }
                // Recent: the recaps this phone kept (a week), to open again. Not while Sessions is off or loading.
                if (recaps.isNotEmpty() && ui != SessionsListUi.Loading && ui != SessionsListUi.Off) {
                    item(key = "recent_label") { ListLabel(stringResource(R.string.sessions_recent)) }
                    items(recaps, key = { "r" + it.id }) { recap -> RecentRow(recap, onClick = { onOpenRecap(recap) }) }
                }
            }
        }
    }
}

/** A station that goes live on its own soon: its name, and when. */
@Composable
private fun UpcomingRow(station: Station, onClick: () -> Unit) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val locale = config.locales[0] ?: java.util.Locale.getDefault()
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(station.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(
            station.next?.let { stringResource(R.string.sessions_upcoming_at, nextOnText(it.start, java.time.ZoneId.systemDefault(), locale)) }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** One kept recap: the station (or "Your session"), and when it ended. */
@Composable
private fun RecentRow(recap: SessionRecap, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val title = when {
        recap.wasHost -> stringResource(R.string.sessions_recent_yours)
        else -> recap.stationName ?: recap.hostName ?: stringResource(R.string.sessions_recent_session)
    }
    val whenEnded = android.text.format.DateUtils.getRelativeTimeSpanString(
        recap.endedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.sessions_recent_songs, recap.songs, recap.songs, whenEnded),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ListLabel(text: String) {
    RowLabel(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp).semantics { heading() })
}

/**
 * ● Go live, shaped as Community's Post pill. While this phone is on the air it reads "On the air" in the live red and
 * opens the booth instead.
 */
@Composable
private fun GoLivePill(onAir: Boolean, onClick: () -> Unit) {
    val ext = StashTheme.extendedColors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.heightIn(min = MinTouch).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(ext.glassBackground)
                .border(1.dp, ext.glassBorderBright, CircleShape)
                .indication(interaction, ripple())
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(7.dp, pulsing = onAir)
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(if (onAir) R.string.sessions_on_air else R.string.sessions_go_live),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (onAir) liveInk() else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** Your own station's face (its colour and letter, as on its round card): opens Your station. */
@Composable
private fun YourStationFace(station: Station, onClick: () -> Unit) {
    val label = stringResource(R.string.sessions_your_station)
    Box(
        Modifier.size(MinTouch).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(personColor(station.id)), contentAlignment = Alignment.Center) {
            Text(
                station.initial(),
                color = FaceInk,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}

/**
 * One session on the air: its place ([rank], among everyone else's) or, on one you follow, the live dot on the
 * cover; the station, its vibe line, the first faces and how many are listening; the score. A full one says Full
 * and takes no tap.
 */
@Composable
private fun SessionRow(session: LiveSession, rank: Int?, rankWidth: androidx.compose.ui.unit.Dp, tuning: Boolean, onClick: () -> Unit) {
    val station = session.station
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (session.full) 0.6f else 1f)
            .clickable(enabled = !session.full, role = Role.Button, onClickLabel = stringResource(R.string.sessions_tune_in), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rank != null) {
            Text(
                "$rank",
                style = MaterialTheme.typography.titleMedium,
                color = muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(rankWidth),
            )
        }
        Box(Modifier.size(RowArtSize)) {
            CoverArt(listOfNotNull(session.track?.artUrl), Modifier.fillMaxSize(), corner = 12.dp)
            // A followed one's list has no ranks: the dot says it's on the air.
            if (rank == null) {
                Box(Modifier.padding(5.dp).size(12.dp).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) {
                    LiveDot(8.dp)
                }
            }
            if (tuning) {
                Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The vibe line, or else the song: a station that hasn't written one still says something.
            val second = station.vibe?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.sessions_vibe, it) } ?: session.track?.nowLine()
            if (second != null) {
                Text(
                    second,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.crowd.isNotEmpty()) {
                    Faces(session.crowd, size = 18.dp, modifier = Modifier.clearAndSetSemantics { })
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    when {
                        session.full -> stringResource(R.string.sessions_full)
                        session.listeners <= 0 -> stringResource(R.string.sessions_just_host)
                        else -> pluralStringResource(R.plurals.sessions_listening, session.listeners, session.listeners)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Score(station.score)
    }
}

private val RowArtSize = 56.dp

/** Skeleton rows while the list loads: about a screenful. */
private const val SKELETON_ROWS = 6
