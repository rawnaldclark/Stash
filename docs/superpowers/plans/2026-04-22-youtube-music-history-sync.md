# YouTube Music History Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an opt-in "Send plays to YouTube Music" feature that submits Stash's local play events to YT Music's Watch History via the unofficial `playbackTracking.videostatsPlaybackUrl` endpoint, so YT Music's recommender graph learns from Stash listening.

**Architecture:** New `YouTubeHistoryScrobbler` singleton mirrors the existing `LastFmScrobbler` queue-drain pattern. Reuses `ListeningEventEntity` (adds one `yt_scrobbled` column), `YouTubeCookieHelper` for SAPISIDHASH auth, and `InnerTubeClient` for the `player` endpoint + canonical search. A new `YtCanonicalResolver` chooses the best ATV/OMV video id per track, skipping submission entirely for UGC-only tracks to protect recommender signal quality. Opt-in toggle + one-time confirmation dialog + pending-count / health-badge in Settings. Behavior-based kill-switch (5 consecutive non-auth failures pauses the scrobbler; cleared on app update).

**Tech Stack:** Kotlin, Android (Jetpack Compose + Room + DataStore + Hilt), kotlinx.coroutines + Flow, okhttp, kotlinx.serialization.json.

**Spec:** `docs/superpowers/specs/2026-04-22-youtube-music-history-sync-design.md`

**Target release:** v0.6.0

---

## File Structure

### New files

| Path | Responsibility |
|------|----------------|
| `core/data/src/main/kotlin/com/stash/core/data/prefs/YouTubeHistoryPreference.kt` | DataStore wrapper: opt-in enabled flag. |
| `core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeScrobblerState.kt` | DataStore wrapper: kill-switch state (`disabled_reason`, `consecutive_failures`, `last_known_version_code`). |
| `core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeScrobblerHealth.kt` | Enum: `OK`, `OFFLINE`, `AUTH_FAILED`, `PROTOCOL_BROKEN`, `DISABLED`. |
| `core/data/src/main/kotlin/com/stash/core/data/youtube/YtCanonicalResolver.kt` | Given a `TrackEntity`, returns the best ATV/OMV video id for YT Music Recap/recommender, or null to skip. |
| `core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeHistoryScrobbler.kt` | Singleton worker that reactively drains unscrobbled events and submits pings. Mirrors `LastFmScrobbler`. |
| `core/data/src/test/kotlin/com/stash/core/data/youtube/YtCanonicalResolverTest.kt` | Unit tests. |
| `core/data/src/test/kotlin/com/stash/core/data/youtube/YouTubeHistoryScrobblerTest.kt` | Queue-transition + kill-switch logic tests. |
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/PlaybackTrackingParser.kt` | Pure parser: extracts `videostatsPlaybackUrl` from `/youtubei/v1/player` JSON. |
| `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/PlaybackTrackingParserTest.kt` | Parser tests with fixture JSON. |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/components/YouTubeHistorySyncSection.kt` | Composable: toggle + health badge + first-enable dialog. |

### Files to modify

| Path | Change |
|------|--------|
| `core/data/src/main/kotlin/com/stash/core/data/db/entity/ListeningEventEntity.kt` | Add `yt_scrobbled` column. |
| `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt` | Add `yt_canonical_video_id` column. |
| `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` | Bump DB version; add migration. |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/ListeningEventDao.kt` | Add `pendingYtScrobbles`, `markYtScrobbled`, `pendingYtScrobbleCount`. |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` | Add `updateYtCanonicalVideoId`. |
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt` | Add `getPlaybackTracking(videoId)` + `searchCanonical(artist, title)`. |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt` | Add `ytHistoryEnabled`, `ytHistoryHealth`, `ytPendingCount`. |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` | Inject preference + health + scrobbler; add toggle callback. |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt` | Insert `YouTubeHistorySyncSection` in Downloads card. |
| `app/src/main/kotlin/com/stash/app/StashApplication.kt` | Start `YouTubeHistoryScrobbler` once. |
| `app/build.gradle.kts` | Bump version to 0.6.0. |

---

## Phase 1 — Schema & DAO plumbing

### Task 1: Add `yt_scrobbled` column to `ListeningEventEntity`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/ListeningEventEntity.kt`

- [ ] **Step 1: Open `ListeningEventEntity.kt` and add the column + index**

Add the field below `scrobbled` and add an index on it:

```kotlin
indices = [
    Index(value = ["track_id"]),
    Index(value = ["started_at"]),
    Index(value = ["scrobbled"]),
    Index(value = ["yt_scrobbled"]),
],
```

And the new column:

```kotlin
/**
 * True once successfully submitted to YouTube Music as a history ping.
 * Mirrors [scrobbled] but for the YT Music recommender-graph write path.
 * Set to `true` when a ping lands (2xx), when the track is UGC-only and
 * has no canonical ATV/OMV equivalent (don't pollute Recap), and when
 * the feature is disabled and we want to skip processing the backlog.
 */
@ColumnInfo(name = "yt_scrobbled", defaultValue = "0")
val ytScrobbled: Boolean = false,
```

- [ ] **Step 2: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/ListeningEventEntity.kt
git commit -m "feat(db): add yt_scrobbled column to listening_events"
```

---

### Task 2: Add `yt_canonical_video_id` column to `TrackEntity`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`

- [ ] **Step 1: Add the column in `TrackEntity`**

Near the other YouTube-related fields (`youtube_id`, `music_video_type`):

