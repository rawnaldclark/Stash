package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How often Home's "Made for you" and "Mood & decades" rails reshuffle which
 * mixes lead. [days] is the length of one rotation; [OFF] keeps the plain
 * freshest-first order. A rotation flips at local midnight.
 */
enum class MixRotation(val days: Int) {
    OFF(0),
    DAILY(1),
    EVERY_3_DAYS(3),
    WEEKLY(7),
}

/** Dedicated DataStore for Home's mix-rail rotation. */
private val Context.homeMixRotationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_mix_rotation_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Home's mix-rail rotation: [rotation] (default [MixRotation.DAILY]: the rails
 * otherwise only change when a sync adds tracks, which is never for anyone
 * without auto-sync) and [unplayedFirst] (default off: mixes whose songs you've
 * barely played lead the rail).
 *
 * Both flows are deduped and caught to their defaults: they feed HomeViewModel's
 * `combine`, and an IOException that ended the chain would leave Home loading.
 */
@Singleton
class HomeMixRotationPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val rotationKey = stringPreferencesKey("mix_rotation")
    private val unplayedFirstKey = booleanPreferencesKey("unplayed_mixes_first")

    val rotation: Flow<MixRotation> = context.homeMixRotationDataStore.data.map { prefs ->
        prefs[rotationKey]?.let { name -> MixRotation.entries.firstOrNull { it.name == name } } ?: MixRotation.DAILY
    }.distinctUntilChanged().catch { emit(MixRotation.DAILY) }

    val unplayedFirst: Flow<Boolean> = context.homeMixRotationDataStore.data.map { prefs ->
        prefs[unplayedFirstKey] ?: false
    }.distinctUntilChanged().catch { emit(false) }

    suspend fun setRotation(value: MixRotation) {
        context.homeMixRotationDataStore.edit { it[rotationKey] = value.name }
    }

    suspend fun setUnplayedFirst(value: Boolean) {
        context.homeMixRotationDataStore.edit { it[unplayedFirstKey] = value }
    }
}
