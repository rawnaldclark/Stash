package com.stash.feature.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.SectionHeader
import com.stash.feature.community.sessions.RowLabel
import com.stash.feature.community.sessions.SessionsRows
import com.stash.feature.community.sessions.SessionsViewModel
import com.stash.feature.community.sessions.sessionsRows

/**
 * Home's Community section (spec 2026-09-26 §3, redrawn 2026-11-01): Home's own section header with See all, the
 * chart's #1 as a hero (this week's top post by votes, see topChart), then #2–#5 as a rail of art cards ending in a
 * "Share yours" card (the picker). Skeletons while the first list loads (the Share card already there), a card with
 * Retry and "Share something" when it can't, "Be the first" when there's nothing: there's always a way to post.
 * Composed only while Community is on, so nothing here runs, or asks the Worker anything, while it's off.
 *
 * Sessions (UX spec 2026-11-20 §3), once the Worker says it's on: Your stations and Live now sit above the posts,
 * which then get a small label of their own. Until then nothing about Sessions shows and this is the section it was.
 * [onOpenPlayer] opens the player on the room after a card's tune-in; [onOpenSessions] is Live now's way to every
 * session; [onOpenStation] opens a followed station that isn't on the air.
 */
@Composable
fun CommunitySection(
    onOpenPost: (String) -> Unit,
    onSeeAll: () -> Unit,
    onPost: () -> Unit,
    onOpenPlayer: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
    onOpenStation: (String) -> Unit = {},
    viewModel: CommunityViewModel = hiltViewModel(),
    sessions: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sessionsState by sessions.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Each time Home resumes (first open, back from another screen or from the background) the ages catch up and
    // the lists reload; the ViewModels throttle the reloads.
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        viewModel.onShown()
        sessions.onShown()
        onPauseOrDispose {}
    }
    CommunitySnackbar(state.message, viewModel::messageShown)
    CommunitySnackbar(voteNoteText(state.voteNote), viewModel::voteNoteShown)
    CommunitySnackbar(sessionsState.message, sessions::messageShown)
    CommunitySnackbar(sessionsNoteText(sessionsState.note), sessions::noteShown)
    val gutter = Modifier.padding(horizontal = 20.dp)
    Column(Modifier.fillMaxWidth()) {
        // Home's rail rhythm (CardRail): 16 dp, then the header.
        Spacer(Modifier.height(16.dp))
        SectionHeader(
            title = stringResource(R.string.community_title),
            actionText = stringResource(R.string.community_see_all),
            onActionClick = onSeeAll,
        )
        val ui = listUi(state, segment = null, now = now)
        sessionsRows(sessionsState, SessionsViewModel.HOME_LIVE)?.let { rows ->
            SessionsRows(
                rows = rows,
                tuningIn = sessionsState.tuningIn,
                now = now,
                onTuneIn = { sessions.tuneIn(it, onOpenPlayer) },
                // One on the air is the join; one that isn't opens its page (when it's next on, Unfollow).
                onStation = { station -> if (station.live != null) sessions.tuneIn(station, onOpenPlayer) else onOpenStation(station.id) },
                onAll = onOpenSessions,
            )
            // The posts under the rooms say what they are: until now the section's own header did.
            RowLabel(
                stringResource(if (ui is ListUi.Ready && !ui.thisWeek) R.string.sessions_posts_label_all else R.string.sessions_posts_label),
                Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
            )
        }
        when (ui) {
            ListUi.Loading -> {
                HeroSkeleton(gutter)
                Spacer(Modifier.height(16.dp))
                RailSkeleton(onPost)
            }
            ListUi.Failed -> ErrorCard(onRetry = { viewModel.show(CommunityViewModel.Tab.HOME) }, modifier = gutter, onPost = onPost)
            ListUi.Empty -> EmptyCard(segment = null, onPost = onPost, modifier = gutter)
            is ListUi.Ready -> {
                ui.hero?.let { hero ->
                    CommunityHero(
                        post = hero,
                        now = now,
                        thisWeek = ui.thisWeek,
                        onOpen = { onOpenPost(hero.id) },
                        onVote = { viewModel.vote(hero, it) },
                        modifier = gutter,
                    )
                }
                Spacer(Modifier.height(16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ui.rows, key = { it.post.id }) { r ->
                        CommunityRailCard(
                            rank = r.rank ?: 0,
                            post = r.post,
                            onOpen = { onOpenPost(r.post.id) },
                            onVote = { viewModel.vote(r.post, it) },
                        )
                    }
                    item(key = "share") { ShareCard(onPost) }
                }
            }
        }
    }
}

/** What a Sessions note says. */
@Composable
internal fun sessionsNoteText(note: SessionsViewModel.Note?): String? = when (note) {
    SessionsViewModel.Note.END_YOURS_FIRST -> stringResource(R.string.sessions_end_yours_first)
    null -> null
}
