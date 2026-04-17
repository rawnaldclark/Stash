# Album Discovery — TrackActionsDelegate Refactor + InnerTube-powered Album Detail

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a dedicated Album Discovery screen powered by YouTube Music (InnerTube) for the Search/Artist-Profile flow, and — as step 1 — extract the duplicated preview+download code from `SearchViewModel` + `ArtistProfileViewModel` into a reusable `TrackActionsDelegate`.

**Architecture:** New `TrackActionsDelegate` (`@ViewModelScoped`) owns preview + download state + methods. Both existing VMs migrate onto it. New `AlbumDiscoveryScreen/VM/UiState` + `AlbumHero` + `AlbumCache` (in-memory, 30-min TTL) + `YTMusicApiClient.getAlbum(browseId)` + new `AlbumResponseParser`. New `SearchAlbumRoute` replaces the library's `AlbumDetailRoute` as the album-tap target from Search + Artist Profile.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt (@ViewModelScoped), Room (no schema change this branch), kotlinx-coroutines (SharedFlow / StateFlow / flatMap), Coil3, Media3/ExoPlayer, InnerTube via existing `InnerTubeClient`, JUnit4 + Turbine for ViewModel tests.

**Spec:** `docs/superpowers/specs/2026-04-17-album-discovery-design.md`

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt` | Create | Shared preview+download state and methods for all VMs. |
| `core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateTest.kt` | Create | Preview cache hit, InnerTube→yt-dlp retry, download success, markDownloadFailed, refreshDownloadedIds. |
| `core/media/build.gradle.kts` | Modify | Add `implementation(project(":data:download"))`, `implementation(project(":core:data"))`, Hilt `hilt-android` + ksp, test deps (junit, turbine, mockito-kotlin, coroutines-test). |
| `core/data/src/main/kotlin/com/stash/core/data/cache/AlbumCache.kt` | Create | In-memory LRU cache, 30-min TTL, per-key mutex. |
| `core/data/src/test/kotlin/com/stash/core/data/cache/AlbumCacheTest.kt` | Create | Hit / miss / expiry / concurrent-get. |
| `core/ui/src/main/kotlin/com/stash/core/ui/components/DiscoveryErrorCard.kt` | Create | Shared error card (extracted from ArtistProfileErrorCard). |
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/AlbumDetail.kt` | Create | Serializable DTO for full album. |
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/AlbumResponseParser.kt` | Create | Parser for `browse(albumBrowseId)` InnerTube response. |
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt` | Modify | Add `getAlbum(browseId): AlbumDetail` method. |
| `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt` | Modify | Add 3 `getAlbum` tests. |
| `data/ytmusic/src/test/resources/fixtures/album_rich.json` | Create | 15-track album with year + moreByArtist. |
| `data/ytmusic/src/test/resources/fixtures/album_sparse.json` | Create | Minimal album (no year, empty moreByArtist). |
| `data/ytmusic/src/test/resources/fixtures/album_malformed.json` | Create | Missing tracklist shelf. |
| `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryScreen.kt` | Create | Top-level composable + AlertDialog for download-all confirm. |
| `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryViewModel.kt` | Create | Nav-arg hero hydration, cache subscription, retry, shuffle, download-all. |
| `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryUiState.kt` | Create | UiState + `AlbumDiscoveryStatus` sealed hierarchy + `AlbumHeroState`. |
| `feature/search/src/main/kotlin/com/stash/feature/search/AlbumHero.kt` | Create | Cover art hero + title + chips + back button. |
| `feature/search/src/test/kotlin/com/stash/feature/search/AlbumDiscoveryViewModelTest.kt` | Create | 7 tests covering cache flow, retry, shuffle, download-all, prefetch. |
| `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt` | Modify | Inject `TrackActionsDelegate`; remove 8 deps + 4 methods + 3 helpers + bookkeeping + playerErrors collector + onCleared body. |
| `feature/search/src/main/kotlin/com/stash/feature/search/SearchUiState.kt` | Modify | Drop `downloadingIds`, `downloadedIds`, `previewLoading`. |
| `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt` | Modify | Read `downloadingIds`/`downloadedIds`/`previewLoading`/`previewState` from `vm.delegate`; narrow `onNavigateToAlbum` signature to `(AlbumSummary) -> Unit`. |
| `feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt` | Modify | Swap 8 dep mocks for single `TrackActionsDelegate` mock. |
| `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt` | Modify | Inject `TrackActionsDelegate`; remove 8 deps + duplicated methods + bookkeeping + playerErrors collector. |
| `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt` | Modify | Drop `downloadingIds`, `downloadedIds`, `previewLoading`. |
| `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt` | Modify | Read `downloadingIds`/etc. from `vm.delegate`; narrow `onNavigateToAlbum` signature; swap local `ArtistProfileErrorCard` for imported `DiscoveryErrorCard`. |
| `feature/search/src/main/kotlin/com/stash/feature/search/PopularTracksSection.kt` | Modify | Accept delegate-sourced flags from the screen (no internal structural change). |
| `feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt` | Modify | Swap 8 dep mocks for single `TrackActionsDelegate` mock. |
| `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt` | Modify | Register `SearchAlbumRoute`. |
| `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt` | Modify | Register `composable<SearchAlbumRoute>`; rewire Search + ArtistProfile album callbacks. |

---

## Phase 1 — TrackActionsDelegate Refactor (no user-visible change)

The goal of Phase 1 is to end with the app looking and behaving identically to today, but with the preview+download code living in exactly ONE place. If Phase 1 breaks any existing behavior, Phase 2 will compound the bug — so Phase 1 has its own green-tests gate and commits independently.

### Task 1 — `TrackActionsDelegate` class + tests

**Why this phase:** Need a working delegate before anything else can be migrated to it. TDD lets us lock the delegate's behavior first, then port VMs onto a known-good surface.

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt`
- Create: `core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateTest.kt`
- Modify: `core/media/build.gradle.kts`

- [ ] **Step 1.1 — Update `core/media/build.gradle.kts`**

Add the module deps and test deps needed for the delegate + tests. The `core/media` module currently doesn't depend on `data/download` or `core/data`; the delegate needs both.

```kotlin
dependencies {
    // existing deps...
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":data:download"))
    implementation(project(":core:data"))
    implementation(project(":core:auth"))

    // Hilt (if not already present)
    implementation("com.google.dagger:hilt-android:2.53")
    ksp("com.google.dagger:hilt-compiler:2.53")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
}
```

**Read first:** the existing `core/media/build.gradle.kts` to confirm which of these are already declared. Only add missing lines; don't duplicate. If `core/media` already uses the `convention.android.library.compose` plugin, KSP may already be wired.

Commit:

```bash
git add core/media/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(core-media): add deps needed for TrackActionsDelegate

Prepares the module for the shared preview+download delegate.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 1.2 — Write the first failing test: preview cache hit**

Create `core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateTest.kt` with skeleton + first test. This pins the "preview cache hit → playUrl directly, no extractor call" behavior that exists today in `SearchViewModel.previewTrack`.

```kotlin
package com.stash.core.media.actions

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import app.cash.turbine.test
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.QualityTier
import com.stash.data.download.preview.PreviewUrlCache
import com.stash.data.download.preview.PreviewUrlExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private fun delegate(
        previewPlayer: PreviewPlayer = stubPreviewPlayer(),
        urlExtractor: PreviewUrlExtractor = mock(),
        urlCache: PreviewUrlCache = PreviewUrlCache(),
        downloadExecutor: DownloadExecutor = mock(),
        trackDao: TrackDao = mock(),
        fileOrganizer: FileOrganizer = mock(),
        qualityPrefs: QualityPreferencesManager = mock { on { qualityTier } doReturn flowOf(QualityTier.MP3_128) },
        musicRepository: MusicRepository = mock(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
    ) = TrackActionsDelegate(
        previewPlayer, urlExtractor, urlCache, downloadExecutor,
        trackDao, fileOrganizer, qualityPrefs, musicRepository,
    ).also { it.bindToScope(scope) }

    private fun stubPreviewPlayer(): PreviewPlayer = mock {
        on { previewState } doReturn MutableStateFlow(PreviewState.Idle).asStateFlow()
        on { playerErrors } doReturn MutableSharedFlow()
    }

    @Test
    fun `previewTrack with cached URL plays directly without calling extractor`() = runTest {
        val urlCache = PreviewUrlCache().also { it["v1"] = "https://warm.example/x" }
        val extractor = mock<PreviewUrlExtractor>()
        val player = stubPreviewPlayer()
        val d = delegate(previewPlayer = player, urlExtractor = extractor, urlCache = urlCache)

        d.previewTrack("v1")
        advanceUntilIdle()

        verify(player).playUrl(eq("v1"), eq("https://warm.example/x"))
        verify(extractor, never()).extractStreamUrl(any())
    }
}
```

**Note on imports:** `doReturn` comes from `org.mockito.kotlin.doReturn`. Add it to the import list alongside the others.

- [ ] **Step 1.3 — Run the test and verify it fails for the right reason**

```bash
./gradlew :core:media:testDebugUnitTest --tests com.stash.core.media.actions.TrackActionsDelegateTest
```

Expected: FAIL with "unresolved reference TrackActionsDelegate" — the class doesn't exist yet.

- [ ] **Step 1.4 — Write the minimal `TrackActionsDelegate` to make the test pass**

Create `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt`:

