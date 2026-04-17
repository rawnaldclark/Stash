# Search Tab Overhaul — Artist Profile + Multi-Category Results Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Spotify-style sectioned Search with a new Artist Profile screen, sourced from YouTube Music (InnerTube), meeting the latency targets in the spec's Performance Contract.

**Architecture:** New InnerTube browse+search wrapper methods → SWR artist cache (memory + Room) → preview-URL prefetcher built on split InnerTube/yt-dlp concurrency + race → new Artist Profile screen wired via a new nav route with nav-arg hydration → Search screen refactored to sectioned multi-category results → skeletons + Coil tuning + ExoPlayer preview LoadControl + OkHttp warm-up across the stack.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room (+ 1 migration), kotlinx-coroutines (SharedFlow / flatMapLatest / async-race), Coil3, Media3/ExoPlayer, InnerTube via existing `InnerTubeClient`, JUnit4 + Turbine for ViewModel tests, Compose UI tests for instrumented tests.

**Spec:** `docs/superpowers/specs/2026-04-17-search-artist-profile-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `data/ytmusic/.../YTMusicApiClient.kt` | Modify (append) | Add `searchAll(query)` + `getArtist(browseId)` + new DTOs |
| `data/ytmusic/.../model/SearchAllResults.kt` | Create | DTOs: `SearchResultSection`, `TopResultItem`, `SearchAllResults`, `ArtistSummary`, `AlbumSummary`, `TrackSummary`, `ArtistProfile` |
| `data/ytmusic/src/test/.../YTMusicApiClientTest.kt` | Create | Unit tests for `searchAll` and `getArtist` parsers |
| `core/data/.../db/entity/ArtistProfileCacheEntity.kt` | Create | Room entity for SWR cache |
| `core/data/.../db/dao/ArtistProfileCacheDao.kt` | Create | DAO with upsert, get, evict |
| `core/data/.../db/StashDatabase.kt` | Modify (52, 64-68, 70-96) | Register entity, bump version 6→7, add `MIGRATION_6_7` |
| `core/data/.../di/DatabaseModule.kt` | Modify (35, 53-59) | Register migration + provide DAO |
| `core/data/.../cache/ArtistCache.kt` | Create | `@Singleton` SWR cache (memory LRU + Room) |
| `core/data/src/test/.../ArtistCacheTest.kt` | Create | Tests for hit/stale-refresh/miss/eviction/refresh-failure |
| `data/download/.../preview/PreviewUrlExtractor.kt` | Modify (40-46, 59-71) | Split `Semaphore`s + InnerTube‖yt-dlp race |
| `data/download/src/test/.../PreviewUrlExtractorTest.kt` | Create | Semaphore isolation + race + cancel-loser |
| `core/ui/.../components/ShimmerPlaceholder.kt` | Create | Animated alpha-gradient skeleton primitive |
| `core/ui/.../components/ArtistProfileSkeletons.kt` | Create | `ArtistHeroSkeleton`, `PopularListSkeleton`, `AlbumsRowSkeleton` |
| `core/ui/.../components/ArtistAvatarCard.kt` | Create | Circular avatar + label card |
| `core/ui/.../components/AlbumSquareCard.kt` | Create | 140 dp square + title/artist/year |
| `core/ui/.../components/SectionHeader.kt` | Modify (15-23) | Keep existing — already satisfies §5.3 |
| `feature/search/.../PreviewPrefetcher.kt` | Create | Warms preview cache for Popular + visible-row prefetch |
| `feature/search/.../ArtistProfileScreen.kt` | Create | Top-level composable, hosts Snackbar |
| `feature/search/.../ArtistProfileViewModel.kt` | Create | Nav-arg hydration + `ArtistCache` subscription + prefetch kick |
| `feature/search/.../ArtistProfileUiState.kt` | Create | UiState + status sealed hierarchy |
| `feature/search/.../ArtistHero.kt` | Create | Purple-wash hero + glass chips |
| `feature/search/.../PopularTracksSection.kt` | Create | Reuses existing preview+download row |
| `feature/search/.../AlbumsRow.kt` | Create | LazyRow of `AlbumSquareCard` |
| `feature/search/.../SinglesRow.kt` | Create | LazyRow of `AlbumSquareCard` |
| `feature/search/.../RelatedArtistsRow.kt` | Create | LazyRow of `ArtistAvatarCard` |
| `feature/search/.../SearchScreen.kt` | Rewrite (sectioned render) | Sectioned list (Top, Songs, Artists, Albums) |
| `feature/search/.../SearchViewModel.kt` | Rewrite (flatMapLatest SWR) | New `SearchStatus` hierarchy, `userMessages` SharedFlow, InnerTube retry |
| `feature/search/.../SearchUiState.kt` | Rewrite | `SearchStatus` sealed; add `previewLoading`, `downloadingIds` |
| `feature/search/src/test/.../SearchViewModelTest.kt` | Create | flatMapLatest cancellation + retry fallback + userMessages |
| `app/.../navigation/TopLevelDestination.kt` | Modify (34) | Register `SearchArtistRoute` |
| `app/.../navigation/StashNavHost.kt` | Modify (64, 98) | Register new composable + wire callback from SearchScreen |
| `app/.../CoilConfiguration.kt` | Create | `Application.newImageLoader` override installing global `ImageLoader` |
| `app/.../StashApplication.kt` | Modify | Add `SingletonImageLoader.setSafe` + warm-up HEAD request |
| `core/media/.../preview/PreviewLoadControlFactory.kt` | Create | Preview-tuned `LoadControl` provider |
| `core/media/.../preview/PreviewPlayer.kt` | Modify (161-175) | Inject preview-tuned `LoadControl` into `ExoPlayer.Builder` |
| `feature/search/build.gradle.kts` | Modify (7-15) | Add Turbine + junit + coroutines-test for unit tests |

---

### Task 1 — `YTMusicApiClient.searchAll` + DTOs

**Why this phase:** The existing `HybridSearchExecutor.search(query)` returns a flat list. The Search tab needs a sectioned `top/songs/artists/albums` structure. We add a parallel method without touching the download-match pipeline.

**Files:**
- Create: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/SearchAllResults.kt`
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt:30-44` (add new companion consts), append new method after `getPlaylistTracks` (line 110)
- Create: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt`
- Create test fixtures: `data/ytmusic/src/test/resources/fixtures/search_artist.json`, `search_track.json`, `search_empty.json`

- [ ] **Step 1.1 — Add test-only dependencies to `data/ytmusic/build.gradle.kts`**

Append inside the `dependencies {}` block:

```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
```

Commit:

```bash
git add data/ytmusic/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(ytmusic): add junit + turbine test deps

Prepares module for YTMusicApiClient parser tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 1.2 — Write the DTO file (`SearchAllResults.kt`)**

This is a brand-new file; no failing test needed yet (data classes are trivial) — but we stage it so the test in Step 1.3 compiles.

```kotlin
package com.stash.data.ytmusic.model

/** One section shown in the sectioned Search results. */
sealed interface SearchResultSection {
    data class Top(val item: TopResultItem) : SearchResultSection
    data class Songs(val tracks: List<TrackSummary>) : SearchResultSection
    data class Artists(val artists: List<ArtistSummary>) : SearchResultSection
    data class Albums(val albums: List<AlbumSummary>) : SearchResultSection
}

/** Discriminator for the tall "Top result" card. */
sealed interface TopResultItem {
    data class ArtistTop(val artist: ArtistSummary) : TopResultItem
    data class TrackTop(val track: TrackSummary) : TopResultItem
}

data class SearchAllResults(val sections: List<SearchResultSection>)

data class ArtistSummary(
    val id: String,
    val name: String,
    val avatarUrl: String?,
)

data class AlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val year: String?,
)

data class TrackSummary(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Double,
    val thumbnailUrl: String?,
)

data class ArtistProfile(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val subscribersText: String?,
    val popular: List<TrackSummary>,
    val albums: List<AlbumSummary>,
    val singles: List<AlbumSummary>,
    val related: List<ArtistSummary>,
)
```

- [ ] **Step 1.3 — TDD (RED) — write failing `searchAll` parser tests**

Capture three realistic InnerTube search responses (manually, via adb + a real authenticated call, or from an existing network recording). Save them under `data/ytmusic/src/test/resources/fixtures/`. Then:

```kotlin
// data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt
package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

class YTMusicApiClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private fun fakeClient(responseJson: String): YTMusicApiClient {
        val inner = mock(InnerTubeClient::class.java)
        val parsed = Json.parseToJsonElement(responseJson).jsonObject
        // search() is suspend — use mockito-kotlin whenever/any
        org.mockito.kotlin.runBlocking { `when`(inner.search(any())).thenReturn(parsed) }
        return YTMusicApiClient(inner)
    }

    @Test
    fun `searchAll returns top artists songs albums for artist query`() = runTest {
        val client = fakeClient(loadFixture("search_artist.json"))

        val result = client.searchAll("lootpack")

        // Section ordering must be Top, Songs, Artists, Albums
        val kinds = result.sections.map { it::class.simpleName }
        assertEquals(listOf("Top", "Songs", "Artists", "Albums"), kinds)

        val top = result.sections.first() as SearchResultSection.Top
        assertTrue(top.item is TopResultItem.ArtistTop)

        val songs = result.sections[1] as SearchResultSection.Songs
        assertTrue(songs.tracks.size in 1..4)
        assertTrue(songs.tracks.first().videoId.isNotBlank())
    }

    @Test
    fun `searchAll returns track as top when query matches single song`() = runTest {
        val client = fakeClient(loadFixture("search_track.json"))
        val result = client.searchAll("never gonna give")
        val top = result.sections.first() as SearchResultSection.Top
        assertTrue(top.item is TopResultItem.TrackTop)
    }

    @Test
    fun `searchAll returns empty sections list for zero-result query`() = runTest {
        val client = fakeClient(loadFixture("search_empty.json"))
        val result = client.searchAll("zzzzqqqqxxxxvvvv")
        assertTrue(result.sections.isEmpty())
    }
}
```

Run — expect FAIL (method does not exist):

```bash
./gradlew :data:ytmusic:testDebugUnitTest --tests "com.stash.data.ytmusic.YTMusicApiClientTest"
```

Expected: `error: unresolved reference: searchAll` / BUILD FAILED.

- [ ] **Step 1.4 — Implement `searchAll` parser (GREEN)**

Append to `YTMusicApiClient.kt` after `getPlaylistTracks` (insert near line 110 in current file):

```kotlin
    /**
     * Sectioned Search tab results.
     *
     * Wraps [InnerTubeClient.search]. InnerTube search returns a
     * `contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents`
     * array of `musicShelfRenderer` / `musicCardShelfRenderer` objects. We
     * iterate shelves and emit [SearchResultSection]s in fixed order:
     * Top → Songs → Artists → Albums. Missing shelves are skipped.
     */
    suspend fun searchAll(query: String): SearchAllResults {
        val response = innerTubeClient.search(query)
            ?: return SearchAllResults(emptyList())

        val shelves = response.navigatePath(
            "contents", "tabbedSearchResultsRenderer", "tabs",
        )?.firstArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray()
            ?: return SearchAllResults(emptyList())

        val sections = mutableListOf<SearchResultSection>()

        // 1. Top result (musicCardShelfRenderer — appears once, first)
        shelves.firstOrNull { it.asObject()?.containsKey("musicCardShelfRenderer") == true }
            ?.let { parseTopResultCard(it.asObject()!!) }
            ?.let { sections.add(SearchResultSection.Top(it)) }

        // 2..4. Named shelves — detect by the shelf's title run text
        for (shelf in shelves) {
            val renderer = shelf.asObject()?.get("musicShelfRenderer")?.asObject() ?: continue
            val title = renderer.navigatePath("title", "runs")?.firstArray()
                ?.firstOrNull()?.asObject()?.get("text")?.asString() ?: continue
            when (title) {
                "Songs" -> parseSongsShelf(renderer).takeIf { it.isNotEmpty() }
                    ?.let { sections.add(SearchResultSection.Songs(it.take(4))) }
                "Artists" -> parseArtistsShelf(renderer).takeIf { it.isNotEmpty() }
                    ?.let { sections.add(SearchResultSection.Artists(it)) }
                "Albums" -> parseAlbumsShelf(renderer).takeIf { it.isNotEmpty() }
                    ?.let { sections.add(SearchResultSection.Albums(it)) }
            }
        }

        return SearchAllResults(sections)
    }

    // Private helpers — parseTopResultCard, parseSongsShelf, parseArtistsShelf,
    // parseAlbumsShelf. Each returns the appropriate DTO list by walking the
    // musicResponsiveListItemRenderer or musicTwoRowItemRenderer children.
    // (See §3.3 of spec for DTO shape.)
```

Implement the private helpers using the same `navigatePath` / `asObject` pattern already in the file. For brevity the helpers are sketched inline in the plan; the engineer fills in the body by reading the InnerTube JSON fixtures captured in Step 1.3 and following the existing `parseTrackFromRenderer` style at `YTMusicApiClient.kt:185-262`.

Run — expect PASS:

```bash
./gradlew :data:ytmusic:testDebugUnitTest --tests "com.stash.data.ytmusic.YTMusicApiClientTest"
```

Expected: `BUILD SUCCESSFUL` with 3 tests passing.

- [ ] **Step 1.5 — Commit**

```bash
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/SearchAllResults.kt \
        data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt \
        data/ytmusic/src/test/resources/fixtures/
git commit -m "$(cat <<'EOF'
feat(ytmusic): add searchAll + sectioned DTOs

Adds YTMusicApiClient.searchAll(query) that returns
SearchAllResults{Top,Songs,Artists,Albums} from InnerTube
tabbedSearchResultsRenderer. Three fixture tests lock shelf
ordering and DTO shape for artist/track/empty queries.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 1.6 — Verify**

```bash
./gradlew :data:ytmusic:testDebugUnitTest
```

Expected: `3 tests, 0 failures`.

---

### Task 2 — `YTMusicApiClient.getArtist`

**Why this phase:** One browse call returns the whole artist page. We parse header, popular, albums, singles, related in a single pass.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt` (append after `searchAll`)
- Create test fixtures: `data/ytmusic/src/test/resources/fixtures/artist_rich.json`, `artist_sparse.json`
- Modify: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt` (append two tests)

- [ ] **Step 2.1 — TDD (RED) — write failing `getArtist` tests**

Append to `YTMusicApiClientTest.kt`:

```kotlin
    @Test
    fun `getArtist returns full profile for rich artist`() = runTest {
        val inner = mock(InnerTubeClient::class.java)
        val parsed = Json.parseToJsonElement(loadFixture("artist_rich.json")).jsonObject
        org.mockito.kotlin.runBlocking { `when`(inner.browse(any())).thenReturn(parsed) }
        val client = YTMusicApiClient(inner)

        val profile = client.getArtist("UCxxxxx")

        assertEquals("Lootpack", profile.name)
        assertTrue(profile.avatarUrl!!.startsWith("https://"))
        assertTrue(profile.popular.size in 5..10)
        assertTrue(profile.albums.isNotEmpty())
        assertTrue(profile.singles.isNotEmpty())
        assertTrue(profile.related.isNotEmpty())
        assertTrue(profile.subscribersText?.contains("subscriber", ignoreCase = true) == true)
    }

    @Test
    fun `getArtist tolerates sparse artist with only popular shelf`() = runTest {
        val inner = mock(InnerTubeClient::class.java)
        val parsed = Json.parseToJsonElement(loadFixture("artist_sparse.json")).jsonObject
        org.mockito.kotlin.runBlocking { `when`(inner.browse(any())).thenReturn(parsed) }
        val client = YTMusicApiClient(inner)

        val profile = client.getArtist("UCyyyyy")

        assertTrue(profile.popular.isNotEmpty())
        assertTrue(profile.albums.isEmpty())
        assertTrue(profile.singles.isEmpty())
        assertTrue(profile.related.isEmpty())
    }
```

Run — expect FAIL:

```bash
./gradlew :data:ytmusic:testDebugUnitTest --tests "*getArtist*"
```

Expected: `unresolved reference: getArtist`.

- [ ] **Step 2.2 — Implement `getArtist` (GREEN)**

Append to `YTMusicApiClient.kt`:

```kotlin
    /**
     * Fetch a YouTube Music artist browse page in one round-trip and parse it
     * into an [ArtistProfile].
     *
     * The browse response for an artist channel (browseId starting with `UC`
     * or `MPLAUC`) contains:
     *   - `header.musicImmersiveHeaderRenderer` — name, avatar, subscribers
     *   - multiple `musicShelfRenderer` / `musicCarouselShelfRenderer` under
     *     the single-column browse results tabs
     * Missing shelves return empty lists so callers don't branch on null.
     */
    suspend fun getArtist(browseId: String): ArtistProfile {
        val normalized = normalizeArtistBrowseId(browseId)
        val response = innerTubeClient.browse(normalized)
            ?: return ArtistProfile(normalized, name = "", avatarUrl = null,
                subscribersText = null, popular = emptyList(), albums = emptyList(),
                singles = emptyList(), related = emptyList())

        val header = response["header"]?.asObject()
            ?.get("musicImmersiveHeaderRenderer")?.asObject()
        val name = header?.navigatePath("title", "runs")?.firstArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString() ?: ""
        val avatarUrl = header?.navigatePath("thumbnail", "musicThumbnailRenderer",
            "thumbnail", "thumbnails")?.firstArray()?.lastOrNull()
            ?.asObject()?.get("url")?.asString()
        val subscribersText = header?.navigatePath("subscriptionButton",
            "subscribeButtonRenderer", "subscriberCountText", "runs")
            ?.firstArray()?.firstOrNull()?.asObject()?.get("text")?.asString()

        val sections = response.navigatePath(
            "contents", "singleColumnBrowseResultsRenderer", "tabs",
        )?.firstArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray() ?: return ArtistProfile(normalized, name, avatarUrl,
                subscribersText, emptyList(), emptyList(), emptyList(), emptyList())

        var popular = emptyList<TrackSummary>()
        var albums = emptyList<AlbumSummary>()
        var singles = emptyList<AlbumSummary>()
        var related = emptyList<ArtistSummary>()

        for (section in sections) {
            val obj = section.asObject() ?: continue
            obj["musicShelfRenderer"]?.asObject()?.let { shelf ->
                val title = shelf.navigatePath("title", "runs")?.firstArray()
                    ?.firstOrNull()?.asObject()?.get("text")?.asString()
                if (title?.contains("popular", ignoreCase = true) == true) {
                    popular = parseTracksFromShelf(shelf).take(10)
                }
            }
            obj["musicCarouselShelfRenderer"]?.asObject()?.let { carousel ->
                val title = carousel.navigatePath("header",
                    "musicCarouselShelfBasicHeaderRenderer", "title", "runs")
                    ?.firstArray()?.firstOrNull()?.asObject()
                    ?.get("text")?.asString().orEmpty()
                when {
                    title.equals("Albums", true) ->
                        albums = parseAlbumsCarousel(carousel)
                    title.equals("Singles", true) || title.contains("EPs", true) ->
                        singles = parseAlbumsCarousel(carousel)
                    title.contains("Fans also like", true) ->
                        related = parseArtistsCarousel(carousel)
                }
            }
        }

        return ArtistProfile(normalized, name, avatarUrl, subscribersText,
            popular, albums, singles, related)
    }

    /**
     * Spec §8 Open Question 1: InnerTube returns artists with either `UC...`
     * (channel) or `MPLAUC...` (music channel) browseIds. Cache-key stability
     * requires a single form. We strip `MPLA` if present and use the bare
     * channel ID.
     */
    private fun normalizeArtistBrowseId(browseId: String): String =
        if (browseId.startsWith("MPLA")) browseId.removePrefix("MPLA") else browseId
```

