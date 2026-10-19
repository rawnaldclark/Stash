package com.stash.core.data.lossless

import com.stash.core.model.DownloadMode

/**
 * The user's [DownloadMode], for modules that can't see `:data:download` (where the preference
 * lives): Now Playing's and the sync worker's "waiting" wording, which reads "Not available as
 * AAC yet" in AAC mode and "waiting for lossless" otherwise.
 */
interface DownloadModeReader {
    suspend fun currentDownloadMode(): DownloadMode
}
