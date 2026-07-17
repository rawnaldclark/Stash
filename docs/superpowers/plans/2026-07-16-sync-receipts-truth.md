# Sync Receipts Truth — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each "recent syncs" receipt honest — show Online/Offline (not the dead "Manual" label), show the surfaced-count in Online mode instead of a misleading "+0 tracks", and mark cancelled syncs as cancelled instead of a green success.

**Architecture:** Persist the streaming mode per sync via one nullable Boolean column + a Room migration (32→33). Add a real `CANCELLED` sync state so cancellation stops masquerading as idle. Thread both facts through the existing entity → `SyncHistoryInfo` → `SyncScreen` → `RecentSyncRow` → `RecentSyncsCard` path. Pre-migration rows have `streamingMode = null` and suppress the mode label rather than mislabel.

**Tech Stack:** Kotlin, Room, Hilt/Dagger, Jetpack Compose, JUnit + mockito-kotlin (module's existing test harness), Room `MigrationTestHelper`.

**Branch:** extends the existing "recent syncs receipts" work on `feat/library-redesign` (commit `d39eac30`). Continue there.

**Pre-migration caveat:** existing history rows get `streamingMode = null`; the UI shows no mode label and falls back to `tracks_downloaded` for the count on those rows only.

---

## File Structure

**Modify:**
- `core/model/.../SyncStatus.kt` — add `CANCELLED` to `SyncState`.
- `core/model/.../SyncDisplayStatus.kt` — add `Cancelled` object.
- `core/data/.../db/entity/SyncHistoryEntity.kt` — add `streamingMode: Boolean?` column.
- `core/data/.../db/StashDatabase.kt` — `MIGRATION_32_33`, bump `version = 33`.
- `core/data/.../di/DatabaseModule.kt` — register `MIGRATION_32_33`.
- `core/data/.../sync/SyncDisplayStatusMapper.kt` — map `CANCELLED → Cancelled`.
- `core/data/.../sync/workers/PlaylistFetchWorker.kt` — capture `streamingMode` at row creation.
- `core/data/.../sync/workers/TrackDownloadWorker.kt` — write `CANCELLED` on cancellation (was `IDLE`).
- `feature/sync/.../SyncViewModel.kt` — add `streamingMode` to `SyncHistoryInfo` + `toInfo()`.
- `feature/sync/.../SyncScreen.kt` — build `RecentSyncRow` with mode + mode-aware count + cancelled status.
- `feature/sync/.../components/RecentSyncsCard.kt` — render mode label, surfaced/downloaded verb, Cancelled pill.

**Test:**
- `core/data/src/test/.../MigrationTest.kt` (or the module's existing migration test) — 32→33.
- `core/data/src/test/.../SyncDisplayStatusMapperTest.kt` — CANCELLED mapping.
- `feature/sync/src/test/.../RecentSyncRowMappingTest.kt` (new) — mode-aware count + label logic.

---

## Task 1: Add `CANCELLED` sync state (and fix every exhaustive `when` it breaks)

**Files:**
- Modify: `core/model/src/main/kotlin/com/stash/core/model/SyncStatus.kt:14`
- Modify: `core/model/src/main/kotlin/com/stash/core/model/SyncDisplayStatus.kt`
- Modify: `core/data/.../sync/SyncDisplayStatusMapper.kt:36`
- Modify: `feature/sync/.../components/SyncStatusCard.kt:145-155, 172-179` — two exhaustive `when(displayStatus)` with **no `else`**; both break the moment `Cancelled` exists.
- Modify: `feature/sync/.../SyncScreen.kt:301-308` — exhaustive `when(displayStatus)` with no `else`; add a placeholder branch now (Task 5 refines it).
- Modify: `feature/sync/.../SyncViewModel.kt:528-535` — `healthLabel`/`healthColor` have an `else`, so they compile, but a cancelled latest sync would silently show a blank mark; add explicit handling.
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/SyncDisplayStatusMapperTest.kt`

> **Why this task is bigger than "add an enum value":** `SyncDisplayStatus` is a sealed class. Adding `Cancelled` makes every exhaustive `when` over it fail to compile. The reviewer confirmed three no-`else` sites (`SyncStatusCard` ×2, `SyncScreen` ×1). This task keeps the whole build green by fixing all of them together. **No DB migration is needed** — `SyncState` is stored by **name** (`Converters.syncStateToString = value?.name`; `status TEXT NOT NULL` in `32.json`), so appending `CANCELLED` is schema-safe.

- [ ] **Step 1: Write the failing test:**
```kotlin
@Test
fun `cancelled state maps to Cancelled display status`() {
    val entity = SyncHistoryEntity(
        status = SyncState.CANCELLED,
        errorMessage = "Cancelled",
        tracksDownloaded = 3,
    )
    assertThat(entity.toDisplayStatus()).isEqualTo(SyncDisplayStatus.Cancelled)
}
```
(`assertThat`/Truth IS available in `:core:data` tests.)

- [ ] **Step 2: Run it, verify it fails** — `./gradlew :core:data:testDebugUnitTest --tests "*SyncDisplayStatusMapperTest*"`. Expected: compile error.

- [ ] **Step 3: Add the enum value + display object.** In `SyncStatus.kt` append `CANCELLED` at the END of the enum; in `SyncDisplayStatus.kt` add `data object Cancelled : SyncDisplayStatus()`.

- [ ] **Step 4: Map it** in `SyncDisplayStatusMapper.kt`:
```kotlin
        SyncState.IDLE -> SyncDisplayStatus.Idle
        SyncState.CANCELLED -> SyncDisplayStatus.Cancelled
```

- [ ] **Step 5: Fix the three exhaustive `when` sites** so the module compiles.
  - `SyncStatusCard.kt:145` label `when`: add `SyncDisplayStatus.Cancelled -> "Cancelled"`.
  - `SyncStatusCard.kt:172` dot-color `when`: add `SyncDisplayStatus.Cancelled -> extendedColors.onSurfaceVariant` (neutral, not red/green — use the module's dim/neutral color; `MaterialTheme.colorScheme.onSurfaceVariant` if no extended neutral exists).
  - `SyncScreen.kt:301` row-status `when`: add `SyncDisplayStatus.Cancelled -> SyncRowStatus.PARTIAL` **as a temporary placeholder** — Task 5 changes it to the new `SyncRowStatus.CANCELLED`.
  - `SyncViewModel.kt:528` `healthLabel` and `:536` `healthColor`: add explicit `SyncDisplayStatus.Cancelled -> "⊘"` (or `""`) and a neutral color, so a cancelled latest sync doesn't fall through the `else` to a blank/transparent mark.

- [ ] **Step 6: Run the test + compile the feature module** — `./gradlew :core:data:testDebugUnitTest --tests "*SyncDisplayStatusMapperTest*" :feature:sync:compileDebugKotlin`. Expected: test PASS, compile SUCCESSFUL.

- [ ] **Step 7: Commit** — `feat(sync): add CANCELLED sync state distinct from idle`.

---

## Task 2: Persist streaming mode per sync (column + migration)

**Files:**
- Modify: `core/data/.../db/entity/SyncHistoryEntity.kt:47`
- Modify: `core/data/.../db/StashDatabase.kt:82` (version) + migration objects block
- Modify: `core/data/.../di/DatabaseModule.kt` (`addMigrations`)
- Test: the module's Room migration test (uses `MigrationTestHelper`, `exportSchema=true` so schema `33.json` is generated)

- [ ] **Step 1: Write the failing migration test** — 32→33 adds `streaming_mode`, old rows read null. Mirror the existing `MigrationV31V32Test.kt` scaffold exactly (`@RunWith(RobolectricTestRunner)`, `@Config(sdk = [33])`, `MigrationTestHelper(InstrumentationRegistry..., StashDatabase::class.java)`); these migration tests live in `src/test` under Robolectric, so `:core:data:testDebugUnitTest` is the right runner.

```kotlin
@Test
fun migrate32To33_addsStreamingModeColumn_defaultsNull() {
    helper.createDatabase(TEST_DB, 32).apply {
        execSQL(
            "INSERT INTO sync_history (started_at, status, playlists_checked, " +
            "new_tracks_found, tracks_downloaded, tracks_failed, bytes_downloaded, trigger) " +
            "VALUES (0, 'COMPLETED', 5, 100, 0, 0, 0, 'MANUAL')"
        )
        close()
    }
    val db = helper.runMigrationsAndValidate(TEST_DB, 33, true, StashDatabase.MIGRATION_32_33)
    db.query("SELECT streaming_mode FROM sync_history LIMIT 1").use { c ->
        assertThat(c.moveToFirst()).isTrue()
        assertThat(c.isNull(0)).isTrue()  // pre-migration rows: unknown mode
    }
}
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew :core:data:testDebugUnitTest --tests "*Migration*"`. Expected: FAIL (no `MIGRATION_32_33`).

- [ ] **Step 3: Add the column** in `SyncHistoryEntity.kt` after `trigger`:
```kotlin
    val trigger: SyncTrigger = SyncTrigger.MANUAL,

    /**
     * Whether this run executed in Online/streaming mode (true) or
     * Offline/download mode (false). Null on rows written before the
     * mode column existed — the UI suppresses the label for those.
     */
    @ColumnInfo(name = "streaming_mode")
    val streamingMode: Boolean? = null,
```

- [ ] **Step 4: Add the migration** in `StashDatabase.kt` (mirror the existing `MIGRATION_31_32` object), and bump `version = 33` at line 82:
```kotlin
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_history ADD COLUMN streaming_mode INTEGER DEFAULT NULL")
            }
        }
