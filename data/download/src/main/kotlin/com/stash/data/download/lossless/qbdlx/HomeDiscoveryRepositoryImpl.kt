package com.stash.data.download.lossless.qbdlx

import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.core.model.discovery.QobuzDiscoveryStatus
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches Home discovery rows from Qobuz featured endpoints via the qbdlx token
 * pool and maps them into [AlbumSummary]/[PlaylistSummary].
 *
 * Fail-soft: any failure yields an empty list (never throws) and is NOT cached,
 * so the next visit retries. Successful results (including a legitimately empty
 * catalog for a genre) are cached for [ttlMs]. This path deliberately does NOT
 * touch the download rate-limiter / breaker — a discovery hiccup is not a
 * source-health signal.
 */
@Singleton
class HomeDiscoveryRepositoryImpl @Inject constructor(
    private val client: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
) : HomeDiscoveryRepository {
    private val _status = kotlinx.coroutines.flow.MutableStateFlow(QobuzDiscoveryStatus.OK)
    override val status: kotlinx.coroutines.flow.StateFlow<QobuzDiscoveryStatus> = _status.asStateFlow()

    private class Entry(val at: Long, val value: List<Any?>)

    // ponytail: ConcurrentHashMap, not a Mutex — the check-then-load race across
    // the three concurrent row fetches costs at most a duplicate request
    // (idempotent, fail-soft), never corruption.
    private val cache = ConcurrentHashMap<String, Entry>()
    private val ttlMs = 3 * 60 * 60 * 1000L

    override suspend fun newReleases(genreId: Int?): List<AlbumSummary> =
        albums("new-releases-full", genreId)

    override suspend fun topAlbums(genreId: Int?): List<AlbumSummary> =
        albums("best-sellers", genreId)

    override suspend fun communityPlaylists(genreId: Int?): List<PlaylistSummary> =
        cached("playlists:$genreId") {
            withToken { tok -> client.getFeaturedPlaylists(genreId, tok) }.map { it.toPlaylistSummary() }
        }

    override suspend fun browsePlaylists(genreId: Int?, offset: Int, limit: Int): List<PlaylistSummary> =
        cached("browse:$genreId:$offset:$limit") {
            withToken { tok -> client.getFeaturedPlaylists(genreId, tok, limit, offset) }
                .map { it.toPlaylistSummary() }
        }

    override suspend fun searchPlaylists(query: String, offset: Int, limit: Int): List<PlaylistSummary> =
        cached("psearch:$query:$offset:$limit") {
            withToken { tok -> client.searchPlaylists(query, tok, limit, offset) }
                .map { it.toPlaylistSummary() }
        }

    private suspend fun albums(type: String, genreId: Int?): List<AlbumSummary> =
        cached("$type:$genreId") {
            withToken { tok -> client.getFeaturedAlbums(type, genreId, tok) }.map { it.toAlbumSummary() }
        }

    /** Fail-soft memoization: throw → empty (uncached); success (incl. empty) → cached. */
    private suspend fun <T> cached(key: String, load: suspend () -> List<T>): List<T> {
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < ttlMs }?.let {
            @Suppress("UNCHECKED_CAST") return it.value as List<T>
        }
        return try {
            load().also { cache[key] = Entry(System.currentTimeMillis(), it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One 401 rotation (markDead + next live token). Empty when no live token. */
    private suspend fun <T> withToken(call: suspend (String) -> List<T>): List<T> {
        val tok = credentialStore.activeToken()
        if (tok == null) {
            _status.value = QobuzDiscoveryStatus.NO_TOKEN
            return emptyList()
        }
        return try {
            val result = call(tok)
            _status.value = QobuzDiscoveryStatus.OK
            result
        } catch (e: QbdlxAuthException) {
            credentialStore.markDead(tok)
            val next = credentialStore.activeToken()
            if (next == null) {
                _status.value = QobuzDiscoveryStatus.NO_TOKEN
                return emptyList()
            }
            try {
                val result = call(next)
                _status.value = QobuzDiscoveryStatus.OK
                result
            } catch (e2: java.io.IOException) {
                _status.value = QobuzDiscoveryStatus.NO_INTERNET
                emptyList()
            }
        } catch (e: java.io.IOException) {
            _status.value = QobuzDiscoveryStatus.NO_INTERNET
            emptyList()
        }
    }

    private fun QbdlxAlbumItem.toAlbumSummary() = AlbumSummary(
        id = id,
        title = title,
        artist = artist?.name.orEmpty(),
        thumbnailUrl = image?.large ?: image?.small,
        year = release_date_original?.take(4),
        source = AlbumSource.QOBUZ,
    )

    private fun QbdlxPlaylistItem.toPlaylistSummary() = PlaylistSummary(
        id = id.toString(),
        title = name,
        curator = owner?.name.orEmpty(),
        thumbnailUrl = images300.firstOrNull(),
        trackCount = tracks_count,
    )
}
