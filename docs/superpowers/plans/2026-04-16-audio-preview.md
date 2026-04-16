# Audio Preview in Search Tab — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add audio preview to Search tab so users can listen to a YouTube track before downloading — tap play to stream, tap stop to end.

**Architecture:** `PreviewUrlExtractor` (in `data/download`) uses yt-dlp to extract a direct CDN stream URL without downloading. `PreviewPlayer` (in `core/media`) wraps a standalone ExoPlayer instance that streams the URL. `SearchViewModel` coordinates both — it already depends on both modules. The preview player is completely separate from `StashPlaybackService` (no MediaSession, no notification). Audio focus handles main player pausing automatically.

**Tech Stack:** yt-dlp (`--dump-json --no-download`), Media3 ExoPlayer, Hilt, Kotlin Coroutines/Flow

**Spec:** `docs/superpowers/specs/2026-04-16-audio-preview-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `data/download/.../preview/PreviewUrlExtractor.kt` | **Create** | yt-dlp stream URL extraction |
| `core/media/.../preview/PreviewPlayer.kt` | **Create** | Standalone ExoPlayer wrapper + PreviewState |
| `core/media/di/MediaModule.kt` | Modify | Provide PreviewPlayer singleton |
| `feature/search/.../SearchUiState.kt` | Modify | Add previewLoading, previewError fields |
| `feature/search/.../SearchViewModel.kt` | Modify | Inject PreviewUrlExtractor + PreviewPlayer, coordinate |
| `feature/search/.../SearchScreen.kt` | Modify | Add preview button to SearchResultRow |

---

### Task 1: Create PreviewUrlExtractor

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt`

- [ ] **Step 1: Create the file**

This class extracts a direct CDN stream URL from YouTube using yt-dlp without downloading the file. It follows the exact same QuickJS + cookie pattern as `DownloadExecutor.kt`.

```kotlin
package com.stash.data.download.preview

import android.content.Context
import com.stash.core.auth.TokenManager
import com.stash.data.download.ytdlp.CookieFileWriter
import com.stash.data.download.ytdlp.YtDlpManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts a direct CDN audio stream URL from YouTube without downloading.
 *
 * Uses yt-dlp's `--dump-json --no-download` mode to fetch video metadata
 * including the signed stream URL. The URL is temporary (expires in hours)
 * but is sufficient for preview playback.
 *
 * IMPORTANT: Does NOT use `--flat-playlist`. That flag suppresses per-video
 * metadata (including the `url` field) and is only used in search contexts.
 * For single-video URL extraction, the full metadata fetch is required.
 */
@Singleton
class PreviewUrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val tokenManager: TokenManager,
) {
    /**
     * Extracts the direct audio stream URL for a YouTube video.
     *
     * @param videoId YouTube video ID (e.g., "dQw4w9WgXcQ")
     * @return Direct CDN URL for the best available audio stream
     * @throws Exception on network error, yt-dlp failure, or timeout (10s)
     */
    suspend fun extractStreamUrl(videoId: String): String {
        ytDlpManager.initialize()

        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
        request.addOption("-f", "251/250/bestaudio")
        request.addOption("--dump-json")
        request.addOption("--no-download")

        // QuickJS runtime for YouTube JS signature solving (same as DownloadExecutor)
        val qjsPath = ytDlpManager.quickJsPath
        if (qjsPath != null) {
            request.addOption("--js-runtimes", "quickjs:$qjsPath")
            request.addOption("--remote-components", "ejs:github")
        }

        // Cookie injection for authenticated access (same as DownloadExecutor)
        val cookieFile = File(context.noBackupFilesDir, "yt_preview_cookies.txt")
        try {
            val cookie = tokenManager.getYouTubeCookie()
            if (cookie != null) {
                CookieFileWriter.write(cookie, cookieFile)
                cookieFile.setReadable(false, false)
                cookieFile.setReadable(true, true)
                cookieFile.setWritable(false, false)
                cookieFile.setWritable(true, true)
                request.addOption("--cookies", cookieFile.absolutePath)
            }

            val response = withTimeout(10_000L) {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request)
                }
            }

            return parseStreamUrl(response.out)
        } finally {
            cookieFile.delete()
        }
    }

    private fun parseStreamUrl(jsonOutput: String): String {
        val json = JSONObject(jsonOutput.trim().lines().last())
        // The "url" field contains the direct CDN stream URL for the selected format
        return json.optString("url").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No stream URL found in yt-dlp output")
    }
}
```

