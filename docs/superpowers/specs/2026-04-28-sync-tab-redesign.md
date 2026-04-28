# Sync Tab Redesign Design

## Problem

The current Sync tab (`feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`, 1322 lines) suffers from three concrete UX failures the user has called out:

1. **Cluttered preferences cards.** The Spotify and YouTube Music preference dropdowns each contain a `SyncMode` chip row plus per-category sub-sections (Liked Songs, Spotify Mixes, Daily Mixes, Custom Playlists), each with N individual toggle rows. When expanded, the cards become a wall of toggles. Today's collapsed-state header gives the user almost no signal about what's currently selected — it's just the title and an explainer line.

2. **Clanky schedule timer.** The "Sync time" row is a labeled `Button` whose label is a formatted hours:minutes string. Tapping opens a Material 3 `TimePicker` in a `Dialog`. The flow is utilitarian, the picker hides the schedule context, and there is no concept of "which days" — sync runs every day at the chosen time.

3. **Generic look — doesn't feel premium.** Connected Sources tiles, Sync Now button, ScheduleCard, and SyncHistoryRow all use stock Material 3 patterns with brand color icons and stock switches. Nothing leverages Stash's actual design system (GlassCard glassmorphism over near-black, StashPurple/Cyan accents) to feel distinct.

The app is approaching its first public visibility window (~100 GitHub stars). The Sync tab is the most-used "settings-like" surface and shapes how the app feels overall.

## Goals

- Restructure the Sync tab using the existing single-scroll layout (no sub-tabs, no new screens) but with a stronger visual hierarchy and a premium glassmorphism feel that's true to the rest of the app.
- Replace the schedule timer with a natural-language sentence pattern (`"Sync on weekdays at 6:00 AM on Wi-Fi only"`) where the underlined parts are tappable chips.
- Add **days-of-week** as a first-class scheduling field. The user can select any subset of M-T-W-T-F-S-S; the WorkManager scheduler honors it.
- Compress the preferences cards' resting state to a single brand-bar + status-pills + track-count summary so the wall of toggles only appears when the user explicitly drills in.
- Move away from a top-level "Connected Sources" tile row — the new Source Preferences cards already show brand identity and connection state.

## Non-goals

- Sub-tabs within the Sync tab (Now / Sources / Schedule / History) or a hero-with-drill-in tile grid. Both were considered and explicitly rejected by the user during brainstorming in favor of "polish in place."
- A bottom-sheet drill-in for Source Preferences. Bottom sheets fight the chosen "stay on one screen" direction.
- Demoting Library Maintenance or Sync History to the Settings tab. Both stay on Sync.
- A redesign of the per-category drill-in screen (when a user taps the "›" arrow next to "Custom Playlists 1/12 ›" inside an expanded source card). This spec stops at the new collapsed/expanded source card shape; the drill-in destination keeps today's row-of-toggles design and is out of scope.
- A redesign of the Failed Matches / Blocked Songs screens reachable from the review queue.
- Adding new types of schedule rules beyond days-of-week (e.g. "every other day", "first of the month"). The day-bitmask covers 95% of real schedules.

## Architecture

### Information architecture (top-to-bottom)

The new Sync tab has a single `LazyColumn` with the following items, in order. Items in **bold** are new or substantially redesigned; everything else is light polish.

