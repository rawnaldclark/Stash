# Sync Tab — "Actually Useful" Design

**Date:** 2026-07-16
**Status:** Approved for planning (decisions locked with user)
**Design language:** Premium Crisp (continues Home + Library redesign)
**Investigation:** 4-agent parallel deep-dive (workflow `wf_c2d3c617-c2f`), every claim traced to `file:line`.

## 0. Framing

This is **not** UI work. The Sync tab's "recent syncs" receipts currently show information that is wrong or misleading, and the source pipeline hides most of the mixes it already fetches. Two independent workstreams make the tab trustworthy and richer. They ship separately.

Two mental-model corrections that shaped the design:
- **`SyncMode` (REFRESH/ACCUMULATE) is not Online/Offline.** Online/Offline is a separate global boolean, `StreamingPreference.enabled`, and it is **never recorded per sync**.
- **Mixes are not filtered out at fetch.** We already fetch Discover Weekly, Release Radar, On Repeat, Daily Mix 1–6, etc. They're invisible because every discovered playlist is created `syncEnabled=false` (opt-in) and hidden until toggled on.

## 1. Confirmed problems (from investigation)

**Receipts (`RecentSyncsCard`):**
1. The trigger label is dead — hardcoded `SyncTrigger.MANUAL` (`PlaylistFetchWorker.kt:124`); `SCHEDULED` is never written, so every receipt says "Manual". The user wants Online/Offline there instead — which requires *persisting* the mode, not relabeling.
2. `+N tracks` binds to `tracks_downloaded` (`SyncScreen.kt:297`), always 0 in Online mode → a sync that **surfaced 1,578 tracks shows "+0 tracks."**
3. A cancelled sync is written as `SyncState.IDLE` (`TrackDownloadWorker.kt:~620`); it renders as a green "+0 tracks" row with "Cancelled" hidden behind the expand chevron. No `CANCELLED` state exists.

**Sources (mix variety):**
4. Spotify `parseHomeFeedForSpotifyMixes` (`SpotifyApiClient.kt:475`) keeps only `Daily Mix N` + 6 named mixes; everything else in the same home response (Your Top Songs, Blend, "Made For You" mood mixes, "This Is") is discarded. `sectionItemsLimit=20` (`:398`) can truncate.
5. YouTube `isAllowedMixPlaylist` (`YTMusicApiClient.kt:988`) accepts only 4 ID prefixes; broader personalized radios/carousels are dropped.
6. Discovered mixes default `syncEnabled=false` (`DiffWorker.kt:264`) and are hidden — the real reason "we only enable daily mixes and some discoveries."

**Blocklist:** verified sound end-to-end; **no change** (user chose "verify only"). Documented for the record in §5.

## 2. Decisions (locked)

- **Receipts → "Label + count truth":** persist Online/Offline per sync (new column + migration), show it instead of the dead trigger label, show *surfaced* count in Online mode and *downloaded* in Offline, and add a real Cancelled marker. NOT in scope: splitting unmatched out of the failed count, the `new_tracks_found` clobber fix (those were the "full accuracy pass" the user did not pick — and neither affects what "Label + count truth" displays, see §4).
- **Mixes → "Widen + surface-on (Online)":** widen both allowlists (reusing the same API calls — no new network code), AND default newly-discovered *algorithmic mixes* to enabled **only when the library is in Online mode**, so nothing auto-downloads. In Offline mode they stay opt-in.
- **Blocklist → no change.**
- **Process → plan first** (this document + the two plans below).

## 3. Online vs Offline — precise definition

The only behavioral branch is in `DiffWorker` (`DiffWorker.kt:93` reads `streamingPreference.current()`, `:438` branches):
- **Online (streaming enabled):** writes track rows + playlist membership, **skips** the `download_queue` insert. Tracks stream on tap. `tracks_downloaded` stays 0; `new_tracks_found` holds the real count.
- **Offline:** enqueues downloads; `tracks_downloaded` is the meaningful number.

This is why the receipt must know the mode to display an honest count.

## 4. Why the count fix is self-consistent without the clobber fix

In Online mode `TrackDownloadWorker` returns early (`:171`) and never runs `updateCounts`, so `new_tracks_found` keeps `DiffWorker`'s genuine count — accurate to display as "surfaced". In Offline mode the receipt shows `tracks_downloaded` (not `new_tracks_found`), so the known `new_tracks_found` clobber never reaches the screen. "Label + count truth" is therefore correct on its own; the clobber fix is a separate, optional truth improvement.

## 5. Blocklist invariant (record only — no code change)

"Blocked stays blocked across a sync" holds via three layers: `DiffWorker.kt:329` skips a blocked snapshot before any row/membership/queue is created; three `DownloadQueueDao` feeder queries `LEFT JOIN track_blocklist ... WHERE canonical_key IS NULL`; `TrackDownloadWorker.kt:312` last-line guard drops a blocked queue row without incrementing failed. Blocked tracks are excluded entirely — never counted in `tracks_failed`. Minor defense-in-depth gaps exist (two secondary feeders + the discovery path rely on the in-code guard alone) but are not live leaks. Left as-is.

## 6. Plans

- `docs/superpowers/plans/2026-07-16-sync-receipts-truth.md` — Workstream A (receipts).
- `docs/superpowers/plans/2026-07-16-sync-mix-variety.md` — Workstream B (mixes).

Each is independently shippable, device-verifiable, and testable. Suggested order: A first (self-contained, extends the existing receipts commit), then B.

## 6b. Known limitation (found in final review, follow-up)

The `CANCELLED` write lives only in `TrackDownloadWorker` (Task 3). In **Offline** mode the download drain is the long phase, so a user-cancel lands there → the row is written `CANCELLED` and the receipt shows the neutral "Cancelled" pill. In **Online** mode `TrackDownloadWorker` early-returns instantly, so a cancel almost always lands in `PlaylistFetchWorker`/`DiffWorker`/`SyncFinalizeWorker`, whose generic `catch (Exception)` swallows the `CancellationException` and writes `FAILED` (the documented repo gotcha — see `feedback_cancellation_in_worker_catches`). So an Online-mode cancel currently renders "Sync failed", not "Cancelled". Not a regression (those phases wrote FAILED before this work too) and not a masquerade after the §6c fix, but the neutral Cancelled marker won't appear for the headline Online scenario.

**Follow-up fix:** add a `catch (ce: CancellationException) { withContext(NonCancellable) { updateStatus(CANCELLED, "Cancelled") }; throw ce }` ahead of the generic catch in `PlaylistFetchWorker`, `DiffWorker`, and `SyncFinalizeWorker` (mirror `TrackDownloadWorker`'s handler; `NonCancellable` so the DB write survives the cancelling coroutine). Small and pattern-established, but touches the delicate worker-cancellation path so it deserves its own change + test.

## 6c. Final-review fix (applied)

`RecentSyncsCard` gated the "Sync failed" pill on `added == 0` — safe when `added` was the download count, but this feature binds `added` to the *surfaced* count in Online mode, which can be >0 on a late failure. That let a failed Online sync render "+N surfaced" in green. Fixed: any `FAILED` row shows the failure pill (commit on branch).

## 7. Out of scope

- Trigger (Manual/Scheduled) plumbing — the label is being replaced by mode, so the dead trigger is simply no longer rendered (column kept, no destructive migration).
- Unmatched-vs-failed split; `new_tracks_found` clobber (deferred, see §4).
- Blocklist hardening (verified sound).
- Decoupling "surface" from "download" as separate per-playlist toggles (larger change; the Online-gated default-on achieves the user's intent without it).