```kotlin
package com.stash.core.media.actions

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import com.stash.data.download.preview.PreviewUrlCache
import com.stash.data.download.preview.PreviewUrlExtractor
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared preview + download actions for any screen that renders track rows.
 *
 * Consolidates the preview/download wiring that was previously duplicated across
 * [com.stash.feature.search.SearchViewModel] and
 * [com.stash.feature.search.ArtistProfileViewModel]. A new delegate instance is
 * created per VM (`@ViewModelScoped`) so two screens open at once don't share
 * `downloadingIds` / `previewLoading` state; the underlying 8 singletons (player,
 * extractor, executor, etc.) are shared.
 *
 * **Lifecycle contract:** callers must invoke [bindToScope] exactly once in their
 * VM's `init` block before calling any other method. A second [bindToScope] call
 * throws [IllegalStateException]. Flows return their initial empty values until
 * bound.
 */
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
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    private val _previewLoadingId = MutableStateFlow<String?>(null)
    val previewLoadingId: StateFlow<String?> = _previewLoadingId.asStateFlow()

    private var boundScope: CoroutineScope? = null
    private var lastPreviewVideoId: String? = null
    private var lastPreviewStartedAt: Long = 0L

    /**
     * Binds the delegate to the calling VM's [scope]. Must be called exactly once
     * during VM init, before any other method. Starts the internal player-error
     * collector on [scope] so structured cancellation cleans it up on onCleared.
     */
    fun bindToScope(scope: CoroutineScope) {
        check(boundScope == null) { "TrackActionsDelegate.bindToScope called twice" }
        boundScope = scope
        scope.launch {
            previewPlayer.playerErrors.collect { event ->
                onPreviewError(event.videoId, event.error)
            }
        }
    }

    private fun scope(): CoroutineScope =
        checkNotNull(boundScope) { "TrackActionsDelegate used before bindToScope" }

    fun previewTrack(videoId: String) {
        previewPlayer.stop()
        scope().launch {
            _previewLoadingId.value = videoId
            try {
                val url = previewUrlCache[videoId]
                    ?: previewUrlExtractor.extractStreamUrl(videoId).also {
                        previewUrlCache[videoId] = it
                    }
                lastPreviewVideoId = videoId
                lastPreviewStartedAt = SystemClock.elapsedRealtime()
                previewPlayer.playUrl(videoId, url)
                _previewLoadingId.value = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Preview failed for videoId=$videoId", e)
                _previewLoadingId.value = null
                _userMessages.emit("Couldn't load preview.")
                previewPlayer.stop()
            }
        }
    }

    fun stopPreview() {
        previewPlayer.stop()
        _previewLoadingId.value = null
    }

    fun onPreviewError(videoId: String, error: PlaybackException) {
        if (!isIoError(error)) return
        if (videoId != lastPreviewVideoId) return
        val elapsed = SystemClock.elapsedRealtime() - lastPreviewStartedAt
        if (elapsed > RETRY_WINDOW_MS) return

        scope().launch {
            _previewLoadingId.value = videoId
            try {
                val retryUrl = previewUrlExtractor.extractViaYtDlpForRetry(videoId)
                previewUrlCache[videoId] = retryUrl
                previewPlayer.playUrl(videoId, retryUrl)
                _previewLoadingId.value = null
                Log.d(TAG, "yt-dlp retry SUCCESS for $videoId after InnerTube error $error")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "yt-dlp retry FAILED for $videoId", t)
                _previewLoadingId.value = null
                _userMessages.emit("Couldn't load preview.")
            }
        }
    }

    fun downloadTrack(item: TrackItem) {
        if (item.videoId in _downloadingIds.value) return
        if (item.videoId in _downloadedIds.value) return
        _downloadingIds.update { it + item.videoId }

        scope().launch {
            try {
                val url = "https://www.youtube.com/watch?v=${item.videoId}"
                val qualityTier = qualityPrefs.qualityTier.first()
                val qualityArgs = qualityTier.toYtDlpArgs()
                val tempDir = fileOrganizer.getTempDir()
                val tempFilename = "actions_${item.videoId}"

                when (val result = downloadExecutor.download(
                    url = url,
                    outputDir = tempDir,
                    filename = tempFilename,
                    qualityArgs = qualityArgs,
                )) {
                    is DownloadResult.Success -> handleDownloadSuccess(result, item)
                    is DownloadResult.YtDlpError -> {
                        Log.e(TAG, "Download failed for ${item.title}: ${result.message.take(100)}")
                        markDownloadFailed(item.videoId)
                    }
                    is DownloadResult.NoOutput -> {
                        Log.e(TAG, "Download produced no output for ${item.title}")
                        markDownloadFailed(item.videoId)
                    }
                    is DownloadResult.Error -> {
                        Log.e(TAG, "Download error for ${item.title}: ${result.message}")
                        markDownloadFailed(item.videoId)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Download error for ${item.title}", e)
                markDownloadFailed(item.videoId)
            }
        }
    }

    private suspend fun handleDownloadSuccess(
        result: DownloadResult.Success,
        item: TrackItem,
    ) {
        val finalFile = fileOrganizer.getTrackFile(
            artist = item.artist,
            album = null,
            title = item.title,
            format = result.file.extension,
        )
        result.file.copyTo(finalFile, overwrite = true)
        result.file.delete()

        val track = Track(
            title = item.title,
            artist = item.artist,
            durationMs = (item.durationSeconds * 1000).toLong(),
            source = MusicSource.YOUTUBE,
            youtubeId = item.videoId,
            filePath = finalFile.absolutePath,
            fileSizeBytes = finalFile.length(),
            isDownloaded = true,
            albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(item.thumbnailUrl),
        )
        musicRepository.insertTrack(track)

        _downloadingIds.update { it - item.videoId }
        _downloadedIds.update { it + item.videoId }
    }

    private fun markDownloadFailed(videoId: String) {
        _downloadingIds.update { it - videoId }
    }

    /**
     * Cross-reference [videoIds] against the local DB and update [downloadedIds]
     * so already-downloaded tracks show the green checkmark on screen. Callers
     * supply the list of ids visible on screen (not all ids in the DB).
     */
    suspend fun refreshDownloadedIds(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        val downloaded = videoIds.filter { id ->
            trackDao.findByYoutubeId(id)?.isDownloaded == true
        }.toSet()
        _downloadedIds.update { it + downloaded }
    }

    /** Called by owning VM's onCleared. Stops preview; boundScope auto-cancels. */
    fun onOwnerCleared() {
        previewPlayer.stop()
    }

    private fun isIoError(error: PlaybackException): Boolean =
        error.errorCode in 2000..2999

    companion object {
        private const val TAG = "TrackActionsDelegate"
        private const val RETRY_WINDOW_MS = 3_000L
    }
}

/**
 * Minimal track identity needed to initiate a download. A light-weight stand-in
 * that both `SearchResultItem` (feature/search) and any future caller can map to.
 */
data class TrackItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Double,
    val thumbnailUrl: String?,
)
```

**Note:** `TrackItem` is a new DTO at `core/media/actions` so the delegate doesn't depend on `feature/search`'s `SearchResultItem` (wrong direction in module graph). Both `SearchViewModel` and `ArtistProfileViewModel` will convert their `SearchResultItem` to `TrackItem` at the call site — a one-line `.let { TrackItem(...) }`.

- [ ] **Step 1.5 — Run the first test and verify pass**

```bash
./gradlew :core:media:testDebugUnitTest --tests com.stash.core.media.actions.TrackActionsDelegateTest
```

Expected: PASS (`BUILD SUCCESSFUL`, 1 test).

- [ ] **Step 1.6 — Add remaining tests one at a time (TDD loop)**

Add each test, run, verify it passes, commit only after all pass. Tests to add:

1. `previewTrack cache miss calls extractor then plays`
2. `previewTrack extractor failure emits userMessage and clears loading`
3. `onPreviewError within window triggers yt-dlp retry and plays on success`
4. `onPreviewError outside window is ignored`
5. `onPreviewError ignored when videoId differs from last preview`
6. `downloadTrack success inserts track and moves id from downloading to downloaded`
7. `downloadTrack YtDlpError marks failed and drops from downloading`
8. `downloadTrack skips if already downloading`
9. `refreshDownloadedIds adds only downloaded ids`
10. `bindToScope called twice throws IllegalStateException`
11. `methods throw before bindToScope is called`
12. `stopPreview clears previewLoadingId and stops player`

Template for a download-success test:

```kotlin
@Test
fun `downloadTrack success inserts track and flips state from downloading to downloaded`() = runTest {
    val executor = mock<DownloadExecutor>()
    val tempFile = java.io.File.createTempFile("t", ".mp3").apply { writeText("x") }
    val finalFile = java.io.File.createTempFile("final", ".mp3").apply { delete() }
    val fileOrganizer = mock<FileOrganizer> {
        onBlocking { getTempDir() } doReturn tempFile.parentFile
        on { getTrackFile(any(), anyOrNull(), any(), any()) } doReturn finalFile
    }
    val qualityPrefs = mock<QualityPreferencesManager> {
        on { qualityTier } doReturn flowOf(QualityTier.MP3_128)
    }
    val repo = mock<MusicRepository>()
    whenever(executor.download(any(), any(), any(), any()))
        .thenReturn(DownloadResult.Success(tempFile))

    val d = delegate(
        downloadExecutor = executor,
        fileOrganizer = fileOrganizer,
        qualityPrefs = qualityPrefs,
        musicRepository = repo,
    )

    d.downloadTrack(TrackItem("v1", "Title", "Artist", 180.0, null))

    // Optimistic flip
    assertEquals(setOf("v1"), d.downloadingIds.value)

    advanceUntilIdle()

    assertEquals(emptySet<String>(), d.downloadingIds.value)
    assertEquals(setOf("v1"), d.downloadedIds.value)
    verify(repo).insertTrack(any())
    finalFile.delete()
}
```

