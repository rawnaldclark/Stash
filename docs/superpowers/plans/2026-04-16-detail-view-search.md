# In-List Search for Detail Views — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a search/filter feature to all 4 detail views so users can find specific songs within any playlist, mix, or collection.

**Architecture:** A shared `withSearchFilter` Flow extension handles debounced filtering in ViewModels. A shared `SearchFilterBar` composable provides the UI. Each ViewModel gets `_searchQuery` and `_showSearch` state flows. Each screen's header gets a search icon that toggles the filter bar. Artist and Album screens are migrated to shared `core:ui` components first.

**Tech Stack:** Jetpack Compose, Kotlin Flow (combine, debounce, flatMapLatest), Hilt ViewModels, Room

**Spec:** `docs/superpowers/specs/2026-04-16-detail-view-search-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `core/ui/.../util/SearchFilter.kt` | **Create** | `Flow<List<Track>>.withSearchFilter()` extension |
| `core/ui/.../components/SearchFilterBar.kt` | **Create** | Shared search text field composable |
| `core/ui/.../components/DetailTrackRow.kt` | Modify | Add `showArtist` and `subtitleOverride` params |
| `feature/library/.../ArtistDetailScreen.kt` | Modify | Migrate to shared core:ui components, add search |
| `feature/library/.../ArtistDetailViewModel.kt` | Modify | Add search query state + filter |
| `feature/library/.../AlbumDetailScreen.kt` | Modify | Migrate to shared core:ui components, add search |
| `feature/library/.../AlbumDetailViewModel.kt` | Modify | Add search query state + filter |
| `feature/library/.../PlaylistDetailViewModel.kt` | Modify | Add search query state + filter |
| `feature/library/.../PlaylistDetailScreen.kt` | Modify | Add search icon + SearchFilterBar |
| `feature/library/.../LikedSongsDetailViewModel.kt` | Modify | Add search query state + filter |
| `feature/library/.../LikedSongsDetailScreen.kt` | Modify | Add search icon + SearchFilterBar |

---

### Task 1: Create shared search filter utility

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/util/SearchFilter.kt`

- [ ] **Step 1: Create SearchFilter.kt**

```kotlin
package com.stash.core.ui.util

import com.stash.core.model.Track
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Filters a track list flow using a debounced search query.
 *
 * Matches against [Track.title] and [Track.artist] using case-insensitive
 * contains. An empty query passes the full list through unchanged and
 * is **not** subject to the 300ms debounce — this avoids a visible loading
 * flash when the screen first opens.
 *
 * The raw [queryFlow] value should be used directly for UI display
 * (text field binding). This function handles debouncing internally
 * for filtering only.
 *
 * Uses 300ms debounce (not 500ms like SearchScreen) because this is
 * client-side string matching, not a network call.
 */
@OptIn(FlowPreview::class)
fun Flow<List<Track>>.withSearchFilter(
    queryFlow: StateFlow<String>,
): Flow<List<Track>> {
    val tracksFlow = this
    return queryFlow
        .debounce { query -> if (query.isEmpty()) 0L else 300L }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank()) {
                tracksFlow
            } else {
                tracksFlow.map { tracks ->
                    val lowerQuery = query.lowercase()
                    tracks.filter { track ->
                        track.title.lowercase().contains(lowerQuery) ||
                            track.artist.lowercase().contains(lowerQuery)
                    }
                }
            }
        }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/util/SearchFilter.kt
git commit -m "feat: add withSearchFilter Flow extension for debounced track filtering"
```

---

### Task 2: Create shared SearchFilterBar composable

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/SearchFilterBar.kt`

- [ ] **Step 1: Create SearchFilterBar.kt**

```kotlin
package com.stash.core.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A search/filter text field for detail screens.
 *
 * Styled with the app's glass/dark theme. Shows a leading search icon
 * and a trailing clear button when [query] is non-empty. Auto-focuses
 * when first composed.
 *
 * @param query          Current search text (bound to ViewModel state).
 * @param onQueryChanged Called on every keystroke with the new text.
 * @param onClear        Called when the clear (X) button is tapped.
 */
@Composable
fun SearchFilterBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        placeholder = {
            Text(
                text = "Filter tracks...",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() },
        ),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = extendedColors.glassBackground,
            unfocusedContainerColor = extendedColors.glassBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/SearchFilterBar.kt