```
Register it in `DatabaseModule.kt` `addMigrations(...)` (append `StashDatabase.MIGRATION_32_33`).

- [ ] **Step 5: Run the test, verify it passes.** Expected: PASS (and Room regenerates `schemas/.../33.json`; commit it).

- [ ] **Step 6: Commit** — stage `core/data` entity, db, module, the generated `33.json`, and the test. Message: `feat(sync): persist streaming (online/offline) mode per sync row`.

---

## Task 3: Capture mode at row creation; write CANCELLED on cancel

**Files:**
- Modify: `core/data/.../sync/workers/PlaylistFetchWorker.kt:122` (row creation) + constructor (~:71)
- Modify: `core/data/.../sync/workers/TrackDownloadWorker.kt:~620` (cancellation handler)

- [ ] **Step 1: Inject `StreamingPreference` into `PlaylistFetchWorker`** (it's a `@Singleton` in `core:data`, already injected into `DiffWorker`). Add the constructor param.

- [ ] **Step 2: Set the mode** in the `SyncHistoryEntity(...)` built at `PlaylistFetchWorker.kt:122`:
```kotlin
            trigger = SyncTrigger.MANUAL,
            streamingMode = streamingPreference.current(),
```

- [ ] **Step 3: Change the cancellation write** in `TrackDownloadWorker` — the `CancellationException` handler currently sets `status = SyncState.IDLE`. Change to:
```kotlin
            status = SyncState.CANCELLED,
