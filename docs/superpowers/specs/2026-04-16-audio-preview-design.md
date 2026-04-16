# Audio Preview in Search Tab — Design Spec

## Goal

Add audio preview to Search tab results so users can listen to a YouTube track before downloading. Especially valuable for verifying failed matches — users can hear the song to confirm it's the right version.

## Scope

- Preview (play/stop) button on each search result row
- Standalone ExoPlayer instance for streaming (separate from main library player)
- yt-dlp stream URL extraction without downloading
- Auto-pause main player via audio focus

## UX Flow

1. User searches for a track in the Search tab.
2. Each result row shows: thumbnail | title + artist | duration | **preview button** | **download button**.
3. User taps the preview (play triangle) button → icon changes to stop (square), a loading spinner shows briefly while yt-dlp extracts the stream URL (~1-3 seconds).
4. Audio streams via a standalone ExoPlayer instance. Main library player pauses automatically via Android audio focus.
5. User taps stop → preview stops, icon reverts to play. Or taps preview on a different result → current stops, new one starts.
6. User can download at any time — preview and download are independent actions.
7. No time limit on preview. User listens as long as needed, stops when satisfied.

### Button States

Each search result's preview button cycles through:
- **Idle**: play triangle icon (default)
- **Loading**: small CircularProgressIndicator (while yt-dlp extracts URL, ~1-3s)
- **Playing**: stop square icon
- **Error**: reverts to play icon, inline error shown in `SearchUiState.previewError`

Only one result can preview at a time. Tapping preview on a different result auto-stops the current one.

## Technical Architecture

### Coordination Pattern

**Module dependency constraint:** `core/media` cannot depend on `data/download` (layering violation). The coordination lives in `SearchViewModel`, which already depends on both modules:

```
SearchViewModel (feature/search)
    ├─ calls PreviewUrlExtractor.extractStreamUrl(videoId)  → data/download
    ├─ receives streamUrl: String
    └─ calls PreviewPlayer.playUrl(videoId, streamUrl)      → core/media
```

`PreviewPlayer` knows nothing about yt-dlp — it only receives a URL and plays it. `PreviewUrlExtractor` knows nothing about ExoPlayer — it only extracts URLs. `SearchViewModel` is the coordinator.

### PreviewPlayer

New class in `core/media/preview/`. Wraps a standalone ExoPlayer instance for preview playback only.

```kotlin
class PreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val previewState: StateFlow<PreviewState>

    fun playUrl(videoId: String, streamUrl: String)  // Set MediaItem and play
    fun stop()                                        // Stop playback, revert to Idle
    fun release()                                     // Release ExoPlayer resources
}

sealed interface PreviewState {
    data object Idle : PreviewState
    data class Playing(val videoId: String) : PreviewState
}
```

**Key characteristics:**
- Completely separate from `StashPlaybackService` — no MediaSession, no notification, no foreground service. Media3 supports multiple independent ExoPlayer instances with no global state conflicts.
- Accepts HTTP/HTTPS URLs directly (bypasses the URI whitelist on the main service)
- ExoPlayer built with explicit audio focus handling:

```kotlin
val audioAttributes = AudioAttributes.Builder()
    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
    .setUsage(C.USAGE_MEDIA)
    .build()

val player = ExoPlayer.Builder(context)
    .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
    .build()
```

When `handleAudioFocus = true`, ExoPlayer automatically requests `AUDIOFOCUS_GAIN_TRANSIENT` on `play()`. The main player's ExoPlayer (also with `handleAudioFocus = true`) receives `AUDIOFOCUS_LOSS_TRANSIENT` and pauses automatically — no manual focus management needed.

- Scoped via Hilt `@Singleton` — the ExoPlayer instance is lightweight when idle (no buffers allocated until `playUrl` is called). Singleton avoids recreating the player on every Search tab visit.
- ExoPlayer listener updates `previewState` on state changes (`STATE_ENDED` → Idle, `STATE_READY + isPlaying` → Playing)
- `SearchViewModel.onCleared()` must call `previewPlayer.stop()` to ensure preview stops when leaving the Search tab.

### PreviewUrlExtractor

New class in `data/download/preview/`. Extracts the direct CDN stream URL for a YouTube video without downloading.

