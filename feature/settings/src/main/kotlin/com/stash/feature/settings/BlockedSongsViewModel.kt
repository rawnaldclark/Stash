package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Presentation-layer row for the Blocked Songs viewer. Decouples the UI
 * from the Room entity so the settings feature doesn't take a dependency
 * on `core:data`'s `TrackEntity` at its surface.
 */
data class BlockedTrackRow(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String?,
)

/**
 * ViewModel backing the Settings → Blocked Songs screen.
 *
 * Exposes the currently-blocked tracks as a reactive [StateFlow] (so a new
 * block from the Home delete dialog streams into the list without a
 * manual refresh) and a single [unblock] action that clears the flag.
 * Unblocking immediately makes the next sync re-queue the track's
 * identity normally — see [MusicRepository.unblacklistTrack].
 */
@HiltViewModel
class BlockedSongsViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
) : ViewModel() {

    /** Reactive list of blocked tracks, sorted by artist then title. */
    val blockedTracks: StateFlow<List<BlockedTrackRow>> =
        musicRepository.getBlacklistedTracks()
            .map { entities -> entities.map { it.toRow() } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /** Unblock a track. The next sync will re-queue it for download. */
    fun unblock(trackId: Long) {
        viewModelScope.launch {
            musicRepository.unblacklistTrack(trackId)
        }
    }
}

private fun TrackEntity.toRow() = BlockedTrackRow(
    trackId = id,
    title = title,
    artist = artist,
    album = album,
    albumArtUrl = albumArtUrl,
)
