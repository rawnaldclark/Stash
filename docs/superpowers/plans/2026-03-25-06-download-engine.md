# Phase 6: Download Engine -- Implementation Plan

**Date:** 2026-03-25
**Phase:** 6 of N
**Assumes:** Phases 1-5 complete (project scaffold, auth layer, Spotify/YT Music API clients, Room database, sync scheduling)
**Module:** `:data:download`
**Estimated tasks:** 22 tasks, ~2-5 minutes each

---

## Overview

This phase implements the complete audio download pipeline: yt-dlp binary management, YouTube track matching for Spotify tracks, concurrent download execution, metadata/album-art embedding into Opus files, file organization, quality tier configuration, and the Settings screen audio quality UI. By the end, `TrackDownloadWorker` from Phase 5 will call into a fully functional `DownloadManager` that takes a track from the queue and delivers a tagged, organized `.opus` file on disk.

---

## Architecture Decisions

**AD-1: Album art embedding strategy.** FFmpeg cannot natively write cover art into Opus/OGG containers (open FFmpeg ticket #4448 since 2015). yt-dlp's `--embed-thumbnail` is unreliable for Opus. Our approach: construct a FLAC-format METADATA_BLOCK_PICTURE binary blob in Kotlin, Base64-encode it, and pass it to ffmpeg via `-metadata "METADATA_BLOCK_PICTURE={base64}"`. This is the same approach used by MusicBrainz Picard and opustags.

**AD-2: Metadata source priority.** We use Spotify/YT Music API metadata (title, artist, album, track number) rather than YouTube video metadata, since YouTube titles often contain extraneous text ("Official Audio", "Lyrics", etc.). yt-dlp is invoked WITHOUT `--embed-metadata` -- we do all tagging ourselves via ffmpeg post-processing.

**AD-3: String similarity.** We implement Jaro-Winkler in-house (~60 lines of Kotlin) rather than adding a library dependency. The algorithm is well-defined and we only need one function.

**AD-4: Download concurrency.** Kotlin `Semaphore(3)` gates concurrent yt-dlp processes. Each download runs in its own `Dispatchers.IO` coroutine. A `Channel<DownloadRequest>(Channel.UNLIMITED)` serves as the queue, with priority reordering handled at enqueue time.

**AD-5: Opus-only format.** All quality tiers output Opus. Android 8+ (our minSdk 26) has native Opus decoding via ExoPlayer/Media3. This simplifies the pipeline -- one codec, one tag format (Vorbis comments), one art embedding method.

---

## Task List

### Task 6.1: Add youtubedl-android dependencies

**File:** `data/download/build.gradle.kts`

**Why:** The `:data:download` module needs the youtubedl-android library (JunkFood02 fork) and its ffmpeg companion module. These ship yt-dlp + Python 3.8 + ffmpeg as native `.so` files.

**Code:**
```kotlin
// data/download/build.gradle.kts
plugins {
    id("com.stash.android.library")
    id("com.stash.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stash.data.download"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))

    // youtubedl-android (JunkFood02 fork, used by Seal)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)

    // Kotlinx Serialization for parsing yt-dlp JSON output
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // DataStore for quality preferences
    implementation(libs.datastore.preferences)

    // Coil for downloading album art bitmaps
    implementation(libs.coil.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
```

**File:** `gradle/libs.versions.toml` (add entries)

```toml
[versions]
youtubedl = "0.17.+"

[libraries]
youtubedl-library = { group = "io.github.junkfood02.youtubedl-android", name = "library", version.ref = "youtubedl" }
youtubedl-ffmpeg = { group = "io.github.junkfood02.youtubedl-android", name = "ffmpeg", version.ref = "youtubedl" }
```

**File:** `app/src/main/AndroidManifest.xml` (verify attribute exists)

```xml
<application
    android:extractNativeLibs="true"
    ... >
```

**Verify:** Project syncs and compiles. `./gradlew :data:download:dependencies` shows youtubedl-android resolved.

---

### Task 6.2: YtDlpManager -- Initialization and binary lifecycle

**File:** `data/download/src/main/kotlin/com/stash/data/download/ytdlp/YtDlpManager.kt`

**Why:** Centralizes yt-dlp + ffmpeg binary initialization, update checks, and version tracking. Called once at app startup and periodically to self-update.

**Code:**
```kotlin
package com.stash.data.download.ytdlp

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YtDlpManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val initMutex = Mutex()
    private var initialized = false

    /**
     * Initialize yt-dlp and ffmpeg binaries. Safe to call multiple times;
     * subsequent calls are no-ops. Must be called before any download or
     * getInfo operation.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (initialized) return@withContext Result.success(Unit)
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                initialized = true
                Timber.i("yt-dlp and ffmpeg initialized successfully")
                Result.success(Unit)
            } catch (e: YoutubeDLException) {
                Timber.e(e, "Failed to initialize yt-dlp/ffmpeg")
                Result.failure(e)
            }
        }
    }

    /**
     * Check for yt-dlp updates from GitHub releases (STABLE channel).
     * Returns the new version string if updated, null if already current.
     */
    suspend fun updateYtDlp(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                UpdateChannel.STABLE,
            )
            when (status) {
                is UpdateStatus.DONE -> {
                    Timber.i("yt-dlp updated to: ${status.version}")
                    Result.success(status.version)
                }
                is UpdateStatus.ALREADY_UP_TO_DATE -> {
                    Timber.d("yt-dlp already up to date")
                    Result.success(null)
                }
                else -> Result.success(null)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update yt-dlp")
            Result.failure(e)
        }
    }

    /**
     * Get the currently installed yt-dlp version string.
     */
    suspend fun getVersion(): String? = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            YoutubeDL.getInstance().version(context)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get yt-dlp version")
            null
        }
    }

    private suspend fun ensureInitialized() {
        if (!initialized) initialize().getOrThrow()
    }
}
```

**Verify:** Write a unit test that calls `initialize()` (requires Robolectric or instrumented test since it touches native libs). In practice, verify by calling `getVersion()` in the app's `onCreate` and logging the result.

---

### Task 6.3: YtDlpManager -- Self-update WorkManager job

**File:** `data/download/src/main/kotlin/com/stash/data/download/ytdlp/YtDlpUpdateWorker.kt`

**Why:** yt-dlp extractors break when YouTube changes their site. Automatic daily updates keep the binary current. Also triggered on-demand after extractor failures.

**Code:**
```kotlin
package com.stash.data.download.ytdlp

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class YtDlpUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val ytDlpManager: YtDlpManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("Starting yt-dlp update check (attempt ${runAttemptCount + 1})")
        return ytDlpManager.updateYtDlp().fold(
            onSuccess = { version ->
                if (version != null) {
                    Timber.i("yt-dlp updated to $version")
                } else {
                    Timber.d("yt-dlp already up to date")
                }
                Result.success()
            },
            onFailure = { e ->
                Timber.e(e, "yt-dlp update failed")
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        private const val WORK_NAME = "ytdlp_update"

        /**
         * Schedule a periodic update check every 24 hours on unmetered network.
         */
        fun enqueuePeriodicUpdate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val request = PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(
                24, TimeUnit.HOURS,
                6, TimeUnit.HOURS, // flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Trigger an immediate one-shot update (e.g., after extractor failure).
         */
        fun enqueueImmediateUpdate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<YtDlpUpdateWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
```

**Wire into app startup** in `app/.../StashApplication.kt`:
```kotlin
// In onCreate(), after Hilt initialization:
lifecycleScope.launch {
    ytDlpManager.initialize()
}
YtDlpUpdateWorker.enqueuePeriodicUpdate(this)
```

**Verify:** `adb shell dumpsys jobscheduler | grep ytdlp` shows the periodic job scheduled.

---

### Task 6.4: Quality tier configuration and DataStore preferences

**File:** `data/download/src/main/kotlin/com/stash/data/download/model/QualityTier.kt`

**Why:** Defines the 4 quality tiers with their yt-dlp flags. Stored in DataStore so the user's choice persists.

**Code:**
```kotlin
package com.stash.data.download.model

/**
 * Audio quality tiers. All use Opus format.
 * Bitrates are approximate -- YouTube's max is ~160kbps Opus.
 */
enum class QualityTier(
    val displayName: String,
    val approximateBitrateKbps: Int,
    val sizeMbPerMinute: Float,
    val ytDlpFormatArg: String,
    val ytDlpAudioQuality: Int,
) {
    BEST(
        displayName = "Best",
        approximateBitrateKbps = 160,
        sizeMbPerMinute = 1.2f,
        ytDlpFormatArg = "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio",
        ytDlpAudioQuality = 0,
    ),
    HIGH(
        displayName = "High",
        approximateBitrateKbps = 128,
        sizeMbPerMinute = 0.96f,
        ytDlpFormatArg = "bestaudio",
        ytDlpAudioQuality = 3,
    ),
    NORMAL(
        displayName = "Normal",
        approximateBitrateKbps = 96,
        sizeMbPerMinute = 0.72f,
        ytDlpFormatArg = "bestaudio",
        ytDlpAudioQuality = 5,
    ),
    LOW(
        displayName = "Low",
        approximateBitrateKbps = 64,
        sizeMbPerMinute = 0.48f,
        ytDlpFormatArg = "bestaudio",
        ytDlpAudioQuality = 8,
    );
}
```

**File:** `data/download/src/main/kotlin/com/stash/data/download/prefs/DownloadPreferences.kt`

```kotlin
package com.stash.data.download.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.stash.data.download.model.QualityTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private companion object {
        val KEY_QUALITY_TIER = stringPreferencesKey("audio_quality_tier")
    }

    val qualityTier: Flow<QualityTier> = dataStore.data.map { prefs ->
        val name = prefs[KEY_QUALITY_TIER] ?: QualityTier.BEST.name
        QualityTier.valueOf(name)
    }

    suspend fun setQualityTier(tier: QualityTier) {
        dataStore.edit { prefs ->
            prefs[KEY_QUALITY_TIER] = tier.name
        }
    }
}
```

**Verify:** Unit test writes NORMAL, reads back NORMAL. Default is BEST when key absent.

---

### Task 6.5: Download state machine and domain models

**File:** `data/download/src/main/kotlin/com/stash/data/download/model/DownloadState.kt`

**Why:** Each download progresses through a well-defined state machine. This enum drives UI, retry logic, and queue management.

**Code:**
```kotlin
package com.stash.data.download.model

/**
 * State machine for a single download:
 *
 * QUEUED -> MATCHING -> DOWNLOADING -> PROCESSING -> COMPLETED
 *                  \-> UNMATCHED        \-> FAILED
 *                                        \-> FAILED
 *
 * FAILED can transition back to QUEUED on retry.
 */
enum class DownloadStatus {
    /** Waiting in queue for a semaphore permit. */
    QUEUED,
    /** Searching YouTube for matching video. */
    MATCHING,
    /** yt-dlp process actively downloading. */
    DOWNLOADING,
    /** Post-download: metadata tagging + album art embedding. */
    PROCESSING,
    /** File written to disk, metadata embedded, DB updated. */
    COMPLETED,
    /** Download or processing failed. Eligible for retry. */
    FAILED,
    /** Track matching found no acceptable result. Needs user resolution. */
    UNMATCHED,
    /** User cancelled or track removed from queue. */
    CANCELLED,
}

data class DownloadProgress(
    val trackId: Long,
    val status: DownloadStatus,
    val progressPercent: Float = 0f,
    val etaSeconds: Long = -1,
    val errorMessage: String? = null,
    val matchConfidence: Float? = null,
)

data class DownloadRequest(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val albumArtUrl: String?,
    val spotifyUri: String?,
    val youtubeId: String?,
    val trackNumber: Int?,
    val priority: DownloadPriority = DownloadPriority.NORMAL,
)

enum class DownloadPriority(val weight: Int) {
    /** Currently playing track -- download immediately. */
    URGENT(0),
    /** Track from the playlist currently being viewed. */
    HIGH(1),
    /** Background sync queue. */
    NORMAL(2),
}
```

**Verify:** Compiles. States are referenced by name in later tasks.

---

### Task 6.6: Canonical normalization utility

**File:** `core/common/src/main/kotlin/com/stash/core/common/StringNormalization.kt`

**Why:** Used for duplicate detection (before queueing, cross-source matching, fuzzy comparison). Must be deterministic and consistent across the app.

**Code:**
```kotlin
package com.stash.core.common

/**
 * Normalizes track titles and artist names to a canonical form for dedup.
 *
 * Rules:
 * 1. Lowercase
 * 2. Strip content in parentheses and brackets: "(feat. X)", "[Deluxe]", "(Remix)" etc.
 * 3. Remove "feat.", "ft.", "featuring", "with" and everything after
 * 4. Remove non-alphanumeric characters except spaces
 * 5. Collapse multiple spaces to single space
 * 6. Trim
 */
object StringNormalization {

    private val PARENTHETICAL = Regex("""[\(\[][^)\]]*[\)\]]""")
    private val FEATURING = Regex("""\s*(feat\.?|ft\.?|featuring|with)\s+.*""", RegexOption.IGNORE_CASE)
    private val NON_ALPHANUM = Regex("""[^a-z0-9 ]""")
    private val MULTI_SPACE = Regex("""\s{2,}""")

    fun canonicalize(input: String): String {
        return input
            .lowercase()
            .replace(PARENTHETICAL, "")
            .replace(FEATURING, "")
            .replace(NON_ALPHANUM, "")
            .replace(MULTI_SPACE, " ")
            .trim()
    }

    /**
     * Sort multi-artist strings alphabetically after splitting on common delimiters.
     * "Drake, The Weeknd" -> "drake theweeknd" (after full canonicalization)
     */
    fun canonicalizeArtist(input: String): String {
        return input
            .split(Regex("""[,;&]|feat\.?|ft\.?|featuring|and\b""", RegexOption.IGNORE_CASE))
            .map { canonicalize(it) }
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString(" ")
    }

    /**
     * Generate a filesystem-safe slug from a string.
     * "The Dark Side of the Moon" -> "the-dark-side-of-the-moon"
     */
    fun slugify(input: String): String {
        return input
            .lowercase()
            .replace(Regex("""[^a-z0-9\s-]"""), "")
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("""-{2,}"""), "-")
            .trim('-')
            .take(80) // filesystem length safety
    }
}
```

**Verify:** Unit tests:
- `canonicalize("Hello (feat. Drake) [Remix]")` -> `"hello"`
- `canonicalizeArtist("The Weeknd, Drake")` -> `"drake theweeknd"`
- `slugify("The Dark Side of the Moon")` -> `"the-dark-side-of-the-moon"`

---

### Task 6.7: Jaro-Winkler string similarity

**File:** `core/common/src/main/kotlin/com/stash/core/common/JaroWinkler.kt`

**Why:** Used by the track matching scoring algorithm. ~60 lines, no library needed.

**Code:**
```kotlin
package com.stash.core.common

/**
 * Jaro-Winkler similarity between two strings. Returns value in [0.0, 1.0].
 * 1.0 = identical, 0.0 = completely dissimilar.
 */
object JaroWinkler {

    private const val WINKLER_PREFIX_WEIGHT = 0.1
    private const val MAX_PREFIX_LENGTH = 4

    fun similarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val jaro = jaroSimilarity(s1, s2)

        // Winkler modification: boost for common prefix
        val prefixLength = s1.zip(s2)
            .takeWhile { (a, b) -> a == b }
            .count()
            .coerceAtMost(MAX_PREFIX_LENGTH)

        return jaro + prefixLength * WINKLER_PREFIX_WEIGHT * (1.0 - jaro)
    }

    private fun jaroSimilarity(s1: String, s2: String): Double {
        val maxDist = (maxOf(s1.length, s2.length) / 2) - 1
        if (maxDist < 0) return 0.0

        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)
        var matches = 0
        var transpositions = 0

        for (i in s1.indices) {
            val start = maxOf(0, i - maxDist)
            val end = minOf(s2.length - 1, i + maxDist)
            for (j in start..end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val jaro = (
            matches.toDouble() / s1.length +
            matches.toDouble() / s2.length +
            (matches - transpositions / 2.0) / matches
        ) / 3.0

        return jaro
    }
}
```

**Verify:** Unit tests:
- `similarity("martha", "marhta")` ~= 0.961
- `similarity("", "test")` == 0.0
- `similarity("identical", "identical")` == 1.0

---

### Task 6.8: yt-dlp JSON response DTOs

**File:** `data/download/src/main/kotlin/com/stash/data/download/ytdlp/YtDlpModels.kt`

**Why:** yt-dlp's `--dump-json` output is a large JSON object. We parse only the fields we need with `ignoreUnknownKeys = true` for resilience against format changes.

**Code:**
```kotlin
package com.stash.data.download.ytdlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of yt-dlp --dump-json output fields relevant to track matching.
 * ignoreUnknownKeys = true MUST be set on the Json instance.
 */
@Serializable
data class YtDlpVideoInfo(
    val id: String,
    val title: String = "",
    val uploader: String? = null,
    @SerialName("uploader_id") val uploaderId: String? = null,
    val channel: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    val duration: Double? = null, // seconds (can be fractional)
    @SerialName("view_count") val viewCount: Long? = null,
    @SerialName("like_count") val likeCount: Long? = null,
    val description: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val thumbnail: String? = null,
    @SerialName("artist") val artist: String? = null, // sometimes present for music
    @SerialName("track") val track: String? = null,   // sometimes present for music
    @SerialName("album") val album: String? = null,
)

/**
 * Wrapper for ytsearch5 results -- yt-dlp outputs one JSON object per line.
 */
@Serializable
data class YtDlpSearchResult(
    val entries: List<YtDlpVideoInfo>? = null,
    // Single results come as flat objects, not wrapped in entries
)
```

**Verify:** Parse a sample yt-dlp JSON output from a real `ytsearch1` call. Ensure unknown fields are silently ignored.

---

### Task 6.9: TrackMatcher -- YouTube search execution

**File:** `data/download/src/main/kotlin/com/stash/data/download/matcher/TrackMatcher.kt`

**Why:** For Spotify tracks, we must find the correct YouTube video. This class executes yt-dlp search queries and delegates scoring to `MatchScorer`.

**Code:**
```kotlin
package com.stash.data.download.matcher

import com.stash.data.download.model.DownloadRequest
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.download.ytdlp.YtDlpVideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class MatchResult(
    val videoInfo: YtDlpVideoInfo,
    val confidence: Float,
    val youtubeUrl: String,
)

sealed class MatchOutcome {
    data class Matched(val result: MatchResult) : MatchOutcome()
    data class LowConfidence(val result: MatchResult) : MatchOutcome()
    data object Unmatched : MatchOutcome()
}

@Singleton
class TrackMatcher @Inject constructor(
    private val ytDlpManager: YtDlpManager,
    private val matchScorer: MatchScorer,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val CONFIDENCE_AUTO_ACCEPT = 0.75f
        private const val CONFIDENCE_LOW_ACCEPT = 0.50f
    }

    /**
     * Find the best YouTube match for a Spotify track.
     * If the request already has a youtubeId, skip matching entirely.
     */
    suspend fun findMatch(request: DownloadRequest): MatchOutcome =
        withContext(Dispatchers.IO) {
            // If we already have a YouTube ID (e.g., from YT Music source), use it directly
            if (request.youtubeId != null) {
                return@withContext resolveDirectId(request)
            }

            // Tier 1: Quick search with single result
            val tier1Result = searchYouTube(
                query = "${request.artist} - ${request.title}",
                maxResults = 1,
            )
            if (tier1Result.isNotEmpty()) {
                val scored = matchScorer.score(request, tier1Result.first())
                if (scored >= CONFIDENCE_AUTO_ACCEPT) {
                    return@withContext MatchOutcome.Matched(
                        MatchResult(
                            videoInfo = tier1Result.first(),
                            confidence = scored,
                            youtubeUrl = "https://www.youtube.com/watch?v=${tier1Result.first().id}",
                        ),
                    )
                }
            }

            // Tier 2: Broader search with scoring
            val tier2Result = searchYouTube(
                query = "${request.artist} - ${request.title} official audio",
                maxResults = 5,
            )
            if (tier2Result.isEmpty()) {
                return@withContext MatchOutcome.Unmatched
            }

            val scoredResults = tier2Result
                .map { info -> info to matchScorer.score(request, info) }
                .sortedByDescending { it.second }

            val (bestInfo, bestScore) = scoredResults.first()

            when {
                bestScore >= CONFIDENCE_AUTO_ACCEPT -> MatchOutcome.Matched(
                    MatchResult(bestInfo, bestScore, "https://www.youtube.com/watch?v=${bestInfo.id}"),
                )
                bestScore >= CONFIDENCE_LOW_ACCEPT -> MatchOutcome.LowConfidence(
                    MatchResult(bestInfo, bestScore, "https://www.youtube.com/watch?v=${bestInfo.id}"),
                )
                else -> MatchOutcome.Unmatched
            }
        }

    private suspend fun resolveDirectId(request: DownloadRequest): MatchOutcome {
        return try {
            val url = "https://www.youtube.com/watch?v=${request.youtubeId}"
            val ytRequest = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--no-download")
            }
            val response = YoutubeDL.getInstance().execute(ytRequest, null)
            val info = json.decodeFromString<YtDlpVideoInfo>(response.out)
            MatchOutcome.Matched(
                MatchResult(info, 1.0f, url),
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve YouTube ID: ${request.youtubeId}")
            MatchOutcome.Unmatched
        }
    }

    private suspend fun searchYouTube(query: String, maxResults: Int): List<YtDlpVideoInfo> {
        return try {
            val searchPrefix = if (maxResults == 1) "ytsearch1" else "ytsearch$maxResults"
            val ytRequest = YoutubeDLRequest("$searchPrefix:$query").apply {
                addOption("--dump-json")
                addOption("--no-download")
                addOption("--flat-playlist") // don't resolve each video fully during search
            }
            val response = YoutubeDL.getInstance().execute(ytRequest, null)

            // yt-dlp outputs one JSON object per line for search results
            response.out
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        json.decodeFromString<YtDlpVideoInfo>(line)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to parse yt-dlp search result line")
                        null
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "YouTube search failed for query: $query")
            emptyList()
        }
    }
}
```

**Verify:** Instrumented test: search for "Radiohead - Creep", verify at least one result returned with a non-empty `id` field.

---

### Task 6.10: MatchScorer -- Weighted scoring algorithm

**File:** `data/download/src/main/kotlin/com/stash/data/download/matcher/MatchScorer.kt`

**Why:** Implements the four-factor scoring: title similarity (0.35), artist match (0.25), duration match (0.25), popularity (0.15). Includes penalty for "remix", "cover", "live", "karaoke" mismatches.

**Code:**
```kotlin
package com.stash.data.download.matcher

import com.stash.core.common.JaroWinkler
import com.stash.core.common.StringNormalization
import com.stash.data.download.model.DownloadRequest
import com.stash.data.download.ytdlp.YtDlpVideoInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10

@Singleton
class MatchScorer @Inject constructor() {

    companion object {
        private const val WEIGHT_TITLE = 0.35f
        private const val WEIGHT_ARTIST = 0.25f
        private const val WEIGHT_DURATION = 0.25f
        private const val WEIGHT_POPULARITY = 0.15f

        // Words that indicate a variant -- penalize if query doesn't contain them
        private val VARIANT_KEYWORDS = setOf("remix", "cover", "live", "karaoke", "acoustic", "instrumental")

        // Log10 of a reasonable max view count (10 billion = 10.0)
        private const val MAX_LOG_VIEWS = 10.0
    }

    /**
     * Score a candidate YouTube video against the source track metadata.
     * Returns a value in [0.0, 1.0].
     */
    fun score(request: DownloadRequest, candidate: YtDlpVideoInfo): Float {
        val titleScore = scoreTitleSimilarity(request, candidate)
        val artistScore = scoreArtistMatch(request, candidate)
        val durationScore = scoreDurationMatch(request, candidate)
        val popularityScore = scorePopularity(candidate)

        return (titleScore * WEIGHT_TITLE) +
            (artistScore * WEIGHT_ARTIST) +
            (durationScore * WEIGHT_DURATION) +
            (popularityScore * WEIGHT_POPULARITY)
    }

    private fun scoreTitleSimilarity(request: DownloadRequest, candidate: YtDlpVideoInfo): Float {
        val queryTitle = StringNormalization.canonicalize(request.title)
        // Prefer yt-dlp's extracted 'track' field if available, else video title
        val candidateTitle = StringNormalization.canonicalize(
            candidate.track ?: candidate.title,
        )

        var similarity = JaroWinkler.similarity(queryTitle, candidateTitle).toFloat()

        // Penalize variant mismatches
        val queryLower = request.title.lowercase()
        val candidateLower = (candidate.track ?: candidate.title).lowercase()
        for (keyword in VARIANT_KEYWORDS) {
            val inQuery = keyword in queryLower
            val inCandidate = keyword in candidateLower
            if (!inQuery && inCandidate) {
                similarity *= 0.3f // Heavy penalty for unwanted variants
            }
        }

        return similarity.coerceIn(0f, 1f)
    }

    private fun scoreArtistMatch(request: DownloadRequest, candidate: YtDlpVideoInfo): Float {
        val queryArtist = StringNormalization.canonicalize(request.artist)

        // Check multiple fields: artist metadata, uploader, channel
        val candidateFields = listOfNotNull(
            candidate.artist,
            candidate.uploader,
            candidate.channel,
        ).map { StringNormalization.canonicalize(it) }

        if (candidateFields.isEmpty()) return 0f

        var bestScore = candidateFields.maxOf { field ->
            JaroWinkler.similarity(queryArtist, field).toFloat()
        }

        // Bonus for "- Topic" channels (YouTube's official auto-generated music channels)
        val channelName = candidate.channel ?: candidate.uploader ?: ""
        if (channelName.endsWith("- Topic", ignoreCase = true)) {
            bestScore = (bestScore + 0.15f).coerceAtMost(1f)
        }

        return bestScore.coerceIn(0f, 1f)
    }

    private fun scoreDurationMatch(request: DownloadRequest, candidate: YtDlpVideoInfo): Float {
        val candidateDurationMs = ((candidate.duration ?: return 0.5f) * 1000).toLong()
        val diffMs = abs(request.durationMs - candidateDurationMs)
        val diffSec = diffMs / 1000.0

        return when {
            diffSec <= 3.0 -> 1.0f
            diffSec <= 10.0 -> 0.8f
            diffSec <= 30.0 -> 0.4f
            else -> 0.0f
        }
    }

    private fun scorePopularity(candidate: YtDlpVideoInfo): Float {
        val views = candidate.viewCount ?: return 0.3f // neutral default
        if (views <= 0) return 0f
        return (log10(views.toDouble()) / MAX_LOG_VIEWS).toFloat().coerceIn(0f, 1f)
    }
}
```

**Verify:** Unit test with mocked `YtDlpVideoInfo`:
- Exact title/artist match, 2s duration diff, 100M views -> score > 0.85
- Title contains "remix" when query doesn't -> score penalized below 0.6
- "- Topic" channel gets artist bonus

---

### Task 6.11: Duplicate detection service

**File:** `data/download/src/main/kotlin/com/stash/data/download/dedup/DuplicateDetector.kt`

**Why:** Prevents downloading the same track twice, both within a source and across Spotify/YouTube Music. Called before enqueuing a download and after download for file-hash verification.

**Code:**
```kotlin
package com.stash.data.download.dedup

import com.stash.core.common.JaroWinkler
import com.stash.core.common.StringNormalization
import com.stash.core.data.dao.TrackDao
import com.stash.data.download.model.DownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed class DuplicateCheckResult {
    /** No duplicate found. Safe to download. */
    data object Unique : DuplicateCheckResult()
    /** Exact canonical match in DB. Returns existing track ID. */
    data class ExactDuplicate(val existingTrackId: Long) : DuplicateCheckResult()
    /** Fuzzy match above threshold. Returns existing track ID and similarity. */
    data class FuzzyDuplicate(
        val existingTrackId: Long,
        val titleSimilarity: Double,
        val artistSimilarity: Double,
    ) : DuplicateCheckResult()
}

@Singleton
class DuplicateDetector @Inject constructor(
    private val trackDao: TrackDao,
) {
    companion object {
        private const val FUZZY_TITLE_THRESHOLD = 0.92
        private const val FUZZY_ARTIST_THRESHOLD = 0.90
        private const val DURATION_TOLERANCE_MS = 5000L
    }

    /**
     * Check whether a track already exists in the local library.
     * Checks in order: exact canonical match, cross-source ID match, fuzzy match.
     */
    suspend fun check(request: DownloadRequest): DuplicateCheckResult =
        withContext(Dispatchers.Default) {
            // 1. Exact canonical match
            val canonTitle = StringNormalization.canonicalize(request.title)
            val canonArtist = StringNormalization.canonicalizeArtist(request.artist)
            val exactMatch = trackDao.findByCanonical(canonTitle, canonArtist)
            if (exactMatch != null) {
                return@withContext DuplicateCheckResult.ExactDuplicate(exactMatch.id)
            }

            // 2. Cross-source: Spotify track already downloaded via YouTube or vice versa
            if (request.spotifyUri != null) {
                val bySpotify = trackDao.findBySpotifyUri(request.spotifyUri)
                if (bySpotify != null) {
                    return@withContext DuplicateCheckResult.ExactDuplicate(bySpotify.id)
                }
            }
            if (request.youtubeId != null) {
                val byYoutube = trackDao.findByYoutubeId(request.youtubeId)
                if (byYoutube != null) {
                    return@withContext DuplicateCheckResult.ExactDuplicate(byYoutube.id)
                }
            }

            // 3. Fuzzy fallback with duration confirmation
            val candidates = trackDao.findByArtistPrefix(canonArtist.take(5))
            for (candidate in candidates) {
                val titleSim = JaroWinkler.similarity(canonTitle, candidate.canonicalTitle)
                val artistSim = JaroWinkler.similarity(canonArtist, candidate.canonicalArtist)
                val durationClose = kotlin.math.abs(
                    request.durationMs - candidate.durationMs,
                ) <= DURATION_TOLERANCE_MS

                if (titleSim > FUZZY_TITLE_THRESHOLD &&
                    artistSim > FUZZY_ARTIST_THRESHOLD &&
                    durationClose
                ) {
                    return@withContext DuplicateCheckResult.FuzzyDuplicate(
                        existingTrackId = candidate.id,
                        titleSimilarity = titleSim,
                        artistSimilarity = artistSim,
                    )
                }
            }

            DuplicateCheckResult.Unique
        }

    /**
     * Post-download SHA-256 file hash check (belt-and-suspenders).
     * Returns the ID of an existing track with the same hash, or null.
     */
    suspend fun checkFileHash(file: File): Long? = withContext(Dispatchers.IO) {
        val hash = sha256(file)
        trackDao.findByFileHash(hash)?.id
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
```

**DAO methods required** (add to `TrackDao` if not present from Phase 4):
```kotlin
@Query("SELECT * FROM tracks WHERE canonical_title = :title AND canonical_artist = :artist LIMIT 1")
suspend fun findByCanonical(title: String, artist: String): TrackEntity?

@Query("SELECT * FROM tracks WHERE canonical_artist LIKE :prefix || '%'")
suspend fun findByArtistPrefix(prefix: String): List<TrackEntity>

@Query("SELECT * FROM tracks WHERE file_hash = :hash LIMIT 1")
suspend fun findByFileHash(hash: String): TrackEntity?
```

**DB migration:** Add `file_hash TEXT` column to `tracks` table.

**Verify:** Unit test: insert a track with canonical "creep" / "radiohead", then check for "Creep (Acoustic)" by "Radiohead" -- should return FuzzyDuplicate.

---

### Task 6.12: File organization utility

**File:** `data/download/src/main/kotlin/com/stash/data/download/file/FileOrganizer.kt`

**Why:** Downloads land in a temp directory. This utility moves them to the final `artist/album/track.opus` path inside app-internal storage.

**Code:**
```kotlin
package com.stash.data.download.file

import android.content.Context
import com.stash.core.common.StringNormalization
import com.stash.data.download.model.DownloadRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileOrganizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val musicRoot: File
        get() = File(context.filesDir, "music").also { it.mkdirs() }

    private val tempDir: File
        get() = File(context.cacheDir, "download_temp").also { it.mkdirs() }

    /**
     * Returns the temp directory for yt-dlp to write into.
     */
    fun getTempDir(): File = tempDir

    /**
     * Compute the final destination path for a track.
     * Pattern: music/{artistSlug}/{albumSlug}/{trackNumber}-{titleSlug}.opus
     */
    fun getDestinationPath(request: DownloadRequest): File {
        val artistSlug = StringNormalization.slugify(request.artist).ifBlank { "unknown-artist" }
        val albumSlug = StringNormalization.slugify(request.album ?: "singles").ifBlank { "singles" }
        val titleSlug = StringNormalization.slugify(request.title).ifBlank { "untitled" }
        val trackPrefix = request.trackNumber?.let { "%02d-".format(it) } ?: ""

        val dir = File(musicRoot, "$artistSlug/$albumSlug")
        return File(dir, "$trackPrefix$titleSlug.opus")
    }

    /**
     * Move a downloaded file from temp to its final organized location.
     * Creates parent directories as needed. Returns the final path.
     */
    suspend fun moveToFinalLocation(tempFile: File, request: DownloadRequest): File =
        withContext(Dispatchers.IO) {
            val dest = getDestinationPath(request)
            dest.parentFile?.mkdirs()

            if (dest.exists()) {
                Timber.w("Destination already exists, overwriting: ${dest.absolutePath}")
                dest.delete()
            }

            val moved = tempFile.renameTo(dest)
            if (!moved) {
                // renameTo fails across filesystems; fall back to copy+delete
                tempFile.copyTo(dest, overwrite = true)
                tempFile.delete()
            }

            Timber.d("File organized: ${dest.absolutePath}")
            dest
        }

    /**
     * Save album art alongside the track files.
     * Pattern: music/{artistSlug}/{albumSlug}/cover.jpg
     */
    fun getAlbumArtPath(request: DownloadRequest): File {
        val artistSlug = StringNormalization.slugify(request.artist).ifBlank { "unknown-artist" }
        val albumSlug = StringNormalization.slugify(request.album ?: "singles").ifBlank { "singles" }
        val dir = File(musicRoot, "$artistSlug/$albumSlug")
        dir.mkdirs()
        return File(dir, "cover.jpg")
    }

    /**
     * Clean up temp files older than 1 hour.
     */
    suspend fun cleanTempDir() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 3_600_000
        tempDir.listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}
```

**Verify:** Unit test: `getDestinationPath` for artist="The Weeknd", album="After Hours", title="Blinding Lights", trackNumber=1 -> path ends with `the-weeknd/after-hours/01-blinding-lights.opus`.

---

### Task 6.13: MetadataEmbedder -- Vorbis comment tags via ffmpeg

**File:** `data/download/src/main/kotlin/com/stash/data/download/metadata/MetadataEmbedder.kt`

**Why:** Embeds Spotify/YT Music metadata (title, artist, album, track number) and album art into Opus files. Uses ffmpeg from youtubedl-android. Album art requires constructing a FLAC METADATA_BLOCK_PICTURE binary blob because ffmpeg does not natively support writing cover art to OGG/Opus.

**Code:**
```kotlin
package com.stash.data.download.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.stash.data.download.model.DownloadRequest
import com.yausername.ffmpeg.FFmpeg
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
) {
    /**
     * Embed metadata tags and album art into an Opus file.
     *
     * Strategy:
     * 1. Text tags: ffmpeg -i input.opus -metadata TITLE=x -metadata ARTIST=y ... -c copy output.opus
     * 2. Album art: Construct METADATA_BLOCK_PICTURE binary -> base64 -> ffmpeg -metadata
     *
     * We write to a temp file then replace the original to avoid corruption.
     */
    suspend fun embed(
        opusFile: File,
        request: DownloadRequest,
        albumArtFile: File?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tempOutput = File(opusFile.parent, "${opusFile.nameWithoutExtension}_tagged.opus")

            val command = buildList {
                add("-i")
                add(opusFile.absolutePath)

                // Vorbis comment tags
                add("-metadata")
                add("TITLE=${request.title}")
                add("-metadata")
                add("ARTIST=${request.artist}")
                if (request.album != null) {
                    add("-metadata")
                    add("ALBUM=${request.album}")
                }
                if (request.trackNumber != null) {
                    add("-metadata")
                    add("TRACKNUMBER=${request.trackNumber}")
                }
                // Source tracking
                add("-metadata")
                add("COMMENT=Downloaded by Stash")

                // Album art as METADATA_BLOCK_PICTURE
                if (albumArtFile != null && albumArtFile.exists()) {
                    val pictureBlock = buildMetadataBlockPicture(albumArtFile)
                    if (pictureBlock != null) {
                        add("-metadata")
                        add("METADATA_BLOCK_PICTURE=$pictureBlock")
                    }
                }

                // Copy audio stream (no re-encoding)
                add("-c")
                add("copy")

                // Overwrite output if exists
                add("-y")

                add(tempOutput.absolutePath)
            }

            // Execute ffmpeg via youtubedl-android's FFmpeg module
            val commandStr = command.joinToString(" ")
            Timber.d("FFmpeg command: ffmpeg $commandStr")

            // FFmpeg.getInstance().execute() takes a command string
            // We need to use the raw process execution
            val process = Runtime.getRuntime().exec(
                arrayOf(getFFmpegBinaryPath()) + command.toTypedArray(),
            )
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val stderr = process.errorStream.bufferedReader().readText()
                Timber.e("FFmpeg failed (exit $exitCode): $stderr")

                // Fallback: try without album art if that was the issue
                if (albumArtFile != null) {
                    Timber.w("Retrying metadata embed without album art")
                    return@withContext embedTextTagsOnly(opusFile, request)
                }
                return@withContext Result.failure(Exception("FFmpeg exit code: $exitCode"))
            }

            // Replace original with tagged version
            opusFile.delete()
            tempOutput.renameTo(opusFile)

            Timber.d("Metadata embedded successfully: ${opusFile.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to embed metadata")
            Result.failure(e)
        }
    }

    /**
     * Download album art from URL, resize to 500x500, save as JPEG.
     */
    suspend fun downloadAlbumArt(url: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(500, 500)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap()
                    destFile.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to download album art from: $url")
                false
            }
        }

    /**
     * Build a FLAC METADATA_BLOCK_PICTURE binary structure, then Base64-encode it.
     *
     * Format (big-endian):
     *   4 bytes: picture type (3 = front cover)
     *   4 bytes: MIME string length
     *   N bytes: MIME string ("image/jpeg")
     *   4 bytes: description string length
     *   N bytes: description string (empty)
     *   4 bytes: width
     *   4 bytes: height
     *   4 bytes: color depth (24 for JPEG)
     *   4 bytes: number of indexed colors (0)
     *   4 bytes: picture data length
     *   N bytes: picture data
     */
    private fun buildMetadataBlockPicture(imageFile: File): String? {
        return try {
            val imageBytes = imageFile.readBytes()

            // Decode dimensions without loading full bitmap
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, options)
            val width = options.outWidth
            val height = options.outHeight

            val mime = "image/jpeg"
            val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
            val descBytes = ByteArray(0) // empty description

            val totalSize = 4 + 4 + mimeBytes.size + 4 + descBytes.size +
                4 + 4 + 4 + 4 + 4 + imageBytes.size

            val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(3) // picture type: front cover
            buffer.putInt(mimeBytes.size)
            buffer.put(mimeBytes)
            buffer.putInt(descBytes.size)
            buffer.put(descBytes)
            buffer.putInt(width)
            buffer.putInt(height)
            buffer.putInt(24) // color depth
            buffer.putInt(0)  // indexed colors
            buffer.putInt(imageBytes.size)
            buffer.put(imageBytes)

            Base64.getEncoder().encodeToString(buffer.array())
        } catch (e: Exception) {
            Timber.e(e, "Failed to build METADATA_BLOCK_PICTURE")
            null
        }
    }

    /**
     * Fallback: embed only text tags (no album art) using yt-dlp's ffmpeg.
     */
    private suspend fun embedTextTagsOnly(
        opusFile: File,
        request: DownloadRequest,
    ): Result<Unit> {
        val tempOutput = File(opusFile.parent, "${opusFile.nameWithoutExtension}_tagged.opus")
        val command = buildList {
            add("-i")
            add(opusFile.absolutePath)
            add("-metadata"); add("TITLE=${request.title}")
            add("-metadata"); add("ARTIST=${request.artist}")
            if (request.album != null) { add("-metadata"); add("ALBUM=${request.album}") }
            if (request.trackNumber != null) { add("-metadata"); add("TRACKNUMBER=${request.trackNumber}") }
            add("-c"); add("copy"); add("-y")
            add(tempOutput.absolutePath)
        }

        val process = Runtime.getRuntime().exec(
            arrayOf(getFFmpegBinaryPath()) + command.toTypedArray(),
        )
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return Result.failure(Exception("FFmpeg text-only tagging failed"))
        }
        opusFile.delete()
        tempOutput.renameTo(opusFile)
        return Result.success(Unit)
    }

    /**
     * Get the path to the ffmpeg binary bundled by youtubedl-android.
     * The library extracts it to the app's native lib directory.
     */
    private fun getFFmpegBinaryPath(): String {
        // youtubedl-android extracts ffmpeg to the native libs directory
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return "$nativeLibDir/libffmpeg.bin.so"
    }
}
```

**IMPORTANT NOTE:** The `Runtime.getRuntime().exec()` approach above is the fallback. The preferred approach is to use `YoutubeDLRequest` with ffmpeg post-processors, but that does not give us fine-grained control over the METADATA_BLOCK_PICTURE field. If the youtubedl-android library exposes `FFmpeg.getInstance().execute(commandString)`, use that instead of raw `Runtime.exec`. The binary path `libffmpeg.bin.so` is correct per the youtubedl-android library's extraction convention.

**Verify:** Instrumented test: create a dummy Opus file, embed title+artist metadata, then read back with ffprobe to confirm Vorbis comments are present.

---

### Task 6.14: DownloadExecutor -- yt-dlp command construction and execution

**File:** `data/download/src/main/kotlin/com/stash/data/download/engine/DownloadExecutor.kt`

**Why:** Constructs and runs the yt-dlp download command for a specific YouTube URL at the configured quality tier. Reports progress via callback.

**Code:**
```kotlin
package com.stash.data.download.engine

import com.stash.data.download.file.FileOrganizer
import com.stash.data.download.model.QualityTier
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadResult(
    val outputFile: File,
    val fileSizeBytes: Long,
)

@Singleton
class DownloadExecutor @Inject constructor(
    private val fileOrganizer: FileOrganizer,
) {
    /**
     * Download audio from a YouTube URL using yt-dlp.
     *
     * @param youtubeUrl Full YouTube video URL
     * @param qualityTier Audio quality configuration
     * @param onProgress Callback with (progressPercent: Float, etaSeconds: Long)
     * @return The downloaded Opus file in the temp directory
     */
    suspend fun download(
        youtubeUrl: String,
        qualityTier: QualityTier,
        onProgress: (Float, Long) -> Unit,
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        try {
            val tempDir = fileOrganizer.getTempDir()
            // Use a unique filename to avoid collisions with concurrent downloads
            val outputTemplate = "${tempDir.absolutePath}/%(id)s.%(ext)s"

            val request = YoutubeDLRequest(youtubeUrl).apply {
                // Format selection
                addOption("-f", qualityTier.ytDlpFormatArg)

                // Extract audio and convert to Opus
                addOption("-x")
                addOption("--audio-format", "opus")
                addOption("--audio-quality", qualityTier.ytDlpAudioQuality.toString())

                // Output path
                addOption("-o", outputTemplate)

                // Do NOT embed metadata or thumbnail -- we do this ourselves
                // with accurate Spotify/YT Music metadata, not YouTube metadata
                addOption("--no-embed-metadata")

                // No playlist handling
                addOption("--no-playlist")

                // Limit retries within yt-dlp itself
                addOption("--retries", "3")
                addOption("--fragment-retries", "3")
            }

            Timber.d("Starting download: $youtubeUrl at ${qualityTier.displayName} quality")

            val response = YoutubeDL.getInstance().execute(
                request,
            ) { progress, etaInSeconds ->
                onProgress(progress, etaInSeconds)
            }

            // Find the output file (yt-dlp may have named it differently)
            val outputFile = tempDir.listFiles()
                ?.filter { it.extension == "opus" }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("Download completed but no .opus file found in temp dir")

            Timber.d("Download complete: ${outputFile.name} (${outputFile.length()} bytes)")

            Result.success(
                DownloadResult(
                    outputFile = outputFile,
                    fileSizeBytes = outputFile.length(),
                ),
            )
        } catch (e: Exception) {
            Timber.e(e, "Download failed for: $youtubeUrl")
            Result.failure(e)
        }
    }
}
```

**Verify:** Instrumented test: download a known short YouTube video at LOW quality, verify an `.opus` file appears in temp dir with size > 0.

---

### Task 6.15: DownloadManager -- Channel-based queue with concurrency control

**File:** `data/download/src/main/kotlin/com/stash/data/download/engine/DownloadManager.kt`

**Why:** The core orchestrator. Maintains a Channel-based queue, gates concurrency with Semaphore(3), drives each download through the full state machine (QUEUED -> MATCHING -> DOWNLOADING -> PROCESSING -> COMPLETED/FAILED/UNMATCHED).

**Code:**
```kotlin
package com.stash.data.download.engine

import com.stash.core.data.dao.DownloadQueueDao
import com.stash.core.data.dao.TrackDao
import com.stash.core.data.entity.DownloadQueueEntity
import com.stash.data.download.dedup.DuplicateCheckResult
import com.stash.data.download.dedup.DuplicateDetector
import com.stash.data.download.file.FileOrganizer
import com.stash.data.download.matcher.MatchOutcome
import com.stash.data.download.matcher.TrackMatcher
import com.stash.data.download.metadata.MetadataEmbedder
import com.stash.data.download.model.*
import com.stash.data.download.prefs.DownloadPreferences
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.download.ytdlp.YtDlpUpdateWorker
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val trackMatcher: TrackMatcher,
    private val downloadExecutor: DownloadExecutor,
    private val metadataEmbedder: MetadataEmbedder,
    private val fileOrganizer: FileOrganizer,
    private val duplicateDetector: DuplicateDetector,
    private val downloadPreferences: DownloadPreferences,
    private val trackDao: TrackDao,
    private val downloadQueueDao: DownloadQueueDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(3)
    private val downloadChannel = Channel<DownloadRequest>(Channel.UNLIMITED)

    // Observable state for UI
    private val _activeDownloads = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<Long, DownloadProgress>> = _activeDownloads.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private var processingJob: Job? = null

    /**
     * Start processing the download queue. Idempotent -- safe to call multiple times.
     */
    fun startProcessing() {
        if (processingJob?.isActive == true) return
        processingJob = scope.launch {
            for (request in downloadChannel) {
                // Launch each download in its own coroutine, gated by semaphore
                launch {
                    semaphore.withPermit {
                        processDownload(request)
                    }
                }
            }
        }
    }

    /**
     * Enqueue a track for download. Performs duplicate check first.
     * Returns false if the track is a duplicate.
     */
    suspend fun enqueue(request: DownloadRequest): Boolean {
        // Duplicate check
        when (val check = duplicateDetector.check(request)) {
            is DuplicateCheckResult.Unique -> { /* proceed */ }
            is DuplicateCheckResult.ExactDuplicate -> {
                Timber.d("Skipping duplicate: ${request.title} (existing ID: ${check.existingTrackId})")
                // If cross-source, update the existing track to source=BOTH
                updateSourceToBoth(check.existingTrackId, request)
                return false
            }
            is DuplicateCheckResult.FuzzyDuplicate -> {
                Timber.d(
                    "Skipping fuzzy duplicate: ${request.title} " +
                        "(title sim: ${check.titleSimilarity}, artist sim: ${check.artistSimilarity})",
                )
                updateSourceToBoth(check.existingTrackId, request)
                return false
            }
        }

        // Persist to DB queue
        downloadQueueDao.insert(
            DownloadQueueEntity(
                trackId = request.trackId,
                status = DownloadStatus.QUEUED.name,
                searchQuery = "${request.artist} - ${request.title}",
                createdAt = Instant.now().toEpochMilli(),
            ),
        )

        updateProgress(request.trackId, DownloadProgress(request.trackId, DownloadStatus.QUEUED))
        _queueSize.update { it + 1 }

        downloadChannel.send(request)
        return true
    }

    /**
     * Retry a failed download.
     */
    suspend fun retry(trackId: Long) {
        val queueEntry = downloadQueueDao.findByTrackId(trackId) ?: return
        if (queueEntry.retryCount >= 3) {
            Timber.w("Max retries exceeded for track $trackId")
            return
        }
        downloadQueueDao.incrementRetry(trackId)
        // Re-derive the DownloadRequest from the track entity
        val track = trackDao.getById(trackId) ?: return
        val request = DownloadRequest(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            albumArtUrl = track.albumArtUrl,
            spotifyUri = track.spotifyUri,
            youtubeId = track.youtubeId,
            trackNumber = null,
        )
        downloadChannel.send(request)
    }

    /**
     * Cancel a queued or active download.
     */
    fun cancel(trackId: Long) {
        updateProgress(trackId, DownloadProgress(trackId, DownloadStatus.CANCELLED))
        _queueSize.update { (it - 1).coerceAtLeast(0) }
    }

    private suspend fun processDownload(request: DownloadRequest) {
        val qualityTier = downloadPreferences.qualityTier.first()

        try {
            // Phase 1: Match
            updateProgress(request.trackId, DownloadProgress(request.trackId, DownloadStatus.MATCHING))

            val matchOutcome = trackMatcher.findMatch(request)
            val matchResult = when (matchOutcome) {
                is MatchOutcome.Matched -> matchOutcome.result
                is MatchOutcome.LowConfidence -> {
                    // Accept but flag
                    Timber.w("Low confidence match for ${request.title}: ${matchOutcome.result.confidence}")
                    matchOutcome.result
                }
                is MatchOutcome.Unmatched -> {
                    updateProgress(
                        request.trackId,
                        DownloadProgress(request.trackId, DownloadStatus.UNMATCHED),
                    )
                    downloadQueueDao.updateStatus(request.trackId, DownloadStatus.UNMATCHED.name)
                    _queueSize.update { (it - 1).coerceAtLeast(0) }
                    return
                }
            }

            // Update DB with YouTube URL
            downloadQueueDao.updateYoutubeUrl(request.trackId, matchResult.youtubeUrl)

            // Phase 2: Download
            updateProgress(request.trackId, DownloadProgress(request.trackId, DownloadStatus.DOWNLOADING))

            val downloadResult = downloadExecutor.download(
                youtubeUrl = matchResult.youtubeUrl,
                qualityTier = qualityTier,
                onProgress = { percent, eta ->
                    updateProgress(
                        request.trackId,
                        DownloadProgress(
                            request.trackId,
                            DownloadStatus.DOWNLOADING,
                            progressPercent = percent,
                            etaSeconds = eta,
                        ),
                    )
                },
            ).getOrElse { e ->
                handleDownloadFailure(request, e)
                return
            }

            // Phase 3: Post-process (metadata + file organization)
            updateProgress(request.trackId, DownloadProgress(request.trackId, DownloadStatus.PROCESSING))

            // Download album art
            var albumArtFile: java.io.File? = null
            val artUrl = request.albumArtUrl
            if (artUrl != null) {
                val artDest = fileOrganizer.getAlbumArtPath(request)
                if (!artDest.exists()) {
                    val success = metadataEmbedder.downloadAlbumArt(artUrl, artDest)
                    if (success) albumArtFile = artDest
                } else {
                    albumArtFile = artDest
                }
            }

            // Embed metadata
            metadataEmbedder.embed(downloadResult.outputFile, request, albumArtFile)

            // Move to final location
            val finalFile = fileOrganizer.moveToFinalLocation(downloadResult.outputFile, request)

            // Post-download hash check
            val hashDuplicate = duplicateDetector.checkFileHash(finalFile)
            if (hashDuplicate != null && hashDuplicate != request.trackId) {
                Timber.w("Post-download hash duplicate detected! Existing: $hashDuplicate")
                finalFile.delete()
                updateSourceToBoth(hashDuplicate, request)
                updateProgress(request.trackId, DownloadProgress(request.trackId, DownloadStatus.COMPLETED))
                return
            }

            // Update track entity in DB
            trackDao.updateDownloadInfo(
                trackId = request.trackId,
                filePath = finalFile.absolutePath,
                fileFormat = "opus",
                qualityKbps = qualityTier.approximateBitrateKbps,
                fileSizeBytes = finalFile.length(),
                isDownloaded = true,
                youtubeId = matchResult.videoInfo.id,
                matchConfidence = matchResult.confidence,
                fileHash = null, // computed lazily
                albumArtPath = albumArtFile?.absolutePath,
            )

            downloadQueueDao.updateStatus(request.trackId, DownloadStatus.COMPLETED.name)
            updateProgress(request.trackId, DownloadProgress(request.trackId, DownloadStatus.COMPLETED))
            _queueSize.update { (it - 1).coerceAtLeast(0) }

            Timber.i("Download complete: ${request.artist} - ${request.title}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleDownloadFailure(request, e)
        }
    }

    private suspend fun handleDownloadFailure(request: DownloadRequest, error: Throwable) {
        Timber.e(error, "Download failed: ${request.title}")

        val isExtractorError = error.message?.let {
            "ExtractorError" in it || "unable to extract" in it.lowercase()
        } ?: false

        if (isExtractorError) {
            // Trigger yt-dlp update and retry
            Timber.w("Extractor error detected, triggering yt-dlp update")
            YtDlpUpdateWorker.enqueueImmediateUpdate(context)
        }

        updateProgress(
            request.trackId,
            DownloadProgress(
                request.trackId,
                DownloadStatus.FAILED,
                errorMessage = error.message,
            ),
        )
        downloadQueueDao.updateStatus(request.trackId, DownloadStatus.FAILED.name)
        downloadQueueDao.updateError(request.trackId, error.message ?: "Unknown error")
        _queueSize.update { (it - 1).coerceAtLeast(0) }
    }

    private fun updateProgress(trackId: Long, progress: DownloadProgress) {
        _activeDownloads.update { map -> map + (trackId to progress) }
    }

    private suspend fun updateSourceToBoth(existingTrackId: Long, request: DownloadRequest) {
        // When a track exists from one source and is found in the other, mark as BOTH
        trackDao.updateSourceToBoth(existingTrackId)
        if (request.spotifyUri != null) {
            trackDao.updateSpotifyUri(existingTrackId, request.spotifyUri)
        }
        if (request.youtubeId != null) {
            trackDao.updateYoutubeId(existingTrackId, request.youtubeId)
        }
    }

    /**
     * Reduce concurrency to 1 (called on rate limiting).
     * Restores after the backoff period.
     */
    suspend fun enterRateLimitMode(backoffMs: Long) {
        Timber.w("Entering rate limit mode for ${backoffMs}ms")
        // The semaphore approach: we can't dynamically resize, so instead
        // we acquire 2 of the 3 permits for the backoff duration
        scope.launch {
            val permit1 = semaphore.also { it.acquire() }
            val permit2 = semaphore.also { it.acquire() }
            delay(backoffMs)
            semaphore.release()
            semaphore.release()
            Timber.d("Rate limit mode ended")
        }
    }
}
```

**Verify:** Integration test: enqueue 5 mock downloads, verify only 3 run concurrently (check semaphore behavior). Verify state machine transitions in `activeDownloads` flow.

---

### Task 6.16: Rate limit backoff strategy

**File:** `data/download/src/main/kotlin/com/stash/data/download/engine/RateLimitHandler.kt`

**Why:** YouTube returns 429 when rate-limited. We need exponential backoff with concurrency reduction.

**Code:**
```kotlin
package com.stash.data.download.engine

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class RateLimitHandler @Inject constructor(
    private val downloadManager: DownloadManager,
) {
    private var consecutiveRateLimits = 0

    companion object {
        private val BACKOFF_STAGES_MS = longArrayOf(
            30_000L,   // 30 seconds
            60_000L,   // 1 minute
            120_000L,  // 2 minutes
            300_000L,  // 5 minutes
        )
    }

    /**
     * Called when a 429 or rate-limit error is detected.
     * Returns the backoff duration in milliseconds.
     */
    suspend fun onRateLimited(): Long {
        val stage = min(consecutiveRateLimits, BACKOFF_STAGES_MS.size - 1)
        val backoffMs = BACKOFF_STAGES_MS[stage]
        consecutiveRateLimits++

        Timber.w("Rate limited (stage $stage). Backing off for ${backoffMs}ms")
        downloadManager.enterRateLimitMode(backoffMs)

        return backoffMs
    }

    /**
     * Called on a successful download to reset the backoff counter.
     */
    fun onSuccess() {
        if (consecutiveRateLimits > 0) {
            Timber.d("Rate limit counter reset after successful download")
            consecutiveRateLimits = 0
        }
    }
}
```

**Verify:** Unit test: call `onRateLimited()` three times, verify backoff values are 30s, 60s, 120s. Call `onSuccess()`, verify next `onRateLimited()` returns 30s again.

---

### Task 6.17: Hilt module for download dependencies

**File:** `data/download/src/main/kotlin/com/stash/data/download/di/DownloadModule.kt`

**Why:** Wire up all the download engine components via Hilt.

**Code:**
```kotlin
package com.stash.data.download.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(false) // no animation needed for album art download
            .build()
    }

    // All other classes use @Inject constructor and @Singleton,
    // so Hilt discovers them automatically.
    // DataStore<Preferences> is provided by :core:data module.
    // TrackDao and DownloadQueueDao are provided by :core:data module.
}
```

**Verify:** `./gradlew :data:download:kaptDebugKotlin` succeeds with no missing bindings.

---

### Task 6.18: TrackDownloadWorker -- Wire into sync engine

**File:** `data/download/src/main/kotlin/com/stash/data/download/worker/TrackDownloadWorker.kt`

**Why:** This is the WorkManager worker called from the sync chain (Phase 5). It reads the download queue from Room and feeds requests into `DownloadManager`.

**Code:**
```kotlin
package com.stash.data.download.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.stash.core.data.dao.DownloadQueueDao
import com.stash.core.data.dao.TrackDao
import com.stash.data.download.engine.DownloadManager
import com.stash.data.download.model.DownloadPriority
import com.stash.data.download.model.DownloadRequest
import com.stash.data.download.model.DownloadStatus
import com.stash.data.download.ytdlp.YtDlpManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

@HiltWorker
class TrackDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val ytDlpManager: YtDlpManager,
    private val downloadManager: DownloadManager,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "sync_progress"
    }

    override suspend fun doWork(): Result {
        Timber.i("TrackDownloadWorker starting")

        // Ensure yt-dlp is initialized
        ytDlpManager.initialize().getOrElse { e ->
            Timber.e(e, "Failed to initialize yt-dlp")
            return Result.failure()
        }

        // Promote to foreground service for long-running work
        setForeground(createForegroundInfo("Preparing downloads..."))

        // Load pending queue items
        val pendingItems = downloadQueueDao.findByStatus(DownloadStatus.QUEUED.name)
        if (pendingItems.isEmpty()) {
            Timber.d("No pending downloads")
            return Result.success()
        }

        Timber.i("Processing ${pendingItems.size} downloads")

        // Start the download processing loop
        downloadManager.startProcessing()

        // Enqueue all pending items
        var enqueuedCount = 0
        for (queueItem in pendingItems) {
            val track = trackDao.getById(queueItem.trackId) ?: continue
            val request = DownloadRequest(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                albumArtUrl = track.albumArtUrl,
                spotifyUri = track.spotifyUri,
                youtubeId = track.youtubeId,
                trackNumber = null,
                priority = DownloadPriority.NORMAL,
            )
            val enqueued = downloadManager.enqueue(request)
            if (enqueued) enqueuedCount++
        }

        // Wait for all downloads to complete by monitoring queue size
        downloadManager.queueSize
            .first { it == 0 }

        // Summarize results
        val completed = downloadQueueDao.countByStatus(DownloadStatus.COMPLETED.name)
        val failed = downloadQueueDao.countByStatus(DownloadStatus.FAILED.name)
        val unmatched = downloadQueueDao.countByStatus(DownloadStatus.UNMATCHED.name)

        Timber.i("Download batch complete: $completed completed, $failed failed, $unmatched unmatched")

        setForeground(
            createForegroundInfo("Downloads complete: $completed tracks"),
        )

        return if (failed > 0 && completed == 0) Result.failure() else Result.success(
            workDataOf(
                "completed" to completed,
                "failed" to failed,
                "unmatched" to unmatched,
            ),
        )
    }

    private fun createForegroundInfo(status: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Stash - Syncing Music")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
```

**Verify:** Wire into the sync chain from Phase 5 by ensuring `SyncChainBuilder` references `TrackDownloadWorker::class.java`. Run a full sync chain with a mock playlist containing 2 tracks.

---

### Task 6.19: DAO additions for download queue management

**File:** `core/data/src/main/kotlin/com/stash/core/data/dao/DownloadQueueDao.kt`

**Why:** The download engine needs CRUD operations on the `download_queue` table for tracking status, retries, and errors.

**Code:**
```kotlin
package com.stash.core.data.dao

import androidx.room.*
import com.stash.core.data.entity.DownloadQueueEntity

@Dao
interface DownloadQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadQueueEntity): Long

    @Query("SELECT * FROM download_queue WHERE status = :status ORDER BY created_at ASC")
    suspend fun findByStatus(status: String): List<DownloadQueueEntity>

    @Query("SELECT * FROM download_queue WHERE track_id = :trackId LIMIT 1")
    suspend fun findByTrackId(trackId: Long): DownloadQueueEntity?

    @Query("UPDATE download_queue SET status = :status, completed_at = :completedAt WHERE track_id = :trackId")
    suspend fun updateStatus(
        trackId: Long,
        status: String,
        completedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE download_queue SET youtube_url = :url WHERE track_id = :trackId")
    suspend fun updateYoutubeUrl(trackId: Long, url: String)

    @Query("UPDATE download_queue SET error_message = :error WHERE track_id = :trackId")
    suspend fun updateError(trackId: Long, error: String)

    @Query("UPDATE download_queue SET retry_count = retry_count + 1 WHERE track_id = :trackId")
    suspend fun incrementRetry(trackId: Long)

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("DELETE FROM download_queue WHERE status IN ('COMPLETED', 'CANCELLED') AND completed_at < :beforeTimestamp")
    suspend fun cleanupOld(beforeTimestamp: Long)
}
```

**Verify:** Compiles after Room annotation processing. DAO methods align with `DownloadQueueEntity` schema from Phase 4.

---

### Task 6.20: TrackDao additions for download info updates

**File:** `core/data/src/main/kotlin/com/stash/core/data/dao/TrackDao.kt` (add methods)

**Why:** The download engine needs to update track entities with file paths, quality info, YouTube IDs, and source flags after download.

**Code to add:**
```kotlin
@Query("""
    UPDATE tracks SET 
        file_path = :filePath,
        file_format = :fileFormat,
        quality_kbps = :qualityKbps,
        file_size_bytes = :fileSizeBytes,
        is_downloaded = :isDownloaded,
        youtube_id = :youtubeId,
        match_confidence = :matchConfidence,
        file_hash = :fileHash,
        album_art_path = :albumArtPath
    WHERE id = :trackId
""")
suspend fun updateDownloadInfo(
    trackId: Long,
    filePath: String,
    fileFormat: String,
    qualityKbps: Int,
    fileSizeBytes: Long,
    isDownloaded: Boolean,
    youtubeId: String,
    matchConfidence: Float,
    fileHash: String?,
    albumArtPath: String?,
)

@Query("UPDATE tracks SET source = 'BOTH' WHERE id = :trackId")
suspend fun updateSourceToBoth(trackId: Long)

@Query("UPDATE tracks SET spotify_uri = :uri WHERE id = :trackId")
suspend fun updateSpotifyUri(trackId: Long, uri: String)

@Query("UPDATE tracks SET youtube_id = :id WHERE id = :trackId")
suspend fun updateYoutubeId(trackId: Long, id: String)

@Query("SELECT * FROM tracks WHERE id = :id")
suspend fun getById(id: Long): TrackEntity?
```

**Verify:** Room compilation succeeds. Queries match column names in `TrackEntity`.

---

### Task 6.21: Settings screen -- Audio quality selector UI

**File:** `feature/settings/src/main/kotlin/com/stash/feature/settings/AudioQualitySection.kt`

**Why:** Users need to choose their preferred audio quality tier. The UI shows the 4 tiers with bitrate and estimated storage per track.

**Code:**
```kotlin
package com.stash.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.model.QualityTier

@Composable
fun AudioQualitySection(
    selectedTier: QualityTier,
    trackCount: Int,
    onTierSelected: (QualityTier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "AUDIO QUALITY",
            style = MaterialTheme.typography.labelMedium,
            color = StashTheme.colors.primary,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(12.dp))

        QualityTier.entries.forEach { tier ->
            val isSelected = tier == selectedTier
            QualityTierCard(
                tier = tier,
                isSelected = isSelected,
                estimatedStorageMb = estimateStorage(tier, trackCount),
                onClick = { onTierSelected(tier) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Storage estimation footer
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Estimated for $trackCount tracks (avg. 3.5 min each)",
            style = MaterialTheme.typography.bodySmall,
            color = StashTheme.colors.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun QualityTierCard(
    tier: QualityTier,
    isSelected: Boolean,
    estimatedStorageMb: Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (isSelected) StashTheme.colors.primary else Color.White.copy(alpha = 0.08f)
    val bgColor = if (isSelected) {
        StashTheme.colors.primary.copy(alpha = 0.1f)
    } else {
        StashTheme.colors.elevatedSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Radio indicator
        RadioButton(
            selected = isSelected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = StashTheme.colors.primary,
                unselectedColor = Color.White.copy(alpha = 0.3f),
            ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tier.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = StashTheme.colors.onSurface,
            )
            Text(
                text = "${tier.approximateBitrateKbps} kbps Opus",
                style = MaterialTheme.typography.bodySmall,
                color = StashTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${tier.sizeMbPerMinute} MB/min",
                style = MaterialTheme.typography.bodySmall,
                color = StashTheme.colors.onSurface.copy(alpha = 0.7f),
            )
            Text(
                text = "~${formatStorage(estimatedStorageMb)}",
                style = MaterialTheme.typography.labelSmall,
                color = StashTheme.colors.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

private fun estimateStorage(tier: QualityTier, trackCount: Int): Float {
    val avgDurationMinutes = 3.5f
    return tier.sizeMbPerMinute * avgDurationMinutes * trackCount
}

private fun formatStorage(mb: Float): String {
    return if (mb >= 1024f) {
        "%.1f GB".format(mb / 1024f)
    } else {
        "%.0f MB".format(mb)
    }
}
```

**Wire into SettingsScreen:**
```kotlin
// In SettingsScreen.kt
val selectedQuality by settingsViewModel.qualityTier.collectAsStateWithLifecycle()
val trackCount by settingsViewModel.totalTrackCount.collectAsStateWithLifecycle()

AudioQualitySection(
    selectedTier = selectedQuality,
    trackCount = trackCount,
    onTierSelected = { settingsViewModel.setQualityTier(it) },
)
```

**Verify:** Preview shows 4 cards. Selecting "Normal" highlights it with purple border and updates DataStore.

---

### Task 6.22: SettingsViewModel additions for quality management

**File:** `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` (add to existing)

**Why:** The ViewModel bridges the UI to the `DownloadPreferences` DataStore and provides track count for storage estimation.

**Code to add:**
```kotlin
// Add to existing SettingsViewModel

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val downloadPreferences: DownloadPreferences,
    private val trackDao: TrackDao,
    // ... existing dependencies
) : ViewModel() {

    val qualityTier: StateFlow<QualityTier> = downloadPreferences.qualityTier
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = QualityTier.BEST,
        )

    val totalTrackCount: StateFlow<Int> = trackDao.countAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    val storageUsedBytes: StateFlow<Long> = trackDao.totalFileSize()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0L,
        )

    fun setQualityTier(tier: QualityTier) {
        viewModelScope.launch {
            downloadPreferences.setQualityTier(tier)
        }
    }
}
```

**TrackDao additions needed:**
```kotlin
@Query("SELECT COUNT(*) FROM tracks")
fun countAll(): Flow<Int>

@Query("SELECT COALESCE(SUM(file_size_bytes), 0) FROM tracks WHERE is_downloaded = 1")
fun totalFileSize(): Flow<Long>
```

**Verify:** SettingsScreen displays correct track count and storage estimation. Changing quality persists across app restart.

---

## Dependency Graph

```
Task 6.1  (gradle deps)
  |
  ├── Task 6.2  (YtDlpManager init)
  │     └── Task 6.3  (update worker)
  |
  ├── Task 6.4  (QualityTier + prefs)
  |
  ├── Task 6.5  (DownloadState models)
  |
  ├── Task 6.6  (StringNormalization) ─── in :core:common
  │     └── Task 6.7  (JaroWinkler) ──── in :core:common
  |
  ├── Task 6.8  (yt-dlp DTOs)
  │     └── Task 6.9  (TrackMatcher)
  │           └── Task 6.10 (MatchScorer) ── depends on 6.6, 6.7
  |
  ├── Task 6.11 (DuplicateDetector) ──── depends on 6.6, 6.7
  |
  ├── Task 6.12 (FileOrganizer) ─────── depends on 6.6
  |
  ├── Task 6.13 (MetadataEmbedder) ──── depends on 6.12
  |
  ├── Task 6.14 (DownloadExecutor) ──── depends on 6.4, 6.12
  |
  ├── Task 6.15 (DownloadManager) ───── depends on 6.2, 6.9, 6.11, 6.13, 6.14
  │     └── Task 6.16 (RateLimitHandler)
  |
  ├── Task 6.17 (Hilt module)
  |
  ├── Task 6.18 (TrackDownloadWorker) ─ depends on 6.15
  |
  ├── Task 6.19 (DownloadQueueDao)
  ├── Task 6.20 (TrackDao additions)
  |
  ├── Task 6.21 (AudioQualitySection UI) ── depends on 6.4
  └── Task 6.22 (SettingsViewModel) ──────── depends on 6.4, 6.21
```

**Recommended implementation order:**
1. Tasks 6.1, 6.5, 6.6, 6.7, 6.8, 6.4 (foundations, can be parallel)
2. Tasks 6.19, 6.20 (DAO layer)
3. Tasks 6.2, 6.3 (yt-dlp lifecycle)
4. Tasks 6.10, 6.9 (matching)
5. Tasks 6.11 (dedup)
6. Tasks 6.12, 6.13, 6.14 (file + metadata + executor)
7. Tasks 6.15, 6.16, 6.17 (orchestration + DI)
8. Task 6.18 (WorkManager integration)
9. Tasks 6.21, 6.22 (UI)

---

## Known Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| ffmpeg METADATA_BLOCK_PICTURE approach fails on some devices | Fallback to text-only tags (Task 6.13 already has this). Album art saved as `cover.jpg` in album directory for player to discover. |
| yt-dlp search returns no results for obscure tracks | Tier 2 broadens search. UNMATCHED status surfaces to user for manual resolution. |
| Concurrent yt-dlp processes cause OOM on low-RAM devices | Semaphore(3) is the cap; could be reduced to 2 based on `ActivityManager.memoryClass`. Add a TODO. |
| youtubedl-android library breaks on update | Pin to `0.17.+` minor range, not `+` wildcard. Test before bumping. |
| Base64 METADATA_BLOCK_PICTURE exceeds arg length limit (~2MB) | Album art resized to 500x500 JPEG at quality 90, typically 30-80KB. Well under limit. |

---

## Testing Strategy

- **Unit tests:** StringNormalization, JaroWinkler, MatchScorer, QualityTier, RateLimitHandler -- all pure logic, no Android deps
- **Instrumented tests:** YtDlpManager init/update (needs native libs), DownloadExecutor with a real short video, MetadataEmbedder with a real Opus file
- **Integration test:** Full pipeline from DownloadRequest -> COMPLETED state with a known YouTube URL
- **UI tests:** AudioQualitySection preview rendering, selection persistence
```

---

### Critical Files for Implementation

- `data/download/src/main/kotlin/com/stash/data/download/engine/DownloadManager.kt` - Core orchestrator: Channel queue, Semaphore concurrency, full state machine driving all other components
- `data/download/src/main/kotlin/com/stash/data/download/matcher/TrackMatcher.kt` - Two-tier YouTube search via yt-dlp with scoring delegation, the most error-prone component requiring careful yt-dlp JSON parsing
- `data/download/src/main/kotlin/com/stash/data/download/metadata/MetadataEmbedder.kt` - Vorbis tag + METADATA_BLOCK_PICTURE album art embedding via ffmpeg, contains the tricky binary blob construction for Opus cover art
- `data/download/src/main/kotlin/com/stash/data/download/ytdlp/YtDlpManager.kt` - Binary lifecycle management (init, update, version); if this fails, nothing else works
- `core/common/src/main/kotlin/com/stash/core/common/StringNormalization.kt` - Shared across dedup, matching, and file organization; getting canonicalization wrong cascades into duplicate downloads or missed matches

Sources:
- [youtubedl-android (yausername)](https://github.com/yausername/youtubedl-android)
- [JunkFood02/Seal](https://github.com/JunkFood02/Seal)
- [FFmpeg ticket #4448 - Opus cover art](https://fftrac-bg.ffmpeg.org/ticket/4448)
- [string-similarity-kotlin](https://github.com/aallam/string-similarity-kotlin)
- [yt-dlp embed metadata Opus issues](https://github.com/yt-dlp/yt-dlp/issues/4775)
- [Maven: youtubedl-android library](https://mvnrepository.com/artifact/io.github.junkfood02.youtubedl-android/library)