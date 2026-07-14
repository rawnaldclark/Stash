# Home Qobuz Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the discovery-first Home tab with genre-filterable Qobuz content — New Releases, Community Playlists, and Top Albums rows fed by the existing qbdlx token pool.

**Architecture:** New `HomeDiscoveryRepository` (core:data) acquires a pooled token and calls three unsigned Qobuz featured endpoints via new `QbdlxApiClient` methods, mapping into existing `AlbumSummary` + a new `PlaylistSummary`, with an in-memory TTL cache keyed by `"$type:$genreId"` and fail-soft error handling (a failed row is hidden, the download breaker is never touched). The already-built `AlbumSquareCard` / `RankedAlbumList` / `CrispChipRow` components render the rows; taps reuse the existing `AlbumDiscoveryScreen` via a new `AlbumSource.QOBUZ_PLAYLIST` variant that maps `playlist/get` into `AlbumDetail`.

**Tech Stack:** Kotlin, Hilt, Coroutines/Flow, kotlinx.serialization, OkHttp, Jetpack Compose, JUnit4 + MockWebServer + Truth.

**Contract:** Fully verified against the live Qobuz API — see spec §14 (`docs/superpowers/specs/2026-07-14-home-qobuz-discovery-design.md`). No device probe needed.

---

## File Structure

**New files:**
- `core/data/src/main/kotlin/com/stash/core/data/discovery/HomeDiscoveryRepository.kt` — **interface** (returns `AlbumSummary`/`PlaylistSummary`).
- `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/HomeDiscoveryRepositoryImpl.kt` — impl: token acquisition, 3 fetch methods, TTL cache, mapping, fail-soft.
- `core/data/src/main/kotlin/com/stash/core/data/discovery/GenreCatalog.kt` — static curated genre chip list (label + pinned id); pure data, no qbdlx dep.
- Tests alongside (impl test in `data:download`).

**Modified files:**
- `data/download/.../qbdlx/QbdlxCatalogModels.kt` — add playlist DTOs (albums reuse `QbdlxAlbumItem`).
- `data/download/.../qbdlx/QbdlxApiClient.kt` — add `getFeaturedAlbums`, `getFeaturedPlaylists`, `getPlaylist`.
- `data/download/.../qbdlx/di/QbdlxModule.kt` — `@Binds HomeDiscoveryRepositoryImpl → HomeDiscoveryRepository` (next to the existing `QobuzAlbumFetcher` binding).
- `data/ytmusic/.../model/SearchAllResults.kt` — add `QOBUZ_PLAYLIST` to `AlbumSource`; add `PlaylistSummary`.
- `core/data/.../discography/QobuzAlbumFetcher.kt` (interface) + `data/download/.../qbdlx/QobuzAlbumFetcherImpl.kt` — add `getPlaylist(id): AlbumDetail`.
- `core/data/.../cache/AlbumCache.kt` — route `QOBUZ_PLAYLIST`.
- `feature/home/.../HomeUiState.kt` + `HomeViewModel.kt` — genre filter + 3 row flows.
- `feature/home/.../HomeScreen.kt` — render chip row + 3 content rows.
- `app/.../navigation/StashNavHost.kt` — wire Home `onNavigateToAlbum`.

**Boundary note (LOAD-BEARING):** `core:data` does **not** depend on `data:download` — verified. The qbdlx layer (`QbdlxApiClient`, `QbdlxCredentialStore`, all DTOs) lives in `data:download`, which depends on `core:data` + `data:ytmusic`. So `HomeDiscoveryRepository` must follow the exact `QobuzAlbumFetcher` inversion: **interface in `core:data`** (signatures use only `data:ytmusic` models it can see), **impl + `@Binds` in `data:download`** (where it can import the qbdlx client + DTOs). Do NOT put the impl in core:data — it won't compile. Map DTOs → `AlbumSummary`/`PlaylistSummary` in the impl; never leak DTOs above the interface.

---