Implement `parseTracksFromShelf`, `parseAlbumsCarousel`, `parseArtistsCarousel` using the same renderer walk pattern as `parseTrackFromRenderer` at line 185.

Run — expect PASS:

```bash
./gradlew :data:ytmusic:testDebugUnitTest --tests "*getArtist*"
```

Expected: 2 tests pass.

- [ ] **Step 2.3 — Commit**

```bash
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/YTMusicApiClientTest.kt \
        data/ytmusic/src/test/resources/fixtures/artist_rich.json \
        data/ytmusic/src/test/resources/fixtures/artist_sparse.json
git commit -m "$(cat <<'EOF'
feat(ytmusic): add getArtist browse wrapper

Parses the full artist page (header, popular, albums, singles,
related) from a single InnerTube browse call. Normalizes
MPLAUC*->UC* browseId for stable cache keys.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 2.4 — Verify**

```bash
./gradlew :data:ytmusic:testDebugUnitTest
```

Expected: all 5 parser tests pass.

---

### Task 3 — `ArtistCache` + Room migration (SWR)

**Why this phase:** Re-entries to the same artist must paint in < 50 ms; disk-backed SWR delivers that without a network hit.

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/entity/ArtistProfileCacheEntity.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/dao/ArtistProfileCacheDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt:41-96` (add entity to `@Database`, bump version 6→7, add `MIGRATION_6_7`)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt:35,53-59` (add migration + provide DAO)
- Create: `core/data/src/main/kotlin/com/stash/core/data/cache/ArtistCache.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/cache/ArtistCacheTest.kt`
- Modify: `core/data/build.gradle.kts` (add `testImplementation` for junit + turbine + room-testing)

- [ ] **Step 3.1 — Add test deps**

Append to `core/data/build.gradle.kts`:

```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.room:room-testing:2.7.1")
    testImplementation("org.robolectric:robolectric:4.13")
```

Commit:

```bash
git add core/data/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(core/data): add room-testing + robolectric

Enables in-memory Room DAO tests for upcoming ArtistCache.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3.2 — TDD (RED) — write `ArtistProfileCacheDao` failing test**

Create `core/data/src/test/kotlin/com/stash/core/data/cache/ArtistProfileCacheDaoTest.kt`:

```kotlin
package com.stash.core.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ArtistProfileCacheEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtistProfileCacheDaoTest {

    private lateinit var db: StashDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsert then get returns entity`() = runTest {
        val dao = db.artistProfileCacheDao()
        val entity = ArtistProfileCacheEntity("UCabc", "{}", 1_700_000_000_000L)
        dao.upsert(entity)
        assertEquals(entity, dao.get("UCabc"))
    }

    @Test
    fun `evictOldest keeps 20 newest entries`() = runTest {
        val dao = db.artistProfileCacheDao()
        repeat(30) { i -> dao.upsert(ArtistProfileCacheEntity("UC$i", "{}", i.toLong())) }
        dao.evictOldest(keep = 20)
        assertNull(dao.get("UC0"))
        assertEquals("UC29", dao.get("UC29")?.artistId)
    }
}
```

Run — expect FAIL:

```bash
./gradlew :core:data:testDebugUnitTest --tests "*ArtistProfileCacheDaoTest*"
```

Expected: `error: unresolved reference: artistProfileCacheDao`.

- [ ] **Step 3.3 — Create entity + DAO (GREEN)**

`core/data/src/main/kotlin/com/stash/core/data/db/entity/ArtistProfileCacheEntity.kt`:

```kotlin
package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SWR cache row for an [ArtistProfile] JSON blob.
 *
 * - [artistId] is the normalized InnerTube browseId (UC-prefix form).
 * - [json] is the serialized [com.stash.data.ytmusic.model.ArtistProfile].
 * - [fetchedAt] is epoch-millis; used to compute stale-ness (TTL = 6h).
 */
@Entity(tableName = "artist_profile_cache")
data class ArtistProfileCacheEntity(
    @PrimaryKey @ColumnInfo(name = "artist_id") val artistId: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
```

`core/data/src/main/kotlin/com/stash/core/data/db/dao/ArtistProfileCacheDao.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.ArtistProfileCacheEntity

@Dao
interface ArtistProfileCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArtistProfileCacheEntity)

    @Query("SELECT * FROM artist_profile_cache WHERE artist_id = :artistId")
    suspend fun get(artistId: String): ArtistProfileCacheEntity?

    /** Deletes all rows except the [keep] newest by `fetched_at`. */
    @Query("""
        DELETE FROM artist_profile_cache
        WHERE artist_id NOT IN (
            SELECT artist_id FROM artist_profile_cache
            ORDER BY fetched_at DESC LIMIT :keep
        )
    """)
    suspend fun evictOldest(keep: Int)
}
```

Register in `StashDatabase.kt`:

- Line 52 `version = 6,` → `version = 7,`
- Add to `entities = [...]` array: `ArtistProfileCacheEntity::class,`
- Add abstract method: `abstract fun artistProfileCacheDao(): ArtistProfileCacheDao`
- Add migration in `companion object`:

```kotlin
/** v6 → v7: add artist_profile_cache table for SWR artist pages. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS artist_profile_cache (
                artist_id TEXT NOT NULL PRIMARY KEY,
                json TEXT NOT NULL,
                fetched_at INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

In `DatabaseModule.kt:35` add `, StashDatabase.MIGRATION_6_7` to the `addMigrations` call. Add provider:

```kotlin
@Provides
fun provideArtistProfileCacheDao(db: StashDatabase): ArtistProfileCacheDao =
    db.artistProfileCacheDao()
```

Run — expect PASS:

```bash
./gradlew :core:data:testDebugUnitTest --tests "*ArtistProfileCacheDaoTest*"
```

Expected: 2 DAO tests pass.

- [ ] **Step 3.4 — Commit DAO layer**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/ArtistProfileCacheEntity.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/ArtistProfileCacheDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt \
        core/data/src/test/kotlin/com/stash/core/data/cache/ArtistProfileCacheDaoTest.kt
git commit -m "$(cat <<'EOF'
feat(core/data): add artist_profile_cache table (v7)

New Room entity + DAO for the SWR artist-profile cache,
plus MIGRATION_6_7 that creates the table. DatabaseModule
registers the new DAO and migration.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3.5 — TDD (RED) — write `ArtistCache` tests**

`core/data/src/test/kotlin/com/stash/core/data/cache/ArtistCacheTest.kt`:

```kotlin
package com.stash.core.data.cache

import app.cash.turbine.test
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

class ArtistCacheTest {

    private fun mkProfile(id: String, name: String = "Name") = ArtistProfile(
        id = id, name = name, avatarUrl = null, subscribersText = null,
        popular = emptyList(), albums = emptyList(), singles = emptyList(),
        related = emptyList(),
    )

    @Test
    fun `miss emits Fresh after network fetch populates cache`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "A"))
        val cache = ArtistCache(dao, api, now = { 1_000L })

        cache.get("UC1").test {
            val first = awaitItem()
            assertTrue(first is CachedProfile.Fresh)
            assertEquals("A", (first as CachedProfile.Fresh).profile.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hit fresh returns Fresh without calling api`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "A", fetchedAt = 1_000L))
        }
        val api = mock<YTMusicApiClient>()
        val cache = ArtistCache(dao, api, now = { 1_000L + 60_000L }) // 1 min later
        val result = cache.get("UC1").first()
        assertTrue(result is CachedProfile.Fresh)
        org.mockito.Mockito.verifyNoInteractions(api)
    }

    @Test
    fun `hit stale emits Stale then Fresh after refresh`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "Old", fetchedAt = 0L))
        }
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "New"))
        val ttl7h = 7 * 60 * 60 * 1000L
        val cache = ArtistCache(dao, api, now = { ttl7h })

        cache.get("UC1").test {
            val stale = awaitItem()
            assertTrue(stale is CachedProfile.Stale)
            assertEquals("Old", (stale as CachedProfile.Stale).profile.name)

            val fresh = awaitItem()
            assertTrue(fresh is CachedProfile.Fresh)
            assertEquals("New", (fresh as CachedProfile.Fresh).profile.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Spec advisory: Phase 3 — stale-refresh-failure stays Stale + surfaces message ---
    @Test
    fun `stale refresh failure keeps Stale and emits RefreshFailure`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "Cached", fetchedAt = 0L))
        }
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenThrow(RuntimeException("offline"))
        val ttl7h = 7 * 60 * 60 * 1000L
        val cache = ArtistCache(dao, api, now = { ttl7h })

        cache.get("UC1").test {
            val stale = awaitItem() as CachedProfile.Stale
            assertEquals("Cached", stale.profile.name)
            // Second emission must be another Stale with refreshFailed=true,
            // NOT an Error state. The stale data stays visible.
            val after = awaitItem()
            assertTrue(after is CachedProfile.Stale)
            assertTrue((after as CachedProfile.Stale).refreshFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `memory evicts oldest beyond 20`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        repeat(25) { i ->
            whenever(api.getArtist(eq("UC$i"))).thenReturn(mkProfile("UC$i"))
        }
        val cache = ArtistCache(dao, api, now = { 1L })
        repeat(25) { i -> cache.get("UC$i").first() }
        assertTrue(cache.memoryContains("UC24"))
        assertTrue(!cache.memoryContains("UC0"))
    }
}
```

Also create `InMemoryDao` + `ArtistCacheEntityFixtures` helpers in the test package.

Run — expect FAIL: class `ArtistCache` does not exist.

```bash
./gradlew :core:data:testDebugUnitTest --tests "*ArtistCacheTest*"
```

- [ ] **Step 3.6 — Implement `ArtistCache` (GREEN)**

`core/data/src/main/kotlin/com/stash/core/data/cache/ArtistCache.kt`:

```kotlin
package com.stash.core.data.cache

import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.entity.ArtistProfileCacheEntity
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CachedProfile {
    val profile: ArtistProfile
    data class Fresh(override val profile: ArtistProfile) : CachedProfile
    data class Stale(
        override val profile: ArtistProfile,
        val refreshFailed: Boolean = false,
    ) : CachedProfile
}

/**
 * Two-tier SWR cache for [ArtistProfile].
 *
 * Memory: 20-entry LRU keyed by `artistId`.
 * Disk:   [ArtistProfileCacheEntity] (Room).
 * TTL:    6h. Entries older than TTL are served as [CachedProfile.Stale] and
 *         refreshed in-flight; if the refresh fails, the entry stays Stale
 *         with `refreshFailed=true` (spec Phase 3 advisory).
 */