**Key notes for implementer:**
- Read `DownloadExecutor.kt` (lines 78-109) to see the QuickJS + cookie pattern this replicates
- `CookieFileWriter` is an existing utility in the same package — check its exact import path
- The `--dump-json` output is one JSON object per line; the last line contains the selected format's metadata including `url`
- `YtDlpManager.initialize()` is idempotent (guarded by Mutex internally)

- [ ] **Step 2: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt
git commit -m "feat: add PreviewUrlExtractor — yt-dlp stream URL extraction without download"
```

---

### Task 2: Create PreviewPlayer

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/preview/PreviewPlayer.kt`

- [ ] **Step 1: Create the file**

A standalone ExoPlayer wrapper for preview playback. Knows nothing about yt-dlp — only receives a URL and plays it.

```kotlin
package com.stash.core.media.preview

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preview playback state.
 */
sealed interface PreviewState {
    data object Idle : PreviewState
    data class Playing(val videoId: String) : PreviewState
}

/**
 * Standalone ExoPlayer wrapper for audio preview in the Search tab.
 *
 * Completely separate from [StashPlaybackService] — no MediaSession,
 * no notification, no foreground service. Supports HTTP/HTTPS URLs
 * directly (bypasses the main service's URI whitelist).
 *
 * Audio focus is handled automatically: when [playUrl] is called,
 * ExoPlayer requests AUDIOFOCUS_GAIN_TRANSIENT, causing the main
 * library player to pause. When preview stops, focus is abandoned.
 */
@Singleton
class PreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    private var player: ExoPlayer? = null
    private var currentVideoId: String? = null

    private fun getOrCreatePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .also { newPlayer ->
                newPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_ENDED -> {
                                _previewState.value = PreviewState.Idle
                                currentVideoId = null
                            }
                            Player.STATE_READY -> {
                                if (newPlayer.isPlaying) {
                                    currentVideoId?.let {
                                        _previewState.value = PreviewState.Playing(it)
                                    }
                                }
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            currentVideoId?.let {
                                _previewState.value = PreviewState.Playing(it)
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        _previewState.value = PreviewState.Idle
                        currentVideoId = null
                    }
                })
                player = newPlayer
            }
    }

    /**
     * Stream audio from a direct URL.
     *
     * Stops any currently-playing preview first.
     */
    fun playUrl(videoId: String, streamUrl: String) {
        val p = getOrCreatePlayer()
        p.stop()
        currentVideoId = videoId
        p.setMediaItem(MediaItem.fromUri(streamUrl))
        p.prepare()
        p.play()
    }

    /** Stop preview playback. */
    fun stop() {
        player?.stop()
        _previewState.value = PreviewState.Idle
        currentVideoId = null
    }

    /** Release ExoPlayer resources. Called on app termination. */
    fun release() {
        player?.release()
        player = null
        _previewState.value = PreviewState.Idle
        currentVideoId = null
    }
}
```

**Key notes for implementer:**
- Read `StashPlaybackService.kt` to see ExoPlayer builder pattern — this is similar but simpler (no MediaSession)
- The `handleAudioFocus = true` parameter is CRITICAL — without it, the main player won't pause
- Player is lazily created on first `playUrl()` call to avoid unnecessary resource allocation
- `@Singleton` scope means the player survives Search tab navigation; `SearchViewModel.onCleared()` calls `stop()` but doesn't `release()`

- [ ] **Step 2: Add PreviewPlayer to MediaModule**

In `core/media/src/main/kotlin/com/stash/core/media/di/MediaModule.kt`, no change is actually needed — `PreviewPlayer` uses constructor injection with `@Singleton @Inject` directly. Hilt will discover it automatically. BUT verify this by reading the file. If the module uses `@Binds` with an interface, `PreviewPlayer` would need a binding. Since `PreviewPlayer` is a concrete class (no interface), `@Inject constructor` is sufficient.

- [ ] **Step 3: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/preview/PreviewPlayer.kt
git commit -m "feat: add PreviewPlayer — standalone ExoPlayer for search preview playback"
```

---

### Task 3: Update SearchUiState + SearchViewModel

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchUiState.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt`

- [ ] **Step 1: Add preview fields to SearchUiState**

Add two fields to the `SearchUiState` data class:

```kotlin
val previewLoading: String? = null,   // videoId currently loading preview
val previewError: String? = null,     // inline error message for preview failure
```

- [ ] **Step 2: Add preview logic to SearchViewModel**

Read `SearchViewModel.kt` fully. Then:

Add imports:
```kotlin
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.preview.PreviewUrlExtractor
```

Add to constructor injection (after existing params):
```kotlin
private val previewPlayer: PreviewPlayer,
private val previewUrlExtractor: PreviewUrlExtractor,
```