1. **Header** — `"Sync"` title (existing, kept).
2. **Hero card** *(new)* — gradient-tinted GlassCard combining last-sync metadata (timestamp + track count + health indicator), an inline "Sync Now" purple button, and a sync-progress affordance that replaces the button while a sync is running.
3. **Review queue card** — existing `UnmatchedSongsCard`. Conditional (only when `unmatchedCount + flaggedCount > 0`). Light polish to align with new card visual language.
4. **Sources section header** *(new)* — small uppercase `"SOURCES"` label.
5. **Spotify Source Preferences card** *(redesigned)* — brand-bar + name + connection chip + status pills + summary line in collapsed state; expands inline to category rows with master toggles.
6. **YouTube Music Source Preferences card** *(redesigned)* — same shape; carries the new "Studio only" badge inline within the Liked Songs status pill.
7. **Schedule section header** *(new)* — small uppercase `"SCHEDULE"` label.
8. **Schedule card** *(redesigned)* — auto-sync switch on top, then a sentence with tappable chips: `"Sync <days> at <time> on <network>"`.
9. **Recent syncs section header** *(new)* — small uppercase `"RECENT SYNCS"` label.
10. **Recent syncs card** — existing `SyncHistoryRow` content rendered inside a single GlassCard with subtle dividers (current implementation has each row as its own card; this consolidates).
11. **Library section header** *(new)* — small uppercase `"LIBRARY"` label.
12. **Library Maintenance card** — existing `LibraryMaintenanceCard`, slightly tighter row layout to match the new aesthetic.

Items 1, 4, 7, 9, 11 are all-new; the rest evolve.

The top-level "Connected Sources" Row (currently `SourceCard` + `SourceCard` at lines 111-136 of `SyncScreen.kt`) is **removed**. Connection status migrates into the Source Preferences cards as a small `Connected` chip in the header row.

### Stash design system grounding

This redesign is constrained to existing primitives — no new theme tokens, no new shape system, no new typography scale. References to `feedback_stash_design_system.md` confirm that generic dark-mode mockups get rejected; the design must use:

- `GlassCard` (`core/ui/src/main/kotlin/com/stash/core/ui/components/GlassCard.kt`) for every card surface.
- `StashTheme.extendedColors` for `glassBackground`, `glassBorder`, `spotifyGreen`, `youtubeRed`, plus `MaterialTheme.colorScheme.primary` (StashPurple `#8B5CF6`) and `MaterialTheme.colorScheme.onSurfaceVariant` for muted text.
- `MaterialTheme.shapes.large` (16dp rounded) for cards, smaller pill radius (~999dp) for chips.
- `MaterialTheme.typography` scale — `headlineLarge` for the screen title, `titleMedium` for source names, `bodyMedium` for sentence body, `bodySmall` for summary lines, `labelSmall` for section headers and chip labels.

A 3dp-wide brand-color bar runs the full height of each Source Preferences card on its leading edge — uses `StashTheme.extendedColors.spotifyGreen` and `youtubeRed` directly. This is the only new visual primitive introduced; it's a `Box` with a fixed width, not a new component file.

### Hero card

A `GlassCard` with a custom gradient border tint (`linear gradient from primary 18% alpha to cyan 8% alpha`) and an inset purple `Sync Now` button. The card carries:

- A small uppercase eyebrow `"LAST SYNC"`.
- Body text `"<relative time> · <track count> tracks"` (e.g. `"2 hours ago · 1,821 tracks"`). Pulls from the most recent `SyncHistoryEntity`. When no syncs have run, body reads `"Never synced — tap below to start."`.
- Health indicator on the right: small chip styled like the existing partial/healthy distinction. `✓ healthy` (green) when the most recent sync has no errors, `! partial` (amber) when partial, `× failed` (red) when the most recent sync errored.
- Below the metadata row, a full-width `Button` with `containerColor = MaterialTheme.colorScheme.primary` and a `▶` icon. While syncing, the button is replaced by the existing `SyncActionSection` progress UI — this card *contains* the progress, not in addition to it. The current `SyncActionSection` is absorbed.

### Source Preferences card (Spotify · YouTube)

The collapsed state is a `Row` inside a `GlassCard` with three regions:

