# Stash -- Offline Music Sync App Design Specification

**Date:** 2026-03-25
**Status:** Draft
**App Name:** Stash

---

## 1. Overview

Stash is a free, open-source Android app that automatically syncs your Spotify and YouTube Music playlists for offline playback. It connects to both services via OAuth, fetches your Daily Mixes and Liked Songs daily at a scheduled time, downloads the audio via yt-dlp, and stores it locally with a built-in premium music player.

**Core value proposition:** Your curated music, always available offline, without a subscription.

### 1.1 What Stash Is

- A sync tool that pulls from Spotify Daily Mixes (1-6), Spotify Liked Songs, YouTube Music Mixes, and YouTube Music Liked Songs
- A built-in offline music player with premium dark UI
- An automatic daily sync that runs at a user-configured time on WiFi

### 1.2 What Stash Is Not

- A music discovery app
- A streaming service
- A Spotify/YouTube Music replacement for online use

---

## 2. System Architecture

### 2.1 High-Level Components

```
Spotify API  --> Playlist Sync Engine --> Download Queue --> yt-dlp binary --> Local Files
YouTube API  -->                                            ffmpeg binary -->     |
                                                                            Room Database
                                                                                 |
                                                                           Music Player
```

**Six core components:**

1. **Auth Layer** -- OAuth 2.0 (PKCE) for Spotify, Google TV device flow for YouTube Music. Tokens in Android Keystore + DataStore.
2. **Playlist Sync Engine** -- Fetches playlists from both APIs, diffs against local library, queues new tracks.
3. **Download Manager** -- Manages yt-dlp process execution, 3 concurrent downloads, track matching, retry logic.
4. **Local Library (Room DB)** -- Tracks, playlists, sync history, download queue. FTS4 search.
5. **Music Player** -- Media3/ExoPlayer with MediaSessionService, background playback, media notifications.
6. **Scheduled Sync (WorkManager)** -- Daily OneTimeWorkRequest chain at user-configured time, WiFi-only.

### 2.2 Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI Framework | Jetpack Compose |
| Architecture | MVVM + Unidirectional Data Flow |
| DI | Hilt |
| Database | Room + FTS4 |
| Preferences | Jetpack DataStore |
| Networking | OkHttp + Kotlinx Serialization |
| Audio Playback | Media3 ExoPlayer |
| Audio Download | youtubedl-android (yt-dlp + ffmpeg) |
| Background Sync | WorkManager |
| Image Loading | Coil 3 |
| Color Extraction | Palette KTX |

### 2.3 Module Structure

```
:app                    -- Application, Hilt setup, MainActivity, root NavHost
:core:ui                -- Theme, design system, shared composables
:core:model             -- Domain model data classes
:core:data              -- Repositories, Room database, DataStore
:core:network           -- OkHttp client, interceptors, retry logic
:core:auth              -- TokenManager, EncryptedTokenStore, OAuth flows
:core:media             -- MediaSessionService, PlayerRepository, queue manager
:core:common            -- Extensions, utilities, constants
:data:spotify           -- Spotify Web API client, DTOs, playlist sync
:data:ytmusic           -- InnerTube API client, DTOs, library sync
:data:download          -- yt-dlp integration, download queue
:feature:home           -- HomeScreen, HomeViewModel
:feature:library        -- LibraryScreen, LibraryViewModel
:feature:nowplaying     -- NowPlayingScreen, NowPlayingViewModel, MiniPlayer
:feature:sync           -- SyncScreen, SyncViewModel
:feature:settings       -- SettingsScreen, SettingsViewModel
```

### 2.4 Minimum SDK & Target

- **minSdk:** 26 (Android 8.0) -- covers 97%+ of active devices
- **targetSdk:** 35 (latest stable)
- **Compose BOM:** 2026.03.00

---

## 3. Authentication & API Integration

### 3.1 Spotify Authentication

**Flow:** Authorization Code with PKCE (mandatory since November 2025).

- Open Custom Chrome Tab to `https://accounts.spotify.com/authorize`
- Callback via custom scheme: `com.stash.app://callback`
- Exchange code at `/api/token` (no client_secret needed for PKCE)
- Access tokens expire after 1 hour; refresh proactively at 55 minutes

