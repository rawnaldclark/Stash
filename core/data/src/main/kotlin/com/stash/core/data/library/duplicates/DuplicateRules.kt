package com.stash.core.data.library.duplicates

import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.entity.TrackAliasEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.library.SameSongRule
import com.stash.core.data.library.TrackSourceRule
import com.stash.core.model.MusicSource

/**
 * Which library rows are one song, which of two rows stays, and what the row that stays looks like
 * after a merge. Pure: the database work is [TrackMerger]'s, the choice of what to merge is
 * [DuplicateCleanup]'s (certain cases, automatic) and [PossibleDuplicates]'s (the user's).
 *
 * The same-song rule is the library-copy rule from the duplicate guards
 * ([SameSongRule.isLibraryCopy], `findLibraryCopy`), without "holds a file": the same canonical
 * words, the same version and the same take ([TrackCanonical.sameTake]), explicit flags that don't
 * disagree, lengths that agree. So a clean edit and the explicit song, "(Live)", "(A COLORS SHOW)"
 * or a re-recording are never one song here either.
 *
 * Never merged, whatever the user picks: a row flagged as a wrong match (its file plays another
 * song), a local file, a cloud song, a SoundCloud upload.
 */
object DuplicateRules {

    /** Sources whose rows are their own origin and are never merged into anything. */
    private val NEVER_MERGED = setOf(MusicSource.LOCAL, MusicSource.CLOUD, MusicSource.SOUNDCLOUD)

    /** File formats that are lossless: a download in one of these is the better copy. */
    private val LOSSLESS_FORMATS = setOf("flac", "alac", "wav", "aiff")

    /**
     * An id-less stub: no video id, no Spotify id, no SoundCloud id, saved as YouTube. What Stash
     * Mix discovery filed for every rediscovered song (5,480 on the owner's phone on 10-21) and
     * what a like on a stream with no video id saved. Nothing identifies it but its words.
     */
    fun isStub(row: TrackEntity): Boolean =
        row.youtubeId.isNullOrBlank() && row.spotifyUri.isNullOrBlank() && row.soundCloudId == null &&
            row.source == MusicSource.YOUTUBE

    /** A row with a YouTube video id or a Spotify id. */
    fun isIdentified(row: TrackEntity): Boolean = !row.youtubeId.isNullOrBlank() || !row.spotifyUri.isNullOrBlank()

    /** Whether [row] may take part in a merge at all, on either side. */
    fun mayMerge(row: TrackEntity): Boolean =
        !row.matchFlagged && row.source !in NEVER_MERGED && row.soundCloudId == null

    /** Two known explicit flags that differ are two songs; unknown on either side agrees. */
    fun explicitAgree(a: TrackEntity, b: TrackEntity): Boolean =
        a.explicit == null || b.explicit == null || a.explicit == b.explicit

    /** Both [a] and [b] may be merged, with each other: two rows, both mergeable, explicit flags agreeing. */
    fun mayMergePair(a: TrackEntity, b: TrackEntity): Boolean =
        a.id != b.id && mayMerge(a) && mayMerge(b) && explicitAgree(a, b)

    /**
     * Whether [a] and [b] are certainly one song (see [DuplicateRules]): [mayMergePair], the same
     * non-blank canonical words, the same version and take in both directions, and lengths that
     * agree both ways (within 10 s or 5 %, unknown agrees). Ids and files are not compared: two
     * uploads of a song are still that song, and which row's file stays is the merge's question.
     */
    fun sameSong(a: TrackEntity, b: TrackEntity): Boolean =
        mayMergePair(a, b) &&
            a.canonicalTitle == b.canonicalTitle && a.canonicalArtist == b.canonicalArtist &&
            !TrackCanonical.isBlankKey(a.canonicalTitle, a.canonicalArtist) &&
            SameSongRule.isSameSong(a, b.title, youtubeId = null, spotifyUri = null) &&
            SameSongRule.isSameSong(b, a.title, youtubeId = null, spotifyUri = null) &&
            TrackCanonical.sameTake(a.title, b.title) && TrackCanonical.sameTake(b.title, a.title) &&
            SameSongRule.lengthsAgree(a.durationMs, b.durationMs) && SameSongRule.lengthsAgree(b.durationMs, a.durationMs)

    /**
     * Whether [a] and [b] might be one song with different version words: [mayMergePair], the same
     * non-blank canonical words, both lengths known and within [VERSION_WORDS_SLACK_MS], but not
     * [sameSong] ("Song" and "Song (Live)" of one length, a "(Remix)" that is the album cut). Only
     * the user can tell; Possible duplicates offers these and never merges them by itself.
     */
    fun mightBeSameSong(a: TrackEntity, b: TrackEntity): Boolean =
        mayMergePair(a, b) &&
            a.canonicalTitle == b.canonicalTitle && a.canonicalArtist == b.canonicalArtist &&
            !TrackCanonical.isBlankKey(a.canonicalTitle, a.canonicalArtist) &&
            a.durationMs > 0L && b.durationMs > 0L &&
            kotlin.math.abs(a.durationMs - b.durationMs) <= VERSION_WORDS_SLACK_MS &&
            !sameSong(a, b)

    /** Whether [row] has audio of its own on this phone. */
    fun holdsFile(row: TrackEntity): Boolean = SameSongRule.holdsFile(row)

    /** Whether [row]'s own file is lossless (FLAC, ALAC, WAV, AIFF). */
    fun holdsLosslessFile(row: TrackEntity): Boolean =
        holdsFile(row) && row.fileFormat.lowercase() in LOSSLESS_FORMATS

