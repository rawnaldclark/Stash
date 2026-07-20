package com.stash.data.spotify

import android.util.Log
import com.stash.data.spotify.model.SpotifyImage
import com.stash.data.spotify.model.SpotifyOwner
import com.stash.data.spotify.model.SpotifyPlaylistItem
import com.stash.data.spotify.model.SpotifyTracksRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One parsed page of the GraphQL `libraryV3` library listing.
 *
 * @property playlists    The playlist rows on this page, post-filter.
 * @property folderUris   URIs of folder rows on this page
 *                        (`spotify:user:<id>:folder:<hex>`). libraryV3
 *                        returns the library HIERARCHICALLY — playlists
 *                        that live inside a folder appear only when the
 *                        same operation is re-queried with `folderUri` —
 *                        so callers must descend into each of these or
 *                        every folder-filed playlist is invisible
 *                        (issues #48 / #26 / #80 / #136).
 * @property rawItemCount Item count BEFORE filtering. Pagination must
 *                        advance on this, not on [playlists].size: a
 *                        full 50-item page can parse to fewer playlists
 *                        (folders, pseudo-playlists, NotFound tombstones)
 *                        and a post-filter "short page" check would end
 *                        the walk with the rest of the library unread —
 *                        the same trap [PlaylistTracksPage.rawItemCount]
 *                        exists to prevent for track pages.
 */
data class SpotifyLibraryPage(
    val playlists: List<SpotifyPlaylistItem>,
    val folderUris: List<String>,
    val rawItemCount: Int,
    val isComplete: Boolean = true,
) {
    companion object {
        val EMPTY = SpotifyLibraryPage(emptyList(), emptyList(), 0, isComplete = false)
    }
}

private const val TAG = "StashSync"

/**
 * Parses a GraphQL `libraryV3` response into a [SpotifyLibraryPage].
 *
 * Extracted from `SpotifyApiClient` (the [SpotifyTrackParser] pattern) so
 * the folder/raw-count contract is JVM-testable without the client's
 * auth machinery.
 */
fun parseLibraryPage(responseJson: JsonObject): SpotifyLibraryPage {
    return try {
        val items = responseJson["data"]
            ?.jsonObject?.get("me")
            ?.jsonObject?.get("libraryV3")
            ?.jsonObject?.get("items")
            ?.jsonArray

        if (items == null) {
            Log.w(TAG, "parseLibraryPage: could not find data.me.libraryV3.items")
            Log.d(TAG, "parseLibraryPage: top-level keys: ${responseJson.keys}")
            return SpotifyLibraryPage.EMPTY
        }

        Log.d(TAG, "parseLibraryPage: found ${items.size} library items")

        val playlists = mutableListOf<SpotifyPlaylistItem>()
        val folderUris = mutableListOf<String>()
        var isComplete = responseJson["errors"] == null

        for (element in items) {
            try {
                val wrapper = element.jsonObject
                val item = wrapper["item"]?.jsonObject
                if (item == null) {
                    isComplete = false
                    continue
                }
                val typeName = item["__typename"]?.jsonPrimitive?.contentOrNull
                val data = item["data"]?.jsonObject
                if (data == null) {
                    isComplete = false
                    continue
                }
                val dataTypeName = data["__typename"]?.jsonPrimitive?.contentOrNull
                val uri = data["uri"]?.jsonPrimitive?.contentOrNull

                // Folder rows: collect the uri for a descend pass. Match on
                // either the typed name or the uri shape so a wrapper-name
                // change on Spotify's side doesn't silently re-break this.
                if (dataTypeName == "Folder" || uri?.contains(":folder:") == true) {
                    if (uri != null) {
                        folderUris += uri
                        Log.d(TAG, "parseLibraryPage: folder '$uri' queued for descent")
                    } else {
                        isComplete = false
                        Log.w(TAG, "parseLibraryPage: folder item without uri: $item")
                    }
                    continue
                }

                if (dataTypeName != "Playlist") {
                    Log.d(TAG, "parseLibraryPage: skipping item type: $typeName/$dataTypeName")
                    continue
                }

                if (uri == null || !uri.startsWith("spotify:playlist:")) {
                    isComplete = false
                    continue
                }

                val playlistId = uri.removePrefix("spotify:playlist:")
                val name = data["name"]?.jsonPrimitive?.contentOrNull ?: "Untitled"

                val ownerUsername = (data["ownerV2"] as? JsonObject)
                    ?.let { it["data"] as? JsonObject }
                    ?.get("username")
                    ?.jsonPrimitive?.contentOrNull ?: ""

                // Soft casts: `images` is JsonNull for artless playlists and a
                // hard .jsonObject cast would throw and drop the whole row.
                val imageUrl = (data["images"] as? JsonObject)
                    ?.let { it["items"] as? JsonArray }
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.get("sources") as? JsonArray }
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.get("url") }
                    ?.jsonPrimitive?.contentOrNull

                val images = if (imageUrl != null) {
                    listOf(SpotifyImage(url = imageUrl))
                } else {
                    null
                }

                val totalCount = (data["content"] as? JsonObject)
                    ?.get("totalCount")
                    ?.jsonPrimitive?.intOrNull ?: 0

                playlists += SpotifyPlaylistItem(
                    id = playlistId,
                    name = name,
                    owner = SpotifyOwner(id = ownerUsername),
                    images = images,
                    tracks = SpotifyTracksRef(total = totalCount),
                ).also {
                    Log.d(
                        TAG,
                        "parseLibraryPage: playlist '${it.name}' " +
                            "(id=${it.id}, owner=${it.owner.id}, tracks=$totalCount)",
                    )
                }
            } catch (e: Exception) {
                isComplete = false
                Log.w(TAG, "parseLibraryPage: failed to parse item", e)
            }
        }

        Log.d(
            TAG,
            "parseLibraryPage: parsed ${playlists.size} playlists, " +
                "${folderUris.size} folders from ${items.size} raw items",
        )
        SpotifyLibraryPage(
            playlists = playlists,
            folderUris = folderUris,
            rawItemCount = items.size,
            isComplete = isComplete,
        )
    } catch (e: Exception) {
        Log.e(TAG, "parseLibraryPage: failed to parse response", e)
        SpotifyLibraryPage.EMPTY
    }
}
