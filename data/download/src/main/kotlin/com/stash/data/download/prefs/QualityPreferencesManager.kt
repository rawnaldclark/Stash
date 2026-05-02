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
 * YouTube audio format IDs (verified empirically via ffprobe on real
 * downloads — the previous comment block had 140 = "256 kbps" which is
 * wrong; format 140 is actually AAC LC ~128 kbps):
 *   - 141 = AAC LC ~256 kbps in m4a container — gated behind YouTube
 *           Music Premium auth on the default `web` client; the music-
 *           specific InnerTube clients (`web_music`, `android_music`,
 *           `ios_music`) sometimes expose it to free authenticated
 *           accounts. Cookies passed via `--cookies` (set in
 *           DownloadExecutor) carry the user's YT auth context.
 *   - 140 = AAC LC ~128 kbps in m4a container — universally available
 *   - 251 = Opus VBR up to ~160 kbps in webm container — universally
 *           available, perceptually equivalent to AAC ~256 kbps in
 *           published listening tests
 *   - 250 = Opus ~70 kbps
 *   - 249 = Opus ~50 kbps
 *
 * `--embed-metadata` tells yt-dlp to write title/artist/album tags into
 * the file during download, eliminating a separate ffmpeg metadata pass.
 */
fun QualityTier.toYtDlpArgs(): List<String> = when (this) {
    // Experimental: try true 256 first, fall to perceptually-equivalent
    // Opus 160, then AAC 128 as last resort. The extractor-args switch
    // tells yt-dlp to query the YouTube Music backends, where format
    // 141 is more likely to appear on free authenticated accounts.
    // Library Health surfaces the resulting format breakdown so we can
    // measure yield empirically before promoting MAX to default.
    QualityTier.MAX -> listOf(
        "-f", "141/251/140/bestaudio",
        "--extractor-args",
        "youtube:player_client=web_music,android_music,ios_music,tv,web",
        "--embed-metadata",
    )
    QualityTier.BEST -> listOf(
        "-f", "140/bestaudio[ext=m4a]/251/bestaudio",
        "--embed-metadata",
    )
    QualityTier.HIGH -> listOf(
        "-f", "251/250/bestaudio[ext=webm]/bestaudio",
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
