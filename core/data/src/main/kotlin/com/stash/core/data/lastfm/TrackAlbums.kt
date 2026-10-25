package com.stash.core.data.lastfm

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.Track
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The album of a song whose own metadata has none: a Last.fm playlist's or a mix's stream, which
 * arrives with only a title and an artist (29% of one real library, 2026-10-25). Last.fm's
 * `track.getInfo` names the album ("Police Truck": Give Me Convenience or Give Me Death).
 */
interface TrackAlbums {
    /**
     * [track] with its album (and album artist, when it had none) from Last.fm when it has no
     * album of its own, [track] itself otherwise or when Last.fm doesn't know. A library row gets
     * the album too, filled only where blank.
     */
    suspend fun withAlbum(track: Track): Track

    /** Nothing looked up: the default of a ViewModel under test. */
    object None : TrackAlbums {
        override suspend fun withAlbum(track: Track): Track = track
    }
}

@Singleton
class LastFmTrackAlbums @Inject constructor(
    private val lastFm: LastFmApiClient,
    private val credentials: LastFmCredentials,
    private val trackDao: TrackDao,
) : TrackAlbums {

    override suspend fun withAlbum(track: Track): Track {
        if (track.album.isNotBlank() || !credentials.isConfigured) return track
        if (LastFmLookupRules.isUnlookable(track.artist, track.title)) return track
        // The client answers repeats from its cache, misses included: one request per song.
        val result = lastFm.getTrackInfo(
            artist = LastFmLookupRules.cleanArtist(track.artist),
            title = LastFmLookupRules.cleanTitle(track.title),
        )
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        val info = result.getOrNull() ?: return track
        val album = info.albumTitle?.takeIf { it.isNotBlank() } ?: return track
        val albumArtist = track.albumArtist.ifBlank { info.albumArtist.orEmpty() }
        if (track.id > 0) {
            try {
                trackDao.fillMissingAlbum(track.id, album, albumArtist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The album still serves this play; the backfill fills the row later.
            }
        }
        return track.copy(album = album, albumArtist = albumArtist)
    }
}
