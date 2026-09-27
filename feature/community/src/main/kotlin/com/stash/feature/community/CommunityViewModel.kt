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
    private val voting = mutableSetOf<String>()

    init {
        // A post or a take-down (the posting sheet, a post screen) reloads whatever is on screen.
        viewModelScope.launch { repository.revision.drop(1).collect { show(_state.value.tab) } }
    }

    /**
     * Home calls this each time the section appears. It reloads unless it loaded in the last 30 seconds and no
     * vote went through since (See all and the post screen keep their own lists).
     */
    fun onShown() {
        if (System.currentTimeMillis() - loadedAt > 30_000 || repository.lastVoteAt > loadedAt) show(Tab.HOME)
    }

    /** Loads [tab]. The list on screen stays until the new one arrives, unless one is Mine and the other isn't. */
    fun show(tab: Tab) {
        _state.update { s ->
            val sameKind = (tab == Tab.MINE) == (s.tab == Tab.MINE)
            s.copy(tab = tab, posts = if (sameKind) s.posts else null, failed = false)
        }
        loading?.cancel()
        loading = viewModelScope.launch {
            val result = when (tab) {
                Tab.HOME -> repository.feed(CommunityRepository.HOME_SIZE)
                Tab.ALL -> repository.feed(CommunityRepository.ALL_SIZE)
                Tab.MINE -> repository.mine()
            }
            if (result is CommunityResult.Ok) {
                loadedAt = System.currentTimeMillis()
                if (tab == Tab.HOME) repository.lastHome = result.value
            }
            _state.update { s ->
                when {
                    s.tab != tab -> s
                    result is CommunityResult.Ok -> s.copy(posts = result.value, failed = false)
                    else -> s.copy(failed = s.posts == null)
                }
            }
        }
    }

    fun vote(post: CommunityPost, tapped: Int) {
        if (post.mine || !voting.add(post.id)) return
        val value = post.nextVote(tapped)
        patch(post.id) { it.withVote(value) }
        viewModelScope.launch {
            try {
                when (val r = repository.vote(post.id, value)) {
                    is CommunityResult.Ok -> patch(post.id) { it.copy(up = r.value.up, down = r.value.down, myVote = r.value.myVote) }
                    else -> {
                        patch(post.id) { post }
                        _state.update { it.copy(message = communityMessage(r)) }
                    }
                }
            } finally {
                voting.remove(post.id)
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun patch(id: String, change: (CommunityPost) -> CommunityPost) {
        _state.update { s -> s.copy(posts = s.posts?.map { if (it.id == id) change(it) else it }) }
        if (_state.value.tab == Tab.HOME) repository.lastHome = _state.value.posts
    }
}
