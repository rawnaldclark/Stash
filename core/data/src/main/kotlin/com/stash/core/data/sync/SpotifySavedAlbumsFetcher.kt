package com.stash.core.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.album.SavedAlbums
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.SyncUndoDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.sync.workers.SpotifyMirrorStamp
import com.stash.core.data.sync.workers.captureUndoPoint
import com.stash.core.data.sync.workers.shouldDeactivateMissingPlaylists
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.StepStatus
import com.stash.core.model.SyncMode
import com.stash.core.model.SyncStepResult
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyAlbumTracklist
import com.stash.data.spotify.SpotifySavedAlbum
import com.stash.data.spotify.SpotifySavedAlbumsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The Spotify sync's saved-albums step (item 5, 2026-11-02: "Spotify syncing doesn't seem to pull
 * saved albums for me"). Read-only on Spotify: it lists the library's albums and reads their songs;
 * nothing on the account is saved, removed or followed.
 *
 * Each saved album becomes a snapshot the diff files as a `CUSTOM` playlist, source SPOTIFY, keyed
 * [SavedAlbums.sourceId]`("spotify", id)` with its songs at their album positions: #815's way in.
 * From there it is in Library › Albums, out of Playlists and the pickers, merges with the same album
 * saved elsewhere, reads Saved on its page, and is never pinned. New ones start with their Download
 * switch off, so nothing downloads by itself.
 *
 * Scale (a library of thousands of albums):
 *  - the listing is read a page at a time, and each page's albums are written before the next page
 *    is asked for: only the albums' ids are kept for the whole walk;
 *  - an album's songs are read only when it is new here or changed ([albumStampToSkipOn]: saved
 *    again on Spotify, or its rows here changed since it was mirrored); an unchanged one is passed to
 *    the diff as "unchanged", as an unchanged playlist is;
 *  - at most [MAX_READS_PER_SYNC] albums are read per sync, [READ_GAP_MS] apart, and three failed
 *    reads in a row stop the reading for this sync (Spotify answering 429). What isn't read waits for
 *    the next sync, in the library's own order.
 *
 * Removal: an album gone from the listing is hidden like a playlist gone from Spotify, by the same
 * rule ([shouldDeactivateMissingPlaylists]: Refresh mode, and a listing that is provably whole), and
 * only ever a row this step synced (source SPOTIFY). An album saved in Stash under the same key (a
 * pasted Spotify link, source BOTH) is the listener's own save: this step neither reads nor hides it.
 */