git commit -m "feat: add SearchFilterBar composable for in-list track filtering"
```

---

### Task 3: Enhance DetailTrackRow with optional subtitle params

**Files:**
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt`

- [ ] **Step 1: Add `showArtist` and `subtitleOverride` parameters**

Add two new optional parameters to the `DetailTrackRow` function signature:

```kotlin
@Composable
fun DetailTrackRow(
    track: Track,
    trackNumber: Int,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    showArtist: Boolean = true,
    subtitleOverride: String? = null,
)
```

Then update the subtitle `Text` composable inside the function body. Find the `Text` that displays `track.artist` and wrap it:

```kotlin
// Determine subtitle text — default is artist, can be overridden or hidden
val subtitle = subtitleOverride ?: track.artist
if ((showArtist || subtitleOverride != null) && subtitle.isNotBlank()) {
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
```

Logic:
- Default (`showArtist=true, subtitleOverride=null`): shows `track.artist` (unchanged behavior)
- `subtitleOverride="Some Album"`: shows the override string (guards blank strings — no empty Text)
- `showArtist=false, subtitleOverride=null`: hides the subtitle entirely (for AlbumDetailScreen)
- `subtitleOverride=""`: treated as blank, subtitle hidden (matches existing `ArtistDetailTrackRow` behavior)

- [ ] **Step 2: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt
git commit -m "feat: add showArtist and subtitleOverride params to DetailTrackRow"
```

---

### Task 4: Migrate ArtistDetailScreen AND AlbumDetailScreen to shared core:ui components

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt`

**IMPORTANT:** These two files MUST be migrated in the same commit because `ArtistDetailScreen.kt` contains `internal` helper functions (`formatDurationMs`, `formatTotalDurationMs`) that `AlbumDetailScreen.kt` also uses. Deleting them from Artist without simultaneously migrating Album will produce a broken build.

- [ ] **Step 1: Read both ArtistDetailScreen.kt and AlbumDetailScreen.kt completely**

Understand both files' structure. Note that `ArtistDetailScreen.kt` defines `internal fun formatDurationMs()` and `internal fun formatTotalDurationMs()` which are also called from `AlbumDetailScreen.kt`.

- [ ] **Step 2: Migrate ArtistDetailScreen**

Add imports:
```kotlin
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.util.formatTotalDuration
```

Replace usages:
- `ArtistDetailTrackRow(track, ...)` → `DetailTrackRow(track, ..., subtitleOverride = track.album)`
- `ArtistDetailTrackOptionsSheet(track, ...)` → `TrackOptionsSheet(track, ...)`
- `formatTotalDurationMs(...)` → `formatTotalDuration(...)`

Delete the private/internal composables and helpers:
- `ArtistDetailTrackRow` (~lines 336-433)
- `ArtistDetailTrackOptionsSheet` (~lines 441-539)
- `DetailSheetOptionRow` (~lines 546-573)
- `formatDurationMs` (~lines 580-590) — `internal`, used by both screens
- `formatTotalDurationMs` (~lines 595-604) — `internal`, used by both screens

- [ ] **Step 3: Migrate AlbumDetailScreen (in the same commit)**

Add imports:
```kotlin
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.util.formatTotalDuration
```

Replace usages:
- `AlbumDetailTrackRow(track, ...)` → `DetailTrackRow(track, ..., showArtist = false)`
- `AlbumDetailTrackOptionsSheet(track, ...)` → `TrackOptionsSheet(track, ...)`
- `formatDurationMs(...)` → `formatDuration(...)` (import from `com.stash.core.ui.util.formatDuration`)
- `formatTotalDurationMs(...)` → `formatTotalDuration(...)`

Delete the private composables:
- `AlbumDetailTrackRow` (~lines 391-474)
- `AlbumDetailTrackOptionsSheet` (~lines 481-580)
- Any remaining private helper functions

- [ ] **Step 4: Commit both files together**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt
git commit -m "refactor: migrate Artist and Album detail screens to shared core:ui components"
```

---

### Task 5: Add search to PlaylistDetailViewModel + PlaylistDetailScreen

This is the template task — Tasks 6, 7, and 8 follow the same pattern.

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt`

- [ ] **Step 1: Update PlaylistDetailUiState**