```kotlin
/**
 * Cached canonical ATV/OMV video id for YouTube Music recommender-graph
 * scrobbling. Null when uncached. Populated the first time
 * [com.stash.core.data.youtube.YtCanonicalResolver] resolves a
 * non-ATV/OMV track (UGC uploads, YouTube-Library imports) so we don't
 * re-search InnerTube every time the user re-plays it. Never overwritten
 * once set — the canonical id for a given (artist, title) doesn't move.
 */
@ColumnInfo(name = "yt_canonical_video_id")
val ytCanonicalVideoId: String? = null,
```

- [ ] **Step 2: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt
git commit -m "feat(db): add yt_canonical_video_id column to tracks"
```

---

### Task 3: Bump DB version and add migration

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`

- [ ] **Step 1: Read the current DB version**

Look for `@Database(..., version = N)` at the top of `StashDatabase.kt`. Note the current number — the new version will be N+1.

- [ ] **Step 2: Bump the `version` parameter by 1**

Change `@Database(..., version = N)` to `version = N+1`.

- [ ] **Step 3: Add the migration**

Find the `migrations` companion / function. Add a new migration constant matching the existing pattern. Substitute `OLD` and `NEW` with the actual version numbers:

```kotlin
val MIGRATION_OLD_NEW = object : Migration(OLD, NEW) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE listening_events ADD COLUMN yt_scrobbled INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_listening_events_yt_scrobbled " +
                "ON listening_events(yt_scrobbled)"
        )
        db.execSQL(
            "ALTER TABLE tracks ADD COLUMN yt_canonical_video_id TEXT"
        )
    }
}
```

Then register the migration in the `addMigrations(...)` call (or wherever migrations get attached to the builder — follow the existing pattern in the file).

- [ ] **Step 4: Build the project to verify the Room schema compiles**

```bash
./gradlew :core:data:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. Room generates `StashDatabase_Impl` with the new columns.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt
git commit -m "feat(db): migration for YT history sync columns"
```

---

### Task 4: Extend `ListeningEventDao` with YT-scrobble queries

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/ListeningEventDao.kt`

- [ ] **Step 1: Add three new methods**

Alongside the existing `pendingScrobbles` / `markScrobbled` / `pendingScrobbleCount` methods, add the YT-side siblings:

```kotlin
/**
 * Unscrobbled-to-YT events awaiting submission to the YouTube Music
 * recommender graph. Same shape as [pendingScrobbles] but gated on
 * `yt_scrobbled` instead of `scrobbled`. Oldest first so submissions
 * chronologically mirror actual listening order.
 */
@Query(
    """
    SELECT * FROM listening_events
    WHERE yt_scrobbled = 0
    ORDER BY started_at ASC
    LIMIT :limit
    """
)
suspend fun pendingYtScrobbles(limit: Int = 100): List<ListeningEventEntity>

@Query(
    """
    UPDATE listening_events SET yt_scrobbled = 1
    WHERE id = :eventId
    """
)
suspend fun markYtScrobbled(eventId: Long)

/** Count of unscrobbled-to-YT events. Drives the Settings health badge. */
@Query("SELECT COUNT(*) FROM listening_events WHERE yt_scrobbled = 0")
fun pendingYtScrobbleCount(): Flow<Int>
```

- [ ] **Step 2: Build the project**

```bash
./gradlew :core:data:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/ListeningEventDao.kt
git commit -m "feat(dao): listening-events YT scrobble queries"
```

---

### Task 5: Extend `TrackDao` with `updateYtCanonicalVideoId`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`

- [ ] **Step 1: Add the update method near the other `update*` helpers**

```kotlin
/**
 * Set the cached canonical ATV/OMV video id for this track. Called once
 * per track by [com.stash.core.data.youtube.YtCanonicalResolver] when it
 * resolves a non-ATV/OMV track via InnerTube search. Never re-runs —
 * the resolver only fills when `yt_canonical_video_id IS NULL`.
 */
@Query(
    """
    UPDATE tracks
    SET yt_canonical_video_id = :videoId
    WHERE id = :trackId
      AND yt_canonical_video_id IS NULL
    """
)
suspend fun updateYtCanonicalVideoId(trackId: Long, videoId: String)
```

- [ ] **Step 2: Build**

```bash
./gradlew :core:data:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt
git commit -m "feat(dao): updateYtCanonicalVideoId"
```

---

## Phase 2 — Preferences & kill-switch state

### Task 6: `YouTubeHistoryPreference` DataStore wrapper

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/prefs/YouTubeHistoryPreference.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for YT history sync opt-in. */
private val Context.youtubeHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_history_preference",
)

/**
 * Persists the user's opt-in for scrobbling local plays to YouTube
 * Music's Watch History (thereby feeding YT Music's recommender graph).
 *
 * Default is false — the feature is off on every fresh install. Flipping
 * on shows a one-time confirmation dialog in the Settings layer; this
 * class itself is unaware of the dialog, it just stores the bool.
 */
