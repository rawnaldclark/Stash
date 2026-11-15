package com.stash.ext.ytmusic.radio

import com.stash.core.auth.TokenManager
import com.stash.core.data.radio.RadioEngine
import com.stash.core.data.radio.RadioEngineId
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioStation
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.TrackSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stations from YouTube Music's own algorithm.
 *
 * The Stash engine answers "what is similar to this?" from a Last.fm tag graph; this one
 * asks YouTube, which is the only party with a real recommender behind it. A song's
 * station is `/next` with `playlistId=RDAMVM<videoId>`, an artist's is the same endpoint
 * with the `RDEM…` id the artist page's radio button carries, and every page after the
 * first is a continuation token — so a station here is nothing but that token.
 *
 * Everything a station emits is an ordinary streaming [Track] with a real `youtubeId`,
 * using the same synthetic-id convention the Last.fm engine uses (`videoId.hashCode()`),
 * so `PlayerRepository`'s queue identity, the resolver and the download pipeline all see
 * exactly the shape they already handle.
 */
@Singleton
class YouTubeMusicRadioEngine @Inject constructor(
    private val yt: YTMusicApiClient,
    private val tokenManager: TokenManager,
) : RadioEngine {

    override val id: RadioEngineId = RadioEngineId.YOUTUBE_MUSIC

    /**
     * A station is generated for the listener, which is the entire reason to prefer this
     * engine — so it needs the account's session, and the check is the stored cookie
     * rather than a request: [RadioEngines] asks this before every start, and a network
     * round-trip there would put latency in front of playing music. Without an account
     * the engines fall back to Stash's, which is what a fresh install should do.
     */
    override suspend fun available(): Boolean =
        !tokenManager.getYouTubeCookie().isNullOrBlank()

    override suspend fun start(seed: RadioSeed): Pair<RadioStation, List<Track>> {
        val page = when (seed) {
            is RadioSeed.Song -> startSong(seed)
            is RadioSeed.Artist -> startArtist(seed)
        } ?: return EmptyStation to emptyList()
        val station = ContinuationStation(yt, page.continuation)
        return station to page.tracks.map { it.toTrack() }
    }

    private suspend fun startSong(seed: RadioSeed.Song) = runCatching {
        val videoId = seed.videoId() ?: return@runCatching null
        yt.startRadio(videoId)
    }.getOrNull()

    /**
     * An artist station is seeded exactly like the web player's: the page's radio button
     * names the seed video, the `RDEM…` playlist and the scoping `params`. An artist page
     * without that button (or a page that didn't answer) falls back to a station off the
     * artist's most popular song, which still gives a station rather than an error.
     */
    private suspend fun startArtist(seed: RadioSeed.Artist) = runCatching {
        val browseId = seed.ytBrowseId ?: yt.resolveArtist(seed.name)?.id ?: return@runCatching null
        val endpoint = yt.artistRadioEndpoint(browseId)
        if (endpoint != null) {
            return@runCatching yt.startRadio(endpoint.videoId, endpoint.playlistId, endpoint.params)
        }
        val popular = yt.getArtist(browseId).popular.firstOrNull() ?: return@runCatching null
        yt.startRadio(popular.videoId)
    }.getOrNull()

    /**
     * The seed's video id. A `RadioSeed.Song` often carries none: it is built from a
     * library row, and a Spotify-sourced row has never been resolved to a video. The
     * canonical search is the same one the Last.fm engine uses for its own candidates,
     * and it prefers a music-catalog upload, which is what a station seeds best from.
     *
     * No match means no station — deliberately, rather than seeding off a guessed id: a
     * station built from the wrong recording is worse than none.
     */
    private suspend fun RadioSeed.Song.videoId(): String? =
        ytVideoId?.takeIf { it.isNotBlank() }
            ?: yt.searchCanonicalMatch(artist, title)?.videoId

    /** A station with nothing behind it: `start` returns an empty batch, which the player reads as NoStation. */
    private object EmptyStation : RadioStation {
        override suspend fun nextBatch(): List<Track> = emptyList()
    }

    /**
     * The station as the player sees it: the next page's token, and nothing else. YouTube
     * has already de-duplicated the station, so unlike the Last.fm engine there is no
     * no-repeat set to keep here; a page repeating a song is skipped locally anyway, since
     * the player's queue identity would otherwise hold the same song twice.
     */
    private class ContinuationStation(
        private val yt: YTMusicApiClient,
        private var continuation: String?,
    ) : RadioStation {

        override suspend fun nextBatch(): List<Track> {
            val token = continuation ?: return emptyList()
            val page = runCatching { yt.continueRadio(token) }.getOrNull() ?: return emptyList()
            continuation = page.continuation
            return page.tracks.map { it.toTrack() }
        }
    }

    private companion object {
        fun TrackSummary.toTrack() = Track(
            id = videoId.hashCode().toLong(),
            title = title,
            artist = artist,
            album = album.orEmpty(),
            durationMs = (durationSeconds * 1000).toLong(),
            albumArtUrl = thumbnailUrl ?: youtubeThumbnail(videoId),
            youtubeId = videoId,
            source = MusicSource.YOUTUBE,
            isStreamable = true,
        )

        /**
         * The frame YouTube serves for a videoId, for a row that carried no thumbnail:
         * `maxresdefault` is the full 16:9 frame without the black bars `hq`/`sd` pad in,
         * so it centre-crops to a square cleanly. Same reason and same URL shape as the
         * Last.fm engine's fallback.
         */
        fun youtubeThumbnail(videoId: String): String? =
            videoId.takeIf { com.stash.core.model.YouTubeVideoId.isValid(it) }
                ?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
    }
}
