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
 * Fetches Home discovery rows from Qobuz featured endpoints — tokenless, under the
 * web-player app_id — and maps them into [AlbumSummary]/[PlaylistSummary].
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
            safely { client.getFeaturedPlaylists(genreId) }.map { it.toPlaylistSummary() }
        }

    override suspend fun browsePlaylists(genreId: Int?, offset: Int, limit: Int): List<PlaylistSummary> =
        cached("browse:$genreId:$offset:$limit") {
            safely { client.getFeaturedPlaylists(genreId, limit, offset) }
                .map { it.toPlaylistSummary() }
        }

    override suspend fun searchPlaylists(query: String, offset: Int, limit: Int): List<PlaylistSummary> =
        cached("psearch:$query:$offset:$limit") {
            safely { client.searchPlaylists(query, limit, offset) }
                .map { it.toPlaylistSummary() }
        }

    private suspend fun albums(type: String, genreId: Int?): List<AlbumSummary> =
        cached("$type:$genreId") {
            safely { client.getFeaturedAlbums(type, genreId) }.map { it.toAlbumSummary() }
        }

    /** Fail-soft memoization: throw → empty (uncached); success (incl. empty) → cached. */
    private suspend fun <T> cached(key: String, load: suspend () -> List<T>): List<T> {
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < ttlMs }?.let {
            @Suppress("UNCHECKED_CAST") return it.value as List<T>
        }
        return try {
            load().also { cache[key] = Entry(System.currentTimeMillis(), it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fail-soft: IOException → NO_INTERNET, rethrown so [cached] leaves it uncached;
     * success → OK.
     */
    private suspend fun <T> safely(call: suspend () -> List<T>): List<T> =
        try {
            call().also { _status.value = QobuzDiscoveryStatus.OK }
        } catch (e: java.io.IOException) {
            _status.value = QobuzDiscoveryStatus.NO_INTERNET
            throw e
        }

    private fun QbdlxAlbumItem.toAlbumSummary() = AlbumSummary(
        id = id,
        title = title,
        artist = artist?.name.orEmpty(),
        thumbnailUrl = image?.large ?: image?.small,
        year = release_date_original?.take(4),
        source = AlbumSource.QOBUZ,
        // #481: Top Albums lists singles too, and Qobuz sends no release type; the
        // album screen tells a single by this when it looks for a YouTube copy.
        trackCount = tracks_count.takeIf { it > 0 },
    )

    private fun QbdlxPlaylistItem.toPlaylistSummary() = PlaylistSummary(
        id = id.toString(),
        title = name,
        curator = owner?.name.orEmpty(),
        thumbnailUrl = images300.firstOrNull(),
        trackCount = tracks_count,
    )
}