@Singleton
class YouTubeHistoryPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("enabled")

    val enabled: Flow<Boolean> = context.youtubeHistoryDataStore.data.map { prefs ->
        prefs[enabledKey] ?: false
    }

    suspend fun current(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.youtubeHistoryDataStore.edit { it[enabledKey] = value }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :core:data:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/prefs/YouTubeHistoryPreference.kt
git commit -m "feat(prefs): YouTubeHistoryPreference opt-in toggle"
```

---

### Task 7: `YouTubeScrobblerState` — kill-switch state

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeScrobblerState.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.stash.core.data.youtube

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for YT scrobbler kill-switch + failure-counter state. */
private val Context.youtubeScrobblerStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_scrobbler_state",
)

/**
 * Persistent state for [YouTubeHistoryScrobbler]'s behavior-driven
 * kill-switch. Kept separate from the opt-in preference so that disabling
 * via kill-switch (protocol broken) and disabling via user toggle don't
 * collide semantically.
 *
 * Fields:
 *  - [disabledReason]: null when healthy; "protocol_errors" when the
 *    kill-switch has tripped. Scrobbler no-ops while this is non-null.
 *  - [consecutiveFailures]: sliding counter of non-auth (≠ 401/403)
 *    failures since the last successful ping. Reset to 0 on every
 *    success. Trips to `disabledReason = "protocol_errors"` at >= 5.
 *  - [lastKnownVersionCode]: the `BuildConfig.VERSION_CODE` the
 *    scrobbler saw on its most recent start. When the installed app's
 *    version is newer, the kill-switch flag clears automatically — the
 *    "self-heal on app update" mechanism from the spec.
 */
@Singleton
class YouTubeScrobblerState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val disabledReasonKey = stringPreferencesKey("disabled_reason")
    private val consecutiveFailuresKey = intPreferencesKey("consecutive_failures")
    private val lastKnownVersionCodeKey = intPreferencesKey("last_known_version_code")

    val disabledReason: Flow<String?> = context.youtubeScrobblerStateDataStore.data.map {
        it[disabledReasonKey]
    }

    suspend fun currentDisabledReason(): String? = disabledReason.first()

    suspend fun currentConsecutiveFailures(): Int =
        context.youtubeScrobblerStateDataStore.data.first()[consecutiveFailuresKey] ?: 0

    suspend fun currentLastKnownVersionCode(): Int =
        context.youtubeScrobblerStateDataStore.data.first()[lastKnownVersionCodeKey] ?: 0

    suspend fun setDisabledReason(reason: String?) {
        context.youtubeScrobblerStateDataStore.edit {
            if (reason == null) it.remove(disabledReasonKey) else it[disabledReasonKey] = reason
        }
    }

    suspend fun incrementConsecutiveFailures(): Int {
        var newValue = 0
        context.youtubeScrobblerStateDataStore.edit { prefs ->
            val cur = prefs[consecutiveFailuresKey] ?: 0
            newValue = cur + 1
            prefs[consecutiveFailuresKey] = newValue
        }
        return newValue
    }

    suspend fun resetConsecutiveFailures() {
        context.youtubeScrobblerStateDataStore.edit { it[consecutiveFailuresKey] = 0 }
    }

    suspend fun setLastKnownVersionCode(versionCode: Int) {
        context.youtubeScrobblerStateDataStore.edit {
            it[lastKnownVersionCodeKey] = versionCode
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :core:data:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeScrobblerState.kt
git commit -m "feat(youtube): YouTubeScrobblerState for kill-switch storage"
```

---

### Task 8: `YouTubeScrobblerHealth` enum + derived flow

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeScrobblerHealth.kt`

- [ ] **Step 1: Create the enum**

```kotlin
package com.stash.core.data.youtube

/**
 * UI-facing health of the YouTube Music history scrobbler. Derived
 * reactively in [YouTubeHistoryScrobbler] from the combination of
 * (opt-in toggle, YT auth state, kill-switch reason, pending count,
 * most recent ping outcome).
 *
 *  - [OK]: last ping succeeded and no blocking condition.
 *  - [OFFLINE]: last ping failed transiently (retrying) or pending > 0
 *    with no recent success.
 *  - [AUTH_FAILED]: last non-transient failure was 401/403 — the cookie
 *    is stale; user action required.
 *  - [PROTOCOL_BROKEN]: kill-switch tripped after 5 consecutive
 *    non-auth failures. Cleared on next app update.
 *  - [DISABLED]: user has the opt-in toggle off, OR they never connected
 *    YouTube Music. Scrobbler is dormant.
 */
enum class YouTubeScrobblerHealth {
    OK,
    OFFLINE,
    AUTH_FAILED,
    PROTOCOL_BROKEN,
    DISABLED,
}
```

- [ ] **Step 2: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeScrobblerHealth.kt
git commit -m "feat(youtube): YouTubeScrobblerHealth enum"
```

---

## Phase 3 — InnerTube glue

### Task 9: `PlaybackTrackingParser` (pure parser, tested)

**Files:**
- Create: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/PlaybackTrackingParser.kt`
- Create: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/PlaybackTrackingParserTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTrackingParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val parser = PlaybackTrackingParser()

    @Test
    fun `extracts videostatsPlaybackUrl when present`() {
        val response = json.parseToJsonElement(
            """
            {
              "playbackTracking": {
                "videostatsPlaybackUrl": { "baseUrl": "https://youtubei.googleapis.com/api/stats/playback?docid=abc" },
                "videostatsWatchtimeUrl": { "baseUrl": "https://youtubei.googleapis.com/api/stats/watchtime?docid=abc" }
              }
            }
            """.trimIndent()
        ).jsonObject
        val url = parser.extract(response)
        assertEquals(
            "https://youtubei.googleapis.com/api/stats/playback?docid=abc",
            url,
        )
    }

    @Test
    fun `returns null when playbackTracking is missing`() {
        val response = json.parseToJsonElement("""{"responseContext": {}}""").jsonObject
        assertNull(parser.extract(response))
    }

    @Test
    fun `returns null when videostatsPlaybackUrl is missing but watchtime is present`() {
        // Guard against accidentally reading the wrong url if the shape drifts.
        val response = json.parseToJsonElement(
            """
            {
              "playbackTracking": {
                "videostatsWatchtimeUrl": { "baseUrl": "https://youtubei.googleapis.com/api/stats/watchtime?docid=abc" }
              }
            }
            """.trimIndent()
        ).jsonObject
        assertNull(parser.extract(response))
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.PlaybackTrackingParserTest"
```

Expected: FAIL with `Unresolved reference: PlaybackTrackingParser`.

- [ ] **Step 3: Implement the parser**

```kotlin
package com.stash.data.ytmusic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts the `playbackTracking.videostatsPlaybackUrl.baseUrl` from a
 * YouTube Music InnerTube `/youtubei/v1/player` response.
 *
 * **Important:** this is the URL that `ytmusicapi.add_history_item` and
 * SimpMusic use to register a play in the user's Watch History. It is
 * NOT the same as `videostatsWatchtimeUrl` (which is the in-app
 * progress-ping channel; hitting it does not register a history entry
 * on its own).
 */
class PlaybackTrackingParser {
    fun extract(playerResponse: JsonObject): String? {
        val tracking = playerResponse["playbackTracking"]?.jsonObject ?: return null
        val playbackUrl = tracking["videostatsPlaybackUrl"]?.jsonObject ?: return null
        return playbackUrl["baseUrl"]?.jsonPrimitive?.content
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
./gradlew :data:ytmusic:test --tests "com.stash.data.ytmusic.PlaybackTrackingParserTest"
```

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/PlaybackTrackingParser.kt data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/PlaybackTrackingParserTest.kt
git commit -m "feat(ytmusic): PlaybackTrackingParser with tests"
```

---

### Task 10: Extend `InnerTubeClient` with `getPlaybackTracking(videoId)`

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`

- [ ] **Step 1: Read the file** to find the existing `verifyVideo` / `player`-endpoint pattern — reuse its POST body structure and auth header threading. Note: `InnerTubeClient` already handles cookie-auth request building for at least one endpoint; follow that pattern exactly.

- [ ] **Step 2: Add the new method**

Add near the `verifyVideo` method. `PlaybackTrackingParser` is already in the same package.

```kotlin
/**
 * Fetch the `/youtubei/v1/player` response for [videoId] and extract the
 * `playbackTracking.videostatsPlaybackUrl.baseUrl` — the endpoint
 * `YouTubeHistoryScrobbler` POSTs to when registering a Watch History
 * entry.
 *
 * Returns null when:
 *  - The HTTP request fails (network, 5xx).
 *  - The response has no `playbackTracking` block (non-music videos,
 *    private / deleted content, or protocol drift).
 *  - The specific `videostatsPlaybackUrl.baseUrl` field is absent.
 *
 * Callers treat a null return as "skip this scrobble" — the upstream
 * kill-switch counter is driven by the HTTP POST step, not this lookup.
 */
suspend fun getPlaybackTracking(videoId: String): String? {
    // TODO during implementation: mirror the exact `verifyVideo` body
    // construction — same InnerTube client context, same WEB_REMIX /
    // ANDROID_MUSIC client id depending on what verifyVideo uses.
    // Return parser.extract(responseJson).
}
```

The implementation is ~15 lines of boilerplate + 1 line calling `PlaybackTrackingParser`. The exact body/header composition matches `verifyVideo`'s existing pattern in the same file — copy from there, don't reinvent.

- [ ] **Step 3: Add `searchCanonical(artist, title)` method**

```kotlin
/**
 * Find the canonical YouTube Music video id for [artist] + [title],
 * preferring ATV ("Artist Topic") then OMV (official music video).
 * Returns null when neither category surfaces a result with acceptable
 * title/artist similarity.
 *
 * Uses the existing InnerTube music-search path. Skips UGC / live /
 * compilation results so the YT Music recommender graph only sees
 * signal for canonical music entities.
 */
suspend fun searchCanonical(artist: String, title: String): String? {
    // Use existing search() helper; pass a strict search query.
    // Filter results where musicVideoType in {ATV, OMV}.
    // Among matches, pick the one with the highest title+artist
    // similarity (reuse MatchScorer — same module via gradle dependency
    // if needed).
    // Return best.videoId, or null if the best isn't ATV/OMV.
}
```

The filter criterion is `musicVideoType in {ATV, OMV}` from the enum in `com.stash.data.ytmusic.model.MusicVideoType`. Leave the exact scoring algorithm to mirror `MatchScorer.bestMatch` in `data:download`.

- [ ] **Step 4: Build**

```bash
./gradlew :data:ytmusic:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt
git commit -m "feat(ytmusic): InnerTubeClient.getPlaybackTracking + searchCanonical"
```

---

### Task 11: `YtCanonicalResolver` with tests

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/youtube/YtCanonicalResolver.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/youtube/YtCanonicalResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.youtube

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class YtCanonicalResolverTest {

    private fun track(
        id: Long = 1L,
        youtubeId: String? = "abc123",
        musicVideoType: String? = null,
        ytCanonicalVideoId: String? = null,
    ) = TrackEntity(
        id = id, title = "Song", artist = "Artist",
        youtubeId = youtubeId, musicVideoType = musicVideoType,
        ytCanonicalVideoId = ytCanonicalVideoId,
    )

    @Test
    fun `ATV track uses stored youtube_id without searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.ATV.name))

        assertEquals("abc123", result)
        verify(innerTube, never()).searchCanonical(any(), any())
    }

    @Test
    fun `OMV track uses stored youtube_id without searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.OMV.name))

        assertEquals("abc123", result)
        verify(innerTube, never()).searchCanonical(any(), any())
    }

    @Test
    fun `cached yt_canonical_video_id is used over searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(
            track(musicVideoType = MusicVideoType.UGC.name, ytCanonicalVideoId = "cached123")
        )

        assertEquals("cached123", result)
        verify(innerTube, never()).searchCanonical(any(), any())
    }

    @Test
    fun `UGC track with no cache triggers search and caches result`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonical("Artist", "Song")).thenReturn("resolved456")
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.UGC.name))

        assertEquals("resolved456", result)
        verify(trackDao).updateYtCanonicalVideoId(1L, "resolved456")
    }

    @Test
    fun `UGC track with no canonical match returns null and does not cache`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonical("Artist", "Song")).thenReturn(null)
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.UGC.name))

        assertNull(result)
        verify(trackDao, never()).updateYtCanonicalVideoId(any(), any())
    }

    @Test
    fun `null musicVideoType with stored youtube_id triggers search`() = runTest {
        // A track whose classification was never determined — treat like UGC.
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonical("Artist", "Song")).thenReturn("resolved789")
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = null))

        assertEquals("resolved789", result)
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.youtube.YtCanonicalResolverTest"
```

