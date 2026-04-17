# Album Discovery — InnerTube-powered Album Detail for Search/Discovery

**Date:** 2026-04-17
**Status:** Design — approved to proceed to implementation plan
**Scope:** `core/media/actions` (new delegate), `feature/search` (new screen + VM), `data/ytmusic` (one new parser + API method), `app/navigation` (one new route). Migrates `SearchViewModel` + `ArtistProfileViewModel` onto the new delegate.

---

## 1. Goal & Scope

Bug surfaced during Search-overhaul QA: tapping an album from Artist Profile or Search's Albums row routes to the library's `AlbumDetailScreen`, which only shows already-downloaded tracks. Users expect the full album tracklist so they can preview and download tracks they don't yet have.

This spec adds a dedicated **Album Discovery** screen powered by YouTube Music (InnerTube), and — because it becomes the third caller of the preview+download methods already duplicated across `SearchViewModel` and `ArtistProfileViewModel` — extracts those methods into a reusable `TrackActionsDelegate` as step one of the work.

**In scope:**

- A new `TrackActionsDelegate` class in `core/media/actions` consolidating preview + download state and behavior. Migration of `SearchViewModel` + `ArtistProfileViewModel` onto it. No behavior change to either caller.
- A new `AlbumDiscoveryScreen` + `AlbumDiscoveryViewModel` + `AlbumDiscoveryUiState` in `feature/search`.
- A new `AlbumCache` (in-memory, 30-minute TTL) in `core/data/cache`.
- A new `YTMusicApiClient.getAlbum(browseId)` method + `AlbumResponseParser` + `AlbumDetail` DTO.
- A new `SearchAlbumRoute` nav destination. Rewiring of both `SearchScreen.onNavigateToAlbum` and `ArtistProfileScreen.onNavigateToAlbum` to route here instead of the library screen.
- Shuffle chip (hidden until ≥1 track is downloaded) + Download-all chip (with confirm dialog) in the hero.

**Out of scope (explicit non-goals):**

- Room-backed album cache. Albums are near-static; 30-minute in-memory is enough.
- Artist-name link in hero (tapping artist name → `ArtistProfile`). Deferred; adds edge cases around null `artistId`.
- Hero parallax, scroll-collapse, animated shimmer.
- Save-to-playlist / add-to-queue on discovery tracks. Those are library actions; discovery tracks may not be downloaded yet.
- Offline fallback when the user has no network. Error card + retry is enough.

---

## 2. Information Architecture

One new screen, one new route. All entry points that talk about a YouTube-Music album now land here.

```
SearchRoute
  ├── tap album card in Albums row  ──> SearchAlbumRoute(AlbumSummary fields)   (new — was AlbumDetailRoute)
  └── tap artist                     ──> SearchArtistRoute(existing)

SearchArtistRoute
  ├── tap album card in Albums row  ──> SearchAlbumRoute(...)                   (new — was AlbumDetailRoute)
  ├── tap card in Singles & EPs row ──> SearchAlbumRoute(...)                   (new — was AlbumDetailRoute)
  └── tap track in Popular          ──> preview / download in place

SearchAlbumRoute                                                                 (new)
  ├── tap "More by this artist" card ──> SearchAlbumRoute(...)                  (recursive)
  ├── tap track                      ──> preview / download in place
  └── back                            ──> pop
```

The library's `AlbumDetailRoute` — invoked by `LibraryScreen` / `LikedSongsDetailScreen` — is unchanged. That screen remains the library view of downloaded tracks; `SearchAlbumRoute` is the discovery view.

**Screen layout (top → bottom):**

1. **Hero** (`AlbumHero.kt`) — square cover art (full-width), title (Space Grotesk 28 sp), artist, year, `N tracks · M min` metadata line, action chip row:
    - **Shuffle** — only rendered when at least one track on the album is downloaded. Plays the downloaded subset in shuffled order via `PlayerRepository.setQueue`.
    - **Download all** — always rendered. Tap opens a Material3 `AlertDialog` confirming "Download N tracks?" where N is the non-downloaded subset. Confirm → enqueues the N tracks through `TrackActionsDelegate.downloadTrack` in sequence.
