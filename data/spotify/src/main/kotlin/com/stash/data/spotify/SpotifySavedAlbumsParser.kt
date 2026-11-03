package com.stash.data.spotify

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * One album saved in the listener's Spotify library (libraryV3 with the "Albums" filter).
 *
 * @property id        The album's 22-character Spotify id.
 * @property artists   Its credited acts, in Spotify's order.
 * @property coverUrl  The widest cover Spotify lists.
 * @property addedAt   When it was saved (`addedAt.isoString`, as Spotify wrote it): saved again after
 *                     being removed, it moves, which is how the sync tells a re-saved album from one it
 *                     already holds.
 * @property albumType `ALBUM`, `EP`, `SINGLE` or `COMPILATION`, as Spotify names it.
 */
data class SpotifySavedAlbum(
    val id: String,
    val name: String,
    val artists: List<String>,
    val coverUrl: String?,
    val addedAt: String?,
    val albumType: String?,
)

/**
 * One parsed page of the saved-albums listing.
 *
 * @property albums       The albums a sync can read: released, and readable.
 * @property listedIds    Every album id this page listed, read or not (a pre-release, one Spotify no
 *                        longer has): what "still saved" is measured against, so an album that can't be
 *                        read this time is never mistaken for one the listener removed.
 * @property rawItemCount Items on the page before anything was left out: pagination advances on this.
 * @property totalCount   The library's own count of saved albums (`libraryV3.totalCount`).
 * @property offset       Where this page starts (`pagingInfo.offset`).
 * @property isComplete   False when a row couldn't be read at all, or the response carried errors.
 */
data class SpotifySavedAlbumsPage(
    val albums: List<SpotifySavedAlbum>,
    val listedIds: List<String>,
    val rawItemCount: Int,
    val totalCount: Int?,
    val offset: Int?,
    val isComplete: Boolean = true,
    /** The sort order Spotify listed the page in (`selectedSortOrder.id`), e.g. "Recently Added". */
    val sortOrder: String? = null,
) {
    companion object {
        /** The failure sentinel: the request or the response failed. Compared by identity. */
        val EMPTY = SpotifySavedAlbumsPage(emptyList(), emptyList(), 0, null, null, isComplete = false)
    }
}

/** One song of an album page (`getAlbum`), in the album's order. */
data class SpotifyAlbumTrack(
    val id: String,
    val name: String,
    val artists: List<String>,
    val durationMs: Long,
    /** `contentRating.label == "EXPLICIT"`. */
    val explicit: Boolean,
    val discNumber: Int?,
    val trackNumber: Int?,
)

/**
 * One parsed page of an album's songs.
 *
 * @property rawItemCount Items before anything was left out: the next page's offset advances on this.
 * @property totalCount   The album's own song count (`tracksV2.totalCount`).
 */
data class SpotifyAlbumTracksPage(
    val tracks: List<SpotifyAlbumTrack>,
    val rawItemCount: Int,
    val totalCount: Int?,
    val isComplete: Boolean = true,
)

/**
 * An album's whole tracklist ([SpotifyApiClient.getAlbumTracks]).
 *
 * @property complete Every song Spotify counts was read ([SpotifyAlbumTracksPage.totalCount] met), so
 *                    the sync may mirror the album exactly; otherwise the snapshot is partial.
 * @property walkedAll Every page was read up to Spotify's own count, though a song on them may have
 *                     been unreadable. False is a short read, for one of three reasons: [pageFailed],
 *                     [pageCapHit], or neither (Spotify listed fewer songs than it counts).
 * @property pageFailed A page after the first failed: worth reading again.
 * @property pageCapHit The album is longer than the walk reads (over 1,000 songs): every read stops
 *                      at the same place, so what was read is all there will be.
 */
data class SpotifyAlbumTracklist(
    val tracks: List<SpotifyAlbumTrack>,
    val totalCount: Int?,
    val complete: Boolean,
    val walkedAll: Boolean = complete,
    val pageFailed: Boolean = false,
    val pageCapHit: Boolean = false,
)

private const val TAG = "StashSync"
private const val ALBUM_URI = "spotify:album:"
private const val TRACK_URI = "spotify:track:"

/**
 * Parses a libraryV3 response asked for with `filters: ["Albums"]` (shape pinned by the fixture
 * `libraryv3_saved_albums.json`, captured 2026-11-02 and sanitised): `data.me.libraryV3` with
 * `totalCount`, `pagingInfo` and `items[]`, each item `{addedAt, item: AlbumResponseWrapper{_uri,
 * data: Album}}`. The same reading as the web player's own: a pre-release (its
 * `preReleaseEndDateTime` still ahead of [nowMs]) and a row whose data isn't an `Album` (NotFound)
 * are listed but not read.
 */