Run after each test is written:

```bash
./gradlew :core:media:testDebugUnitTest --tests com.stash.core.media.actions.TrackActionsDelegateTest
```

Expected after all: all tests PASS (12-13 tests).

- [ ] **Step 1.7 — Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt \
        core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateTest.kt
git commit -m "$(cat <<'EOF'
feat(core-media): add TrackActionsDelegate for shared preview+download

Consolidates the preview+download wiring duplicated across SearchViewModel
and ArtistProfileViewModel into one @ViewModelScoped class. Both VMs will
migrate onto it in follow-up commits; no behavior change yet.

Tests cover cache-hit fast path, extractor miss, yt-dlp retry window,
download success/failure, refreshDownloadedIds, and the bindToScope
lifecycle contract.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 — Migrate `SearchViewModel` onto `TrackActionsDelegate`

**Why this phase:** `SearchViewModel` is the simpler of the two migrations (no cache retry logic to preserve). Shipping it first validates the migration pattern before applying it to `ArtistProfileViewModel`.

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchUiState.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt`
- Modify: `feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt`
- Modify: `feature/search/build.gradle.kts` (add `:core:media` if not already — it is already included transitively via `:data:download`; verify before adding)

- [ ] **Step 2.1 — Shrink `SearchUiState`**

In `SearchUiState.kt`, remove the 3 delegate-owned fields. Current:

```kotlin
data class SearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.Idle,
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val previewLoading: String? = null,
)
```

New:

```kotlin
data class SearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.Idle,
)
```

- [ ] **Step 2.2 — Rewrite `SearchViewModel`**

Replace the constructor's 8 preview/download deps with a single `TrackActionsDelegate`:

```kotlin
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel @Inject constructor(
    private val api: YTMusicApiClient,
    private val prefetcher: PreviewPrefetcher,
    val delegate: TrackActionsDelegate,
) : ViewModel() {
    // companion object: KEEP — TAG, MIN_QUERY_LENGTH, DEBOUNCE_MS, PREFETCH_TOP_N.
    // REMOVE RETRY_WINDOW_MS (now in the delegate).
    // KEEP: queryFlow, _uiState/uiState, _userMessages/userMessages, runSearch, onQueryChanged, prefetchTopN.
    // REMOVE: previewTrack, stopPreview, downloadTrack, onPreviewError, handleDownloadSuccess, markDownloadFailed, isIoError, lastPreviewVideoId, lastPreviewStartedAt, playerErrors collector, downloading-related onCleared body.

    init {
        delegate.bindToScope(viewModelScope)

        viewModelScope.launch {
            queryFlow
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .flatMapLatest { q -> runSearch(q) }
                .collect { status -> _uiState.update { it.copy(status = status) } }
        }
    }

    // ... runSearch, onQueryChanged, prefetchTopN stay the same, BUT:
    //   - runSearch's refreshDownloadedIds block now calls `delegate.refreshDownloadedIds(videoIds)` instead of the old inline version.

    private suspend fun refreshDownloadedIds(sections: List<SearchResultSection>) {
        val videoIds = sections.flatMap { section ->
            when (section) {
                is SearchResultSection.Songs -> section.tracks.map { it.videoId }
                is SearchResultSection.Top -> (section.item as? TopResultItem.TrackTop)
                    ?.track?.videoId?.let { listOf(it) } ?: emptyList()
                else -> emptyList()
            }
        }
        delegate.refreshDownloadedIds(videoIds)
    }

    override fun onCleared() {
        super.onCleared()
        delegate.onOwnerCleared()
    }
}

// Public pass-through adapter for SearchScreen. Called from screen-side click
// handlers that previously called viewModel.previewTrack directly. Screen now
// calls vm.delegate.previewTrack / etc. — no pass-throughs needed.
internal fun TrackSummary.toTrackItem() = com.stash.core.media.actions.TrackItem(
    videoId = videoId,
    title = title,
    artist = artist,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
)
```

**Note:** The existing `TrackSummary.toSearchResultItem()` extension stays — the `PreviewDownloadRow` composable still takes a `SearchResultItem`. But the VM-side download call changes from `downloadTrack(t.toSearchResultItem())` to `delegate.downloadTrack(t.toTrackItem())`.

Do NOT keep a pass-through `fun downloadTrack(...)` on the VM. Make the screen call `vm.delegate.downloadTrack(...)` directly. The explicit delegate access at call sites makes the ownership clear; pass-throughs hide it.

- [ ] **Step 2.3 — Update `SearchScreen.kt` to read from the delegate**

In `SearchScreen.kt:88-126`, change:

```kotlin
val state by viewModel.uiState.collectAsStateWithLifecycle()
val previewState by viewModel.previewState.collectAsStateWithLifecycle()
```

to:

```kotlin
val state by viewModel.uiState.collectAsStateWithLifecycle()
val previewState by viewModel.delegate.previewState.collectAsStateWithLifecycle()
val downloadingIds by viewModel.delegate.downloadingIds.collectAsStateWithLifecycle()
val downloadedIds by viewModel.delegate.downloadedIds.collectAsStateWithLifecycle()
val previewLoadingId by viewModel.delegate.previewLoadingId.collectAsStateWithLifecycle()
```

And change the `SectionedResultsList` signature + call to pass those 3 new explicit values instead of reading them from `uiState`. Inside `SectionedResultsList`, update every reference from `uiState.downloadingIds` to `downloadingIds`, `uiState.downloadedIds` to `downloadedIds`, `uiState.previewLoading` to `previewLoadingId`.

Merge snackbar sources:

```kotlin
LaunchedEffect(viewModel) {
    kotlinx.coroutines.flow.merge(
        viewModel.userMessages,
        viewModel.delegate.userMessages,
    ).collect { msg -> snackbarHostState.showSnackbar(msg) }
}
```

The existing `viewModel.previewTrack(it)`, `viewModel.stopPreview()`, `viewModel.downloadTrack(...)` call sites become `viewModel.delegate.previewTrack(it)`, `viewModel.delegate.stopPreview()`, `viewModel.delegate.downloadTrack(t.toTrackItem())`.

Grep for all call sites after editing:

```bash
grep -n "viewModel.previewTrack\|viewModel.stopPreview\|viewModel.downloadTrack" feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt
```

Expected: no matches (all rewritten through `viewModel.delegate.*`).

- [ ] **Step 2.4 — Update `SearchViewModelTest.kt`**

Replace the 8 dep mocks with 1 `TrackActionsDelegate` mock:

```kotlin
private fun vmWith(
    api: YTMusicApiClient = mock(),
    prefetcher: PreviewPrefetcher = mock(),
    delegate: TrackActionsDelegate = mock {
        on { previewState } doReturn MutableStateFlow(PreviewState.Idle).asStateFlow()
        on { userMessages } doReturn MutableSharedFlow<String>().asSharedFlow()
        on { downloadingIds } doReturn MutableStateFlow<Set<String>>(emptySet()).asStateFlow()
        on { downloadedIds } doReturn MutableStateFlow<Set<String>>(emptySet()).asStateFlow()
        on { previewLoadingId } doReturn MutableStateFlow<String?>(null).asStateFlow()
    },
): SearchViewModel = SearchViewModel(api, prefetcher, delegate)
```

The 4 existing tests in the file:
- `onQueryChanged with blank query emits Idle` — keep unchanged.
- `onQueryChanged with short query emits Idle` — keep unchanged.
- `onQueryChanged triggers search after debounce` — keep unchanged.
- `keystroke cancels previous in-flight search` — keep unchanged; no delegate interaction needed.

Tests that exercised preview/download directly move to `TrackActionsDelegateTest` (done in Task 1).

- [ ] **Step 2.5 — Run and commit**

```bash
./gradlew :feature:search:testDebugUnitTest :app:assembleDebug
```

Expected: PASS. `SearchViewModelTest` still has 4 tests, all green.

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/SearchUiState.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt
git commit -m "$(cat <<'EOF'
refactor(search): migrate SearchViewModel onto TrackActionsDelegate

Removes 8 deps and ~150 LOC of duplicated preview+download code.
UiState shrinks to {query, status}; the screen reads per-track flags
directly from the delegate's flows. Snackbar host merges VM + delegate
user-message sources.

No behavior change on-device.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 — Migrate `ArtistProfileViewModel` onto `TrackActionsDelegate`

**Why this phase:** Second VM migration. Once this lands, the delegate has two users and the path for `AlbumDiscoveryViewModel` to be the third is mechanical.

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/PopularTracksSection.kt`
- Modify: `feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt`

- [ ] **Step 3.1 — Shrink `ArtistProfileUiState`**

Remove `downloadingIds`, `downloadedIds`, `previewLoading` fields. Keep `hero`, `popular`, `albums`, `singles`, `related`, `status`.

- [ ] **Step 3.2 — Rewrite `ArtistProfileViewModel`**