### Task 1: Qobuz playlist DTOs + model additions

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCatalogModels.kt`
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/SearchAllResults.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxFeaturedParseTest.kt`

Album featured items reuse the existing `QbdlxAlbumItem` + `QbdlxAlbumList` (the `{albums:{items}}` envelope is identical). Only playlist DTOs are new.

- [ ] **Step 1: Write the failing parse test**

Use the real probed shapes (spec §14). `QbdlxImage`/`QbdlxPerformer` already exist.

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class QbdlxFeaturedParseTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test fun `parses album getFeatured envelope`() {
        val body = """{"albums":{"total":482,"items":[
            {"id":"a8gc9ch2rp7kl","title":"POWER HOUSE",
             "image":{"small":"s","thumbnail":"t","large":"L"},
             "artist":{"id":5771332,"name":"Magi Merlin"},
             "release_date_original":"2026-07-10","tracks_count":10}]}}"""
        val r = json.decodeFromString<QbdlxArtistAlbumsResponse>(body)
        val a = r.albums.items.single()
        assertThat(a.id).isEqualTo("a8gc9ch2rp7kl")
        assertThat(a.image?.large).isEqualTo("L")
        assertThat(a.release_date_original).isEqualTo("2026-07-10")
    }

    @Test fun `parses playlist getFeatured envelope`() {
        val body = """{"playlists":{"total":6360,"items":[
            {"id":67048110,"name":"Brazilcore II","owner":{"name":"Qobuz France"},
             "tracks_count":58,"images300":["https://img/300.jpg"]}]}}"""
        val p = json.decodeFromString<QbdlxFeaturedPlaylistsResponse>(body).playlists.items.single()
        assertThat(p.id).isEqualTo(67048110L)
        assertThat(p.name).isEqualTo("Brazilcore II")
        assertThat(p.owner?.name).isEqualTo("Qobuz France")
        assertThat(p.images300.firstOrNull()).isEqualTo("https://img/300.jpg")
    }

    @Test fun `parses playlist get detail with tracks`() {
        val body = """{"id":67048110,"name":"Brazilcore II","owner":{"name":"Qobuz France"},
            "tracks_count":58,"images300":["https://img/300.jpg"],
            "tracks":{"total":58,"items":[
              {"id":108364435,"title":"Funky Tamborim","performer":{"name":"Tania Maria"},
               "duration":195,"album":{"title":"Love Explosion","image":{"large":"AL"}}}]}}"""
        val d = json.decodeFromString<QbdlxPlaylistDetailResponse>(body)
        assertThat(d.name).isEqualTo("Brazilcore II")
        val t = d.tracks.items.single()
        assertThat(t.performer?.name).isEqualTo("Tania Maria")
        assertThat(t.album?.image?.large).isEqualTo("AL")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxFeaturedParseTest*"`
Expected: FAIL — `QbdlxFeaturedPlaylistsResponse` / `QbdlxPlaylistDetailResponse` unresolved.

- [ ] **Step 3: Add the playlist DTOs to `QbdlxCatalogModels.kt`**

```kotlin
// ── playlist/getFeatured?type=editor-picks ─────────────────────────────
@Serializable
data class QbdlxFeaturedPlaylistsResponse(val playlists: QbdlxPlaylistList = QbdlxPlaylistList())

@Serializable
data class QbdlxPlaylistList(val items: List<QbdlxPlaylistItem> = emptyList())

@Serializable
data class QbdlxPlaylistItem(
    val id: Long = 0,
    val name: String = "",
    val owner: QbdlxOwner? = null,
    val tracks_count: Int = 0,
    val images300: List<String> = emptyList(),   // square covers, 300px
)

@Serializable
data class QbdlxOwner(val name: String = "")

// ── playlist/get?extra=tracks ──────────────────────────────────────────
@Serializable
data class QbdlxPlaylistDetailResponse(
    val id: Long = 0,
    val name: String = "",
    val owner: QbdlxOwner? = null,
    val tracks_count: Int = 0,
    val images300: List<String> = emptyList(),
    val tracks: QbdlxPlaylistTrackList = QbdlxPlaylistTrackList(),
)

@Serializable
data class QbdlxPlaylistTrackList(val items: List<QbdlxPlaylistTrackItem> = emptyList())

@Serializable
data class QbdlxPlaylistTrackItem(
    val id: Long = 0,
    val title: String = "",
    val performer: QbdlxPerformer? = null,
    val duration: Int = 0,                        // seconds
    val album: QbdlxTrackAlbumRef? = null,
)

@Serializable
data class QbdlxTrackAlbumRef(val title: String = "", val image: QbdlxImage? = null)
```

- [ ] **Step 4: Add `QOBUZ_PLAYLIST` + `PlaylistSummary` to `SearchAllResults.kt`**

```kotlin
enum class AlbumSource { YOUTUBE, QOBUZ, QOBUZ_PLAYLIST }
```
```kotlin
/** Minimal playlist identity for the Home discovery playlist row. */
@Serializable
data class PlaylistSummary(
    val id: String,
    val title: String,
    val curator: String,
    val thumbnailUrl: String?,
    val trackCount: Int,
)
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxFeaturedParseTest*"`
Expected: PASS (3/3).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(qbdlx): Qobuz featured/playlist DTOs + PlaylistSummary + QOBUZ_PLAYLIST source"
```

---

### Task 2: `QbdlxApiClient` featured methods

**Files:**
- Modify: `data/download/.../qbdlx/QbdlxApiClient.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClientTest.kt` (append)

All three are unsigned GETs reusing the private `get(url, token)` — mirror the existing `search`/`getAlbum` methods. `genre_id` is omitted when null (all-genres).

- [ ] **Step 1: Write failing MockWebServer tests**

Mirror the existing tests in `QbdlxApiClientTest.kt` (they already stand up a MockWebServer + point `client.baseUrl`/`appId` at it). Add:

```kotlin
@Test fun `getFeaturedAlbums sends type + genre_id + app_id and parses`() = runTest {
    server.enqueue(MockResponse().setBody("""{"albums":{"items":[
        {"id":"a1","title":"T","image":{"large":"L"},"artist":{"name":"AR"},
         "release_date_original":"2026-01-02","tracks_count":9}]}}"""))
    val items = client.getFeaturedAlbums("best-sellers", genreId = 112, token = "tok")
    val req = server.takeRequest()
    assertThat(req.path).contains("album/getFeatured")
    assertThat(req.path).contains("type=best-sellers")
    assertThat(req.path).contains("genre_id=112")
    assertThat(req.path).contains("app_id=")
    assertThat(items.single().title).isEqualTo("T")
}

@Test fun `getFeaturedAlbums omits genre_id when null`() = runTest {
    server.enqueue(MockResponse().setBody("""{"albums":{"items":[]}}"""))
    client.getFeaturedAlbums("new-releases-full", genreId = null, token = "tok")
    assertThat(server.takeRequest().path).doesNotContain("genre_id")
}

@Test fun `getFeaturedPlaylists parses playlists`() = runTest {
    server.enqueue(MockResponse().setBody("""{"playlists":{"items":[
        {"id":5,"name":"P","owner":{"name":"O"},"tracks_count":3,"images300":["i"]}]}}"""))
    val items = client.getFeaturedPlaylists(genreId = null, token = "tok")
    assertThat(server.takeRequest().path).contains("playlist/getFeatured")
    assertThat(items.single().name).isEqualTo("P")
}

@Test fun `getPlaylist sends extra=tracks and parses detail`() = runTest {
    server.enqueue(MockResponse().setBody("""{"id":5,"name":"P","owner":{"name":"O"},
        "images300":["i"],"tracks":{"items":[
          {"id":9,"title":"S","performer":{"name":"AR"},"duration":100,
           "album":{"title":"AL","image":{"large":"L"}}}]}}"""))
    val d = client.getPlaylist("5", token = "tok")
    val req = server.takeRequest()
    assertThat(req.path).contains("playlist/get")
    assertThat(req.path).contains("extra=tracks")
    assertThat(d.tracks.items.single().title).isEqualTo("S")
}
```

- [ ] **Step 2: Run to verify fail** — `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxApiClientTest*"` → FAIL (unresolved methods).

- [ ] **Step 3: Implement the three methods** (place after `getAlbum`)

```kotlin
/** Featured albums (new-releases-full / best-sellers). Unsigned. genreId null = all. */
suspend fun getFeaturedAlbums(type: String, genreId: Int?, token: String, limit: Int = 20): List<QbdlxAlbumItem> =
    withContext(Dispatchers.IO) {
        val url = "$baseUrl/api.json/0.2/album/getFeatured".toHttpUrl().newBuilder()
            .addQueryParameter("type", type)
            .apply { if (genreId != null) addQueryParameter("genre_id", genreId.toString()) }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("app_id", appId)
            .build()
        val body = get(url.toString(), token)
        runCatching { json.decodeFromString<QbdlxArtistAlbumsResponse>(body).albums.items }.getOrDefault(emptyList())
    }

/** Featured playlists (editor-picks). Unsigned. genreId null = all. */
suspend fun getFeaturedPlaylists(genreId: Int?, token: String, limit: Int = 15): List<QbdlxPlaylistItem> =
    withContext(Dispatchers.IO) {
        val url = "$baseUrl/api.json/0.2/playlist/getFeatured".toHttpUrl().newBuilder()
            .addQueryParameter("type", "editor-picks")
            .apply { if (genreId != null) addQueryParameter("genre_id", genreId.toString()) }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("app_id", appId)
            .build()
        val body = get(url.toString(), token)
        runCatching { json.decodeFromString<QbdlxFeaturedPlaylistsResponse>(body).playlists.items }.getOrDefault(emptyList())
    }

/** Playlist detail incl. tracks. Unsigned. */
suspend fun getPlaylist(playlistId: String, token: String, limit: Int = 500): QbdlxPlaylistDetailResponse =
    withContext(Dispatchers.IO) {
        val url = "$baseUrl/api.json/0.2/playlist/get".toHttpUrl().newBuilder()
            .addQueryParameter("playlist_id", playlistId)
            .addQueryParameter("extra", "tracks")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("app_id", appId)
            .build()
        json.decodeFromString<QbdlxPlaylistDetailResponse>(get(url.toString(), token))
    }
```

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(qbdlx): getFeaturedAlbums/Playlists + getPlaylist API methods"`

---

### Task 3: Playlist → AlbumDetail (fetcher + cache routing)

**Files:**
- Modify: `core/data/.../discography/QobuzAlbumFetcher.kt` (interface) + `data/download/.../qbdlx/QobuzAlbumFetcherImpl.kt` (impl)
- Modify: `core/data/.../cache/AlbumCache.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QobuzAlbumFetcherImplTest.kt` (append)

**Read `QobuzAlbumFetcherImpl.kt` first** to match how `getAlbum` builds `AlbumDetail` (it wraps `QbdlxApiClient.getAlbum` + a token from `QbdlxCredentialStore`). `getPlaylist` mirrors it: acquire token, call `apiClient.getPlaylist`, map.

- [ ] **Step 1: Failing test** — `getPlaylist` maps `playlist/get` JSON → `AlbumDetail` (title=name, artist=owner.name, each track title/artist=performer/duration/art=album.image.large). Mirror the existing `getAlbum` test's token-store + client stubbing.

- [ ] **Step 2: Verify fail.**

- [ ] **Step 3: Implement.** Interface: add `suspend fun getPlaylist(playlistId: String): AlbumDetail`. Impl:

```kotlin
override suspend fun getPlaylist(playlistId: String): AlbumDetail {
    val token = credentialStore.activeToken() ?: error("no live qbdlx token")
    val d = apiClient.getPlaylist(playlistId, token)
    return AlbumDetail(
        id = playlistId,
        title = d.name,
        artist = d.owner?.name.orEmpty(),
        artistId = null,
        thumbnailUrl = d.images300.firstOrNull(),
        year = null,
        tracks = d.tracks.items.map { t ->
            // videoId = "" for Qobuz; the QOBUZ play path resolves by metadata.
            TrackSummary(
                videoId = "",
                title = t.title,
                artist = t.performer?.name.orEmpty(),
                album = t.album?.title,
                durationSeconds = t.duration.toDouble(),
                thumbnailUrl = t.album?.image?.large,
            )
        },
        moreByArtist = emptyList(),
    )
}
```
Constructor confirmed: `AlbumDetail(id, title, artist, artistId, thumbnailUrl, year, tracks, moreByArtist)`; `TrackSummary(videoId, title, artist, album, durationSeconds: Double, thumbnailUrl)`. If `getAlbum` rotates tokens on `QbdlxAuthException`, mirror that; otherwise the single-token path is fine (discovery is fail-soft upstream). **Read `QobuzAlbumFetcherImpl.getAlbum` first** to match its exact token handling + constructor style.

`AlbumCache.kt`: add the branch —
```kotlin
AlbumSource.QOBUZ_PLAYLIST -> qobuzFetcher.getPlaylist(id)
```

- [ ] **Step 4: Verify pass.**

- [ ] **Step 5: Commit** — `git commit -m "feat(discovery): map Qobuz playlist to AlbumDetail via QOBUZ_PLAYLIST source"`

---

### Task 4: `GenreCatalog` + `HomeDiscoveryRepository` (interface + impl)

**Files:**
- Create: `core/data/.../discovery/GenreCatalog.kt`
- Create: `core/data/.../discovery/HomeDiscoveryRepository.kt` (interface only)
- Create: `data/download/.../qbdlx/HomeDiscoveryRepositoryImpl.kt` (impl)
- Modify: `data/download/.../qbdlx/di/QbdlxModule.kt` (`@Binds`)
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/HomeDiscoveryRepositoryImplTest.kt`

See the **Boundary note** — interface in core:data, impl in data:download (mirrors `QobuzAlbumFetcher`). The test lives in data:download where the qbdlx test deps (mockk, MockWebServer) already exist.

- [ ] **Step 1: `GenreCatalog.kt`** (static; IDs from spec §5)

```kotlin
package com.stash.core.data.discovery

data class Genre(val label: String, val genreId: Int?)   // null id = All

object GenreCatalog {
    val GENRES = listOf(
        Genre("All", null),
        Genre("Pop/Rock", 112),
        Genre("Hip-Hop", 133),
        Genre("Electronic", 64),
        Genre("Jazz", 80),
        Genre("Classical", 10),
        Genre("Soul/R&B", 127),
        Genre("Metal", 116),
    )
}
```

- [ ] **Step 2: Failing repo tests**

```kotlin
class HomeDiscoveryRepositoryTest {
    private val client = mockk<QbdlxApiClient>()
    private val store = mockk<QbdlxCredentialStore>(relaxed = true)
    private lateinit var repo: HomeDiscoveryRepositoryImpl   // concrete impl (data:download)

    @Before fun setup() {
        every { store.activeToken() } returns "tok"
        repo = HomeDiscoveryRepositoryImpl(client, store)
    }

    @Test fun `newReleases maps items to AlbumSummary with QOBUZ source`() = runTest {
        coEvery { client.getFeaturedAlbums("new-releases-full", null, "tok", any()) } returns
            listOf(QbdlxAlbumItem(id = "a1", title = "T", artist = QbdlxPerformer(name = "AR"),
                image = QbdlxImage(large = "L"), release_date_original = "2026-01-02"))
        val out = repo.newReleases(genreId = null)
        assertThat(out.single().id).isEqualTo("a1")
        assertThat(out.single().source).isEqualTo(AlbumSource.QOBUZ)
        assertThat(out.single().year).isEqualTo("2026")          // first 4 of release date
        assertThat(out.single().thumbnailUrl).isEqualTo("L")
    }

    @Test fun `second call within TTL is served from cache (no second network call)`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any(), any()) } returns emptyList()
        repo.topAlbums(null); repo.topAlbums(null)
        coVerify(exactly = 1) { client.getFeaturedAlbums("best-sellers", null, "tok", any()) }
    }

    @Test fun `different genre is a separate cache key`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any(), any()) } returns emptyList()
        repo.topAlbums(null); repo.topAlbums(112)
        coVerify(exactly = 2) { client.getFeaturedAlbums("best-sellers", any(), "tok", any()) }
    }

    @Test fun `network error yields empty list (fail-soft), not a throw`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any(), any()) } throws QbdlxApiException(500)
        assertThat(repo.newReleases(null)).isEmpty()
    }

    @Test fun `401 rotates the token then retries once`() = runTest {
        every { store.activeToken() } returnsMany listOf("dead", "live")
        coEvery { client.getFeaturedAlbums(any(), any(), "dead", any()) } throws QbdlxAuthException(401)
        coEvery { client.getFeaturedAlbums(any(), any(), "live", any()) } returns emptyList()
        repo.newReleases(null)
        verify { store.markDead("dead") }
    }

    @Test fun `no live token yields empty list`() = runTest {
        every { store.activeToken() } returns null
        assertThat(repo.communityPlaylists(null)).isEmpty()
    }
}
```

- [ ] **Step 3: Verify fail.**

- [ ] **Step 4a: Interface in `core:data`** — `core/data/.../discovery/HomeDiscoveryRepository.kt`

```kotlin
package com.stash.core.data.discovery

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary

