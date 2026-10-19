package com.stash.core.model

/**
 * What a download saves (Settings › Audio & Quality › Download quality). Downloads only:
 * streaming keeps its own Lossless switch.
 *
 *  - [LOSSLESS]: FLAC first; with no FLAC, Tidal's AAC when "Tidal AAC when no FLAC" is on, then
 *    JioSaavn and YouTube when the lossy fallback is on; otherwise the download waits for lossless.
 *  - [AAC]: Tidal's 320 kbps AAC only, never FLAC, so no lossless relay call is made and the daily
 *    lossless allowance is never touched. With no AAC on Tidal: JioSaavn and YouTube when the
 *    lossy fallback is on; otherwise the download waits until Tidal has it.
 *  - [OPUS]: YouTube only, at the YouTube quality picked in Settings.
 *
 * Stored by name; an unknown name reads as the default.
 */
enum class DownloadMode {
    LOSSLESS,
    AAC,
    OPUS,
    ;

    companion object {
        /** [name] back to a mode, or null for anything else (an old or hand-edited value). */
        fun fromName(name: String?): DownloadMode? = entries.firstOrNull { it.name == name }
    }
}
