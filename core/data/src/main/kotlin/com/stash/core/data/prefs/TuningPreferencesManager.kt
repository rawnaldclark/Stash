package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.TuningMath
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Singleton DataStore for Tuning, separate from other preference stores. */
private val Context.tuningDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tuning_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Persists the Tuning reference via DataStore; a missing or unreadable value reads as 440 Hz. */
@Singleton
class TuningPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : TuningPreference {
    private val referenceKey = floatPreferencesKey(KEY_REFERENCE_HZ)

    override val referenceHz: Flow<Float> = context.tuningDataStore.data
        .map { prefs -> TuningMath.sanitize(prefs[referenceKey] ?: TuningMath.STANDARD_HZ) }
        .distinctUntilChanged()

    override suspend fun setReferenceHz(hz: Float) {
        context.tuningDataStore.edit { prefs -> prefs[referenceKey] = TuningMath.sanitize(hz) }
    }

    companion object {
        const val KEY_REFERENCE_HZ = "tuning_reference_hz"
    }
}
