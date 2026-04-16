# In-List Search for Detail Views — Design Spec

## Goal

Add a search/filter feature to all 4 detail views (PlaylistDetail, LikedSongsDetail, ArtistDetail, AlbumDetail) so users can find specific songs within any playlist, mix, or collection.

## UX Behavior

1. A search icon appears in each detail screen's header, next to the Play All / Shuffle buttons.
2. Tapping the icon reveals a `SearchFilterBar` between the header and the track list.
3. Typing filters the visible track list in real-time (300ms debounce), matching against track title and artist (case-insensitive contains).
4. Header track count and total duration update to reflect the filtered results.
5. Clearing the search text or tapping the search icon again hides the bar and restores the full list.
6. Zero results shows a centered "No matching songs" message in the track list area (distinct from any "no tracks at all" empty state).
7. Search bar auto-focuses when expanded; keyboard dismisses on scroll.
8. **Playback scope during search**: Play All, Shuffle, and tapping a track all operate on the **filtered** list. If you searched "love" and see 5 results, tapping one queues those 5 tracks starting at the tapped position. This is intentional — you searched for something, you want to play what you found.

## Architecture

### Shared search filter utility

Location: `core/ui/src/main/kotlin/com/stash/core/ui/util/SearchFilter.kt`

```kotlin
fun Flow<List<Track>>.withSearchFilter(queryFlow: StateFlow<String>): Flow<List<Track>>
```

- Internally applies `queryFlow.debounce(300).distinctUntilChanged()` before combining with the tracks flow. The debounce is applied **only to the filtering**, not to the raw query exposed in UiState.
- Filters by `track.title` or `track.artist` containing the query (case-insensitive).
- Empty query passes the full list through unchanged.
- Note: uses 300ms debounce (not 500ms like SearchScreen) because this is client-side string matching, not a network call.

### Shared SearchFilterBar composable

Location: `core/ui/src/main/kotlin/com/stash/core/ui/components/SearchFilterBar.kt`