```kotlin
class PreviewUrlExtractor @Inject constructor(
    private val ytDlpManager: YtDlpManager,
) {
    suspend fun extractStreamUrl(videoId: String): String {
        ytDlpManager.ensureInitialized()

        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
        request.addOption("-f", "251/250/bestaudio")
        request.addOption("--dump-json")
        request.addOption("--no-download")

        // QuickJS runtime for YouTube signature solving (same as DownloadExecutor)
        val qjsPath = ytDlpManager.getQuickJsPath()
        if (qjsPath != null) {
            request.addOption("--js-runtimes", "quickjs:$qjsPath")
        }

        // Cookie injection for authenticated access (same as DownloadExecutor)
        val cookieFile = ytDlpManager.getCookieFile()
        if (cookieFile?.exists() == true) {
            request.addOption("--cookies", cookieFile.absolutePath)
        }

        val response = withTimeout(10_000L) {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request)
            }
        }

        return parseStreamUrlFromJson(response.out)
    }
}
```

**IMPORTANT: Do NOT add `--flat-playlist`.** This flag suppresses per-video metadata (including the `url` field) and is only used in `YouTubeSearchExecutor` for batch search performance. For single-video URL extraction, the full metadata fetch is necessary and takes only ~1-3 seconds.

**Timeout note:** `withTimeout(10_000L)` wraps the coroutine. However, `YoutubeDL.execute()` runs blocking native code that does not respond to coroutine cancellation cooperatively — the underlying process continues even after timeout. The timeout means "stop waiting and surface the error to the user," not "kill the process."

### SearchViewModel changes

- Injects `PreviewPlayer` and `PreviewUrlExtractor`
- Exposes `previewState: StateFlow<PreviewState>` from `PreviewPlayer` to the UI
- Coordinates URL extraction → playback:

```kotlin
fun previewTrack(videoId: String) {
    // Stop any current preview
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
}

override fun onCleared() {
    super.onCleared()
    previewPlayer.stop()
}
```

### SearchUiState changes

Add to `SearchUiState`:
```kotlin
val previewLoading: String? = null,   // videoId currently loading
val previewError: String? = null,     // error message to display inline
```

Combined with `previewPlayer.previewState` (which tracks `Playing(videoId)`), the UI has full information for button state rendering.

### SearchScreen changes

- Each `SearchResultRow` gets a preview button (left of the existing download button)
- Button state logic per row:
  - `uiState.previewLoading == thisVideoId` → CircularProgressIndicator
  - `previewState is Playing(thisVideoId)` → stop icon
  - else → play icon
- Error rendering: if `uiState.previewError != null`, show inline error text (same pattern as existing search error state), auto-clear after 3 seconds

### Main Player Interaction

- Preview's ExoPlayer requests audio focus automatically via `handleAudioFocus = true`
- Main player's ExoPlayer receives `AUDIOFOCUS_LOSS_TRANSIENT` and pauses automatically
- When preview stops, it abandons audio focus. Main player does NOT auto-resume — standard Android behavior, avoids unexpected playback resumption.

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `core/media/.../preview/PreviewPlayer.kt` | **Create** | Standalone ExoPlayer wrapper (playback only) |
| `data/download/.../preview/PreviewUrlExtractor.kt` | **Create** | yt-dlp stream URL extraction (no playback knowledge) |
| `core/media/di/MediaModule.kt` | Modify | Provide PreviewPlayer singleton |
| `feature/search/.../SearchViewModel.kt` | Modify | Inject both, coordinate extraction → playback |
| `feature/search/.../SearchScreen.kt` | Modify | Add preview button to result rows |
| `feature/search/.../SearchUiState.kt` | Modify | Add previewLoading, previewError fields |

## Edge Cases

- **Preview while another preview is playing**: auto-stops the previous, starts the new one.
- **Preview while library music is playing**: main player pauses via audio focus.
- **Network error during URL extraction**: inline error in UI, revert preview button to play icon.
- **URL extraction timeout (10s)**: error state, revert to play icon.
- **User navigates away from Search tab**: `SearchViewModel.onCleared()` calls `previewPlayer.stop()`.
- **App backgrounded during preview**: preview pauses (ExoPlayer loses audio focus, no foreground service to keep it alive).
- **Stream URL expires mid-preview**: ExoPlayer error → state reverts to Idle, user can tap preview again to re-extract.
- **yt-dlp not initialized**: `YtDlpManager.ensureInitialized()` called before extraction (same pattern as downloads).
- **QuickJS runtime missing**: handled gracefully — yt-dlp falls back to other JS runtimes or fails with a clear error.

## Out of Scope

- Seek bar / progress indicator on preview
- Preview in screens other than Search
- Caching preview URLs
- Preview quality selection (always best available)
- Background preview playback (no notification/foreground service)
