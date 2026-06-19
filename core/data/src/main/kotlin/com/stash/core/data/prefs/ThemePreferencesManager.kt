package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    private val showBlurLayerKey = booleanPreferencesKey("show_blur_layer_amoled")

    /** Emits the current [ThemeMode], defaulting to [ThemeMode.SYSTEM]. */
    override val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        val name = prefs[themeKey]
        name?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }
    override val showBlurLayerInAmoled: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[showBlurLayerKey] ?: true
    }
    /** Persists the selected [mode]. */
    override suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs ->
            prefs[themeKey] = mode.name
        }
    }
    override suspend fun setShowBlurLayerInAmoled(show: Boolean) {
        context.themeDataStore.edit { prefs -> prefs[showBlurLayerKey] = show }
    }
}