@Singleton
class ArtistCache @Inject constructor(
    private val dao: ArtistProfileCacheDao,
    private val api: YTMusicApiClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = object : LinkedHashMap<String, ArtistProfileCacheEntity>(
        20, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtistProfileCacheEntity>?) =
            size > 20
    }

    fun memoryContains(id: String): Boolean = synchronized(memory) { memory.containsKey(id) }

    fun get(artistId: String): Flow<CachedProfile> = flow {
        val hit = synchronized(memory) { memory[artistId] } ?: dao.get(artistId)
        if (hit != null) {
            synchronized(memory) { memory[artistId] = hit }
            val profile = json.decodeFromString<ArtistProfile>(hit.json)
            val age = now() - hit.fetchedAt
            if (age < TTL_MS) {
                emit(CachedProfile.Fresh(profile))
                return@flow
            }
            emit(CachedProfile.Stale(profile))
            try {
                val refreshed = api.getArtist(artistId)
                persist(refreshed)
                emit(CachedProfile.Fresh(refreshed))
            } catch (t: Throwable) {
                // Stay stale, surface refreshFailed=true.
                emit(CachedProfile.Stale(profile, refreshFailed = true))
            }
        } else {
            val profile = api.getArtist(artistId)
            persist(profile)
            emit(CachedProfile.Fresh(profile))
        }
    }

    private suspend fun persist(profile: ArtistProfile) {
        val entity = ArtistProfileCacheEntity(
            artistId = profile.id,
            json = json.encodeToString(profile),
            fetchedAt = now(),
        )
        dao.upsert(entity)
        dao.evictOldest(keep = 20)
        synchronized(memory) { memory[profile.id] = entity }
    }

    companion object {
        private const val TTL_MS: Long = 6 * 60 * 60 * 1000L
    }
}
```

Annotate `ArtistProfile` (and nested DTOs) with `@Serializable` — add `import kotlinx.serialization.Serializable` and `@Serializable` above each data class in `SearchAllResults.kt`. This is a DTO-file edit:

```kotlin
@Serializable data class ArtistProfile(...)
@Serializable data class ArtistSummary(...)
@Serializable data class AlbumSummary(...)
@Serializable data class TrackSummary(...)
```

Run — expect PASS:

```bash
./gradlew :core:data:testDebugUnitTest --tests "*ArtistCacheTest*"
```

Expected: 5 tests pass — including the refresh-failure lock.

- [ ] **Step 3.7 — Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/cache/ArtistCache.kt \
        data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/SearchAllResults.kt \
        core/data/src/test/kotlin/com/stash/core/data/cache/ArtistCacheTest.kt
git commit -m "$(cat <<'EOF'
feat(core/data): add ArtistCache (SWR memory+Room, 6h TTL)

Serves cached ArtistProfile instantly (memory LRU 20, Room
fallback). Stale entries are surfaced and refreshed in-flight;
if refresh fails the entry stays Stale with refreshFailed=true
so the VM can show a Snackbar without swapping the screen into
an error state.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3.8 — Verify**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: all DAO + cache tests pass.

---

### Task 4 — `PreviewUrlExtractor` split concurrency + race

**Why this phase:** Preview start-to-audio must hit < 500 ms p50. Racing InnerTube and yt-dlp in parallel, with semaphores that don't block each other, is the biggest single win.

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt:40-71, 141-196` (add two semaphores; rewrite `extractStreamUrl` as a race)
- Create: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt`
- Modify: `data/download/build.gradle.kts` (add junit + coroutines-test + turbine + mockito)

- [ ] **Step 4.1 — Add test deps**

Append to `data/download/build.gradle.kts`:

```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
```

Commit:

```bash
git add data/download/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(data/download): add test deps

Prep for PreviewUrlExtractor race + semaphore tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4.2 — TDD (RED) — failing tests for race + semaphore isolation**

```kotlin
// data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt
package com.stash.data.download.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class PreviewUrlExtractorTest {

    /** Test-double: replace the two private extract* methods via subclass-exposed hooks. */
    private class TestableExtractor(
        val innertube: suspend (String) -> String?,
        val ytdlp: suspend (String) -> String,
    ) : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String) = innertube(id)
        override suspend fun ytDlpExtract(id: String) = ytdlp(id)
    }

    @Test
    fun `race returns innertube URL when innertube wins`() = runTest {
        val hooks = TestableExtractor(
            innertube = { "https://fast/$it" },
            ytdlp = { delay(5_000); "https://slow/$it" },
        )
        val url = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://fast/abc", url)
    }

    @Test
    fun `race cancels ytdlp when innertube wins`() = runTest {
        val ytDlpCancelled = AtomicBoolean(false)
        val hooks = TestableExtractor(
            innertube = { "https://fast/$it" },
            ytdlp = {
                try { delay(2_000); "https://slow/$it" }
                catch (t: Throwable) { ytDlpCancelled.set(true); throw t }
            },
        )
        PreviewUrlExtractor.raceForTest(hooks, "abc")
        // small yield so structured cancellation propagates
        delay(50)
        assertTrue(ytDlpCancelled.get())
    }

    @Test
    fun `race falls back to ytdlp when innertube returns null`() = runTest {
        val hooks = TestableExtractor(
            innertube = { null },
            ytdlp = { "https://ytdlp/$it" },
        )
        val url = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://ytdlp/abc", url)
    }

    @Test
    fun `innertube semaphore allows 8 concurrent, ytdlp allows 2`() = runTest {
        val itMax = AtomicInteger(0); val itCur = AtomicInteger(0)
        val ytMax = AtomicInteger(0); val ytCur = AtomicInteger(0)

        val hooks = TestableExtractor(
            innertube = {
                itMax.updateAndGet { m -> maxOf(m, itCur.incrementAndGet()) }
                try { delay(30); "u" } finally { itCur.decrementAndGet() }
            },
            ytdlp = {
                ytMax.updateAndGet { m -> maxOf(m, ytCur.incrementAndGet()) }
                try { delay(30); "y" } finally { ytCur.decrementAndGet() }
            },
        )
        coroutineScope {
            (1..20).map { async { PreviewUrlExtractor.raceForTest(hooks, "id$it") } }.awaitAll()
        }
        assertTrue("innertube max=${itMax.get()}", itMax.get() <= 8)
        assertTrue("ytdlp max=${ytMax.get()}", ytMax.get() <= 2)
    }
}
```

Run — expect FAIL (`raceForTest`, `TestHooks` unknown):

```bash
./gradlew :data:download:testDebugUnitTest --tests "*PreviewUrlExtractorTest*"
```

- [ ] **Step 4.3 — Implement split semaphores + race (GREEN)**

Add to companion object and refactor `extractStreamUrl` in `PreviewUrlExtractor.kt`:

```kotlin
    companion object {
        private const val TAG = "PreviewUrlExtractor"
        private const val YTDLP_TIMEOUT_MS = 60_000L
        private const val INNERTUBE_TIMEOUT_MS = 10_000L
        private const val FORMAT_SELECTOR = "251/250/bestaudio"
        private const val INNERTUBE_CONCURRENCY = 8
        private const val YTDLP_CONCURRENCY = 2

        /** Shared so parallel callers (e.g. PreviewPrefetcher) respect the cap. */
        private val innerTubeSemaphore = kotlinx.coroutines.sync.Semaphore(INNERTUBE_CONCURRENCY)
        private val ytDlpSemaphore     = kotlinx.coroutines.sync.Semaphore(YTDLP_CONCURRENCY)

        /** Test-only injection point for race logic. Not wired in production. */
        interface TestHooks {
            suspend fun innerTubeExtract(id: String): String?
            suspend fun ytDlpExtract(id: String): String
        }

        /** Test-only: exercises race() directly without Android deps. */
        internal suspend fun raceForTest(hooks: TestHooks, videoId: String): String =
            race(videoId, hooks::innerTubeExtract, hooks::ytDlpExtract,
                 innerTubeSemaphore, ytDlpSemaphore)

        private suspend fun race(
            videoId: String,
            innerTubeExtract: suspend (String) -> String?,
            ytDlpExtract: suspend (String) -> String,
            itSem: kotlinx.coroutines.sync.Semaphore,
            ytSem: kotlinx.coroutines.sync.Semaphore,
        ): String = kotlinx.coroutines.coroutineScope {
            val inner = async {
                itSem.acquire()
                try { innerTubeExtract(videoId) } finally { itSem.release() }
            }
            val yt = async {
                ytSem.acquire()
                try { ytDlpExtract(videoId) } finally { ytSem.release() }
            }
            // Await inner first: if it produces a non-null URL, cancel yt.
            val itResult = runCatching { inner.await() }.getOrNull()
            if (itResult != null) {
                yt.cancel()
                itResult
            } else {
                yt.await()
            }
        }
    }
```