- **Leading 3dp brand bar** running the card's full height, colored `spotifyGreen` or `youtubeRed`.
- **Body column** containing a header row (source name + small `Connected` / `Disconnected` chip + expand chevron), a status-pill row (4-5 small chips summarizing what's currently set to sync), and a summary line (`"5 of 35 playlists · 1,247 tracks"`).
- **Trailing chevron** indicating expand state.

Status pills are a uniform component but their content varies by source:

- **Spotify pills:** `Liked ✓` (green-tinted, when Liked Songs syncs), `Mixes 3/4` (purple-tinted muted), `Custom 1/12` (purple-tinted muted), `Refresh` or `Accumulate` (purple chip).
- **YouTube pills:** `Liked · Studio only` (red-tinted; appended `Studio only` only when the Liked filter is enabled — a single combined chip rather than two), `11/11 mixes`, `24/24 playlists`, `Refresh` or `Accumulate`.

The expanded state replaces the status pills + summary line with category rows. Each row has the category name, an inline count display (`"3/4"` or `"all"` or `"none"`), a master toggle, and a chevron `›` (when there are individual playlists to drill into). The existing per-playlist drill-in is reached by tapping the row but not the toggle (the toggle handles master on/off; the row handles drill-in). The `SyncMode` chip row moves *out* of the expansion and *into* the always-visible status-pills row — there's no point hiding it. Studio-only filter for YouTube also stays in the expansion (one row labeled `"Studio recordings only"` with a switch and the same help text it has today).

Animation: tapping the card toggles `expanded`, content size animates via `animateContentSize()` (already used by today's expanding cards). The brand bar stays visible in both states.

### Schedule card

The redesigned card has three vertical regions:

1. **Auto-sync switch row** — `⟳ Auto-sync` label on the left, `Switch` on the right. When off, the rest of the card dims to 50% alpha (visually parked but still readable for a glance).
2. **Schedule sentence** — single `Text` block with three inline `Chip` composables: `"Sync <DaysChip> at <TimeChip> on <NetworkChip>"`. Each chip is a tappable surface that triggers a different inline action.
3. **Help line** — small grey `"Tap any chip to change"` hint.

Three chip behaviors:

- **Days chip** — label is the rendered string for the current day-bitmask: `"daily"` (all 7), `"weekdays"` (M-F only), `"weekends"` (S-S only), or comma-separated abbreviations (`"Mon · Wed · Fri"`). Tapping expands an inline panel inside the same GlassCard with two regions: a row of 7 day-circles (M T W T F S S) each tappable to toggle, and a row of preset chips (Daily / Weekdays / Weekends / Custom). Tapping outside the panel collapses it.
- **Time chip** — label is `"6:00 AM"`. Tapping opens a Material 3 `ModalBottomSheet` containing a `TimePicker`. The bottom sheet replaces today's `Dialog` — feels more native to the rest of the app and lets the schedule context stay visible behind it.
- **Network chip** — label cycles between `"Wi-Fi only"` and `"Any network"`. Tap toggles the `wifiOnly` boolean directly (no panel — a simple state cycle).

Visual styling: the days and time chips share a "primary tappable" treatment (purple-tinted background, purple border) since they have richer interactions. The network chip uses the muted/secondary chip treatment because it's a binary cycle. This contrast is intentional — it tells the user where the multi-step interactions live.

### Days-of-week storage and scheduling

A new field on `SyncPreferences`:

```kotlin
/**
 * Bitmask of days the auto-sync should run. Bit 0 = Monday … Bit 6 = Sunday.
 * Default 0b1111111 (127) = every day, matching prior single-day behavior.
 * 0 means no day is enabled, equivalent to disabling auto-sync.
 */
val syncDays: Int = 0b1111111,
```

Storage: `intPreferencesKey("sync_days")` in `SyncPreferencesManager.Keys`, default `127`. This is back-compat — installs that never wrote the key resolve to `127` and continue to sync every day. No migration needed.

Public API:

- `val syncDays: Flow<Int>` — narrow worker-side flow.
- `suspend fun setSyncDays(bitmask: Int)` — setter.
- A small `DayOfWeekSet` value class wrapping `Int` exposes ergonomic helpers: `contains(DayOfWeek): Boolean`, `with(DayOfWeek, on: Boolean): DayOfWeekSet`, `isDaily: Boolean`, `isWeekdays: Boolean`, `isWeekends: Boolean`, `presetLabel(): String`, `compactLabel(): String` (e.g. `"Mon · Wed · Fri"`). Lives in `core/data/src/main/kotlin/com/stash/core/data/sync/DayOfWeekSet.kt` so both the worker and the ViewModel can use it.

Scheduler integration: `SyncScheduler` (existing) currently schedules a single `OneTimeWorkRequest` for the next `syncHour:syncMinute`. The new behavior:

- After computing the next firing time, check whether the firing time's `DayOfWeek` is in the bitmask. If yes, schedule normally. If no, advance the firing time by 1 day at a time until a matching day is found, then schedule for that.
- If the bitmask is 0 (no days selected), do not schedule a work request at all. This is functionally equivalent to `autoSyncEnabled = false`, but we don't change `autoSyncEnabled` behind the user's back — UI shows the disabled-state hint instead.
- Manual `Sync Now` is unaffected — it never goes through the scheduler.

This is the only meaningful behavior change in the scheduling pipeline. The scheduling cadence (one shot, re-scheduled by `BootReceiver` on device restart) and the WorkManager constraints (Wi-Fi only, etc.) are unchanged.

### Recent syncs

Today, each `SyncHistoryRow` is rendered as its own `GlassCard` (per the existing `SyncScreen.kt:975` definition). The redesign consolidates them into a single GlassCard with internal dividers — visually quieter, takes less vertical space.

Each row keeps the same content (status dot, timestamp, playlist + track count, optional partial/error annotation) but adopts:

- A leading colored dot (green / amber / red) instead of an icon.
- Tabular timestamp + count text on the body.
- A small trailing status mark (`✓` / `!` / `×`).

Row tappability remains as today (reuses existing handler).

### Library Maintenance

`LibraryMaintenanceCard` (line 554) keeps its functionality but moves to a single tighter row inside a GlassCard:

- Leading icon + title + subtitle (e.g. `"Cleanup — 12 orphaned files"`).
- Trailing pill button labeled `"Run"` instead of today's larger CTA.

If the existing card has multiple actions (cleanup, etc.), each becomes one row. The card no longer has its own large header — the `"LIBRARY"` section label upstream takes that role.

### Removed surfaces

- The two `SourceCard` composables in the top "Connected Sources" Row (search hint: `SyncScreen.kt` ~line 111-136 as of branch `feat/yt-sync-pagination`) and the helper composable `SourceCard` itself (~line 651). Connection state moves into the Source Preferences cards as a `Connected` chip.
- The standalone `SyncActionSection` composable (~line 687) that today renders the "Sync Now" button and progress. Absorbed into the new Hero card.
- `phaseLabel` (~line 785), if it was only used by `SyncActionSection`, moves with the absorbed code (or stays at file scope if used elsewhere).
- The `ScheduleCard` (~line 800) and `TimePickerDialog` (~line 928) are replaced wholesale.

> All line numbers above are search hints; treat them as approximate. The implementation plan should grep the file fresh.

## Component breakdown — new files

The new Sync screen is decomposed into focused composable files instead of a single 1300-line `SyncScreen.kt`. New files:

| File | Responsibility |
|---|---|
| `feature/sync/.../components/SyncHeroCard.kt` | The gradient hero with last-sync stats + Sync Now button + inline progress |
| `feature/sync/.../components/SourcePreferencesCard.kt` | Generic collapsed/expanded card; takes brand color, source name, status pills, category rows. Both Spotify and YouTube use it. |
| `feature/sync/.../components/StatusPill.kt` | Small reusable pill used in source card summary rows |
| `feature/sync/.../components/ScheduleCard.kt` | Replaces today's `ScheduleCard`. Auto-sync switch + sentence-with-chips + days panel + time bottom sheet |
| `feature/sync/.../components/DayOfWeekPanel.kt` | The 7-circle day picker + presets row, used inside ScheduleCard |
| `feature/sync/.../components/SyncTimeBottomSheet.kt` | `ModalBottomSheet` wrapping a Material 3 `TimePicker`. Replaces today's `TimePickerDialog`. |
| `feature/sync/.../components/RecentSyncsCard.kt` | Single-card consolidation of `SyncHistoryRow`s |
| `core/data/src/main/kotlin/com/stash/core/data/sync/DayOfWeekSet.kt` | Value-class wrapper around the day bitmask with ergonomic helpers and label rendering |

`SyncScreen.kt` itself shrinks from ~1300 lines to roughly 200, becoming the orchestrator that pulls each component together and wires them to the ViewModel.

## Data flow

```
SyncPreferencesManager.syncDays (Flow<Int>)
              ↓
SyncViewModel observes → SyncUiState.syncDays: Int
              ↓
ScheduleCard renders chip with DayOfWeekSet(syncDays).presetLabel()
              ↓
User toggles a day-circle or selects a preset
              ↓
SyncViewModel.onSyncDaysChanged(newBitmask: Int)
              ↓
SyncPreferencesManager.setSyncDays(...)
              ↓
SyncScheduler observes (or is triggered after the setter completes) →
re-computes next firing time, honors the bitmask
```

`SyncViewModel` already observes other prefs via `observeSyncMode()` (renamed to `observeSyncPreferences()` for accuracy). The new field plugs into that flow alongside `youtubeSyncMode` and `youtubeLikedStudioOnly` (which just landed on this branch).

## Error handling

- **Empty bitmask (0).** UI shows `Auto-sync` switch as ON but the sentence reads `"Not scheduled — pick at least one day"` with the days chip styled in error red. Scheduler explicitly does nothing. This is treated as user-state, not error-state — they can enable a day at any time. The day-circle toggle logic does NOT veto the last deselection — the user is allowed to reach the zero state and the UI handles it gracefully.
- **Scheduler day-advance.** The `while (firingDay !in bitmask) advance 1 day` loop is bounded by 7 iterations (day cycle); if for some reason no day matches after 7 hops, log a warning and skip scheduling (this implies bitmask is 0, which the UI prevents — defensive only).
- **TimePicker bottom sheet dismissal.** If the user dismisses without confirming, no state change. Standard.
- **DataStore corruption on `syncDays` read.** `runCatching { ... }.getOrDefault(0b1111111)` — same fallback discipline as `youtubeLikedStudioOnly`. Falls back to "every day" on read failure, which is the safe default.

## Test plan

### Unit: `DayOfWeekSet`

A pure value-class with no dependencies. Test in `:core:data` test:

- `contains(DayOfWeek)` returns the right boolean for each of the 7 bits.
- `with(DayOfWeek, on)` sets and unsets correctly without affecting other bits.
- `isDaily`, `isWeekdays`, `isWeekends` true on the canonical bitmasks (127 = `0b1111111`; 31 = `0b0011111` for M-F = bits 0-4; 96 = `0b1100000` for Sat-Sun = bits 5-6) and false otherwise. Bit ordering is fixed: bit 0 = Monday, bit 6 = Sunday — matches `java.time.DayOfWeek.value - 1`.
- `presetLabel()` returns `"daily"`, `"weekdays"`, `"weekends"`, or `compactLabel()` for arbitrary bitmasks.
- `compactLabel()` returns `"Mon · Wed · Fri"` style for 0b0010101.

### Unit: `SyncPreferencesManager.syncDays`

Round-trip storage test using an in-memory or fake DataStore: `setSyncDays(0b0011111)` then read via `syncDays.first()` returns `0b0011111`. Default-on-missing-key returns `127`.

### Unit: `SyncScheduler` day-advance logic

Pure function test on the next-firing-time computation:

- Bitmask `127`, current time Mon 10am, target hour=06: schedules for Tue 6am.
- Bitmask `0b0011111` (M-F), current time Fri 10am, target hour=06: schedules for Mon 6am (skips Sat + Sun).
- Bitmask `0b1000000` (Sun only), current time Mon 10am, target hour=06: schedules for Sun 6am.
- Bitmask `0`: scheduler returns null (nothing scheduled).

### UI: snapshot tests for new components

Snapshot tests with `Paparazzi` (or whatever the project uses — locate during planning) for:

- `SyncHeroCard` — three states: never-synced, synced-healthy, syncing-with-progress.
- `SourcePreferencesCard` — collapsed and expanded variants for both Spotify and YouTube.
- `ScheduleCard` — auto-sync on / off; days chip in resting / expanded panel; the empty-bitmask error state.
- `RecentSyncsCard` — empty / one row / multiple rows including a partial.

If the project doesn't currently have snapshot test infra, defer the UI tests to manual verification. The unit tests on `DayOfWeekSet` and the scheduler still cover the load-bearing logic.

### Manual on-device verification

The implementation plan will include a step-by-step manual verification:

1. Install, observe new layout. Confirm Connected Sources tiles are gone, the Hero card shows last-sync stats, all sections render.
2. Tap Spotify card — expands inline to category rows. Toggle a category master and confirm the collapsed state's status pills update.
3. Tap Schedule's days chip — confirm panel opens, day circles toggle, preset chips work. Verify bitmask round-trip via `adb` + sqlite (read `sync_preferences` DataStore).
4. Tap time chip — bottom sheet opens with TimePicker; pick a time, confirm.
5. Set days to a single day far in the future (e.g. only Saturday), tap Sync Now, verify it still runs (manual sync ignores the schedule).
6. Verify autoSync state: toggle auto-sync off, sentence dims; toggle on, sentence brightens.
7. Verify the empty-bitmask error state: deselect all days, sentence becomes `"Not scheduled — pick at least one day"` in red.
8. Tap "Cleanup" in the Library card — confirm action still works.

## Open questions / risks

- **Snapshot test harness.** The project may or may not have Paparazzi. If absent, UI testing falls back to manual verification, which is acceptable for a single-developer pre-launch app but worth flagging for the implementer.
- **Hero card progress UI.** The current `SyncActionSection` (lines 687-784) renders a multi-phase progress widget. Absorbing it into the Hero card needs care to preserve all the phases and their labels (`phaseLabel(phase: SyncPhase)`). The implementer should treat the existing progress UI as a black box to be embedded, not redesigned in this pass.
- **Scheduler retry/window behavior.** This spec doesn't change WorkManager retry policy, network constraints, or backoff. If the existing scheduler relies on a specific firing-time computation, the day-advance logic plugs in at the right place (after the time is computed, before the WorkRequest is scheduled). Locate the existing computation site during planning and confirm the integration point.
- **Material 3 BottomSheet API stability.** `ModalBottomSheet` is stable in M3, but `TimePicker` inside it sometimes surfaces edge cases (state hoisting through `ModalBottomSheetState`). If the wrapper proves fiddly, falling back to a `Dialog` containing the TimePicker is acceptable — the rest of the redesign doesn't depend on the bottom-sheet specifically.
- **Brand bar visual weight on light theme.** The 3dp brand bar uses raw `spotifyGreen` / `youtubeRed` which were chosen against the dark theme. Verify the contrast still feels right on the lavender light theme; if not, dim the bar by ~20% in light mode.

## Verification

After implementation:

- The Sync tab visibly differs from the current build — Hero card present, Source cards have brand bars + status pills + summary lines, Schedule card uses the chip-sentence pattern, Recent syncs are unified into one card, and the top "Connected Sources" tiles are gone.
- The user can set auto-sync to fire only on weekdays at 6:30 AM on Wi-Fi, persist across app restart, and the WorkManager-scheduled work fires only on weekdays.
- Studio-only filter remains visible and toggleable inside the expanded YouTube source card; its state is reflected in the Liked Songs status pill.
- All existing functionality (Sync Now, review queue navigation, Library Maintenance Cleanup, individual playlist toggles via drill-in) continues to work.
