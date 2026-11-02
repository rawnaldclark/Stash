package com.stash.core.data.prefs

import com.stash.core.model.AlbumColourMode
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

    /**
     * Settings › Appearance › Album colours: whether the app wears the playing song's colours
     * ([AlbumColourMode.FOLLOW_SONG], the default) or stays neutral.
     */
    val albumColours: Flow<AlbumColourMode>

    /** Persists the Album colours choice. */
    suspend fun setAlbumColours(mode: AlbumColourMode)

    /**
     * Settings › Appearance › Display size: the app-wide UI scale the user chose, on the
     * [com.stash.core.model.UiScale] grid, default 1.0. A small window may lay out at less (see
     * [com.stash.core.model.UiScale.maxFor]).
     */
    val uiScale: Flow<Float>

    /**
     * The same, read synchronously: the scale the open activity was laid out with, before any
     * per-window cap. Lets a screen show the right value on its first frame instead of 1.0
     * until the DataStore read lands.
     */
    val uiScaleNow: Float

    /** Persists the UI scale, clamped to the range and snapped to the 0.05 grid. */
    suspend fun setUiScale(scale: Float)
}
