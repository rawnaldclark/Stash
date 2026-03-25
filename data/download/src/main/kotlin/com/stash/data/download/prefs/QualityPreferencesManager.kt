package com.stash.data.download.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.QualityTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Extension property providing a singleton DataStore for quality preferences. */
private val Context.qualityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quality_preferences",
)

/**
 * Persists the user's preferred audio quality tier via DataStore.
 *
 * Exposes the current selection as a [Flow] and provides a suspend function
 * to update it. The tier is stored by its enum name so it survives across
 * app versions even if the ordinal changes.
 */
@Singleton
class QualityPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val qualityKey = stringPreferencesKey("quality_tier")

    /** Emits the current [QualityTier], defaulting to [QualityTier.BEST]. */
    val qualityTier: Flow<QualityTier> = context.qualityDataStore.data.map { prefs ->
        val name = prefs[qualityKey]
        name?.let { runCatching { QualityTier.valueOf(it) }.getOrNull() } ?: QualityTier.BEST
    }

    /** Persists the selected [tier]. */
    suspend fun setQualityTier(tier: QualityTier) {
        context.qualityDataStore.edit { prefs ->
            prefs[qualityKey] = tier.name
        }
    }
}

/**
 * Maps a [QualityTier] to the corresponding yt-dlp command-line arguments.
 *
 * All tiers extract audio-only and transcode to Opus. The `--audio-quality`
 * flag controls VBR quality on a 0-10 scale (0 = best, 10 = worst).
 */
fun QualityTier.toYtDlpArgs(): List<String> = when (this) {
    QualityTier.BEST -> listOf(
        "-f", "bestaudio[ext=webm]/bestaudio",
        "-x", "--audio-format", "opus", "--audio-quality", "0",
    )
    QualityTier.HIGH -> listOf(
        "-f", "bestaudio",
        "-x", "--audio-format", "opus", "--audio-quality", "3",
    )
    QualityTier.NORMAL -> listOf(
        "-f", "bestaudio",
        "-x", "--audio-format", "opus", "--audio-quality", "5",
    )
    QualityTier.LOW -> listOf(
        "-f", "bestaudio",
        "-x", "--audio-format", "opus", "--audio-quality", "8",
    )
}
