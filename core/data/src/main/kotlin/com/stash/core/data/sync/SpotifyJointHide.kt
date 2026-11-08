package com.stash.core.data.sync

import android.util.Log
import com.stash.core.data.album.SavedAlbums
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SyncUndoDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.sync.workers.captureUndoPoint
import com.stash.core.data.sync.workers.shouldDeactivateMissingPlaylists
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode

/**
 * How many extra Spotify accounts a sync reads (plan 2026-11-04 §6 decision 10: up to 3, to keep
 * Spotify's rate limits in reach). Extras past it, in row-id order, are left out of the sync and
 * their rows left exactly as they are. The add flow caps at the same number.
 */
const val MAX_EXTRA_SPOTIFY_ACCOUNTS = 3

/**
 * The label an extra Spotify account carries in Sync History and diagnostics
 * ([com.stash.core.model.SyncStepResult.account]): "extra <row id>". The row id is local, stable
 * across syncs (an account removed and added again gets a new one) and names nobody.
 *
 * PRIVACY: never the account's user id or the name the listener gave it.
 */
fun spotifyExtraAccountLabel(rowId: Long): String = "extra $rowId"

/**
 * What one Spotify account listed of one kind (its library playlists, or its saved albums) during
 * one sync, for the joint hide ([hideWhatNoSpotifyAccountListed]) and for who owns a row.
 *
 * A pass registers it, as incomplete, before it starts that listing, and marks it [listedAll] /
 * [complete] only once the walk proved them. So a pass that fails part way, however it fails,
 * leaves an incomplete listing behind.
 *
 * @property owner The account, as rows are tagged (`playlists.spotify_account`): null for the
 *   main account, an extra's Spotify user id otherwise.
 */
class SpotifyAccountListing(val owner: String?) {
    /** Every id the account listed: playlist ids, or saved-album keys (`album:spotify:<id>`). */
    val ids: MutableSet<String> = HashSet()

    /**
     * Whether [ids] is provably every id the account has of this kind: every page and folder
     * read, nothing skipped over. Says nothing about the songs inside them: a listed playlist
     * whose songs couldn't be read is still listed. What the joint hide needs of every OTHER
     * account's listing before a row may go, and what a row's owner needs before the row may
     * move to another account.
     */
    var listedAll: Boolean = false

    /**
     * [listedAll], and everything listed was read too (no playlist whose songs couldn't be read).
     * What the account's OWN rows need before one may be hidden: the rule a single account always
     * had. For saved albums the same as [listedAll] (an album whose songs couldn't be read never
     * kept that step from taking un-saved albums out).
     */
    var complete: Boolean = false
}

/** Every listing a sync's Spotify passes registered, by kind ([SpotifyAccountListing]). */
class SpotifySyncListings {
    val playlists: MutableList<SpotifyAccountListing> = mutableListOf()
    val albums: MutableList<SpotifyAccountListing> = mutableListOf()
}

/**
 * Which rows the joint hide takes out, by the owning account (null = main), and why some or all of
 * a kind were kept ([playlistsKept] / [albumsKept], null when every owner was free to hide; for the
 * log). Worked out by [spotifyRowsToHide]; nothing is written.
 */
data class SpotifyHidePlan(
    val playlists: Map<String?, List<Long>>,
    val albums: Map<String?, List<Long>>,
    val playlistsKept: String?,
    val albumsKept: String?,
) {
    val isEmpty: Boolean get() = playlists.values.all { it.isEmpty() } && albums.values.all { it.isEmpty() }
}

/** What [hideWhatNoSpotifyAccountListed] hid, by the owning account (null = main). */
data class SpotifyHideOutcome(
    val hiddenPlaylists: Map<String?, Int> = emptyMap(),
    val hiddenAlbums: Map<String?, Int> = emptyMap(),
)