Expected: FAIL with `Unresolved reference: YtCanonicalResolver`.

- [ ] **Step 3: Implement the resolver**

```kotlin
package com.stash.core.data.youtube

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.model.MusicVideoType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the best canonical YouTube Music video id for scrobbling a
 * track. Three-tier decision:
 *
 *  1. If `musicVideoType` is ATV or OMV, use the stored `youtubeId`
 *     directly — Stash already downloaded from a canonical source.
 *  2. Else, if `ytCanonicalVideoId` is cached, use it — a previous
 *     resolver pass already did the search.
 *  3. Else, call `InnerTubeClient.searchCanonical`. On hit, persist the
 *     id via `TrackDao.updateYtCanonicalVideoId` and return it. On miss,
 *     return null — caller should mark the event as "handled" and skip
 *     submission (don't pollute Recap with UGC).
 *
 * Pure in the happy path; only writes to `TrackDao` when a new canonical
 * id is discovered. No network except the search call.
 */
@Singleton
class YtCanonicalResolver @Inject constructor(
    private val trackDao: TrackDao,
    private val innerTubeClient: InnerTubeClient,
) {
    suspend fun resolve(track: TrackEntity): String? {
        // Tier 1: ATV/OMV already downloaded — use stored id.
        val mvType = track.musicVideoType
        if (
            (mvType == MusicVideoType.ATV.name || mvType == MusicVideoType.OMV.name)
            && !track.youtubeId.isNullOrBlank()
        ) {
            return track.youtubeId
        }

        // Tier 2: cache hit from a prior search.
        if (!track.ytCanonicalVideoId.isNullOrBlank()) {
            return track.ytCanonicalVideoId
        }

        // Tier 3: search and cache.
        val resolved = innerTubeClient.searchCanonical(track.artist, track.title)
        if (!resolved.isNullOrBlank()) {
            trackDao.updateYtCanonicalVideoId(track.id, resolved)
        }
        return resolved
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.youtube.YtCanonicalResolverTest"
```

Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/youtube/YtCanonicalResolver.kt core/data/src/test/kotlin/com/stash/core/data/youtube/YtCanonicalResolverTest.kt
git commit -m "feat(youtube): YtCanonicalResolver with tier-1/2/3 fallback"
```

---

## Phase 4 — The scrobbler

### Task 12: `YouTubeHistoryScrobbler` core + unit tests

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeHistoryScrobbler.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/youtube/YouTubeHistoryScrobblerTest.kt`

