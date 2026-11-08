package com.stash.core.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.album.SavedAlbums
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.sync.workers.SpotifyMirrorStamp
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
 *    again on Spotify, or its rows here changed since it was mirrored), or when the diff last took
 *    them in over [REFRESH_AFTER_MS] ago (`last_synced`; at most [REFRESHES_PER_SYNC] a sync, in the
 *    listing's order); an unchanged one is passed to the diff as "unchanged", as an unchanged
 *    playlist is;
 *  - at most [MAX_READS_PER_SYNC] albums are read per sync, [READ_GAP_MS] apart, and three reads in a
 *    row that got nothing back stop the reading for this sync (Spotify answering 429). What isn't
 *    read waits for the next sync.
 *  - a short read (fewer songs than Spotify counts) is written as partial and unstamped, so the diff
 *    only adds, when every read would stop there too (an album over the walk's 1,000 songs, or
 *    Spotify listing fewer than it counts) or the album isn't here yet. When a page failed over an
 *    album already here, nothing is written: its last tracklist stands, and it's read next sync.
 *    Spotify answered either way, so a short read never counts toward the stop.
 *
 * Removal: this step only LISTS. An album gone from every account's listing is hidden after every
 * account's pass by the sync's one joint hide ([hideWhatNoSpotifyAccountListed], plan 2026-11-04
 * §2.4), by the same rule a playlist gone from Spotify is (Refresh mode, and listings that are
 * provably whole: here, every page by date saved, [SpotifyApiClient.SAVED_ALBUMS_ORDER]), and only
 * ever a row a sync filed (source SPOTIFY). A row under the same key from anywhere else (a pasted
 * Spotify link, source BOTH) is the listener's own: this step neither reads, rewrites nor hides it,
 * and when the listener removed it (Remove hides such a copy) it stays out, unless they save it on
 * Spotify after removing it ([savedSinceRemoved]): then it is the sync's again.
 *
 * Several Spotify accounts (plan 2026-11-04 §2.3): the sync runs this step once per account, the
 * main first, each run acting as its account. An album an earlier account listed is "claimed": a
 * later account counts it as listed but doesn't write it again (one snapshot per album per sync),
 * so an album two accounts saved is one row, the main's when it is one of them. Every account
 * shares one [ReadBudget], so a sync reads at most [MAX_READS_PER_SYNC] albums whatever the number
 * of accounts.
 */
class SpotifySavedAlbumsFetcher @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val database: StashDatabase,
    private val syncPreferences: SyncPreferencesManager,
    private val syncLog: SyncLog,
) {

    /**
     * One sync's album reading, shared by every account's run of this step: the albums read, the
     * failed reads in a row that stop the reading, and the places taken by albums re-read for
     * their age. One per sync, so extra accounts add no reads to it (plan 2026-11-04 §5 Wave 2).
     */
    class ReadBudget {
        /** Albums whose songs were read this sync, of [MAX_READS_PER_SYNC]. */
        var reads = 0
            internal set

        /** Reads in a row that got nothing back; [FAILURES_TO_STOP] of them stop the reading. */
        var failedInARow = 0
            internal set

        /** Albums picked this sync to read again for their age, of [REFRESHES_PER_SYNC]. */
        var duePicked = 0
            internal set

        /** Whether the reading stopped for this sync, at the cap or on failures in a row. */
        val spent: Boolean get() = reads >= MAX_READS_PER_SYNC || failedInARow >= FAILURES_TO_STOP
    }

    /** What one account's run did, for the log and the diagnostics. */
    private class Tally {
        var reads = 0
        var unchanged = 0
        var deferred = 0
        var refreshed = 0
        var readInPart = 0
        var savedInStash = 0
        var savedAgain = 0
        val unread = mutableListOf<String>()
    }

    /**
     * Lists one account's saved albums into [listing] and writes this run's snapshots for [syncId].
     * Hides nothing: [listing] is what the sync's joint hide measures "un-saved" against, and it is
     * marked complete only when the walk proved it whole (every page, by date saved, the library's
     * own count met). [onAlbumRead] fires once per album read.
     *
     * @param syncMode Only for the step's note on an order that can't prove an album un-saved.
     * @param listing The account's album listing ([SpotifyAccountListing.owner] tags the snapshots:
     *   null for the main account). Filled with every album key listed, claimed ones included.
     * @param accountLabel The account's label for the step ([SyncStepResult.account]); null for the main.
     * @param claimed Album keys an earlier account listed this sync: counted as listed, not written again.
     * @param budget The sync's one album-reading budget, shared by every account.
     * @param stopReading Whether to read no more albums this sync (an extra account's run in a
     *   background sync out of time): the rest wait for the next sync, as past [budget]. The
     *   listing itself still runs whole.
     *
     * The main account's switch is its Sync setting, read here: switched off, the step is SKIPPED
     * and [listing] stays empty and incomplete (the caller drops it). An extra account's switch is
     * its `spotify_accounts` row's, which the caller checks before calling.
     */
    suspend fun fetch(
        syncId: Long,
        syncMode: SyncMode?,
        listing: SpotifyAccountListing = SpotifyAccountListing(owner = null),
        accountLabel: String? = null,
        claimed: Set<String> = emptySet(),
        budget: ReadBudget = ReadBudget(),
        onAlbumRead: () -> Unit = {},
        nowMs: Long = System.currentTimeMillis(),
        stopReading: () -> Boolean = { false },
    ): SyncStepResult {
        val owner = listing.owner
        if (owner == null) {
            val enabled = runCatching { syncPreferences.syncSpotifySavedAlbums.first() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(true)
            if (!enabled) return SyncStepResult(SERVICE, STEP, StepStatus.SKIPPED)
        }

        val tally = Tally()
        val listed = listing.ids
        var claimedSkipped = 0
        val handled = HashSet<String>()
        var total: Int? = null
        var offset = 0
        var pages = 0
        var order: String? = SpotifyApiClient.SAVED_ALBUMS_ORDER
        var listingComplete = true
        // Every page by date saved. In any other order (Spotify's default, "Recents", should it have
        // refused ours, moves an album up each time it's played) an album can slip between two pages,
        // so the walk can't prove an album missing, and nothing is hidden.
        var byDateSaved = true

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
            if (page.sortOrder != SpotifyApiClient.SAVED_ALBUMS_ORDER) byDateSaved = false
            // The rest of the walk goes in the order the first page came in (Spotify's default, should
            // it have refused ours); a page in another order is a different listing.
            if (pages == 1) order = page.sortOrder ?: order else if (page.sortOrder != null && page.sortOrder != order) listingComplete = false
            // The library changing mid-walk shifts the pages: an album can be skipped over.
            if (total == null) total = page.totalCount else if (page.totalCount != total) listingComplete = false
            page.listedIds.mapTo(listed) { keyOf(it) }

            // An album an earlier account listed this sync is that account's to write: it counts as
            // listed here (above), and isn't written again.
            val fresh = page.albums.filter { handled.add(it.id) }
            val mine = fresh.filter { keyOf(it.id) !in claimed }
            claimedSkipped += fresh.size - mine.size
            handlePage(syncId, owner, mine, nowMs, tally, budget, onAlbumRead, stopReading)

            if (page.rawItemCount == 0) break
            offset += page.rawItemCount
            if (offset >= (total ?: 0)) break
        }
        // Whole only when every album the library counts was listed: a short or shifted walk hides nothing.
        if (total == null || listed.size != total) listingComplete = false
        // What the joint hide may measure "un-saved" against: a whole walk, by date saved. An album
        // whose songs couldn't be read is still listed, so both say the same here.
        listing.listedAll = listingComplete && byDateSaved
        listing.complete = listing.listedAll

        if (tally.deferred > 0) syncLog.info("Read ${tally.reads} saved albums; ${tally.deferred} more come with the next syncs")
        Log.i(
            TAG,
            "saved albums${accountLabel?.let { " ($it)" }.orEmpty()}: listed=${listed.size}/$total pages=$pages " +
                "order=$order byDateSaved=$byDateSaved " +
                "read=${tally.reads} refreshed=${tally.refreshed} readInPart=${tally.readInPart} " +
                "unchanged=${tally.unchanged} deferred=${tally.deferred} unread=${tally.unread.size} " +
                "savedInStash=${tally.savedInStash} savedAgain=${tally.savedAgain} claimed=$claimedSkipped " +
                "whole=$listingComplete",
        )

        val problem = listOfNotNull(
            "listing incomplete — a page failed or the library changed mid-walk".takeUnless { listingComplete },
            tally.unread.takeIf { it.isNotEmpty() }?.let(::unreadAlbumsMessage),
        ).joinToString("; ").ifEmpty { null }
        val note = listOfNotNull(
            "${tally.unchanged} unchanged since the last sync, not re-read".takeIf { tally.unchanged > 0 },
            "${tally.refreshed} re-read after $REFRESH_AFTER_DAYS days".takeIf { tally.refreshed > 0 },
            "${tally.readInPart} read in part (Spotify gave fewer songs than it counts)".takeIf { tally.readInPart > 0 },
            "${tally.savedAgain} you'd removed, saved on Spotify again: back in Albums".takeIf { tally.savedAgain > 0 },
            "${tally.deferred} left for the next sync".takeIf { tally.deferred > 0 },
            "$claimedSkipped also saved on an account read before, written there".takeIf { claimedSkipped > 0 },
            "listed in Spotify's order \"$order\", not by date saved: none taken out as un-saved"
                .takeIf { !byDateSaved && pages > 0 && syncMode == SyncMode.REFRESH },
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
            account = accountLabel,
        )
    }

    /**
     * One listing page's [albums]: their rows looked up together, the fingerprints of the ones whose
     * stamp names this save worked out together, the songs read of those that need it, then every
     * snapshot written in one transaction.
     */
    private suspend fun handlePage(
        syncId: Long,
        owner: String?,
        albums: List<SpotifySavedAlbum>,
        nowMs: Long,
        tally: Tally,
        budget: ReadBudget,
        onAlbumRead: () -> Unit,
        stopReading: () -> Boolean,
    ) {
        if (albums.isEmpty()) return
        val playlistDao = database.playlistDao()
        val rows = playlistDao.findBySourceIds(albums.map { keyOf(it.id) }).associateByTo(HashMap()) { it.sourceId }

        // A pasted copy the listener removed, saved on Spotify after that: the sync's again, read below.
        for (album in albums) {
            val row = rows[keyOf(album.id)] ?: continue
            if (!savedSinceRemoved(row, album.addedAt)) continue
            if (playlistDao.returnToSpotifySync(row.id) > 0) {
                rows[row.sourceId] = row.copy(source = MusicSource.SPOTIFY, userHidden = false, snapshotId = null)
                tally.savedAgain++
                syncLog.info("${album.name} — saved on Spotify again since you removed it: back in Albums")
            }
        }

        // Read again once the diff last took their songs in [REFRESH_AFTER_DAYS] days ago, a few a
        // sync in the listing's order. Decided from the rows just loaded: an album read moves on (its
        // last_synced), so later syncs pick the next ones, and one no longer listed never holds a place.
        val readBefore = nowMs - REFRESH_AFTER_MS
        val due = HashSet<String>()
        for (album in albums) {
            if (budget.duePicked >= REFRESHES_PER_SYNC) break
            val row = rows[keyOf(album.id)] ?: continue
            val readAt = row.lastSynced?.toEpochMilli()
            if (sameSave(row, album) && (readAt == null || readAt < readBefore)) {
                due += row.sourceId
                budget.duePicked++
            }
        }

        // A stamp can only vouch for the save it names: fingerprint those alone.
        val vouched = albums.mapNotNull { album ->
            rows[keyOf(album.id)]?.takeIf { it.sourceId !in due && sameSave(it, album) }?.id
        }
        val digests = playlistDao.mirrorDigests(vouched)

        val unchanged = mutableListOf<RemotePlaylistSnapshotEntity>()
        val read = mutableListOf<Pair<SpotifySavedAlbum, SpotifyAlbumTracklist>>()
        for (album in albums) {
            val key = keyOf(album.id)
            val row = rows[key]
            val stamp = row?.takeIf { key !in due && sameSave(it, album) }
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
                    unchanged += albumSnapshot(syncId, owner, album, trackCount = 0, partial = true, snapshotId = stamp, expectedCount = null)
                    tally.unchanged++
                }
                budget.spent || stopReading() -> tally.deferred++
                else -> {
                    // Spaced across accounts too: the gap follows the sync's last read, whoever's it was.
                    if (budget.reads > 0) delay(READ_GAP_MS)
                    budget.reads++
                    tally.reads++
                    val tracks = try {
                        spotify.getAlbumTracks(album.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "saved albums: couldn't read '${album.name}'", e)
                        null
                    }
                    when {
                        tracks == null -> {
                            // Nothing came back (Spotify answering 429, say): counts toward the stop.
                            budget.failedInARow++
                            tally.unread += album.name
                            syncLog.warn("${album.name} — couldn't read its songs (kept as it was)")
                        }
                        tracks.walkedAll -> {
                            budget.failedInARow = 0
                            if (key in due) tally.refreshed++
                            read += album to tracks
                        }
                        // Short reads: Spotify answered, so neither counts toward the stop.
                        row != null && tracks.pageFailed -> {
                            // A later page failed over an album already here: its last tracklist
                            // stands, and the next sync reads it again.
                            tally.unread += album.name
                            syncLog.warn("${album.name} — couldn't read all its songs (kept as it was)")
                        }
                        else -> {
                            // Every read would stop here too (over the walk's 1,000 songs, or Spotify
                            // listing fewer than it counts), or the album isn't here yet: what came is
                            // written, partial (the diff only adds) and unstamped (tracks.complete is
                            // false for any short read).
                            tally.readInPart++
                            read += album to tracks
                            syncLog.info("${album.name} — read ${tracks.tracks.size} of ${tracks.totalCount ?: "?"} songs")
                        }
                    }
                    onAlbumRead()
                }
            }
        }
        writePage(syncId, owner, unchanged, read)
    }

    /** Whether [row] is synced here, not taken out, and stamped with [album]'s save: unread unless due or changed. */
    private fun sameSave(row: PlaylistEntity, album: SpotifySavedAlbum): Boolean {
        if (row.source != MusicSource.SPOTIFY || row.userHidden) return false
        val revision = savedAlbumRevision(album.addedAt) ?: return false
        return SpotifyMirrorStamp.decode(row.snapshotId)?.revision == revision
    }

    /** A page's snapshots, in one transaction. */
    private suspend fun writePage(
        syncId: Long,
        owner: String?,
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
                        syncId, owner, album,
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

    /** A saved album's snapshot, tagged with the account ([owner], null = main) whose run wrote it. */
    private fun albumSnapshot(
        syncId: Long,
        owner: String?,
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
        spotifyAccount = owner,
    )

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

        /**
         * [step] (an account's albums step) naming the [hidden] albums the joint hide took out of
         * that account's rows, as the step did when it still hid them itself.
         */
        fun withHidden(step: SyncStepResult, hidden: Int): SyncStepResult {
            if (hidden <= 0) return step
            val line = "$hidden un-saved on Spotify, hidden"
            return step.copy(note = step.note?.let { "$it; $line" } ?: line)
        }
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
 * When a pasted Spotify album was removed, from the note [com.stash.core.data.db.dao.PlaylistDao.markPastedSpotifyAlbumRemoved]
 * keeps in its `snapshot_id`; null without one (removed before the note existed, or the note was
 * cleared by an undo or a restore: such an album stays out).
 */
internal fun removedAtOf(snapshotId: String?): Long? =
    snapshotId?.takeIf { it.startsWith(REMOVED_NOTE_PREFIX) }?.removePrefix(REMOVED_NOTE_PREFIX)?.toLongOrNull()

internal const val REMOVED_NOTE_PREFIX = "removed:"

/** Spotify's `addedAt.isoString` in epoch ms, or null when it can't be read. */
internal fun addedAtMs(addedAt: String?): Long? = addedAt?.takeIf { it.isNotBlank() }?.let {
    runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Whether [row] is a pasted Spotify album the listener removed (hidden, source BOTH) and has saved on
 * Spotify since ([addedAt] after the removal): the sync takes it back. Saved before the removal, it
 * stays out: removing it here was the later word.
 */
internal fun savedSinceRemoved(row: PlaylistEntity, addedAt: String?): Boolean {
    if (row.source != MusicSource.BOTH || !row.userHidden) return false
    val removedAt = removedAtOf(row.snapshotId) ?: return false
    val savedAt = addedAtMs(addedAt) ?: return false
    return savedAt > removedAt
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