/**
 * The joint hide's rule (plan 2026-11-04 §2.4), on [rows] (the active Spotify rows) and what this
 * sync's accounts listed. Per kind, playlists and saved albums each with their own listings:
 *
 *  1. Only in Refresh mode ([shouldDeactivateMissingPlaylists]), and only when EVERY account that
 *     listed that kind listed every id of it ([SpotifyAccountListing.listedAll]): an account whose
 *     walk missed a page could have the row, so nothing of that kind goes, for anyone. An extra
 *     skipped or stopped this sync for a passing reason (out of time, Spotify's limits) counts as
 *     one whose walk missed a page.
 *  2. Only rows owned by an account that listed that kind this sync, and listed it
 *     [complete][SpotifyAccountListing.complete]ly: its own songs all read too, as a single
 *     account always needed. An account with one playlist it couldn't read keeps its own rows,
 *     and costs no other account anything (its ids are all listed). A signed-out, switched-off,
 *     over-the-limit or skipped account's rows are left alone (NULL is the main account). So is a
 *     row whose tag names an account with no `spotify_accounts` row at all (a merge restore or a
 *     likes-only import copies `playlists.spotify_account` but never the account): no pass can
 *     run as it, so nothing ever hides it. It comes back to life when that account is added
 *     again, or when another account lists it (ownership then follows that account's snapshot).
 *  3. A row goes only when its id is missing from EVERY account's listing this run, so a playlist
 *     another account still follows stays, whoever owns the row.
 *
 * What counts as each kind is what the single-account hides used: a playlist is an active Spotify
 * `CUSTOM` row that is no saved album (any `album:` key), a saved album is an active Spotify
 * `CUSTOM` row keyed `album:spotify:<id>`. Liked Songs and mixes are never hidden here.
 */
internal fun spotifyRowsToHide(
    rows: List<PlaylistEntity>,
    syncMode: SyncMode?,
    listings: SpotifySyncListings,
): SpotifyHidePlan {
    val spotifyCustom = rows.filter { it.isActive && it.source == MusicSource.SPOTIFY && it.type == PlaylistType.CUSTOM }
    val playlistRows = spotifyCustom.filterNot { SavedAlbums.isSavedAlbum(it.type, it.source, it.sourceId) }
    val albumRows = spotifyCustom.filter { isSpotifySavedAlbumKey(it.sourceId) }
    val (playlists, playlistsKept) = missingByOwner(playlistRows, syncMode, listings.playlists, "playlists")
    val (albums, albumsKept) = missingByOwner(albumRows, syncMode, listings.albums, "saved albums")
    return SpotifyHidePlan(playlists, albums, playlistsKept, albumsKept)
}

/** One kind of [spotifyRowsToHide]: the missing rows by owner, or none and the reason. */
private fun missingByOwner(
    candidates: List<PlaylistEntity>,
    syncMode: SyncMode?,
    listings: List<SpotifyAccountListing>,
    kind: String,
): Pair<Map<String?, List<Long>>, String?> {
    // No account listed this kind (switched off, or no pass ran): nothing to measure against.
    if (listings.isEmpty()) return emptyMap<String?, List<Long>>() to "no account listed its $kind"
    // Rule 1: every account's ids, whole. One that missed a page could have any row.
    val allListed = listings.all { it.listedAll }
    if (syncMode == null || !shouldDeactivateMissingPlaylists(syncMode, allListed)) {
        val why = when {
            syncMode == null -> "the sync mode couldn't be read"
            syncMode != SyncMode.REFRESH -> "Accumulate never removes"
            else -> "an account's $kind couldn't all be listed"
        }
        return emptyMap<String?, List<Long>>() to why
    }
    // Rule 2: only the accounts that listed this kind completely, their own songs read too. A tag
    // naming any other account (one with something unread, signed out, switched off, over the
    // limit, or with no account row behind it at all) is not in here.
    val owners = listings.filter { it.complete }.mapTo(HashSet()) { it.owner }
    val listed = HashSet<String>()
    for (listing in listings) listed += listing.ids
    val missing = candidates
        .filter { it.spotifyAccount in owners && it.sourceId !in listed }
        .groupBy({ it.spotifyAccount }, { it.id })
    // With one account this is exactly as before: its unread playlist keeps all of its rows.
    val unread = listings.count { !it.complete }
    return missing to if (unread > 0) "$unread account(s) with $kind they couldn't read keep theirs" else null
}