Add two fields to the data class:

```kotlin
data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
    val searchQuery: String = "",
    val showSearch: Boolean = false,
)
```

- [ ] **Step 2: Add search state and methods to PlaylistDetailViewModel**

Add imports:
```kotlin
import com.stash.core.ui.util.withSearchFilter
import kotlinx.coroutines.FlowPreview
```

Add `@OptIn(FlowPreview::class)` annotation to the class (required because `withSearchFilter` uses `debounce`).

Add after the `playlistId` declaration:

```kotlin
private val _searchQuery = MutableStateFlow("")
private val _showSearch = MutableStateFlow(false)

fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
fun clearSearch() { _searchQuery.value = "" }
fun toggleSearch() {
    _showSearch.value = !_showSearch.value
    if (!_showSearch.value) _searchQuery.value = ""
}
```

Update the `uiState` combine to pipe tracks through the filter and include search state:

```kotlin
val uiState: StateFlow<PlaylistDetailUiState> = combine(
    _playlist,
    musicRepository.getTracksByPlaylist(playlistId).withSearchFilter(_searchQuery),
    playerRepository.playerState,
    _searchQuery,
    _showSearch,
) { playlist, tracks, playerState, query, showSearch ->
    PlaylistDetailUiState(
        playlist = playlist,
        tracks = tracks,
        isLoading = playlist == null,
        currentlyPlayingTrackId = playerState.currentTrack?.id,
        searchQuery = query,
        showSearch = showSearch,
    )
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = PlaylistDetailUiState(),
)
```

- [ ] **Step 3: Update PlaylistDetailScreen**

Add imports:
```kotlin
import com.stash.core.ui.components.SearchFilterBar
import androidx.compose.material.icons.filled.Search
```

**3a.** Add `onToggleSearch: () -> Unit` parameter to `PlaylistHeader` function signature.

**3b.** Add a search icon button to `PlaylistHeader`'s button Row (after the Shuffle button):

```kotlin
IconButton(
    onClick = onToggleSearch,
    modifier = Modifier
        .size(48.dp)
        .background(
            color = extendedColors.glassBackground,
            shape = RoundedCornerShape(12.dp),
        ),
) {
    Icon(
        imageVector = Icons.Default.Search,
        contentDescription = "Filter tracks",
        tint = MaterialTheme.colorScheme.onSurface,
    )
}
```

**3c.** Update the `PlaylistHeader` call site to pass the new callback:

```kotlin
PlaylistHeader(
    state = state,
    onBack = onBack,
    onPlayAll = { ... },
    onShuffle = { viewModel.shuffleAll() },
    onToggleSearch = { viewModel.toggleSearch() },
)
```

**3d.** In the LazyColumn, add a search bar item between header and tracks:

```kotlin
if (state.showSearch) {
    item(key = "search") {
        SearchFilterBar(
            query = state.searchQuery,
            onQueryChanged = viewModel::onSearchQueryChanged,
            onClear = viewModel::clearSearch,
        )
    }
}
```

**3e.** Add empty search results state — when tracks is empty and searchQuery is non-empty, show "No matching songs" centered message instead of the track list. Three-branch logic:
- `isLoading` → loading spinner
- `tracks.isEmpty() && searchQuery.isNotEmpty()` → "No matching songs"
- `tracks.isEmpty() && searchQuery.isEmpty()` → no special handling (playlist just has no tracks)
- otherwise → track list content

- [ ] **Step 4: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt
git commit -m "feat: add search/filter to PlaylistDetailScreen"
```

---

### Task 6: Add search to LikedSongsDetailViewModel + LikedSongsDetailScreen

Same pattern as Task 5 but adapted for LikedSongsDetail.

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt`

- [ ] **Step 1: Update LikedSongsDetailUiState** — add `searchQuery: String = ""` and `showSearch: Boolean = false` fields
- [ ] **Step 2: Add search state to LikedSongsDetailViewModel** — add `@OptIn(FlowPreview::class)`, `import com.stash.core.ui.util.withSearchFilter`, add `_searchQuery`, `_showSearch`, `onSearchQueryChanged`, `clearSearch`, `toggleSearch` methods. Pipe `tracksFlow` through `.withSearchFilter(_searchQuery)`. Add `_searchQuery` and `_showSearch` to the `combine` that builds `uiState`.
- [ ] **Step 3: Update LikedSongsDetailScreen** — add search icon to `LikedSongsHeader` (add `onToggleSearch: () -> Unit` param), update call site to pass `onToggleSearch = { viewModel.toggleSearch() }`, add `SearchFilterBar` item in LazyColumn.

