package com.stash.data.download.lossless.qbdlx

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of a getFileUrl call, classified from the JSON body (spec §2). */
sealed interface QbdlxResolveResult {
    data class Ok(val url: String, val codec: String, val bitDepth: Int, val sampleRateHz: Int) : QbdlxResolveResult
    /** Token is dead/unauthenticated (preview/sample/fmt5). Caller marks it dead + rotates. */
    object TokenDead : QbdlxResolveResult
    /** Track unavailable for this token's region/rights. Caller tries other tokens. */
    object RegionLocked : QbdlxResolveResult
}

/** Thrown on an HTTP 401 (auth) — distinct so the source can markDead + rotate. */
class QbdlxAuthException(val status: Int, message: String? = null) : RuntimeException(message)
/** Thrown on any other non-2xx / network failure — transient, do NOT mark dead. */
class QbdlxApiException(val status: Int, message: String? = null) : RuntimeException(message)

@Singleton
class QbdlxApiClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val signer: QbdlxSigner,
) {
    // appId read from BuildConfig directly (like ArcodClient reads ARCOD_STREAM_BASE) —
    // NOT a constructor String param, to avoid polluting the global Hilt String namespace.
    // internal var so tests can override.
    internal var appId: String = com.stash.data.download.BuildConfig.QBDLX_APP_ID
    internal var httpClient: OkHttpClient = sharedClient  // direct www.qobuz.com; no interceptor
    internal var baseUrl: String = ORIGIN
    internal var json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Search the Qobuz catalog. Throws [QbdlxAuthException] on 401, [QbdlxApiException] otherwise. */
    suspend fun search(query: String, token: String, limit: Int = 10): List<QbdlxTrack> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("type", "tracks")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("app_id", appId)
                .build()
            val body = get(url.toString(), token)
            runCatching { json.decodeFromString<QbdlxSearchResponse>(body).tracks.items }.getOrDefault(emptyList())
        }

    /** Search the Qobuz catalog for artists (read-only metadata). */
    suspend fun searchArtists(query: String, token: String, limit: Int = 10): List<QbdlxArtistItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("type", "artists")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("app_id", appId)
                .build()
            val body = get(url.toString(), token)
            runCatching { json.decodeFromString<QbdlxArtistSearchResponse>(body).artists.items }.getOrDefault(emptyList())
        }

    /** Fetch an artist's albums (read-only discography metadata). */
    suspend fun getArtistAlbums(artistId: Long, token: String, limit: Int = 100): List<QbdlxAlbumItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/artist/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_id", artistId.toString())
                .addQueryParameter("extra", "albums")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", "0")
                .addQueryParameter("app_id", appId)
                .build()
            val body = get(url.toString(), token)
            runCatching { json.decodeFromString<QbdlxArtistAlbumsResponse>(body).albums.items }.getOrDefault(emptyList())
        }

    /** Fetch an album's detail incl. its tracks (read-only metadata). */
    suspend fun getAlbum(albumId: String, token: String): QbdlxAlbumDetailResponse =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/album/get".toHttpUrl().newBuilder()
                .addQueryParameter("album_id", albumId)
                .addQueryParameter("app_id", appId)
                .build()
            val body = get(url.toString(), token)
            json.decodeFromString<QbdlxAlbumDetailResponse>(body)
        }

    /**
     * Featured albums (`type` = `new-releases-full` / `best-sellers`). Unsigned
     * GET; [genreId] null = all genres. Reuses the album-list envelope. Read-only.
     */
    suspend fun getFeaturedAlbums(type: String, genreId: Int?, token: String, limit: Int = 20): List<QbdlxAlbumItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/album/getFeatured".toHttpUrl().newBuilder()
                .addQueryParameter("type", type)
                .apply { if (genreId != null) addQueryParameter("genre_id", genreId.toString()) }
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("app_id", appId)
                .build()
            val body = get(url.toString(), token)
            runCatching { json.decodeFromString<QbdlxArtistAlbumsResponse>(body).albums.items }.getOrDefault(emptyList())
        }

    /**
     * Featured playlists (editor-picks). Unsigned GET; [genreId] null = all,
     * [offset] paginates the ~6.3k editorial catalog. Read-only.
     *
     * NB: playlists filter on `genre_ids` (PLURAL). The singular `genre_id`
     * that `album/getFeatured` uses is silently ignored here (returns all
     * genres) — so this must send the plural form or the genre chips don't
     * actually filter the playlist row.
     */
    suspend fun getFeaturedPlaylists(genreId: Int?, token: String, limit: Int = 15, offset: Int = 0): List<QbdlxPlaylistItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/playlist/getFeatured".toHttpUrl().newBuilder()
                .addQueryParameter("type", "editor-picks")
                .apply { if (genreId != null) addQueryParameter("genre_ids", genreId.toString()) }
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("app_id", appId)
                .build()
            val body = get(url.toString(), token)
            runCatching { json.decodeFromString<QbdlxFeaturedPlaylistsResponse>(body).playlists.items }.getOrDefault(emptyList())
        }

    /** Playlist detail incl. its tracks. Unsigned GET (read-only metadata). */
    suspend fun getPlaylist(playlistId: String, token: String, limit: Int = 500): QbdlxPlaylistDetailResponse =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/playlist/get".toHttpUrl().newBuilder()
                .addQueryParameter("playlist_id", playlistId)
                .addQueryParameter("extra", "tracks")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("app_id", appId)
                .build()
            json.decodeFromString<QbdlxPlaylistDetailResponse>(get(url.toString(), token))
        }

    /** Resolve a track id to a signed FLAC URL, classified. */
    suspend fun getFileUrl(trackId: Long, formatId: Int, token: String): QbdlxResolveResult =
        withContext(Dispatchers.IO) {
            // ts and sig MUST be one atomic read: take ts once, sign with it, send the same ts.
            val ts = signer.requestTs()
            val sig = signer.signGetFileUrl(ts = ts, trackId = trackId, formatId = formatId)
            val url = "$baseUrl/api.json/0.2/track/getFileUrl".toHttpUrl().newBuilder()
                .addQueryParameter("track_id", trackId.toString())
                .addQueryParameter("format_id", formatId.toString())
                .addQueryParameter("app_id", appId)
                .addQueryParameter("request_ts", ts.toString())
                .addQueryParameter("request_sig", sig)
                .addQueryParameter("intent", "stream")
                .build()
            val raw = get(url.toString(), token)
            val result = classify(json.decodeFromString<QbdlxFileUrl>(raw))
            if (result is QbdlxResolveResult.TokenDead) {
                android.util.Log.w(TAG, "getFileUrl classified TokenDead for track=$trackId fmt=$formatId; raw=${raw.take(300)}")
            }
            result
        }

    private fun classify(f: QbdlxFileUrl): QbdlxResolveResult {
        val dead = f.sample || f.formatId == 5 ||
            f.restrictions.any { it.code.equals("UserUnauthenticated", ignoreCase = true) }
        if (dead) return QbdlxResolveResult.TokenDead
        if (f.url.isNullOrBlank() || f.formatId < 6) return QbdlxResolveResult.RegionLocked
        // formatId >= 6 here (5 already returned TokenDead) → always FLAC.
        return QbdlxResolveResult.Ok(f.url, "flac", f.bitDepth, (f.samplingRate * 1000f).toInt())
    }

    private fun get(url: String, token: String): String {
        val req = Request.Builder().url(url)
            .header("X-App-Id", appId)
            .header("X-User-Auth-Token", token)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .get().build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) {
                android.util.Log.w(TAG, "auth 401 on ${url.substringBefore('?').substringAfterLast('/')}: ${body.take(160)}")
                throw QbdlxAuthException(401, body.take(120))
            }
            if (!resp.isSuccessful) {
                android.util.Log.w(TAG, "HTTP ${resp.code} on ${url.substringBefore('?').substringAfterLast('/')}: ${body.take(160)}")
                throw QbdlxApiException(resp.code, body.take(120))
            }
            return body
        }
    }

    private companion object {
        const val TAG = "QbdlxApiClient"
        const val ORIGIN = "https://www.qobuz.com"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }
}