Similar shape to the SearchViewModel migration. Keep:
- `artistCache`, `prefetcher` deps.
- `cacheJob`, `observeCache()`, `retry()`, `apply()`, `prefetchKicked`, `refreshDownloadedIds` (which now delegates to `delegate.refreshDownloadedIds`).
- `_userMessages`, `userMessages` SharedFlow (for the "Couldn't load artist — tap Retry." + "Couldn't refresh — showing cached." messages the VM emits).

Remove:
- 8 preview/download deps.
- `previewTrack`, `stopPreview`, `downloadTrack`, `onPreviewError`, `handleDownloadSuccess`, `markDownloadFailed`, `isIoError`.
- `lastPreviewVideoId`, `lastPreviewStartedAt`.
- `previewPlayer.playerErrors` init collector.
- `previewState` exposure (screen reads `vm.delegate.previewState` directly).
- `RETRY_WINDOW_MS` companion constant.

`init` becomes:

```kotlin
init {
    val t0 = SystemClock.elapsedRealtime()
    delegate.bindToScope(viewModelScope)
    PerfLog.d { "ArtistProfile hero first-frame nav-args (name=$initialName)" }
    cacheJob = viewModelScope.launch { observeCache(t0) }
}
```

`onCleared` stays — adjust body:

```kotlin
override fun onCleared() {
    super.onCleared()
    delegate.onOwnerCleared()
    prefetcher.cancelAll()
}
```

- [ ] **Step 3.3 — Update `ArtistProfileScreen.kt` + `PopularTracksSection.kt`**

**Pre-step:** grep `PopularTracksSection.kt` to confirm Task 12 already wired external flags into its signature:

```bash
grep -n "previewState\|downloadingIds\|previewLoadingId" feature/search/src/main/kotlin/com/stash/feature/search/PopularTracksSection.kt
```

Expected: matches on each of those parameter names. If ANY don't show up, Task 12 left something behind and `PopularTracksSection` needs its signature updated too — stop and add those params before proceeding.

Assuming the grep returns the 7 params Task 12 added: in `ArtistProfileScreen`, read the 4 delegate flows the same way `SearchScreen` does (Step 2.3). Merge `vm.userMessages` and `vm.delegate.userMessages` in the `LaunchedEffect`. Change `PopularTracksSection` call site to pass the delegate-sourced `downloadingIds`/etc. No structural change to the composable body itself.

Also swap the `onPreview = { vm.previewTrack(track.videoId) }`, `onStopPreview = vm::stopPreview`, `onDownload = { vm.downloadTrack(it) }` patterns to `vm.delegate.previewTrack(...)`, `vm.delegate::stopPreview`, `vm.delegate.downloadTrack(it.toTrackItem())`.

- [ ] **Step 3.4 — Update `ArtistProfileViewModelTest.kt`**

Same pattern as Step 2.4: replace 8 dep mocks with 1 `TrackActionsDelegate` mock. 5 existing tests stay as-is; the retry + downloadTrack tests that relied on direct VM preview/download internals either:
- Stay as VM tests if they're still reasonable (`retry flips status to Loading and re-subscribes` — yes, still VM-owned).
- Move to `TrackActionsDelegateTest` if they were testing preview/download flow (`downloadTrack adds id to downloadingIds optimistically` — this is already covered by delegate tests; delete from VM test class).

Expected final test count in `ArtistProfileViewModelTest`: 4 (drop the downloadTrack test; keep the 4 others: initial hero, prefetch kick, stale refresh, retry).

- [ ] **Step 3.5 — Run and commit**

```bash
./gradlew :feature:search:testDebugUnitTest :core:media:testDebugUnitTest :app:assembleDebug
```

Expected: all green.

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/PopularTracksSection.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt
git commit -m "$(cat <<'EOF'
refactor(search): migrate ArtistProfileViewModel onto TrackActionsDelegate

Second and final migration of the Option-A-duplicated preview+download
code. VM now owns only the artist-specific surface (cache subscription,
retry, nav-arg hero, prefetch kick).

No behavior change on-device.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4 — Extract `DiscoveryErrorCard` to `core/ui`

**Why this phase:** `ArtistProfileErrorCard` (Task 12) + the upcoming `AlbumDiscoveryErrorCard` are visually identical. Second caller justifies extraction. Keeps the Phase 1 reshuffle contained before Phase 2 starts building on top.

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/DiscoveryErrorCard.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt`

- [ ] **Step 4.1 — Create the shared composable**

```kotlin
package com.stash.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable full-width error card for Discovery screens.
 *
 * Shows an icon + title + body message + "Retry" button. Used by
 * ArtistProfileScreen and AlbumDiscoveryScreen when a cold-miss fetch fails
 * and there's no cached copy to fall back to.
 */
@Composable
fun DiscoveryErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Retry")
        }
    }
}
```

- [ ] **Step 4.2 — Rewire `ArtistProfileScreen.kt`**

Delete the local `ArtistProfileErrorCard` composable (added in Task 12). Replace its single usage with:

```kotlin
import com.stash.core.ui.components.DiscoveryErrorCard

// ...at the existing call site:
DiscoveryErrorCard(
    message = status.message,
    onRetry = vm::retry,
)
```

- [ ] **Step 4.3 — Run and commit**

```bash
./gradlew :feature:search:testDebugUnitTest :app:assembleDebug
```

Expected: green.

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/DiscoveryErrorCard.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt
git commit -m "$(cat <<'EOF'
refactor(core-ui): extract DiscoveryErrorCard from ArtistProfileScreen

Second caller (AlbumDiscoveryScreen) is about to need the same widget.
Moves it to core/ui so both screens share one implementation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

**Phase 1 Complete.** The app should still build, install, and behave identically to pre-Phase-1. All existing tests green. 12-13 new tests in `TrackActionsDelegateTest` added net; 0 tests lost. Install on device and run the smoke checklist below before starting Phase 2:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew :app:installDebug
```

**Phase 1 smoke-test checklist (verify NO user-visible change from pre-Phase-1 behavior):**
1. Search for "frusciante" — top-result card renders; tap its preview button → audio plays; tap its download button → spinner then green checkmark.
2. Search result Songs section — same preview + download behavior on a song row.
3. Tap an artist → Artist Profile loads, hero paints immediately, Popular tracks render with working preview + download controls.
4. Tap an album on Artist Profile — **still routes to the library's AlbumDetailScreen (Bug #1 is NOT yet fixed).** That's expected; Phase 2 rewires it.
5. Force an Artist Profile cache-miss failure (airplane mode, new artist) — error card with Retry button appears.

If ANY of these 5 regress, fix before starting Phase 2.

**Phase 1 is independently mergeable.** If Phase 2 stalls or runs long, the Phase 1 commits (Tasks 1-4) can merge to master on their own — the app gains no user-visible feature but loses ~300 LOC of duplication and gains the delegate foundation. If that path is taken, cut a new branch for Phase 2 off the merged Phase 1.

---

## Phase 2 — Album Discovery (user-visible feature)

### Task 5 — `AlbumDetail` DTO + `AlbumResponseParser` + `getAlbum(browseId)` + fixtures + API tests

**Why this phase:** The foundation layer. `AlbumCache` and `AlbumDiscoveryViewModel` both depend on `getAlbum` existing and returning a well-formed `AlbumDetail`. Parser tests lock the shape so the VM tests can mock just the API surface.

**Files:**
- Create: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/AlbumDetail.kt`
- Create: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/AlbumResponseParser.kt`
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Modify: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`
- Create: `data/ytmusic/src/test/resources/fixtures/album_rich.json`
- Create: `data/ytmusic/src/test/resources/fixtures/album_sparse.json`
- Create: `data/ytmusic/src/test/resources/fixtures/album_malformed.json`

- [ ] **Step 5.1 — Create `AlbumDetail.kt`**

```kotlin
package com.stash.data.ytmusic.model

import kotlinx.serialization.Serializable

/**
 * Full album detail fetched from InnerTube `browse(albumBrowseId)`.
 *
 * `tracks` is the full tracklist in the order InnerTube returns it.
 * `moreByArtist` is the "More by this artist" shelf at the bottom of the
 * YouTube Music album page; may be empty for compilations or unknown artists.
 */
