package com.stash.data.download.lossless.qbdlx

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of a getFileUrl call, classified from the JSON body (spec §2). */
sealed interface QbdlxResolveResult {
    data class Ok(val url: String, val codec: String, val bitDepth: Int, val sampleRateHz: Int) : QbdlxResolveResult
    /** Token is dead/unauthenticated (30 s preview sample, or UserUnauthenticated). Caller marks it dead + rotates. */
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
    private val signingResolver: QbdlxSigningResolver,
    private val webCreds: QobuzWebCredentialsClient,
) {
    /**
     * The app_id catalog calls run under, with NO user token. Qobuz's web player
     * browses its catalog logged-out under this id (verified live 2026-08-29: all
     * eight catalog endpoints answer 200 tokenless here and 401 under the bundled
     * Android-lineage id). Public — it sits in Qobuz's own JS bundle. Self-heals:
     * a 401 refreshes it once from the live bundle via [QobuzWebCredentialsClient].
     *
     * The heal is single-flighted behind [healMutex] and floored at
     * [HEAL_MIN_INTERVAL_MS]: Home fires ~6 catalog calls at once, so without both
     * one rotation would touch off six full page+bundle scrapes. Losers of the race
     * reuse the winner's id instead of scraping; a 401 inside the floor just throws.
     */
    @Volatile internal var catalogAppId: String = WEB_APP_ID
    private val healMutex = Mutex()
    /** Sentinel, not a timestamp: the first heal is always outside the floor. */
    private var lastHealMs = -HEAL_MIN_INTERVAL_MS   // read/written only under [healMutex]
    /** Test seam — the heal throttle's clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }
    internal var httpClient: OkHttpClient = sharedClient  // direct www.qobuz.com; no interceptor
    internal var baseUrl: String = ORIGIN
    internal var json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Search the Qobuz catalog. Throws [QbdlxAuthException] on 401, [QbdlxApiException] otherwise. */
    suspend fun search(query: String, limit: Int = 10): List<QbdlxTrack> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("type", "tracks")
                .addQueryParameter("limit", limit.toString())
                .build()
            val body = catalogGet(url.toString())
            runCatching { json.decodeFromString<QbdlxSearchResponse>(body).tracks.items }.getOrDefault(emptyList())
        }

    /** Search the Qobuz catalog for artists (read-only metadata). */
    suspend fun searchArtists(query: String, limit: Int = 10): List<QbdlxArtistItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("type", "artists")
                .addQueryParameter("limit", limit.toString())
                .build()
            val body = catalogGet(url.toString())
            runCatching { json.decodeFromString<QbdlxArtistSearchResponse>(body).artists.items }.getOrDefault(emptyList())
        }

    /**
     * Search the Qobuz catalog for playlists (read-only metadata). Same
     * `catalog/search` endpoint as tracks/artists — the playlists bucket
     * shares the featured-playlists envelope. Search is catalog-global:
     * the endpoint has no genre filter.
     */
    suspend fun searchPlaylists(query: String, limit: Int = 30, offset: Int = 0): List<QbdlxPlaylistItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("type", "playlists")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
            val body = catalogGet(url.toString())
            runCatching { json.decodeFromString<QbdlxFeaturedPlaylistsResponse>(body).playlists.items }.getOrDefault(emptyList())
        }

    /** Fetch an artist's albums (read-only discography metadata). */
    suspend fun getArtistAlbums(artistId: Long, limit: Int = 100): List<QbdlxAlbumItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/artist/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_id", artistId.toString())
                .addQueryParameter("extra", "albums")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", "0")
                .build()
            val body = catalogGet(url.toString())
            runCatching { json.decodeFromString<QbdlxArtistAlbumsResponse>(body).albums.items }.getOrDefault(emptyList())
        }

    /** Fetch an album's detail incl. its tracks (read-only metadata). */
    suspend fun getAlbum(albumId: String): QbdlxAlbumDetailResponse =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/album/get".toHttpUrl().newBuilder()
                .addQueryParameter("album_id", albumId)
                .build()
            val body = catalogGet(url.toString())
            json.decodeFromString<QbdlxAlbumDetailResponse>(body)
        }

    /**
     * Featured albums (`type` = `new-releases-full` / `best-sellers`). Unsigned
     * GET; [genreId] null = all genres. Reuses the album-list envelope. Read-only.
     */
    suspend fun getFeaturedAlbums(type: String, genreId: Int?, limit: Int = 20): List<QbdlxAlbumItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/album/getFeatured".toHttpUrl().newBuilder()
                .addQueryParameter("type", type)
                .apply { if (genreId != null) addQueryParameter("genre_id", genreId.toString()) }
                .addQueryParameter("limit", limit.toString())
                .build()
            val body = catalogGet(url.toString())
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
    suspend fun getFeaturedPlaylists(genreId: Int?, limit: Int = 15, offset: Int = 0): List<QbdlxPlaylistItem> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/playlist/getFeatured".toHttpUrl().newBuilder()
                .addQueryParameter("type", "editor-picks")
                .apply { if (genreId != null) addQueryParameter("genre_ids", genreId.toString()) }
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
            val body = catalogGet(url.toString())
            runCatching { json.decodeFromString<QbdlxFeaturedPlaylistsResponse>(body).playlists.items }.getOrDefault(emptyList())
        }

    /** Playlist detail incl. its tracks. Unsigned GET (read-only metadata). */
    suspend fun getPlaylist(playlistId: String, limit: Int = 500): QbdlxPlaylistDetailResponse =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/playlist/get".toHttpUrl().newBuilder()
                .addQueryParameter("playlist_id", playlistId)
                .addQueryParameter("extra", "tracks")
                .addQueryParameter("limit", limit.toString())
                .build()
            json.decodeFromString<QbdlxPlaylistDetailResponse>(catalogGet(url.toString()))
        }

    /** Resolve a track id to a signed FLAC URL, classified. */
    suspend fun getFileUrl(trackId: Long, formatId: Int, token: String): QbdlxResolveResult =
        withContext(Dispatchers.IO) {
            // Sign with THIS token's own (app_id, app_secret) — the connected
            // account's, stored with it at login. Sign with anything else and Qobuz
            // returns a 30-second preview, not FLAC, so the resolver throws instead
            // of guessing when the token isn't the connected account's.
            val signing = signingResolver.signingFor(token)
            // ts and sig MUST be one atomic read: take ts once, sign with it, send the same ts.
            val ts = signer.requestTs()
            val sig = signer.signGetFileUrl(ts = ts, trackId = trackId, formatId = formatId, appSecret = signing.appSecret)
            val url = "$baseUrl/api.json/0.2/track/getFileUrl".toHttpUrl().newBuilder()
                .addQueryParameter("track_id", trackId.toString())
                .addQueryParameter("format_id", formatId.toString())
                .addQueryParameter("app_id", signing.appId)
                .addQueryParameter("request_ts", ts.toString())
                .addQueryParameter("request_sig", sig)
                .addQueryParameter("intent", "stream")
                .build()
            val raw = get(url.toString(), token, appIdHeader = signing.appId)
            val result = classify(json.decodeFromString<QbdlxFileUrl>(raw))
            if (result is QbdlxResolveResult.TokenDead) {
                android.util.Log.w(TAG, "getFileUrl classified TokenDead for track=$trackId fmt=$formatId; raw=${raw.take(300)}")
            }
            result
        }

    private fun classify(f: QbdlxFileUrl): QbdlxResolveResult {
        // Only account-level signals mark a token dead. A lossy (format 5) or missing file is
        // about the TRACK — region / licence — never the account: reading `format_id == 5` as
        // dead retired five healthy relay pool accounts overnight (2026-09-05), and here it
        // cooled a healthy connected account for 60 s on every such track.
        val dead = f.sample || f.restrictions.any { it.code.equals("UserUnauthenticated", ignoreCase = true) }
        if (dead) return QbdlxResolveResult.TokenDead
        if (f.url.isNullOrBlank() || f.formatId < 6) return QbdlxResolveResult.RegionLocked
        // formatId >= 6 → always FLAC.
        return QbdlxResolveResult.Ok(f.url, "flac", f.bitDepth, (f.samplingRate * 1000f).toInt())
    }

    /**
     * Catalog GET under [catalogAppId], no user token; one self-heal on 401.
     * [lastHealMs] is stamped BEFORE the scrape on purpose — that bounds
     * concurrent scrapes, at the cost that one FAILED scrape burns the whole
     * [HEAL_MIN_INTERVAL_MS] window: 401s inside it rethrow without a retry.
     */
    private suspend fun catalogGet(url: String): String {
        // Capture the id THIS call used: comparing the scrape against the live field
        // would make a loser rethrow even though the winner already put a good id there.
        val used = catalogAppId
        val fresh = try {
            return catalogGetOnce(url, used)
        } catch (e: QbdlxAuthException) {
            healMutex.withLock {
                if (catalogAppId != used) {
                    catalogAppId  // another call already healed — reuse it, don't scrape
                } else {
                    val now = clock()
                    if (now - lastHealMs < HEAL_MIN_INTERVAL_MS) throw e
                    lastHealMs = now
                    val scraped = webCreds.fetch()?.appId?.takeIf { it.isNotBlank() && it != used } ?: throw e
                    android.util.Log.i(TAG, "catalog app_id rotated $used -> $scraped after 401")
                    catalogAppId = scraped
                    scraped
                }
            }
        }
        // Outside the try on purpose: a second 401 propagates rather than looping.
        return catalogGetOnce(url, fresh)
    }

    private fun catalogGetOnce(url: String, appIdForCall: String): String {
        val req = Request.Builder()
            .url(url.toHttpUrl().newBuilder().setQueryParameter("app_id", appIdForCall).build())
            .header("X-App-Id", appIdForCall)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .get().build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) throw QbdlxAuthException(401, body.take(120))
            if (!resp.isSuccessful) {
                android.util.Log.w(TAG, "HTTP ${resp.code} on ${url.substringBefore('?').substringAfterLast('/')}: ${body.take(160)}")
                throw QbdlxApiException(resp.code, body.take(120))
            }
            return body
        }
    }

    /**
     * Signed/token'd GET — since the catalog moved to [catalogGet], [getFileUrl] is
     * its only caller. The paragraphs below are the history of why it exists.
     *
     * Qobuz binds a `user_auth_token` to the app_id it was minted under: send a
     * different app's id and the SAME token answers 401. The pool mixes tokens from
     * two apps, so the id must come from the TOKEN, never from a client-wide
     * constant.
     *
     * Resolved here rather than at each endpoint because the endpoints that forgot
     * are exactly how this went unnoticed: [getFileUrl] was migrated to per-token
     * signing, the eight catalog calls were not, and since `search` runs first in
     * every resolve those tokens 401'd and were marked dead before signing was ever
     * reached. Measured on the live pool 2026-08-15: 2 of 18 tokens authenticated
     * with the primary app_id, 12 of 18 with their own — the pool was never "dead".
     */
    private suspend fun get(url: String, token: String, appIdHeader: String? = null): String {
        val tokenAppId = appIdHeader ?: signingResolver.signingFor(token).appId
        val req = Request.Builder().url(
            url.toHttpUrl().newBuilder().setQueryParameter("app_id", tokenAppId).build(),
        )
            .header("X-App-Id", tokenAppId)
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
            // A banned account is a DEAD TOKEN, not a service failure.
            //
            // Qobuz answers a blocked account with 403 USER_BLOCKED, which used to
            // fall through to the generic branch below: reported as a health
            // failure, never marked dead, never rotated away from. Because the
            // active token is sticky, one banned account in the pool meant every
            // single resolve failed to it and dropped to lossy YouTube — with the
            // other live tokens sitting unused. Observed on-device 2026-08-02.
            //
            // Matched on the error_code rather than the bare status so a 403 that
            // genuinely means "service said no" (rate limit, geo) still counts as
            // a transient failure and doesn't burn a good token.
            if (resp.code == 403 && body.contains("USER_BLOCKED", ignoreCase = true)) {
                android.util.Log.w(TAG, "token's account is blocked (403 USER_BLOCKED) — marking dead + rotating")
                throw QbdlxAuthException(403, body.take(120))
            }
            if (!resp.isSuccessful) {
                android.util.Log.w(TAG, "HTTP ${resp.code} on ${url.substringBefore('?').substringAfterLast('/')}: ${body.take(160)}")
                throw QbdlxApiException(resp.code, body.take(120))
            }
            return body
        }
    }

    internal companion object {
        const val TAG = "QbdlxApiClient"
        const val ORIGIN = "https://www.qobuz.com"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
        /** Qobuz web-player app_id — the tokenless catalog id. See [catalogAppId]. */
        const val WEB_APP_ID = "712109809"
        /** Floor between catalog app_id scrapes. A rotation is a deploy, not a burst. */
        private const val HEAL_MIN_INTERVAL_MS = 60_000L
    }
}
