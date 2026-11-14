package com.stash.core.media.streaming

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

/**
 * A queue slot's song, played from [uri]: a new item, not this one changed (review-888 M1).
 *
 * A slot's item, as the player shows it, may be a manifest's own: a DASH source's item carries its
 * MPD URI and its MIME type (`application/dash+xml`, [StreamingMediaSourceFactory.createDashMediaSource]),
 * and a [ResolveFirstMediaSource] shows its DASH child's. A copy of that item with another URI keeps
 * the MIME type, and the source built for it reads the next stream (a placeholder's file, a URL, a
 * downloaded FLAC) as a manifest: the song fails and is skipped. So only the song is carried over:
 * its id, [metadata], its request metadata and clipping. [mimeType] is the new stream's own (null:
 * Media3 infers it from [uri]); see [mimeTypeOf].
 */
internal fun MediaItem.playedFrom(
    uri: Uri?,
    metadata: MediaMetadata = mediaMetadata,
    mimeType: String? = null,
    requestMetadata: MediaItem.RequestMetadata = this.requestMetadata,
): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(uri)
    .setMimeType(mimeType)
    .setMediaMetadata(metadata)
    .setRequestMetadata(requestMetadata)
    .setClippingConfiguration(clippingConfiguration)
    .build()

/**
 * This slot put back as [placeholder], its song's `stash-resolve://` placeholder, to be resolved
 * again from the start: with a URI no source in the player has had ([resolvedAgain]), so Media3
 * builds a new source rather than keep the one it has (and what it loaded), and with no MIME type
 * ([playedFrom]). [extras] are the slot's own, without the stamps of the stream it held. Any other
 * placeholder (a cloud song's `stash-cloud://`) is used as it is: its slot never holds a manifest.
 */
internal fun MediaItem.putBackAs(placeholder: Uri, extras: Bundle): MediaItem = playedFrom(
    uri = if (placeholder.scheme == STASH_RESOLVE_SCHEME) placeholder.resolvedAgain() else placeholder,
    metadata = mediaMetadata.buildUpon().setExtras(extras).build(),
)

/** The MIME type a slot playing [stream]'s URL needs: a manifest's own, else none (Media3 infers it from the URL). */
internal fun mimeTypeOf(stream: StreamUrl): String? = when {
    stream.isDash -> MimeTypes.APPLICATION_MPD
    stream.isHls -> MimeTypes.APPLICATION_M3U8
    else -> null
}
