# In-List Search for Detail Views — Design Spec

## Goal

Add a search/filter feature to all 4 detail views (PlaylistDetail, LikedSongsDetail, ArtistDetail, AlbumDetail) so users can find specific songs within any playlist, mix, or collection.

## UX Behavior

1. A search icon appears in each detail screen's header, next to the Play All / Shuffle buttons.
2. Tapping the icon reveals a `SearchFilterBar` between the header and the track list.
3. Typing filters the visible track list in real-time (300ms debounce), matching against track title and artist (case-insensitive contains).
4. Header track count and total duration update to reflect the filtered results.
5. Clearing the search text or tapping the search icon again hides the bar and restores the full list.
6. Zero results shows a centered "No matching songs" message in the track list area.
7. Search bar auto-focuses when expanded; keyboard dismisses on scroll.

## Architecture

### Shared search filter utility

Location: `core/ui/src/main/kotlin/com/stash/core/ui/util/SearchFilter.kt`

```kotlin
fun Flow<List<Track>>.withSearchFilter(queryFlow: StateFlow<String>): Flow<List<Track>>
```

- Combines the tracks flow with the debounced query flow (300ms).
- Filters by `track.title` or `track.artist` containing the query (case-insensitive).
- Empty query passes the full list through unchanged.

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
- Styled with the app's glass/dark theme (`StashTheme.extendedColors`).
- Parent controls visibility via a boolean toggle — no internal expand/collapse animation.

### ViewModel changes (all 4 ViewModels)

Each ViewModel receives:

1. `private val _searchQuery = MutableStateFlow("")` — holds the current query.
2. `fun onSearchQueryChanged(query: String)` — updates `_searchQuery`.
3. `fun clearSearch()` — sets query to `""`.
4. The existing tracks flow piped through `.withSearchFilter(_searchQuery)` before combining into UI state.
5. `searchQuery: String = ""` added to the UI state data class.

Affected ViewModels:
- `PlaylistDetailViewModel` — pipe `musicRepository.getTracksByPlaylist(playlistId)` through filter
- `LikedSongsDetailViewModel` — pipe `tracksFlow` through filter
- `ArtistDetailViewModel` — pipe `musicRepository.getTracksByArtist(artistName)` through filter
- `AlbumDetailViewModel` — pipe the album-filtered tracks flow through filter

### Screen changes (all 4 screens)

Each screen receives:

1. `var showSearch by remember { mutableStateOf(false) }` — toggle state.
2. A search icon button added to the header's button row (next to Play All / Shuffle).
3. When `showSearch` is true, a `SearchFilterBar` composable renders as a sticky `item` in the LazyColumn between header and track list.
4. Track count and duration in the header are computed from `state.tracks` (already filtered when search is active).
5. Clearing search or hiding the bar calls `viewModel.clearSearch()`.
6. When `state.tracks` is empty AND `state.searchQuery` is non-empty, show "No matching songs" centered message.

Affected screens:
- `PlaylistDetailScreen`
- `LikedSongsDetailScreen`
- `ArtistDetailScreen`
- `AlbumDetailScreen`

### Artist and Album screen cleanup

As part of this work, migrate `ArtistDetailScreen` and `AlbumDetailScreen` to use the shared `DetailTrackRow` from `core:ui` (replacing their private `ArtistDetailTrackRow` and `AlbumDetailTrackRow`). This matches the pattern already used by `PlaylistDetailScreen` and `LikedSongsDetailScreen` after the previous refactor.

Similarly, migrate their private `TrackOptionsSheet`/`SheetOptionRow`/`formatDuration`/`formatTotalDuration` duplicates to the shared `core:ui` versions.

## Screens Affected

| Screen | ViewModel | Header composable | Shared track row? |
|--------|-----------|-------------------|-------------------|
| PlaylistDetailScreen | PlaylistDetailViewModel | PlaylistHeader | Yes (already) |
| LikedSongsDetailScreen | LikedSongsDetailViewModel | LikedSongsHeader | Yes (already) |
| ArtistDetailScreen | ArtistDetailViewModel | ArtistDetailHeader | No (migrate in this work) |
| AlbumDetailScreen | AlbumDetailViewModel | AlbumDetailHeader | No (migrate in this work) |

## Edge Cases

- **Zero search results**: "No matching songs" message, header shows "0 tracks".
- **Single character query**: Filtering begins immediately (no minimum length for client-side filter).
- **Very large playlists (1000+ tracks)**: 300ms debounce prevents excessive recompositions. Client-side string matching on 1000 items is sub-millisecond.
- **Search bar dismissed**: Query clears, full list restores, keyboard dismisses.
- **Currently playing track highlight**: Still works on filtered list — `currentlyPlayingTrackId` matching is unchanged.

## Out of Scope

- Sorting/ordering within search results (results maintain original playlist order).
- Search history or suggestions.
- Regex or advanced query syntax.
- Persisting search query across navigation.
