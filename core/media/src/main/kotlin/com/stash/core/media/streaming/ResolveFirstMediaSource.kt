package com.stash.core.media.streaming

import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A `stash-resolve://` placeholder that resolves BEFORE it picks its media source.
 *
 * [LazyResolvingDataSource] resolves inside `DataSource.open()`, which fixes the
 * media source as progressive before the URL is known — fine for every rung but
 * Tidal, whose Hi-Res answer is usually a DASH MPD that a progressive extractor
 * cannot read. This source runs [resolve] (which warms [StreamUrlCache]) when the
 * player prepares it, then hands the result to [childFor]: a DASH source for an
 * MPD, otherwise the existing lazy progressive source, which then opens straight
 * from the warm cache. Used only while a Tidal server is configured, so the
 * proven lazy path is untouched for everyone else.
 *
 * A metadata-only change ([canUpdateMediaItem]: a cover learnt, the quality stamped)
 * is taken in place: the same resolve, the same child, the song plays on. Media3's
 * default says no, and ExoPlayer then removed this source and added a new one, which
 * prepared again and started the playing song over.
 *
 * Threading: [prepareSourceInternal], [updateMediaItem] and every child call run on
 * the playback thread; [canUpdateMediaItem] on the application thread, so what it
 * reads is volatile. The resolve runs on [scope] and posts its result back through a
 * handler on that thread. A release before the result lands drops it ([released]).
 */
@OptIn(UnstableApi::class)
internal class ResolveFirstMediaSource(
    mediaItem: MediaItem,
    private val scope: CoroutineScope,
    private val resolve: suspend () -> StreamUrl?,
    /** The child for the resolve's answer, built from the item as it is then (the newest metadata). */
    private val childFor: (MediaItem, StreamUrl?) -> MediaSource,
) : CompositeMediaSource<Unit>() {

    /** The item as last set: its placeholder URI never changes, its metadata does ([updateMediaItem]). */
    @Volatile private var mediaItem: MediaItem = mediaItem

    /** The URI the child plays (a DASH child's MPD): the player shows the child's item, so a replacement may carry it. */
    @Volatile private var childUri: Uri? = null

    private var handler: Handler? = null
    private var job: Job? = null
    private var child: MediaSource? = null
    private var released = false

    override fun getMediaItem(): MediaItem = mediaItem

    /** True when [mediaItem] plays what this source plays (its placeholder's URI, or its child's): a metadata-only change. */
    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        val uri = mediaItem.localConfiguration?.uri ?: return false
        return uri == this.mediaItem.localConfiguration?.uri || uri == childUri
    }

    /** Keeps [mediaItem]'s metadata (the placeholder's URI stays) and hands it on to the child, which keeps its own URI. */
    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = this.mediaItem.withMetadataOf(mediaItem)
        val c = child ?: return
        val forChild = c.mediaItem.withMetadataOf(mediaItem)
        if (c.canUpdateMediaItem(forChild)) c.updateMediaItem(forChild)
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        released = false
        val h = Util.createHandlerForCurrentLooper()
        handler = h
        job = scope.launch {
            val resolved = try {
                resolve()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A miss here is not the error to surface: the lazy child re-resolves
                // and reports its own IOException through the normal path.
                null
            }
            h.post {
                if (released) return@post
                val c = childFor(mediaItem, resolved)
                child = c
                childUri = c.mediaItem.localConfiguration?.uri
                prepareChildSource(Unit, c)
            }
        }
    }

    override fun onChildSourceInfoRefreshed(childSourceId: Unit, mediaSource: MediaSource, newTimeline: Timeline) {
        refreshSourceInfo(newTimeline)
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child) { "createPeriod before the child was prepared" }
            .createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        child?.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        released = true
        job?.cancel()
        job = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        child = null
        childUri = null
        super.releaseSourceInternal()
    }

    private companion object {
        /** This item with [other]'s id and metadata: what a metadata-only replace changes. */
        fun MediaItem.withMetadataOf(other: MediaItem): MediaItem = buildUpon()
            .setMediaId(other.mediaId)
            .setMediaMetadata(other.mediaMetadata)
            .setRequestMetadata(other.requestMetadata)
            .build()
    }
}
