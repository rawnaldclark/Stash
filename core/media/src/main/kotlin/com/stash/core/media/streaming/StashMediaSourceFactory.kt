package com.stash.core.media.streaming

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.stash.core.data.db.dao.TrackDao

/**
 * Player-wide [MediaSource.Factory] that routes **only YouTube-origin streaming
 * items** through the [StreamingMediaSourceFactory] refresh chain
 * (CacheDataSource → [RefreshingDataSource] → HTTP), and everything else —
 * downloaded/local files AND lossless (Kennyy/Squid) streams — through the
 * plain [DefaultMediaSourceFactory], exactly as before.
 *
 * **Why scope to YouTube only.** Background queue-fill seeds the timeline with
 * cheap InnerTube/iOS placeholder URLs (deep, in-order), but those are
 * PO-token-gated to ~1 MB and return HTTP 403 on full playback. The default
 * factory has no recovery — a 403 surfaces as `onPlayerError`, and the cascade
 * guard skip-storms the whole queue. Wrapping YouTube items in
 * [RefreshingDataSource] makes that 403 transparently re-resolve via yt-dlp
 * (full-range-playable) and continue at the same byte offset — no skip, no
 * Halt. Lossless and local playback are left on their proven path so this
 * change can't regress them.
 *
 * The per-item decision is delegated to [streamingTrackId]: it returns the
 * track id when the item should use the refresh chain (YouTube http(s) stream
 * with a valid id), or null otherwise. The service owns that predicate because
 * the metadata-extra keys live there.
 */
