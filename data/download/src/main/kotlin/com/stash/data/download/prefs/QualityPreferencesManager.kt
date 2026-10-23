package com.stash.data.download.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.model.QualityTier
import com.stash.data.download.files.MetadataEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Extension property providing a singleton DataStore for quality preferences. */
private val Context.qualityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quality_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
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

    /**
     * Emits the current [QualityTier], defaulting to [QualityTier.MAX] (v0.9.8+).
     *
     * Pre-v0.9.8 the default was [QualityTier.BEST]; users who explicitly
     * picked a tier keep their saved value because DataStore preserves
     * explicit writes. Users who never opened the Audio Quality card
     * pick up the new MAX default on next launch — both are 256 kbps
     * (BEST and MAX differ only in the yt-dlp arg string), so this is a
     * silent improvement, not a regression.
     */
    override val qualityTier: Flow<QualityTier> = context.qualityDataStore.data.map { prefs ->
        val name = prefs[qualityKey]
        name?.let { runCatching { QualityTier.valueOf(it) }.getOrNull() } ?: QualityTier.MAX
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
 * `--embed-metadata` tells yt-dlp to write whatever YouTube reports
 * (uploader, video title, description) into the file as a fallback
 * tag layer. As of v0.9.35 the primary tag write happens after the
 * download via [MetadataEmbedder.embedMetadata], which overwrites
 * the noisy YouTube-derived tags with Stash's clean
 * TITLE/ARTIST/ALBUMARTIST/ALBUM/ISRC set plus embedded cover art.
 * The yt-dlp flag stays as a safety net — if our ffmpeg pass fails
 * for any reason, the file still has yt-dlp's tags rather than none.
 */
fun QualityTier.toYtDlpArgs(): List<String> = when (this) {
    // Experimental: try true 256 first, fall to perceptually-equivalent
    // Opus 160, then AAC 128 as last resort. The extractor-args switch
    // tells yt-dlp to query the YouTube Music backends, where format
    // 141 is more likely to appear on free authenticated accounts.
    // Library Health surfaces the resulting format breakdown so we can
    // measure yield empirically before promoting MAX to default.
    // v0.9.16: removed the player_client override. The previous
    // value `web_music,android_music,ios_music,tv,web` is fully
    // broken on YouTube as of late 2025: web_music demands a GVS
    // PO Token we can't acquire from the app, android_music and
    // ios_music are unsupported by current yt-dlp, and tv returns
    // DRM-protected formats. Letting yt-dlp pick its own current
    // defaults works because the bundled youtubedl-android tracks
    // a yt-dlp version that knows which clients still serve free
    // formats. Format preference broadened to fall through 141 →
    // 140 → 251 → 250 → bestaudio so a single missing itag doesn't
    // fail the whole download.
    QualityTier.MAX -> listOf(
        // 141 (AAC 256k) for premium accounts that expose it; otherwise prefer
        // 251 (Opus 160k) over 140 (AAC 128k). The old order tried 140 before
        // 251, so free accounts (where 141 is absent) silently downloaded AAC
        // 128 instead of the higher-quality Opus 160 that's freely available
        // (measured on-device 2026-06-26: 54/54 free downloads now land on 251;
        // 774/Opus-256 was offered first but YouTube never serves it free here).
        "-f", "141/251/140/250/bestaudio",
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

/**
 * yt-dlp args for Download quality "Opus": Opus whenever YouTube has it, whatever the tier. The tier
 * picks the Opus bitrate (251 ~160 kbps, 250 ~70, 249 ~50); [toYtDlpArgs] would let Max and Best
 * take YouTube's AAC (141 / 140) first.
 *
 * A video with no Opus stream at all falls back to AAC (140 ~128 kbps; 139 ~48 first on Low)
 * (owner, 2026-10-23). It used to fail instead, and since a failed song stays queued, every sync
 * retried it through every client, about a minute and a half each time, and it never arrived.
 */
fun QualityTier.toOpusYtDlpArgs(): List<String> = listOf(
    "-f",
    when (this) {
        QualityTier.MAX, QualityTier.BEST, QualityTier.HIGH -> "251/250/249/bestaudio[acodec=opus]/140/bestaudio"
        QualityTier.NORMAL -> "250/251/249/bestaudio[acodec=opus]/140/bestaudio"
        QualityTier.LOW -> "249/250/251/bestaudio[acodec=opus]/139/140/bestaudio"
    },
    "--embed-metadata",
)