/** Home discovery rows from Qobuz featured. Impl in data:download (needs the
 *  qbdlx client). Fail-soft: a failed row returns emptyList, never throws. */
interface HomeDiscoveryRepository {
    suspend fun newReleases(genreId: Int?): List<AlbumSummary>
    suspend fun topAlbums(genreId: Int?): List<AlbumSummary>
    suspend fun communityPlaylists(genreId: Int?): List<PlaylistSummary>
}
```

- [ ] **Step 4b: Impl in `data:download`** — `data/download/.../qbdlx/HomeDiscoveryRepositoryImpl.kt`

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeDiscoveryRepositoryImpl @Inject constructor(
    private val client: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
) : HomeDiscoveryRepository {

    private class Entry(val at: Long, val value: List<Any?>)
    // ponytail: ConcurrentHashMap, not a Mutex — the check-then-load race just
    // costs a rare duplicate fetch (idempotent, fail-soft), never corruption.
    private val cache = ConcurrentHashMap<String, Entry>()
    private val ttlMs = 3 * 60 * 60 * 1000L

    override suspend fun newReleases(genreId: Int?) = albums("new-releases-full", genreId)
    override suspend fun topAlbums(genreId: Int?) = albums("best-sellers", genreId)
    override suspend fun communityPlaylists(genreId: Int?): List<PlaylistSummary> =
        cached("playlists:$genreId") {
            withToken { tok -> client.getFeaturedPlaylists(genreId, tok) }.map { it.toPlaylistSummary() }
        }

    private suspend fun albums(type: String, genreId: Int?): List<AlbumSummary> =
        cached("$type:$genreId") {
            withToken { tok -> client.getFeaturedAlbums(type, genreId, tok) }.map { it.toAlbumSummary() }
        }

    // Fail-soft cache: any throw → empty (not cached); success incl. empty → cached.
    private suspend fun <T> cached(key: String, load: suspend () -> List<T>): List<T> {
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < ttlMs }?.let {
            @Suppress("UNCHECKED_CAST") return it.value as List<T>
        }
        return try {
            load().also { cache[key] = Entry(System.currentTimeMillis(), it) }
        } catch (e: Exception) {
            emptyList()                 // fail-soft; row hidden; download breaker untouched
        }
    }

    // One 401 rotation. Empty when no live token (caught by cached()).
    private suspend fun <T> withToken(call: suspend (String) -> List<T>): List<T> {
        val tok = credentialStore.activeToken() ?: return emptyList()
        return try {
            call(tok)
        } catch (e: QbdlxAuthException) {
            credentialStore.markDead(tok)
            val next = credentialStore.activeToken() ?: return emptyList()
            call(next)
        }
    }

    private fun QbdlxAlbumItem.toAlbumSummary() = AlbumSummary(
        id = id, title = title, artist = artist?.name.orEmpty(),
        thumbnailUrl = image?.large ?: image?.small,
        year = release_date_original?.take(4), source = AlbumSource.QOBUZ,
    )
    private fun QbdlxPlaylistItem.toPlaylistSummary() = PlaylistSummary(
        id = id.toString(), title = name, curator = owner?.name.orEmpty(),
        thumbnailUrl = images300.firstOrNull(), trackCount = tracks_count,
    )
}
```

