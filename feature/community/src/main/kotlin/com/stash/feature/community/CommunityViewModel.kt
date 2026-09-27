package com.stash.feature.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.communityMessage
import com.stash.core.model.community.CommunityPost
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** [this] as if [value] were now this phone's vote: the optimistic change (spec 2026-09-26 §3 Votes). */
internal fun CommunityPost.withVote(value: Int): CommunityPost = copy(
    up = up - (if (myVote == 1) 1 else 0) + (if (value == 1) 1 else 0),
    down = down - (if (myVote == -1) 1 else 0) + (if (value == -1) 1 else 0),
    myVote = value,
)

/** Tapping ▲ (1) or ▼ (-1). Tapping your current vote again takes it back (0). */
internal fun CommunityPost.nextVote(tapped: Int): Int = if (myVote == tapped) 0 else tapped

/** A list of posts with optimistic votes: Home's section (HOME) and See all (ALL, MINE). */
@HiltViewModel
class CommunityViewModel @Inject constructor(private val repository: CommunityRepository) : ViewModel() {
    enum class Tab { HOME, ALL, MINE }

    /** [posts] is null until something loads; [failed] means nothing loaded and the last try failed (Retry). */
    data class UiState(
        val tab: Tab = Tab.HOME,
        val posts: List<CommunityPost>? = null,
        val failed: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState(posts = repository.lastHome))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var loading: Job? = null
    private var loadedAt = 0L

    /** Post id → the vote last asked for, while a request for that post is out (main thread only). */
    private val voting = mutableMapOf<String, Int>()

    /** Test seam for the reload clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        // A post or a take-down (the posting sheet, a post screen) reloads whatever is on screen.
        viewModelScope.launch { repository.revision.drop(1).collect { show(_state.value.tab) } }
    }

    /**
     * Home calls this each time the section appears. It reloads unless it loaded in the last 30 seconds and no
     * vote was sent since (See all and the post screen keep their own lists). A clock set back counts as stale.
     */
    fun onShown() {
        if ((clock() - loadedAt) !in 0..30_000 || repository.lastVoteAt > loadedAt) show(Tab.HOME)
    }

    /** Loads [tab]. The list on screen stays until the new one arrives, unless one is Mine and the other isn't. */
    fun show(tab: Tab) {
        _state.update { s ->
            val sameKind = (tab == Tab.MINE) == (s.tab == Tab.MINE)
            s.copy(tab = tab, posts = if (sameKind) s.posts else null, failed = false)
        }
        loading?.cancel()
        loading = viewModelScope.launch {
            // When it was asked, not answered: a vote made during a slow load then counts as newer.
            val asked = clock()
            val result = when (tab) {
                Tab.HOME -> repository.feed(CommunityRepository.HOME_SIZE)
                Tab.ALL -> repository.feed(CommunityRepository.ALL_SIZE)
                Tab.MINE -> repository.mine()
            }
            // A vote still on its way stays on its row, so its highlight doesn't flicker off and back on.
            val posts = (result as? CommunityResult.Ok)?.value?.map { p -> voting[p.id]?.let { p.withVote(it) } ?: p }
            if (posts != null) {
                loadedAt = asked
                if (tab == Tab.HOME) repository.lastHome = posts
            }
            _state.update { s ->
                when {
                    s.tab != tab -> s
                    posts != null -> s.copy(posts = posts, failed = false)
                    else -> s.copy(failed = s.posts == null)
                }
            }
        }
    }

    /** Your own posts can't be voted on; one request per post is out at a time, and a tap meanwhile is sent after it. */
    fun vote(post: CommunityPost, tapped: Int) {
        if (post.mine) return
        val value = post.nextVote(tapped)
        patch(post.id) { it.withVote(value) }
        if (voting.put(post.id, value) != null) return // the request that's out sends this when it's back
        viewModelScope.launch {
            var confirmed = post.myVote
            try {
                while (true) {
                    val sent = voting.getValue(post.id)
                    val r = repository.vote(post.id, sent)
                    if (r !is CommunityResult.Ok) {
                        patch(post.id) { it.withVote(confirmed) }
                        _state.update { it.copy(message = communityMessage(r)) }
                        if (r is CommunityResult.Rejected && r.code == "gone") show(_state.value.tab)
                        return@launch
                    }
                    confirmed = r.value.myVote
                    if (voting[post.id] == sent) {
                        patch(post.id) { it.copy(up = r.value.up, down = r.value.down, myVote = r.value.myVote) }
                        return@launch
                    }
                }
            } finally {
                voting.remove(post.id)
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun patch(id: String, change: (CommunityPost) -> CommunityPost) {
        val s = _state.updateAndGet { it.copy(posts = it.posts?.map { p -> if (p.id == id) change(p) else p }) }
        if (s.tab == Tab.HOME) repository.lastHome = s.posts
    }
}