- [ ] **Step 1: Study the reference implementation**

Read `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmScrobbler.kt` top to bottom. Its `start()` + `combine(session, pendingCount) → drainQueue(session)` is the pattern to mirror — the new class is structurally a sibling. Reuse the error-handling style, the `runCatching` conventions, the log tag format, the `DrainResult` shape if it helps the Settings UI.

- [ ] **Step 2: Write failing tests covering the kill-switch transitions**

Focus tests on branching logic, not the happy HTTP path. `PlaybackTrackingParser` + `YtCanonicalResolver` already cover the logic before/after the network call. The scrobbler's testable behavior:

1. Skip all work when `enabled = false` OR `disabledReason != null` OR user not connected to YT Music.
2. On success → `resetConsecutiveFailures()` + `markYtScrobbled(eventId)`.
3. On auth failure (401/403) → don't increment counter; don't trip kill-switch; emit `AUTH_FAILED` health.
4. On protocol failure → increment counter; 5th consecutive → `setDisabledReason("protocol_errors")` + emit `PROTOCOL_BROKEN`.
5. On `start()`: if `BuildConfig.VERSION_CODE > lastKnownVersionCode` → clear `disabledReason`, reset counter, update `lastKnownVersionCode`.

Write one test per transition. Mock `YouTubeScrobblerState`, `InnerTubeClient`, `YtCanonicalResolver`, `YouTubeCookieHelper`, and an `OkHttpClient` wrapper so HTTP calls are synthetic. Keep the test file around 150 lines.

- [ ] **Step 3: Implement `YouTubeHistoryScrobbler`**

Structure:

