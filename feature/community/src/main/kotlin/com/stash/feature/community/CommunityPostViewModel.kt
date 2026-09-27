package com.stash.feature.community

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.communityMessage
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.SharedMixDocument
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.toTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** "Playlist · 42 songs · 2 h 11 min", "Mix · 34 songs", or a song's artist (spec 2026-09-26 §3 The post screen). */
internal fun CommunityPost.headline(): String {
    if (kind == "song") return track?.artist ?: artist.orEmpty()
    val minutes = tracks.sumOf { it.durationMs ?: 0L } / 60_000
    val length = when {
        minutes <= 0 -> null
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h" + if (minutes % 60 > 0) " ${minutes % 60} min" else ""
    }
    return listOfNotNull(kindLabel(kind), songs(count), length).joinToString(" · ")
}

/** A playlist or mix post as the shared-mix document that Play and Save a copy already take. */
internal fun CommunityPost.toDocument() = SharedMixDocument(name = title, sharedBy = name, covers = covers, tracks = tracks)

/** One post (spec 2026-09-26 §3 The post screen). */
@HiltViewModel
class CommunityPostViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CommunityRepository,
    private val sharedMixRepository: SharedMixRepository,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val likeCoordinator: LikeCoordinator,
) : ViewModel() {
    private val postId: String = checkNotNull(savedStateHandle.get<String>("postId"))

    sealed interface UiState {
        data object Loading : UiState
        /** Taken down, removed, expired or hidden (spec §3). */
        data object Gone : UiState
        /** [message] says why: offline, rate limited (spec §4). */
        data class Failed(val message: String) : UiState
        data class Loaded(val post: CommunityPost, val busy: Boolean = false, val liked: Boolean = false, val message: String? = null) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    /** The vote last asked for while a request is out, or null (main thread only). */
    private var pending: Int? = null

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = repository.open(postId)) {
                is CommunityResult.Ok -> UiState.Loaded(r.value, liked = likedAlready(r.value))
                is CommunityResult.Rejected -> if (r.code == "gone") UiState.Gone else UiState.Failed(communityMessage(r))
                is CommunityResult.Failed -> UiState.Failed(communityMessage(r))
            }
        }
    }

    /** Plays the frozen songs, as a shared mix's Play does: each is kept as a hidden stream-only row first. */
    fun play() = withPost("play this") { post ->
        if (post.kind == "song") {
            songRow(post)?.let { row ->
                // As SharedTrackViewModel.play: the row says whether the song is liked here.
                update { it.copy(liked = row.stashLikedAt != null) }
                playerRepository.setQueue(listOf(row), 0)
            }
        } else {
            val tracks = sharedMixRepository.tracksFor(post.toDocument())
            if (tracks.isNotEmpty()) playerRepository.setQueue(tracks, 0)
        }
    }

    fun saveCopy(onSaved: (Long) -> Unit) = withPost("save a copy") { post -> onSaved(sharedMixRepository.saveCopy(post.toDocument())) }

    fun like() = withPost("like this song") { post ->
        val song = post.track ?: return@withPost
        likeCoordinator.setLiked(musicRepository.ensureTrackPersisted(song.toTrack()), true)
        update { it.copy(liked = true) }
    }

    /** Not on your own post. One request is out at a time; a tap meanwhile is sent after it (as in CommunityViewModel). */
    fun vote(tapped: Int) {
        val s = _state.value as? UiState.Loaded ?: return
        if (s.post.mine) return
        val value = s.post.nextVote(tapped)
        update { it.copy(post = it.post.withVote(value)) }
        val first = pending == null
        pending = value
        if (!first) return // the request that's out sends this when it's back
        viewModelScope.launch {
            var confirmed = s.post.myVote
            try {
                while (true) {
                    val sent = pending ?: return@launch
                    val r = repository.vote(postId, sent)
                    if (r !is CommunityResult.Ok) {
                        update { it.copy(post = it.post.withVote(confirmed), message = communityMessage(r)) }
                        return@launch
                    }
                    confirmed = r.value.myVote
                    if (pending == sent) {
                        update { it.copy(post = it.post.copy(up = r.value.up, down = r.value.down, myVote = r.value.myVote)) }
                        return@launch
                    }
                }
            } finally {
                pending = null
            }
        }
    }

    fun takeDown(onDone: () -> Unit) = withPost("take this post down") { _ ->
        when (val r = repository.takeDown(postId)) {
            is CommunityResult.Ok -> onDone()
            else -> update { it.copy(message = communityMessage(r)) }
        }
    }

    fun messageShown() = update { it.copy(message = null) }

    /** Is the posted song already liked here: a read-only lookup, so opening a post never adds a row. */
    private suspend fun likedAlready(post: CommunityPost): Boolean {
        val yt = post.track?.youtubeId ?: return false
        return try { musicRepository.observeTrackByYoutubeId(yt).first()?.stashLikedAt != null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    /** The song post's library row, for Play: saved like a song link's (SharedTrackViewModel), then read back. */
    private suspend fun songRow(post: CommunityPost): Track? {
        val song = post.track ?: return null
        return musicRepository.observeTrackById(musicRepository.ensureTrackPersisted(song.toTrack())).first()
    }

    /** Runs one action on the loaded post; a failure (DB, IO) becomes "Couldn't [action]. Try again.", never a crash. */
    private fun withPost(action: String, block: suspend (CommunityPost) -> Unit) {
        val s = _state.value as? UiState.Loaded ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block(s.post)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$action failed", e)
                update { it.copy(message = "Couldn't $action. Try again.") }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    private fun update(change: (UiState.Loaded) -> UiState.Loaded) {
        (_state.value as? UiState.Loaded)?.let { _state.value = change(it) }
    }

    private companion object {
        const val TAG = "CommunityPostVM"
    }
}
