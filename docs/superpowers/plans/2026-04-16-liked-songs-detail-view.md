# Liked Songs Detail View — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Home tab's Liked Songs card navigate to a detail view showing all liked tracks (combined or per-source), instead of only triggering immediate playback.

**Architecture:** Add a new `LikedSongsDetailRoute(source: String?)` navigation route that opens a new `LikedSongsDetailScreen` + `LikedSongsDetailViewModel`. The screen follows the exact same pattern as the existing `PlaylistDetailScreen` (header + track list + bottom sheet actions). The ViewModel queries all playlists of type `LIKED_SONGS`, optionally filtered by source, merges their tracks, and deduplicates. The existing per-source chips on the Home card navigate to `PlaylistDetailRoute(playlistId)` directly (already works for single-playlist cases).

**Tech Stack:** Jetpack Compose, Hilt, Room, Compose Navigation (typed routes), Media3 PlayerRepository

**Key design decisions:**
- New screen rather than hacking `PlaylistDetailScreen` — because the combined case (all liked songs from multiple playlists) is genuinely new behavior that doesn't map to a single `playlistId`.
- Per-source chips reuse existing `PlaylistDetailRoute(playlistId)` — zero new code for single-source detail views.
- DAO gets one new query (`getByType`) to fetch playlists by `PlaylistType` — clean, no raw SQL in the ViewModel.

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `core/data/.../db/dao/PlaylistDao.kt` | Modify (after `getBySource`) | Add `getByType()` query |
| `core/data/.../repository/MusicRepository.kt` | Modify (after `getPlaylistWithTracks`) | Add `getPlaylistsByType()` interface method |
| `core/data/.../repository/MusicRepositoryImpl.kt` | Modify (after `getAllPlaylists`) | Implement `getPlaylistsByType()` |
| `core/ui/.../components/DetailTrackRow.kt` | **Create** | Shared track row composable (extracted from PlaylistDetailScreen) |
| `core/ui/.../components/TrackOptionsSheet.kt` | **Create** | Shared bottom sheet composable (extracted from PlaylistDetailScreen) |
| `core/ui/.../util/DurationFormat.kt` | **Create** | Shared duration formatting helpers |
| `feature/library/.../PlaylistDetailScreen.kt` | Modify | Replace private composables with `core:ui` imports |
| `feature/library/.../LikedSongsDetailViewModel.kt` | **Create** | Query liked playlists, merge tracks, expose UI state |
| `feature/library/.../LikedSongsDetailScreen.kt` | **Create** | Heart-icon header + track list (uses shared `core:ui` components) |
| `app/.../navigation/TopLevelDestination.kt` | Modify (after `AlbumDetailRoute`) | Add `LikedSongsDetailRoute` |
| `app/.../navigation/StashNavHost.kt` | Modify | Wire new route + add `onNavigateToLikedSongs` callback |
| `feature/home/.../HomeScreen.kt` | Modify | Add navigation callbacks to `LikedSongsCard` |
| `feature/home/.../HomeUiState.kt` | No change | Already exposes playlist IDs |

---

### Task 1: Add DAO query for playlists by type

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` (after line 86)

- [ ] **Step 1: Add `getByType` query to PlaylistDao**

Add after line 86 (after the `getBySource` method):

```kotlin
/** All active playlists of a specific type, ordered alphabetically. */
@Query("SELECT * FROM playlists WHERE type = :type AND is_active = 1 ORDER BY name ASC")
fun getByType(type: PlaylistType): Flow<List<PlaylistEntity>>
```

Note: Room's registered `PlaylistType` ↔ `String` TypeConverter (in `Converters.kt`) handles the enum-to-column translation automatically. This matches the existing pattern used by `getBySource(source: MusicSource)` and other DAO methods that accept enums directly. The `PlaylistType` import is already present in the file.

- [ ] **Step 2: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt
git commit -m "feat: add PlaylistDao.getByType() query for type-filtered playlist lookups"
```

---

### Task 2: Add repository method for playlists by type

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt` (after line 63)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` (after line 130)

- [ ] **Step 1: Add interface method to MusicRepository**

Add after line 63 (after `getPlaylistWithTracks`):

```kotlin
/** All active playlists of a given type (e.g. LIKED_SONGS). */
fun getPlaylistsByType(type: com.stash.core.model.PlaylistType): Flow<List<Playlist>>
```

