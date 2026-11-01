package com.stash.core.media.streaming

/**
 * What the lossy rungs ask for: JioSaavn's AAC variant ([jioSaavnKbps], it holds every song at 96,
 * 160 and 320 kbps) and YouTube's audio format ([youTubeLowest]: its lowest, Opus 249/250 at roughly
 * 50-70 kbps, instead of its best, Opus ~160 / AAC 128). Picked by [StreamQualityPolicy.lossyQuality].
 */
enum class LossyQuality(val jioSaavnKbps: Int, val youTubeLowest: Boolean) {
    /** Data saver not in effect (off, or on unmetered Wi-Fi): JioSaavn 320, YouTube's best. */
    FULL(jioSaavnKbps = 320, youTubeLowest = false),

    /**
     * Data saver on a metered network with Lossless on: the stream FLAC would have been, at about a
     * sixth of CD's size. JioSaavn 160 and YouTube's best, the same size class (~4-5 MB per four
     * minutes against ~28 for CD FLAC).
     */
    DATA_SAVER(jioSaavnKbps = 160, youTubeLowest = false),

    /**
     * Data saver on a metered network with Lossless off: the user already chose lossy everywhere,
     * so the lowest each source has, JioSaavn 96 and YouTube's lowest (as Save Data always did).
     */
    LOWEST(jioSaavnKbps = 96, youTubeLowest = true),
}