@OptIn(UnstableApi::class)
class StashMediaSourceFactory(
    private val context: Context,
    private val streamingFactory: StreamingMediaSourceFactory,
    private val streamingTrackId: (MediaItem) -> Long?,
    private val isJioSaavnOrigin: (MediaItem) -> Boolean,
    httpClient: okhttp3.OkHttpClient,
    resolver: StreamSourceRegistry,
    urlCache: StreamUrlCache,
    trackDao: TrackDao,
    /**
     * True while a Tidal (BitChord) server is configured or an add-on that is on can
     * answer a DASH manifest ([AddonPlayback.resolveBeforeSourcePick]): placeholders then
     * resolve BEFORE choosing a media source ([ResolveFirstMediaSource]), since either may
     * answer with a DASH MPD. Read when an item's source is built. False keeps the lazy
     * path exactly as it was.
     */
    private val resolveBeforeSourcePick: () -> Boolean = { false },
    /**
     * `stash-cloud://` items (the user's own Dropbox / OneDrive files): the stream cache over
     * [CloudResolvingDataSource]. Null in tests and builds without cloud wiring; such an item
     * then fails to open (and is skipped), it is never routed to the online resolvers.
     */
    cloudDataSourceFactory: DataSource.Factory? = null,
    /** The same without the cache, for Cast. */
    cloudUncachedDataSourceFactory: DataSource.Factory? = null,
    /** "Play my cloud copies first": told when a cloud copy won't open. Null in tests. */
    private val cloudCopies: CloudCopyFinder? = null,
    /**
     * False in Download mode. Then only a file on the phone opens: every stream (a
     * placeholder's resolve, an http(s) URL, a cloud file) fails to open and is skipped,
     * whichever path queued it. The player's own gates keep streams out of the queue;
     * this is the last line behind them.
     */
    private val streamAllowed: () -> Boolean = { true },
    resolveDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    /**
     * Add-on streams (`addon:` origins): the add-on media client's HTTP source, whose DNS and
     * connection guard refuse the user's own network (add-ons plan phase 4 §2.6). Null in tests;
     * such a stream is then refused, never played on the default path (review-882 B1).
     */
    addonDataSourceFactory: DataSource.Factory? = null,
    /** An item whose stream an add-on answered (its origin extra starts `addon:`). */
    private val isAddonOrigin: (MediaItem) -> Boolean = { false },
    /** An item whose URL is a DASH manifest by its own word (its DASH extra), whatever its path. */
    private val isDashStream: (MediaItem) -> Boolean = { false },
    /**
     * Judges every add-on's stream before a sound of it plays ([AddonCheckedMediaSource]): an item
     * resolved to an add-on's URL, and a placeholder whose data source opens one. Null in tests that
     * don't ask, and then nothing is judged.
     */
    private val addonCheck: AddonStreamCheck? = null,
) : MediaSource.Factory {

    private val resolver = resolver
    private val urlCache = urlCache
    private val trackDao = trackDao

    /** Where [ResolveFirstMediaSource] resolves; outlives any one source. */
    private val resolveScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + resolveDispatcher,
    )

    // Every network source below is gated when it OPENS ([DownloadModeGate]), not only here when its
    // media source is made: a source made in Stream only and opened after the switch flips is refused.
    // A file on the phone always opens.
    private val localDataSourceFactory = DownloadModeGate.gate(DefaultDataSource.Factory(context), streamAllowed)
    private val localFactory = DefaultMediaSourceFactory(localDataSourceFactory)

    internal val jioSaavnHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jioSaavnDataSourceFactory = DownloadModeGate.gate(
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(jioSaavnHttpClient),
        streamAllowed,
    )
    private val jioSaavnFactory = DefaultMediaSourceFactory(jioSaavnDataSourceFactory)

    private val addonSourceFactory: DataSource.Factory? = addonDataSourceFactory?.let { DownloadModeGate.gate(it, streamAllowed) }
    private val addonFactory: DefaultMediaSourceFactory? = addonSourceFactory?.let { DefaultMediaSourceFactory(it) }

    // Full-timeline placeholders: stash-resolve://track/<id> items resolve
    // their URL inside LazyResolvingDataSource.open() on the loader thread.
    // Cold-jump path only — the next-up prefetch upgrades the common case to
    // a real URL in place, which then routes through the branches above.
    private val lazyDataSourceFactory =
        DataSource.Factory {
            LazyResolvingDataSource(
                resolver = resolver,
                urlCache = urlCache,
                trackDao = trackDao,
                // Each delegate gated too: a placeholder whose URL was cached opens it without a resolve.
                httpDelegate = {
                    DownloadModeGate(
                        DefaultHttpDataSource.Factory().setUserAgent("Stash/0.9.26")
                            .setConnectTimeoutMs(10_000).setReadTimeoutMs(30_000)
                            .createDataSource(),
                        streamAllowed,
                    )
                },
                jioSaavnDelegate = { jioSaavnDataSourceFactory.createDataSource() },
                // "Play my cloud copies first": a stream answered by the user's own cloud file.
                cloudDelegate = cloudDataSourceFactory?.let { { cloudSourceFactory.createDataSource() } },
                cloudCopies = cloudCopies,
                // A song whose download finished after it was queued plays its file.
                downloadedFile = { row -> row.usableDownload(context) },
                localDelegate = { localDataSourceFactory.createDataSource() },
                streamAllowed = streamAllowed,
                addonDelegate = addonSourceFactory?.let { f -> { f.createDataSource() } },
                onOpened = onOpenedFor(OpenedStream.beingCreated()),
            )
        }

    /**
     * What a placeholder's data source says when it opens a stream: to the period the add-on check
     * is creating ([checkedLazy]), so it knows whose stream it judges, and to [addonCheck], so a song
     * whose link an add-on handed out is found in the queue when that add-on leaves or is set aside.
     */
    private fun onOpenedFor(period: OpenedStream?): ((Long, StreamUrl) -> Unit)? {
        val check = addonCheck
        if (period == null && check == null) return null
        return { trackId, stream ->
            period?.stream = stream
            check?.opened(trackId, stream.origin)
        }
    }
    private val lazyFactory = DefaultMediaSourceFactory(lazyDataSourceFactory)

    /**
     * A placeholder's lazy source, its periods judged by [addonCheck] once their data source has
     * opened a stream: an add-on's stream is checked before it plays, any other is left alone.
     */
    private fun checkedLazy(item: MediaItem): MediaSource {
        // A placeholder's stream is a file read through its data source, never a manifest (a manifest's
        // answer gets a source of its own, [ResolveFirstMediaSource]). A slot put back from a manifest's
        // item may still carry its MIME type (`application/dash+xml`), which would make this a DASH
        // source that reads the file as a manifest and fails (review-888 M1).
        val source = lazyFactory.createMediaSource(
            if (item.localConfiguration?.mimeType == null) item else item.buildUpon().setMimeType(null).build(),
        )
        val check = addonCheck ?: return source
        return AddonCheckedMediaSource(source, check) { opened -> opened?.let { AddonStreamCheck.claimOf(it, item) } }
    }

    /**
     * A placeholder's resolve, whose answer may be played now: an add-on that left or was set aside
     * while it answered ([StreamUrlCache.isServable]; an unofficial one may take 6 s) is asked past
     * once, and its answer is never handed on (review-888 m1).
     */
    private suspend fun resolveServable(entity: com.stash.core.data.db.entity.TrackEntity): StreamUrl? {
        val first = resolver.resolve(entity, allowYouTube = true, allowYtDlp = true) ?: return null
        if (urlCache.isServable(first)) return first
        return resolver.resolve(entity, allowYouTube = true, allowYtDlp = true)?.takeIf { urlCache.isServable(it) }
    }

    /**
     * [source], for an item resolved to an add-on's stream, judged by [addonCheck] before it plays,
     * and never opened again by a new period once its add-on left or was set aside ([isGone]).
     */
    private fun checked(source: MediaSource, item: MediaItem): MediaSource {
        val check = addonCheck ?: return source
        val claim = AddonStreamCheck.claimOf(item) ?: return source
        return AddonCheckedMediaSource(source, check, isGone) { claim }
    }

    /** [source] for a placeholder that resolved to the add-on stream [resolved] before its source was picked. */
    private fun checked(source: MediaSource, resolved: StreamUrl, item: MediaItem): MediaSource {
        val check = addonCheck ?: return source
        val claim = AddonStreamCheck.claimOf(resolved, item) ?: return source
        return AddonCheckedMediaSource(source, check, isGone) { claim }
    }

    /**
     * Whether an add-on's link may no longer be opened afresh: its add-on left or is set aside. A
     * placeholder needs no such word: its link is dropped from [urlCache] then, and it resolves again.
     */
    private val isGone: (String) -> Boolean = { origin -> !urlCache.isServableOrigin(origin) }

    /** What a stream opens as in Download mode: an error, so the player skips it. */
    private val refusedDataSourceFactory = DataSource.Factory { DownloadModeRefusal() }
    private val refusedFactory = DefaultMediaSourceFactory(refusedDataSourceFactory)

    /** What an add-on's stream opens as with no add-on source wired: an error that says so. */
    private val unwiredAddonDataSourceFactory = DataSource.Factory { DownloadModeRefusal(ADDON_NOT_WIRED) }
    private val unwiredAddonFactory = DefaultMediaSourceFactory(unwiredAddonDataSourceFactory)

    private val cloudSourceFactory: DataSource.Factory = cloudDataSourceFactory
        ?.let { DownloadModeGate.gate(it, streamAllowed, alwaysNetwork = true) }
        ?: DataSource.Factory { throw java.io.IOException("cloud playback isn't wired") }
    private val cloudUncachedSourceFactory: DataSource.Factory? =
        cloudUncachedDataSourceFactory?.let { DownloadModeGate.gate(it, streamAllowed, alwaysNetwork = true) }
    // A blocked or changed cloud file fails at once instead of being retried (CloudLoadErrorPolicy).
    private val cloudFactory = DefaultMediaSourceFactory(cloudSourceFactory).apply {
        setLoadErrorHandlingPolicy(CloudLoadErrorPolicy())
    }

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        localFactory.setDrmSessionManagerProvider(provider)
        addonFactory?.setDrmSessionManagerProvider(provider)
        refusedFactory.setDrmSessionManagerProvider(provider)
        unwiredAddonFactory.setDrmSessionManagerProvider(provider)
        jioSaavnFactory.setDrmSessionManagerProvider(provider)
        lazyFactory.setDrmSessionManagerProvider(provider)
        cloudFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        localFactory.setLoadErrorHandlingPolicy(policy)
        addonFactory?.setLoadErrorHandlingPolicy(policy)
        refusedFactory.setLoadErrorHandlingPolicy(policy)
        unwiredAddonFactory.setLoadErrorHandlingPolicy(policy)
        jioSaavnFactory.setLoadErrorHandlingPolicy(policy)
        lazyFactory.setLoadErrorHandlingPolicy(policy)
        cloudFactory.setLoadErrorHandlingPolicy(CloudLoadErrorPolicy(policy))
        return this
    }

    override fun getSupportedTypes(): IntArray = localFactory.supportedTypes

    /**
     * The byte source [createMediaSource] would play [mediaItem] from, with the
     * same routing. The cast media server reads through it, so a speaker gets
     * the lazy resolve, the YouTube 403 refresh and JioSaavn's no-redirect
     * client exactly as local playback does (spec 2026-10-06 §4) — minus the
     * stream cache, see [StreamingMediaSourceFactory.createUncachedDataSourceFactory].
     */
    fun dataSourceFactoryFor(mediaItem: MediaItem): DataSource.Factory {
        if (mediaItem.localConfiguration?.uri?.isRefusedStream() == true) return refusedDataSourceFactory
        if (mediaItem.localConfiguration?.uri?.scheme == STASH_RESOLVE_SCHEME) return lazyDataSourceFactory
        if (mediaItem.localConfiguration?.uri?.scheme == com.stash.core.model.STASH_CLOUD_SCHEME) {
            return cloudUncachedSourceFactory ?: cloudSourceFactory
        }
        streamingTrackId(mediaItem)?.let { return streamingFactory.createUncachedDataSourceFactory(it) }
        if (isJioSaavnOrigin(mediaItem)) return jioSaavnDataSourceFactory
        // An add-on's URL on the add-on media client, or refused: never on the default source.
        if (isAddonOrigin(mediaItem)) return addonSourceFactory ?: unwiredAddonDataSourceFactory
        return localDataSourceFactory
    }

    /**
     * True for a stream in Download mode. A placeholder isn't refused here: it may be a
     * downloaded song queued before its file landed, and [LazyResolvingDataSource] refuses
     * only the resolve.
     */
    private fun android.net.Uri.isRefusedStream(): Boolean {
        if (streamAllowed()) return false
        return when (scheme?.lowercase()) {
            null, "file", "content", "android.resource", STASH_RESOLVE_SCHEME -> false
            else -> true
        }
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        // Placeholder items (full-timeline queue) resolve in the DataSource
        // layer — checked first because they carry no http(s) scheme for the
        // predicates below to match.
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.isRefusedStream() == true) return refusedFactory.createMediaSource(mediaItem)
        // The user's own cloud file: its own resolver and cache, never the online chain below.
        if (uri?.scheme == com.stash.core.model.STASH_CLOUD_SCHEME) return cloudFactory.createMediaSource(mediaItem)
        if (uri?.scheme == STASH_RESOLVE_SCHEME) {
            val trackId = uri.lastPathSegment?.toLongOrNull()
            if (trackId != null && !isRowOverride(uri) && streamAllowed() &&
                (resolveBeforeSourcePick() || uri.getQueryParameter("sc") == "1" || urlCache.get(trackId)?.origin == "soundcloud")) {
                return ResolveFirstMediaSource(
                    mediaItem = mediaItem,
                    scope = resolveScope,
                    resolve = {
                        // A downloaded file needs no resolve: the lazy child opens it.
                        val row = trackDao.getById(trackId)
                        if (row?.usableDownload(context) != null) {
                            null
                        } else {
                            urlCache.get(trackId)?.takeIf {
                                (row?.soundCloudId == null && row?.source != com.stash.core.model.MusicSource.SOUNDCLOUD) || it.origin == "soundcloud"
                            } ?: (if (uri.getQueryParameter("sc") == "1" && row?.soundCloudId == null) null else placeholderEntity(trackDao, trackId, uri))
                                ?.let { resolveServable(it) }
                                ?.also { urlCache.put(trackId, it) }
                        }
                    },
                    childFor = { item, resolved ->
                        // A manifest's segments are read by the DASH source, never by a placeholder's data
                        // source: which add-on answered is noted here (a progressive child notes it as it opens).
                        if (resolved != null) addonCheck?.opened(trackId, resolved.origin)
                        if (resolved != null && !urlCache.isServable(resolved)) {
                            // Its add-on left or was set aside since it answered, and the note above may have come
                            // too late for the queue to see (review-888 m1): the lazy child resolves again as it
                            // opens, without it.
                            checkedLazy(item)
                        } else if (resolved?.isHls == true) {
                            streamingFactory.createHlsMediaSource(item, resolved)
                        } else if (resolved?.isDash == true) {
                            // From here on the player shows the DASH child's item as the slot's: the manifest's URL.
                            // It says which stream that is, as the look-ahead says it of a slot it swaps to a URL
                            // (StreamStamp): without it the crossfade took the URL for an unresolved queue-fill one
                            // and never faded into the song (#880), and the spare primed on the slot (or the new
                            // master after a fade) sent an add-on's manifest to the default HTTP source.
                            val stamped = StreamStamp.of(item, resolved)
                            if (AddonPlayback.isAddonOrigin(resolved.origin)) {
                                // An add-on's manifest and segments on the add-on media client, or refused;
                                // judged by what the manifest says before a segment plays.
                                addonSourceFactory?.let { checked(streamingFactory.createDashMediaSource(stamped, resolved.url, it), resolved, stamped) }
                                    ?: unwiredAddonFactory.createMediaSource(item)
                            } else {
                                streamingFactory.createDashMediaSource(stamped, resolved.url, null)
                            }
                        } else {
                            checkedLazy(item)
                        }
                    },
                )
            }
            return checkedLazy(mediaItem)
        }
        // A resolved Tidal MPD (prefetch / foreground tap set the item's URI to it).
        // Keyed on the path, not an extra: the session strips local configuration,
        // and upstream serves every MPD at `/dash/td/<id>.mpd`.
        // An add-on's manifest can sit at any path (`/dash?id=1`), so its item says it is one
        // (review-882 N4): it went to the progressive source, which cannot read a manifest.
        if (uri != null && (uri.scheme == "https" || uri.scheme == "http") &&
            (uri.path?.endsWith(".mpd") == true || (isAddonOrigin(mediaItem) && isDashStream(mediaItem)))
        ) {
            if (isAddonOrigin(mediaItem)) {
                // An add-on's manifest and segments on the add-on media client, or refused; judged before it plays.
                return addonSourceFactory?.let { checked(streamingFactory.createDashMediaSource(mediaItem, uri.toString(), it), mediaItem) }
                    ?: unwiredAddonFactory.createMediaSource(mediaItem)
            }
            return streamingFactory.createDashMediaSource(mediaItem, uri.toString(), null)
        }
        // Order matches existing precedence: YouTube-trackId refresh chain first,
        // then JioSaavn authed-HTTP, then local/default.
        streamingTrackId(mediaItem)?.let { trackId ->
            return streamingFactory.create(trackId).createMediaSource(mediaItem)
        }
        if (isJioSaavnOrigin(mediaItem)) {
            return jioSaavnFactory.createMediaSource(mediaItem)
        }
        // An add-on's URL on the add-on media client, or refused: never on the default source. Judged
        // by what its bytes are before a sound of it plays (AddonStreamCheck).
        if (isAddonOrigin(mediaItem)) {
            return addonFactory?.let { checked(it.createMediaSource(mediaItem), mediaItem) } ?: unwiredAddonFactory.createMediaSource(mediaItem)
        }
        return localFactory.createMediaSource(mediaItem)
    }
}

/** What [StashMediaSourceFactory] says when an add-on's stream reaches it with no add-on source to read it on. */
internal const val ADDON_NOT_WIRED = "add-on playback isn't wired"

/**
 * A source that never opens, so the player skips the song: a stream in Download mode, or, with
 * [reason], whatever else may not be read (an add-on's stream with no add-on source wired).
 */
@OptIn(UnstableApi::class)
private class DownloadModeRefusal(private val reason: String? = null) : DataSource {
    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long =
        throw java.io.IOException(reason ?: "Download mode: ${dataSpec.uri.scheme} streams don't play")
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw java.io.IOException("not open")
    override fun getUri(): android.net.Uri? = null
    override fun close() = Unit
}
