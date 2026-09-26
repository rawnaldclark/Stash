package com.stash.core.model.share

import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The portable description of a song that any Stash can turn into playable audio
 * (spec §2). Short JSON keys keep shared documents and `/t` links small. Everything
 * except title and artist is optional; ISRC is what lets lossless match the exact recording.
 */
@Serializable
data class SharedTrack(
    @SerialName("t") val title: String,
    @SerialName("a") val artist: String,
    @SerialName("al") val album: String? = null,
    @SerialName("d") val durationMs: Long? = null,
    @SerialName("isrc") val isrc: String? = null,
    @SerialName("sp") val spotifyId: String? = null,
    @SerialName("yt") val youtubeId: String? = null,
    /** Listen Together only: the member id of whoever added the song. Never set for shared mixes (null is left out). */
    @SerialName("by") val addedBy: String? = null,
    /**
     * Listen Together only: the cover the adder's phone shows, an https link on [ShareConfig.COVER_HOSTS]
     * (the room drops any other). Never set for shared mixes.
     */
    @SerialName("art") val artUrl: String? = null,
)

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** `spotify:track:ID` or `https://open.spotify.com/track/ID?…` → `ID`. */
fun spotifyTrackId(uri: String?): String? {
    val u = uri.clean() ?: return null
    return when {
        u.startsWith("spotify:track:") -> u.removePrefix("spotify:track:").clean()
        u.startsWith("https://open.spotify.com/track/") ->
            u.removePrefix("https://open.spotify.com/track/").substringBefore('?').substringBefore('/').clean()
        else -> null
    }
}

fun Track.toSharedTrack(): SharedTrack = SharedTrack(
    // Title and artist are required by links and the Worker; a local file may lack them.
    title = title.clean() ?: "Unknown title",
    artist = artist.clean() ?: "Unknown artist",
    album = album.clean(),
    durationMs = durationMs.takeIf { it > 0 },
    isrc = isrc.clean(),
    spotifyId = spotifyTrackId(spotifyUri),
    youtubeId = youtubeId.clean(),
)

/**
 * A received descriptor as a new, stream-only library track. Always [MusicSource.BOTH]
 * (spec §2, owner decision): download reconciliation only queues tracks whose source is
 * connected, and BOTH is always connected, so a followed mix downloads for anyone.
 */
fun SharedTrack.toTrack(): Track = Track(
    title = title,
    artist = artist,
    album = album.orEmpty(),
    durationMs = durationMs ?: 0,
    isrc = isrc?.takeIf { it.isNotBlank() },
    // Blank ids must stay null: youtube_id and spotify_uri are UNIQUE, so "" / "spotify:track:" would collide.
    spotifyUri = spotifyId?.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" },
    youtubeId = youtubeId?.takeIf { it.isNotBlank() },
    // A room song this phone didn't have shows the adder's cover. It came from another phone, so it's
    // checked here too, not only by the room.
    albumArtUrl = artUrl?.takeIf(ShareConfig::isAllowedCover),
    source = MusicSource.BOTH,
    isStreamable = true,
)