@Serializable
data class AlbumDetail(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val thumbnailUrl: String?,
    val year: String?,
    val tracks: List<TrackSummary>,
    val moreByArtist: List<AlbumSummary>,
)
```

- [ ] **Step 5.2 — Capture fixture JSON**

Generate the three fixtures. You can either:
- (Easy path) construct synthetic JSON by hand — less realistic, faster.
- (Better path) hit InnerTube live with a dev tool (Postman / HTTPie) using the same InnerTubeClient POST body the app uses (`browseId` of a known album like `MPREb_KTc_GnJIiRb` for a Frusciante album), save the response, redact nothing.

**Hand-construct synthetic `album_rich.json`** — minimal but covers all fields the parser needs to extract:

```json
{
  "header": {
    "musicDetailHeaderRenderer": {
      "title": { "runs": [{ "text": "Curtains" }] },
      "subtitle": {
        "runs": [
          { "text": "Album" },
          { "text": " • " },
          { "text": "John Frusciante", "navigationEndpoint": { "browseEndpoint": { "browseId": "UCxxx_artist" } } },
          { "text": " • " },
          { "text": "2005" }
        ]
      },
      "thumbnail": {
        "croppedSquareThumbnailRenderer": {
          "thumbnail": {
            "thumbnails": [ { "url": "https://lh3.googleusercontent.com/album_art", "width": 600, "height": 600 } ]
          }
        }
      }
    }
  },
  "contents": {
    "singleColumnBrowseResultsRenderer": {
      "tabs": [{
        "tabRenderer": {
          "content": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "track1_v" },
                          "flexColumns": [
                            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "The Past Recedes" }] } } },
                            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "John Frusciante" }] } } }
                          ],
                          "fixedColumns": [{
                            "musicResponsiveListItemFixedColumnRenderer": { "text": { "runs": [{ "text": "4:39" }] } }
                          }]
                        }
                      }
                    ]
                  }
                },
                {
                  "musicCarouselShelfRenderer": {
                    "header": {
                      "musicCarouselShelfBasicHeaderRenderer": {
                        "title": { "runs": [{ "text": "More from John Frusciante" }] }
                      }
                    },
                    "contents": [
                      {
                        "musicTwoRowItemRenderer": {
                          "title": { "runs": [{ "text": "Inside of Emptiness" }] },
                          "subtitle": { "runs": [{ "text": "Album" }, { "text": " • " }, { "text": "2004" }] },
                          "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_other_album" } },
                          "thumbnailRenderer": {
                            "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/more_album", "width": 600, "height": 600 }] } }
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
      }]
    }
  }
}
```

**Tip:** If the exact JSON shape turns out to differ from what InnerTube currently returns, capture a real response by running the existing `getArtist` against any artist and looking at what `InnerTubeClient.browse` returns (you'll see the JSON in the logcat `Network` filter if the app is run with verbose logging). Update the fixture until the parser you write in Step 5.3 extracts all fields correctly.

Add `album_sparse.json` with the same structure minus `year` run, empty `musicCarouselShelfRenderer.contents`, and 2 tracks.

Add `album_malformed.json` with the header but no `musicShelfRenderer` at all (simulates a region-blocked album).

- [ ] **Step 5.3 — TDD: write a failing parser test**

Add to `YTMusicApiClientTest.kt`:

```kotlin
@Test
fun `getAlbum parses rich fixture correctly`() = runTest {
    val json = readFixture("album_rich.json")
    val client = clientWithMockBrowseResponse(json)

    val result = client.getAlbum("MPREb_KTc_GnJIiRb")

    assertEquals("Curtains", result.title)
    assertEquals("John Frusciante", result.artist)
    assertEquals("UCxxx_artist", result.artistId)
    assertEquals("2005", result.year)
    assertEquals(1, result.tracks.size)
    assertEquals("The Past Recedes", result.tracks[0].title)
    assertEquals("track1_v", result.tracks[0].videoId)
    assertEquals(279.0, result.tracks[0].durationSeconds, 0.01)
    assertEquals(1, result.moreByArtist.size)
    assertEquals("Inside of Emptiness", result.moreByArtist[0].title)
    assertEquals("MPREb_other_album", result.moreByArtist[0].id)
}
```

`readFixture` + `clientWithMockBrowseResponse` already exist in `YTMusicApiClientTest.kt` from the Search Overhaul work — follow the same pattern used for `getArtist parses rich fixture`.

- [ ] **Step 5.4 — Run and verify failure**

```bash
./gradlew :data:ytmusic:testDebugUnitTest --tests com.stash.data.ytmusic.YTMusicApiClientTest.getAlbum\ parses\ rich\ fixture\ correctly
```

Expected: FAIL (`unresolved reference getAlbum` or similar).

- [ ] **Step 5.5 — Write the parser + API method**

Create `AlbumResponseParser.kt`. Use `ArtistResponseParser.kt` as a template — same shape, same helpers (`ResponseParserHelpers.kt` already has `parseTextRun`, `thumbnailUrlFrom`, `parseDurationToSeconds`, etc.).

Skeleton:

```kotlin
package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object AlbumResponseParser {
    fun parse(browseId: String, response: JsonObject): AlbumDetail {
        val header = response["header"]?.jsonObject
            ?.get("musicDetailHeaderRenderer")?.jsonObject
            ?: error("Album response missing musicDetailHeaderRenderer")

        val title = parseTextRun(header["title"]) ?: "Unknown album"
        val (artist, artistId, year) = parseSubtitle(header["subtitle"])
        val thumbnailUrl = thumbnailUrlFrom(
            header["thumbnail"]?.jsonObject
                ?.get("croppedSquareThumbnailRenderer")?.jsonObject
                ?.get("thumbnail"),
            preferredSize = 600,
        )

        val shelves = response["contents"]?.jsonObject
            ?.get("singleColumnBrowseResultsRenderer")?.jsonObject
            ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("tabRenderer")?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("sectionListRenderer")?.jsonObject
            ?.get("contents")?.jsonArray
            ?: emptyList<JsonElement>()

        val tracks = parseTrackShelf(shelves)
        val moreByArtist = parseMoreCarousel(shelves)

        return AlbumDetail(
            id = browseId,
            title = title,
            artist = artist,
            artistId = artistId,
            thumbnailUrl = thumbnailUrl,
            year = year,
            tracks = tracks,
            moreByArtist = moreByArtist,
        )
    }

    private data class Subtitle(val artist: String, val artistId: String?, val year: String?)

    private fun parseSubtitle(subtitle: JsonElement?): Subtitle {
        // Runs: ["Album", " • ", {artistName+browseId}, " • ", "2005"]
        // Extract the artist run (has browseEndpoint.browseId) and the year run (last if 4-digit).
        val runs = subtitle?.jsonObject?.get("runs")?.jsonArray ?: return Subtitle("Unknown artist", null, null)
        var artist = "Unknown artist"
        var artistId: String? = null
        var year: String? = null
        for (run in runs) {
            val obj = run.jsonObject
            val text = obj["text"]?.jsonPrimitive?.content ?: continue
            val browseId = obj["navigationEndpoint"]?.jsonObject
                ?.get("browseEndpoint")?.jsonObject
                ?.get("browseId")?.jsonPrimitive?.content
            if (browseId != null && browseId.startsWith("UC")) {
                artist = text
                artistId = browseId
            } else if (text.matches(Regex("\\d{4}"))) {
                year = text
            }
        }
        return Subtitle(artist, artistId, year)
    }

    private fun parseTrackShelf(shelves: List<JsonElement>): List<TrackSummary> {
        val shelf = shelves.firstOrNull { it.jsonObject.containsKey("musicShelfRenderer") }
            ?.jsonObject?.get("musicShelfRenderer")?.jsonObject
            ?: return emptyList()
        val contents = shelf["contents"]?.jsonArray ?: return emptyList()
        return contents.mapNotNull { entry ->
            val item = entry.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: return@mapNotNull null
            val videoId = item["playlistItemData"]?.jsonObject?.get("videoId")?.jsonPrimitive?.content
                ?: return@mapNotNull null
            val flexColumns = item["flexColumns"]?.jsonArray ?: return@mapNotNull null
            val title = flexColumns.getOrNull(0)?.let { parseFlexColumnText(it) } ?: return@mapNotNull null
            val artistText = flexColumns.getOrNull(1)?.let { parseFlexColumnText(it) } ?: ""
            val durationStr = item["fixedColumns"]?.jsonArray?.firstOrNull()?.let {
                parseFixedColumnText(it)
            }
            val durationSeconds = durationStr?.let { parseDurationToSeconds(it) } ?: 0.0
            TrackSummary(
                videoId = videoId,
                title = title,
                artist = artistText,
                album = null,
                durationSeconds = durationSeconds,
                thumbnailUrl = null,
            )
        }
    }

    private fun parseMoreCarousel(shelves: List<JsonElement>): List<AlbumSummary> {
        val carousel = shelves.firstOrNull { it.jsonObject.containsKey("musicCarouselShelfRenderer") }
            ?.jsonObject?.get("musicCarouselShelfRenderer")?.jsonObject
            ?: return emptyList()
        val contents = carousel["contents"]?.jsonArray ?: return emptyList()
        return contents.mapNotNull { entry ->
            val item = entry.jsonObject["musicTwoRowItemRenderer"]?.jsonObject ?: return@mapNotNull null
            val title = parseTextRun(item["title"]) ?: return@mapNotNull null
            val browseId = item["navigationEndpoint"]?.jsonObject
                ?.get("browseEndpoint")?.jsonObject
                ?.get("browseId")?.jsonPrimitive?.content ?: return@mapNotNull null
            val subtitleRuns = item["subtitle"]?.jsonObject?.get("runs")?.jsonArray
            val year = subtitleRuns?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.firstOrNull { it.matches(Regex("\\d{4}")) }
            val thumbnailUrl = thumbnailUrlFrom(
                item["thumbnailRenderer"]?.jsonObject
                    ?.get("musicThumbnailRenderer")?.jsonObject
                    ?.get("thumbnail"),
                preferredSize = 400,
            )
            AlbumSummary(
                id = browseId,
                title = title,
                artist = "",   // InnerTube doesn't include artist on this nested row — leave blank
                thumbnailUrl = thumbnailUrl,
                year = year,
            )
        }
    }

    private fun parseFlexColumnText(entry: JsonElement): String? =
        entry.jsonObject["musicResponsiveListItemFlexColumnRenderer"]?.jsonObject
            ?.get("text")?.jsonObject
            ?.get("runs")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content

    private fun parseFixedColumnText(entry: JsonElement): String? =
        entry.jsonObject["musicResponsiveListItemFixedColumnRenderer"]?.jsonObject
            ?.get("text")?.jsonObject
            ?.get("runs")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
}
```

**Note:** `parseTextRun`, `thumbnailUrlFrom`, `parseDurationToSeconds` are already in `ResponseParserHelpers.kt`. Don't duplicate.

Add to `YTMusicApiClient.kt` (append after `getArtist`):

```kotlin
/**
 * Fetches the full album page for [browseId] from InnerTube. Returns the
 * album header (title, artist, year, cover), full tracklist, and the
 * "More by this artist" shelf in a single round-trip.
 *
 * Throws on network/parse failures; callers should wrap in try/catch.
 */