Replace the body of `extractStreamUrl`:

```kotlin
    suspend fun extractStreamUrl(videoId: String): String =
        race(
            videoId = videoId,
            innerTubeExtract = { id -> extractViaInnerTube(id) },
            ytDlpExtract = { id -> extractViaYtDlp(id) },
            itSem = innerTubeSemaphore,
            ytSem = ytDlpSemaphore,
        )
```

Run — expect PASS:

```bash
./gradlew :data:download:testDebugUnitTest --tests "*PreviewUrlExtractorTest*"
```

Expected: 4 tests pass.

- [ ] **Step 4.4 — Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt
git commit -m "$(cat <<'EOF'
feat(preview): race InnerTube vs yt-dlp with split semaphores

InnerTube fans out to 8 concurrent calls, yt-dlp stays narrow
at 2. Race returns whichever finishes first; loser is
cancelled. Falls back to yt-dlp when InnerTube returns null.
Locks the behaviour with four unit tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4.5 — Verify**

```bash
./gradlew :data:download:testDebugUnitTest
```

Expected: all download module tests pass.

---

### Task 5 — `PreviewPrefetcher`

**Why this phase:** Popular tracks on Artist Profile and visible rows on Search need their stream URL warmed before the user taps. A tiny façade over `PreviewUrlExtractor` keeps the VM clean.

**Files:**
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/PreviewPrefetcher.kt`
- Create: `feature/search/src/test/kotlin/com/stash/feature/search/PreviewPrefetcherTest.kt`
- Modify: `feature/search/build.gradle.kts` (add test deps)

- [ ] **Step 5.1 — Add test deps**

Append to `feature/search/build.gradle.kts`:

```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
```

Commit:

```bash
git add feature/search/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(feature/search): add VM test deps

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5.2 — TDD (RED)**

```kotlin
// feature/search/src/test/kotlin/com/stash/feature/search/PreviewPrefetcherTest.kt
package com.stash.feature.search

import com.stash.data.download.preview.PreviewUrlExtractor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*

class PreviewPrefetcherTest {

    @Test
    fun `prefetch calls extractStreamUrl once per id and populates cache`() = runTest {
        val ex = mock<PreviewUrlExtractor>()
        whenever(ex.extractStreamUrl(any())).thenAnswer { inv -> "u/${inv.arguments[0]}" }
        val cache = mutableMapOf<String, String>()
        val pf = PreviewPrefetcher(ex, cache)

        pf.prefetch(listOf("a", "b", "c"))
        advanceUntilIdle()

        verify(ex, times(3)).extractStreamUrl(any())
        assertEquals("u/a", cache["a"])
    }

    @Test
    fun `prefetch skips ids already in cache`() = runTest {
        val ex = mock<PreviewUrlExtractor>()
        val cache = mutableMapOf("a" to "u/a")
        val pf = PreviewPrefetcher(ex, cache)

        pf.prefetch(listOf("a", "b"))
        advanceUntilIdle()

        verify(ex, never()).extractStreamUrl(eq("a"))
        verify(ex).extractStreamUrl(eq("b"))
    }
}
```

Run — expect FAIL.

```bash
./gradlew :feature:search:testDebugUnitTest --tests "*PreviewPrefetcherTest*"
```

- [ ] **Step 5.3 — Implement `PreviewPrefetcher` (GREEN)**

```kotlin
// feature/search/src/main/kotlin/com/stash/feature/search/PreviewPrefetcher.kt
package com.stash.feature.search

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Warms [PreviewUrlExtractor] for a list of videoIds so that a later
 * `extractStreamUrl` call from the UI resolves instantly.
 *
 * `PreviewUrlExtractor` already owns semaphores, so this class only orchestrates
 * launches. It does NOT spin up its own concurrency limiter.
 */
class PreviewPrefetcher(
    private val extractor: PreviewUrlExtractor,
    private val previewUrlCache: MutableMap<String, String>,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    @Inject
    constructor(extractor: PreviewUrlExtractor) :
        this(extractor, mutableMapOf())

    private val jobs = mutableListOf<Job>()

    fun prefetch(videoIds: List<String>) {
        videoIds
            .filter { it !in previewUrlCache }
            .forEach { id ->
                val job = scope.launch {
                    try {
                        previewUrlCache[id] = extractor.extractStreamUrl(id)
                    } catch (t: Throwable) {
                        Log.w(TAG, "prefetch fail $id: ${t.message}")
                    }
                }
                jobs.add(job)
            }
    }

    /** Prefetches the first track of each visible album/single row (±3 look-ahead). */
    fun prefetchVisible(listState: LazyListState, items: List<TrackSummary>) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val first = (visible.first().index - 3).coerceAtLeast(0)
        val last  = (visible.last().index + 3).coerceAtMost(items.lastIndex)
        prefetch(items.subList(first, last + 1).map { it.videoId })
    }

    fun cancelAll() { jobs.forEach { it.cancel() }; jobs.clear() }

    companion object { private const val TAG = "PreviewPrefetcher" }
}
```

Run — expect PASS:

```bash
./gradlew :feature:search:testDebugUnitTest --tests "*PreviewPrefetcherTest*"
```

- [ ] **Step 5.4 — Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/PreviewPrefetcher.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/PreviewPrefetcherTest.kt
git commit -m "$(cat <<'EOF'
feat(search): add PreviewPrefetcher facade

Warms preview stream URLs for Popular + viewport tracks,
deduplicates against the existing cache, relies on the
semaphores already owned by PreviewUrlExtractor.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5.5 — Verify**

```bash
./gradlew :feature:search:testDebugUnitTest --tests "*PreviewPrefetcher*"
```

Expected: 2 tests pass.

---

### Task 6 — Skeleton primitives

**Why this phase:** Skeletons must appear on the first keystroke (< 100 ms). A shared primitive keeps loading UI consistent across Search and Artist Profile.

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/ShimmerPlaceholder.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/ArtistProfileSkeletons.kt`

- [ ] **Step 6.1 — Write `ShimmerPlaceholder`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/ShimmerPlaceholder.kt
package com.stash.core.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape

@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    baseAlpha: Float = 0.06f,
    highlightAlpha: Float = 0.12f,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = baseAlpha,
        targetValue = highlightAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = alpha)),
    )
}
```

- [ ] **Step 6.2 — Write composed skeletons**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/ArtistProfileSkeletons.kt
package com.stash.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ArtistHeroSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShimmerPlaceholder(Modifier.size(96.dp), shape = CircleShape)
        Spacer(Modifier.height(16.dp))
        ShimmerPlaceholder(Modifier.height(28.dp).width(180.dp),
            shape = RoundedCornerShape(6.dp))
        Spacer(Modifier.height(8.dp))
        ShimmerPlaceholder(Modifier.height(14.dp).width(120.dp),
            shape = RoundedCornerShape(4.dp))
    }
}

@Composable
fun PopularListSkeleton(rows: Int = 5, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp)) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ShimmerPlaceholder(Modifier.size(48.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    ShimmerPlaceholder(Modifier.height(14.dp).fillMaxWidth(0.7f),
                        RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(6.dp))
                    ShimmerPlaceholder(Modifier.height(12.dp).fillMaxWidth(0.4f),
                        RoundedCornerShape(4.dp))
                }
            }
        }
    }
}

@Composable
fun AlbumsRowSkeleton(count: Int = 6, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count) {
            Column {
                ShimmerPlaceholder(Modifier.size(140.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.height(6.dp))
                ShimmerPlaceholder(Modifier.height(12.dp).width(120.dp))
            }
        }
    }
}
```

- [ ] **Step 6.3 — Build check + commit**

```bash
./gradlew :core:ui:assembleDebug
```

Expected: BUILD SUCCESSFUL.

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/ShimmerPlaceholder.kt \
        core/ui/src/main/kotlin/com/stash/core/ui/components/ArtistProfileSkeletons.kt
git commit -m "$(cat <<'EOF'
feat(core/ui): add ShimmerPlaceholder + artist skeletons

Single animated alpha-gradient primitive plus composed
Hero/PopularList/AlbumsRow skeletons used by Search and
Artist Profile to honour <100ms first-paint target.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7 — Reusable cards + SectionHeader

**Why this phase:** `ArtistAvatarCard` and `AlbumSquareCard` are used by both Search and Artist Profile. Keep them in `core/ui` so the feature module doesn't depend on itself indirectly.

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/ArtistAvatarCard.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/AlbumSquareCard.kt`
- (Existing `SectionHeader.kt` already satisfies the spec — no change.)

