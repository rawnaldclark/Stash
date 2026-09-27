package com.stash.feature.community

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.Draft
import com.stash.core.data.community.communityMessage
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.PostTarget
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The confirm sheet's state (spec 2026-09-26 §3 Posting). One instance serves every opening; [open] resets it. */
@HiltViewModel
class PostComposerViewModel @Inject constructor(private val repository: CommunityRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState

        /** It can't be posted: "This playlist is empty." and the like. */
        data class Problem(val message: String) : UiState

        /**
         * [name] is the saved "Show my name as" (blank: the sheet asks for one). [me] is null until the limits
         * arrive, or when they can't be read. [seeMyPosts] offers See all ▸ Mine after a live_limit.
         */
        data class Ready(
            val draft: Draft.Ready,
            val name: String,
            val me: CommunityMe? = null,
            val posting: Boolean = false,
            val error: String? = null,
            val seeMyPosts: Boolean = false,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var opening: Job? = null

    /** Called each time the sheet opens. */
    fun open(target: PostTarget) {
        opening?.cancel()
        _state.value = UiState.Loading
        opening = viewModelScope.launch {
            try {
                when (val draft = repository.draft(target)) {
                    is Draft.Problem -> _state.value = UiState.Problem(draft.message)
                    is Draft.Ready -> {
                        _state.value = UiState.Ready(draft, repository.displayName().orEmpty())
                        val me = repository.me()
                        if (me is CommunityResult.Ok) update { it.copy(me = me.value) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "draft failed", e)
                _state.value = UiState.Problem("Something went wrong. Try again.")
            }
        }
    }

    /** The sheet closed: the next opening starts from Loading, not from this one's draft or "Posting…". */
    fun reset() { opening?.cancel(); _state.value = UiState.Loading }

    /** Posts under [name]. [onPosted] closes the sheet; a refusal stays on it with the reason. */
    fun post(name: String, onPosted: () -> Unit) {
        val s = _state.value as? UiState.Ready ?: return
        if (s.posting) return
        if (name.isBlank()) {
            _state.value = s.copy(error = "Add the name to show on your post.")
            return
        }
        _state.value = s.copy(posting = true, error = null, seeMyPosts = false)
        viewModelScope.launch {
            val r = try {
                repository.post(s.draft, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "post failed", e)
                CommunityResult.Failed(e.message)
            }
            if (r is CommunityResult.Ok) {
                onPosted()
            } else {
                update { it.copy(posting = false, error = communityMessage(r), seeMyPosts = (r as? CommunityResult.Rejected)?.code == "live_limit") }
            }
        }
    }

    private fun update(change: (UiState.Ready) -> UiState.Ready) {
        (_state.value as? UiState.Ready)?.let { _state.value = change(it) }
    }

    private companion object {
        const val TAG = "CommunityPost"
    }
}

/** The frozen-copy note (spec §3 Posting). */
internal fun frozenNote(draft: Draft.Ready): String =
    if (draft.kind == "song") {
        "Everyone with Community turned on will see this song. It stays up for 30 days, and you can take it down any time."
    } else {
        "Everyone with Community turned on will see ${if (draft.tracks.size == 1) "this song exactly as it is" else "these ${draft.tracks.size} songs exactly as they are"} now. " +
            "Later changes to your ${if (draft.kind == "mix") "mix" else "playlist"} won't show. " +
            "It stays up for 30 days, and you can take it down any time."
    }

/** The one confirm sheet every way in leads to (spec §3 Posting). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostConfirmSheet(
    target: PostTarget,
    onDismiss: () -> Unit,
    onSeeMyPosts: () -> Unit,
    viewModel: PostComposerViewModel = hiltViewModel(),
) {
    LaunchedEffect(target) { viewModel.open(target) }
    DisposableEffect(viewModel) { onDispose(viewModel::reset) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // A post in flight holds the sheet open: its result (the toast or the reason) lands here, and Post can't go twice.
    val sending by rememberUpdatedState((state as? PostComposerViewModel.UiState.Ready)?.posting == true)
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { it != SheetValue.Hidden || !sending })
    // M3 1.4.0: back calls onDismissRequest even after confirmValueChange vetoed the hide.
    ModalBottomSheet(onDismissRequest = { if (!sending) onDismiss() }, sheetState = sheetState, containerColor = StashTheme.extendedColors.elevatedSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Post to Community", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            when (val s = state) {
                PostComposerViewModel.UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                is PostComposerViewModel.UiState.Problem -> {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("OK") }
                }
                is PostComposerViewModel.UiState.Ready -> ReadyContent(
                    s,
                    onPost = { name ->
                        viewModel.post(name) {
                            Toast.makeText(context, "Posted to Community", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                    onDismiss = onDismiss,
                    onSeeMyPosts = onSeeMyPosts,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    s: PostComposerViewModel.UiState.Ready,
    onPost: (String) -> Unit,
    onDismiss: () -> Unit,
    onSeeMyPosts: () -> Unit,
) {
    val draft = s.draft
    val askName = s.name.isBlank()
    var name by rememberSaveable(draft.title) { mutableStateOf(s.name) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CoverArt(draft.covers, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(draft.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val what = if (draft.kind == "song") draft.tracks.single().artist else songs(draft.tracks.size)
            Text(
                listOfNotNull(kindLabel(draft.kind), what, s.name.takeUnless { askName }?.let { "posting as $it" })
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (askName) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Show my name as") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(frozenNote(draft), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    s.me?.let { me ->
        Spacer(Modifier.height(8.dp))
        if (me.blocked) {
            Text(
                communityMessage(CommunityResult.Rejected("blocked")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "${me.postsLeftToday} of 2 posts left today · ${me.spotsFree} of 5 spots free",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    s.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (s.seeMyPosts) TextButton(onClick = onSeeMyPosts) { Text("See my posts") }
    Spacer(Modifier.height(16.dp))
    Button(onClick = { onPost(name) }, enabled = !s.posting && s.me?.blocked != true, modifier = Modifier.fillMaxWidth()) {
        Text(if (s.posting) "Posting…" else "Post")
    }
    TextButton(onClick = onDismiss, enabled = !s.posting, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}
