package com.stash.feature.community

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.community.PostTarget
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class CommunityPickerViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val repository: CommunityRepository,
) : ViewModel() {
    data class UiState(val playlists: List<Playlist> = emptyList(), val songs: List<Track> = emptyList(), val loading: Boolean = true)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Your playlists and mixes (not Liked Songs or the Downloads bucket, not empty ones), then your 20 most recently
     * played songs. A reopen keeps the last lists on screen until these arrive.
     */
    fun load() {
        viewModelScope.launch {
            _state.value = try {
                val playlists = musicRepository.getAllPlaylists().first()
                    .filter { (it.type == PlaylistType.CUSTOM || it.type in CommunityRepository.MIX_TYPES) && it.trackCount > 0 }
                    // SQLite's name order puts "sad boy hours" after every capitalised name.
                    .sortedBy { it.name.lowercase() }
                UiState(playlists, repository.recentSongs(), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CommunityPicker", "load failed", e)
                _state.value.copy(loading = false)
            }
        }
    }
}

/** `+`'s picker (spec 2026-09-26 §3 Posting): your playlists and mixes, then your recent songs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityPicker(onPick: (PostTarget) -> Unit, onDismiss: () -> Unit, viewModel: CommunityPickerViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = StashTheme.extendedColors.elevatedSurface) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("What are you digging?", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your playlists and mixes, then recent songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (state.loading) item { CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp)) }
            items(state.playlists, key = { "p${it.id}" }) { p ->
                val mix = p.type in CommunityRepository.MIX_TYPES
                PickRow(p.artUrl, p.name, "${if (mix) "Mix" else "Playlist"} · ${songs(p.trackCount)}") { onPick(PostTarget.Playlist(p.id)) }
            }
            items(state.songs, key = { "s${it.id}" }) { t ->
                PickRow(t.albumArtPath ?: t.albumArtUrl, t.title, "Song · ${t.artist}") { onPick(PostTarget.Song(t)) }
            }
            if (!state.loading && state.playlists.isEmpty() && state.songs.isEmpty()) {
                item {
                    Text(
                        "Nothing to post yet. Play a song or make a playlist first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickRow(art: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Your own art, local files included: nothing leaves the phone until you post.
        AsyncImage(art, null, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
