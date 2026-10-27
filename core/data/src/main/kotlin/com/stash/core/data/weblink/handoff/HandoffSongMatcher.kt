package com.stash.core.data.weblink.handoff

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.weblink.SongKey
import com.stash.core.model.weblink.SongRef
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A song from another device → this phone's library row (spec §3 "Matching on the phone", §8.3): the ISRC first, then the
 * YouTube and Spotify ids, then the same words (`sameSong`: never two different ISRCs), and only then a new stream-only row
 * through Listen Together's exact persist ([MusicRepository.ensureExactTrackPersisted]). A song this phone can't hold (only on
 * another phone, or the lookup failed) is null: the restore leaves it out.
 */
class HandoffSongMatcher @Inject constructor(
    private val trackDao: TrackDao,
    private val musicRepository: MusicRepository,
    private val database: StashDatabase,
) {
    /**
     * [songs] matched in order, in transactions of [BATCH] songs: the stream-only rows a batch inserts invalidate the library
     * once per batch, not once per song (every library screen re-queries on each invalidation), and the write lock is never
     * held for long. Same result as [match] one by one. [onBatch] is told each batch's size once it is matched (an import's
     * progress; it may throw to stop the call).
     */
    suspend fun matchAll(
        songs: List<WireSong?>,
        mirror: Boolean = false,
        identities: MirrorIdentities? = null,
        onBatch: (Int) -> Unit = {},
    ): List<TrackEntity?> = withContext(Dispatchers.IO) {
        val out = ArrayList<TrackEntity?>(songs.size)
        // The song each row was taken for in this call: a row stands for one song only (final check F-B1).
        val claims = HashMap<Long, WireSong>()
        for (chunk in songs.chunked(BATCH)) {
            val rows = try {
                database.withTransaction {
                    chunk.map { s ->
                        s?.let { song ->
                            if (!mirror) return@let matchOne(song)
                            matchMirror(song, Claims(claims, identities))?.also { claims.putIfAbsent(it.id, song) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a batch of handoff songs failed: ${e.javaClass.simpleName}")
                // The mirror never writes a list it couldn't match: the run stops and tries again (Phase 6 review B2).
                if (mirror) throw MirrorMatchException(e)
                chunk.map { null }
            }
            out += rows
            onBatch(chunk.size)
        }
        out
    }

    suspend fun match(song: WireSong): TrackEntity? = withContext(Dispatchers.IO) {
        try {
            matchOne(song)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "no row for a handoff song", e)
            null
        }
    }

    /**
     * [mirror]: the stricter matching a mirrored like, play or playlist needs (link-sync spec §3, sync-v1 §2.2). A `phoneOnly` song
     * is this phone's own file, found by its words, never made. A row found by its ISRC, YouTube id or Spotify id is taken only
     * when it is the same song ([sameRecording]): two songs that share a video (a placeholder id, a remaster and its original)
     * must stay two songs, or one would stand in for the other and the mirror would send the missing one back as removed. A
     * song whose ids already name another song gets a row of its own **without those ids**, made through a lookup that can't
     * hit that other row, so nothing of this song (its ISRC above all) is ever written onto it (Phase 6 review B1).
     * Without [mirror], a `phoneOnly` song has no row here (a handoff leaves it out).
     */
    private suspend fun matchOne(song: WireSong): TrackEntity? {
        if (song.phoneOnly) return null
        return song.isrc?.let { trackDao.findByIsrc(it) }
            ?: song.youtubeId?.let { trackDao.findByYoutubeId(it) }
            ?: song.spotifyId?.let { trackDao.findBySpotifyUri("spotify:track:$it") }
            ?: byWords(song)
            ?: trackDao.getById(musicRepository.ensureExactTrackPersisted(song.toSharedTrack()))
    }

    /** What the mirror already knows about rows: within one call ([claims]) and from before ([identities]). */
    private class Claims(val claims: Map<Long, WireSong>, val identities: MirrorIdentities?) {
        /** The song [row] already stands for in the mirror under other words than its own, else null (its own words). */
        fun standsFor(row: TrackEntity): WireSong? = claims[row.id] ?: identities?.aliasOf(row)

        /**
         * [row] may stand for [song]: it already does, or it has no other identity and it is [song] by its words; or, for a
         * row found by an id, it is the same recording under slightly different words (review S4) and has no mirror history
         * at all (never liked, played, or listed), so taking the song's identity rewrites nothing (final check F-B1).
         */
        suspend fun fits(row: TrackEntity, song: WireSong, byId: Boolean): Boolean {
            standsFor(row)?.let { return SongKey.sameSong(it, song) }
            if (SongKey.sameSong(SongRef(row.title, row.artist, row.isrc), song)) return true
            return byId && identities != null && sameRecording(row, song) && !identities.hasHistory(row.id)
        }
    }

    private suspend fun matchMirror(song: WireSong, c: Claims): TrackEntity? {
        if (song.phoneOnly) return byWords(song, c)
        var isrcTaken = false
        var youtubeTaken = false
        var spotifyTaken = false
        song.isrc?.let { trackDao.findByIsrc(it) }?.let { if (c.fits(it, song, byId = true)) return it else isrcTaken = true }
        song.youtubeId?.let { trackDao.findByYoutubeId(it) }?.let { if (c.fits(it, song, byId = true)) return it else youtubeTaken = true }
        song.spotifyId?.let { trackDao.findBySpotifyUri("spotify:track:$it") }?.let { if (c.fits(it, song, byId = true)) return it else spotifyTaken = true }
        byWords(song, c)?.let { return it }
        // None of the ids left names a row (each was looked up above), so this inserts a new row and backfills nothing.
        val own = song.copy(
            isrc = song.isrc.takeUnless { isrcTaken },
            refs = if (youtubeTaken) emptyMap() else song.refs,
            spotifyId = song.spotifyId.takeUnless { spotifyTaken },
        )
        return trackDao.getById(musicRepository.ensureExactTrackPersisted(own.toSharedTrack()))?.takeIf { c.fits(it, song, byId = false) }
    }

    /** [byWords] for the mirror: the first row with the same words that doesn't already stand for another song. */
    private suspend fun byWords(song: WireSong, c: Claims): TrackEntity? =
        rowsWithWordsOf(song).firstOrNull { c.fits(it, song, byId = false) }

    /**
     * The library's oldest row with the same words that is the same song by sync-v1 identity (an ISRC on both sides must
     * agree; `SongKey.fold` keeps version words like "(Live)", so the loose canonical words never stand in for it). Every
     * row with those words is checked, not just the first: the loose words group "Song" with "Song (Live)".
     */
    private suspend fun byWords(song: WireSong): TrackEntity? =
        rowsWithWordsOf(song).firstOrNull { SongKey.sameSong(SongRef(it.title, it.artist, it.isrc), song) }

    /**
     * Every row with [song]'s canonical words, oldest first. A blank key would load every local import, so a song whose canonical
     * words are blank (a title that is only brackets, "(Intro)") is looked up by its exact words instead: matched by nothing else,
     * it got a new row each time it came (a Spotify export's liked "(Intro)" and the same "(Intro)" in a playlist were two rows).
     */
    private suspend fun rowsWithWordsOf(song: WireSong): List<TrackEntity> {
        val title = TrackCanonical.title(song.title)
        val artist = TrackCanonical.artist(song.artist)
        if (TrackCanonical.isBlankKey(title, artist)) {
            if (song.title.isBlank() || song.artist.isBlank()) return emptyList()
            return trackDao.findAllByExactWords(song.title, song.artist)
        }
        return trackDao.findAllByCanonicalIdentity(title, artist)
    }

    companion object {
        private const val TAG = "WebLinkHandoff"

        /**
         * A row found by an id is the same song unless it is clearly another one (Phase 6 review S4): two different ISRCs, or
         * titles where neither folded title contains the other, or artists that share no word. So "Song A" and "Song A -
         * Remastered 2011" by "The Band" / "The Band feat. X" are one song; "Nights" and "Pink + White" are two.
         */
        fun sameRecording(row: TrackEntity, song: WireSong): Boolean {
            if (SongKey.sameSong(SongRef(row.title, row.artist, row.isrc), song)) return true
            val a = row.isrc?.uppercase()
            val b = song.isrc?.uppercase()
            if (a != null && b != null && a != b) return false
            val ft = SongKey.fold(row.title)
            val fs = SongKey.fold(song.title)
            if (ft.isEmpty() || fs.isEmpty() || !(ft.contains(fs) || fs.contains(ft))) return false
            val wa = SongKey.fold(row.artist).split(' ').filter { it.isNotEmpty() }.toSet()
            return SongKey.fold(song.artist).split(' ').any { it.isNotEmpty() && it in wa }
        }

        private const val BATCH = 200
    }
}

/**
 * The mirror's identities of library rows (final check F-B1): a row stands for one song, and a loosely matched song (review S4)
 * may take a row only while that row has no mirror history under its own words.
 */
interface MirrorIdentities {
    /** The song [row] stands for under other words than its own (an alias, review R-S3), or null. */
    fun aliasOf(row: TrackEntity): WireSong?

    /** [trackId] was liked, played or listed here: the mirror may already know it by its own words. */
    suspend fun hasHistory(trackId: Long): Boolean
}

/** The mirror couldn't match a batch of songs (a database error): nothing of that list is written. */
class MirrorMatchException(cause: Throwable) : Exception("mirror matching failed", cause)

/** A wire song as Listen Together's descriptor, for the exact persist (cover only from the share service's hosts). */
fun WireSong.toSharedTrack() = SharedTrack(
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    isrc = isrc,
    spotifyId = spotifyId,
    youtubeId = youtubeId?.takeIf { YOUTUBE_ID.matches(it) },
    // The web lists covers smallest first; the largest allowed one (the phone shows it full screen).
    artUrl = artwork.lastOrNull { ShareConfig.isAllowedCover(it) },
)

private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