- [ ] **Step 4c: `@Binds` in `QbdlxModule.kt`** — add next to the existing `QobuzAlbumFetcher` binding:

```kotlin
@Binds @Singleton
abstract fun bindHomeDiscoveryRepository(impl: HomeDiscoveryRepositoryImpl): HomeDiscoveryRepository
```
(If `QbdlxModule` is an `object` with `@Provides`, follow its existing style for the `QobuzAlbumFetcher` binding instead — read the file first.)

Notes: the impl test lives in `data:download` (mockk + MockWebServer are already test deps there — the qbdlx tests use them). `QbdlxApiException` (non-401) propagates out of `withToken` → caught by `cached()` → empty. `AlbumSummary`/`PlaylistSummary` field names must match `SearchAllResults.kt` (Task 1) exactly.

- [ ] **Step 5: Verify pass.**

- [ ] **Step 6: Commit** — `git commit -m "feat(discovery): HomeDiscoveryRepository (TTL cache, fail-soft, token rotate) + GenreCatalog"`

---

### Task 5: HomeViewModel + HomeUiState — genre filter + row flows

**Files:**
- Modify: `feature/home/.../HomeUiState.kt`, `HomeViewModel.kt`
- Test: `feature/home/src/test/kotlin/com/stash/feature/home/HomeViewModelDiscoveryTest.kt`

