package com.stash.core.media.preview

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Preview playback should be audible fast, not buffered long. We aggressively
 * shrink the ready-to-play thresholds so ExoPlayer enters READY the moment
 * ~250 ms of audio is decoded.
 */
object PreviewLoadControlFactory {
    fun create(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 1_000,
            /* maxBufferMs = */ DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            /* bufferForPlaybackMs = */ 250,
            /* bufferForPlaybackAfterRebufferMs = */ 500,
        )
        .build()
}
