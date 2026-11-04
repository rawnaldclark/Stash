package com.stash.core.data.prefs

import kotlinx.coroutines.flow.Flow

/** Settings › Playback › Headphones. */
interface HeadphonePreference {
    /** Whether headphones reconnecting soon after an unplug paused the music start it again. Off by default. */
    val resumeOnReconnect: Flow<Boolean>
    suspend fun setResumeOnReconnect(enabled: Boolean)
}