class SpotifySavedAlbumsFetcher @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val database: StashDatabase,
    private val syncPreferences: SyncPreferencesManager,
    private val syncUndoDao: SyncUndoDao,
    private val syncLog: SyncLog,
) {

    /**
     * Lists the saved albums and writes this run's snapshots for [syncId]; hides the albums no longer
     * saved when [syncMode] and a whole listing allow it. [onAlbumRead] fires once per album read.
     */
    suspend fun fetch(syncId: Long, syncMode: SyncMode?, onAlbumRead: () -> Unit = {}): SyncStepResult {
        val enabled = runCatching { syncPreferences.syncSpotifySavedAlbums.first() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(true)
        if (!enabled) return SyncStepResult(SERVICE, STEP, StepStatus.SKIPPED)

        val playlistDao = database.playlistDao()
        val listed = HashSet<String>()
        val handled = HashSet<String>()
        val unread = mutableListOf<String>()
        var total: Int? = null
        var offset = 0
        var pages = 0
        var listingComplete = true
        var reads = 0
        var failedInARow = 0
        var skipped = 0
        var deferred = 0
        var savedInStash = 0

        while (true) {
            if (pages >= PAGE_CAP) {
                listingComplete = false
                break
            }
            val page = spotify.getSavedAlbumsPage(limit = PAGE_SIZE, offset = offset)
            currentCoroutineContext().ensureActive()
            if (page === SpotifySavedAlbumsPage.EMPTY) {
                listingComplete = false
                break
            }
            pages++
            if (!page.isComplete) listingComplete = false
            // The library changing mid-walk shifts the pages: an album can be skipped over.
            if (total == null) total = page.totalCount else if (page.totalCount != total) listingComplete = false
            page.listedIds.mapTo(listed) { SavedAlbums.sourceId(SPOTIFY_KEY, it) }

            for (album in page.albums) {
                if (!handled.add(album.id)) continue
                val key = SavedAlbums.sourceId(SPOTIFY_KEY, album.id)
                val row = playlistDao.findBySourceId(key)
                val synced = row != null && row.source == MusicSource.SPOTIFY
                val unchanged = if (synced && !row!!.userHidden) unchangedStamp(row, album) else null
                when {
                    // Saved in Stash (a pasted link): the listener's own copy, left exactly as it is.
                    row != null && !synced -> savedInStash++
                    // Taken out of the library: stays out, and isn't read.
                    row?.userHidden == true -> Unit
                    unchanged != null -> {
                        writeUnchanged(syncId, album, unchanged)
                        skipped++
                    }
                    reads >= MAX_READS_PER_SYNC || failedInARow >= FAILURES_TO_STOP -> deferred++
                    else -> {
                        if (reads > 0) delay(READ_GAP_MS)
                        reads++
                        val tracks = try {
                            spotify.getAlbumTracks(album.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "saved albums: couldn't read '${album.name}'", e)
                            null
                        }
                        if (tracks == null) {
                            failedInARow++
                            unread += album.name
                            syncLog.warn("${album.name} — couldn't read its songs (kept as it was)")
                        } else {
                            failedInARow = 0
                            writeRead(syncId, album, tracks)
                        }
                        onAlbumRead()
                    }
                }
            }

            if (page.rawItemCount == 0) break
            offset += page.rawItemCount
            if (offset >= (total ?: 0)) break
        }
        // Whole only when every album the library counts was listed: a short or shifted walk hides nothing.
        if (total == null || listed.size != total) listingComplete = false

        val hidden = if (syncMode != null && shouldDeactivateMissingPlaylists(syncMode, listingComplete)) {
            hideMissing(syncId, listed)
        } else {
            0
        }

        if (deferred > 0) syncLog.info("Read $reads saved albums; $deferred more come with the next syncs")
        if (hidden > 0) syncLog.warn("Took $hidden album${if (hidden == 1) "" else "s"} you un-saved on Spotify out of Albums")
        Log.i(
            TAG,
            "saved albums: listed=${listed.size}/$total pages=$pages read=$reads unchanged=$skipped " +
                "deferred=$deferred unread=${unread.size} savedInStash=$savedInStash hidden=$hidden whole=$listingComplete",
        )

        val problem = listOfNotNull(
            "listing incomplete — a page failed or the library changed mid-walk".takeUnless { listingComplete },
            unread.takeIf { it.isNotEmpty() }?.let(::unreadAlbumsMessage),
        ).joinToString("; ").ifEmpty { null }
        val note = listOfNotNull(
            "$skipped unchanged since the last sync, not re-read".takeIf { skipped > 0 },
            "$deferred left for the next sync".takeIf { deferred > 0 },
            "$hidden un-saved on Spotify, hidden".takeIf { hidden > 0 },
        ).joinToString("; ").ifEmpty { null }
        val status = when {
            pages == 0 -> StepStatus.ERROR
            problem != null -> StepStatus.ERROR
            total == 0 -> StepStatus.EMPTY
            else -> StepStatus.SUCCESS
        }
        return SyncStepResult(
            SERVICE, STEP, status, itemCount = listed.size,
            errorMessage = if (pages == 0) "couldn't read your saved albums" else problem,
            note = note,
            // Named on the Sync History row with the playlists that couldn't be read.
            unreadPlaylists = unread.take(SyncStepResult.UNREAD_NAMES_STORED),
            unreadPlaylistCount = unread.size,
        )
    }

    /** The stamp to pass [album] on as unchanged, or null to read it ([albumStampToSkipOn]). */
    private suspend fun unchangedStamp(row: PlaylistEntity, album: SpotifySavedAlbum): String? {
        val revision = savedAlbumRevision(album.addedAt) ?: return null
        val stored = row.snapshotId ?: return null
        // The fingerprint (a few queries) only for a stamp whose revision already matches.
        if (SpotifyMirrorStamp.decode(stored)?.revision != revision) return null
        return albumStampToSkipOn(revision, stored, database.playlistDao().mirrorDigest(row.id))
    }

    private suspend fun writeUnchanged(syncId: Long, album: SpotifySavedAlbum, stamp: String) {
        database.remoteSnapshotDao().insertPlaylistSnapshot(
            // Partial, as an unchanged playlist's is: should the stamps ever not match at the diff,
            // nothing is cleared, and the next sync reads the album in full.
            albumSnapshot(syncId, album, trackCount = 0, partial = true, snapshotId = stamp, expectedCount = null),
        )
    }

    private suspend fun writeRead(syncId: Long, album: SpotifySavedAlbum, tracks: SpotifyAlbumTracklist) {
        val cover = com.stash.core.common.ArtUrlUpgrader.upgrade(album.coverUrl)
        database.withTransaction {
            val snapshots = database.remoteSnapshotDao()
            val id = snapshots.insertPlaylistSnapshot(
                albumSnapshot(
                    syncId, album,
                    trackCount = tracks.totalCount ?: tracks.tracks.size,
                    partial = !tracks.complete,
                    // Only a whole read is stamped once mirrored, so a partial one is read again.
                    snapshotId = savedAlbumRevision(album.addedAt).takeIf { tracks.complete },
                    expectedCount = tracks.totalCount,
                ),
            )
            snapshots.insertTrackSnapshots(
                tracks.tracks.mapIndexed { position, track ->
                    RemoteTrackSnapshotEntity(
                        syncId = syncId,
                        snapshotPlaylistId = id,
                        title = track.name,
                        artist = track.artists.joinToString(", "),
                        album = album.name,
                        durationMs = track.durationMs,
                        spotifyUri = "spotify:track:${track.id}",
                        albumArtUrl = cover,
                        position = position,
                        explicit = track.explicit,
                    )
                },
            )
        }
    }

    private fun albumSnapshot(
        syncId: Long,
        album: SpotifySavedAlbum,
        trackCount: Int,
        partial: Boolean,
        snapshotId: String?,
        expectedCount: Int?,
    ) = RemotePlaylistSnapshotEntity(
        syncId = syncId,
        source = MusicSource.SPOTIFY,
        sourcePlaylistId = SavedAlbums.sourceId(SPOTIFY_KEY, album.id),
        playlistName = album.name,
        playlistType = PlaylistType.CUSTOM,
        trackCount = trackCount,
        artUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(album.coverUrl),
        partial = partial,
        expectedCount = expectedCount,
        snapshotId = snapshotId,
    )

    /** Hides the synced albums not in [listed]; the restore point first, so Undo last sync brings them back. */
    private suspend fun hideMissing(syncId: Long, listed: Set<String>): Int {
        val playlistDao = database.playlistDao()
        val missing = playlistDao.spotifySavedAlbumIdsMissingFrom(listed)
        if (missing.isEmpty()) return 0
        captureUndoPoint(syncUndoDao, syncLog, syncId, TAG)
        return playlistDao.deactivateByIdsChunked(missing)
    }

    companion object {
        private const val TAG = "StashSync"
        const val SERVICE = "SPOTIFY"
        const val STEP = "getSavedAlbums"
        private const val SPOTIFY_KEY = "spotify"

        /** Albums per listing page: the web player's own size. */
        const val PAGE_SIZE = 50

        /** Safety cap on listing pages (10,000 albums); a longer walk reads as incomplete and hides nothing. */
        const val PAGE_CAP = 200

        /** Albums whose songs one sync reads; the rest wait for the next sync. */
        const val MAX_READS_PER_SYNC = 200

        /** Between two album reads. */
        const val READ_GAP_MS = 250L

        /** Failed reads in a row that stop reading for this sync. */
        const val FAILURES_TO_STOP = 3
    }
}

/** "couldn't read 1 album: X (kept as it was)", naming up to five. */
internal fun unreadAlbumsMessage(names: List<String>): String {
    val more = names.size - 5
    val named = names.take(5).joinToString(", ") + if (more > 0) " and $more more" else ""
    return if (names.size == 1) {
        "couldn't read 1 album: $named (kept as it was)"
    } else {
        "couldn't read ${names.size} albums: $named (kept as they were)"
    }
}

/**
 * What a saved album's mirror is stamped with ([SpotifyMirrorStamp.revision]): when it was saved on
 * Spotify. Saved again after a removal, it moves, and the album is read again. Null (always read)
 * without one.
 */
internal fun savedAlbumRevision(addedAt: String?): String? =
    addedAt?.takeIf { it.isNotBlank() }?.let { "added:$it" }?.takeIf { SpotifyMirrorStamp.isUsableRevision(it) }

/**
 * The stamp to pass a saved album on as unchanged, or null to read its songs. An album's tracklist
 * doesn't change, so unlike a playlist's stamp ([com.stash.core.data.sync.workers.stampToSkipOn])
 * this one doesn't age or end with an app update: re-reading every album weekly, and after every
 * update, would be thousands of requests for a big library. It holds while the album is the same
 * save ([listRevision]) and its rows here are as the last full mirror left them ([currentDigest]:
 * a song deleted or a copy downloaded since makes it read again, and a block, unblock, restore or
 * undo forgets the stamp).
 */
internal fun albumStampToSkipOn(listRevision: String?, stored: String?, currentDigest: String?): String? {
    if (!SpotifyMirrorStamp.isUsableRevision(listRevision) || currentDigest == null) return null
    val stamp = SpotifyMirrorStamp.decode(stored) ?: return null
    return stored.takeIf { stamp.revision == listRevision && stamp.digest == currentDigest }
}
