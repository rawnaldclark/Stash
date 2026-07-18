package com.stash.core.data.prefs

import com.stash.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for reading and writing the user's light/dark theme preference.
 *
 * Lives in `:core:data` so feature modules can inject it without depending on
 * a concrete preferences implementation. The DataStore-backed implementation
 * is [com.stash.core.data.prefs.ThemePreferencesManager] and is bound via Hilt.
 */
interface ThemePreference {

    /** Emits the current [ThemeMode], defaulting to [ThemeMode.SYSTEM]. */
    val themeMode: Flow<ThemeMode>

    /** Persists the selected [mode] for the next app session and beyond. */
    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * Emits whether dark theme should use pure-black (AMOLED) backgrounds.
     * Dormant while the effective theme is light. Defaults to false.
     */
    val amoledDark: Flow<Boolean>

    /** Persists the pure-black dark preference. */
    suspend fun setAmoledDark(enabled: Boolean)
}