fun parseSavedAlbumsPage(responseJson: JsonObject, nowMs: Long = System.currentTimeMillis()): SpotifySavedAlbumsPage {
    return try {
        val library = responseJson.obj("data")?.obj("me")?.obj("libraryV3")
        val items = library?.get("items") as? JsonArray
        if (library == null || items == null) {
            Log.w(TAG, "parseSavedAlbumsPage: no data.me.libraryV3.items (typename=${library?.str("__typename")})")
            return SpotifySavedAlbumsPage.EMPTY
        }
        var isComplete = responseJson["errors"] == null
        val albums = mutableListOf<SpotifySavedAlbum>()
        val listed = mutableListOf<String>()
        for (element in items) {
            val wrapper = (element as? JsonObject)?.obj("item")
            val data = wrapper?.obj("data")
            val uri = data?.str("uri") ?: wrapper?.str("_uri")
            val id = uri?.takeIf { it.startsWith(ALBUM_URI) }?.removePrefix(ALBUM_URI)?.takeIf { it.isNotBlank() }
            if (id == null) {
                // Not an album row at all (the filter should never return one), or unreadable.
                if (wrapper?.str("__typename") != "AlbumResponseWrapper" || data == null) isComplete = false
                continue
            }
            listed += id
            if (data?.str("__typename") != "Album") continue
            if (isPreRelease(data, nowMs)) continue
            val name = data.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            if (name == null) {
                isComplete = false
                continue
            }
            albums += SpotifySavedAlbum(
                id = id,
                name = name,
                artists = artistNames(data.obj("artists")),
                coverUrl = SpotifyArtwork.largestSource(data.obj("coverArt")?.get("sources")),
                addedAt = (element as JsonObject).obj("addedAt")?.str("isoString")?.takeIf { it.isNotBlank() },
                albumType = data.str("type"),
            )
        }
        val paging = library.obj("pagingInfo")
        SpotifySavedAlbumsPage(
            albums = albums,
            listedIds = listed,
            rawItemCount = items.size,
            totalCount = library.int("totalCount"),
            offset = paging?.int("offset"),
            isComplete = isComplete,
            sortOrder = library.obj("selectedSortOrder")?.str("id"),
        )
    } catch (e: Exception) {
        Log.e(TAG, "parseSavedAlbumsPage: failed to parse response", e)
        SpotifySavedAlbumsPage.EMPTY
    }
}

/**
 * The `libraryV3` typename when the library answered with an error object instead of a page (e.g.
 * `LibraryInvalidSortOrderIdError`); null for a page, or a response without the library at all.
 */
internal fun libraryErrorTypename(responseJson: JsonObject): String? =
    responseJson.obj("data")?.obj("me")?.obj("libraryV3")?.str("__typename")?.takeIf { it != "LibraryPage" }

/**
 * Parses one `getAlbum` page (shape pinned by the fixture `getalbum.json`, captured 2026-11-02 and
 * sanitised): `data.albumUnion.tracksV2` with `totalCount` and `items[]`, each `{track: {uri, name,
 * artists, duration.totalMilliseconds, contentRating.label, discNumber, trackNumber}}`, in the album's
 * order. Null when the response holds no album.
 */
fun parseAlbumTracksPage(responseJson: JsonObject): SpotifyAlbumTracksPage? {
    return try {
        val album = responseJson.obj("data")?.obj("albumUnion")
        val tracks = album?.obj("tracksV2")
        val items = tracks?.get("items") as? JsonArray
        if (album?.str("__typename") != "Album" || items == null) {
            Log.w(TAG, "parseAlbumTracksPage: no album (typename=${album?.str("__typename")})")
            return null
        }
        var isComplete = responseJson["errors"] == null
        val parsed = items.mapNotNull { element ->
            val track = (element as? JsonObject)?.obj("track")
            val id = track?.str("uri")?.takeIf { it.startsWith(TRACK_URI) }?.removePrefix(TRACK_URI)?.takeIf { it.isNotBlank() }
            val name = track?.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            val artists = artistNames(track?.obj("artists"))
            if (track == null || id == null || name == null || artists.isEmpty()) {
                isComplete = false
                return@mapNotNull null
            }
            SpotifyAlbumTrack(
                id = id,
                name = name,
                artists = artists,
                durationMs = track.obj("duration")?.long("totalMilliseconds") ?: 0L,
                explicit = track.obj("contentRating")?.str("label") == "EXPLICIT",
                discNumber = track.int("discNumber"),
                trackNumber = track.int("trackNumber"),
            )
        }
        SpotifyAlbumTracksPage(
            tracks = parsed,
            rawItemCount = items.size,
            totalCount = tracks.int("totalCount"),
            isComplete = isComplete,
        )
    } catch (e: Exception) {
        Log.e(TAG, "parseAlbumTracksPage: failed to parse response", e)
        null
    }
}

/** The web player's rule: a pre-release until its `preReleaseEndDateTime` has passed. */
private fun isPreRelease(album: JsonObject, nowMs: Long): Boolean {
    val end = album.obj("preReleaseEndDateTime")?.str("isoString") ?: return false
    val endMs = runCatching { Instant.parse(end).toEpochMilli() }.getOrNull() ?: return false
    return endMs > nowMs
}

private fun artistNames(artists: JsonObject?): List<String> =
    (artists?.get("items") as? JsonArray).orEmpty().mapNotNull { a ->
        (a as? JsonObject)?.obj("profile")?.str("name")?.trim()?.takeIf { it.isNotEmpty() }
    }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
