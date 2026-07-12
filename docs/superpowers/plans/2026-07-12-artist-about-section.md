# Artist "About" Section Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Spotify-style "About" section (bio, social links, larger artist photo) to the Search-side artist page.

**Architecture:** A new `ArtistAboutEnricher` runs as a **second best-effort supplement** inside `ArtistCache.fetchAndMerge()`, concurrently with the existing Qobuz supplement and independently timeout-bounded so it never gates cold-miss first paint or fails the page. Bio comes from Last.fm `artist.getInfo` (via the existing proxy Worker); social links + the photo bridge come from **client-direct** MusicBrainz `url-rels`. The result rides in the existing `ArtistProfile` JSON cache blob as one nullable field — no DB migration.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines/Flow, kotlinx.serialization, OkHttp, JUnit + Truth + mockito 5.x (inline maker is the 5.x default) / mockk + kotlinx-coroutines-test. Cloudflare Worker (JS, `node --test`) for the one-line Last.fm allowlist change.

**Test-dependency prep (do first, part of Tasks 1 & 3):** `core:data` and `data:ytmusic` do **not** currently have Truth on their test classpath (their existing tests use `org.junit.Assert`). Each of those tasks adds `testImplementation(libs.truth)` (catalog entry `libs.truth` already exists) to the module's `build.gradle.kts` as its first step, before the test that needs it. `feature:search` already has Truth.

**Spec:** `docs/superpowers/specs/2026-07-12-artist-about-section-design.md`

**Test-run notes (this repo):** use the Gradle **daemon** (not `--no-daemon`) and always pass a `--tests` filter (see project infra notes). Module unit tests: `./gradlew :<module>:testDebugUnitTest --tests "<pattern>"`.

---

## File Structure

| File | Responsibility | Task |
| --- | --- | --- |
| `data/ytmusic/.../model/ArtistAbout.kt` (new) | `ArtistAbout`, `SocialLink` serializable models | 1 |
| `data/ytmusic/.../model/SearchAllResults.kt` (modify) | add `ArtistProfile.about` field | 1 |
| `infra/lastfm-proxy/src/index.js` (modify) | allow `artist.getinfo` | 2 |
| `core/data/.../lastfm/LastFmArtistInfo.kt` (new) | bio DTO + pure parser (HTML strip, placeholder→null, mbid) | 3 |
| `core/data/.../lastfm/LastFmApiClient.kt` (modify) | `getArtistInfo` read method | 3 |
| `core/data/.../musicbrainz/MusicBrainzClient.kt` (new) | interface + `SocialLink` mapper (pure) + name escaping | 4 |
| `core/data/.../musicbrainz/MusicBrainzClientImpl.kt` (new) | OkHttp impl, client-direct, UA + rate gate | 4 |
| `core/data/.../di/ArtistEnrichmentModule.kt` (new) | `@Binds` for `MusicBrainzClient` (Task 4) **and** `ArtistAboutEnricher` (Task 5) | 4, 5 |
| `core/data/.../cache/ArtistAboutEnricher.kt` (new) | orchestrates bio + socials → `ArtistAbout?`; `Noop` impl | 5 |
| `core/data/.../cache/ArtistCache.kt` (modify) | new ctor param + concurrent enrich in `fetchAndMerge` | 6 |
| `feature/search/.../AboutSection.kt` (new) | Compose section (bio clamp, social icons, photo) | 7 |
| `feature/search/.../ArtistProfileScreen.kt` (modify) | render `AboutSection` after "Fans also like" | 7 |

---

## Task 1: About model types + `ArtistProfile.about` field