```
(leave `completedAt = now`, `errorMessage = "Cancelled"` as-is).

- [ ] **Step 4: Verify build** — `./gradlew :core:data:compileDebugKotlin`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** — `feat(sync): record mode at sync start + mark cancelled runs CANCELLED`.

> No unit test here — these are single-field writes exercised end-to-end by the device smoke in Task 6. (ponytail: don't fixture a WorkManager worker for a two-line assignment.)

---

## Task 4: Thread mode through the presentation model

**Files:**
- Modify: `feature/sync/.../SyncViewModel.kt:39-58` (`SyncHistoryInfo`) + `:686` (`toInfo()`)

- [ ] **Step 1:** Add `streamingMode` to `SyncHistoryInfo`, AND give `tracksDownloaded`/`tracksFailed` defaults so the Task 5 test constructors compile without every field (reviewer: those two currently have no defaults):
```kotlin
    val status: String,
    val tracksDownloaded: Int = 0,   // was: no default
    val tracksFailed: Int = 0,       // was: no default
    ...
    val streamingMode: Boolean? = null,  // Online (true) / Offline (false) / unknown-legacy (null)
```
- [ ] **Step 2:** Map it in `toInfo()`:
```kotlin
        streamingMode = streamingMode,
```
- [ ] **Step 3: Build** — `./gradlew :feature:sync:compileDebugKotlin`. Expected: SUCCESSFUL.
- [ ] **Step 4: Commit** — `feat(sync): expose streamingMode on SyncHistoryInfo`.

---

## Task 5: Render mode label, surfaced/downloaded count, Cancelled pill

**Files:**
- Modify: `feature/sync/.../components/RecentSyncsCard.kt:44-58, 108, 127`
- Modify: `feature/sync/.../SyncScreen.kt:291-312`
- Test: `feature/sync/src/test/kotlin/com/stash/feature/sync/RecentSyncRowMappingTest.kt` (new — extract the mapping into a pure function to test it)

- [ ] **Step 1: Write the failing test** for a **pure** mapping function `SyncHistoryInfo.toRecentSyncRow(relativeTime: String): RecentSyncRow`.

> Two constraints the reviewer surfaced, baked in below:
> - **Purity:** the function must NOT call `formatRelativeTime` (it wraps Android `DateUtils`, which returns null under `isReturnDefaultValues=true` and NPEs). Take `relativeTime` as a **parameter**; the `SyncScreen` call site fills it via `formatRelativeTime(it.startedAt)`. `formatSyncDuration`/`formatSyncBytes` are pure Kotlin and stay inside.
> - **Assertions:** `:feature:sync` test deps are junit + coroutines-test + mockk only — **no Truth/AssertJ**. Use JUnit `assertEquals`/`assertNull` (or add `testImplementation(libs.truth)` to `feature/sync/build.gradle.kts` if you prefer `assertThat`). Snippets below use JUnit.

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

@Test
fun `online sync shows surfaced count and Online label`() {
    val row = SyncHistoryInfo(
        id = 1, startedAt = 0, completedAt = 1000, status = "COMPLETED",
        tracksDownloaded = 0, tracksFailed = 0, newTracksFound = 1578,
        playlistsChecked = 50, streamingMode = true,
        displayStatus = SyncDisplayStatus.Success,
    ).toRecentSyncRow(relativeTime = "35m ago")
    assertEquals("Online", row.modeLabel)
    assertEquals(1578, row.added)
    assertEquals("surfaced", row.addedNoun)
}

@Test
fun `offline sync shows downloaded count and Offline label`() {
    val row = SyncHistoryInfo(
        id = 2, startedAt = 0, completedAt = 1000, status = "COMPLETED",
        tracksDownloaded = 340, tracksFailed = 0, newTracksFound = 400,
        playlistsChecked = 12, streamingMode = false,
        displayStatus = SyncDisplayStatus.Success,
    ).toRecentSyncRow(relativeTime = "1h ago")
    assertEquals("Offline", row.modeLabel)
    assertEquals(340, row.added)
    assertEquals("downloaded", row.addedNoun)
}

@Test
fun `pre-migration row suppresses mode label and falls back to downloaded`() {
    val row = SyncHistoryInfo(
        id = 3, startedAt = 0, completedAt = 1000, status = "COMPLETED",
        tracksDownloaded = 5, tracksFailed = 0, newTracksFound = 5, streamingMode = null,
        displayStatus = SyncDisplayStatus.Success,
    ).toRecentSyncRow(relativeTime = "yesterday")
    assertNull(row.modeLabel)
    assertEquals(5, row.added)
}

@Test
fun `cancelled sync is marked CANCELLED not green`() {
    val row = SyncHistoryInfo(
        id = 4, startedAt = 0, completedAt = 1000, status = "CANCELLED",
        tracksDownloaded = 0, tracksFailed = 0, streamingMode = true,
        displayStatus = SyncDisplayStatus.Cancelled,
    ).toRecentSyncRow(relativeTime = "2m ago")
    assertEquals(SyncRowStatus.CANCELLED, row.status)
}
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew :feature:sync:testDebugUnitTest --tests "*RecentSyncRowMapping*"`. Expected: compile error.

