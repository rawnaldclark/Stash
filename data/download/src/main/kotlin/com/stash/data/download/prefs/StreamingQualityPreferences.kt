package com.stash.data.download.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// `internal` (not `private`) only so module unit tests can clear the shared
// in-memory instance between cases — the file is process-cached by DataStore,
// so deleting it on disk alone doesn't reset state (see StreamingQualityPreferencesTest).
internal val Context.streamingQualityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streaming_quality_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Streaming-specific lossless quality, split from the download tier.
 *
 *  - [wifiTier] / [cellularTier] — tier requested when streaming on the
 *    respective transport. Cellular defaults to CD (the data-saving floor).
 *  - [saveData] — master override; when true, callers should force CD on
 *    every network. The override itself lives in StreamQualityPolicy.
 *  - [preferTidalAac] — opt-in (default off): streaming asks Tidal for its
 *    320 kbps AAC before Qobuz, to save data. Downloads ignore it.
 *
 * Lives in :data:download (not :core:data's StreamingPreference) because
 * [LosslessQualityTier] is defined here and :core:data cannot depend on
 * this module.
 */
@Singleton
class StreamingQualityPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val losslessPrefs: LosslessSourcePreferences,
) {
    private val wifiKey = stringPreferencesKey("streaming_wifi_tier")
    private val cellularKey = stringPreferencesKey("streaming_cellular_tier")
    private val saveDataKey = booleanPreferencesKey("streaming_save_data")
    private val preferTidalAacKey = booleanPreferencesKey("prefer_tidal_aac_streaming")
    private val preferCloudCopiesKey = booleanPreferencesKey("prefer_cloud_copies")

    val wifiTier: Flow<LosslessQualityTier> = context.streamingQualityDataStore.data.map { prefs ->
        prefs[wifiKey]?.let { runCatching { LosslessQualityTier.valueOf(it) }.getOrNull() }
            ?: LosslessQualityTier.HI_RES
    }

    val cellularTier: Flow<LosslessQualityTier> = context.streamingQualityDataStore.data.map { prefs ->
        prefs[cellularKey]?.let { runCatching { LosslessQualityTier.valueOf(it) }.getOrNull() }
            ?: LosslessQualityTier.CD
    }

    val saveData: Flow<Boolean> = context.streamingQualityDataStore.data.map { prefs ->
        prefs[saveDataKey] ?: false
    }

    suspend fun wifiTierNow(): LosslessQualityTier = wifiTier.first()
    suspend fun cellularTierNow(): LosslessQualityTier = cellularTier.first()
    /** "Prefer Tidal AAC while streaming": off unless the user turns it on. */
    val preferTidalAac: Flow<Boolean> = context.streamingQualityDataStore.data.map { prefs ->
        prefs[preferTidalAacKey] ?: false
    }

    /**
     * "Play my cloud copies first" (#679): a song about to stream plays from the user's own lossless
     * file in their cloud storage when one matches, before Qobuz or Tidal. Off unless turned on.
     */
    val preferCloudCopies: Flow<Boolean> = context.streamingQualityDataStore.data.map { prefs ->
        prefs[preferCloudCopiesKey] ?: false
    }

    suspend fun saveDataNow(): Boolean = saveData.first()
    suspend fun preferTidalAacNow(): Boolean = preferTidalAac.first()
    suspend fun preferCloudCopiesNow(): Boolean = preferCloudCopies.first()

    suspend fun setWifiTier(tier: LosslessQualityTier) {
        context.streamingQualityDataStore.edit { it[wifiKey] = tier.name }
    }

    suspend fun setCellularTier(tier: LosslessQualityTier) {
        context.streamingQualityDataStore.edit { it[cellularKey] = tier.name }
    }

    suspend fun setSaveData(value: Boolean) {
        context.streamingQualityDataStore.edit { it[saveDataKey] = value }
    }

    suspend fun setPreferTidalAac(value: Boolean) {
        context.streamingQualityDataStore.edit { it[preferTidalAacKey] = value }
    }

    suspend fun setPreferCloudCopies(value: Boolean) {
        context.streamingQualityDataStore.edit { it[preferCloudCopiesKey] = value }
    }

    /**
     * One-shot migration: when no streaming Wi-Fi tier has been written
     * yet, seed it from the user's current download tier (so Wi-Fi keeps
     * what they had) and leave cellular at its CD default. Idempotent —
     * a present key short-circuits. Call once at startup.
     */
    suspend fun migrateIfNeeded() {
        val already = context.streamingQualityDataStore.data.first()[wifiKey] != null
        if (already) return
        val downloadTier = losslessPrefs.qualityTierNow()
        context.streamingQualityDataStore.edit { prefs ->
            prefs[wifiKey] = downloadTier.name
            if (prefs[cellularKey] == null) prefs[cellularKey] = LosslessQualityTier.CD.name
        }
    }
}