```kotlin
package com.stash.core.data.youtube

import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.YouTubeHistoryPreference
import com.stash.data.ytmusic.InnerTubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class YouTubeHistoryScrobbler @Inject constructor(
    private val preference: YouTubeHistoryPreference,
    private val state: YouTubeScrobblerState,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val resolver: YtCanonicalResolver,
    private val innerTubeClient: InnerTubeClient,
    private val cookieHelper: YouTubeCookieHelper,
    private val tokenManager: TokenManager,
    private val okHttpClient: OkHttpClient,
    private val versionCodeProvider: () -> Int, // BuildConfig.VERSION_CODE; indirection for tests
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _health = MutableStateFlow(YouTubeScrobblerHealth.DISABLED)
    val health: StateFlow<YouTubeScrobblerHealth> = _health.asStateFlow()

    fun start() {
        scope.launch { maybeClearKillSwitchOnUpdate() }
        scope.launch {
            combine(
                preference.enabled,
                tokenManager.youTubeAuthState,
                listeningEventDao.pendingYtScrobbleCount().distinctUntilChanged(),
                state.disabledReason,
            ) { enabled, auth, _, disabledReason ->
                enabled to (auth /* connected */ to disabledReason)
            }.collect { (enabled, authDisabled) ->
                val (auth, disabledReason) = authDisabled
                when {
                    disabledReason != null -> _health.value = YouTubeScrobblerHealth.PROTOCOL_BROKEN
                    !enabled -> _health.value = YouTubeScrobblerHealth.DISABLED
                    !tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC) ->
                        _health.value = YouTubeScrobblerHealth.DISABLED
                    else -> drainQueue()
                }
            }
        }
    }

    private suspend fun maybeClearKillSwitchOnUpdate() { /* compare versionCode, clear if newer */ }

    private suspend fun drainQueue() {
        val pending = runCatching { listeningEventDao.pendingYtScrobbles(limit = 50) }.getOrElse { return }
        if (pending.isEmpty()) return
        for (event in pending) {
            val track = runCatching { trackDao.getById(event.trackId) }.getOrNull()
            if (track == null) {
                listeningEventDao.markYtScrobbled(event.id)
                continue
            }
            submit(event, track)
            delay(REQUEST_INTERVAL_MS + Random.nextLong(-JITTER_MS, JITTER_MS))
        }
    }

    private suspend fun submit(event: ListeningEventEntity, track: TrackEntity) { /* resolver + POST + health updates */ }

    companion object {
        private const val TAG = "YouTubeHistoryScrobbler"
        private const val REQUEST_INTERVAL_MS = 750L
        private const val JITTER_MS = 250L
        private const val KILL_SWITCH_THRESHOLD = 5
    }
}
```

Fill in the `submit()` internals. Here's the full shape — follow it literally:

```kotlin
private suspend fun submit(event: ListeningEventEntity, track: TrackEntity) {
    val canonicalId = runCatching { resolver.resolve(track) }.getOrNull()
    if (canonicalId.isNullOrBlank()) {
        // No ATV/OMV version found — mark handled, skip submission.
        // Prevents UGC pollution of the recommender graph (spec §5.3).
        runCatching { listeningEventDao.markYtScrobbled(event.id) }
        return
    }

    val trackingUrl = runCatching {
        innerTubeClient.getPlaybackTracking(canonicalId)
    }.getOrNull()
    if (trackingUrl.isNullOrBlank()) {
        onTransientFailure("no_tracking_url")
        return
    }

    val cookies = cookieHelper.currentCookies() ?: run {
        // No YT cookie available → treat as auth failure.
        onAuthFailure()
        return
    }
    val sapiSid = cookieHelper.extractSapiSid(cookies) ?: run {
        onAuthFailure()
        return
    }

    val request = Request.Builder()
        .url(trackingUrl)
        .post("".toRequestBody()) // YT accepts an empty body; query params carry the state.
        .header("Authorization", cookieHelper.generateAuthHeader(sapiSid))
        .header("Cookie", cookies)
        .header("Origin", "https://music.youtube.com")
        .header("X-Goog-AuthUser", "0")
        .header("User-Agent", USER_AGENT)
        .build()

    val code = runCatching {
        okHttpClient.newCall(request).execute().use { it.code }
    }.getOrElse { e ->
        Log.w(TAG, "submit: network error for event=${event.id}", e)
        onTransientFailure("io_error")
        return
    }

    when (code) {
        in 200..299 -> onSuccess(event.id)
        401, 403 -> onAuthFailure()
        else -> {
            Log.w(TAG, "submit: unexpected HTTP $code for event=${event.id}")
            onProtocolFailure()
        }
    }
}

private suspend fun onSuccess(eventId: Long) {
    runCatching { listeningEventDao.markYtScrobbled(eventId) }
    state.resetConsecutiveFailures()
    _health.value = YouTubeScrobblerHealth.OK
}

private suspend fun onTransientFailure(@Suppress("UNUSED_PARAMETER") reason: String) {
    // Don't increment kill-switch counter for transient; just surface OFFLINE.
    _health.value = YouTubeScrobblerHealth.OFFLINE
}

private suspend fun onAuthFailure() {
    // Auth failures do NOT increment the kill-switch counter (spec §5.4).
    _health.value = YouTubeScrobblerHealth.AUTH_FAILED
}

private suspend fun onProtocolFailure() {
    val count = state.incrementConsecutiveFailures()
    if (count >= KILL_SWITCH_THRESHOLD) {
        state.setDisabledReason("protocol_errors")
        _health.value = YouTubeScrobblerHealth.PROTOCOL_BROKEN
    } else {
        _health.value = YouTubeScrobblerHealth.OFFLINE
    }
}

companion object {
    // ...existing constants...
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
```

`cookieHelper.currentCookies()` should return the stored YT cookie string — check how `LastFmApiClient` / `InnerTubeClient` currently retrieve it (probably through a `YouTubeCookieStore` or `TokenManager` — follow the existing wiring). If `YouTubeCookieHelper` doesn't already expose a current-cookies getter, add a small `currentCookies(): String?` helper to it; don't invent a new store.