- [ ] **Step 3: Extend `RecentSyncRow` and add `SyncRowStatus.CANCELLED`** in `RecentSyncsCard.kt`:
```kotlin
data class RecentSyncRow(
    val id: Long,
    val modeLabel: String?,      // "Online" / "Offline"; null on pre-migration rows
    val relativeTime: String,
    val duration: String?,
    val added: Int,
    val addedNoun: String,       // "surfaced" (online) / "downloaded" (offline/legacy)
    val playlists: Int,
    val sizeLabel: String?,
    val failed: Int,
    val status: SyncRowStatus,
    val errorMessage: String? = null,
    val diagnostics: String? = null,
)

enum class SyncRowStatus { HEALTHY, PARTIAL, FAILED, CANCELLED }
```

- [ ] **Step 4: Add the pure mapping function** (in a small `RecentSyncRowMapping.kt` next to `SyncScreen.kt`), replacing the inline `map { }` at `SyncScreen.kt:291`. Note `relativeTime` is a **parameter** (purity) and the `Cancelled` branch now yields the real `SyncRowStatus.CANCELLED` (Task 1 left a `PARTIAL` placeholder here):
```kotlin
fun SyncHistoryInfo.toRecentSyncRow(relativeTime: String): RecentSyncRow {
    val online = streamingMode
    return RecentSyncRow(
        id = id,
        modeLabel = when (online) { true -> "Online"; false -> "Offline"; null -> null },
        relativeTime = relativeTime,
        duration = formatSyncDuration(startedAt, completedAt),
        added = if (online == true) newTracksFound else tracksDownloaded,
        addedNoun = if (online == true) "surfaced" else "downloaded",
        playlists = playlistsChecked,
        sizeLabel = formatSyncBytes(bytesDownloaded),
        failed = tracksFailed,
        status = when (displayStatus) {
            SyncDisplayStatus.Success -> SyncRowStatus.HEALTHY
            is SyncDisplayStatus.PartialSuccess -> SyncRowStatus.PARTIAL
            is SyncDisplayStatus.Interrupted -> SyncRowStatus.PARTIAL
            is SyncDisplayStatus.Failed -> SyncRowStatus.FAILED
            SyncDisplayStatus.Cancelled -> SyncRowStatus.CANCELLED
            SyncDisplayStatus.Running -> SyncRowStatus.PARTIAL
            SyncDisplayStatus.Idle -> SyncRowStatus.PARTIAL
        },
        errorMessage = errorMessage,
        diagnostics = diagnostics,
    )
}
```
Then at `SyncScreen.kt:291`: `val rows = uiState.recentSyncs.map { it.toRecentSyncRow(formatRelativeTime(it.startedAt)) }` — the one Android-touching call stays at the Compose call site, out of the tested path.