```kotlin
@Composable
fun SearchFilterBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- Text field with leading search icon and trailing clear (X) button (shown when query is non-empty).
- Placeholder text: "Filter tracks..."
- Styled with the app's glass/dark theme (`StashTheme.extendedColors`).
- Parent controls visibility via a boolean toggle — no internal expand/collapse animation.
- Content description for screen readers: "Filter tracks".

### DetailTrackRow enhancement

Add optional parameters to the existing shared `DetailTrackRow` in `core/ui`:

```kotlin
@Composable
fun DetailTrackRow(
    track: Track,
    trackNumber: Int,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    showArtist: Boolean = true,    // NEW — false for AlbumDetailScreen
    subtitleOverride: String? = null, // NEW — e.g. album name for ArtistDetailScreen
)
```

- When `showArtist` is false, the subtitle line is hidden (used by AlbumDetailScreen where artist is in the header).
- When `subtitleOverride` is non-null, it replaces `track.artist` as the subtitle (used by ArtistDetailScreen to show `track.album` instead).
- Default behavior (both params at defaults) is unchanged — shows `track.artist` as subtitle.

### ViewModel changes (all 4 ViewModels)

Each ViewModel receives:

1. `private val _searchQuery = MutableStateFlow("")` — holds the **raw** (undebounced) query.
2. `private val _showSearch = MutableStateFlow(false)` — search bar visibility (survives rotation).
3. `fun onSearchQueryChanged(query: String)` — updates `_searchQuery`.
4. `fun toggleSearch()` — toggles `_showSearch`; when hiding, also clears `_searchQuery`.
5. `fun clearSearch()` — sets `_searchQuery` to `""`.
6. The existing tracks flow piped through `.withSearchFilter(_searchQuery)` before combining into UI state.
7. UI state data class gets: `searchQuery: String = ""` (raw, for text field display and empty-state branching) and `showSearch: Boolean = false`.

**Key: the raw `_searchQuery` feeds the UiState `searchQuery` field for display. The debounced version is used only inside `withSearchFilter` for filtering. This prevents a race where the text field and the filter results are out of sync.**

Affected ViewModels:
- `PlaylistDetailViewModel` — pipe `musicRepository.getTracksByPlaylist(playlistId)` through filter
- `LikedSongsDetailViewModel` — pipe `tracksFlow` through filter
- `ArtistDetailViewModel` — pipe `musicRepository.getTracksByArtist(artistName)` through filter
- `AlbumDetailViewModel` — pipe the album-filtered tracks flow through filter

### Screen changes (all 4 screens)

Each screen receives:

1. Search icon button added to the header's button row (next to Play All / Shuffle).
2. When `state.showSearch` is true, a `SearchFilterBar` composable renders as a regular `item` in the LazyColumn between header and track list (scrolls with header, not sticky — avoids experimental API).
3. Track count and duration in the header are computed from `state.tracks` (already filtered when search is active).
4. Clearing search or hiding the bar calls `viewModel.clearSearch()` / `viewModel.toggleSearch()`.
5. Empty state branching (applies to all screens, especially LikedSongsDetailScreen which has an existing empty state):
   - `isLoading` → loading spinner
   - `tracks.isEmpty() && searchQuery.isNotEmpty()` → "No matching songs" message
   - `tracks.isEmpty() && searchQuery.isEmpty()` → screen-specific empty state (e.g., "No liked songs yet")
   - otherwise → track list content

Affected screens:
- `PlaylistDetailScreen`
- `LikedSongsDetailScreen`
- `ArtistDetailScreen`
- `AlbumDetailScreen`

### Artist and Album screen cleanup

As part of this work, migrate `ArtistDetailScreen` and `AlbumDetailScreen` to use:
- `DetailTrackRow` from `core:ui` (with `subtitleOverride = track.album` for Artist, `showArtist = false` for Album)
- `TrackOptionsSheet` / `SheetOptionRow` from `core:ui` (replacing private duplicates)
- `formatDuration` / `formatTotalDuration` from `core:ui` (replacing private `formatDurationMs` / `formatTotalDurationMs`)

This removes ~280 lines of duplicated code per screen, matching the pattern already used by PlaylistDetailScreen and LikedSongsDetailScreen.

## Screens Affected

| Screen | ViewModel | Header composable | Shared track row? | Track row customization |
|--------|-----------|-------------------|-------------------|------------------------|
| PlaylistDetailScreen | PlaylistDetailViewModel | PlaylistHeader | Yes (already) | Default (title + artist) |
| LikedSongsDetailScreen | LikedSongsDetailViewModel | LikedSongsHeader | Yes (already) | Default (title + artist) |
| ArtistDetailScreen | ArtistDetailViewModel | ArtistDetailHeader | No → migrate | `subtitleOverride = track.album` |
| AlbumDetailScreen | AlbumDetailViewModel | AlbumDetailHeader | No → migrate | `showArtist = false` |

## Edge Cases

- **Zero search results**: "No matching songs" message, header shows "0 tracks". Distinct from screen-specific "no data" empty states.
- **Single character query**: Filtering begins immediately (no minimum length for client-side filter).
- **Very large playlists (1000+ tracks)**: 300ms debounce + `distinctUntilChanged()` prevents excessive recompositions. Client-side string matching on 1000 items is sub-millisecond.
- **Search bar dismissed**: Query clears, full list restores, keyboard dismisses.
- **Currently playing track highlight**: Still works on filtered list — `currentlyPlayingTrackId` matching is unchanged.
- **Device rotation / process death**: `showSearch` and `searchQuery` live in ViewModel (survives config changes). On process death, ViewModel is recreated and search state resets to defaults — acceptable.
- **Playback during search**: Queue is scoped to filtered results. User sees and plays exactly what they searched for.

## Out of Scope

- Sorting/ordering within search results (results maintain original playlist order).
- Search history or suggestions.
- Regex or advanced query syntax.
- Persisting search query across navigation.
