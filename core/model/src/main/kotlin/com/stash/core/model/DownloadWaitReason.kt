package com.stash.core.model

/**
 * Why a download waits in the queue (status WAITING_FOR_LOSSLESS). Stored on the queue row
 * as [code] (its `error_message`, which a waiting row has no other use for), so the Downloads
 * screen, Now Playing and the retry sweep each read the row's own reason instead of guessing
 * it from today's settings.
 */
enum class DownloadWaitReason(val code: String) {
    /** No lossless copy yet (Download quality Lossless, lossy fallback off). */
    LOSSLESS("wait:lossless"),

    /** Tidal has no AAC of it yet (Download quality AAC 320, lossy fallback off). */
    AAC("wait:aac"),

    /** The day's Tidal & Qobuz allowance is spent: it goes out after the reset. */
    RESET("wait:reset"),

    /** Tidal was busy (or this device paced it): tried again shortly, whatever the fallback. */
    TIDAL_BUSY("wait:tidal_busy"),

    /** The user picked a YouTube video for it, and the lossy fallback is off. */
    FALLBACK("wait:fallback"),
    ;

    companion object {
        /** The reason a row stores, or null for a row deferred before reasons were kept. */
        fun fromCode(code: String?): DownloadWaitReason? = entries.firstOrNull { it.code == code }
    }
}
