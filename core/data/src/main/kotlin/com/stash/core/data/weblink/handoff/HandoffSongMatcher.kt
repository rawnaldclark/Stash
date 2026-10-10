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
     * is this phone's own file, found by its words, never made. A row found by a YouTube or Spotify id is taken only when it is
     * the same song by sync-v1 identity: two songs that share a video (a remaster and its original, a placeholder id) must stay
     * two songs, or one would stand in for the other and the mirror would send the missing one back as removed. A song whose
     * ids belong to another song gets a row of its own, without those ids (it resolves by its words when played).
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
        fun agrees(t: TrackEntity?) = t?.takeIf { SongKey.sameSong(SongRef(it.title, it.artist, it.isrc), song) }
        song.isrc?.let { trackDao.findByIsrc(it) }?.let { return it } // one ISRC is one recording
        agrees(song.youtubeId?.let { trackDao.findByYoutubeId(it) })?.let { return it }
        agrees(song.spotifyId?.let { trackDao.findBySpotifyUri("spotify:track:$it") })?.let { return it }
        byWords(song)?.let { return it }
        agrees(trackDao.getById(musicRepository.ensureExactTrackPersisted(song.toSharedTrack())))?.let { return it }
        // Its ids already name another song here: a row of its own, by its words (and ISRC) only.
        return agrees(trackDao.getById(musicRepository.ensureExactTrackPersisted(song.copy(refs = emptyMap(), spotifyId = null).toSharedTrack())))
    }

    /** The library's row with the same words, checked with sync-v1 identity (an ISRC on both sides must agree). */
    private suspend fun byWords(song: WireSong): TrackEntity? {
        val row = trackDao.findByCanonicalIdentity(canonical(song.title), canonical(song.artist)) ?: return null
        return row.takeIf { SongKey.sameSong(SongRef(it.title, it.artist, it.isrc), song) }
    }

    private companion object {
        const val TAG = "WebLinkHandoff"
        const val BATCH = 200

        /** The library's `canonical_title` / `canonical_artist` form (MusicRepositoryImpl's canonicalizeIdentity). */
        fun canonical(s: String): String = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

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