**Retry semantics:** the spec's exponential backoff (1→2→4→8s, cap 5) applies to transient errors within a single drain pass. Keep v1 simple: `onTransientFailure` just marks OFFLINE and exits — the event stays in the queue, gets retried on the next `drainQueue()` fire (triggered by the next successful play or app foregrounding). If field testing reveals this drains too slowly, layer the explicit backoff in a follow-up.

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.youtube.YouTubeHistoryScrobblerTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeHistoryScrobbler.kt core/data/src/test/kotlin/com/stash/core/data/youtube/YouTubeHistoryScrobblerTest.kt
git commit -m "feat(youtube): YouTubeHistoryScrobbler with kill-switch"
```

---

### Task 13: Start the scrobbler from `StashApplication`

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

- [ ] **Step 1: Inject `YouTubeHistoryScrobbler`**

Add alongside the existing `lastFmScrobbler`:

```kotlin
@Inject
lateinit var youTubeHistoryScrobbler: com.stash.core.data.youtube.YouTubeHistoryScrobbler
```

- [ ] **Step 2: Start it in `onCreate()` after `lastFmScrobbler.start()`**

```kotlin
lastFmScrobbler.start()
youTubeHistoryScrobbler.start()
```

- [ ] **Step 3: Provide `versionCode` via Hilt or a module**

`YouTubeHistoryScrobbler` takes a `() -> Int` for `versionCodeProvider`. Add a simple `@Provides` in an existing Hilt module (or a new small module in `app/di/`) returning `{ BuildConfig.VERSION_CODE }`.

- [ ] **Step 4: Build the full app**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(app): start YouTubeHistoryScrobbler at launch"
```

---

## Phase 5 — Settings UI

### Task 14: Extend `SettingsUiState` with YT history fields

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt`

- [ ] **Step 1: Add three fields**

```kotlin
import com.stash.core.data.youtube.YouTubeScrobblerHealth

// in SettingsUiState:
val ytHistoryEnabled: Boolean = false,
val ytHistoryHealth: YouTubeScrobblerHealth = YouTubeScrobblerHealth.DISABLED,
val ytPendingCount: Int = 0,
```

- [ ] **Step 2: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt
git commit -m "feat(settings): add YT history fields to UiState"
```

---

### Task 15: Wire the preference / scrobbler into `SettingsViewModel`

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: Inject the new deps**

```kotlin
private val youTubeHistoryPreference: YouTubeHistoryPreference,
private val youTubeHistoryScrobbler: YouTubeHistoryScrobbler,
```

- [ ] **Step 2: Extend the `combine(...)` call**

Add three more flows (`youTubeHistoryPreference.enabled`, `youTubeHistoryScrobbler.health`, `listeningEventDao.pendingYtScrobbleCount()`) to the existing `combine`. Map them into the `SettingsUiState` fields added in Task 14.

- [ ] **Step 3: Add two callbacks**

```kotlin
/** Flip the YT-history opt-in. Settings screen shows the first-enable
 *  dialog; by the time this is called, the user has already confirmed. */
fun onYouTubeHistoryEnabledChanged(enabled: Boolean) {
    viewModelScope.launch {
        youTubeHistoryPreference.setEnabled(enabled)
    }
}

/** Clear the kill-switch after PROTOCOL_BROKEN. Exposed to the Settings
 *  UI's "Retry YouTube sync" button on the red health badge. */
fun onRetryYouTubeHistory() {
    viewModelScope.launch {
        youTubeScrobblerState.setDisabledReason(null)
        youTubeScrobblerState.resetConsecutiveFailures()
    }
}
```

Inject `private val youTubeScrobblerState: YouTubeScrobblerState` alongside the other deps for this callback.

- [ ] **Step 4: Build**

```bash
./gradlew :feature:settings:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt
git commit -m "feat(settings): VM wiring for YT history toggle"
```

---

### Task 16: `YouTubeHistorySyncSection` composable

**Files:**
- Create: `feature/settings/src/main/kotlin/com/stash/feature/settings/components/YouTubeHistorySyncSection.kt`

- [ ] **Step 1: Sketch the composable**

Mirror the `QualityTier` radio-row pattern from `SettingsScreen.kt` (we used it for the Downloads/network-mode section in v0.5.5). Structure:

```kotlin
@Composable
fun YouTubeHistorySyncSection(
    enabled: Boolean,
    health: YouTubeScrobblerHealth,
    pendingCount: Int,
    ytConnected: Boolean,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = ytConnected) {
                    if (!enabled) showConfirm = true else onToggle(false)
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Send plays to YouTube Music",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (ytConnected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        !ytConnected -> "Connect YouTube Music first"
                        enabled -> statusLine(health, pendingCount)
                        else -> "Off"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(health),
                )
            }
            Switch(checked = enabled, onCheckedChange = null, enabled = ytConnected)
        }
        if (enabled && health == YouTubeScrobblerHealth.PROTOCOL_BROKEN) {
            TextButton(onClick = onRetry) { Text("Retry YouTube sync") }
        }
    }

    if (showConfirm) {
        FirstEnableConfirmDialog(
            onDismiss = { showConfirm = false },
            onConfirm = { showConfirm = false; onToggle(true) },
        )
    }
}
```

- [ ] **Step 2: Add the first-enable dialog using the exact spec §5.1 copy**

The dialog text is in the spec. Use `AlertDialog` matching the existing SettingsScreen dialog patterns (e.g., the Spotify cookie dialog).

