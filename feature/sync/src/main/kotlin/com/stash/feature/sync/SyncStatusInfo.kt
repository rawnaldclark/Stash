package com.stash.feature.sync

import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.SyncState

/**
 * The Sync tab's library numbers: song counts (in total and per account), the
 * storage breakdown, the latest sync's timestamp, and a richer
 * [SyncDisplayStatus] so partial / interrupted runs aren't reported as generic
 * failures. The [com.stash.feature.sync.components.TrackCountCard] and both
 * heroes read it.
 */
data class SyncStatusInfo(
    val lastSyncTime: Long? = null,
    val nextSyncTime: Long? = null,
    /**
     * Songs, from every source. Online ([countsInLibrary]): the songs in your
     * library, downloaded or not, as Library's Songs tab lists them. Offline:
     * downloaded songs.
     */
    val totalTracks: Int = 0,
    /** The part of [totalTracks] that came from Spotify. */
    val spotifyTracks: Int = 0,
    /** The part of [totalTracks] that came from YouTube Music. */
    val youTubeTracks: Int = 0,
    /** The part of [totalTracks] that came from SoundCloud. */
    val soundCloudTracks: Int = 0,
    /** True when the song counts are "in your library" (Online); false when they're downloads (Offline). */
    val countsInLibrary: Boolean = false,
    val totalPlaylists: Int = 0,
    val storageUsedBytes: Long = 0,
    /** Lossless files on this phone (the disk walk). */
    val flacTracks: Int = 0,
    /** Sum of file sizes for downloaded FLAC tracks. Subset of [storageUsedBytes]. */
    val flacStorageBytes: Long = 0,
    val state: SyncState = SyncState.IDLE,
    /** Richer display-oriented summary of the latest sync outcome. */
    val displayStatus: SyncDisplayStatus = SyncDisplayStatus.Idle,
)
