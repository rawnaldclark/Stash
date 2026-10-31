package com.stash.core.data.library

import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity

/**
 * When a library row found by its canonical words, not by an id, may be taken as the row for an
 * incoming song. One rule for every path that does that: Search downloads, add-to-playlist from
 * Search, a like on a stream, [com.stash.core.data.repository.MusicRepository.ensureTrackPersisted],
 * discovery, and the sync's own canonical fallback, so no path files a song another path would
 * have found, and none takes a row another path would have kept apart.
 *
 * The words ([TrackCanonical]) are loose on purpose: "Song (Live)" and "Song" share them, and so do
 * two different uploads of one song. So a row found by words is taken only when
 *  1. **no id disagrees**: the row has no YouTube video id or the same one, and no Spotify URI or
 *     the same one. An id only one side has never disagrees (a Spotify stub has no video id yet);
 *  2. **it is the same version**: [TrackCanonical.sameVersion] on the two raw titles, a deny-list:
 *     only a performance word one names and the other doesn't (live, remix, acoustic, sped up...)
 *     keeps them apart. So "Song (Live)" or "Song (Remix)" never lands on "Song", while
 *     "STAY (with Justin Bieber)", "PRBLMS [Official Music Video]", "Song (2011 Remaster)" and
 *     "Song (Clean)" still find their song. For people who sync Spotify and YouTube this word
 *     match is the only link between the two services' rows, so it must not split one song;
 *  3. **for a download, the row holds no file**: a row that already has audio is another copy, so
 *     the new download gets its own row rather than replacing that file. Search checks first and
 *     downloads nothing when such a row is the song ("Already in your library"), so this only
 *     matters for a copy that lands while the download runs.
 *
 * Callers look up the incoming song's own ids first, so the same video (or Spotify track) always
 * finds its row before this rule is consulted.
 */
object SameSongRule {

    /**
     * Whether [row], found by words, is the incoming song: rules 1 and 2.
     *
     * @param row the library row with the same canonical title and artist.
     * @param title the incoming song's raw title.
     * @param youtubeId the incoming song's video id, if it has one.
     * @param spotifyUri the incoming song's Spotify URI (`spotify:track:…`), if it has one.
     */
    fun isSameSong(
        row: TrackEntity,
        title: String,
        youtubeId: String?,
        spotifyUri: String?,
        soundCloudId: String? = null,
    ): Boolean =
        // SoundCloud uploads (especially remixes) must never be identified by loose words.
        (if (row.soundCloudId != null || soundCloudId != null) row.soundCloudId == soundCloudId else true) &&
            idsAgree(row.youtubeId, youtubeId) &&
            idsAgree(row.spotifyUri, spotifyUri) &&
            TrackCanonical.sameVersion(row.title, title)

    /**
     * Whether [row], found by words, may stand for an incoming song: [isSameSong], and for a
     * download a row with no file of its own (rule 3).
     *
     * @param forDownload true when the caller is about to record a downloaded file on the row.
     */
    fun mayReuseByWords(
        row: TrackEntity,
        title: String,
        youtubeId: String?,
        spotifyUri: String?,
        forDownload: Boolean,
        soundCloudId: String? = null,
    ): Boolean = isSameSong(row, title, youtubeId, spotifyUri, soundCloudId) && (!forDownload || !holdsFile(row))

    /** Whether [row] has audio of its own on this phone. */
    fun holdsFile(row: TrackEntity): Boolean = row.isDownloaded || !row.filePath.isNullOrBlank()

    /** Two ids disagree only when both are present and differ. */
    private fun idsAgree(stored: String?, incoming: String?): Boolean =
        stored.isNullOrBlank() || incoming.isNullOrBlank() || stored == incoming
}

/**
 * Every row with the incoming song's canonical words that [SameSongRule.isSameSong] calls that song,
 * oldest first. None when the title or artist has no canonical words (a title that is only a
 * bracket, a blank artist): a blank key would match every other blank row.
 *
 * Index-backed: `findAllByCanonicalIdentity` filters on `(canonical_title, canonical_artist)`.
 */
suspend fun TrackDao.findSameSongByWords(
    title: String,
    artist: String,
    youtubeId: String?,
    spotifyUri: String?,
): List<TrackEntity> {
    val canonicalTitle = TrackCanonical.title(title)
    val canonicalArtist = TrackCanonical.artist(artist)
    if (TrackCanonical.isBlankKey(canonicalTitle, canonicalArtist)) return emptyList()
    return findAllByCanonicalIdentity(canonicalTitle, canonicalArtist).filter {
        SameSongRule.isSameSong(it, title, youtubeId, spotifyUri)
    }
}

/**
 * The oldest row [SameSongRule] lets an incoming song take by its words ([findSameSongByWords], and
 * for a download one with no file of its own), or null.
 */
suspend fun TrackDao.findReusableByWords(
    title: String,
    artist: String,
    youtubeId: String?,
    spotifyUri: String?,
    forDownload: Boolean,
): TrackEntity? = findSameSongByWords(title, artist, youtubeId, spotifyUri).firstOrNull {
    !forDownload || !SameSongRule.holdsFile(it)
}