Expose preview state:
```kotlin
val previewState: StateFlow<PreviewState> = previewPlayer.previewState
```

Add preview methods:
```kotlin
fun previewTrack(videoId: String) {
    previewPlayer.stop()
    viewModelScope.launch {
        _uiState.update { it.copy(previewLoading = videoId, previewError = null) }
        try {
            val url = previewUrlExtractor.extractStreamUrl(videoId)
            previewPlayer.playUrl(videoId, url)
            _uiState.update { it.copy(previewLoading = null) }
        } catch (e: Exception) {
            _uiState.update { it.copy(previewLoading = null, previewError = "Couldn't load preview") }
            previewPlayer.stop()
        }
    }
}

fun stopPreview() {
    previewPlayer.stop()
    _uiState.update { it.copy(previewLoading = null) }
}
```

Add cleanup:
```kotlin
override fun onCleared() {
    super.onCleared()
    previewPlayer.stop()
}
```

- [ ] **Step 3: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchUiState.kt feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt
git commit -m "feat: add preview coordination to SearchViewModel — extract URL then stream"
```

---

### Task 4: Add preview button to SearchScreen

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt`

- [ ] **Step 1: Read the file completely**

Understand the current `SearchResultRow` composable and how it's called.

- [ ] **Step 2: Pass preview state to SearchResultRow**

The `SearchResultRow` needs to know:
- Whether this specific item is loading preview (`uiState.previewLoading == item.videoId`)
- Whether this specific item is currently playing (`previewState is PreviewState.Playing(item.videoId)`)

Update the `SearchResultRow` call site in the LazyColumn to pass these:

```kotlin
SearchResultRow(
    item = item,
    isDownloading = item.videoId in uiState.downloadingIds,
    isDownloaded = item.videoId in uiState.downloadedIds,
    isPreviewLoading = uiState.previewLoading == item.videoId,
    isPreviewPlaying = previewState is PreviewState.Playing && previewState.videoId == item.videoId,
    onPreview = { viewModel.previewTrack(item.videoId) },
    onStopPreview = { viewModel.stopPreview() },
    onDownload = { viewModel.downloadTrack(item) },
)
```

Collect `previewState` in the screen:
```kotlin
val previewState by viewModel.previewState.collectAsStateWithLifecycle()
```

- [ ] **Step 3: Add preview button to SearchResultRow**

Update the composable signature to accept new params:

```kotlin
@Composable
private fun SearchResultRow(
    item: SearchResultItem,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    isPreviewLoading: Boolean,
    isPreviewPlaying: Boolean,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit,
)
```

Add a preview button BEFORE the existing download button (in the Row, after the duration text):

```kotlin
// Preview button
IconButton(
    onClick = if (isPreviewPlaying) onStopPreview else onPreview,
    modifier = Modifier.size(40.dp),
) {
    when {
        isPreviewLoading -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        isPreviewPlaying -> Icon(
            imageVector = Icons.Default.Stop,
            contentDescription = "Stop preview",
            tint = MaterialTheme.colorScheme.primary,
        )
        else -> Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Preview",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

Add import: `import androidx.compose.material.icons.filled.Stop`

- [ ] **Step 4: Add inline error display**

If `uiState.previewError != null`, show it briefly. Add after the search results LazyColumn or as a Snackbar:

```kotlin
if (uiState.previewError != null) {
    // Auto-clear after showing
    LaunchedEffect(uiState.previewError) {
        delay(3000)
        viewModel.clearPreviewError()
    }
}
```

Add `fun clearPreviewError()` to SearchViewModel:
```kotlin
fun clearPreviewError() {
    _uiState.update { it.copy(previewError = null) }
}
```

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt
git commit -m "feat: add preview play/stop button to search result rows"
```

---

### Task 5: Build and verify

- [ ] **Step 1: Build**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew --stop 2>&1; sleep 2 && ./gradlew assembleDebug --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix compilation errors**

Common issues:
- `CookieFileWriter` import path — check the actual package in `data/download/src/.../ytdlp/`
- `Icons.Default.Stop` may not exist in base material-icons — check if `material-icons-extended` is in feature:search dependencies (it is: line 12 of build.gradle.kts)
- `PreviewPlayer` Hilt discovery — if it fails, add a `@Provides` method in MediaModule
- `TokenManager` injection in `PreviewUrlExtractor` — check if `core:auth` is a dependency of `data:download` (it is: line 16 of build.gradle.kts)

- [ ] **Step 3: Install and test**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew installDebug --no-daemon 2>&1 | tail -10
```

- [ ] **Step 4: Commit fixes (if any)**

```bash
git add -A && git commit -m "fix: resolve compilation issues for audio preview feature"
```
