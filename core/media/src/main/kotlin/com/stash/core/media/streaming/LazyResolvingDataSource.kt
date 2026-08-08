package com.stash.core.media.streaming

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * URI scheme for stream tracks queued BEFORE their URL is resolved:
 * `stash-resolve://track/<trackId>`. Every logical-queue track gets a
 * MediaItem immediately (full timeline — native next/prev/repeat/shuffle),
 * and the URL is resolved just-in-time here, in [DataSource.open] on
 * ExoPlayer's loader thread.
 *
 * NOT the reverted 2026-05-22 `stash-lazy://` design: that resolved in the
 * SESSION callback at add-time and let unresolved placeholders reach
 * ExoPlayer as unplayable URIs. This scheme is only ever opened by
 * [LazyResolvingDataSource], which blocks until it has a real URL or
 * throws — the documented `ResolvingDataSource` contract, and the same
 * loader-thread blocking [RefreshingDataSource] has shipped for 403
 * recovery since the streaming overhaul.
 */
const val STASH_RESOLVE_SCHEME = "stash-resolve"

/**
 * Builds the placeholder URI for [trackId]. The optional query params carry
 * the [StreamSourceRegistry] resolver inputs so a track that has NO Room row
 * (search-surface synthetic id — see TrackActionsDelegate.toDomainTrack) can
 * still resolve at open() time: the DAO lookup misses and
 * [LazyResolvingDataSource] falls back to an entity built from these params
 * (the DataSource-layer equivalent of the old eager path's toEntity()
 * fallback).
 */
fun stashResolveUri(
    trackId: Long,
    youtubeId: String? = null,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    durationMs: Long = 0L,
    isrc: String? = null,
): Uri = Uri.Builder()
    .scheme(STASH_RESOLVE_SCHEME)
    .authority("track")
    .appendPath(trackId.toString())
    .apply {
        youtubeId?.let { appendQueryParameter("yt", it) }
        title?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("t", it) }
        artist?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("a", it) }
        album?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("al", it) }
        if (durationMs > 0) appendQueryParameter("d", durationMs.toString())
        isrc?.let { appendQueryParameter("isrc", it) }
    }
    .build()

/**
 * DataSource that turns a `stash-resolve://track/<id>` placeholder into a
 * playable stream at open time: [StreamUrlCache] first (the next-up
 * prefetch usually has it warm), then a full [StreamSourceRegistry.resolve]
 * under [resolveDeadlineMs]. The read is delegated by origin:
 * amz → [amzDelegate] (authed OkHttp; header auth, never URL-refresh, never
 * disk cache), everything else → [httpDelegate].
 *
 * Failure = [IOException] from [open], which ExoPlayer surfaces as a source
 * error → `onPlayerError` → the cascade guard decides recover vs halt. The
 * deadline exists so a hung resolver can NEVER wedge playback (the
 * "spinner forever, can't pause" bug).
 *
 * Single [runBlocking] for DAO + resolve, mirroring [RefreshingDataSource]'s
 * re-entrancy note (two independent runBlocking calls on the loader thread
 * can deadlock Room's pool / the aggregator rate-limiter).
 */
@OptIn(UnstableApi::class)
class LazyResolvingDataSource(
    private val resolver: StreamSourceRegistry,
    private val urlCache: StreamUrlCache,
    private val trackDao: TrackDao,
    private val httpDelegate: () -> DataSource,
    private val amzDelegate: () -> DataSource,
    private val jioSaavnDelegate: () -> DataSource,
    private val resolveDeadlineMs: Long = RESOLVE_DEADLINE_MS,
) : DataSource {

    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != STASH_RESOLVE_SCHEME) {
            return delegateTo(httpDelegate(), dataSpec)
        }
        val trackId = uri.lastPathSegment?.toLongOrNull()
            ?: throw IOException("stash-resolve URI without a track id: $uri")

        // StreamUrlCache.get() checks expiry internally (evicts + returns null
        // when stale — see its monotonic-TTL KDoc), so no freshness re-check here.
        val fresh = urlCache.get(trackId)
            ?: resolveBlocking(trackId, uri)
            ?: throw IOException("stream resolve failed for track $trackId")

        val realSpec = dataSpec.buildUpon().setUri(Uri.parse(fresh.url)).build()
        val delegate = when (fresh.origin) {
            "amz" -> amzDelegate()
            JioSaavnStreamResolver.ORIGIN -> jioSaavnDelegate()
            else -> httpDelegate()
        }
        return delegateTo(delegate, realSpec)
    }

    private fun delegateTo(delegate: DataSource, spec: DataSpec): Long {
        listeners.forEach(delegate::addTransferListener)
        active = delegate
        return delegate.open(spec)
    }

    private fun resolveBlocking(trackId: Long, uri: Uri): StreamUrl? = try {
        runBlocking {
            withTimeout(resolveDeadlineMs) {
                // DAO first; a track with no Room row (search-surface synthetic
                // id) falls back to the resolver inputs carried in the URI's
                // query params — see [stashResolveUri].
                val entity = trackDao.getById(trackId)
                    ?: entityFromUri(trackId, uri)
                    ?: return@withTimeout null
                resolver.resolve(entity, allowYouTube = true, allowYtDlp = true)
            }
        }?.also { urlCache.put(trackId, it) }
    } catch (e: TimeoutCancellationException) {
        throw IOException("stream resolve deadline (${resolveDeadlineMs}ms) for track $trackId", e)
    }

    /**
     * Rebuilds a resolver-input [TrackEntity] from the placeholder URI's query
     * params. Null when the URI carries neither a youtubeId nor a
     * title+artist pair — nothing the resolver chain could look up.
     */
    private fun entityFromUri(trackId: Long, uri: Uri): TrackEntity? {
        val youtubeId = uri.getQueryParameter("yt")
        val title = uri.getQueryParameter("t").orEmpty()
        val artist = uri.getQueryParameter("a").orEmpty()
        if (youtubeId == null && (title.isBlank() || artist.isBlank())) return null
        return TrackEntity(
            id = trackId,
            title = title,
            artist = artist,
            album = uri.getQueryParameter("al").orEmpty(),
            durationMs = uri.getQueryParameter("d")?.toLongOrNull() ?: 0L,
            isrc = uri.getQueryParameter("isrc"),
            youtubeId = youtubeId,
            isStreamable = true,
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("read before open")

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        active?.close()
        active = null
    }

    companion object {
        /**
         * Hard ceiling on a just-in-time resolve. yt-dlp worst case is
         * ~15-35 s on the single serialized slot; 45 s covers it with margin
         * while guaranteeing playback can never hang indefinitely.
         */
        const val RESOLVE_DEADLINE_MS = 45_000L
    }
}