    /**
     * Which of two rows of one song stays, best first: the downloaded one, then the one whose file
     * is lossless, then the more played, then the older row (the one other places have pointed at
     * longest).
     */
    val keeperOrder: Comparator<TrackEntity> = compareByDescending<TrackEntity> { holdsFile(it) }
        .thenByDescending { holdsLosslessFile(it) }
        .thenByDescending { it.playCount }
        .thenBy { it.id }

    /**
     * The row that stays, after taking in [loser]: [keeper]'s own identity, file and words, with its
     * blanks filled from [loser], their plays added up and their likes kept. Never moves a file.
     *
     * - Ids: the keeper's own stay. An id the keeper lacks (a video id, a Spotify id, an ISRC) is
     *   taken from the loser, and with a video id its video type and canonical video too. The
     *   loser's other ids become aliases ([aliasesFor]).
     * - Source: the keeper's, unless the keeper had no id at all (an id-less stub that holds the
     *   file), when it takes the loser's: the song is that service's now. Then [TrackSourceRule]
     *   corrects a Spotify claim with no Spotify id.
     * - Plays: counts added, the later last play. Likes: the earliest Stash like; a Spotify, YouTube
     *   Music or Last.fm like on either side is kept.
     * - Album, art, length, explicit flag, MusicBrainz id: the keeper's, or the loser's when the
     *   keeper has none. Streamable when either was.
     */
    fun mergedRow(keeper: TrackEntity, loser: TrackEntity): TrackEntity {
        val keeperHadIds = isIdentified(keeper)
        val videoFromLoser = keeper.youtubeId.isNullOrBlank() && !loser.youtubeId.isNullOrBlank()
        val youtubeId = keeper.youtubeId?.takeIf { it.isNotBlank() } ?: loser.youtubeId?.takeIf { it.isNotBlank() }
        val spotifyUri = keeper.spotifyUri?.takeIf { it.isNotBlank() } ?: loser.spotifyUri?.takeIf { it.isNotBlank() }
        val claimed = if (!keeperHadIds && isIdentified(loser)) loser.source else keeper.source
        val albumFromLoser = keeper.album.isBlank() && loser.album.isNotBlank()
        return keeper.copy(
            youtubeId = youtubeId,
            spotifyUri = spotifyUri,
            musicVideoType = if (videoFromLoser) loser.musicVideoType else keeper.musicVideoType,
            ytCanonicalVideoId = if (videoFromLoser) loser.ytCanonicalVideoId else keeper.ytCanonicalVideoId,
            isrc = keeper.isrc?.takeIf { it.isNotBlank() } ?: loser.isrc?.takeIf { it.isNotBlank() },
            mbid = keeper.mbid ?: loser.mbid,
            explicit = keeper.explicit ?: loser.explicit,
            source = TrackSourceRule.honestSource(claimed, spotifyUri, youtubeId),
            album = if (albumFromLoser) loser.album else keeper.album,
            albumArtist = if (albumFromLoser) loser.albumArtist else keeper.albumArtist,
            albumArtUrl = keeper.albumArtUrl ?: loser.albumArtUrl,
            albumArtPath = keeper.albumArtPath ?: loser.albumArtPath,
            durationMs = keeper.durationMs.takeIf { it > 0L } ?: loser.durationMs,
            playCount = keeper.playCount + loser.playCount,
            lastPlayed = listOfNotNull(keeper.lastPlayed, loser.lastPlayed).maxOrNull(),
            stashLikedAt = listOfNotNull(keeper.stashLikedAt, loser.stashLikedAt).minOrNull(),
            spotifySavedAt = keeper.spotifySavedAt ?: loser.spotifySavedAt,
            ytMusicSavedAt = keeper.ytMusicSavedAt ?: loser.ytMusicSavedAt,
            lastFmLovedAt = keeper.lastFmLovedAt ?: loser.lastFmLovedAt,
            lastfmUserLoved = keeper.lastfmUserLoved || loser.lastfmUserLoved,
            lastfmUserPlaycount = keeper.lastfmUserPlaycount ?: loser.lastfmUserPlaycount,
            lastfmListeners = keeper.lastfmListeners ?: loser.lastfmListeners,
            isStreamable = keeper.isStreamable || loser.isStreamable,
        )
    }

    /**
     * The aliases a merge writes for [loser]'s ids that [merged] (the row that stays) doesn't hold:
     * its video id, Spotify id and ISRC when they differ, and always its own row id, so anything
     * still holding that id (a queued like, a saved queue) finds the song.
     */
    fun aliasesFor(loser: TrackEntity, merged: TrackEntity, now: Long): List<TrackAliasEntity> = buildList {
        loser.youtubeId?.takeIf { it.isNotBlank() && it != merged.youtubeId }
            ?.let { add(TrackAliasEntity(TrackAliasEntity.KIND_YOUTUBE_ID, it, merged.id, now)) }
        loser.spotifyUri?.takeIf { it.isNotBlank() && it != merged.spotifyUri }
            ?.let { add(TrackAliasEntity(TrackAliasEntity.KIND_SPOTIFY_URI, it, merged.id, now)) }
        loser.isrc?.takeIf { it.isNotBlank() && it != merged.isrc }
            ?.let { add(TrackAliasEntity(TrackAliasEntity.KIND_ISRC, it, merged.id, now)) }
        add(TrackAliasEntity(TrackAliasEntity.KIND_TRACK_ID, loser.id.toString(), merged.id, now))
    }

    /** "Different version words" pairs: both lengths within this of each other. */
    const val VERSION_WORDS_SLACK_MS = 10_000L
}