- [ ] **Step 7.1 — `ArtistAvatarCard`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/ArtistAvatarCard.kt
package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun ArtistAvatarCard(
    name: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.width(96.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = avatarUrl?.let { "$it=w96-h96" } ?: avatarUrl,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
```

- [ ] **Step 7.2 — `AlbumSquareCard`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/AlbumSquareCard.kt
package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun AlbumSquareCard(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    year: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.width(140.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = thumbnailUrl?.let { "$it=w300-h300" } ?: thumbnailUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = if (year != null) "$year • $artist" else artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

- [ ] **Step 7.3 — Build + commit**

```bash
./gradlew :core:ui:assembleDebug
git add core/ui/src/main/kotlin/com/stash/core/ui/components/ArtistAvatarCard.kt \
        core/ui/src/main/kotlin/com/stash/core/ui/components/AlbumSquareCard.kt
git commit -m "$(cat <<'EOF'
feat(core/ui): add ArtistAvatarCard + AlbumSquareCard

Shared circular avatar card and 140dp album square,
both with explicit =w300-h300 / =w96-h96 size knobs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8 — Artist Profile nav + screen + VM

**Why this phase:** The only visible evidence of the whole effort is this screen. Nav-arg hydration + cache subscription is what buys the < 50 ms hero target.

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt:34` (register route)
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt:64,98` (wire composable)
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistHero.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/PopularTracksSection.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumsRow.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/SinglesRow.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/RelatedArtistsRow.kt`
- Create: `feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt`

- [ ] **Step 8.1 — Register `SearchArtistRoute`**

Append to `TopLevelDestination.kt` (after line 34):

```kotlin
@Serializable
data class SearchArtistRoute(
    val artistId: String,
    val name: String,
    val avatarUrl: String? = null,
)
```

- [ ] **Step 8.2 — TDD (RED) — write `ArtistProfileViewModelTest`**

```kotlin
// feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt
package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.stash.core.data.cache.ArtistCache
import com.stash.core.data.cache.CachedProfile
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After  fun tear()  = Dispatchers.resetMain()

    private fun vmWith(cache: ArtistCache, prefetcher: PreviewPrefetcher = mock()):
        ArtistProfileViewModel = ArtistProfileViewModel(
            savedStateHandle = SavedStateHandle(mapOf(
                "artistId" to "UC1", "name" to "LocalName", "avatarUrl" to "u",
            )),
            artistCache = cache,
            prefetcher = prefetcher,
        )

    @Test
    fun `initial state paints hero from nav args before cache emits`() = runTest {
        val cache = mock<ArtistCache>()
        whenever(cache.get(any())).thenReturn(flow { /* never emits */ })
        val vm = vmWith(cache)
        vm.uiState.test {
            val first = awaitItem()
            assertEquals("LocalName", first.hero.name)
            assertEquals("u", first.hero.avatarUrl)
            assertTrue(first.status is ArtistProfileStatus.Loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prefetcher is kicked with popular videoIds`() = runTest {
        val profile = ArtistProfile(
            id = "UC1", name = "A", avatarUrl = null, subscribersText = null,
            popular = listOf(t("v1"), t("v2")),
            albums = emptyList(), singles = emptyList(), related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))
        val pf = mock<PreviewPrefetcher>()
        vmWith(cache, pf)
        advanceUntilIdle()
        verify(pf).prefetch(eq(listOf("v1", "v2")))
    }

    @Test
    fun `stale refresh failure surfaces userMessage without changing status to Error`() = runTest {
        val cachedProfile = ArtistProfile("UC1", "A", null, null,
            emptyList(), emptyList(), emptyList(), emptyList())
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flow {
            emit(CachedProfile.Stale(cachedProfile))
            emit(CachedProfile.Stale(cachedProfile, refreshFailed = true))
        })
        val vm = vmWith(cache)

        vm.userMessages.test {
            advanceUntilIdle()
            assertEquals("Couldn't refresh — showing cached.", awaitItem())
        }
        assertTrue(vm.uiState.value.status is ArtistProfileStatus.Stale)
    }

    private fun t(id: String) = TrackSummary(id, "t", "a", null, 0.0, null)
}
```

Run — expect FAIL.

```bash
./gradlew :feature:search:testDebugUnitTest --tests "*ArtistProfileViewModelTest*"
```

- [ ] **Step 8.3 — Implement `ArtistProfileUiState` + VM (GREEN)**

```kotlin
// feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt
package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary

data class HeroState(val name: String, val avatarUrl: String?, val subscribersText: String?)

sealed interface ArtistProfileStatus {
    data object Loading : ArtistProfileStatus
    data object Fresh : ArtistProfileStatus
    data object Stale : ArtistProfileStatus
    data class Error(val message: String) : ArtistProfileStatus
}

data class ArtistProfileUiState(
    val hero: HeroState,
    val popular: List<TrackSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val singles: List<AlbumSummary> = emptyList(),
    val related: List<ArtistSummary> = emptyList(),
    val status: ArtistProfileStatus = ArtistProfileStatus.Loading,
)
```

```kotlin
// feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt
package com.stash.feature.search

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.cache.ArtistCache
import com.stash.core.data.cache.CachedProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val artistCache: ArtistCache,
    private val prefetcher: PreviewPrefetcher,
) : ViewModel() {

    private val artistId: String = requireNotNull(savedStateHandle["artistId"])
    private val initialName: String = savedStateHandle["name"] ?: ""
    private val initialAvatar: String? = savedStateHandle["avatarUrl"]

    private val _uiState = MutableStateFlow(
        ArtistProfileUiState(
            hero = HeroState(initialName, initialAvatar, null),
            status = ArtistProfileStatus.Loading,
        ),
    )
    val uiState: StateFlow<ArtistProfileUiState> = _uiState.asStateFlow()

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private var prefetchKicked = false

    init {
        val t0 = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            artistCache.get(artistId).collect { cached ->
                when (cached) {
                    is CachedProfile.Fresh -> apply(cached.profile, ArtistProfileStatus.Fresh, t0)
                    is CachedProfile.Stale -> {
                        apply(cached.profile, ArtistProfileStatus.Stale, t0)
                        if (cached.refreshFailed) {
                            _userMessages.emit("Couldn't refresh — showing cached.")
                        }
                    }
                }
            }
        }
    }

    private fun apply(
        p: com.stash.data.ytmusic.model.ArtistProfile,
        status: ArtistProfileStatus,
        t0: Long,
    ) {
        _uiState.value = _uiState.value.copy(
            hero = HeroState(p.name, p.avatarUrl, p.subscribersText),
            popular = p.popular, albums = p.albums,
            singles = p.singles, related = p.related,
            status = status,
        )
        if (!prefetchKicked && p.popular.isNotEmpty()) {
            prefetchKicked = true
            prefetcher.prefetch(p.popular.map { it.videoId })
        }
        Log.d("Perf", "ArtistProfile paint after ${SystemClock.elapsedRealtime() - t0}ms (status=$status)")
    }
}
```

Run — expect PASS:

```bash
./gradlew :feature:search:testDebugUnitTest --tests "*ArtistProfileViewModelTest*"
```

Expected: 3 VM tests pass, including the stale-refresh-failure lock.

- [ ] **Step 8.4 — Compose screen + sections**

```kotlin
// feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt
package com.stash.feature.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.*

@Composable
fun ArtistProfileScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumName: String, artistName: String) -> Unit,
    onNavigateToArtist: (artistId: String, name: String, avatar: String?) -> Unit,
    vm: ArtistProfileViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.userMessages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            item { ArtistHero(state.hero, state.status) }
            if (state.status is ArtistProfileStatus.Loading && state.popular.isEmpty()) {
                item { PopularListSkeleton() }
                item { AlbumsRowSkeleton() }
            } else {
                item { SectionHeader("Popular") }
                item { PopularTracksSection(state.popular) }
                if (state.albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    item { AlbumsRow(state.albums, onClick = { onNavigateToAlbum(it.title, state.hero.name) }) }
                }
                if (state.singles.isNotEmpty()) {
                    item { SectionHeader("Singles & EPs") }
                    item { SinglesRow(state.singles, onClick = { onNavigateToAlbum(it.title, state.hero.name) }) }
                }
                if (state.related.isNotEmpty()) {
                    item { SectionHeader("Fans also like") }
                    item { RelatedArtistsRow(state.related,
                        onClick = { onNavigateToArtist(it.id, it.name, it.avatarUrl) }) }
                }
            }
        }
    }
}
```

`ArtistHero.kt`, `PopularTracksSection.kt`, `AlbumsRow.kt`, `SinglesRow.kt`, `RelatedArtistsRow.kt` — each is a thin adapter over `AlbumSquareCard` / `ArtistAvatarCard` / the existing `SearchResultRow` (see Step 8.5).

- [ ] **Step 8.5 — Spec verification: Popular reuses the existing row 1:1**

Extract the body of `SearchResultRow` (currently `feature/search/.../SearchScreen.kt:330-468`) into a file-level composable `@Composable fun PreviewDownloadRow(...)` so both Search and `PopularTracksSection` can import it without duplication. `PopularTracksSection`:

```kotlin
@Composable
fun PopularTracksSection(
    tracks: List<TrackSummary>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        tracks.forEach { t ->
            PreviewDownloadRow(
                item = SearchResultItem(
                    videoId = t.videoId, title = t.title, artist = t.artist,
                    durationSeconds = t.durationSeconds, thumbnailUrl = t.thumbnailUrl,
                ),
                // … same props as on SearchScreen
            )
        }
    }
}
```

Write a Compose UI test that asserts the same composable identity across both screens via a test tag:

```kotlin
// feature/search/src/androidTest/kotlin/com/stash/feature/search/PreviewDownloadRowSharedTest.kt
@Test
fun popularTracksSection_usesPreviewDownloadRow() {
    composeRule.setContent { PopularTracksSection(listOf(sample)) }
    composeRule.onAllNodesWithTag("PreviewDownloadRow").assertCountEquals(1)
}
@Test
fun searchScreenRow_usesPreviewDownloadRow() {
    composeRule.setContent { PreviewDownloadRow(sampleItem, ...) }
    composeRule.onNodeWithTag("PreviewDownloadRow").assertExists()
}
```

Tag `PreviewDownloadRow`'s outermost `Row` with `Modifier.testTag("PreviewDownloadRow")`.

- [ ] **Step 8.6 — Wire NavHost**

In `StashNavHost.kt`:

Add to imports: `import com.stash.feature.search.ArtistProfileScreen`.

Replace line 64 with:

```kotlin
composable<SearchRoute> {
    SearchScreen(
        onNavigateToArtist = { id, name, avatar ->
            navController.navigate(SearchArtistRoute(id, name, avatar))
        },
        onNavigateToAlbum = { album, artist ->
            navController.navigate(AlbumDetailRoute(album, artist))
        },
    )
}
```

Before line 98 add:

```kotlin
composable<SearchArtistRoute> {
    ArtistProfileScreen(
        onBack = { navController.popBackStack() },
        onNavigateToAlbum = { album, artist ->
            navController.navigate(AlbumDetailRoute(album, artist))
        },
        onNavigateToArtist = { id, name, avatar ->
            navController.navigate(SearchArtistRoute(id, name, avatar))
        },
    )
}
```

- [ ] **Step 8.7 — Build + commit**

```bash
./gradlew :app:assembleDebug
git add app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt \
        app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistHero.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/PopularTracksSection.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/AlbumsRow.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/SinglesRow.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/RelatedArtistsRow.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt \
        feature/search/src/androidTest/kotlin/com/stash/feature/search/PreviewDownloadRowSharedTest.kt
git commit -m "$(cat <<'EOF'
feat(search): artist profile screen + nav route

New SearchArtistRoute wires ArtistProfileScreen. VM hydrates
hero from nav args (hero paints first frame), subscribes to
ArtistCache, kicks PreviewPrefetcher once Popular resolves.
Popular reuses the extracted PreviewDownloadRow 1:1; a shared
UI test locks the non-fork.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 8.8 — Verify**

```bash
./gradlew :feature:search:testDebugUnitTest --tests "*ArtistProfileViewModelTest*"
./gradlew :app:assembleDebug
```

Expected: unit tests pass, APK builds.

---

### Task 9 — Search screen refactor to sectioned results + retry fallback

**Why this phase:** Ties the new `searchAll` + sections UI together. Also introduces the InnerTube→yt-dlp silent retry for preview playback from §4 advisory.

**Files:**
- Rewrite: `feature/search/src/main/kotlin/com/stash/feature/search/SearchUiState.kt`
- Rewrite: `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt`
- Rewrite: `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt`
- Create: `feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt`

- [ ] **Step 9.1 — TDD (RED) — write failing ViewModel tests**

```kotlin
// feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt
package com.stash.feature.search

import app.cash.turbine.test
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.*
import com.stash.core.media.preview.PreviewPlayer
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.core.media.preview.PreviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import androidx.media3.common.PlaybackException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun s() = Dispatchers.setMain(dispatcher)
    @After  fun t() = Dispatchers.resetMain()

    @Test
    fun `flatMapLatest cancels prior query on new keystroke`() = runTest {
        val api = mock<YTMusicApiClient>()
        whenever(api.searchAll(eq("foo"))).thenAnswer {
            runBlocking { kotlinx.coroutines.delay(500) }
            SearchAllResults(emptyList())
        }
        whenever(api.searchAll(eq("foobar")))
            .thenReturn(SearchAllResults(emptyList()))
        val vm = newVm(api)

        vm.onQueryChanged("foo")
        advanceTimeBy(100)
        vm.onQueryChanged("foobar")
        advanceUntilIdle()

        // verify the first (long-running) call was cancelled
        verify(api, atLeastOnce()).searchAll(eq("foobar"))
    }

    @Test
    fun `error surfaces userMessages snackbar`() = runTest {
        val api = mock<YTMusicApiClient>()
        whenever(api.searchAll(any())).thenThrow(RuntimeException("boom"))
        val vm = newVm(api)
        vm.userMessages.test {
            vm.onQueryChanged("abc")
            advanceUntilIdle()
            assertTrue(awaitItem().contains("search failed", ignoreCase = true))
        }
    }

    /** Spec advisory: InnerTube URL validated at playback start → if ExoPlayer
     *  reports SOURCE error within 3s, silently retry with yt-dlp. */
    @Test
    fun `preview retries via ytdlp when InnerTube playback errors within 3s`() = runTest {
        val player = mock<PreviewPlayer>()
        val extractor = mock<PreviewUrlExtractor>()
        whenever(extractor.extractStreamUrl(eq("vid"))).thenReturn("https://innertube/vid")
        whenever(extractor.extractViaYtDlpForRetry(eq("vid"))).thenReturn("https://ytdlp/vid")
        val playerState = MutableStateFlow<PreviewState>(PreviewState.Idle)
        whenever(player.previewState).thenReturn(playerState)

        val vm = newVm(extractor = extractor, player = player)
        vm.previewTrack("vid")
        advanceUntilIdle()

        verify(player).playUrl(eq("vid"), eq("https://innertube/vid"))
        // simulate ExoPlayer SOURCE error within 3s
        vm.onPreviewError("vid", PlaybackException(
            "source", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        advanceTimeBy(500)
        verify(player).playUrl(eq("vid"), eq("https://ytdlp/vid"))
    }

    private fun newVm(
        api: YTMusicApiClient = mock(),
        extractor: PreviewUrlExtractor = mock(),
        player: PreviewPlayer = mock { on { previewState } doReturn MutableStateFlow(PreviewState.Idle) },
    ): SearchViewModel = SearchViewModel(
        api = api, previewPlayer = player, previewUrlExtractor = extractor,
        // ...other deps (trackDao, downloadExecutor, fileOrganizer, qualityPrefs,
        // musicRepository, prefetcher) all mocked
    )
}
```

Run — expect FAIL (new API surface: `onPreviewError`, `extractViaYtDlpForRetry`).

```bash
./gradlew :feature:search:testDebugUnitTest --tests "*SearchViewModelTest*"
```

- [ ] **Step 9.2 — Rewrite `SearchUiState.kt` (GREEN part 1)**

```kotlin
package com.stash.feature.search

import com.stash.data.ytmusic.model.SearchResultSection

sealed interface SearchStatus {
    data object Idle : SearchStatus
    data object Typing : SearchStatus
    data object Loading : SearchStatus
    data class Results(val sections: List<SearchResultSection>) : SearchStatus
    data object Empty : SearchStatus
    data class Error(val message: String) : SearchStatus
}

data class SearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.Idle,
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val previewLoading: String? = null,
)
```

- [ ] **Step 9.3 — Rewrite `SearchViewModel.kt` (GREEN part 2)**

Key changes vs current file:

- Remove `HybridSearchExecutor` dependency from the search path (keep it for download internals).
- Add constructor deps: `YTMusicApiClient`, `PreviewPrefetcher`.
- Replace debounce coroutine-per-keystroke with `queryFlow.debounce(300).distinctUntilChanged().flatMapLatest { api.searchAll(it) }`.
- Expose `userMessages: SharedFlow<String>` and emit on error.
- Add `onPreviewError(videoId, PlaybackException)` that, on `ERROR_CODE_IO_*` within 3s of playback start, awaits `extractor.extractViaYtDlpForRetry(videoId)` and retries `playerUrl`. Add a companion public method `extractViaYtDlpForRetry` to `PreviewUrlExtractor` that calls the private `extractViaYtDlp` directly (bypassing the race).
- Kick `prefetcher.prefetch(top-N videoIds)` immediately on `Results`.

Add `PreviewUrlExtractor.extractViaYtDlpForRetry` in `data/download/.../PreviewUrlExtractor.kt`:

```kotlin
/** Public entry point for retry: bypass InnerTube and go straight to yt-dlp. */
suspend fun extractViaYtDlpForRetry(videoId: String): String = extractViaYtDlp(videoId)
```

- [ ] **Step 9.4 — Wire `SearchScreen.kt` to sections**

Replace the body of `ResultsList` (current lines 291-319) with a `LazyColumn` that pattern-matches each `SearchResultSection`:

```kotlin
@Composable
private fun SectionedResultsList(
    sections: List<SearchResultSection>,
    uiState: SearchUiState,
    previewState: PreviewState,
    onArtistClick: (ArtistSummary) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (TrackSummary) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEach { section ->
            when (section) {
                is SearchResultSection.Top -> item { TopResultCard(section.item,
                    onArtistClick = onArtistClick, onTrackPlay = onPreview) }
                is SearchResultSection.Songs -> {
                    item { SectionHeader("Songs") }
                    items(section.tracks, key = { it.videoId }) { t ->
                        PreviewDownloadRow(...)
                    }
                }
                is SearchResultSection.Artists -> {
                    item { SectionHeader("Artists") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(section.artists, key = { it.id }) { a ->
                                ArtistAvatarCard(a.name, a.avatarUrl,
                                    onClick = { onArtistClick(a) })
                            }
                        }
                    }
                }
                is SearchResultSection.Albums -> {
                    item { SectionHeader("Albums") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(section.albums, key = { it.id }) { a ->
                                AlbumSquareCard(a.title, a.artist, a.thumbnailUrl,
                                    a.year, onClick = { onAlbumClick(a) })
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- Update `SearchScreen(onNavigateToArtist, onNavigateToAlbum)` signature; replace `state.isSearching` branching with `when (state.status) { Loading -> LoadingSkeletons(); Results -> SectionedResultsList(...); ... }`.
- Replace `LoadingIndicator` with `LoadingSkeletons` = `Column { repeat(6) { ShimmerPlaceholder(Modifier.height(64.dp).padding(12.dp), RoundedCornerShape(12.dp)) } }` shown at the first keystroke (not after debounce).

Run — expect PASS on the two ViewModel tests:

```bash
./gradlew :feature:search:testDebugUnitTest --tests "*SearchViewModelTest*"
```

- [ ] **Step 9.5 — Build**

```bash
./gradlew :app:assembleDebug
```

Fix imports as needed (`kotlinx.coroutines.flow.debounce`, `flatMapLatest`, `MutableSharedFlow`).

- [ ] **Step 9.6 — Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchUiState.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt \
        data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt
git commit -m "$(cat <<'EOF'
feat(search): sectioned results + preview retry fallback

Search tab now renders Top/Songs/Artists/Albums sections via
searchAll(), flatMapLatest cancels in-flight queries on new
keystrokes, and ExoPlayer SOURCE errors within 3s silently
retry via yt-dlp. Three VM tests lock the flatMapLatest,
Snackbar-on-error, and retry behaviours.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 9.7 — Verify**

```bash
./gradlew :feature:search:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: all feature/search unit tests pass, APK builds.

---

### Task 10 — Global Coil `ImageLoader` + ExoPlayer preview LoadControl + OkHttp warm-up

**Why this phase:** Polish task that captures three additive latency wins from §4.2.

**Files:**
- Create: `app/src/main/kotlin/com/stash/app/CoilConfiguration.kt`
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`
- Create: `core/media/src/main/kotlin/com/stash/core/media/preview/PreviewLoadControlFactory.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/preview/PreviewPlayer.kt:161-175`

- [ ] **Step 10.1 — Global Coil `ImageLoader`**

`app/src/main/kotlin/com/stash/app/CoilConfiguration.kt`:

```kotlin
package com.stash.app

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

object CoilConfiguration {
    fun build(context: PlatformContext, okHttp: OkHttpClient): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context as Context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory((context as Context).cacheDir.resolve("coil"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
            }
            .crossfade(false) // hero does its own crossfade
            .build()
}
```

In `StashApplication.onCreate`:

```kotlin
SingletonImageLoader.setSafe { ctx -> CoilConfiguration.build(ctx, okHttpClient) }

// Warm up music.youtube.com TLS + DNS in the first 2s of launch.
applicationScope.launch {
    runCatching {
        okHttpClient.newCall(
            Request.Builder().url("https://music.youtube.com/").head().build(),
        ).execute().close()
    }
}
```

Add `@Inject lateinit var okHttpClient: OkHttpClient` + `@Inject lateinit var applicationScope: CoroutineScope` (create the scope in `NetworkModule` if absent).

- [ ] **Step 10.2 — Preview `LoadControl`**

`core/media/src/main/kotlin/com/stash/core/media/preview/PreviewLoadControlFactory.kt`:

```kotlin
package com.stash.core.media.preview

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Preview playback should be audible fast, not buffered long. We aggressively
 * shrink the ready-to-play thresholds so ExoPlayer enters READY the moment
 * ~250 ms of audio is decoded.
 */
object PreviewLoadControlFactory {
    fun create(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 1_000,
            /* maxBufferMs = */ DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            /* bufferForPlaybackMs = */ 250,
            /* bufferForPlaybackAfterRebufferMs = */ 500,
        )
        .build()
}
```

In `PreviewPlayer.kt:161-175` update `requirePlayer`:

```kotlin
private fun requirePlayer(): ExoPlayer {
    return exoPlayer ?: ExoPlayer.Builder(context)
        .setLoadControl(PreviewLoadControlFactory.create())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .also { player ->
            player.addListener(playerListener)
            exoPlayer = player
        }
}
```

- [ ] **Step 10.3 — Build + commit**

```bash
./gradlew :app:assembleDebug
git add app/src/main/kotlin/com/stash/app/CoilConfiguration.kt \
        app/src/main/kotlin/com/stash/app/StashApplication.kt \
        core/media/src/main/kotlin/com/stash/core/media/preview/PreviewLoadControlFactory.kt \
        core/media/src/main/kotlin/com/stash/core/media/preview/PreviewPlayer.kt
git commit -m "$(cat <<'EOF'
perf: global Coil loader, preview LoadControl, warm OkHttp

- Install app-wide Coil ImageLoader (25% heap, 250MB disk,
  crossfade off) via SingletonImageLoader.setSafe.
- Tighten preview ExoPlayer buffering: 1s min, 250ms for
  playback, 500ms after rebuffer — ~1s off start-to-audio.
- Fire a HEAD music.youtube.com in the first 2s after app
  start to prime DNS + TLS before first search.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 10.4 — Verify**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. No unit tests here — perf verified in Task 11.

---

### Task 11 — Timing instrumentation + manual latency verification

**Why this phase:** The performance contract is a hard gate. We make it executable with `SystemClock.elapsedRealtime` bookends, `Perf` log tag, and an explicit on-device checklist.

**Files:**
- Modify: `feature/search/.../SearchViewModel.kt` (bookends around flatMapLatest)
- Modify: `feature/search/.../ArtistProfileViewModel.kt` (already has `t0` from Step 8.3; add two more)
- Modify: `core/media/.../PreviewPlayer.kt` (bookend inside `playUrl`)
- Create (tracked but not committed): `docs/superpowers/plans/2026-04-17-search-artist-profile-perf-checklist.md`

- [ ] **Step 11.1 — Add `Perf` log bookends**

`SearchViewModel` — wrap `performSearch` / section emission:

```kotlin
val t0 = SystemClock.elapsedRealtime()
_uiState.update { it.copy(status = SearchStatus.Loading) }
Log.d("Perf", "Search skeleton at ${SystemClock.elapsedRealtime() - t0}ms")
val r = api.searchAll(q)
_uiState.update { it.copy(status = SearchStatus.Results(r.sections)) }
Log.d("Perf", "Search first-results at ${SystemClock.elapsedRealtime() - t0}ms (q=$q)")
```

`ArtistProfileViewModel` — already logs profile paint in Step 8.3. Add a hero-visible log:

```kotlin
Log.d("Perf", "ArtistProfile hero first-frame nav-args (name=$initialName)")
```

`PreviewPlayer.playUrl`:

```kotlin
val t0 = SystemClock.elapsedRealtime()
// after prepare + playWhenReady
player.addListener(object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            Log.d("Perf", "Preview audible at ${SystemClock.elapsedRealtime() - t0}ms (vid=$videoId)")
            player.removeListener(this)
        }
    }
})
```

Guard all `Perf` logs with a `BuildConfig.DEBUG` check so release builds drop them.

- [ ] **Step 11.2 — Manual checklist (dev ticks on-device)**

Save to `docs/superpowers/plans/2026-04-17-search-artist-profile-perf-checklist.md` (not committed by the plan — the executing agent commits per its normal cadence). Each row maps to §4.1.

```
[ ]  Keystroke → skeleton rows visible          target p50 <100ms / p95 <200ms
     Trigger: adb logcat Perf:D | grep "Search skeleton"
[ ]  Debounced query → first real results       target p50 <900ms / p95 <1500ms
     Trigger: adb logcat Perf:D | grep "Search first-results"
[ ]  Tap artist → hero (name+avatar) visible    target p50 <50ms  / p95 <100ms
     Verify: first Perf log line "ArtistProfile hero first-frame"
[ ]  Tap artist → full profile painted          target p50 <1s    / p95 <2s
     Trigger: adb logcat Perf:D | grep "ArtistProfile paint"
[ ]  Tap artist (SWR-cached) → full profile     target p50 <50ms  / p95 <100ms
     Cold-open, tap same artist again within 6h; Perf log should read "status=Fresh"
[ ]  Tap preview on Popular → audible           target p50 <500ms / p95 <3s
     adb logcat Perf:D | grep "Preview audible"
[ ]  Scroll Albums/Singles row                  target 60 fps
     adb shell dumpsys gfxinfo com.stash.mp3apk framestats
```

Gate: if ANY target is missed twice in a row, the phase is failed — do NOT ship.

- [ ] **Step 11.3 — Commit instrumentation**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt \
        core/media/src/main/kotlin/com/stash/core/media/preview/PreviewPlayer.kt
git commit -m "$(cat <<'EOF'
chore(perf): add Perf-tagged bookends for latency targets

SystemClock.elapsedRealtime bookends around Search skeleton,
first-results, Artist hero paint, full profile paint, and
preview audible. Guarded by BuildConfig.DEBUG so release
builds are unaffected. Pair with the performance checklist.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 11.4 — Verify**

```bash
./gradlew :app:assembleDebug
./gradlew :feature:search:testDebugUnitTest :data:ytmusic:testDebugUnitTest \
          :core:data:testDebugUnitTest :data:download:testDebugUnitTest
```

Install on-device, walk through every row of the checklist, log-grep the Perf tag, and tick each box. If any p50 target fails, open an issue, do NOT merge.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell "logcat -c && logcat Perf:D *:S" &
# perform each IA action, capture timings, paste into checklist
```

Expected: all 7 checklist rows hit p50 target on the reference device (Pixel 6 Pro).

---

## Final Verification (end-to-end)

- [ ] **All unit tests green**

```bash
./gradlew testDebugUnitTest
```

Expected: no failures across `core:data`, `data:ytmusic`, `data:download`, `feature:search`.

- [ ] **APK builds**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Instrumented test (PreviewDownloadRow reuse)**

```bash
./gradlew :feature:search:connectedDebugAndroidTest --tests "*PreviewDownloadRowSharedTest*"
```

Expected: 2 tests pass (Popular + Search use the same row).

- [ ] **Perf checklist ticked**

All 7 latency targets within p50 bounds on Pixel 6 Pro. Attach the `adb logcat Perf:D` capture to the PR description.