**Required scopes:**
- `user-read-private` -- account details
- `user-library-read` -- liked/saved tracks
- `playlist-read-private` -- private playlists including Daily Mixes

**Accessing Daily Mixes (Primary approach):**
1. Call `GET /v1/me/playlists` (paginated, 50/page)
2. Filter where `owner.id == "spotify"` and `name` matches "Daily Mix 1" through "Daily Mix 6"
3. Fetch tracks via `GET /v1/playlists/{id}/tracks`
4. Users must open their Daily Mixes in Spotify at least once for them to appear in their library

**Rate limiting:** ~180 requests/minute per user token. Implement 100ms delay between calls during bulk sync. Respect `Retry-After` header on 429 responses.

### 3.2 YouTube Music Authentication

**Approach:** Port InnerTube API calls directly to Kotlin/OkHttp (following InnerTune/OuterTune pattern).

**Auth flow:** Google OAuth TV/Limited Input device flow:
- Register client as "TVs and Limited Input devices" in Google Cloud Console
- `POST https://oauth2.googleapis.com/device/code` -- user enters code at `google.com/device`
- Poll `https://oauth2.googleapis.com/token` until authorized
- Receive access_token (1 hour) + refresh_token (long-lived)

**InnerTube API endpoints** (all POST to `https://music.youtube.com/youtubei/v1/{action}`):

| Action | browseId | Returns |
|--------|----------|---------|
| `/browse` | `FEmusic_home` | Personalized mixes |
| `/browse` | `FEmusic_liked_videos` | Liked songs |
| `/browse` | `FEmusic_library_privately_owned_playlists` | User playlists |
| `/browse` | `VL{playlistId}` | Specific playlist tracks |
| `/search` | query param | Search results |

**API stability:** InnerTube breaks 2-4 times/year (response structure changes). Mitigate with `ignoreUnknownKeys` in JSON parsing and monitoring ytmusicapi/InnerTune repos for changes.

### 3.3 Token Security

- **Access tokens:** In-memory cache only (never persisted)
- **Refresh tokens:** Encrypted via AES-256-GCM key in Android Keystore, stored in Jetpack DataStore
- **Proactive refresh:** Refresh tokens 5 minutes before expiry
- **Fallback:** EncryptedSharedPreferences for API 26-28 devices with Keystore compatibility issues

---

## 4. Download Engine

### 4.1 yt-dlp Integration

**Library:** `youtubedl-android` (JunkFood02 fork, used by Seal)

```kotlin
implementation("io.github.junkfood02.youtubedl-android:library:0.17.+")
implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.+")
```

This bundles Python 3.8 interpreter + yt-dlp + ffmpeg as native `.so` libraries. Requires `android:extractNativeLibs="true"` in manifest.

