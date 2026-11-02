package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.ThemeMode
import com.stash.core.model.UiScale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Singleton DataStore for theme preferences, separate from other preference stores. */
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "theme_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persists the user's preferred [ThemeMode] via DataStore.
 *
 * Follows the same pattern as [com.stash.data.download.prefs.QualityPreferencesManager]:
 * the mode is stored by enum name (not ordinal) so it survives across app versions
 * even if the enum order changes.
 */
@Singleton
class ThemePreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : ThemePreference {
    private val themeKey = stringPreferencesKey("theme_mode")

    /** Emits the current [ThemeMode], defaulting to [ThemeMode.SYSTEM]. */
    override val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        val name = prefs[themeKey]
        name?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    /** Persists the selected [mode]. */
    override suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs ->
            prefs[themeKey] = mode.name
        }
    }

    private val amoledKey = booleanPreferencesKey("amoled_dark")

    /** Emits the pure-black (AMOLED) dark preference. On by default since 0.9.123 (the owner's setup). */
    override val amoledDark: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[amoledKey] ?: true
    }

    /** Persists the pure-black dark preference. */
    override suspend fun setAmoledDark(enabled: Boolean) {
        context.themeDataStore.edit { prefs ->
            prefs[amoledKey] = enabled
        }
    }

    private val uiScaleKey = floatPreferencesKey("ui_scale")

    /**
     * Emits the UI scale, defaulting to 1.0. Snapped on read as well as on write, so a value from
     * a restore or an older build can't put the layout off the range. Deduped: this store
     * re-emits on every theme write, and `MainActivity` recreates when the scale changes.
     */
    override val uiScale: Flow<Float> = context.themeDataStore.data.map { prefs ->
        UiScale.snap(prefs[uiScaleKey] ?: UiScale.DEFAULT)
    }.distinctUntilChanged()

    override val uiScaleNow: Float get() = ThemeSnapshot.readUiScale(context)

    /** Persists the UI scale, clamped to the range and snapped to the 0.05 grid. */
    override suspend fun setUiScale(scale: Float) {
        context.themeDataStore.edit { prefs ->
            prefs[uiScaleKey] = UiScale.snap(scale)
        }
    }
}
