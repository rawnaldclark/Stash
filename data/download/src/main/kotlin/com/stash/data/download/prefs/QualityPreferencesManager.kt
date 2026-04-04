package com.stash.data.download.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.prefs.QualityPreference
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
 * Implements [QualityPreference] so feature modules can depend on the
 * abstraction in `:core:data` without pulling in `:data:download`.
 * The tier is stored by its enum name so it survives across app versions
 * even if the ordinal changes.
 */
@Singleton
class QualityPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : QualityPreference {
    private val qualityKey = stringPreferencesKey("quality_tier")

    /** Emits the current [QualityTier], defaulting to [QualityTier.BEST]. */
    override val qualityTier: Flow<QualityTier> = context.qualityDataStore.data.map { prefs ->
        val name = prefs[qualityKey]
        name?.let { runCatching { QualityTier.valueOf(it) }.getOrNull() } ?: QualityTier.BEST
    }

    /** Persists the selected [tier]. */
    override suspend fun setQualityTier(tier: QualityTier) {
        context.qualityDataStore.edit { prefs ->
            prefs[qualityKey] = tier.name
        }
    }
}

/**
 * Maps a [QualityTier] to the corresponding yt-dlp command-line arguments.
 *
 * Requests YouTube's native Opus streams directly by format ID, avoiding
 * the FFmpeg extraction/transcode step entirely:
 *   - 251 = Opus ~160 kbps (highest quality)
 *   - 250 = Opus ~70 kbps
 *   - 249 = Opus ~50 kbps
 *
 * `--embed-metadata` tells yt-dlp to write title/artist/album tags into
 * the file during download, eliminating a separate ffmpeg metadata pass.
 */
fun QualityTier.toYtDlpArgs(): List<String> = when (this) {
    QualityTier.BEST -> listOf(
        "-f", "251/250/bestaudio[ext=webm]/bestaudio",
        "--embed-metadata",
    )
    QualityTier.HIGH -> listOf(
        "-f", "250/251/bestaudio[ext=webm]/bestaudio",
        "--embed-metadata",
    )
    QualityTier.NORMAL -> listOf(
        "-f", "250/251/bestaudio",
        "--embed-metadata",
    )
    QualityTier.LOW -> listOf(
        "-f", "250/249/bestaudio",
        "--embed-metadata",
    )
}