/**
 * The joint hide, once per sync after every Spotify account's pass (plan 2026-11-04 §2.4): hides
 * the rows [spotifyRowsToHide] picks. It replaces the hides that used to run inside the one
 * account's pass (the playlist walk's and the saved-albums step's), which, with two accounts,
 * would each hide the other account's whole library.
 *
 * The "Undo last sync" restore point is taken once, before the first row is hidden
 * ([captureUndoPoint]; the diff keeps it rather than taking its own), so Undo brings back every
 * account's rows. Rows are only ever hidden (`is_active = 0`), never deleted: a hidden row comes
 * back the moment an account lists it again.
 *
 * Reads the rows once and writes by id, so a library of any size binds within SQLite's limits.
 */
suspend fun hideWhatNoSpotifyAccountListed(
    playlistDao: PlaylistDao,
    syncUndoDao: SyncUndoDao,
    syncLog: SyncLog,
    syncId: Long,
    syncMode: SyncMode?,
    listings: SpotifySyncListings,
): SpotifyHideOutcome {
    // Nothing listed at all: no read needed.
    if (listings.playlists.isEmpty() && listings.albums.isEmpty()) return SpotifyHideOutcome()
    val plan = spotifyRowsToHide(playlistDao.getActivePlaylistsBySource(MusicSource.SPOTIFY), syncMode, listings)
    plan.playlistsKept?.let { if (listings.playlists.isNotEmpty()) Log.i(TAG, "joint hide: kept Spotify playlists ($it)") }
    plan.albumsKept?.let { if (listings.albums.isNotEmpty()) Log.i(TAG, "joint hide: kept Spotify saved albums ($it)") }
    if (plan.isEmpty) return SpotifyHideOutcome()

    // The first change this sync makes to the library: Undo must restore from before it.
    captureUndoPoint(syncUndoDao, syncLog, syncId, TAG)
    val hiddenPlaylists = plan.playlists.mapValues { (_, ids) -> playlistDao.deactivateByIdsChunked(ids) }
    val hiddenAlbums = plan.albums.mapValues { (_, ids) -> playlistDao.deactivateByIdsChunked(ids) }

    val playlistsHidden = hiddenPlaylists.values.sum()
    val albumsHidden = hiddenAlbums.values.sum()
    if (playlistsHidden > 0) {
        Log.i(TAG, "Deactivated $playlistsHidden missing Spotify custom playlist(s)")
        syncLog.warn("Hid $playlistsHidden Spotify playlist(s) no longer in your account")
    }
    if (albumsHidden > 0) {
        syncLog.warn("Took $albumsHidden album${if (albumsHidden == 1) "" else "s"} you un-saved on Spotify out of Albums")
    }
    Log.i(
        TAG,
        "joint hide: playlists=$playlistsHidden albums=$albumsHidden " +
            "accounts(playlists)=${listings.playlists.size} accounts(albums)=${listings.albums.size}",
    )
    return SpotifyHideOutcome(hiddenPlaylists.filterValues { it > 0 }, hiddenAlbums.filterValues { it > 0 })
}

private const val TAG = "StashSync"

/** The key prefix of a saved album the Spotify sync files ([SavedAlbums.sourceId] for "spotify"). */
private const val SPOTIFY_ALBUM_KEY_PREFIX = "album:spotify:"

/**
 * Whether [sourceId] is a saved album's key as the Spotify sync files it (`album:spotify:<id>`), so
 * measured by the album listings; any other Spotify playlist key goes by the playlist listings.
 */
internal fun isSpotifySavedAlbumKey(sourceId: String): Boolean =
    sourceId.startsWith(SPOTIFY_ALBUM_KEY_PREFIX, ignoreCase = true)