- [ ] **Step 3: Add `statusLine` + `statusColor` helpers**

```kotlin
private fun statusLine(health: YouTubeScrobblerHealth, pending: Int): String = when (health) {
    YouTubeScrobblerHealth.OK -> if (pending == 0) "Up to date" else "$pending pending"
    YouTubeScrobblerHealth.OFFLINE -> "$pending pending · offline"
    YouTubeScrobblerHealth.AUTH_FAILED -> "YT Music connection lost — reconnect"
    YouTubeScrobblerHealth.PROTOCOL_BROKEN -> "Disabled due to errors — will re-enable in next update"
    YouTubeScrobblerHealth.DISABLED -> "Off"
}

@Composable
private fun statusColor(health: YouTubeScrobblerHealth) = when (health) {
    YouTubeScrobblerHealth.OK -> MaterialTheme.colorScheme.primary
    YouTubeScrobblerHealth.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
    YouTubeScrobblerHealth.AUTH_FAILED -> MaterialTheme.colorScheme.error
    YouTubeScrobblerHealth.PROTOCOL_BROKEN -> MaterialTheme.colorScheme.error
    YouTubeScrobblerHealth.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
}
```

- [ ] **Step 4: Build**

```bash
./gradlew :feature:settings:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/components/YouTubeHistorySyncSection.kt
git commit -m "feat(settings): YouTubeHistorySyncSection composable"
```

---

### Task 17: Insert the section into `SettingsScreen`

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt`

- [ ] **Step 1: Place it under the Downloads `GlassCard`**

In the "Downloads" section we built in v0.5.5, after the network-mode radio group, add:

```kotlin
Spacer(modifier = Modifier.height(12.dp))
HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
Spacer(modifier = Modifier.height(12.dp))

YouTubeHistorySyncSection(
    enabled = uiState.ytHistoryEnabled,
    health = uiState.ytHistoryHealth,
    pendingCount = uiState.ytPendingCount,
    ytConnected = uiState.youTubeAuthState is AuthState.Connected,
    onToggle = onYouTubeHistoryEnabledChanged,
    onRetry = onRetryYouTubeHistory,
)
```

- [ ] **Step 2: Add the two new callback parameters** to both `SettingsScreen(...)` and `SettingsContent(...)` signatures, wired through to the VM.

- [ ] **Step 3: Build**

```bash
./gradlew :feature:settings:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt
git commit -m "feat(settings): embed YouTubeHistorySyncSection in Downloads card"
```

---

## Phase 6 — Version bump & verification

### Task 18: Bump version and build

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump**

```kotlin
versionCode = 28       // was 27
versionName = "0.6.0"  // was 0.5.5
```

- [ ] **Step 2: Full build + install on device**

```bash
./gradlew :app:installDebug
```

Expected: `Installed on 1 device.`

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump to 0.6.0"
```

---

### Task 19: On-device verification

- [ ] **Step 1: Launch app → Settings → Downloads**

Expect to see the new "Send plays to YouTube Music" row. Switch should be OFF. If YT not connected, row is greyed out.

- [ ] **Step 2: Ensure YouTube Music is connected** (Accounts card, paste cookies if needed)

- [ ] **Step 3: Tap the YT history toggle** — the first-enable dialog should appear with spec §5.1 copy

- [ ] **Step 4: Tap "Enable"** — switch flips on, dialog dismisses

- [ ] **Step 5: Play one track** whose `musicVideoType` is ATV or OMV. Let it play past the threshold (≥30s or 50%). Wait ~60s.

- [ ] **Step 6: Verify on the web**

Open `https://music.youtube.com/history` in a browser logged into the same Google account. The track should appear at the top.

- [ ] **Step 7: Check Settings status line** — should read `"Up to date"` with green indicator.

- [ ] **Step 8: Turn off WiFi, play another track, wait for threshold, turn WiFi back on**

Settings status line should briefly show `"1 pending · offline"` then return to `"Up to date"` after drain.

---

### Task 20: Release

- [ ] **Step 1: Push master + tag**

```bash
git push origin master
git tag v0.6.0
git push origin v0.6.0
```

The release workflow builds and publishes `Stash-v0.6.0.apk`.

- [ ] **Step 2: Watch the workflow**

```bash
gh run watch $(gh run list --limit 1 --json databaseId -q '.[0].databaseId') --exit-status
```

- [ ] **Step 3: Confirm release**

```bash
gh release view v0.6.0
```

---

## Notes for implementers

- **Reference implementation to study:** `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmScrobbler.kt` — the new scrobbler is a structural sibling. Same `combine(session, pendingCount) → drainQueue()` shape, same error-handling taste, same logging conventions.
- **Don't add a manual "Retry now" drain button yet.** The spec §8 flags it as optional; defer unless the v1 badge is insufficient.
- **Don't add a sticky notification for auth failure yet.** Also spec §8 — in-app badge only for v1.
- **The `OkHttpClient` instance** injected into `YouTubeHistoryScrobbler` is the app-wide singleton — do not build a new one. See how `LastFmApiClient` receives it.
- **Testing HTTP:** don't add an HTTP fixture server for unit tests. The submission POST is behind an interface; mock at the `submit()` boundary. On-device verification in Task 19 is the contract test for the real network path.
- **YT `musicVideoType` values:** `ATV`, `OMV`, `UGC`, `OFFICIAL_SOURCE_MUSIC`, `PODCAST_EPISODE` — enum in `com.stash.data.ytmusic.model.MusicVideoType`. Only ATV and OMV are canonical for music-recommender purposes.
