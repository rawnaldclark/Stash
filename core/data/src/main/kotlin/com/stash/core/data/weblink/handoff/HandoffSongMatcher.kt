package com.stash.core.data.weblink.handoff

import android.util.Log
import androidx.room.withTransaction
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
     * held for long. Same result as [match] one by one.
     */
    suspend fun matchAll(songs: List<WireSong?>, mirror: Boolean = false): List<TrackEntity?> = withContext(Dispatchers.IO) {
        val out = ArrayList<TrackEntity?>(songs.size)
        for (chunk in songs.chunked(BATCH)) {
            val rows = try {
                database.withTransaction { chunk.map { s -> s?.let { matchOne(it, mirror) } } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a batch of handoff songs failed: ${e.javaClass.simpleName}")
                // The mirror never writes a list it couldn't match: the run stops and tries again (Phase 6 review B2).
                if (mirror) throw MirrorMatchException(e)
                chunk.map { null }
            }
            out += rows
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
    private suspend fun matchOne(song: WireSong, mirror: Boolean = false): TrackEntity? {
        if (song.phoneOnly) return if (mirror) byWords(song) else null
        if (!mirror) {
            return song.isrc?.let { trackDao.findByIsrc(it) }
                ?: song.youtubeId?.let { trackDao.findByYoutubeId(it) }
                ?: song.spotifyId?.let { trackDao.findBySpotifyUri("spotify:track:$it") }
                ?: byWords(song)
                ?: trackDao.getById(musicRepository.ensureExactTrackPersisted(song.toSharedTrack()))
        }
        var isrcTaken = false
        var youtubeTaken = false
        var spotifyTaken = false
        song.isrc?.let { trackDao.findByIsrc(it) }?.let { if (sameRecording(it, song)) return it else isrcTaken = true }
        song.youtubeId?.let { trackDao.findByYoutubeId(it) }?.let { if (sameRecording(it, song)) return it else youtubeTaken = true }
        song.spotifyId?.let { trackDao.findBySpotifyUri("spotify:track:$it") }?.let { if (sameRecording(it, song)) return it else spotifyTaken = true }
        byWords(song)?.let { return it }
        // None of the ids left names a row (each was looked up above), so this inserts a new row and backfills nothing.
        val own = song.copy(
            isrc = song.isrc.takeUnless { isrcTaken },
            refs = if (youtubeTaken) emptyMap() else song.refs,
            spotifyId = song.spotifyId.takeUnless { spotifyTaken },
        )
        return trackDao.getById(musicRepository.ensureExactTrackPersisted(own.toSharedTrack()))?.takeIf { sameRecording(it, song) }
    }

    /** The library's row with the same words, checked with sync-v1 identity (an ISRC on both sides must agree). */
    private suspend fun byWords(song: WireSong): TrackEntity? {
        val row = trackDao.findByCanonicalIdentity(canonical(song.title), canonical(song.artist)) ?: return null
        return row.takeIf { SongKey.sameSong(SongRef(it.title, it.artist, it.isrc), song) }
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

        /** The library's `canonical_title` / `canonical_artist` form (MusicRepositoryImpl's canonicalizeIdentity). */
        private fun canonical(s: String): String = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
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
