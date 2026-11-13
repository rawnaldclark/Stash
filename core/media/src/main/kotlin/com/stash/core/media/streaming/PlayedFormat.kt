package com.stash.core.media.streaming

import com.stash.core.model.Track

/**
 * What the player's extractor found in the stream it is playing, as Now Playing's quality tags
 * (review-882 M9).
 *
 * A stream's label otherwise comes from what the source said about it. For Stash's own sources
 * that is their catalog's word. An add-on's word is only its own: one that echoes the
 * `quality=LOSSLESS` it was sent while serving AAC was shown as FLAC. So for a stream an add-on
 * answered, the tags come from the bytes: the codec the extractor read, and its sample rate and
 * bit depth, replace what the add-on stated as soon as the player knows them.
 *
 * Pure, so it is tested without a player: the caller passes the selected audio track's
 * `Format` fields (`sampleMimeType`, `sampleRate`, `pcmEncoding`, `bitrate`; media3 writes -1
 * for a value it does not know).
 */
object PlayedFormat {

    /** The tag Now Playing shows for media3's [sampleMimeType]; null for one this app has no tag for. */
    fun codecTag(sampleMimeType: String?): String? = when (sampleMimeType?.lowercase()) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/alac" -> "alac"
        "audio/raw", "audio/wav", "audio/x-wav" -> "wav"
        "audio/mp4a-latm", "audio/aac" -> "aac"
        "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> "mp3"
        "audio/opus" -> "opus"
        "audio/vorbis" -> "vorbis"
        "audio/eac3", "audio/eac3-joc", "audio/ac3" -> "eac3"
        else -> null
    }

    private val LOSSLESS = setOf("flac", "alac", "wav")

    /** Bits per sample of a media3 `C.ENCODING_PCM_*` value; null for float, unknown or none. */
    internal fun bitsOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
        PCM_8BIT -> 8
        PCM_16BIT, PCM_16BIT_BIG_ENDIAN -> 16
        PCM_24BIT, PCM_24BIT_BIG_ENDIAN -> 24
        PCM_32BIT, PCM_32BIT_BIG_ENDIAN -> 32
        else -> null
    }

    /**
     * [track] with its quality tags taken from what is being decoded, when an add-on answered its
     * stream ([AddonPlayback.isAddonOrigin]) and the extractor has read a codec this app can
     * name. Any other track, and an add-on's until then, is returned as it is.
     *
     * A lossy codec carries no bit depth, whatever the add-on stated; its sample rate and bit
     * rate are the extractor's when it has them. A lossless one keeps a stated depth or rate the
     * extractor did not report (a FLAC in fragmented MP4 does not always say its depth).
     */
    fun applyTo(track: Track, sampleMimeType: String?, sampleRate: Int, pcmEncoding: Int, bitrate: Int): Track {
        if (!AddonPlayback.isAddonOrigin(track.streamOrigin)) return track
        val codec = codecTag(sampleMimeType) ?: return track
        val rate = sampleRate.takeIf { it > 0 }
        val lossless = codec in LOSSLESS
        return track.copy(
            fileFormat = codec,
            bitsPerSample = if (lossless) bitsOf(pcmEncoding) ?: track.bitsPerSample else null,
            sampleRateHz = rate ?: track.sampleRateHz,
            qualityKbps = when {
                bitrate > 0 -> bitrate / 1000
                // What the add-on stated for a file it called something else says nothing of this one.
                codec != track.fileFormat -> 0
                else -> track.qualityKbps
            },
        )
    }

    // media3 `C.ENCODING_PCM_*`, by value: this file stays free of the player's classes.
    private const val PCM_8BIT = 3
    private const val PCM_16BIT = 2
    private const val PCM_16BIT_BIG_ENDIAN = 0x10000000
    private const val PCM_24BIT = 21
    private const val PCM_24BIT_BIG_ENDIAN = 0x50000000
    private const val PCM_32BIT = 22
    private const val PCM_32BIT_BIG_ENDIAN = 0x60000000
}
