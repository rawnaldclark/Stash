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
import com.stash.data.spotify.SpotifyAlbumTracklist
import com.stash.data.spotify.SpotifyApiClient
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
 *  - the listing is read a page at a time, newest save first ([SpotifyApiClient.SAVED_ALBUMS_ORDER]),
 *    and each page is handled in a batch before the next is asked for: its rows looked up together,
 *    the fingerprints of its stamped albums worked out together, its snapshots written in one
 *    transaction. Only the albums' ids are kept for the whole walk;
 *  - an album's songs are read only when it is new here or changed ([albumStampToSkipOn]: saved
 *    again on Spotify, or its rows here changed since it was mirrored), or when they were last read
 *    over [REFRESH_AFTER_MS] ago (a few a sync, oldest first); an unchanged one is passed to the diff
 *    as "unchanged", as an unchanged playlist is;
 *  - at most [MAX_READS_PER_SYNC] albums are read per sync, [READ_GAP_MS] apart, and three failed
 *    reads in a row stop the reading for this sync (Spotify answering 429). What isn't read waits for
 *    the next sync. A read cut short (a later page failed) writes nothing: the album keeps its last
 *    whole tracklist, and a new one waits for a whole read.
 *
 * Removal: an album gone from the listing is hidden like a playlist gone from Spotify, by the same
 * rule ([shouldDeactivateMissingPlaylists]: Refresh mode, and a listing that is provably whole), and
 * only ever a row this step synced (source SPOTIFY). A row under the same key from anywhere else (a
 * pasted Spotify link, source BOTH) is the listener's own: this step neither reads, rewrites nor
 * hides it, and when the listener removed it (Remove hides such a copy) it stays out.
 */