suspend fun getAlbum(browseId: String): AlbumDetail = withContext(Dispatchers.IO) {
    val response = innerTubeClient.browse(browseId)
    AlbumResponseParser.parse(browseId, response)
}
```

- [ ] **Step 5.6 — Run and verify pass**

```bash
./gradlew :data:ytmusic:testDebugUnitTest --tests com.stash.data.ytmusic.YTMusicApiClientTest.getAlbum\ parses\ rich\ fixture\ correctly
```

Expected: PASS.

- [ ] **Step 5.7 — Add sparse + malformed tests**

```kotlin
@Test
fun `getAlbum parses sparse fixture with empty moreByArtist and no year`() = runTest {
    val json = readFixture("album_sparse.json")
    val result = clientWithMockBrowseResponse(json).getAlbum("MPREb_sparse")
    assertNull(result.year)
    assertTrue(result.moreByArtist.isEmpty())
    assertEquals(2, result.tracks.size)
}

@Test
fun `getAlbum with malformed response returns empty tracks not throw`() = runTest {
    val json = readFixture("album_malformed.json")
    val result = clientWithMockBrowseResponse(json).getAlbum("MPREb_missing")
    assertTrue(result.tracks.isEmpty())
}
```

Run:

```bash
./gradlew :data:ytmusic:testDebugUnitTest --tests com.stash.data.ytmusic.YTMusicApiClientTest
```

Expected: PASS (3 new tests + existing getArtist/searchAll tests).

- [ ] **Step 5.8 — Commit**

```bash
git add data/ytmusic/
git commit -m "$(cat <<'EOF'
feat(ytmusic): add getAlbum(browseId) InnerTube method + parser

New AlbumDetail DTO + AlbumResponseParser + YTMusicApiClient.getAlbum.
Returns the album header, full tracklist, and "More by this artist"
shelf in one round-trip. Tests cover rich, sparse, and malformed
response fixtures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6 — `AlbumCache` + tests

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/cache/AlbumCache.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/cache/AlbumCacheTest.kt`

- [ ] **Step 6.1 — Write the first failing test**

```kotlin
package com.stash.core.data.cache

import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumCacheTest {

    private fun detail(id: String) = AlbumDetail(
        id = id, title = "T", artist = "A", artistId = null,
        thumbnailUrl = null, year = null, tracks = emptyList(), moreByArtist = emptyList(),
    )

    @Test
    fun `miss fetches and caches`() = runTest {
        val api = mock<YTMusicApiClient>()
        whenever(api.getAlbum(eq("X"))).thenReturn(detail("X"))
        val cache = AlbumCache(api)

        val result = cache.get("X")

        assertEquals("X", result.id)
        verify(api).getAlbum(eq("X"))
        verifyNoMoreInteractions(api)
    }
}
```

- [ ] **Step 6.2 — Run and fail**

```bash
./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.cache.AlbumCacheTest
```

Expected: FAIL (unresolved `AlbumCache`).

- [ ] **Step 6.3 — Implement `AlbumCache`**

```kotlin
package com.stash.core.data.cache

import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory album cache with per-key mutex to prevent duplicate concurrent
 * fetches. Entries expire after [TTL_MS]. No Room persistence — albums are
 * near-static; the tradeoff for not surviving process death is zero migration
 * cost and zero on-disk state.
 */
@Singleton
class AlbumCache @Inject constructor(
    private val api: YTMusicApiClient,
) {
    private data class Entry(val detail: AlbumDetail, val fetchedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(browseId: String): AlbumDetail {
        val cached = entries[browseId]
        if (cached != null && !isStale(cached)) return cached.detail

        // Serialize per-key fetches so concurrent gets for the same album
        // result in exactly one network call.
        val lock = keyLocks.computeIfAbsent(browseId) { Mutex() }
        return lock.withLock {
            // Re-check after acquiring the lock — maybe someone else filled it.
            val afterLock = entries[browseId]
            if (afterLock != null && !isStale(afterLock)) return@withLock afterLock.detail

            val fresh = api.getAlbum(browseId)
            entries[browseId] = Entry(fresh, now())
            fresh
        }
    }

    fun invalidate(browseId: String) {
        entries.remove(browseId)
    }

    private fun isStale(entry: Entry): Boolean =
        now() - entry.fetchedAt > TTL_MS

    internal fun now(): Long = System.currentTimeMillis()

    companion object {
        internal const val TTL_MS = 30 * 60_000L
    }
}
```

- [ ] **Step 6.4 — Run and pass the first test**

```bash
./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.cache.AlbumCacheTest
```

Expected: PASS.

- [ ] **Step 6.5 — Add remaining tests**

```kotlin
@Test
fun `hit within TTL returns cached without second network call`() = runTest {
    val api = mock<YTMusicApiClient>()
    whenever(api.getAlbum(eq("X"))).thenReturn(detail("X"))
    val cache = AlbumCache(api)

    val first = cache.get("X")
    val second = cache.get("X")

    assertSame(first, second)
    verify(api).getAlbum(eq("X"))
    verifyNoMoreInteractions(api)
}

@Test
fun `hit past TTL refetches`() = runTest {
    val api = mock<YTMusicApiClient>()
    val first = detail("X")
    val second = first.copy(title = "T2")
    whenever(api.getAlbum(eq("X"))).thenReturn(first, second)

    // Inject a clock we can advance.
    var fakeNow = 0L
    val cache = object : AlbumCache(api) { override fun now() = fakeNow }

    val got1 = cache.get("X")
    fakeNow = AlbumCache.TTL_MS + 1
    val got2 = cache.get("X")

    assertEquals("T", got1.title)
    assertEquals("T2", got2.title)
    verify(api, org.mockito.kotlin.times(2)).getAlbum(eq("X"))
}

@Test
fun `invalidate evicts`() = runTest {
    val api = mock<YTMusicApiClient>()
    val a = detail("X")
    val b = a.copy(title = "T2")
    whenever(api.getAlbum(eq("X"))).thenReturn(a, b)
    val cache = AlbumCache(api)

    cache.get("X")
    cache.invalidate("X")
    val afterInvalidate = cache.get("X")

    assertEquals("T2", afterInvalidate.title)
}

@Test
fun `concurrent gets for same key result in one fetch`() = runTest {
    val api = mock<YTMusicApiClient>()
    val hang = kotlinx.coroutines.CompletableDeferred<AlbumDetail>()
    // `getAlbum` is a suspend function; use doSuspendableAnswer (NOT thenAnswer,
    // which can't call .await() on a CompletableDeferred).
    org.mockito.kotlin.doSuspendableAnswer { hang.await() }.whenever(api).getAlbum(eq("X"))
    val cache = AlbumCache(api)

    val j1 = async { cache.get("X") }
    val j2 = async { cache.get("X") }
    hang.complete(detail("X"))

    j1.await(); j2.await()
    verify(api).getAlbum(eq("X"))
    verifyNoMoreInteractions(api)
}
```

**Note:** The `now()` override needs `AlbumCache` to have `open fun now()` (already is — `internal fun now()` → change to `internal open fun now()`).

- [ ] **Step 6.6 — Run and commit**

```bash
./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.cache.AlbumCacheTest
```

Expected: 4 tests PASS.

```bash
git add core/data/src/main/kotlin/com/stash/core/data/cache/AlbumCache.kt \
        core/data/src/test/kotlin/com/stash/core/data/cache/AlbumCacheTest.kt
git commit -m "$(cat <<'EOF'
feat(core-data): add in-memory AlbumCache with 30-min TTL

Per-key mutex prevents duplicate concurrent fetches; hit within TTL returns
cached without network; past TTL refetches. No Room persistence — album
detail is near-static and 30 minutes covers a single browsing session.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7 — `AlbumDiscoveryUiState` + `AlbumDiscoveryViewModel` + tests

**Files:**
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryUiState.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryViewModel.kt`
- Create: `feature/search/src/test/kotlin/com/stash/feature/search/AlbumDiscoveryViewModelTest.kt`

- [ ] **Step 7.1 — Create `AlbumDiscoveryUiState`**

```kotlin
package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary

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
    val downloadConfirmQueue: List<TrackSummary> = emptyList(),
)
```

- [ ] **Step 7.2 — Write the first failing VM test**

Create `AlbumDiscoveryViewModelTest.kt` with the hero-hydration test (mirrors `ArtistProfileViewModelTest.initial state paints hero from nav args`):

```kotlin
@Test
fun `initial state paints hero from nav args before cache emits`() = runTest {
    val cache = mock<AlbumCache>()
    // Never emits — mimic slow fetch.
    org.mockito.kotlin.doSuspendableAnswer { kotlinx.coroutines.awaitCancellation() }
        .whenever(cache).get(any())

    val vm = vmWith(cache = cache)

    val first = vm.uiState.value
    assertEquals("Curtains", first.hero.title)
    assertEquals("John Frusciante", first.hero.artist)
    assertEquals("url", first.hero.thumbnailUrl)
    assertEquals("2005", first.hero.year)
    assertTrue(first.status is AlbumDiscoveryStatus.Loading)
}