**IMPORTANT — 3-branch empty state:** LikedSongsDetailScreen already has a 3-branch state (loading / empty / content). The empty branch must be split:
- `isLoading` → loading spinner
- `tracks.isEmpty() && searchQuery.isEmpty()` → existing `LikedSongsEmptyState` ("No liked songs yet")
- `tracks.isEmpty() && searchQuery.isNotEmpty()` → "No matching songs"
- otherwise → content

- [ ] **Step 4: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt
git commit -m "feat: add search/filter to LikedSongsDetailScreen"
```

---

### Task 7: Add search to ArtistDetailViewModel + ArtistDetailScreen

Same pattern as Task 5 but adapted for ArtistDetail.

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt`

- [ ] **Step 1: Update ArtistDetailUiState** — add `searchQuery: String = ""` and `showSearch: Boolean = false` fields
- [ ] **Step 2: Add search state to ArtistDetailViewModel** — add `@OptIn(FlowPreview::class)`, `import com.stash.core.ui.util.withSearchFilter`, add `_searchQuery`, `_showSearch`, methods. Pipe `musicRepository.getTracksByArtist(artistName)` through `.withSearchFilter(_searchQuery)`. Add `_searchQuery` and `_showSearch` to the `combine`.
- [ ] **Step 3: Update ArtistDetailScreen** — add search icon to `ArtistDetailHeader` (add `onToggleSearch: () -> Unit` param), update call site to pass `onToggleSearch = { viewModel.toggleSearch() }`, add `SearchFilterBar` item in LazyColumn.

**3-branch empty state:**
- `isLoading` → loading spinner
- `tracks.isEmpty() && searchQuery.isNotEmpty()` → "No matching songs"
- `tracks.isEmpty() && searchQuery.isEmpty()` → no special handling
- otherwise → content

- [ ] **Step 4: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt
git commit -m "feat: add search/filter to ArtistDetailScreen"
```

---

### Task 8: Add search to AlbumDetailViewModel + AlbumDetailScreen

Same pattern as Task 5 but adapted for AlbumDetail.

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt`

- [ ] **Step 1: Update AlbumDetailUiState** — add `searchQuery: String = ""` and `showSearch: Boolean = false` fields
- [ ] **Step 2: Add search state to AlbumDetailViewModel** — add `@OptIn(FlowPreview::class)`, `import com.stash.core.ui.util.withSearchFilter`, add `_searchQuery`, `_showSearch`, methods. Pipe the album-filtered tracks flow through `.withSearchFilter(_searchQuery)`. Add `_searchQuery` and `_showSearch` to the `combine`.
- [ ] **Step 3: Update AlbumDetailScreen** — add search icon to `AlbumDetailHeader` (add `onToggleSearch: () -> Unit` param), update call site to pass `onToggleSearch = { viewModel.toggleSearch() }`, add `SearchFilterBar` item in LazyColumn.

**3-branch empty state:**
- `isLoading` → loading spinner
- `tracks.isEmpty() && searchQuery.isNotEmpty()` → "No matching songs"
- `tracks.isEmpty() && searchQuery.isEmpty()` → no special handling
- otherwise → content

- [ ] **Step 4: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt
git commit -m "feat: add search/filter to AlbumDetailScreen"
```

---

### Task 9: Build and verify

- [ ] **Step 1: Build the project**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDebug --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation errors**

Common issues:
- Missing imports for `withSearchFilter`, `SearchFilterBar`, `Icons.Default.Search`
- Kotlin's `combine` supports up to 5 typed flows — all ViewModels in this plan use exactly 5, which compiles fine
- `@OptIn(FlowPreview::class)` should already be on each ViewModel from Tasks 5-8, but verify

- [ ] **Step 3: Install and test on device**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew installDebug --no-daemon 2>&1 | tail -10
```

- [ ] **Step 4: Commit fixes (if any)**

```bash
git add -A && git commit -m "fix: resolve compilation issues for detail view search feature"
```
