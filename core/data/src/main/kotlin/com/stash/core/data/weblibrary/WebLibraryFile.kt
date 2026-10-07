package com.stash.core.data.weblibrary

import com.stash.core.model.Track
import com.stash.core.model.share.toSharedTrackWithArt
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The web player's library file, `stash-web-library` version 1: what "Export for Stash on the web" writes and
 * Stash on the web's Settings › Your library › "Import from the Stash app" reads. The format is frozen; its spec is
 * `docs/library-file-v1.md` in the web player's repo (stash-player). Field names and meanings must not change.
 *
 * It describes songs, never carries them: no stream addresses, file paths, download state, logins or settings.
 */
@Serializable
data class WebLibraryFile(
    val kind: String = KIND,
    val v: Int = VERSION,
    /** ISO 8601 in UTC with milliseconds, as JavaScript's `toISOString()` writes it. */
    val exportedAt: String,
    /** Who wrote the file, for people reading it. Readers ignore it. */
    val generator: String? = null,
    val likes: List<Like>,
    val playlists: List<Playlist>,
    val history: List<Play>,
) {
    /** A song as the web knows it (its `LibraryItem` minus what it works out itself). */
    @Serializable
    data class Song(
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long? = null,
        val isrc: String? = null,
        /** Rides along for identity; the web has no Spotify source, so it doesn't use it yet. */
        val spotifyId: String? = null,
        /** Web source id → that source's track id. Only ids a web source can play: `youtube` today. */
        val refs: Map<String, String>? = null,
        val artwork: List<Artwork>? = null,
        /** When the song came into this library (epoch ms). */
        val addedAt: Long? = null,
    )

    @Serializable
    data class Artwork(val url: String)

    @Serializable
    data class Like(val item: Song, val likedAt: Long)

    @Serializable
    data class Playlist(
        /** Stable across exports (`app-<playlist id>`), so a second import merges into the same playlist. */
        val id: String,
        val name: String,
        val items: List<Song>,
        val createdAt: Long,
        val updatedAt: Long,
        /** Set for a shared mix someone else owns: the web follows it too, and keeps it up to date there. */
        val follow: Follow? = null,
    )

    @Serializable
    data class Follow(val id: String, val version: Int, val sharedBy: String? = null, val checkedAt: Long = 0)

    @Serializable
    data class Play(val item: Song, val playedAt: Long)

    companion object {
        const val KIND = "stash-web-library"
        const val VERSION = 1

        /** The web keeps this many plays (newest first); the export sends no more. */
        const val MAX_PLAYS = 5_000

        /** The web reads at most this many songs a playlist. */
        const val MAX_PLAYLIST_ITEMS = 10_000

        /** The web cuts a playlist name to this many characters. */
        const val MAX_NAME = 100

        /** The web's YouTube source names itself `youtube`; a song link's `yt` id is a ref under it. */
        const val YOUTUBE_SOURCE = "youtube"

        private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val SPOTIFY_ID = Regex("^[A-Za-z0-9]{22}$")
        private val ISRC = Regex("^[A-Za-z0-9]{12}$")
        private val SHARE_ID = Regex("^[A-Za-z0-9]{8}$")

        private val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * Writes the file's text to [out]: compact JSON, UTF-8, fields in a fixed order (so the same library always
         * writes the same bytes). Streamed, so a big library is never held as one string. Doesn't close [out].
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun write(file: WebLibraryFile, out: OutputStream) = json.encodeToStream(serializer(), file, out)

        fun isoMillis(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))

        /** `stash-library-YYYY-MM-DD.json`, the name the web player gives its own backups, in this phone's date. */
        fun fileName(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            "stash-library-${Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()}.json"

        /**
         * [track] as a web song, through the same portable description song links use ([toSharedTrackWithArt]):
         * a cover only from the share service's cover hosts, never a local file. Null when it has no title or artist
         * (a local file without tags): the web can't find or show such a song.
         */
        fun song(track: Track, addedAt: Long?): Song? {
            if (track.title.isBlank() || track.artist.isBlank()) return null
            val s = track.toSharedTrackWithArt()
            return Song(
                title = s.title,
                artist = s.artist,
                album = s.album,
                durationMs = s.durationMs,
                isrc = s.isrc?.takeIf(ISRC::matches)?.uppercase(),
                spotifyId = s.spotifyId?.takeIf(SPOTIFY_ID::matches),
                refs = s.youtubeId?.takeIf(YOUTUBE_ID::matches)?.let { mapOf(YOUTUBE_SOURCE to it) },
                artwork = s.artUrl?.let { listOf(Artwork(it)) },
                addedAt = addedAt?.takeIf { it > 0 },
            )
        }

        /** A follow the web accepts (an 8-character share id, version 1 or more), or null. */
        fun follow(shareId: String, version: Int, sharedBy: String?): Follow? =
            if (SHARE_ID.matches(shareId) && version >= 1) {
                Follow(shareId, version, sharedBy?.trim()?.takeIf { it.isNotEmpty() })
            } else {
                null
            }
    }
}