private fun vmWith(
    cache: AlbumCache = mock(),
    prefetcher: PreviewPrefetcher = mock(),
    delegate: TrackActionsDelegate = stubDelegate(),
    playerRepository: com.stash.core.media.PlayerRepository = mock(),
): AlbumDiscoveryViewModel = AlbumDiscoveryViewModel(
    savedStateHandle = SavedStateHandle(
        mapOf(
            "browseId" to "MPREb_xxx",
            "title" to "Curtains",
            "artist" to "John Frusciante",
            "thumbnailUrl" to "url",
            "year" to "2005",
        ),
    ),
    albumCache = cache,
    prefetcher = prefetcher,
    playerRepository = playerRepository,
    delegate = delegate,
)
```

- [ ] **Step 7.3 — Run and fail**

```bash
./gradlew :feature:search:testDebugUnitTest --tests com.stash.feature.search.AlbumDiscoveryViewModelTest
```

Expected: FAIL (unresolved).

- [ ] **Step 7.4 — Write the VM**

```kotlin
package com.stash.feature.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.cache.AlbumCache
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.actions.TrackItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDiscoveryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val albumCache: AlbumCache,
    private val prefetcher: PreviewPrefetcher,
    private val playerRepository: PlayerRepository,
    private val musicRepository: com.stash.core.data.repository.MusicRepository,
    val delegate: TrackActionsDelegate,
) : ViewModel() {

    private val browseId: String = requireNotNull(savedStateHandle["browseId"]) {
        "SearchAlbumRoute requires a non-null browseId nav arg"
    }
    private val initialTitle: String = savedStateHandle["title"] ?: ""
    private val initialArtist: String = savedStateHandle["artist"] ?: ""
    private val initialThumb: String? = savedStateHandle["thumbnailUrl"]
    private val initialYear: String? = savedStateHandle["year"]

    private val _uiState = MutableStateFlow(
        AlbumDiscoveryUiState(
            hero = AlbumHeroState(
                title = initialTitle,
                artist = initialArtist,
                thumbnailUrl = initialThumb,
                year = initialYear,
                trackCount = 0,
                totalDurationMs = 0L,
            ),
            status = AlbumDiscoveryStatus.Loading,
        ),
    )
    val uiState: StateFlow<AlbumDiscoveryUiState> = _uiState.asStateFlow()

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

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

    fun onDownloadAllClicked() {
        val snapshot = _uiState.value.tracks.filter {
            it.videoId !in delegate.downloadedIds.value
        }
        _uiState.update { it.copy(showDownloadConfirm = true, downloadConfirmQueue = snapshot) }
    }

    fun onDownloadAllDismissed() {
        _uiState.update { it.copy(showDownloadConfirm = false, downloadConfirmQueue = emptyList()) }
    }

    fun onDownloadAllConfirmed() {
        val queue = _uiState.value.downloadConfirmQueue
        _uiState.update { it.copy(showDownloadConfirm = false, downloadConfirmQueue = emptyList()) }
        queue.forEach { track ->
            delegate.downloadTrack(
                TrackItem(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl,
                ),
            )
        }
    }

    fun shuffleDownloaded() {
        viewModelScope.launch {
            val downloadedVideoIds = delegate.downloadedIds.value
                .intersect(_uiState.value.tracks.map { it.videoId }.toSet())
            if (downloadedVideoIds.isEmpty()) return@launch
            val tracks = musicRepository.findByYoutubeIds(downloadedVideoIds)
            if (tracks.isEmpty()) return@launch
            playerRepository.setQueue(tracks.shuffled(), 0)
        }
    }

    override fun onCleared() {
        super.onCleared()
        delegate.onOwnerCleared()
        prefetcher.cancelAll()
    }

    private suspend fun observeAlbum() {
        try {
            val detail = albumCache.get(browseId)
            val totalMs = detail.tracks.sumOf { (it.durationSeconds * 1000).toLong() }
            _uiState.update {
                it.copy(
                    hero = AlbumHeroState(
                        title = detail.title,
                        artist = detail.artist,
                        thumbnailUrl = detail.thumbnailUrl ?: it.hero.thumbnailUrl,
                        year = detail.year ?: it.hero.year,
                        trackCount = detail.tracks.size,
                        totalDurationMs = totalMs,
                    ),
                    tracks = detail.tracks,
                    moreByArtist = detail.moreByArtist,
                    status = AlbumDiscoveryStatus.Fresh,
                )
            }
            if (!prefetchKicked && detail.tracks.isNotEmpty()) {
                prefetchKicked = true
                prefetcher.prefetch(detail.tracks.take(6).map { it.videoId })
            }
            delegate.refreshDownloadedIds(detail.tracks.map { it.videoId })
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "album fetch failed for $browseId", t)
            _uiState.update { it.copy(status = AlbumDiscoveryStatus.Error(t.message ?: "Something went wrong.")) }
            _userMessages.emit("Couldn't load album — tap Retry.")
        }
    }

    companion object {
        private const val TAG = "AlbumDiscoveryVM"
    }
}
```

**Prerequisite:** `MusicRepository` likely does NOT yet have `findByYoutubeIds(Collection<String>): List<Track>`. Before writing the VM, add it to `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt` as:

```kotlin
suspend fun findByYoutubeIds(videoIds: Collection<String>): List<Track> =
    videoIds.mapNotNull { trackDao.findByYoutubeId(it)?.toTrack() }
```

(`TrackDao.findByYoutubeId(String): TrackEntity?` and the `toTrack()` entity-to-model extension already exist — grep for existing usages to confirm the import path for `toTrack()`.) Commit that one-line repository addition BEFORE starting Step 7.1. That way the VM's `shuffleDownloaded` compiles cleanly.

- [ ] **Step 7.5 — Add remaining VM tests**

```kotlin
@Test fun `cache emit transitions to Fresh and kicks prefetch`()
@Test fun `cache failure transitions to Error and emits user message`()
@Test fun `retry flips status to Loading and re-fetches cache`()
@Test fun `onDownloadAllClicked snapshots non-downloaded tracks`()
@Test fun `onDownloadAllConfirmed enqueues snapshot into delegate`()
@Test fun `shuffleDownloaded plays only downloaded subset`()
```

Each follows the pattern from `ArtistProfileViewModelTest`. Example for the prefetch test:

```kotlin
@Test
fun `cache emit transitions to Fresh and kicks prefetch`() = runTest {
    val detail = AlbumDetail(
        id = "MPREb_xxx", title = "Curtains", artist = "John Frusciante",
        artistId = "UCxxx", thumbnailUrl = "u", year = "2005",
        tracks = (1..3).map { TrackSummary("v$it", "t$it", "a", null, 10.0, null) },
        moreByArtist = emptyList(),
    )
    val cache = mock<AlbumCache>().also { whenever(it.get(eq("MPREb_xxx"))).thenReturn(detail) }
    val prefetcher = mock<PreviewPrefetcher>()

    val vm = vmWith(cache = cache, prefetcher = prefetcher)
    advanceUntilIdle()

    assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
    assertEquals(3, vm.uiState.value.tracks.size)
    verify(prefetcher).prefetch(eq(listOf("v1", "v2", "v3")))
}
```

- [ ] **Step 7.6 — Run and commit**

```bash
./gradlew :feature:search:testDebugUnitTest --tests com.stash.feature.search.AlbumDiscoveryViewModelTest
```

Expected: 7 tests PASS.

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryUiState.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryViewModel.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/AlbumDiscoveryViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(search): add AlbumDiscoveryViewModel + UiState

VM hydrates hero from nav args, subscribes to AlbumCache, kicks preview
prefetcher once, exposes retry + download-all confirm-flow + shuffle.
Delegates per-track preview+download to TrackActionsDelegate.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8 — `AlbumHero` composable

**Files:**
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumHero.kt`

- [ ] **Step 8.1 — Write the composable**

```kotlin
@Composable
fun AlbumHero(
    hero: AlbumHeroState,
    hasDownloaded: Boolean,
    onBack: () -> Unit,
    onShuffle: () -> Unit,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            if (hero.thumbnailUrl != null) {
                AsyncImage(
                    model = hero.thumbnailUrl,
                    contentDescription = "${hero.title} artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                    )
                }
            }
            // Gradient scrim bottom 40%
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        ),
                    ),
            )
            // Back
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp)
                    .align(Alignment.TopStart)
                    .size(48.dp)
                    .background(StashTheme.extendedColors.glassBackground, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = hero.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(hero.artist)
                    if (hero.year != null) append(" • ").append(hero.year)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hero.trackCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(hero.trackCount)
                        append(" track")
                        if (hero.trackCount != 1) append("s")
                        if (hero.totalDurationMs > 0) {
                            append(" • ")
                            append(formatTotalDuration(hero.totalDurationMs))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasDownloaded) {
                    OutlinedButton(
                        onClick = onShuffle,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                }
                Button(
                    onClick = onDownloadAll,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download all")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
```

**Imports to add:** everything in `androidx.compose.material.icons.filled.*` for Album/Download/Shuffle, `androidx.compose.material.icons.automirrored.filled.ArrowBack`, `com.stash.core.ui.util.formatTotalDuration`, `com.stash.core.ui.theme.StashTheme`, etc. Mirror `feature/library/.../AlbumDetailScreen.kt` imports — its header is visually the closest.