- [ ] **Step 5: Update the card render** in `RecentSyncsCard.kt`:
  - Line 1 (around :108): render `row.modeLabel` when non-null, else start with the relative time alone:
    ```kotlin
    if (row.modeLabel != null) {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)) { append(row.modeLabel) }
        withStyle(SpanStyle(color = dim)) { append("  ·  ${row.relativeTime}") }
    } else {
        withStyle(SpanStyle(color = dim)) { append(row.relativeTime) }
    }
    ```
  - Line 2 noun (around :138): `append(row.addedNoun)` instead of the literal `"tracks"`.
  - Cancelled branch (around :127): before the FAILED check, add:
    ```kotlin
    if (row.status == SyncRowStatus.CANCELLED) {
        Pill("Cancelled", dim)          // neutral, not the red fail tint
        Spacer(Modifier.weight(1f))
    } else if (row.status == SyncRowStatus.FAILED && row.added == 0) {
        Pill("Sync failed", fail)
        Spacer(Modifier.weight(1f))
    } else { /* existing receipt line */ }
    ```

- [ ] **Step 6: Run the test, verify it passes.** Expected: PASS.

- [ ] **Step 7: Update the Compose preview** in `RecentSyncsCard.kt` (if one exists) to include an Online, an Offline, and a Cancelled row so the preview stays representative.

- [ ] **Step 8: Commit** — `feat(sync): receipts show Online/Offline + surfaced count + Cancelled marker`.

---

## Task 6: Device verification

- [ ] **Step 1: Build + install** — `./gradlew :app:installDebug`. Expected: `Installed on 1 device`.

- [ ] **Step 2: Trigger a real Online sync** — with Online mode selected, tap Sync Now; let it finish. On the Sync tab, the newest receipt reads **"Online · <time>"** with **"+N surfaced"** (N > 0), not "+0 tracks".

- [ ] **Step 3: Trigger + cancel a sync** — start a sync, cancel it. Its receipt shows a neutral **"Cancelled"** pill, not a green "+0 tracks".

- [ ] **Step 4: Confirm a pre-migration row** (one of the existing yesterday rows) shows **no** mode label and doesn't crash. (Screencap is black on this Android 16 build — capture via `screenrecord` + `ffmpeg -frames:v 1`.)

- [ ] **Step 5:** Report PASS/FAIL with a captured frame of the receipts list.

---

## Verification checklist (whole plan)

- [ ] `./gradlew :core:data:testDebugUnitTest :feature:sync:testDebugUnitTest` green (use `--tests` filters; do not gate on the known-flaky `:core:media` / preexisting matcher suites — see memory).
- [ ] Migration 32→33 test passes; `33.json` committed.
- [ ] Device: Online receipt shows surfaced count; Cancelled shows Cancelled; legacy rows show no mode label.
