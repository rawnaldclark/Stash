package com.stash.core.data.prefs

import kotlinx.coroutines.flow.Flow

enum class AutoMixMode { BEAT_SYNC, SMOOTH }

interface AutoMixPreference {
    val enabled: Flow<Boolean>
    val mode: Flow<AutoMixMode>
    suspend fun setEnabled(value: Boolean)
    suspend fun setMode(value: AutoMixMode)
}