**APK size budget per ABI:**
| Component | Size |
|-----------|------|
| Python interpreter | ~8 MB |
| yt-dlp | ~12 MB |
| ffmpeg | ~18 MB |
| **Total** | **~38 MB** (per ABI, AAB delivers only user's architecture) |

**Self-update:** `YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.STABLE)` checks GitHub releases and replaces the local binary. Triggered on app launch (non-blocking), every 24 hours via WorkManager, and after download failures.

### 4.2 Track Matching Algorithm

For Spotify tracks (which don't have YouTube IDs), the app must find the correct YouTube video:

**Two-tier search:**

1. **Tier 1 (preferred):** `yt-dlp "ytsearch1:{artist} - {title}" --dump-json --no-download`
2. **Tier 2 (fallback):** `yt-dlp "ytsearch5:{artist} - {title} official audio" --dump-json --no-download` with scoring

**Scoring algorithm (Tier 2):**

```
finalScore = (titleScore * 0.35) + (artistScore * 0.25) + (durationScore * 0.25) + (popularityScore * 0.15)
```

- **Title similarity (0.35):** Levenshtein/Jaro-Winkler after normalization. Penalize "remix", "cover", "live", "karaoke" unless query contains them.
- **Artist match (0.25):** Check uploader/channel fields. Bonus for "- Topic" channels (official auto-generated).
- **Duration match (0.25):** <=3s diff = 1.0, <=10s = 0.8, <=30s = 0.4, >30s = 0.0
- **Popularity (0.15):** `log10(viewCount) / log10(maxViewCount)` -- favors official uploads

**Confidence thresholds:**
- >= 0.75: Auto-accept
- 0.50-0.74: Accept, flag for potential review
- < 0.50: Reject, retry with alternate query. If still below threshold, mark as "unmatched" for user resolution.

### 4.3 Download Pipeline

```
QUEUED -> MATCHING -> DOWNLOADING -> PROCESSING -> TAGGING -> COMPLETED
                                                           -> FAILED
                                                           -> UNMATCHED
```

- **Concurrency:** 3 simultaneous downloads (separate yt-dlp processes)
- **Priority:** Currently-playing track > current playlist > background sync queue
- **Progress:** `youtubedl-android` provides 0-100% callback per track, emitted to Room DB and UI via StateFlow

**yt-dlp command for highest quality:**
```
-f "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio"
-x --audio-format opus --audio-quality 0
```

**Metadata embedding:** After download, embed metadata from Spotify/YT Music (not YouTube) using ffmpeg (Opus is the primary format -- embed via Vorbis comment tags and attached pictures via ffmpeg CLI through youtubedl-android's FFmpeg module). Album art sourced from Spotify API at 640x640, resized to 500x500 JPEG.

### 4.4 Quality Tiers

All tiers use Opus format (best quality-per-byte for offline playback on Android 8+). The yt-dlp `--audio-quality` flag controls bitrate.

| Tier | Format | yt-dlp Flags | Bitrate | Size/min | Default |
|------|--------|-------------|---------|----------|---------|
| Best | OPUS | `-f bestaudio[ext=webm]` `-x` `--audio-format opus` `--audio-quality 0` | ~160 kbps | ~1.2 MB | Yes |
| High | OPUS | `-f bestaudio` `-x` `--audio-format opus` `--audio-quality 3` | ~128 kbps | ~0.96 MB | |
| Normal | OPUS | `-f bestaudio` `-x` `--audio-format opus` `--audio-quality 5` | ~96 kbps | ~0.72 MB | |
| Low | OPUS | `-f bestaudio` `-x` `--audio-format opus` `--audio-quality 8` | ~64 kbps | ~0.48 MB | |

Note: YouTube's actual highest quality audio is ~160 kbps Opus. "Best" is the ceiling YouTube provides, not true lossless.

### 4.5 Duplicate Detection

**Canonical identity:** Normalize title + artist (lowercase, strip parentheticals/brackets, remove "feat.", sort multi-artist names).

**Dedup check points:**
1. Before queueing: exact canonical match in DB
2. Cross-source import: link Spotify track to existing YouTube track (set source=BOTH)
3. Post-download: SHA-256 file hash comparison (belt-and-suspenders)
4. Fuzzy fallback: Jaro-Winkler similarity > 0.92 on title + > 0.90 on artist with duration confirmation

### 4.6 File Organization

```
/data/data/com.stash.app/files/music/
  {artistSlug}/
    {albumSlug}/
      {trackNumber}-{titleSlug}.opus
      cover.jpg
```

App-internal storage only. Not accessible to other apps. Survives app updates, cleared on uninstall.

---

## 5. Sync Scheduling

### 5.1 WorkManager Chain

**Approach:** OneTimeWorkRequest with self-rescheduling (not PeriodicWorkRequest, which can't guarantee precise timing or support chaining).

```
PlaylistFetchWorker -> DiffWorker -> TrackDownloadWorker -> SyncFinalizeWorker
      (5%)              (5%)              (70%)                  (5%)
                                     [foreground service]    [reschedules next sync]
```

**Constraints:**
- `NetworkType.UNMETERED` (WiFi only)
- `RequiresBatteryNotLow(true)`
- `RequiresStorageNotLow(true)`

**Missed schedule handling:** WorkManager persists requests in SQLite. When constraints are met (phone on + WiFi), work executes immediately. `SyncFinalizeWorker` always reschedules for the next occurrence of the user's target time, preventing drift.

**Data passing:** Between workers via Room database (not WorkData, which has 10KB limit). `PlaylistFetchWorker` writes to `remote_playlist_snapshot` table, `DiffWorker` reads it to produce `download_queue`, `TrackDownloadWorker` processes the queue.

### 5.2 Battery/Doze Handling

- WorkManager handles Doze mode automatically (defers to maintenance windows)
- `setExpedited()` on TrackDownloadWorker for OS priority
- On first launch, detect aggressive OEMs (Xiaomi, Huawei, Samsung) and show dontkillmyapp.com instructions
- TrackDownloadWorker promotes to foreground service with notification for long-running downloads

### 5.3 Sync Progress Reporting

| Phase | Weight | Range |
|-------|--------|-------|
| Authenticating | 5% | 0-5% |
| Fetching playlists | 15% | 5-20% |
| Diffing | 5% | 20-25% |
| Downloading | 70% | 25-95% |
| Finalizing | 5% | 95-100% |

Notification updates per-track (not per-byte) to avoid churn.

---

## 6. Database Schema (Room)

### 6.1 Entities

**TrackEntity:**
```
id (PK, auto), title, artist, album, duration_ms,
file_path, file_format, quality_kbps, file_size_bytes,
source (SPOTIFY/YOUTUBE/BOTH), spotify_uri (unique), youtube_id (unique),
album_art_url, album_art_path, date_added, last_played, play_count,
is_downloaded, canonical_title, canonical_artist, match_confidence
```

Indexes: spotify_uri, youtube_id, artist, album, date_added, last_played, play_count, (title, artist) composite.

**PlaylistEntity:**
```
id (PK, auto), name, source (SPOTIFY/YOUTUBE), source_id,
type (DAILY_MIX/LIKED_SONGS/CUSTOM), mix_number, last_synced,
track_count, is_active, art_url
```

**PlaylistTrackCrossRef:**
```
playlist_id (FK), track_id (FK), position, added_at, removed_at
```
Primary key: (playlist_id, track_id). Soft-delete via `removed_at` -- tracks removed from remote playlists stay on disk.

**SyncHistoryEntity:**
```
id (PK, auto), started_at, completed_at, status,
playlists_checked, new_tracks_found, tracks_downloaded,
tracks_failed, bytes_downloaded, error_message, trigger
```

**DownloadQueueEntity:**
```
id (PK, auto), track_id (FK), sync_id (FK), status,
search_query, youtube_url, retry_count, error_message,
created_at, completed_at
```

**TrackFts:** FTS4 content table on tracks(title, artist, album) for fast search.

### 6.2 Migration Strategy

- `exportSchema = true` always
- `AutoMigration` for additive changes
- Manual `Migration` for destructive changes
- Schema versions stored in `schemas/` directory in repo

---

## 7. Music Player

### 7.1 Media3 Setup

**StashPlaybackService** extends `MediaSessionService`:
- ExoPlayer with `AUDIO_CONTENT_TYPE_MUSIC`, `handleAudioFocus = true`, `handleAudioBecomingNoisy = true`
- `WAKE_MODE_LOCAL` to keep CPU awake during playback
- `foregroundServiceType="mediaPlayback"` in manifest
- Media notification managed automatically by Media3

### 7.2 PlayerRepository Interface

```kotlin
interface PlayerRepository {
    val playerState: StateFlow<PlayerState>
    val currentPosition: Flow<Long>  // emits every ~200ms

    suspend fun play()
    suspend fun pause()
    suspend fun skipToNext()
    suspend fun skipToPrevious()
    suspend fun seekTo(positionMs: Long)
    suspend fun setQueue(tracks: List<Track>, startIndex: Int)
    suspend fun addToQueue(track: Track)
    suspend fun toggleShuffle()
    suspend fun cycleRepeatMode()
}
```

### 7.3 Queue Management

- In-memory `QueueManager` with shuffled + original order tracking
- Shuffle preserves current track at position 0
- Repeat cycles: OFF -> ALL -> ONE -> OFF
- Current position saved to DataStore on pause/stop for resume
- Gapless playback native via ExoPlayer when tracks queued in sequence

---

## 8. UI Design

### 8.1 Design Language

| Token | Value |
|-------|-------|
| Base background | #06060C (near-black, slight blue undertone) |
| Surface | #0D0D18 |
| Elevated surface | #1A1A2E |
| Primary accent | #8B5CF6 (purple) |
| Secondary accent | #06B6D4 (cyan) |
| Spotify indicator | #1DB954 (green, subtle dot only) |
| YouTube indicator | #FF0033 (red, subtle dot only) |
| Glass background | rgba(255,255,255, 0.04-0.08) |
| Glass border | rgba(255,255,255, 0.06-0.14) |
| Glass blur | backdrop-filter: blur(20-40px) on API 31+ |
| Display font | Space Grotesk (Bold/SemiBold) |
| Body font | Inter (Regular/Medium/SemiBold) |
| Corner radius | 8dp (small), 12dp (medium), 16dp (large), 20dp (xl) |

### 8.2 Navigation

- Bottom nav: Home, Library, Sync, Settings
- Persistent mini player between content area and bottom nav
- Now Playing as full-screen overlay (not in bottom nav graph)
- Shared element animation: mini player expands to Now Playing

### 8.3 Screens

**Home Screen:**
- Sync status card with pulse animation
- Daily Mix carousel (horizontal scroll, large cards with gradient overlays)
- Recently added tracks (horizontal scroll)
- Liked Songs featured card
- Playlist grid (2-column)

**Library Screen:**
- Glassmorphic search bar with FTS4
- Filter tabs: Playlists / Tracks / Artists / Albums
- Sort options: recently added, alphabetical, most played
- Grid and list view toggle
- Source indicator dots on each item

**Now Playing Screen:**
- Ambient gradient background (3 radial gradients, 12s drift animation)
- Album art centered (280dp, rounded corners, glow effect, swipe L/R to skip via HorizontalPager)
- Dynamic color palette extracted from album art (Palette API)
- Custom gradient progress bar with glow at playhead
- Gesture: swipe down to dismiss (AnchoredDraggable)
- Lyrics preview card (glassmorphic)

**Sync Screen:**
- Connected sources with status
- Schedule configuration
- Manual sync button with progress
- Sync history log

**Settings Screen:**
- Audio quality selector (4 tiers)
- Storage usage visualization
- Account connections (connect/disconnect)
- Auto-sync and WiFi-only toggles

### 8.4 Premium UI Techniques

- **Glassmorphism:** API 31+: `RenderEffect.createBlurEffect()` via `graphicsLayer`. API 26-30: semi-transparent surfaces with noise texture (looks great on dark backgrounds even without blur).
- **Dynamic colors:** Palette API extracts DarkMuted, Vibrant, Muted swatches from album art (loaded at 128px for speed). Colors validated for WCAG AA contrast.
- **Animated gradients:** `InfiniteTransition` with `animateFloat` for ambient drift. `animateColorAsState` with 800ms tween for track-change crossfade.
- **Staggered animations:** 50ms delay per item, capped at first 8-10 visible items.
- **Custom fonts:** Bundled as static `res/font/` resources (~200KB total). No downloadable fonts.

---

## 9. Error Handling & Resilience

| Failure | Response |
|---------|----------|
| Spotify token refresh fails | Show "Re-authenticate Spotify" prompt; keep cached playlists playable |
| Daily Mixes not in user library | Show message: "Open your Daily Mix in Spotify once, then try again" |
| YouTube Music InnerTube schema change | Banner: "YouTube Music sync temporarily unavailable"; keep cached data |
| yt-dlp extractor broken | Trigger self-update, retry after update |
| Track can't be matched | Mark as "unmatched" in a visible list for user resolution |
| Network lost mid-download | Pause active downloads, resume on connectivity |
| YouTube rate limiting (429) | Exponential backoff: 30s, 60s, 120s, 300s. Reduce concurrency to 1 |
| Disk space low | Pause queue at <100MB free, notify user |
| Missed sync schedule | WorkManager executes when constraints met; reschedules for next target time |

---

## 10. Notifications

Three channels:

1. **Sync Progress** (IMPORTANCE_LOW) -- ongoing notification during download with per-track progress
2. **Sync Summary** (IMPORTANCE_DEFAULT) -- "Added 15 new tracks from 3 playlists"
3. **Media Playback** (IMPORTANCE_LOW) -- standard media controls, managed by Media3

---

## 11. Legal Considerations

- **Spotify:** OAuth via public API is compliant. Daily Mix access via playlist filtering is within API terms. No sp_dc cookie extraction.
- **YouTube Music:** InnerTube API usage exists in a gray area (same as NewPipe, InnerTune, OuterTune on F-Droid). No official API exists.
- **yt-dlp:** Downloading audio from YouTube violates YouTube ToS. yt-dlp itself is legal open-source software.
- **Distribution:** GitHub + F-Droid. Include clear disclaimer in README. Not suitable for Google Play Store due to ToS concerns.
- **Disclaimer:** "This software is for personal use. Users are responsible for ensuring their use complies with applicable laws and service terms."

---

## 12. Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| androidx.compose:compose-bom | 2026.03.00 | Compose version alignment |
| androidx.navigation:navigation-compose | 2.9.7 | Screen routing |
| androidx.media3:media3-exoplayer | 1.9.2 | Audio playback |
| androidx.media3:media3-session | 1.9.2 | MediaSession + notification |
| com.google.dagger:hilt-android | 2.56 | Dependency injection |
| androidx.room:room-runtime + room-ktx | 2.7.1 | Local database |
| androidx.datastore:datastore-preferences | 1.1.4 | Preferences |
| com.squareup.okhttp3:okhttp | 4.12.x | HTTP client |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.7.x | JSON parsing |
| io.coil-kt.coil3:coil-compose | 3.4.0 | Image loading |
| androidx.palette:palette-ktx | 1.0.0 | Color extraction |
| io.github.junkfood02.youtubedl-android:library | 0.17.+ | yt-dlp wrapper |
| io.github.junkfood02.youtubedl-android:ffmpeg | 0.17.+ | ffmpeg for audio processing |
| net.openid:appauth | 0.11.x | OAuth/PKCE flows |
| com.google.crypto.tink:tink-android | 1.12.x | Token encryption |
---

## 13. Android Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> <!-- API 33+, runtime -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" /> <!-- reschedule sync after reboot -->
```

---

## 14. Threading Model

- **`Dispatchers.IO`:** All network calls (Spotify API, InnerTube), Room database operations, file I/O, yt-dlp process execution
- **`Dispatchers.Default`:** Track matching scoring algorithm, canonical normalization, diffing computations
- **`Dispatchers.Main`:** Compose UI, StateFlow collection
- **yt-dlp concurrency:** A `Semaphore(3)` in the DownloadManager limits concurrent yt-dlp processes. Each process runs in its own `Dispatchers.IO` coroutine.
- **yt-dlp JSON parsing:** Use `ignoreUnknownKeys = true` in Kotlinx Serialization to handle yt-dlp output format changes gracefully.

---

## 15. Incremental Sync Strategy

- **Spotify:** Store the `snapshot_id` from each playlist. On sync, compare remote `snapshot_id` with stored value. If unchanged, skip fetching tracks entirely. For Liked Songs (no snapshot_id), use `offset` pagination and stop when encountering already-known track IDs.
- **YouTube Music:** No equivalent to snapshot_id. Always fetch full playlist. Compare track lists locally.
- **Diff behavior:** New tracks added to download queue. Tracks removed from remote playlists get `removed_at` timestamp on cross-ref (soft-delete). Files stay on disk.

---

## 16. Storage Management

- **Policy:** Keep all downloaded tracks forever (user manages their own storage)
- **Visibility:** Settings screen shows total storage used, breakdown by source (Spotify/YouTube), and per-playlist sizes
- **Manual cleanup:** Users can delete individual tracks, entire playlists, or "tracks not in any active playlist" from Settings
- **Album art cache:** Stored in app cache directory (`Context.cacheDir`). Android may reclaim this. Re-downloaded on next display if missing.
- **Future consideration:** Optional auto-cleanup of tracks unplayed for X days (not in v1)

---

## 17. Lyrics

Lyrics are a **v2 feature** and not in initial scope. The Now Playing screen reserves a glassmorphic card area for lyrics but it will show "Lyrics coming soon" in v1. Potential v2 sources: LRCLIB (open-source synced lyrics), Musixmatch API (requires API key).

---

## 18. Risks

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| Spotify blocks Daily Mix access for new apps | High | Medium | User playlist filtering approach; apply for extended access |
| YouTube InnerTube API breaks | Medium | High (2-4x/yr) | Lenient JSON parsing; monitor ytmusicapi/InnerTune repos |
| yt-dlp blocked by YouTube | Critical | Medium | Auto-update mechanism; yt-dlp team responds within days |
| Google rejects TV device OAuth flow | High | Low | Browser cookie auth as fallback |
| App removed from Play Store | Critical | Medium | Distribute via GitHub/F-Droid only |
| Aggressive OEM battery killers | Medium | High | dontkillmyapp.com guidance on first launch |
