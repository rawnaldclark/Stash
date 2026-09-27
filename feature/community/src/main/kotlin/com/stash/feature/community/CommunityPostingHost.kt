package com.stash.feature.community

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.model.community.PostTarget
import com.stash.core.ui.components.LocalPostToCommunity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Which posting sheet is open (spec 2026-09-26 §3 Posting). The app's nav host holds one for every screen. */
@Stable
class CommunityPosting {
    internal var picking by mutableStateOf(false)
    internal var target by mutableStateOf<PostTarget?>(null)

    /** Opens the confirm sheet for a song or playlist: what LocalPostToCommunity hands out. */
    val post: (PostTarget) -> Unit = { picking = false; target = it }

    /** The section's `+` and See all's `+ Post`: opens the picker. */
    val pick: () -> Unit = { target = null; picking = true }
}

@Composable
fun rememberCommunityPosting(): CommunityPosting =
    // ponytail: remember, not rememberSaveable. PostTarget.Song holds a Track, which isn't Parcelable, so a rotation closes
    // the sheet like the app's track sheets. A post in flight still finishes and toasts; a refusal then shows only on a
    // sheet reopened before it lands, and such a reopen (within the 15 s timeout) could post twice. Add a Saver if
    // rotation mid-post ever matters.
    remember { CommunityPosting() }

/**
 * Is Community on: all the host reads while it's off, so the app makes no Community requests (spec §3).
 * [on] is null until the switch is read.
 */
@HiltViewModel
class CommunityHostViewModel @Inject constructor(homeSections: HomeSectionsPreference) : ViewModel() {
    val on: StateFlow<Boolean?> = homeSections.communityOn.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * Provides [LocalPostToCommunity] to [content] while Community is on, and hosts the picker and the confirm
 * sheet over it. Until the switch is read it counts as off. [onSeeMyPosts] opens See all ▸ Mine (the
 * live_limit message's button).
 */
@Composable
fun CommunityPostingHost(
    posting: CommunityPosting,
    onSeeMyPosts: () -> Unit,
    viewModel: CommunityHostViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val on = viewModel.on.collectAsStateWithLifecycle().value == true
    CompositionLocalProvider(LocalPostToCommunity provides posting.post.takeIf { on }) { content() }
    if (!on) return
    if (posting.picking) CommunityPicker(onPick = posting.post, onDismiss = { posting.picking = false })
    posting.target?.let { target ->
        PostConfirmSheet(
            target = target,
            onDismiss = { posting.target = null },
            onSeeMyPosts = { posting.target = null; onSeeMyPosts() },
        )
    }
}
