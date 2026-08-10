package com.stash.data.spotify

import android.util.Log
import com.stash.data.spotify.model.SpotifyAlbum
import com.stash.data.spotify.model.SpotifyArtist
import com.stash.data.spotify.model.SpotifyImage
import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.spotify.model.SpotifyTrackObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

private const val TAG = "StashSync"

private val parserJson = Json { ignoreUnknownKeys = true }

/**
 * Parses a single page of the Spotify Web API `/v1/playlists/{id}/tracks`
 * response into [SpotifyTrackItem]s plus the `next` page URL.
 *
 * Pulls `explicit` and `external_ids.isrc` from each track object — both
 * must be requested via the `fields=` query parameter in
 * [SpotifyApiClient.tryGetPlaylistTracksViaWebApi] or they won't be present
 * in the response. Missing `external_ids` yields a null [SpotifyTrackObject.isrc]
 * (legacy tracks added before ISRCs were routinely attached).
 *
 * @param responseBody The raw JSON response body.
 * @return Pair of (tracks on this page, next page URL or null).
 */
internal fun parseWebApiPlaylistPage(responseBody: String): Pair<List<SpotifyTrackItem>, String?> {
    return try {
        val root = parserJson.parseToJsonElement(responseBody).jsonObject
        val nextUrl = root["next"]?.jsonPrimitive?.contentOrNull
        val items = root["items"]?.jsonArray ?: return Pair(emptyList(), null)

        val tracks = items.mapNotNull { element ->
            try {
                val wrapper = element.jsonObject
                val trackObj = wrapper["track"]?.jsonObject ?: return@mapNotNull null

                val id = trackObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = trackObj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                val uri = trackObj["uri"]?.jsonPrimitive?.contentOrNull ?: "spotify:track:$id"
                val durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                val explicit = trackObj["explicit"]?.jsonPrimitive?.booleanOrNull ?: false
                val isrc = trackObj["external_ids"]?.jsonObject
                    ?.get("isrc")?.jsonPrimitive?.contentOrNull

                val artists = trackObj["artists"]?.jsonArray?.mapNotNull { artistEl ->
                    val artistObj = artistEl.jsonObject
                    val artistId = artistObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val artistName = artistObj["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    SpotifyArtist(id = artistId, name = artistName)
                } ?: emptyList()

                val albumObj = trackObj["album"]?.jsonObject
                val album = if (albumObj != null) {
                    val albumId = albumObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val albumName = albumObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val albumImages = albumObj["images"]?.jsonArray?.mapNotNull { imgEl ->
                        val imgUrl = imgEl.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                        if (imgUrl != null) SpotifyImage(url = imgUrl) else null
                    }
                    SpotifyAlbum(id = albumId, name = albumName, images = albumImages)
                } else {
                    null
                }

                SpotifyTrackItem(
                    track = SpotifyTrackObject(
                        id = id,
                        name = name,
                        artists = artists,
                        album = album,
                        duration_ms = durationMs,
                        uri = uri,
                        explicit = explicit,
                        isrc = isrc,
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "parseWebApiPlaylistPage: failed to parse track item", e)
                null
            }
        }

        Pair(tracks, nextUrl)
    } catch (e: Exception) {
        Log.e(TAG, "parseWebApiPlaylistPage: failed to parse response", e)
        Pair(emptyList(), null)
    }
}

/**
 * Parses the Spotify Web API `/v1/search?type=track` response into
 * [SpotifyTrackCandidate]s.
 *
 * Response shape: `{ "tracks": { "items": [ {track object} ] } }`. Unlike the
 * playlist endpoint there is no `item.track` wrapper — each entry in
 * `tracks.items` IS the track object. Pulls `id`, `name`, `album.name`
 * (→ albumName), `duration_ms` (→ durationMs), `explicit`, ordered
 * `artists[].name`, and `external_ids.isrc` (often absent on /search results,
 * which yields a null isrc).
 *
 * Parseable-but-empty (`tracks.items` empty or absent) returns an empty list.
 *
 * @param responseBody The raw JSON response body.
 * @return The parsed candidate tracks, in result order.
 */
internal fun parseSearchTracks(responseBody: String): List<SpotifyTrackCandidate> {
    return try {
        val items = parserJson.parseToJsonElement(responseBody).jsonObject["tracks"]
            ?.jsonObject?.get("items")
            ?.jsonArray
            ?: return emptyList()

        items.mapNotNull { element ->
            try {
                val trackObj = element.jsonObject

                val id = trackObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = trackObj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                val durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                val explicit = trackObj["explicit"]?.jsonPrimitive?.booleanOrNull ?: false
                val isrc = trackObj["external_ids"]?.jsonObject
                    ?.get("isrc")?.jsonPrimitive?.contentOrNull

                val artists = trackObj["artists"]?.jsonArray?.mapNotNull { artistEl ->
                    artistEl.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                } ?: emptyList()

                val albumName = trackObj["album"]?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull ?: ""

                SpotifyTrackCandidate(
                    id = id,
                    name = name,
                    artists = artists,
                    albumName = albumName,
                    durationMs = durationMs,
                    isrc = isrc,
                    explicit = explicit,
                )
            } catch (e: Exception) {
                Log.w(TAG, "parseSearchTracks: failed to parse track item", e)
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "parseSearchTracks: failed to parse response", e)
        emptyList()
    }
}

/**
 * Pulls the library add-date off a `fetchLibraryTracks` item wrapper.
 *
 * Spotify's GraphQL library shapes have moved around, so this accepts the
 * nested `addedAt.isoString` the desktop client reads, a flat `addedAt`
 * string, and the Web API's `added_at` — returning null when none is present
 * rather than guessing. Callers must stay correct with a null add date.
 */
internal fun parseLibraryAddedAt(wrapper: JsonObject): String? {
    val addedAt = wrapper["addedAt"]
    (addedAt as? JsonObject)?.get("isoString")?.let { iso ->
        (iso as? JsonPrimitive)?.contentOrNull?.let { return it }
    }
    (addedAt as? JsonPrimitive)?.contentOrNull?.let { return it }
    return (wrapper["added_at"] as? JsonPrimitive)?.contentOrNull
}

/**
 * Orders Liked Songs newest-saved first, the way Spotify itself presents them.
 *
 * The sync stores list index as playlist position, so this ordering is what
 * the user ends up seeing. `fetchLibraryTracks` is called without an `order`
 * variable, meaning the server's own ordering is not something we can rely on
 * (issue #410) — sorting on the reported add date makes the result independent
 * of it.
 *
 * Items with no parseable add date keep their original relative order at the
 * end of the list (the sort is stable), and a page with no add dates at all is
 * returned untouched, so an endpoint that stops reporting them degrades to
 * today's behaviour instead of scrambling.
 */
fun sortLikedSongsByAddedAtDesc(items: List<SpotifyTrackItem>): List<SpotifyTrackItem> {
    val stamped = items.map { item ->
        item to item.addedAt?.let { raw ->
            try {
                Instant.parse(raw)
            } catch (e: DateTimeParseException) {
                Log.w(TAG, "sortLikedSongsByAddedAtDesc: unparseable addedAt '$raw'", e)
                null
            }
        }
    }
    if (stamped.none { (_, instant) -> instant != null }) return items
    return stamped
        // nullsFirst, not nullsLast: compareByDescending flips the comparator's
        // arguments, so the nulls-are-smallest ordering is what leaves undated
        // items at the END of the descending result.
        .sortedWith(compareByDescending(nullsFirst()) { (_, instant) -> instant })
        .map { (item, _) -> item }
}

/**
 * Parses a `fetchLibraryTracks` GraphQL response (Liked Songs) into [SpotifyTrackItem]s.
 *
 * Response shape, confirmed against the persisted-query hash
 * `087278b2…f944240` as used by several independent clients:
 *
 * ```
 * data.me.library.tracks
 * |- totalCount, pagingInfo
 * `- items[]
 *    |- addedAt.isoString : ISO-8601 save date  -> SpotifyTrackItem.addedAt
 *    `- track._uri / track.data.{name, uri, trackDuration, artists, albumOfTrack}
 * ```
 *
 * `data.me.libraryTracks.items` is accepted as a second path: the private API
 * has renamed this node before and the cost of tolerating both is one `?:`.
 *
 * Returns an empty list for any unrecognised shape rather than throwing —
 * callers treat empty as `SyncResult.Empty` and stop paginating.
 */
internal fun parseLibraryTracksResponse(responseJson: JsonObject): List<SpotifyTrackItem> {
    return try {
        // Log top-level keys for debugging
        val dataObj = responseJson["data"]?.jsonObject
        if (dataObj == null) {
            Log.w(TAG, "parseLibraryTracksResponse: no 'data' key, responseKeys=${responseJson.keys}")
            return emptyList()
        }
        Log.d(TAG, "parseLibraryTracksResponse: data keys: ${dataObj.keys}")

        // Try multiple possible response paths
        val items = dataObj["me"]
            ?.jsonObject?.get("library")
            ?.jsonObject?.get("tracks")
            ?.jsonObject?.get("items")
            ?.jsonArray
            ?: dataObj["me"]
                ?.jsonObject?.get("libraryTracks")
                ?.jsonObject?.get("items")
                ?.jsonArray

        if (items == null) {
            // Log what we DID find so we can fix the path
            val meKeys = dataObj["me"]?.jsonObject?.keys
            Log.w(TAG, "parseLibraryTracksResponse: items not found. me keys: $meKeys")
            val meObj = dataObj["me"]?.jsonObject
            meObj?.keys?.forEach { key ->
                val subKeys = meObj[key]?.jsonObject?.keys
                Log.d(TAG, "parseLibraryTracksResponse: me.$key keys: $subKeys")
            }
            return emptyList()
        }

        Log.d(TAG, "parseLibraryTracksResponse: found ${items.size} items")

        items.mapNotNull { element ->
            try {
                val wrapper = element.jsonObject
                // Response shape: items[].track.data (with _uri on the track wrapper)
                val trackData = wrapper["track"]?.jsonObject?.get("data")?.jsonObject
                    ?: wrapper["item"]?.jsonObject?.get("data")?.jsonObject
                    ?: wrapper["itemV2"]?.jsonObject?.get("data")?.jsonObject
                    ?: return@mapNotNull null

                val uri = trackData["uri"]?.jsonPrimitive?.contentOrNull
                    ?: wrapper["track"]?.jsonObject?.get("_uri")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                if (!uri.startsWith("spotify:track:")) return@mapNotNull null

                val trackId = uri.removePrefix("spotify:track:")
                val name = trackData["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"

                val durationMs = trackData["trackDuration"]
                    ?.jsonObject?.get("totalMilliseconds")
                    ?.jsonPrimitive?.longOrNull
                    ?: trackData["duration"]
                        ?.jsonObject?.get("totalMilliseconds")
                        ?.jsonPrimitive?.longOrNull
                    ?: 0L

                val artistItems = trackData["artists"]
                    ?.jsonObject?.get("items")
                    ?.jsonArray

                val artists = artistItems?.mapNotNull { artistElement ->
                    val artistObj = artistElement.jsonObject
                    val artistName = artistObj["profile"]
                        ?.jsonObject?.get("name")
                        ?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val artistUri = artistObj["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                    val artistId = artistUri.removePrefix("spotify:artist:")
                    SpotifyArtist(id = artistId, name = artistName)
                } ?: emptyList()

                val albumData = trackData["albumOfTrack"]?.jsonObject
                val album = if (albumData != null) {
                    val albumUri = albumData["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                    val albumId = albumUri.removePrefix("spotify:album:")
                    val albumName = albumData["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val coverArtUrl = albumData["coverArt"]
                        ?.jsonObject?.get("sources")
                        ?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("url")
                        ?.jsonPrimitive?.contentOrNull
                    SpotifyAlbum(
                        id = albumId,
                        name = albumName,
                        images = if (coverArtUrl != null) listOf(SpotifyImage(url = coverArtUrl)) else null,
                    )
                } else null

                SpotifyTrackItem(
                    track = SpotifyTrackObject(
                        id = trackId,
                        name = name,
                        artists = artists,
                        album = album,
                        duration_ms = durationMs,
                        uri = uri,
                    ),
                    addedAt = parseLibraryAddedAt(wrapper),
                )
            } catch (e: Exception) {
                Log.w(TAG, "parseLibraryTracksResponse: failed to parse item", e)
                null
            }
        }.also { tracks ->
            Log.d(TAG, "parseLibraryTracksResponse: parsed ${tracks.size} tracks")
        }
    } catch (e: Exception) {
        Log.e(TAG, "parseLibraryTracksResponse: failed", e)
        emptyList()
    }
}
