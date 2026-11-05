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
    /**
     * The rest of [totalTracks]: source BOTH (Spotify export imports, shared and web-library adds),
     * your own files and cloud songs. Not added to the accounts' numbers: a BOTH song would count twice.
     */
    val otherTracks: Int = 0,
    /** True when the song counts are "in your library" (Online); false when they're downloads (Offline). */
    val countsInLibrary: Boolean = false,
    /** False until the song counts have been read once. Screens ask [songCountFor], which also checks the mode. */
    val songCountsKnown: Boolean = false,
    val totalPlaylists: Int = 0,
    val storageUsedBytes: Long = 0,
    /** Lossless files on this phone (the disk walk). */
    val flacTracks: Int = 0,
    /** Sum of file sizes for downloaded FLAC tracks. Subset of [storageUsedBytes]. */
    val flacStorageBytes: Long = 0,
    val state: SyncState = SyncState.IDLE,
    /** Richer display-oriented summary of the latest sync outcome. */
    val displayStatus: SyncDisplayStatus = SyncDisplayStatus.Idle,
) {
    /**
     * The song count to show in [streamingMode], or null while there is none for that mode, so the
     * heroes leave the number out and the card stays away. Null before the first count, so "0 songs"
     * can't flash on a library with thousands. Null after a flip until the new mode's count is in: the
     * count rests off-screen and its query takes time, so the number held here can be the old mode's,
     * and would read "72,000 songs" on the Offline hero.
     */
    fun songCountFor(streamingMode: Boolean): Int? =
        totalTracks.takeIf { songCountsKnown && countsInLibrary == streamingMode }
}