- [ ] **Step 8.2 — Verify `assembleDebug` still builds**

```bash
./gradlew :app:assembleDebug
```

Expected: green.

- [ ] **Step 8.3 — Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/AlbumHero.kt
git commit -m "$(cat <<'EOF'
feat(search): add AlbumHero composable for AlbumDiscoveryScreen

Square cover art, gradient scrim, back button, title/artist/year/metadata,
Shuffle (conditional on >=1 downloaded) and Download-All chip row. Visual
cribbed from library's AlbumDetailHeader.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9 — `AlbumDiscoveryScreen` + Download-All confirm dialog

**Files:**
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryScreen.kt`

- [ ] **Step 9.1 — Write the composable**

```kotlin
@Composable
fun AlbumDiscoveryScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (AlbumSummary) -> Unit,
    vm: AlbumDiscoveryViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val downloadingIds by vm.delegate.downloadingIds.collectAsStateWithLifecycle()
    val downloadedIds by vm.delegate.downloadedIds.collectAsStateWithLifecycle()
    val previewLoadingId by vm.delegate.previewLoadingId.collectAsStateWithLifecycle()
    val previewState by vm.delegate.previewState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        kotlinx.coroutines.flow.merge(vm.userMessages, vm.delegate.userMessages)
            .collect { snackbar.showSnackbar(it) }
    }

    val hasDownloaded = remember(state.tracks, downloadedIds) {
        state.tracks.any { it.videoId in downloadedIds }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp),
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            item {
                AlbumHero(
                    hero = state.hero,
                    hasDownloaded = hasDownloaded,
                    onBack = onBack,
                    onShuffle = vm::shuffleDownloaded,
                    onDownloadAll = vm::onDownloadAllClicked,
                )
            }

            when (val s = state.status) {
                AlbumDiscoveryStatus.Loading -> item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is AlbumDiscoveryStatus.Error -> item {
                    DiscoveryErrorCard(
                        message = s.message,
                        onRetry = vm::retry,
                    )
                }
                AlbumDiscoveryStatus.Fresh -> {
                    if (state.tracks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "No tracks available",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(state.tracks, key = { "album_track_" + it.videoId }) { track ->
                            val item = track.toSearchResultItem()
                            PreviewDownloadRow(
                                item = item,
                                isDownloading = track.videoId in downloadingIds,
                                isDownloaded = track.videoId in downloadedIds,
                                isPreviewLoading = previewLoadingId == track.videoId,
                                isPreviewPlaying = previewState is PreviewState.Playing &&
                                    previewState.videoId == track.videoId,
                                onPreview = { vm.delegate.previewTrack(track.videoId) },
                                onStopPreview = { vm.delegate.stopPreview() },
                                onDownload = {
                                    vm.delegate.downloadTrack(
                                        com.stash.core.media.actions.TrackItem(
                                            videoId = track.videoId,
                                            title = track.title,
                                            artist = track.artist,
                                            durationSeconds = track.durationSeconds,
                                            thumbnailUrl = track.thumbnailUrl,
                                        ),
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        if (state.moreByArtist.isNotEmpty()) {
                            item { SectionHeader(title = "More by this artist") }
                            item { AlbumsRow(albums = state.moreByArtist, onClick = onNavigateToAlbum) }
                        }
                    }
                }
            }
        }

        if (state.showDownloadConfirm) {
            val count = state.downloadConfirmQueue.size
            AlertDialog(
                onDismissRequest = vm::onDownloadAllDismissed,
                title = { Text("Download all?") },
                text = {
                    Text(
                        if (count == 0) "All tracks already downloaded."
                        else "Download $count track${if (count == 1) "" else "s"} to your library?",
                    )
                },
                confirmButton = {
                    if (count == 0) {
                        TextButton(onClick = vm::onDownloadAllDismissed) { Text("OK") }
                    } else {
                        Button(onClick = vm::onDownloadAllConfirmed) { Text("Download") }
                    }
                },
                dismissButton = {
                    if (count != 0) {
                        TextButton(onClick = vm::onDownloadAllDismissed) { Text("Cancel") }
                    }
                },
            )
        }
    }
}
```

**Imports** (the non-obvious ones, added to the existing `androidx.compose.*` + `com.stash.*` imports already in a typical screen file):

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.preview.PreviewState
import com.stash.core.ui.components.DiscoveryErrorCard
import com.stash.core.ui.components.SectionHeader
import com.stash.data.ytmusic.model.AlbumSummary
```

`AlbumsRow`'s click signature currently takes `(AlbumSummary) -> Unit` — passing `onNavigateToAlbum` directly works.

- [ ] **Step 9.2 — Verify build**

```bash
./gradlew :app:assembleDebug
```

Expected: green.

- [ ] **Step 9.3 — Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryScreen.kt
git commit -m "$(cat <<'EOF'
feat(search): add AlbumDiscoveryScreen

Hero + tracklist (PreviewDownloadRow per track) + More-by-artist carousel
+ download-all confirm AlertDialog. Status branches render CircularProgress
during Loading, DiscoveryErrorCard with Retry on Error, and a "No tracks
available" message when Fresh but the album has zero tracks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10 — `SearchAlbumRoute` + wiring + rewire Search and ArtistProfile album callbacks

**Why this phase:** The final step that makes the screen reachable. Must also change both existing album-tap call sites to the new route so Bug #1 is fully closed.

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt`

- [ ] **Step 10.1 — Register the route**

In `TopLevelDestination.kt`, append:

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

- [ ] **Step 10.2 — Wire the composable in `StashNavHost.kt`**

```kotlin
composable<SearchAlbumRoute> {
    AlbumDiscoveryScreen(
        onBack = { navController.popBackStack() },
        onNavigateToAlbum = { album ->
            navController.navigate(
                SearchAlbumRoute(
                    browseId = album.id,
                    title = album.title,
                    artist = album.artist,
                    thumbnailUrl = album.thumbnailUrl,
                    year = album.year,
                ),
            )
        },
    )
}
```

- [ ] **Step 10.3 — Change Search + Artist Profile album callback signatures**

Both `SearchScreen.onNavigateToAlbum: (String, String) -> Unit` and `ArtistProfileScreen.onNavigateToAlbum: (String, String) -> Unit` become `(AlbumSummary) -> Unit`. Update call sites that pass them:

In `StashNavHost.kt`, the existing `composable<SearchArtistRoute>` block:

```kotlin
composable<SearchArtistRoute> {
    ArtistProfileScreen(
        onBack = { navController.popBackStack() },
        onNavigateToAlbum = { album ->
            navController.navigate(SearchAlbumRoute(album.id, album.title, album.artist, album.thumbnailUrl, album.year))
        },
        onNavigateToArtist = { id, name, avatar ->
            navController.navigate(SearchArtistRoute(id, name, avatar))
        },
    )
}
```

And whichever composable registers `SearchRoute` — its `onNavigateToAlbum` also navigates via `SearchAlbumRoute`.

Inside both screens, `AlbumsRow(albums = state.albums, onClick = { onNavigateToAlbum(it.title, state.hero.name) })` becomes `AlbumsRow(albums = state.albums, onClick = onNavigateToAlbum)` (the whole `AlbumSummary` passes through).

Same change for `SinglesRow` in `ArtistProfileScreen`.

- [ ] **Step 10.4 — Verify `assembleDebug` passes**

```bash
./gradlew :app:assembleDebug
```

Expected: green. If any call site forgot to update, the compiler will catch it.

- [ ] **Step 10.5 — Run the full test suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. All existing + new tests green.

- [ ] **Step 10.6 — Install on device**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew :app:installDebug
```

- [ ] **Step 10.7 — Manual QA checklist**

Verify on-device:
1. Search "frusciante" → Albums row → tap an album. Hero paints immediately from nav args. Full tracklist appears after <2s. Preview + download buttons work per-row.
2. Tap "More by this artist" card → another album opens (recursive nav).
3. Back from Album Discovery → returns to Artist Profile, not to Search.
4. Download a single track → row flips to spinner → green checkmark. Shuffle chip appears in hero.
5. Tap Download All → confirm dialog shows "Download N tracks?" with correct N (= non-downloaded count). Cancel dismisses. Confirm enqueues all.
6. With airplane mode on, open a never-cached album → error card with Retry. Turn airplane mode off, tap Retry → loads.
7. Search tab → Albums row → tap an album → same new screen (not the library screen).

- [ ] **Step 10.8 — Commit the final wiring**

```bash
git add app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt \
        app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt
git commit -m "$(cat <<'EOF'
feat(search): wire Album Discovery into nav graph

Registers SearchAlbumRoute; rewires Search tab and Artist Profile album
clicks to land there instead of the library's AlbumDetailScreen. Narrows
onNavigateToAlbum callback signature to (AlbumSummary) -> Unit so all
nav-arg fields (browseId, title, artist, thumbnailUrl, year) flow through.

Closes Bug #1 from search-overhaul QA.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Wrap-up

After Task 10 lands:
- Branch is `feature/album-discovery`, ~10 commits ahead of master.
- User runs on-device QA per Step 10.7. If OK, invoke `superpowers:finishing-a-development-branch` for the merge decision.
- Known follow-ups (not blocking merge):
  - Artist link in hero (tap artist name → ArtistProfile).
  - Hero parallax / scroll-collapse.
  - Save-to-playlist on discovery tracks (if ever desired).
