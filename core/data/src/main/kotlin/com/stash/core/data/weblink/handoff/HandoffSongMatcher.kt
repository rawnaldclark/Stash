package com.stash.core.data.weblink.handoff

import android.util.Log
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
) {
    suspend fun match(song: WireSong): TrackEntity? = withContext(Dispatchers.IO) {
        if (song.phoneOnly) return@withContext null
        try {
            song.isrc?.let { trackDao.findByIsrc(it) }
                ?: song.youtubeId?.let { trackDao.findByYoutubeId(it) }
                ?: song.spotifyId?.let { trackDao.findBySpotifyUri("spotify:track:$it") }
                ?: byWords(song)
                ?: trackDao.getById(musicRepository.ensureExactTrackPersisted(song.toSharedTrack()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "no row for a handoff song", e)
            null
        }
    }

    /** The library's row with the same words, checked with sync-v1 identity (an ISRC on both sides must agree). */
    private suspend fun byWords(song: WireSong): TrackEntity? {
        val row = trackDao.findByCanonicalIdentity(canonical(song.title), canonical(song.artist)) ?: return null
        return row.takeIf { SongKey.sameSong(SongRef(it.title, it.artist, it.isrc), song) }
    }

    private companion object {
        const val TAG = "WebLinkHandoff"

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
