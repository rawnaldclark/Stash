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

    /**
     * Whether [row], found by its words, is a copy of the incoming song already on this phone, so
     * the song is never downloaded a second time: it [holdsFile], it is the same version
     * ([isSameSong], with the incoming ids left out on purpose), and the lengths agree
     * ([lengthsAgree]).
     *
     * The ids don't count here, unlike for a row's identity ([mayReuseByWords]): another upload of
     * a song you have is still that song. With lossless on, audio is fetched by title and artist,
     * not by video, so a second upload of a song (or a Spotify row next to a YouTube one) downloaded
     * the same recording again under a second row. A SoundCloud upload is never matched by words
     * ([isSameSong]'s SoundCloud rule): pass its [soundCloudId].
     *
     * @param title the incoming song's raw title.
     * @param durationMs the incoming song's length; 0 when unknown.
     */
    fun isLibraryCopy(
        row: TrackEntity,
        title: String,
        durationMs: Long,
        soundCloudId: String? = null,
    ): Boolean = holdsFile(row) &&
        isSameSong(row, title, youtubeId = null, spotifyUri = null, soundCloudId = soundCloudId) &&
        lengthsAgree(row.durationMs, durationMs)

    /**
     * Whether a row of [rowMs] and an incoming song of [incomingMs] can be one recording: within
     * 10 s or 5 % of the row's length, or either length unknown (0). A same-titled track of clearly
     * different length (two "Interlude"s by one artist, a music video with a long intro) is another
     * track.
     */
    fun lengthsAgree(rowMs: Long, incomingMs: Long): Boolean {
        if (rowMs <= 0L || incomingMs <= 0L) return true
        return kotlin.math.abs(rowMs - incomingMs) <= maxOf(LENGTH_SLACK_MS, rowMs / LENGTH_SLACK_DIVISOR)
    }

    /** The oldest of [rows] that [isLibraryCopy] calls a copy of the incoming song, other than [excludeTrackId]. */
    fun libraryCopyAmong(
        rows: List<TrackEntity>,
        title: String,
        durationMs: Long,
        soundCloudId: String? = null,
        excludeTrackId: Long? = null,
    ): TrackEntity? = rows
        .sortedBy { it.id }
        .firstOrNull { it.id != excludeTrackId && isLibraryCopy(it, title, durationMs, soundCloudId) }

    /** Two ids disagree only when both are present and differ. */
    private fun idsAgree(stored: String?, incoming: String?): Boolean =
        stored.isNullOrBlank() || incoming.isNullOrBlank() || stored == incoming

    /** [lengthsAgree]: at least 10 s apart… */
    private const val LENGTH_SLACK_MS = 10_000L

    /** …or 5 % of the row's length, whichever is more. */
    private const val LENGTH_SLACK_DIVISOR = 20L
}

/**
 * The library's own copy of an incoming song, when the phone already has one: the same video's row
 * when it holds a file, else the oldest row with the same canonical words that
 * [SameSongRule.isLibraryCopy] calls a copy. One check for every path that would otherwise download
 * the song again: Search, a tap on Download, a playlist's Download switch and a sync's new songs.
 * Null when there is none, or the title or artist has no canonical words.
 *
 * "Holds a file" is the row's own record ([SameSongRule.holdsFile]); the startup repair sweep clears
 * that record when a file goes missing.
 *
 * @param excludeTrackId the incoming song's own row, when it has one: it is never its own copy.
 */
suspend fun TrackDao.findLibraryCopy(
    title: String,
    artist: String,
    durationMs: Long,
    youtubeId: String? = null,
    soundCloudId: String? = null,
    excludeTrackId: Long? = null,
): TrackEntity? {
    youtubeId?.takeIf { it.isNotBlank() }
        ?.let { findByYoutubeId(it) }
        ?.takeIf { it.id != excludeTrackId && SameSongRule.holdsFile(it) }
        ?.let { return it }
    val canonicalTitle = TrackCanonical.title(title)
    val canonicalArtist = TrackCanonical.artist(artist)
    if (TrackCanonical.isBlankKey(canonicalTitle, canonicalArtist)) return null
    return SameSongRule.libraryCopyAmong(
        rows = findAllByCanonicalIdentity(canonicalTitle, canonicalArtist),
        title = title,
        durationMs = durationMs,
        soundCloudId = soundCloudId,
        excludeTrackId = excludeTrackId,
    )
}

/**
 * For each of [rows] (library rows, with their ids), the phone's own copy of its song in another
 * row, when there is one ([SameSongRule.isLibraryCopy]): [findLibraryCopy] for a list, its
 * candidates read once ([TrackDao.findHoldingFileByCanonicalIdentities]: index-backed, chunked for
 * the bind cap). Keyed by row id; a row with no copy, or no canonical words, is absent.
 *
 * The same-video step of [findLibraryCopy] is left out: a row's video id is unique, so no other row
 * holds it.
 */
suspend fun TrackDao.libraryCopiesOf(rows: List<TrackEntity>): Map<Long, TrackEntity> {
    if (rows.isEmpty()) return emptyMap()
    val candidates = findHoldingFileByCanonicalIdentities(rows.map { it.canonicalTitle to it.canonicalArtist })
        .groupBy { it.canonicalTitle to it.canonicalArtist }
    val copies = HashMap<Long, TrackEntity>()
    for (row in rows) {
        SameSongRule.libraryCopyAmong(
            rows = candidates[row.canonicalTitle to row.canonicalArtist].orEmpty(),
            title = row.title,
            durationMs = row.durationMs,
            soundCloudId = row.soundCloudId,
            excludeTrackId = row.id,
        )?.let { copies[row.id] = it }
    }
    return copies
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
