package com.stash.core.data.prefs

import kotlinx.coroutines.flow.Flow

interface HeadphonePreference {
    val resumeOnReconnect: Flow<Boolean>
    suspend fun setResumeOnReconnect(enabled: Boolean)
}
