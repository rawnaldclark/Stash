package com.stash.core.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a FLAC upgrade sweep runs automatically after every sync. Off by default. */
@Singleton
class AutoFlacUpgradePreference @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("auto_flac_upgrade", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun current(): Boolean = prefs.getBoolean(KEY, false)

    fun set(value: Boolean) {
        prefs.edit().putBoolean(KEY, value).apply()
        _enabled.value = value
    }

    private companion object {
        const val KEY = "auto_upgrade_enabled"
    }
}