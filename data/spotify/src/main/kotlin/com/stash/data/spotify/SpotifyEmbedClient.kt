package com.stash.data.spotify

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A song of a Spotify embed page. */
data class SpotifyEmbedTrack(
    /** Its 22-character Spotify track id, when the page names one. */
    val id: String?,
    val title: String,
    /** Its artists, comma-separated ("Pitbull, Sensato"). */
    val artist: String,
    val durationMs: Long?,
)

/** What a Spotify embed page shows of a playlist, album or track. */
data class SpotifyEmbedEntity(
    /** `playlist`, `album` or `track`. */
    val type: String,
    val name: String,
    /** A playlist's owner, an album's artist; null for a track. */
    val subtitle: String?,
    val coverUrl: String?,
    /** A track's own song for a track; at most [SpotifyEmbedClient.EMBED_CAP] for a playlist. */
    val tracks: List<SpotifyEmbedTrack>,
)

/**
 * Reads Spotify's embed pages (`open.spotify.com/embed/{type}/{id}`), which anyone can open: no login, no Web API token, and
 * Spotify's own playlists (Today's Top Hits) as well as listeners'. The page's `__NEXT_DATA__` holds the entity with its first
 * [EMBED_CAP] songs, so a longer playlist needs [SpotifyApiClient.getPlaylistTracks] for the rest.
 */
@Singleton
class SpotifyEmbedClient @Inject constructor(private val okHttpClient: OkHttpClient) {

    /** The [type] (`playlist`, `album`, `track`) with [id], or null when the page can't be read. */
    suspend fun get(type: String, id: String): SpotifyEmbedEntity? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://open.spotify.com/embed/$type/$id")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "embed $type: HTTP ${response.code}")
                    return@use null
                }
                parse(response.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "embed $type failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Where a `spotify.link` / `spotify.app.link` short link leads: the `open.spotify.com` link it redirects to (OkHttp follows
     * the redirects), or the first one named in the page it lands on. Null when neither is there.
     */
    suspend fun resolveShortLink(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            okHttpClient.newCall(request).execute().use { response ->
                val landed = response.request.url
                if (landed.host == OPEN_HOST) return@use landed.toString()
                OPEN_LINK.find(response.body?.string().orEmpty().take(MAX_SHORT_LINK_PAGE))?.value
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "short link failed: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        private const val TAG = "SpotifyEmbed"
        private const val OPEN_HOST = "open.spotify.com"

        /** The songs an embed page lists of a playlist; Spotify's own cap. */
        const val EMBED_CAP = 100

        private const val MAX_SHORT_LINK_PAGE = 200_000
        private val OPEN_LINK = Regex("""https://open\.spotify\.com/[A-Za-z0-9/_\-]+""")
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val TRACK_URI = Regex("""^spotify:track:([A-Za-z0-9]{22})$""")
        private val json = Json { ignoreUnknownKeys = true }

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

        /** The entity of an embed page's `__NEXT_DATA__` (`props.pageProps.state.data.entity`), or null. */
        fun parse(html: String): SpotifyEmbedEntity? {
            val data = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
            val root = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return null
            val e = root.at("props", "pageProps", "state", "data", "entity") as? JsonObject ?: return null
            val type = e.str("type") ?: return null
            val name = (e.str("name") ?: e.str("title"))?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
            val tracks = if (type == "track") {
                val artist = (e["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(", ")
                listOfNotNull(SpotifyEmbedTrack(idOf(e.str("uri")), name, clean(artist), e.long("duration")).takeIf { artist.isNotBlank() })
            } else {
                (e["trackList"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val t = item as? JsonObject ?: return@mapNotNull null
                    // Episodes and other non-songs can sit in a playlist: songs only.
                    if (t.str("entityType")?.let { it != "track" } == true) return@mapNotNull null
                    val title = t.str("title")?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val artist = t.str("subtitle")?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    SpotifyEmbedTrack(idOf(t.str("uri")), title, artist, t.long("duration"))
                }
            }
            return SpotifyEmbedEntity(
                type = type,
                name = name,
                subtitle = e.str("subtitle")?.let(::clean)?.takeIf { it.isNotEmpty() && type != "track" },
                coverUrl = coverOf(e),
                tracks = tracks,
            )
        }

        /** A playlist's `coverArt.sources`, else the largest of `visualIdentity.image` (albums and tracks). */
        private fun coverOf(e: JsonObject): String? {
            val sources = (e.at("coverArt", "sources") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            sources.maxByOrNull { it.int("width") ?: 0 }?.str("url")?.let { return it }
            return (e.at("visualIdentity", "image") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .maxByOrNull { it.int("maxWidth") ?: 0 }?.str("url")
        }

        private fun idOf(uri: String?): String? = uri?.let { TRACK_URI.matchEntire(it)?.groupValues?.get(1) }

        /** The page joins artists with a no-break space ("Pitbull, Sensato"). */
        private fun clean(s: String) = s.replace(' ', ' ').trim()

        private fun JsonElement.at(vararg path: String): JsonElement? =
            path.fold(this as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