class SpotifySavedAlbumsFetcher @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val database: StashDatabase,
    private val syncPreferences: SyncPreferencesManager,
    private val syncUndoDao: SyncUndoDao,
    private val syncLog: SyncLog,
) {

    /** What one run did, for the log, the diagnostics and the reading budget. */
    private class Tally {
        var reads = 0
        var failedInARow = 0
        var unchanged = 0
        var deferred = 0
        var refreshed = 0
        var savedInStash = 0
        val unread = mutableListOf<String>()
    }

    /**
     * Lists the saved albums and writes this run's snapshots for [syncId]; hides the albums no longer
     * saved when [syncMode] and a whole listing allow it. [onAlbumRead] fires once per album read.
     */
    suspend fun fetch(
        syncId: Long,
        syncMode: SyncMode?,
        onAlbumRead: () -> Unit = {},
        nowMs: Long = System.currentTimeMillis(),
    ): SyncStepResult {
        val enabled = runCatching { syncPreferences.syncSpotifySavedAlbums.first() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(true)
        if (!enabled) return SyncStepResult(SERVICE, STEP, StepStatus.SKIPPED)

        // Read again, oldest first, a few a sync: a tracklist Spotify corrected comes through in time.
        val dueForRefresh = database.playlistDao()
            .spotifyAlbumsReadBefore(nowMs - REFRESH_AFTER_MS, REFRESHES_PER_SYNC)
            .toHashSet()
        val tally = Tally()
        val listed = HashSet<String>()
        val handled = HashSet<String>()
        var total: Int? = null
        var offset = 0
        var pages = 0
        var order: String? = SpotifyApiClient.SAVED_ALBUMS_ORDER
        var listingComplete = true

        while (true) {
            if (pages >= PAGE_CAP) {
                listingComplete = false
                break
            }
            val page = spotify.getSavedAlbumsPage(limit = PAGE_SIZE, offset = offset, order = order)
            currentCoroutineContext().ensureActive()
            if (page === SpotifySavedAlbumsPage.EMPTY) {
                listingComplete = false
                break
            }
            pages++
            if (!page.isComplete) listingComplete = false
            // The rest of the walk goes in the order the first page came in (Spotify's default, should
            // it have refused ours); a page in another order is a different listing.
            if (pages == 1) order = page.sortOrder ?: order else if (page.sortOrder != null && page.sortOrder != order) listingComplete = false
            // The library changing mid-walk shifts the pages: an album can be skipped over.
            if (total == null) total = page.totalCount else if (page.totalCount != total) listingComplete = false
            page.listedIds.mapTo(listed) { keyOf(it) }

            handlePage(syncId, page.albums.filter { handled.add(it.id) }, dueForRefresh, tally, onAlbumRead)

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

        if (tally.deferred > 0) syncLog.info("Read ${tally.reads} saved albums; ${tally.deferred} more come with the next syncs")
        if (hidden > 0) syncLog.warn("Took $hidden album${if (hidden == 1) "" else "s"} you un-saved on Spotify out of Albums")
        Log.i(
            TAG,
            "saved albums: listed=${listed.size}/$total pages=$pages order=$order read=${tally.reads} " +
                "refreshed=${tally.refreshed} unchanged=${tally.unchanged} deferred=${tally.deferred} " +
                "unread=${tally.unread.size} savedInStash=${tally.savedInStash} hidden=$hidden whole=$listingComplete",
        )

        val problem = listOfNotNull(
            "listing incomplete — a page failed or the library changed mid-walk".takeUnless { listingComplete },
            tally.unread.takeIf { it.isNotEmpty() }?.let(::unreadAlbumsMessage),
        ).joinToString("; ").ifEmpty { null }
        val note = listOfNotNull(
            "${tally.unchanged} unchanged since the last sync, not re-read".takeIf { tally.unchanged > 0 },
            "${tally.refreshed} re-read after $REFRESH_AFTER_DAYS days".takeIf { tally.refreshed > 0 },
            "${tally.deferred} left for the next sync".takeIf { tally.deferred > 0 },
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
            unreadPlaylists = tally.unread.take(SyncStepResult.UNREAD_NAMES_STORED),
            unreadPlaylistCount = tally.unread.size,
        )
    }

    /**
     * One listing page's [albums]: their rows looked up together, the fingerprints of the ones whose
     * stamp names this save worked out together, the songs read of those that need it, then every
     * snapshot written in one transaction.
     */
    private suspend fun handlePage(
        syncId: Long,
        albums: List<SpotifySavedAlbum>,
        dueForRefresh: Set<String>,
        tally: Tally,
        onAlbumRead: () -> Unit,
    ) {
        if (albums.isEmpty()) return
        val playlistDao = database.playlistDao()
        val rows = playlistDao.findBySourceIds(albums.map { keyOf(it.id) }).associateBy { it.sourceId }
        // A stamp can only vouch for the save it names: fingerprint those alone.
        val vouched = albums.mapNotNull { album ->
            rows[keyOf(album.id)]?.takeIf { skippable(it, album, dueForRefresh) }?.id
        }
        val digests = playlistDao.mirrorDigests(vouched)

        val unchanged = mutableListOf<RemotePlaylistSnapshotEntity>()
        val read = mutableListOf<Pair<SpotifySavedAlbum, SpotifyAlbumTracklist>>()
        for (album in albums) {
            val key = keyOf(album.id)
            val row = rows[key]
            val stamp = row?.takeIf { skippable(it, album, dueForRefresh) }
                ?.let { albumStampToSkipOn(savedAlbumRevision(album.addedAt), it.snapshotId, digests[it.id]) }
            when {
                // Anyone else's row under this key (a pasted link, source BOTH): the listener's own,
                // left exactly as it is, removed (hidden) or not.
                row != null && row.source != MusicSource.SPOTIFY -> tally.savedInStash++
                // Taken out of the library: stays out, and isn't read.
                row?.userHidden == true -> Unit
                stamp != null -> {
                    // Partial, as an unchanged playlist's is: should the stamps ever not match at the
                    // diff, nothing is cleared, and the next sync reads the album in full.
                    unchanged += albumSnapshot(syncId, album, trackCount = 0, partial = true, snapshotId = stamp, expectedCount = null)
                    tally.unchanged++
                }
                tally.reads >= MAX_READS_PER_SYNC || tally.failedInARow >= FAILURES_TO_STOP -> tally.deferred++
                else -> {
                    if (tally.reads > 0) delay(READ_GAP_MS)
                    tally.reads++
                    val tracks = try {
                        spotify.getAlbumTracks(album.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "saved albums: couldn't read '${album.name}'", e)
                        null
                    }
                    if (tracks == null || !tracks.walkedAll) {
                        // Failed, or cut short by a page that failed: nothing written over the album.
                        tally.failedInARow++
                        tally.unread += album.name
                        syncLog.warn("${album.name} — couldn't read its songs (kept as it was)")
                    } else {
                        tally.failedInARow = 0
                        if (key in dueForRefresh) tally.refreshed++
                        read += album to tracks
                    }
                    onAlbumRead()
                }
            }
        }
        writePage(syncId, unchanged, read)
    }

    /** Whether [row] may be passed on unread: synced here, not taken out, not due, its stamp naming [album]'s save. */
    private fun skippable(row: PlaylistEntity, album: SpotifySavedAlbum, dueForRefresh: Set<String>): Boolean {
        if (row.source != MusicSource.SPOTIFY || row.userHidden || row.sourceId in dueForRefresh) return false
        val revision = savedAlbumRevision(album.addedAt) ?: return false
        return SpotifyMirrorStamp.decode(row.snapshotId)?.revision == revision
    }

    /** A page's snapshots, in one transaction. */
    private suspend fun writePage(
        syncId: Long,
        unchanged: List<RemotePlaylistSnapshotEntity>,
        read: List<Pair<SpotifySavedAlbum, SpotifyAlbumTracklist>>,
    ) {
        if (unchanged.isEmpty() && read.isEmpty()) return
        database.withTransaction {
            val snapshots = database.remoteSnapshotDao()
            if (unchanged.isNotEmpty()) snapshots.insertPlaylistSnapshots(unchanged)
            for ((album, tracks) in read) {
                val cover = com.stash.core.common.ArtUrlUpgrader.upgrade(album.coverUrl)
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
        sourcePlaylistId = keyOf(album.id),
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

    private fun keyOf(albumId: String) = SavedAlbums.sourceId(SPOTIFY_KEY, albumId)

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

        /** An album's songs are read again once they are this old, so Spotify's corrections arrive. */
        const val REFRESH_AFTER_DAYS = 90
        const val REFRESH_AFTER_MS = REFRESH_AFTER_DAYS * 24L * 60 * 60 * 1000

        /** Of [MAX_READS_PER_SYNC], how many may go to albums read [REFRESH_AFTER_DAYS] days ago. */
        const val REFRESHES_PER_SYNC = 100
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
 * rarely changes, so unlike a playlist's stamp ([com.stash.core.data.sync.workers.stampToSkipOn])
 * this one doesn't end after a week or with an app update: re-reading every album weekly, and after
 * every update, would be thousands of requests for a big library (the step re-reads the oldest after
 * [SpotifySavedAlbumsFetcher.REFRESH_AFTER_DAYS] days instead, a few a sync). It holds while the
 * album is the same save ([listRevision]) and its rows here are as the last full mirror left them
 * ([currentDigest]: a song deleted or blocked, or a copy downloaded since makes it read again, and an
 * unblock of a song it may hold, a restore or an undo forgets the stamp).
 */
internal fun albumStampToSkipOn(listRevision: String?, stored: String?, currentDigest: String?): String? {
    if (!SpotifyMirrorStamp.isUsableRevision(listRevision) || currentDigest == null) return null
    val stamp = SpotifyMirrorStamp.decode(stored) ?: return null
    return stored.takeIf { stamp.revision == listRevision && stamp.digest == currentDigest }
}
