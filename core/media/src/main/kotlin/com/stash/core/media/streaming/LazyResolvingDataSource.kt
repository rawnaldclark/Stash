package com.stash.core.media.streaming

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
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
 * Query param marking a placeholder whose `yt` / `isrc` / `d` must win over the
 * track's own Room row — see [withRowOverride].
 */
private const val ROW_OVERRIDE_PARAM = "pin"

/**
 * Marks [this] placeholder so [LazyResolvingDataSource] resolves it from the ids
 * and length in its query params even when the track has a Room row, and keeps
 * the result out of [StreamUrlCache].
 *
 * Listen Together plays the HOST's recording on a listener whose exact-matched
 * row is a different edit: the item carries the host's ids, but the row exists,
 * so the resolve used to read the row's own `youtubeId` / ISRC / length (and any
 * URL already cached for the row) — the listener heard their own edit, the
 * version-mismatch badge lit and drift correction kept seeking. Uncached both
 * ways: the cache is keyed by track id alone, and the host's edit must not be
 * served later when this row plays on its own.
 */
fun Uri.withRowOverride(): Uri = buildUpon().appendQueryParameter(ROW_OVERRIDE_PARAM, "1").build()

/**
 * DataSource that turns a `stash-resolve://track/<id>` placeholder into a
 * playable stream at open time: [StreamUrlCache] first (the next-up
 * prefetch usually has it warm), then a full [StreamSourceRegistry.resolve]
 * under [resolveDeadlineMs]. The read is delegated by origin:
 * JioSaavn → [jioSaavnDelegate] (authed OkHttp, no redirects), everything
 * else → [httpDelegate].
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
    private val jioSaavnDelegate: () -> DataSource,
    private val resolveDeadlineMs: Long = RESOLVE_DEADLINE_MS,
    /**
     * Opens a cloud copy's `stash-cloud://` address ("Play my cloud copies first", #679). Null in
     * tests and builds without cloud wiring: a cloud copy then counts as failed and falls back.
     */
    private val cloudDelegate: (() -> DataSource)? = null,
    /** Told when a cloud copy won't open, so it isn't offered again this session. */
    private val cloudCopies: CloudCopyFinder? = null,
) : DataSource {

    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    /**
     * The stream this instance first served, kept for every later open.
     *
     * Media3 builds ONE DataSource per media period and re-opens it at byte N for
     * every seek outside the buffer and every dropped connection, with the
     * extractor it chose from the first bytes. Consulting [urlCache] (or
     * re-resolving) on each open let the item switch files under it: a network
     * change flips the quality tier and [StreamQualityPolicy] clears the cache, a
     * long pause lets the URL lapse and the re-resolve lands on another rung, and
     * the CD file (or YouTube Opus) is opened at byte N into a 24/96 FLAC
     * extractor — a stall or a decoder error. The pin is reused while its URL is
     * still good; a re-resolve is accepted only when it is the same file
     * ([isSameFileAs]) of the same total length. Anything else throws, and the
     * player's error path re-prepares the item from byte 0 in a new period, where
     * a new file is sniffed from scratch.
     */
    private var pinned: StreamUrl? = null
    private var pinnedAtElapsedMs = 0L
    private var pinnedLifetimeMs = 0L
    private var pinnedTotalLength = C.LENGTH_UNSET.toLong()

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

        val pin = pinned
        val fresh = if (pin != null && pinStillValid()) {
            pin
        } else {
            // StreamUrlCache.get() checks expiry internally (evicts + returns null
            // when stale — see its monotonic-TTL KDoc), so no freshness re-check here.
            val next = (if (overridesRow(uri)) null else urlCache.get(trackId))
                ?: resolveBlocking(trackId, uri)
                ?: throw IOException("stream resolve failed for track $trackId")
            // A DASH MPD (Tidal Hi-Res) cannot be byte-piped into the progressive
            // extractor this source feeds. ResolveFirstMediaSource routes those to a
            // DASH source up front; reaching here means it was bypassed (an override
            // item, or a server configured after this item was built). The URL is
            // cached, so the player's retry of this item builds the DASH source.
            if (next.isDash) {
                throw IOException("track $trackId resolved to a DASH manifest; needs a DASH media source")
            }
            if (pin != null && !next.isSameFileAs(pin)) {
                throw IOException(
                    "track $trackId re-resolved to another file " +
                        "(${pin.origin}/${pin.codec} -> ${next.origin}/${next.codec}); not splicing it at byte ${dataSpec.position}",
                )
            }
            next
        }

        var stream = fresh
        val length = try {
            openStream(stream, dataSpec)
        } catch (e: IOException) {
            // A cloud copy that won't open (moved, deleted, signed out, offline) falls back to the
            // normal chain, once, on a first open. Mid-song a switch of file is the pin's to refuse.
            if (stream.origin != CloudCopyFinder.ORIGIN || pin != null) throw e
            Log.w(TAG, "cloud copy of track $trackId didn't open (${e.message}); streaming it instead")
            cloudCopies?.markFailed(stream.url)
            runCatching { active?.close() }
            active = null
            urlCache.invalidate(trackId)
            stream = resolveBlocking(trackId, uri)?.takeIf { it.origin != CloudCopyFinder.ORIGIN }
                ?: throw IOException("stream resolve failed for track $trackId after its cloud copy failed", e)
            if (stream.isDash) {
                throw IOException("track $trackId resolved to a DASH manifest; needs a DASH media source", e)
            }
            openStream(stream, dataSpec)
        }
        if (dataSpec.length == C.LENGTH_UNSET.toLong() && length != C.LENGTH_UNSET.toLong()) {
            // The bytes from the server's position to its end, so position + length is
            // the file's size: the one identity check that catches a same-format file
            // of another master (a re-resolve that matched another release).
            val total = dataSpec.position + length
            if (pinnedTotalLength != C.LENGTH_UNSET.toLong() && total != pinnedTotalLength) {
                close()
                throw IOException("track $trackId re-opened on a file of another length ($pinnedTotalLength -> $total)")
            }
            pinnedTotalLength = total
        }
        if (stream !== pin) pinStream(stream)
        return length
    }

    /** Opens [stream] at [dataSpec]'s position with the delegate its origin needs. */
    private fun openStream(stream: StreamUrl, dataSpec: DataSpec): Long {
        val delegate = when (stream.origin) {
            JioSaavnStreamResolver.ORIGIN -> jioSaavnDelegate()
            CloudCopyFinder.ORIGIN -> cloudDelegate?.invoke() ?: throw IOException("cloud playback isn't wired")
            else -> httpDelegate()
        }
        return delegateTo(delegate, dataSpec.buildUpon().setUri(Uri.parse(stream.url)).build())
    }

    private fun pinStream(stream: StreamUrl) {
        pinned = stream
        pinnedAtElapsedMs = elapsedMs()
        // Same wall-clock-once, monotonic-after scheme as StreamUrlCache.
        pinnedLifetimeMs = usableLifetimeMs(stream.expiresAtMs - System.currentTimeMillis())
    }

    private fun pinStillValid(): Boolean = elapsedMs() - pinnedAtElapsedMs < pinnedLifetimeMs

    private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L

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
                // query params — see [stashResolveUri]. An override item's
                // params win over its row ([withRowOverride]).
                val entity = placeholderEntity(trackDao, trackId, uri) ?: return@withTimeout null
                resolver.resolve(entity, allowYouTube = true, allowYtDlp = true)
            }
        }?.also { if (!overridesRow(uri)) urlCache.put(trackId, it) }
    } catch (e: TimeoutCancellationException) {
        throw IOException("stream resolve deadline (${resolveDeadlineMs}ms) for track $trackId", e)
    }

    private fun overridesRow(uri: Uri): Boolean = isRowOverride(uri)

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
        private const val TAG = "LazyResolve"
    }
}

/** True for a [withRowOverride] placeholder. */
internal fun isRowOverride(uri: Uri): Boolean = uri.getQueryParameter(ROW_OVERRIDE_PARAM) == "1"

/**
 * The resolver input for placeholder [uri]: the Room row (with an override's ids
 * applied, [withRowOverride]), else an entity rebuilt from the URI's query params.
 * Shared by [LazyResolvingDataSource] and [ResolveFirstMediaSource].
 */
internal suspend fun placeholderEntity(trackDao: TrackDao, trackId: Long, uri: Uri): TrackEntity? {
    val row = trackDao.getById(trackId)
    return (if (row != null && isRowOverride(uri)) row.withIdsFrom(uri) else row)
        ?: entityFromUri(trackId, uri)
}

/** The row, with the recording ids and length the override URI names. */
private fun TrackEntity.withIdsFrom(uri: Uri): TrackEntity = copy(
    youtubeId = uri.getQueryParameter("yt") ?: youtubeId,
    isrc = uri.getQueryParameter("isrc") ?: isrc,
    durationMs = uri.getQueryParameter("d")?.toLongOrNull() ?: durationMs,
)

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