- [ ] **Step 2: Implement in MusicRepositoryImpl**

Add after line 130 (after the `getAllPlaylists` implementation):

```kotlin
override fun getPlaylistsByType(type: com.stash.core.model.PlaylistType): Flow<List<Playlist>> =
    playlistDao.getByType(type).map { entities -> entities.map { it.toDomain() } }
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt
git commit -m "feat: add MusicRepository.getPlaylistsByType() for type-filtered playlist queries"
```

---

### Task 3: Create LikedSongsDetailViewModel

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailViewModel.kt`

- [ ] **Step 1: Create the ViewModel**

This ViewModel follows the exact same pattern as `PlaylistDetailViewModel` (same file, 179 lines). Key differences:
- Instead of a single `playlistId`, it receives an optional `source: String?` from navigation args
- Queries `MusicRepository.getPlaylistsByType(PlaylistType.LIKED_SONGS)` reactively
- Filters by source if provided
- Merges tracks from all matching playlists, deduplicates by track ID

```kotlin
package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Liked Songs detail screen.
 *
 * @property title          Display title (e.g. "Liked Songs", "Liked Songs — Spotify").
 * @property tracks         Merged, deduplicated track list from all matching liked playlists.
 * @property isLoading      True while the initial data load is in progress.
 * @property currentlyPlayingTrackId The ID of the currently-playing track for row highlighting.
 * @property source         The filtered source, or null for combined view.
 */
data class LikedSongsDetailUiState(
    val title: String = "Liked Songs",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
    val source: MusicSource? = null,
)

/**
 * ViewModel for the Liked Songs detail screen.
 *
 * Loads all playlists of type [PlaylistType.LIKED_SONGS], optionally filtered
 * by [MusicSource], then merges their tracks into a single deduplicated list.
 *
 * The optional `source` parameter is extracted from the navigation
 * [SavedStateHandle] — null means "show all liked songs from every source".
 */
