package com.stash.feature.nowplaying.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.npSpacingDebugStore: DataStore<Preferences> by preferencesDataStore(
    name = "np_spacing_debug",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * DEBUG BUILDS ONLY: Now Playing's spacing overrides, set live from the browser tuner
 * (tools/np-tuner) through the adb-only receiver (app/src/debug DebugNpSpacing). Stored as the
 * tuner's JSON in its own small DataStore, so a tuned layout survives the app restarting.
 *
 * Release builds never create this: the app binds [NpSpacingSource.Production] there (see
 * `NpSpacingSource.select`), so no release build reads or writes this store.
 */
@Singleton
class NpSpacingOverrides @Inject constructor(
    @ApplicationContext private val context: Context,
) : NpSpacingSource {
    private val key = stringPreferencesKey("json")

    override val overridesJson: Flow<String?> =
        context.npSpacingDebugStore.data.map { it[key] }.distinctUntilChanged()

    /**
     * Applies the tuner's [json] over what is already overridden (a slider can send just its own
     * value) and returns the full JSON now in force. Clamped and filtered by [NpSpacingJson.parse].
     */
    suspend fun apply(json: String): String {
        var written = ""
        context.npSpacingDebugStore.edit { prefs ->
            val merged = NpSpacingJson.parse(json, base = NpSpacingJson.parse(prefs[key]))
            written = NpSpacingJson.write(merged)
            prefs[key] = written
        }
        return written
    }

    /** Back to the production values. */
    suspend fun reset() {
        context.npSpacingDebugStore.edit { it.remove(key) }
    }

    /** The JSON in force now: the overrides over the production values (for the receiver's log). */
    suspend fun current(): String = NpSpacingJson.write(NpSpacingJson.parse(overridesJson.first()))
}

/** How Now Playing's composables reach the bound [NpSpacingSource] without a ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface NpSpacingEntryPoint {
    fun npSpacingSource(): NpSpacingSource
}

/**
 * The spacing to lay Now Playing out with: the production values, with a debug build's tuner
 * overrides on top. Outside a Hilt app (previews, Robolectric) it is the production values.
 */
@Composable
internal fun rememberNpSpacing(): NpSpacing {
    val app = LocalContext.current.applicationContext
    val source = remember(app) {
        try {
            EntryPointAccessors.fromApplication(app, NpSpacingEntryPoint::class.java).npSpacingSource()
        } catch (e: Exception) {
            Log.d("NpSpacing", "no Hilt graph here; production spacing", e)
            NpSpacingSource.Production
        }
    }
    if (source === NpSpacingSource.Production) return remember { NpSpacing() }
    val json by source.overridesJson.collectAsState(initial = null)
    return remember(json) { NpSpacingJson.parse(json) }
}
