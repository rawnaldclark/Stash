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
- **Error**: reverts to play icon, brief error toast shown

Only one result can preview at a time. Tapping preview on a different result auto-stops the current one.

## Technical Architecture

### PreviewPlayer

New class in `core/media/`. Wraps a standalone ExoPlayer instance dedicated to preview playback.

```kotlin
class PreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val previewState: StateFlow<PreviewState>  // Idle, Loading(videoId), Playing(videoId), Error(message)

    suspend fun preview(videoId: String)  // Extract URL via yt-dlp, then stream
    fun stop()                            // Stop playback, revert to Idle
    fun release()                         // Release ExoPlayer resources
}

sealed interface PreviewState {
    data object Idle : PreviewState
    data class Loading(val videoId: String) : PreviewState
    data class Playing(val videoId: String) : PreviewState
    data class Error(val message: String) : PreviewState
}
```

**Key characteristics:**
- Completely separate from `StashPlaybackService` — no MediaSession, no notification, no foreground service
- Accepts HTTP/HTTPS URLs directly (bypasses the URI whitelist on the main service)
- Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT` — main player receives focus loss and pauses automatically
- Audio attributes: `CONTENT_TYPE_MUSIC`, `USAGE_MEDIA`
- Singleton scoped via Hilt `@Singleton` (lives as long as the app)
- On `preview()`: launches coroutine to extract stream URL, then sets MediaItem on ExoPlayer and plays
- On `stop()`: stops ExoPlayer, updates state to Idle
- ExoPlayer listener updates `previewState` on playback state changes (e.g., `STATE_ENDED` → Idle)

### Stream URL Extraction

Reuses existing yt-dlp infrastructure to extract the direct audio stream URL without downloading:

```kotlin
suspend fun extractStreamUrl(videoId: String): String {
    val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
    request.addOption("-f", "251/250/bestaudio")
    request.addOption("--dump-json")
    request.addOption("--no-download")
    // Add cookies, QuickJS runtime (same as DownloadExecutor)
    val response = YoutubeDL.getInstance().execute(request)
    // Parse JSON output → extract "url" field from selected format
    return parseStreamUrlFromJson(response.out)
}
```

- Uses format `251/250/bestaudio` (best Opus, matching existing quality selection)
- yt-dlp's `--dump-json` output includes a `url` field with the direct CDN stream URL
- URL is signed and temporary (expires in hours) — fine for preview playback
- Extraction takes ~1-3 seconds
- 10-second timeout on extraction

This logic lives in a `PreviewUrlExtractor` class in `data/download/` that reuses `YtDlpManager` for initialization and cookie handling.

### SearchViewModel changes

- Injects `PreviewPlayer`
- Exposes `previewState: StateFlow<PreviewState>` to the UI
- `fun previewTrack(videoId: String)` — calls `previewPlayer.preview(videoId)`
- `fun stopPreview()` — calls `previewPlayer.stop()`

### SearchScreen changes

- Each `SearchResultRow` gets a preview button (left of the existing download button)
- Button icon/state derived from `previewState`:
  - If `previewState` is `Loading(thisVideoId)` → show CircularProgressIndicator
  - If `previewState` is `Playing(thisVideoId)` → show stop icon
  - Otherwise → show play icon
- Tapping the preview button calls `viewModel.previewTrack(videoId)` or `viewModel.stopPreview()` depending on state

### Main Player Interaction

- Preview requests `AUDIOFOCUS_GAIN_TRANSIENT` via ExoPlayer's built-in audio focus handling
- Main player (StashPlaybackService) receives `AUDIOFOCUS_LOSS_TRANSIENT` and pauses automatically (already configured with `handleAudioFocus = true`)
- When preview stops, it abandons audio focus. Main player does NOT auto-resume — this is standard Android behavior and avoids unexpected playback resumption.

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `core/media/.../preview/PreviewPlayer.kt` | **Create** | Standalone ExoPlayer wrapper for preview |
| `core/media/.../preview/PreviewState.kt` | **Create** | Sealed interface for preview states |
| `data/download/.../preview/PreviewUrlExtractor.kt` | **Create** | yt-dlp stream URL extraction |
| `core/media/di/MediaModule.kt` | Modify | Provide PreviewPlayer singleton |
| `feature/search/.../SearchViewModel.kt` | Modify | Inject PreviewPlayer, expose state |
| `feature/search/.../SearchScreen.kt` | Modify | Add preview button to result rows |

## Edge Cases

- **Preview while another preview is playing**: auto-stops the previous, starts the new one.
- **Preview while library music is playing**: main player pauses via audio focus.
- **Network error during URL extraction**: error toast, revert preview button to play icon.
- **URL extraction timeout (10s)**: error state, revert to play icon.
- **User navigates away from Search tab**: preview stops (ViewModel cleared).
- **App backgrounded during preview**: preview stops (no foreground service, ExoPlayer pauses on focus loss).
- **Stream URL expires mid-preview**: ExoPlayer error → state reverts to Idle, user can tap preview again to re-extract.
- **yt-dlp not initialized**: `YtDlpManager.ensureInitialized()` called before extraction (same pattern as downloads).

## Out of Scope

- Seek bar / progress indicator on preview
- Preview in screens other than Search
- Caching preview URLs
- Preview quality selection (always best available)
- Background preview playback (no notification/foreground service)