@HiltViewModel
class LikedSongsDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val sourceFilter: MusicSource? =
        savedStateHandle.get<String>("source")?.let { MusicSource.valueOf(it) }

    /**
     * Reactive flow of liked playlists, filtered by source if specified.
     *
     * Uses `flatMapLatest` + `combine` to maintain full reactivity: when
     * the playlist list changes OR any playlist's tracks change, the
     * combined list re-emits. Since there are at most 2 liked playlists
     * (one Spotify, one YouTube), the combine is lightweight.
     */
    private val tracksFlow = musicRepository.getPlaylistsByType(PlaylistType.LIKED_SONGS)
        .map { playlists ->
            if (sourceFilter != null) playlists.filter { it.source == sourceFilter }
            else playlists
        }
        .flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(playlists.map { musicRepository.getTracksByPlaylist(it.id) }) { arrays ->
                    arrays.flatMap { it.toList() }.distinctBy { it.id }
                }
            }
        }

    val uiState: StateFlow<LikedSongsDetailUiState> = combine(
        tracksFlow,
        playerRepository.playerState,
    ) { tracks, playerState ->
        val title = when (sourceFilter) {
            MusicSource.SPOTIFY -> "Liked Songs \u2022 Spotify"
            MusicSource.YOUTUBE -> "Liked Songs \u2022 YouTube"
            else -> "Liked Songs"
        }
        LikedSongsDetailUiState(
            title = title,
            tracks = tracks,
            isLoading = false,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
            source = sourceFilter,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LikedSongsDetailUiState(),
    )

    // ── Playback actions (identical to PlaylistDetailViewModel) ─────────

    fun playTrack(trackId: Long) {
        viewModelScope.launch {
            val downloaded = uiState.value.tracks.filter { it.filePath != null }
            if (downloaded.isEmpty()) return@launch
            val index = downloaded.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
            playerRepository.setQueue(downloaded, index)
        }
    }

    fun shuffleAll() {
        viewModelScope.launch {
            val downloaded = uiState.value.tracks.filter { it.filePath != null }
            if (downloaded.isEmpty()) return@launch
            playerRepository.setQueue(downloaded, downloaded.indices.random())
        }
    }

    fun playNext(track: Track) {
        viewModelScope.launch { playerRepository.addNext(track) }
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch { playerRepository.addToQueue(track) }
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch { musicRepository.deleteTrack(track) }
    }

    val userPlaylists = musicRepository.getUserCreatedPlaylists()

    fun saveTrackToPlaylist(trackId: Long, playlistId: Long) {
        viewModelScope.launch { musicRepository.addTrackToPlaylist(trackId, playlistId) }
    }

    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        viewModelScope.launch {
            val id = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, id)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailViewModel.kt
git commit -m "feat: add LikedSongsDetailViewModel — merges tracks from liked playlists with source filtering"
```

---

### Task 4a: Extract shared composables to core:ui

**Why:** `PlaylistDetailScreen.kt` contains ~280 lines of track row, bottom sheet, and helper composables that `LikedSongsDetailScreen` also needs. Rather than duplicating, extract them into `core:ui` so both screens import from a shared location. This eliminates maintenance burden for future visual changes.

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/util/DurationFormat.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt`

- [ ] **Step 1: Extract `DetailTrackRow` to `core:ui`**

Move the `PlaylistTrackRow` composable (PlaylistDetailScreen.kt lines 397-493) to `core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt`. Rename to `DetailTrackRow` and make it `public` (remove `private`). Keep the same parameters: `track`, `trackNumber`, `isPlaying`, `onClick`, `onLongPress`.

- [ ] **Step 2: Extract `TrackOptionsSheet` + `SheetOptionRow` to `core:ui`**

Move `TrackOptionsSheet` (lines 504-609) and `SheetOptionRow` (lines 618-645) from `PlaylistDetailScreen.kt` to `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt`. Make both `public`.

- [ ] **Step 3: Extract duration formatters to `core:ui`**

Move `formatDuration` (lines 655-665) and `formatTotalDuration` (lines 673-682) to `core/ui/src/main/kotlin/com/stash/core/ui/util/DurationFormat.kt`. Make both `internal` (visible within `core:ui` module and its consumers).

- [ ] **Step 4: Update `PlaylistDetailScreen.kt` to import shared composables**

Replace the private `PlaylistTrackRow`, `TrackOptionsSheet`, `SheetOptionRow`, `formatDuration`, and `formatTotalDuration` with imports from `core:ui`:
- `import com.stash.core.ui.components.DetailTrackRow`
- `import com.stash.core.ui.components.TrackOptionsSheet`
- `import com.stash.core.ui.components.SheetOptionRow`
- `import com.stash.core.ui.util.formatDuration`
- `import com.stash.core.ui.util.formatTotalDuration`

Update usage: `PlaylistTrackRow(...)` → `DetailTrackRow(...)`. Everything else stays the same.

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt core/ui/src/main/kotlin/com/stash/core/ui/util/DurationFormat.kt feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt
git commit -m "refactor: extract shared track row, options sheet, and duration formatters to core:ui"
```

---

### Task 4b: Create LikedSongsDetailScreen

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt`

- [ ] **Step 1: Create the screen composable**

This screen follows the same structure as `PlaylistDetailScreen.kt` but is significantly smaller (~250 lines) because the shared composables now live in `core:ui`. The only unique parts are:
1. **Header:** Heart-icon gradient box (matching the Home card aesthetic) instead of playlist artwork
2. **Title:** Dynamic based on source filter ("Liked Songs", "Liked Songs - Spotify", etc.)
3. **ViewModel:** `LikedSongsDetailViewModel` instead of `PlaylistDetailViewModel`
4. **Empty state:** When zero tracks exist, show a centered message instead of a blank screen

The screen imports from `core:ui`:
- `com.stash.core.ui.components.DetailTrackRow`
- `com.stash.core.ui.components.TrackOptionsSheet`
- `com.stash.core.ui.util.formatTotalDuration`

The screen composable structure:

```kotlin
package com.stash.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.Track
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatTotalDuration

/**
 * Liked Songs detail screen entry point.
 *
 * Displays a heart-icon header followed by a scrollable track list of all
 * liked songs, optionally filtered by source. Tapping a track starts playback;
 * long-pressing opens a bottom sheet with queue actions.
 *
 * @param onBack   Callback invoked when the back arrow is tapped.
 * @param viewModel Injected via Hilt; extracts optional `source` from nav args.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LikedSongsDetailScreen(
    onBack: () -> Unit,
    viewModel: LikedSongsDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val extendedColors = StashTheme.extendedColors

    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var trackToSave by remember { mutableStateOf<Track?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (state.tracks.isEmpty()) {
            // Empty state — no liked songs synced yet
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "No liked songs yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Sync your library to get started",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                item(key = "header") {
                    LikedSongsHeader(
                        state = state,
                        onBack = onBack,
                        onPlayAll = {
                            val firstTrack = state.tracks.firstOrNull { it.filePath != null }
                            if (firstTrack != null) viewModel.playTrack(firstTrack.id)
                        },
                        onShuffle = { viewModel.shuffleAll() },
                    )
                }

                itemsIndexed(
                    items = state.tracks,
                    key = { _, track -> track.id },
                ) { index, track ->
                    DetailTrackRow(
                        track = track,
                        trackNumber = index + 1,
                        isPlaying = track.id == state.currentlyPlayingTrackId,
                        onClick = { viewModel.playTrack(track.id) },
                        onLongPress = { selectedTrack = track },
                    )

                    if (index < state.tracks.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 80.dp, end = 20.dp),
                            thickness = 0.5.dp,
                            color = extendedColors.glassBorder,
                        )
                    }
                }
            }
        }
    }

    // Track options bottom sheet — uses shared TrackOptionsSheet from core:ui
    if (selectedTrack != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
            containerColor = extendedColors.elevatedSurface,
        ) {
            TrackOptionsSheet(
                track = selectedTrack!!,
                onPlayNext = { viewModel.playNext(it); selectedTrack = null },
                onAddToQueue = { viewModel.addToQueue(it); selectedTrack = null },
                onSaveToPlaylist = { trackToSave = it; selectedTrack = null },
                onDelete = { viewModel.deleteTrack(it); selectedTrack = null },
            )
        }
    }

    // Save to Playlist sheet
    if (trackToSave != null) {
        com.stash.core.ui.components.SaveToPlaylistSheet(
            playlists = userPlaylists.map {
                com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount)
            },
            onSaveToPlaylist = { playlistId ->
                viewModel.saveTrackToPlaylist(trackToSave!!.id, playlistId)
                trackToSave = null
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name, trackToSave!!.id)
                trackToSave = null
            },
            onDismiss = { trackToSave = null },
        )
    }
}
```

**Header composable** — the key visual difference. Instead of `PlaylistHeader` which shows full-width album art, this uses a compact header with a gradient heart icon box matching the Home card's style:

```kotlin
@Composable
private fun LikedSongsHeader(
    state: LikedSongsDetailUiState,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    Column(modifier = Modifier.fillMaxWidth()) {
        // -- Gradient header area with heart icon --
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)  // Wider than tall (not square like playlist art)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            extendedColors.purpleDark.copy(alpha = 0.4f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Large heart icon
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )

            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 8.dp)
                    .align(Alignment.TopStart)
                    .size(48.dp)
                    .background(
                        color = extendedColors.glassBackground,
                        shape = CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // -- Title + track count + action buttons --
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Track count + total duration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val trackCount = state.tracks.size
                Text(
                    text = "$trackCount track${if (trackCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val totalDuration = state.tracks.sumOf { it.durationMs }
                if (totalDuration > 0) {
                    Text(
                        text = "\u2022",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatTotalDuration(totalDuration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Play All + Shuffle buttons (identical to PlaylistDetailScreen)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPlayAll,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play All", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onShuffle,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
```

The track row, bottom sheets, and helper functions are imported from `core:ui` (extracted in Task 4a). No duplication needed — the screen file is self-contained with the header + screen body above.

- [ ] **Step 2: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt
git commit -m "feat: add LikedSongsDetailScreen — heart-icon header with merged track list"
```

---

### Task 5: Add navigation route and wire it

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt` (after line 32)
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt` (after line 79, and line 10-18 imports)

- [ ] **Step 1: Add the route definition**

In `TopLevelDestination.kt`, add after line 32:

```kotlin
@Serializable data class LikedSongsDetailRoute(val source: String? = null)
```

- [ ] **Step 2: Wire route in NavHost**

In `StashNavHost.kt`, add the import at the top (after line 14):

```kotlin
import com.stash.feature.library.LikedSongsDetailScreen
```

Add the composable route after line 79 (after the `AlbumDetailRoute` block):

```kotlin
composable<LikedSongsDetailRoute> {
    LikedSongsDetailScreen(
        onBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 3: Update HomeScreen callback**

In `StashNavHost.kt`, update the `HomeScreen` block (lines 39-45) to pass a second navigation callback:

```kotlin
composable<HomeRoute> {
    HomeScreen(
        onNavigateToPlaylist = { playlistId ->
            navController.navigate(PlaylistDetailRoute(playlistId))
        },
        onNavigateToLikedSongs = { source ->
            navController.navigate(LikedSongsDetailRoute(source))
        },
    )
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat: add LikedSongsDetailRoute and wire navigation in StashNavHost"
```

---

### Task 6: Wire LikedSongsCard navigation in HomeScreen

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`

This is the final wiring that connects the card taps to the new navigation.

- [ ] **Step 1: Add `onNavigateToLikedSongs` parameter to HomeScreen**

At line 92, add a new parameter after `onNavigateToPlaylist`:

```kotlin
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToPlaylist: (Long) -> Unit = {},
    onNavigateToLikedSongs: (String?) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
```

- [ ] **Step 2: Add navigation callbacks to LikedSongsCard parameters**

Update the `LikedSongsCard` function signature (lines 900-909) to add navigation callbacks:

```kotlin
@Composable
private fun LikedSongsCard(
    totalCount: Int,
    spotifyCount: Int,
    youtubeCount: Int,
    showSourceChips: Boolean,
    singleSource: MusicSource?,
    onPlayAll: () -> Unit,
    onPlaySpotify: () -> Unit,
    onPlayYouTube: () -> Unit,
    onClick: () -> Unit,
    onClickSpotify: () -> Unit,
    onClickYouTube: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 3: Update LikedSongsCard body to navigate on tap, play on button**

Replace the main Row's `.clickable(onClick = onPlayAll)` at line 935 with `.clickable(onClick = onClick)` so tapping the card navigates instead of playing.

The source chips at lines 997-1010: change `onClick = onPlaySpotify` to `onClick = onClickSpotify` and `onClick = onPlayYouTube` to `onClick = onClickYouTube`.

- [ ] **Step 4: Update the LikedSongsCard call site**

Update lines 232-242 to pass the new callbacks:

```kotlin
LikedSongsCard(
    totalCount = uiState.totalLikedCount,
    spotifyCount = uiState.spotifyLikedCount,
    youtubeCount = uiState.youtubeLikedCount,
    showSourceChips = uiState.hasBothLikedSources,
    singleSource = uiState.singleLikedSource,
    onPlayAll = { viewModel.playLikedSongs(source = null) },
    onPlaySpotify = { viewModel.playLikedSongs(source = MusicSource.SPOTIFY) },
    onPlayYouTube = { viewModel.playLikedSongs(source = MusicSource.YOUTUBE) },
    onClick = { onNavigateToLikedSongs(null) },
    onClickSpotify = {
        val spotifyPlaylistId = uiState.spotifyLikedPlaylists.firstOrNull()?.id
        if (spotifyPlaylistId != null) onNavigateToPlaylist(spotifyPlaylistId)
    },
    onClickYouTube = {
        val youtubePlaylistId = uiState.youtubeLikedPlaylists.firstOrNull()?.id
        if (youtubePlaylistId != null) onNavigateToPlaylist(youtubePlaylistId)
    },
    modifier = Modifier.padding(horizontal = 16.dp),
)
```

**Design note:** Per-source chips navigate to `PlaylistDetailRoute(playlistId)` which reuses the existing fully-functional playlist detail screen. Only the combined "all liked songs" view uses the new `LikedSongsDetailRoute`.

- [ ] **Step 5: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
git commit -m "feat: wire LikedSongsCard navigation — card tap opens detail view, chips open per-source view"
```

---

### Task 7: Build and verify

- [ ] **Step 1: Build the project**

```bash
cd /c/Users/theno/Projects/MP3APK && ./gradlew assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation errors**

Common issues to watch for:
- Missing imports in `LikedSongsDetailScreen.kt` (copy import block from `PlaylistDetailScreen.kt`)
- `flatMapLatest` requires `import kotlinx.coroutines.flow.flatMapLatest`
- `first` requires `import kotlinx.coroutines.flow.first`
- Hilt may need the ViewModel to be in a module that depends on `:core:data` and `:core:media`

- [ ] **Step 3: Final commit**

```bash
git add -A && git commit -m "fix: resolve compilation issues for LikedSongsDetail feature"
```

Only create this commit if fixes were needed. If the build succeeds on first try, skip this step.