**Read the current `HomeViewModel`/`HomeUiState` first** (Phase 1 shape: `hero`, `isLoading`, chrome). Add discovery without disturbing the hero.

- [ ] **Step 1: Failing tests**
  - Selecting a genre re-derives all three rows (repo called with the new genreId).
  - Default genre = All (genreId null) on init.
  - A row whose repo call returns empty is exposed as an empty list (screen hides it).

- [ ] **Step 2: Verify fail.**

- [ ] **Step 3: Implement.**
  - `HomeUiState`: add `genres: List<Genre> = GenreCatalog.GENRES`, `selectedGenre: String = "All"`, `newReleases: List<AlbumSummary>`, `topAlbums: List<AlbumSummary>`, `playlists: List<PlaylistSummary>` (or a nested `discovery` holder — match the existing style).
  - `HomeViewModel`: inject `HomeDiscoveryRepository`. A `MutableStateFlow<Int?> genreFilter` (null = All). Three flows via `genreFilter.mapLatest { repo.newReleases(it) }` etc. (or one `flatMapLatest` building a triple), `stateIn`. `fun onSelectGenre(label: String)` sets `selectedGenre` + resolves label→genreId via `GenreCatalog`. `fun onNavigateToAlbum` is a screen callback, not VM state.
  - Keep it off the main thread (repo is `suspend`, flows collect on `viewModelScope`).

