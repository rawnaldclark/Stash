package com.stash.core.data.db.dao

/*
 * The fingerprint behind a Spotify playlist's mirror stamp
 * (com.stash.core.data.sync.workers.SpotifyMirrorStamp). Its row types live
 * here, in the DAO package, because PlaylistDao's queries return them.
 */

/**
 * A Spotify playlist that carries a mirror stamp, for the fetch step's skip
 * decision. See [PlaylistDao.stampedSpotifyPlaylists].
 */
data class StampedSpotifyPlaylist(
    val id: Long,
    val sourceId: String,
    val stamp: String,
    val trackCount: Int,
)

/** A playlist's stored song count, for [PlaylistDao.mirrorDigests]. */
data class PlaylistTrackCount(val id: Long, val trackCount: Int)

/** A [MirrorCopy] with the playlist it was found for ([PlaylistDao.mirrorCopiesOf]). */
data class PlaylistMirrorCopy(
    val playlistId: Long,
    val id: Long,
    val downloaded: Boolean,
    val hasFile: Boolean,
    val matchDismissed: Boolean,
    val title: String,
    val spotifyUri: String?,
    val youtubeId: String?,
) {
    fun toMirrorCopy(): MirrorCopy = MirrorCopy(id, downloaded, hasFile, matchDismissed, title, spotifyUri, youtubeId)
}

/** One `playlist_tracks` row as the mirror digest sees it. See [PlaylistDao.mirrorRows]. */
data class MirrorRow(
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
    val removed: Boolean,
    val locallyAdded: Boolean,
    /** The song has a FAILED download, which the full diff may close. */
    val failed: Boolean,
)

/**
 * One library row sharing canonical words with a song of the playlist (the
 * songs themselves included), with every column the full diff's
 * downloaded-copy linking and by-words matching read. See
 * [PlaylistDao.mirrorCopies].
 */
data class MirrorCopy(
    val id: Long,
    val downloaded: Boolean,
    val hasFile: Boolean,
    val matchDismissed: Boolean,
    val title: String,
    val spotifyUri: String?,
    val youtubeId: String?,
)

/**
 * A playlist's state as one short fingerprint, for the mirror stamp: SHA-256,
 * cut to 128 bits of hex, over
 *  - its stored song count;
 *  - every `playlist_tracks` row (live, removed, added here) as its exact
 *    (track, position, removed, added here, failed download), in track order;
 *  - its songs' copies ([MirrorCopy]: every library row with the same canonical
 *    words as one of its songs) as (id, downloaded, has a file, dismissed,
 *    title, Spotify id, YouTube id), in id order.
 *
 * Whatever a full sync would read differently here changes it, whatever code
 * path made the change: an add, a removal, a deleted or blocked song, a
 * re-order or recount; a song's copy downloaded, un-downloaded or new; a
 * dismissed match; a retitled row or a newly resolved id; a download that
 * failed. The stamp then stops matching and the next sync reads the playlist
 * in full, so the normal diff acts exactly as it always did. Exact, not
 * summed (sums let changes cancel out); text fields are length-prefixed, so
 * no title can forge a field boundary. The inputs' order doesn't matter.
 */
fun mirrorDigestOf(trackCount: Int, rows: List<MirrorRow>, copies: List<MirrorCopy>): String {
    fun StringBuilder.flag(b: Boolean) = append(',').append(if (b) 1 else 0)
    fun StringBuilder.text(t: String?) = append(',').append(t?.length ?: -1).append(':').append(t.orEmpty())
    val text = buildString {
        append(trackCount)
        rows.sortedBy { it.trackId }.forEach { r ->
            append(';').append(r.trackId).append(',').append(r.position)
            flag(r.removed); flag(r.locallyAdded); flag(r.failed)
        }
        append('|')
        copies.distinctBy { it.id }.sortedBy { it.id }.forEach { c ->
            append(';').append(c.id)
            flag(c.downloaded); flag(c.hasFile); flag(c.matchDismissed)
            text(c.title); text(c.spotifyUri); text(c.youtubeId)
        }
    }
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return hash.take(16).joinToString("") { "%02x".format(it) }
}