2. **Tracks list** — `PreviewDownloadRow` per track, showing preview + download state via `TrackActionsDelegate` flows.
3. **Section header** — "More by this artist" (reuses existing `SectionHeader`).
4. **More-by-artist row** — `LazyRow` of `AlbumSquareCard` (same composable the Artist Profile uses).

Back button: `IconButton` with `Icons.AutoMirrored.Filled.ArrowBack` at `Alignment.TopStart` over the hero cover art, same pattern as `ArtistHero` got in Task 12.

---

## 3. Data Sources & Flow

### 3.1 Data source

YouTube Music (InnerTube) is the sole source. InnerTube's `browse(albumBrowseId)` returns the entire album page — metadata, tracklist, "More by this artist" — in one round trip.

### 3.2 New API method

In `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`, mirroring `getArtist`:

```kotlin
suspend fun getAlbum(browseId: String): AlbumDetail
```

Calls `InnerTubeClient.browse(browseId)`. Parses via a new `AlbumResponseParser.kt` (following the shape of `ArtistResponseParser.kt`).

### 3.3 DTOs

New file: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/AlbumDetail.kt`:

```kotlin
@Serializable
data class AlbumDetail(
    val id: String,              // browseId (MPREb_*)
    val title: String,
    val artist: String,
    val artistId: String?,       // may be null on compilations
    val thumbnailUrl: String?,
    val year: String?,
    val tracks: List<TrackSummary>,
    val moreByArtist: List<AlbumSummary>,
)
```

`TrackSummary` + `AlbumSummary` are the existing DTOs from `SearchAllResults.kt` — no changes.

### 3.4 In-memory cache

New file: `core/data/src/main/kotlin/com/stash/core/data/cache/AlbumCache.kt`:

```kotlin
@Singleton
class AlbumCache @Inject constructor(
    private val api: YTMusicApiClient,
) {
    private data class Entry(val detail: AlbumDetail, val fetchedAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val mutex = Mutex()  // guards one in-flight fetch per key

    suspend fun get(browseId: String): AlbumDetail { /* cache-then-fetch */ }
    fun invalidate(browseId: String) { entries.remove(browseId) }

    companion object {
        internal const val TTL_MS = 30 * 60_000L
    }
}
```

Semantics:
- Cache hit within TTL → returns cached synchronously, no network call.
- Cache miss OR stale → fetches from API, stores, returns. If a second caller asks for the same `browseId` while one is in flight, the second call awaits the first (mutex-guarded per-key).
- Fetch failure → throws; caller (`AlbumDiscoveryViewModel`) converts to `Status.Error`.

Deliberately simpler than `ArtistCache`'s SWR — no stale emissions, no Room-backed layer, no "refresh failed but keep showing old" semantics. Albums don't change often enough to justify the complexity.

### 3.5 `TrackActionsDelegate` — the refactor

New file: `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt`.

```kotlin
@ViewModelScoped
class TrackActionsDelegate @Inject constructor(
    private val previewPlayer: PreviewPlayer,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val previewUrlCache: PreviewUrlCache,
    private val downloadExecutor: DownloadExecutor,
    private val trackDao: TrackDao,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val musicRepository: MusicRepository,
) {
    // State the delegate owns:
    val previewState: StateFlow<PreviewState>  // from previewPlayer
    val userMessages: SharedFlow<String>
    val downloadingIds: StateFlow<Set<String>>
    val downloadedIds: StateFlow<Set<String>>
    val previewLoadingId: StateFlow<String?>

    // Delegate is given a scope by its caller (the VM) so its internal
    // coroutines die with the VM. No leaked job past onCleared.
    //
    // Contract: called exactly once, in the VM's init block, before any
    // other delegate method is invoked. A second call throws
    // IllegalStateException. Reading any delegate flow before bindToScope
    // returns the initial value (empty set / null) — flows are cold until
    // bound.
    fun bindToScope(scope: CoroutineScope)

    suspend fun previewTrack(videoId: String)
    fun stopPreview()
    fun downloadTrack(item: SearchResultItem)
    fun onPreviewError(videoId: String, error: PlaybackException)
    suspend fun refreshDownloadedIds(videoIds: Collection<String>)

    // Called by owning VM's onCleared(). Stops preview; cancels inflight work.
    fun onOwnerCleared()
}
```

**Ownership & scoping:**
- `@ViewModelScoped` — a fresh `TrackActionsDelegate` instance per VM (so two VMs living at once don't share state). The 8 underlying deps are `@Singleton`.
- The VM must call `bindToScope(viewModelScope)` in its `init` block. The delegate then uses that scope for its internal `launch`es (the `playerErrors` collector, the download coroutines) — so cancellation is automatic on `onCleared`.
- Alternative to `bindToScope`: accept the scope as a constructor param via `@AssistedInject`. Chose the bind call for simpler test wiring; both are equivalent.

**Migration of existing VMs:**

| Removed from `SearchViewModel` | Stays on `SearchViewModel` |
|---|---|
| 8 preview/download deps | `api`, `prefetcher`, `queryFlow`, `runSearch`, `prefetchTopN`, `refreshDownloadedIds` call-through |
| `previewTrack`, `stopPreview`, `downloadTrack`, `onPreviewError` | `onQueryChanged`, search status flow |
| `handleDownloadSuccess`, `markDownloadFailed`, `isIoError` | |
| `lastPreviewVideoId`, `lastPreviewStartedAt` | |
| `playerErrors` collector | |
| `onCleared() { previewPlayer.stop() }` | `onCleared() { delegate.onOwnerCleared() }` |
| UiState: `downloadingIds`, `downloadedIds`, `previewLoading` | `query`, `status` |

`SearchUiState` reduces to `query` + `status`. The screen reads `downloadingIds` / `downloadedIds` / `previewLoading` / `previewState` directly from the delegate via `collectAsStateWithLifecycle`. Snackbar host listens to `delegate.userMessages` merged with the VM's own (for search failures).

`ArtistProfileViewModel` follows the same shape: keeps cache subscription + `retry()` + `prefetchKicked` + `observeCache` + the nav-arg hero hydration; delegates everything else. `ArtistProfileUiState` loses `downloadingIds`/`downloadedIds`/`previewLoading`.

**Parity check:** the existing 13 tests across `SearchViewModelTest` + `ArtistProfileViewModelTest` that verify preview/download behavior must continue to pass after the migration. Any that break are a refactor bug, not an accepted behavior change.

---

## 4. `AlbumDiscoveryViewModel`

New file: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryViewModel.kt`.

```kotlin
@HiltViewModel
class AlbumDiscoveryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val albumCache: AlbumCache,
    private val prefetcher: PreviewPrefetcher,
    private val playerRepository: PlayerRepository,  // for Shuffle
    val delegate: TrackActionsDelegate,
) : ViewModel() {

    private val browseId: String = requireNotNull(savedStateHandle["browseId"]) {
        "SearchAlbumRoute requires a non-null browseId nav arg"
    }
    private val initialTitle: String = savedStateHandle["title"] ?: ""
    private val initialArtist: String = savedStateHandle["artist"] ?: ""
    private val initialThumb: String? = savedStateHandle["thumbnailUrl"]
    private val initialYear: String? = savedStateHandle["year"]

    private val _uiState = MutableStateFlow(AlbumDiscoveryUiState(
        hero = AlbumHeroState(initialTitle, initialArtist, initialThumb, initialYear, 0, 0L),
        status = AlbumDiscoveryStatus.Loading,
    ))
    val uiState: StateFlow<AlbumDiscoveryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var prefetchKicked = false

    init {
        delegate.bindToScope(viewModelScope)
        loadJob = viewModelScope.launch { observeAlbum() }
    }

    fun retry() {
        loadJob?.cancel()
        _uiState.update { it.copy(status = AlbumDiscoveryStatus.Loading) }
        loadJob = viewModelScope.launch { observeAlbum() }
    }

    fun onDownloadAllClicked() { _uiState.update { it.copy(showDownloadConfirm = true) } }
    fun onDownloadAllDismissed() { _uiState.update { it.copy(showDownloadConfirm = false) } }
    fun onDownloadAllConfirmed() { /* iterate non-downloaded tracks; delegate.downloadTrack each */ }

    fun shuffleDownloaded() { /* reads delegate.downloadedIds snapshot, finds the Track rows in DB, calls playerRepository.setQueue shuffled */ }

    override fun onCleared() {
        super.onCleared()
        delegate.onOwnerCleared()
        prefetcher.cancelAll()
    }

    private suspend fun observeAlbum() { /* cache fetch → fold into state → prefetch once */ }
}
```

**Hero nav-arg hydration:** hero paints immediately from the 5 string nav args (title, artist, thumb, year — plus track count = 0 / total duration = 0 until the fetch returns). This preserves the sub-50ms first-frame feel established by ArtistProfile.

**Download-All confirm flow:** when the chip is tapped, VM **snapshots** the current non-downloaded set into `AlbumDiscoveryUiState.downloadConfirmQueue: List<TrackSummary>` and flips `showDownloadConfirm = true`. The dialog renders `"Download ${queue.size} tracks?"` from the snapshot; user confirms → VM iterates the snapshot, not `delegate.downloadedIds.value`, so a track the user downloaded individually between open-dialog and confirm is NOT re-enqueued. User dismisses → clear the snapshot. `DownloadExecutor` already serializes downloads via a `Semaphore`; we don't orchestrate batching.

**Shuffle:** only clickable when `delegate.downloadedIds` intersects `tracks.map{it.videoId}` non-empty. The chip's visibility is **reactive** to `delegate.downloadedIds` — if a download completes while the user is looking at the album, Shuffle appears without a navigation round-trip. Implementation: the screen reads `downloadedIds` via `collectAsStateWithLifecycle` and computes `hasDownloaded` inline at render time; no special code path needed. VM's `shuffleDownloaded()` queries the TrackDao for Track rows matching those videoIds, shuffles in-memory, hands to `playerRepository.setQueue`. Matches the library screen's Shuffle behavior.

---

## 5. `AlbumDiscoveryScreen`

New file: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryScreen.kt`.

```kotlin
@Composable
fun AlbumDiscoveryScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (AlbumSummary) -> Unit,
    vm: AlbumDiscoveryViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val delegate = vm.delegate
    val downloadingIds by delegate.downloadingIds.collectAsStateWithLifecycle()
    val downloadedIds by delegate.downloadedIds.collectAsStateWithLifecycle()
    val previewLoadingId by delegate.previewLoadingId.collectAsStateWithLifecycle()
    val previewState by delegate.previewState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Merge VM + delegate messages into the one snackbar host.
    LaunchedEffect(vm) {
        merge(vm.userMessages, delegate.userMessages)
            .collect { snackbar.showSnackbar(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        LazyColumn(...) {
            item { AlbumHero(state.hero, hasDownloaded = hasDownloaded, onBack, onShuffle = vm::shuffleDownloaded, onDownloadAll = vm::onDownloadAllClicked) }

            when (state.status) {
                Loading -> item { CircularProgressIndicator(...) }
                is Error -> item { DiscoveryErrorCard(state.status.message, onRetry = vm::retry) }
                Fresh -> {
                    items(state.tracks) { track -> PreviewDownloadRow(...) }
                    if (state.moreByArtist.isNotEmpty()) {
                        item { SectionHeader("More by this artist") }
                        item { AlbumsRow(state.moreByArtist, onClick = onNavigateToAlbum) }
                    }
                }
            }
        }

        if (state.showDownloadConfirm) {
            AlertDialog(
                onDismissRequest = vm::onDownloadAllDismissed,
                title = { Text("Download all?") },
                text = { Text("Download ${state.tracks.count { it.videoId !in downloadedIds }} tracks to your library?") },
                confirmButton = { Button(onClick = vm::onDownloadAllConfirmed) { Text("Download") } },
                dismissButton = { TextButton(onClick = vm::onDownloadAllDismissed) { Text("Cancel") } },
            )
        }
    }
}
```

### 5.1 `AlbumDiscoveryUiState`

```kotlin
data class AlbumHeroState(
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val year: String?,
    val trackCount: Int,
    val totalDurationMs: Long,
)

sealed interface AlbumDiscoveryStatus {
    data object Loading : AlbumDiscoveryStatus
    data object Fresh : AlbumDiscoveryStatus
    data class Error(val message: String) : AlbumDiscoveryStatus
}

data class AlbumDiscoveryUiState(
    val hero: AlbumHeroState,
    val tracks: List<TrackSummary> = emptyList(),
    val moreByArtist: List<AlbumSummary> = emptyList(),
    val status: AlbumDiscoveryStatus = AlbumDiscoveryStatus.Loading,
    val showDownloadConfirm: Boolean = false,
    // Snapshot of the non-downloaded subset at dialog-open. Confirmed →
    // iterate THIS list, not delegate.downloadedIds.value, so the "N" shown
    // to the user is exactly the "N" that get downloaded. Cleared on dismiss.
    val downloadConfirmQueue: List<TrackSummary> = emptyList(),
)
```

### 5.2 `AlbumHero`

New file: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumHero.kt`.

- `Box` with full-width square `AsyncImage` (cover art).
- `IconButton` top-left over the image with ArrowBack.
- Gradient scrim from transparent→background at the bottom 40% of the image so text below reads cleanly (same pattern as `AlbumDetailScreen`'s header).
- Column below the image with title / artist / metadata line / chip row.
- Metadata line: `"${trackCount} track${if (trackCount == 1) "" else "s"} · ${formatTotalDuration(totalDurationMs)}"` — reuses `com.stash.core.ui.util.formatTotalDuration` already present in `core/ui`.
- Chip row (Row + 12.dp spacing): Shuffle `OutlinedButton` (only if `hasDownloaded`) + Download-All `Button`.

---

## 6. Navigation Changes

### 6.1 New route

In `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`, append:

```kotlin
@Serializable
data class SearchAlbumRoute(
    val browseId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val year: String?,
)
```

Nullable-string nav args: `SearchArtistRoute` (already registered) takes `avatarUrl: String?` and works with the current `StashNavHost` type-safe nav setup — no `NavType` customization is required. `SearchAlbumRoute` inherits the same handling for its two nullable fields.

### 6.2 Callback signatures tighten

Both `SearchScreen.onNavigateToAlbum` and `ArtistProfileScreen.onNavigateToAlbum` change from:

```kotlin
(albumName: String, artistName: String) -> Unit
```

to:

```kotlin
(album: AlbumSummary) -> Unit
```

The `AlbumSummary` already carries `id` (browseId), `title`, `artist`, `thumbnailUrl`, `year` — exactly the 5 nav-arg fields. All four call sites (`AlbumsRow` in SearchScreen; `AlbumsRow` + `SinglesRow` in ArtistProfileScreen; the recursive call from AlbumDiscoveryScreen) pass the whole `AlbumSummary` through.

### 6.3 `StashNavHost.kt` wiring

The `composable<SearchAlbumRoute>` block:

```kotlin
composable<SearchAlbumRoute> {
    AlbumDiscoveryScreen(
        onBack = { navController.popBackStack() },
        onNavigateToAlbum = { album ->
            navController.navigate(SearchAlbumRoute(
                album.id, album.title, album.artist, album.thumbnailUrl, album.year,
            ))
        },
    )
}
```

The existing `composable<AlbumDetailRoute>` block is untouched. Library call sites (`LibraryScreen`, `LikedSongsDetailScreen`) still route to it for downloaded-album playback.

---

## 7. Error Handling & Edge Cases

| Condition | Behavior |
|---|---|
| Cache fetch fails (no cached copy) | VM flips `status = Error(msg)`. Screen renders `DiscoveryErrorCard(message, onRetry)`. Snackbar "Couldn't load album — tap Retry." |
| Cache fetch fails (cached copy exists AND within TTL) | Shouldn't happen — hit returns synchronously. |
| Fetch succeeds, empty `tracks` list | Render hero + "No tracks available" message in place of tracks list. Hide Shuffle + Download-All chips. |
| Download-All confirm → one track's download fails mid-batch | `TrackActionsDelegate.downloadTrack` already updates its own `downloadingIds` / `downloadedIds` / snackbar on per-track failure. Other tracks continue. No aggregate "N tracks failed" notification. |
| Preview fails (InnerTube URL rejected) | Existing yt-dlp retry in `TrackActionsDelegate`. If that also fails, snackbar "Couldn't load preview." |
| Tapping "More by this artist" re-enters same route | Expected; back-stack grows. `popBackStack` handles unwinding. |
| Artist link in hero | Deferred. Tapping artist name is a no-op for now. |

---

## 8. Testing

| File | Coverage |
|---|---|
| `core/media/src/test/.../TrackActionsDelegateTest.kt` (**new**) | ~12 tests moved over from the VM tests: preview cache hit, InnerTube→yt-dlp retry, download success path, mid-download cancellation, markDownloadFailed, `refreshDownloadedIds`. |
| `core/data/src/test/.../AlbumCacheTest.kt` (**new**) | Miss → fetch + store; hit within TTL → cache, no network; hit past TTL → refetch; `invalidate()` evicts; concurrent get for same key → one fetch. |
| `data/ytmusic/src/test/.../YTMusicApiClientTest.kt` (**extend**) | Add 3 `getAlbum` tests: rich (15-track album with year + moreByArtist), sparse (no year, empty moreByArtist), malformed (missing tracklist shelf). |
| `feature/search/src/test/.../AlbumDiscoveryViewModelTest.kt` (**new**) | ~7 tests: hero hydrates from nav args; Fresh transition on cache emit; cache failure → Error + snackbar; `retry()` re-subscribes with Loading flip; `shuffleDownloaded()` plays only downloaded subset; `onDownloadAllConfirmed()` enqueues only non-downloaded; prefetch kicked exactly once. |
| `feature/search/src/test/.../SearchViewModelTest.kt` (**migrate**) | Swap 8 mocks for a single `TrackActionsDelegate` mock. The 4 original tests stay green. |
| `feature/search/src/test/.../ArtistProfileViewModelTest.kt` (**migrate**) | Same. The 5 original tests stay green. |

Net test-count change: +~12 new tests (7 VM + 4 cache + 3 API + 1 integration smoke), with ~12 moved (not duplicated). Existing test count on the branch should go up by a net +10-12.

---

## 9. File Structure

| File | Action | Responsibility |
|---|---|---|
| `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt` | Create | Shared preview+download state and methods for all VMs. |
| `core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateTest.kt` | Create | Moves the preview+download tests out of the VM tests. |
| `core/data/src/main/kotlin/com/stash/core/data/cache/AlbumCache.kt` | Create | In-memory LRU cache with 30-min TTL. |
| `core/data/src/test/kotlin/com/stash/core/data/cache/AlbumCacheTest.kt` | Create | Hit / miss / expiry / concurrent-get tests. |
| `core/ui/src/main/kotlin/com/stash/core/ui/components/DiscoveryErrorCard.kt` | Create (extract) | Shared error card — second caller justifies extraction from `ArtistProfileErrorCard`. |
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/AlbumDetail.kt` | Create | New DTO for full album page. |
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/AlbumResponseParser.kt` | Create | Parses `browse(albumBrowseId)` InnerTube response. |
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt` | Modify | Add `getAlbum(browseId)` method. |
| `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt` | Modify | Add `getAlbum` tests (3 fixtures). |
| `data/ytmusic/src/test/resources/fixtures/album_rich.json` | Create | Rich album fixture (15 tracks, year, moreByArtist). |
| `data/ytmusic/src/test/resources/fixtures/album_sparse.json` | Create | Sparse album fixture. |
| `data/ytmusic/src/test/resources/fixtures/album_malformed.json` | Create | Malformed response fixture. |
| `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryScreen.kt` | Create | Top-level composable. |
| `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryViewModel.kt` | Create | Nav-arg hydration, cache subscription, retry, shuffle, download-all confirm flow. |
| `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryUiState.kt` | Create | UiState + status sealed hierarchy. |
| `feature/search/src/main/kotlin/com/stash/feature/search/AlbumHero.kt` | Create | Cover art + title + chips. |
| `feature/search/src/test/kotlin/com/stash/feature/search/AlbumDiscoveryViewModelTest.kt` | Create | 7 VM tests. |
| `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt` | Modify | Narrow `onNavigateToAlbum` signature → `(AlbumSummary) -> Unit`. Update `SectionedResultsList`, Album card call site, and `AlbumsRow` invocation. Drop `downloadingIds`/`downloadedIds`/`previewLoading` from `SearchUiState` reads — read from delegate instead. |
| `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt` | Modify | Inject `TrackActionsDelegate`; remove 8 deps, 4 methods, 3 helpers, bookkeeping; delegate per-track actions. |
| `feature/search/src/main/kotlin/com/stash/feature/search/SearchUiState.kt` | Modify | Drop `downloadingIds`, `downloadedIds`, `previewLoading`. |
| `feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt` | Modify | Swap 8 mocks for `TrackActionsDelegate` mock. |
| `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt` | Modify | `onNavigateToAlbum` signature change. Same for `SinglesRow` click. |
| `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt` | Modify | Inject `TrackActionsDelegate`; remove 8 deps + duplicated methods + bookkeeping + player-errors collector. Keep cache, retry, prefetcher, nav-arg hero. |
| `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt` | Modify (2nd) | Rewire existing `ArtistProfileErrorCard` call site to use the extracted `DiscoveryErrorCard` from `core/ui`. Delete the old local composable. |
| `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt` | Modify | Drop `downloadingIds`, `downloadedIds`, `previewLoading`. |
| `feature/search/src/main/kotlin/com/stash/feature/search/PopularTracksSection.kt` | Modify | Read preview/download flags from delegate-sourced flows passed by the screen. |
| `feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt` | Modify | Swap 8 mocks for `TrackActionsDelegate` mock. |
| `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt` | Modify | Register `SearchAlbumRoute`. |
| `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt` | Modify | Register `composable<SearchAlbumRoute>`. Update the two existing `onNavigateToAlbum` wirings (Search + ArtistProfile composables). |

---

## 10. Performance Contract (inherited)

Latency targets are the ones established in the search spec §4.1. The only new target for this work:

| User action | p50 | p95 |
|---|---|---|
| Tap album card → hero painted (from nav args) | <50 ms | <80 ms |
| Hero painted → tracklist visible (cache hit) | <16 ms (single frame) | <30 ms |
| Hero painted → tracklist visible (cache miss / cold) | <800 ms | <1500 ms |
| Tap preview on a track | inherited from Search §4.1 |
| Tap Download-all Confirm → first download starts | <300 ms | <600 ms |

---

## 11. Suggested Implementation Phasing

This spec is on the upper end for a single plan (~28 file touches). To keep each phase's green-tests gate tight, the planner should split into two phases:

**Phase 1 — `TrackActionsDelegate` refactor (no user-visible change).**
- Create `TrackActionsDelegate` + tests.
- Migrate `SearchViewModel` onto it + update its tests.
- Migrate `ArtistProfileViewModel` onto it + update its tests.
- Extract `DiscoveryErrorCard` to `core/ui` and rewire `ArtistProfileScreen`.
- Green test gate: existing 13 VM tests + new delegate tests all pass; app builds; no visible behavior change.

**Phase 2 — Album Discovery (user-visible feature).**
- New `AlbumDetail` DTO + `AlbumResponseParser` + `getAlbum(browseId)` + API tests.
- New `AlbumCache` + tests.
- New `AlbumDiscoveryScreen` + `AlbumDiscoveryViewModel` + `AlbumDiscoveryUiState` + `AlbumHero` + VM tests.
- New `SearchAlbumRoute`; rewire `SearchScreen` + `ArtistProfileScreen` album callbacks.

Phase 1 is self-contained and mergeable on its own if Phase 2 runs long.

---

## 12. Non-goals recap

- No Room-backed persistence of album detail (confirmed — 30-min in-memory is enough).
- No artist-link in hero (deferred).
- No hero parallax / scroll-collapse animations.
- No save-to-playlist / add-to-queue on discovery tracks.
- No offline fallback — cache is best-effort; error card covers the miss case.
