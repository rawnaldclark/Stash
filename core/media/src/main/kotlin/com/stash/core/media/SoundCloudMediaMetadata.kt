package com.stash.core.media

import android.os.Bundle
import com.stash.core.model.Track

const val EXTRA_SOUNDCLOUD_ID = "stash_soundcloud_id"
const val EXTRA_SOUNDCLOUD_PERMALINK = "stash_soundcloud_permalink"
const val EXTRA_SOUNDCLOUD_PREVIEW = "stash_soundcloud_preview"
const val EXTRA_SOUNDCLOUD_LOCAL = "stash_soundcloud_local"

fun Bundle.putSoundCloudMetadata(track: Track) {
    if (track.soundCloudId == null) return
    putString(EXTRA_SOUNDCLOUD_ID, track.soundCloudId)
    putString(EXTRA_SOUNDCLOUD_PERMALINK, track.soundCloudPermalink)
    putBoolean(EXTRA_SOUNDCLOUD_PREVIEW, track.soundCloudPreview && !track.isDownloaded)
    putBoolean(EXTRA_SOUNDCLOUD_LOCAL, track.isDownloaded)
}