**Files:**
- Create: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/ArtistAbout.kt`
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/SearchAllResults.kt` (`ArtistProfile`, ~line 94)
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/model/ArtistAboutSerializationTest.kt`

Colocated in `data:ytmusic` (not `core:data`) because `ArtistProfile` lives here and `core:data → data:ytmusic` is one-way — the reverse would cycle. `kind` is a **String** (not enum): the cache-hit decode at `ArtistCache.kt:137` is outside any try/catch and kotlinx throws on unknown enum constants, which would crash an older sideloaded APK reading a newer blob.

- [ ] **Step 0: Add Truth to the module's test classpath**

In `data/ytmusic/build.gradle.kts`, add to the `dependencies` block (alongside the other `testImplementation` lines):
```kotlin
    testImplementation(libs.truth)
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.data.ytmusic.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ArtistAboutSerializationTest {
    private val json = Json { ignoreUnknownKeys = true } // mirrors ArtistCache codec

    @Test fun `old blob without about deserializes to null`() {
        val legacy = """{"id":"a1","name":"X","avatarUrl":null,"subscribersText":null,
            "popular":[],"albums":[],"singles":[],"related":[]}""".trimIndent()
        val p = json.decodeFromString<ArtistProfile>(legacy)
        assertThat(p.about).isNull()
    }

    @Test fun `about round-trips`() {
        val about = ArtistAbout(
            bio = "hello",
            socials = listOf(SocialLink("instagram", "https://instagram.com/x")),
            photoUrl = null,
        )
        val p = ArtistProfile("a1", "X", null, null, emptyList(), emptyList(), emptyList(), emptyList(), about)
        val restored = json.decodeFromString<ArtistProfile>(json.encodeToString(ArtistProfile.serializer(), p))
        assertThat(restored.about).isEqualTo(about)
    }

    @Test fun `unknown social kind string does not crash decode`() {
        val blob = """{"id":"a1","name":"X","avatarUrl":null,"subscribersText":null,
            "popular":[],"albums":[],"singles":[],"related":[],
            "about":{"bio":null,"socials":[{"kind":"threads","url":"https://t.co/x"}],"photoUrl":null}}""".trimIndent()
        val p = json.decodeFromString<ArtistProfile>(blob)
        assertThat(p.about!!.socials.single().kind).isEqualTo("threads")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:ytmusic:testDebugUnitTest --tests "*ArtistAboutSerializationTest*"`
Expected: FAIL — `ArtistAbout` unresolved, `ArtistProfile` has no `about`.

- [ ] **Step 3: Create the model file**

`data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/ArtistAbout.kt`:
```kotlin
package com.stash.data.ytmusic.model

import kotlinx.serialization.Serializable

/** One external link. [kind] is a free string (e.g. "instagram","x","youtube",
 *  "website") — NOT an enum, so a value written by a newer app build never
 *  crashes an older build's cache decode. UI maps known kinds to icons. */
@Serializable
data class SocialLink(val kind: String, val url: String)

/** Spotify-style "About" data. All fields optional; the enricher returns `null`
 *  (not an empty instance) when nothing was found, so the UI gate is `about != null`. */
@Serializable
data class ArtistAbout(
    val bio: String? = null,
    val socials: List<SocialLink> = emptyList(),
    val photoUrl: String? = null, // upgrade only; UI coalesces with avatarUrl
)
```

- [ ] **Step 4: Add the field to `ArtistProfile`**

In `SearchAllResults.kt`, add as the last property of `ArtistProfile` (keep the default so old blobs decode):
```kotlin
    val related: List<ArtistSummary>,
    val about: ArtistAbout? = null,
)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :data:ytmusic:testDebugUnitTest --tests "*ArtistAboutSerializationTest*"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add data/ytmusic/build.gradle.kts \
        data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/ArtistAbout.kt \
        data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/SearchAllResults.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/model/ArtistAboutSerializationTest.kt
git commit -m "feat(artist): ArtistAbout/SocialLink model + ArtistProfile.about field"
```

---

## Task 2: Allow `artist.getinfo` in the Last.fm proxy Worker

**Files:**
- Modify: `infra/lastfm-proxy/src/index.js` (`ALLOWED_METHODS`, ~line 39)
- Test: `infra/lastfm-proxy/test/logic.test.js`

Without this, `getArtistInfo` gets a 400 from the proxy and the client silently falls back to a direct read-key call — no shared cache. The method is generic/unsigned. (It *does* send `api_key`, which is in `FORBIDDEN_PARAMS`, but `unsignedGet` strips `api_key` before the proxy call at `LastFmApiClient.kt:496` — so it clears the gate. Do not remove that strip.)

- [ ] **Step 1: Write the failing test** (append to `logic.test.js` — the suite runs on `node --test`, so use `node:assert`, NOT Vitest `expect`; `ALLOWED_METHODS` is already imported at the top of that file)

```js
test("artist.getinfo is allowlisted", () => {
    assert.ok(ALLOWED_METHODS.has("artist.getinfo"));
});
```
(`assert` is already imported as `node:assert/strict` at the top of `logic.test.js`; if not, add `import assert from "node:assert/strict";`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd infra/lastfm-proxy && npm test`
Expected: FAIL — set lacks `artist.getinfo`.

- [ ] **Step 3: Add the method**

In `ALLOWED_METHODS`, add `"artist.getinfo",` alongside the other `artist.*` entries.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd infra/lastfm-proxy && npm test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/lastfm-proxy/src/index.js infra/lastfm-proxy/test/logic.test.js
git commit -m "feat(worker): allow artist.getinfo so bios use the shared cache"
```

> **Deploy note (human, not code):** the Worker must be redeployed (`wrangler deploy`) for the allowlist change to take effect in production. Until then bios fall back to direct read-key calls (still functional, just uncached).

---

## Task 3: `LastFmApiClient.getArtistInfo` + bio parser

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmArtistInfo.kt` (DTO + pure parser)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt` (add method near `getArtistTopTags`, ~line 152)
- Test: `core/data/src/test/kotlin/com/stash/core/data/lastfm/LastFmArtistInfoParserTest.kt`

Mirror the existing `getArtistTopTags` read shape (sortedMap params, `autocorrect=1`, `unsignedGet(params, cacheable = true)`). Parser strips HTML + the "Read more" anchor, maps empty/placeholder → `null`, and extracts `mbid`.

- [ ] **Step 0: Add Truth to `core:data` test classpath** (once — reused by Tasks 3, 4, 5, 6)

In `core/data/build.gradle.kts`, add to the `dependencies` block:
```kotlin
    testImplementation(libs.truth)
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.lastfm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class LastFmArtistInfoParserTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).let { it as kotlinx.serialization.json.JsonObject }

    @Test fun `strips read-more anchor and tags`() {
        val r = parseArtistInfo(obj("""{"artist":{"mbid":"m1","bio":{"content":
            "Real bio here. <a href=\"http://last.fm\">Read more on Last.fm</a>"}}}"""))
        assertThat(r!!.bio).isEqualTo("Real bio here.")
        assertThat(r.mbid).isEqualTo("m1")
    }

    @Test fun `placeholder bio becomes null bio`() {
        val r = parseArtistInfo(obj("""{"artist":{"mbid":"","bio":{"content":
            "<a href=\"x\">Read more on Last.fm</a>"}}}"""))
        assertThat(r!!.bio).isNull()
        assertThat(r.mbid).isNull() // blank mbid normalised to null
    }

    @Test fun `missing artist returns null`() {
        assertThat(parseArtistInfo(obj("""{"error":6,"message":"not found"}"""))).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*LastFmArtistInfoParserTest*"`
Expected: FAIL — `parseArtistInfo` / `LastFmArtistInfo` unresolved.

- [ ] **Step 3: Create DTO + parser**

`core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmArtistInfo.kt`:
```kotlin
package com.stash.core.data.lastfm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parsed artist.getInfo result. [bio] is null when empty/placeholder. */
data class LastFmArtistInfo(val bio: String?, val mbid: String?)

private val READ_MORE = Regex("""<a[^>]*>\s*Read more on Last\.fm\s*</a>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]+>""")
private val PLACEHOLDER = Regex("""^.+ is (a|an) .*artist.*\.?$""", RegexOption.IGNORE_CASE)

fun parseArtistInfo(root: JsonObject): LastFmArtistInfo? {
    val artist = root["artist"]?.jsonObject ?: return null
    val mbid = artist["mbid"]?.jsonPrimitive?.contentOrNullBlank()
    val raw = artist["bio"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNullBlank()
    val cleaned = raw
        ?.replace(READ_MORE, "")
        ?.replace(ANY_TAG, "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !PLACEHOLDER.matches(it) }
    return LastFmArtistInfo(bio = cleaned, mbid = mbid)
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullBlank(): String? =
    content.trim().ifBlank { null }
```

- [ ] **Step 4: Add the client method** (after `getArtistTopTags`)

```kotlin
    /** Artist bio + MBID via artist.getInfo. Cacheable (routes through the proxy
     *  once the Worker allowlists artist.getinfo — see plan Task 2). */
    suspend fun getArtistInfo(artist: String): Result<LastFmArtistInfo?> = runCatching {
        val params = sortedMapOf(
            "method" to "artist.getInfo",
            "api_key" to credentials.apiKey,
            "artist" to artist,
            "autocorrect" to "1",
        )
        parseArtistInfo(unsignedGet(params, cacheable = true))
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*LastFmArtistInfoParserTest*"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add core/data/build.gradle.kts \
        core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmArtistInfo.kt \
        core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt \
        core/data/src/test/kotlin/com/stash/core/data/lastfm/LastFmArtistInfoParserTest.kt
git commit -m "feat(lastfm): getArtistInfo + bio parser (HTML strip, placeholder->null, mbid)"
```

---

## Task 4: `MusicBrainzClient` (client-direct) + socials mapper

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/musicbrainz/MusicBrainzClient.kt` (interface + pure `mapSocials` + `escapeLucene`)
- Create: `core/data/src/main/kotlin/com/stash/core/data/musicbrainz/MusicBrainzClientImpl.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/di/ArtistEnrichmentModule.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/musicbrainz/MusicBrainzMapperTest.kt`

> **Note on DI:** `DiscographySupplement` is bound in `data/download/.../qbdlx/di/QbdlxModule.kt` (NOT core:data), so there is no existing core:data cache module to reuse. This task creates `ArtistEnrichmentModule` in `core:data`; Task 5 adds the enricher `@Binds` to the *same* module. This module is where both the `MusicBrainzClient` and `ArtistAboutEnricher` bindings live.

Mapping is by **URL host** across all `url-rels` (catches every platform regardless of MB's rel typing), `official homepage` type → website, `ended:true` relations skipped, Lucene-special chars escaped in name search. The interface is what the enricher depends on (mock target); the impl does client-direct OkHttp with the project-URL User-Agent and an on-device ~1 req/sec gate.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.SocialLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class MusicBrainzMapperTest {
    private fun rels(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun `maps hosts and skips ended`() {
        val socials = mapSocials(rels("""{"relations":[
            {"type":"social network","url":{"resource":"https://instagram.com/a"}},
            {"type":"youtube","url":{"resource":"https://youtube.com/@a"}},
            {"type":"social network","ended":true,"url":{"resource":"https://myspace.com/a"}},
            {"type":"official homepage","url":{"resource":"https://a.com"}}
        ]}"""))
        assertThat(socials).containsExactly(
            SocialLink("instagram", "https://instagram.com/a"),
            SocialLink("youtube", "https://youtube.com/@a"),
            SocialLink("website", "https://a.com"),
        ).inOrder()
    }

    @Test fun `dedupes and drops unknown hosts`() {
        val socials = mapSocials(rels("""{"relations":[
            {"type":"social network","url":{"resource":"https://x.com/a"}},
            {"type":"social network","url":{"resource":"https://x.com/a"}},
            {"type":"streaming","url":{"resource":"https://unknownhost.example/a"}}
        ]}"""))
        assertThat(socials).containsExactly(SocialLink("x", "https://x.com/a"))
    }

    @Test fun `escapes lucene specials`() {
        assertThat(escapeLucene("""Panic! at the "Disco" (2005)"""))
            .isEqualTo("""Panic\! at the \"Disco\" \(2005\)""")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*MusicBrainzMapperTest*"`
Expected: FAIL — `mapSocials`/`escapeLucene` unresolved.

- [ ] **Step 3: Create interface + pure functions**

`MusicBrainzClient.kt`:
```kotlin
package com.stash.core.data.musicbrainz

import com.stash.data.ytmusic.model.SocialLink
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Client-direct MusicBrainz access. Returns the `relations` JsonObject payload
 *  (from `?inc=url-rels&fmt=json`) or null on any failure/no-match. */
interface MusicBrainzClient {
    /** MBID lookup; null on 404/failure (caller then tries [searchByName]). */
    suspend fun lookupUrlRels(mbid: String): JsonObject?
    /** Name search + url-rels; null if no confident match. */
    suspend fun searchByName(name: String): JsonObject?
}

private val HOST_KIND = linkedMapOf(
    "instagram.com" to "instagram", "twitter.com" to "x", "x.com" to "x",
    "tiktok.com" to "tiktok", "facebook.com" to "facebook",
    "youtube.com" to "youtube", "soundcloud.com" to "soundcloud", "bandcamp.com" to "bandcamp",
)
private val LUCENE = Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)""")

fun escapeLucene(name: String): String = name.replace(LUCENE) { "\\" + it.value }

/** Map an artist `relations` payload to ordered, de-duped social links.
 *  Host-based for platforms; `official homepage` type → website. Skips ended. */
fun mapSocials(rels: JsonObject): List<SocialLink> {
    val out = LinkedHashMap<String, SocialLink>() // key = url, preserves order + dedupes
    val relations = rels["relations"]?.jsonArray ?: return emptyList()
    for (el in relations) {
        val rel = el.jsonObject
        if (rel["ended"]?.jsonPrimitive?.content == "true") continue
        val url = rel["url"]?.jsonObject?.get("resource")?.jsonPrimitive?.content ?: continue
        val type = rel["type"]?.jsonPrimitive?.content
        val kind = when {
            type == "official homepage" -> "website"
            else -> HOST_KIND.entries.firstOrNull { host(url).endsWith(it.key) }?.value
        } ?: continue
        out.putIfAbsent(url, SocialLink(kind, url))
    }
    return out.values.toList()
}

private fun host(url: String): String =
    url.substringAfter("://", url).substringBefore('/').removePrefix("www.").lowercase()
```

- [ ] **Step 4: Run mapper test — should pass**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*MusicBrainzMapperTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Create the OkHttp impl + DI**

`MusicBrainzClientImpl.kt` — inject the app `OkHttpClient`; base `https://musicbrainz.org/ws/2/`; `User-Agent: Stash/<BuildConfig.VERSION or "dev"> ( https://github.com/rawnaldclark/Stash )`; a `Mutex` + last-call timestamp enforcing ≥1000ms between requests; `searchByName` uses `?query=artist:"${escapeLucene(name)}"&fmt=json`, accepts the top result only if `score >= 95` and `type in {Person, Group}`, then calls `lookupUrlRels(mbid)`; both return the parsed `JsonObject` (the whole artist object, so the enricher can also read the `wikidata` rel later) or null on non-2xx/exception. Wrap network in `runCatching`.

`di/ArtistEnrichmentModule.kt` (the enricher `@Binds` is added in Task 5):
```kotlin
package com.stash.core.data.di

import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.core.data.musicbrainz.MusicBrainzClientImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module @InstallIn(SingletonComponent::class)
abstract class ArtistEnrichmentModule {
    @Binds abstract fun bindMusicBrainzClient(impl: MusicBrainzClientImpl): MusicBrainzClient
    // Task 5 adds: @Binds abstract fun bindArtistAboutEnricher(impl: RealArtistAboutEnricher): ArtistAboutEnricher
}
```

- [ ] **Step 6: Build the module**

Run: `./gradlew :core:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/musicbrainz/ \
        core/data/src/main/kotlin/com/stash/core/data/di/ArtistEnrichmentModule.kt \
        core/data/src/test/kotlin/com/stash/core/data/musicbrainz/MusicBrainzMapperTest.kt
git commit -m "feat(musicbrainz): client-direct client + url-rels social mapper"
```

---

## Task 5: `ArtistAboutEnricher` (+ Noop)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/cache/ArtistAboutEnricher.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/cache/ArtistAboutEnricherTest.kt`

Orchestrates: Last.fm `getArtistInfo` (bio + optional mbid) → MusicBrainz (mbid lookup, fall back to name search on 404/absent) → `mapSocials`. Returns `ArtistAbout?` — **null when nothing found** (no bio, no socials, no photo). `@Inject`-constructed. A `NoopArtistAboutEnricher` (returns null) is the `ArtistCache` default so existing tests keep compiling.

- [ ] **Step 1: Write the failing test** (mock `LastFmApiClient` via mockito 5.x; hand-fake `MusicBrainzClient`)

```kotlin
package com.stash.core.data.cache

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmArtistInfo
import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.data.ytmusic.model.SocialLink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ArtistAboutEnricherTest {
    private fun relsPayload(vararg urls: Pair<String, String>): JsonObject {
        val rels = urls.joinToString(",") { (type, url) ->
            """{"type":"$type","url":{"resource":"$url"}}"""
        }
        return Json.parseToJsonElement("""{"relations":[$rels]}""").jsonObject
    }

    private fun enricher(
        info: LastFmArtistInfo?,
        mbLookup: JsonObject? = null,
        mbSearch: JsonObject? = null,
    ): RealArtistAboutEnricher {
        val lastFm = mock<LastFmApiClient> {
            onBlocking { getArtistInfo(org.mockito.kotlin.any()) } doReturn Result.success(info)
        }
        val mb = object : MusicBrainzClient {
            override suspend fun lookupUrlRels(mbid: String) = mbLookup
            override suspend fun searchByName(name: String) = mbSearch
        }
        return RealArtistAboutEnricher(lastFm, mb)
    }

    @Test fun `bio and socials both present`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = "b", mbid = "m1"),
            mbLookup = relsPayload("social network" to "https://instagram.com/a"),
        ).enrich("A")
        assertThat(about!!.bio).isEqualTo("b")
        assertThat(about.socials).containsExactly(SocialLink("instagram", "https://instagram.com/a"))
    }

    @Test fun `bio null but socials present is partial`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = null, mbid = "m1"),
            mbLookup = relsPayload("official homepage" to "https://a.com"),
        ).enrich("A")
        assertThat(about!!.bio).isNull()
        assertThat(about.socials).isNotEmpty()
    }

    @Test fun `nothing found returns null`() = runTest {
        val about = enricher(info = LastFmArtistInfo(bio = null, mbid = null)).enrich("A")
        assertThat(about).isNull()
    }

    @Test fun `stale mbid lookup null falls back to name search`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = "b", mbid = "stale"),
            mbLookup = null, // 404
            mbSearch = relsPayload("youtube" to "https://youtube.com/@a"),
        ).enrich("A")
        assertThat(about!!.socials).containsExactly(SocialLink("youtube", "https://youtube.com/@a"))
    }
}
```
(Verified: `core:data` has `mockito-kotlin:5.4.0` **and** `mockk:1.13.8` on its test classpath — `org.mockito.kotlin` is used in ~29 existing core:data tests, so these imports resolve.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ArtistAboutEnricherTest*"`
Expected: FAIL — `ArtistAboutEnricher` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.data.cache

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.core.data.musicbrainz.mapSocials
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.ytmusic.model.SocialLink
import javax.inject.Inject

interface ArtistAboutEnricher { suspend fun enrich(artistName: String): ArtistAbout? }

/** No-op default so ArtistCache's existing test constructions keep compiling
 *  (mirrors NoopDiscographySupplement). */
class NoopArtistAboutEnricher : ArtistAboutEnricher {
    override suspend fun enrich(artistName: String): ArtistAbout? = null
}

class RealArtistAboutEnricher @Inject constructor(
    private val lastFm: LastFmApiClient,
    private val mb: MusicBrainzClient,
) : ArtistAboutEnricher {
    override suspend fun enrich(artistName: String): ArtistAbout? {
        val info = lastFm.getArtistInfo(artistName).getOrNull()
        val bio = info?.bio
        val relsPayload = (info?.mbid?.let { mb.lookupUrlRels(it) }) ?: mb.searchByName(artistName)
        val socials: List<SocialLink> = relsPayload?.let { mapSocials(it) } ?: emptyList()
        // photoUrl left null in v1 (Wikimedia upgrade deferred per spec §3.3/§9).
        return if (bio == null && socials.isEmpty()) null
        else ArtistAbout(bio = bio, socials = socials, photoUrl = null)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ArtistAboutEnricherTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Add the enricher Hilt binding** (concrete — required, or Task 8 fails with `MissingBinding`)

In `core/data/src/main/kotlin/com/stash/core/data/di/ArtistEnrichmentModule.kt` (created in Task 4), add:
```kotlin
    @Binds abstract fun bindArtistAboutEnricher(impl: RealArtistAboutEnricher): ArtistAboutEnricher
```
plus the import `import com.stash.core.data.cache.ArtistAboutEnricher` and `import com.stash.core.data.cache.RealArtistAboutEnricher`.

- [ ] **Step 6: Compile core:data to confirm the binding resolves**

Run: `./gradlew :core:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/cache/ArtistAboutEnricher.kt \
        core/data/src/main/kotlin/com/stash/core/data/di/ArtistEnrichmentModule.kt \
        core/data/src/test/kotlin/com/stash/core/data/cache/ArtistAboutEnricherTest.kt
git commit -m "feat(artist): ArtistAboutEnricher (bio + socials -> ArtistAbout?) + Noop + Hilt binding"
```

---

## Task 6: Wire the enricher into `ArtistCache.fetchAndMerge` (concurrent)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/cache/ArtistCache.kt` (ctor ~line 68-91; `fetchAndMerge` ~line 177)
- Modify: the cache Hilt module (add `ArtistAboutEnricher` to the `@Provides`/binding for `ArtistCache` if constructed manually; if `ArtistCache` is `@Inject`, the `@Binds` from Task 5 suffices)
- Test: `core/data/src/test/kotlin/com/stash/core/data/cache/ArtistCacheAboutTest.kt`

Add `aboutEnricher: ArtistAboutEnricher = NoopArtistAboutEnricher()` to the **primary** ctor (default keeps existing tests green) and to the **`@Inject` secondary** ctor. In `fetchAndMerge`, run both supplements as siblings under `coroutineScope`, each catching internally — **`TimeoutCancellationException` → degrade BEFORE the `CancellationException` rethrow** (a timeout is a CancellationException; wrong order would cancel the sibling and fail the page).

- [ ] **Step 1: Write the failing test**

Follow the construction style of the existing `ArtistCacheTest`/`ArtistCacheMergeTest` (fake `dao`/`api`, fixed `now`, primary ctor). Add a fake enricher param. Key cases:

```kotlin
// In ArtistCacheAboutTest.kt — reuse the module's existing fakes for dao/api/supplement.
private fun cache(enricher: ArtistAboutEnricher) =
    ArtistCache(fakeDao, fakeApi, now = { 1_000L }, supplement = NoopDiscographySupplement(), aboutEnricher = enricher)

@Test fun `enricher failure still emits Fresh with null about`() = runTest {
    val c = cache(object : ArtistAboutEnricher {
        override suspend fun enrich(artistName: String): ArtistAbout? = throw RuntimeException("boom")
    })
    val result = c.get("a1").first()
    assertThat(result).isInstanceOf(CachedProfile.Fresh::class.java)
    assertThat((result as CachedProfile.Fresh).profile.about).isNull()
}

@Test fun `enricher timeout still emits Fresh and leaves discography intact`() = runTest {
    val c = cache(object : ArtistAboutEnricher {
        override suspend fun enrich(artistName: String): ArtistAbout? {
            kotlinx.coroutines.delay(Long.MAX_VALUE); return null // never returns -> times out
        }
    })
    val result = c.get("a1").first() as CachedProfile.Fresh
    assertThat(result.profile.about).isNull()
    assertThat(result.profile.albums).isEqualTo(fakeApi.artistAlbumsFor("a1")) // Qobuz/YT path unaffected
}

@Test fun `enricher success is carried on the Fresh profile`() = runTest {
    val about = ArtistAbout(bio = "b", socials = emptyList(), photoUrl = null)
    val c = cache(object : ArtistAboutEnricher {
        override suspend fun enrich(artistName: String) = about
    })
    val result = c.get("a1").first() as CachedProfile.Fresh
    assertThat(result.profile.about).isEqualTo(about)
}
```
(Adapt `fakeDao`/`fakeApi`/`artistAlbumsFor` to whatever the existing `ArtistCache` tests already provide — do NOT invent a new fake harness if one exists. The `runTest` virtual clock advances past the `withTimeout` so the timeout test doesn't actually block.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ArtistCacheAboutTest*"`
Expected: FAIL — ctor has no `aboutEnricher`.

- [ ] **Step 3: Add ctor params**

Primary ctor (after `supplement`):
```kotlin
    private val supplement: DiscographySupplement = NoopDiscographySupplement(),
    private val aboutEnricher: ArtistAboutEnricher = NoopArtistAboutEnricher(),
) {
```
`@Inject` secondary ctor — add the param and pass it through:
```kotlin
    @Inject
    constructor(
        dao: ArtistProfileCacheDao,
        api: YTMusicApiClient,
        supplement: DiscographySupplement,
        aboutEnricher: ArtistAboutEnricher,
    ) : this(dao, api, System::currentTimeMillis, supplement, aboutEnricher)
```

- [ ] **Step 4: Make `fetchAndMerge` concurrent + additive**

Add two imports to `ArtistCache.kt` (the file already imports `withTimeout`, `CancellationException`, `TimeoutCancellationException`, `MergedDiscography`):
```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
```
Then replace the body of `fetchAndMerge` so the required `api.getArtist` stays outside, and the two supplements run concurrently with internal catches:
```kotlin
private suspend fun fetchAndMerge(artistId: String): ArtistProfile = coroutineScope {
    val yt = api.getArtist(artistId)   // REQUIRED — failure propagates
    val discographyDeferred = async {
        try {
            withTimeout(SUPPLEMENT_TIMEOUT_MS) { supplement.mergeInto(yt.name, yt.albums, yt.singles) }
        } catch (e: TimeoutCancellationException) { MergedDiscography(yt.albums, yt.singles) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { MergedDiscography(yt.albums, yt.singles) }
    }
    val aboutDeferred = async {
        try {
            withTimeout(ABOUT_TIMEOUT_MS) { aboutEnricher.enrich(yt.name) }
        } catch (e: TimeoutCancellationException) { null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { null }
    }
    val merged = discographyDeferred.await()
    val about = aboutDeferred.await()
    yt.copy(albums = merged.albums, singles = merged.singles, about = about)
}
```
Add the companion constant near `SUPPLEMENT_TIMEOUT_MS`:
```kotlin
/** About enricher bound — kept <= SUPPLEMENT_TIMEOUT_MS so it never extends cold-miss first paint. */
private const val ABOUT_TIMEOUT_MS: Long = 4_000L
```
(Verify `ABOUT_TIMEOUT_MS <= SUPPLEMENT_TIMEOUT_MS`; adjust if the Qobuz bound is lower.)

- [ ] **Step 5: Run tests (new + existing ArtistCache suite)**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ArtistCache*"`
Expected: PASS — new about tests pass AND all pre-existing `ArtistCache` tests still pass (Noop default).

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/cache/ArtistCache.kt \
        core/data/src/test/kotlin/com/stash/core/data/cache/ArtistCacheAboutTest.kt
git commit -m "feat(artist): run About enricher concurrently in ArtistCache.fetchAndMerge"
```

---

## Task 7: `AboutSection` UI + wire into `ArtistProfileScreen`

**Files:**
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/AboutSection.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt` (`contentSections`, after the "Fans also like" item, ~line 279-290; `contentSections` has only `state: ArtistProfileUiState` in scope — read `state.about` + `state.hero.avatarUrl`)
- Modify (**required**, not optional): `feature/search/.../ArtistProfileUiState.kt` — add `about: ArtistAbout? = null`; and `ArtistProfileViewModel.kt` `apply()` (~line 375-386) — set `about = profile.about` when folding the cached profile into the UI state.
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/AboutSocialIconTest.kt` (pure `kind`→icon mapping)

Render only when `about != null`. Bio clamps to ~4 lines with "see more"/"see less". Photo = `about.photoUrl ?: avatarUrl` (omit if both null). Social icons map `kind`→icon with a globe fallback; tap opens via `LocalUriHandler` in `runCatching`. Small "via Last.fm" label when a bio shows.

- [ ] **Step 1: Write the failing test** (pure icon mapper is the testable unit)

```kotlin
// assert socialIconFor("instagram") != socialIconFor("website")
// assert socialIconFor("totally-unknown") == socialIconFor("website")  // globe fallback
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "*AboutSocialIconTest*"`
Expected: FAIL — `socialIconFor` unresolved.

- [ ] **Step 3: Implement `AboutSection.kt`**

Composable `AboutSection(about: ArtistAbout, avatarUrl: String?, modifier)` + a pure `socialIconFor(kind: String): ImageVector` (map known kinds to `Icons.*`/material-icons-extended, else a globe). Bio uses `maxLines` + `remember { mutableStateOf(expanded) }` toggle. Social row = `about.socials.map { IconButton(onClick = { runCatching { uriHandler.openUri(it.url) } }) { Icon(socialIconFor(it.kind), it.kind) } }`. Follow the existing `SectionHeader` + section spacing used by `PopularTracksSection`/`RelatedArtistsRow`.

- [ ] **Step 4: Surface `about` into the UI state, then render it**

First thread the data (both edits are required — `contentSections` sees only `state`):
- In `ArtistProfileUiState.kt`, add `val about: ArtistAbout? = null` to the state class.
- In `ArtistProfileViewModel.kt` `apply()` (~line 375-386), where it builds the UI state from the cached `ArtistProfile`, add `about = profile.about`.

Then in `contentSections` (after the "Fans also like" `item` block, ~line 279-290):
```kotlin
val about = state.about
if (about != null) {
    item { SectionHeader(title = "About") }
    item { AboutSection(about = about, avatarUrl = state.hero.avatarUrl) }
}
```
(Confirm the hero avatar field name against `ArtistProfileUiState` — the plan assumes `state.hero.avatarUrl`; adjust to the real accessor.)

- [ ] **Step 5: Run test + compile the module**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "*AboutSocialIconTest*"`
Then: `./gradlew :feature:search:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/AboutSection.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/AboutSocialIconTest.kt
git commit -m "feat(artist): About section UI (bio + socials + photo) on ArtistProfileScreen"
```

---

## Task 8: Full build + assemble verification

- [ ] **Step 1: Assemble the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the touched modules' unit suites**

Run: `./gradlew :data:ytmusic:testDebugUnitTest :core:data:testDebugUnitTest :feature:search:testDebugUnitTest --tests "*ArtistAbout*" --tests "*MusicBrainz*" --tests "*ArtistCache*" --tests "*About*"`
Expected: PASS.

- [ ] **Step 3: Device smoke (human)** — open an artist page from Search (e.g. a well-known artist with an MB entry), confirm the About section shows bio + tappable socials; open a long-tail/obscure artist and confirm the page renders identically to before (About simply absent). No jank on the required Popular/Albums sections.

- [ ] **Step 4: Commit any fixups, then the feature is ready for PR.**

---

## Out of scope (recorded, do NOT build here)

- Wikimedia P18 photo upgrade (spec §3.3/§9) — incl. its CC BY-SA attribution requirement.
- Genre/tag chips; photo gallery/carousel.
- Library-side `ArtistDetailScreen` (Search-side only).
- Root-cause hardening of the `ArtistCache.kt:137` unguarded decode (also affects the `AlbumSource` enum) — separate task.