- [ ] **Step 4: Verify pass.**

- [ ] **Step 5: Commit** — `git commit -m "feat(home): genre filter + Qobuz discovery row flows in HomeViewModel"`

---

### Task 6: HomeScreen — render chip row + 3 content rows

**Files:**
- Modify: `feature/home/.../HomeScreen.kt`
- (No unit test — Compose UI; verified in device smoke, Task 8.)

**Read the current `HomeScreen` first.** Insert, in order: `CrispChipRow` (above/below the hero per spec §3) → New Releases (`AlbumSquareCard` in a `LazyRow`) → Community Playlists (`AlbumSquareCard`, `artist = curator`) → Top Albums (`RankedAlbumList`). Each row renders only `if (items.isNotEmpty())`.

- [ ] **Step 1: Chip row** — `CrispChipRow(chips = state.genres.map { it.label }, selected = state.selectedGenre, onSelect = viewModel::onSelectGenre)`.
- [ ] **Step 2: New Releases + Playlists** — `LazyRow` of `AlbumSquareCard(title, artist, thumbnailUrl, year, isLossless = true, onClick = { onNavigateToAlbum(it.toAlbumSummaryNav()) })`. Playlists map `PlaylistSummary` → an `AlbumSummary`-shaped nav arg with `source = QOBUZ_PLAYLIST`, `id = playlist.id`, `artist = curator`, `year = null`.
- [ ] **Step 3: Top Albums** — `RankedAlbumList(items = state.topAlbums.mapIndexed { i, a -> RankedAlbumUi(i + 1, a.title, a.artist, a.thumbnailUrl, movement = null) }, onClick = { … })`. Map the tapped `RankedAlbumUi` back to its `AlbumSummary` by index (keep the source list in scope).
- [ ] **Step 4: Section headers** — match Phase-1 Home's existing section-title style ("New Releases", "Qobuz Playlists", "Top Albums").
- [ ] **Step 5: Build** — `./gradlew :feature:home:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** — `git commit -m "feat(home): render Qobuz discovery rows + genre chips on Home"`

---

### Task 7: Navigation wiring

**Files:**
- Modify: `app/.../navigation/StashNavHost.kt`
- Modify: `feature/home/.../HomeScreen.kt` (add the `onNavigateToAlbum` param) + the Home route composable.

- [ ] **Step 1:** Give `HomeScreen` an `onNavigateToAlbum: (AlbumSummary) -> Unit` param (mirror the existing search/artist call sites — spec §9). In `StashNavHost`, wire the Home composable's `onNavigateToAlbum = { album -> navController.navigate(SearchAlbumRoute(browseId = album.id, title = album.title, artist = album.artist, thumbnailUrl = album.thumbnailUrl, year = album.year, source = album.source)) }` — copy the existing block verbatim.
- [ ] **Step 2: Build** `:app:compileDebugKotlin` → SUCCESS.
- [ ] **Step 3: Commit** — `git commit -m "feat(home): wire Home album/playlist taps to the detail screen"`

---

### Task 8: Full build, unit suite, device smoke

- [ ] **Step 1:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Targeted unit runs (avoid the pre-existing failures — memory `infra_preexisting_matcher_test_failures`): `:data:download`, `:core:data`, `:feature:home` test tasks with `--tests` filters for the new tests.
- [ ] **Step 3:** `:app:installDebug`. **Do not launch/screenshot if the phone is in active use** (user's daily device — check `dumpsys activity | grep topResumedActivity` is `com.stash.app.debug` or idle first).
- [ ] **Step 4: Device smoke checklist:** Home shows chips + New Releases + Playlists + Top Albums; tapping a chip re-filters all three; tapping an album opens the detail screen and plays lossless; tapping a playlist opens it (curator as subtitle) and plays; a genre with no results hides its row rather than showing an error.

---

## Review & Finish

After all tasks: dispatch a final code-review subagent over the whole diff, then use superpowers:finishing-a-development-branch. **Ship together with Phase 1 on `feat/design-language-home-redesign` / PR #267** (user's call — do not open a separate PR).
