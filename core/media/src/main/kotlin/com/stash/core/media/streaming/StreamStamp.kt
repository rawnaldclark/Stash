package com.stash.core.media.streaming

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BITRATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BIT_DEPTH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_DASH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_SAMPLE_RATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_SONG_MS

/**
 * The words on a queue slot's item that say which stream it plays, written wherever a slot's item
 * becomes a stream's URL: the look-ahead swapping a placeholder for the URL it resolved
 * (`PlayerRepositoryImpl.refreshControllerMediaItem`), and a placeholder whose resolve answered a
 * manifest, whose DASH child the player shows as the slot's item from then on
 * ([StashMediaSourceFactory], #880).
 *
 * Who reads them, and what goes wrong without them:
 * - the crossfade (`isResolvedForCrossfade`): a URL with no origin is taken for a queue-fill
 *   placeholder and never faded into, so the song cut over with no fade;
 * - the player's source factory, for an item that comes back to it (the crossfade's spare primed on
 *   the slot, the queue copied to the new master after a fade): the origin and the DASH word send an
 *   add-on's manifest, at whatever path, to the add-on media client, never the default HTTP source;
 * - the add-on put-back, which finds the slots holding a set-aside add-on's link by their origin;
 * - Now Playing's quality line.
 */
internal object StreamStamp {

    /** Every extra [onto] writes. A slot put back to be resolved again drops them all: they describe a stream it no longer plays. */
    val KEYS: List<String> = listOf(
        EXTRA_STREAM_CODEC, EXTRA_STREAM_BIT_DEPTH, EXTRA_STREAM_SAMPLE_RATE, EXTRA_STREAM_BITRATE, EXTRA_STREAM_ORIGIN, EXTRA_STREAM_DASH,
        EXTRA_STREAM_SONG_MS,
    )

    /**
     * [extras], changed in place and returned, stamped with [stream]: what it is (codec, bit depth,
     * sample rate, bitrate) and who answered it (origin), each when [stream] says; whether it is a
     * manifest, and the song's length as its source knew it (for the add-on check), always: an
     * earlier stream's word must not stay.
     */
    fun onto(extras: Bundle, stream: StreamUrl): Bundle = extras.apply {
        stream.codec?.let { putString(EXTRA_STREAM_CODEC, it) }
        stream.bitsPerSample?.let { putInt(EXTRA_STREAM_BIT_DEPTH, it) }
        stream.sampleRateHz?.let { putInt(EXTRA_STREAM_SAMPLE_RATE, it) }
        stream.bitrateKbps?.let { putInt(EXTRA_STREAM_BITRATE, it) }
        stream.origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
        if (stream.isDash) putBoolean(EXTRA_STREAM_DASH, true) else remove(EXTRA_STREAM_DASH)
        stream.songMs?.let { putLong(EXTRA_STREAM_SONG_MS, it) } ?: remove(EXTRA_STREAM_SONG_MS)
    }

    /** [item] stamped with [stream], as a new item: [item]'s own extras are left as they were. */
    fun of(item: MediaItem, stream: StreamUrl): MediaItem = item.buildUpon()
        .setMediaMetadata(
            item.mediaMetadata.buildUpon()
                .setExtras(onto(Bundle(item.mediaMetadata.extras ?: Bundle()), stream))
                .build(),
        )
        .build()
}
