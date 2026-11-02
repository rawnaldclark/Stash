package com.stash.core.data.library

import com.stash.core.model.MusicSource

/**
 * Which service a song is from, as far as its ids can tell: what the source dot shows (green for
 * Spotify, red for YouTube), what the sync's download step picks rows by, and how the lossless
 * lookups read a row's title and length (`forLibrarySource`).
 *
 * Only one claim is ever corrected: **a song is never from Spotify without a Spotify id when it has
 * a YouTube video id.** That is the lie Now Playing told: it rebuilt every stream as a Spotify song
 * (the [MusicSource] default), and a like or a listen then saved a YouTube stream as a green Spotify
 * row (411 such rows on the owner's phone, every one with a video id and none with a Spotify id).
 *
 * Every other claim stands. A local file, a cloud song or a SoundCloud upload is its own origin, and
 * a Spotify row with neither id (a local file in a Spotify playlist) has nothing that contradicts
 * it. With no claim at all, a song is Spotify's only with a Spotify id. There is no "no source"
 * value to fall back to, and none is added: [MusicSource] is stored by name, and an older build
 * reading a name it doesn't know (after a downgrade or a backup restore) takes it for LOCAL
 * (`Converters.stringToMusicSource`), a song that only plays its own file, so a stream would stop
 * playing there.
 */
object TrackSourceRule {

    /**
     * The source [claimed] for a song, unless its ids contradict it (see [TrackSourceRule]).
     *
     * @param claimed the source a row or a player item says, or null when it says none.
     * @param spotifyUri the song's Spotify URI, if it has one.
     * @param youtubeId the song's YouTube video id, if it has one.
     */
    fun honestSource(claimed: MusicSource?, spotifyUri: String?, youtubeId: String?): MusicSource {
        val hasSpotifyId = !spotifyUri.isNullOrBlank()
        val hasVideoId = !youtubeId.isNullOrBlank()
        return when (claimed) {
            null -> if (hasSpotifyId) MusicSource.SPOTIFY else MusicSource.YOUTUBE
            MusicSource.SPOTIFY -> if (!hasSpotifyId && hasVideoId) MusicSource.YOUTUBE else MusicSource.SPOTIFY
            else -> claimed
        }
    }
}
