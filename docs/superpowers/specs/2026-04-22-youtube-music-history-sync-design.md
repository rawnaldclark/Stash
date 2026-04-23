# YouTube Music History Sync — Design Spec

**Date:** 2026-04-22
**Status:** Approved — pending spec review → implementation plan
**Target version:** v0.6.0 (first minor bump after 0.5.x cycle)

## 1. Purpose

Send Stash's local play events to the user's YouTube Music Watch History so that YT Music's recommendation graph (Daily Mix, My Supermix, Discover Mix) learns from what they actually listen to inside Stash. YT Music Recap coverage is a downstream side-effect we don't optimize for — the user explicitly framed Recap as "social media nonsense, an extra," and the actual target is recommender-graph parity.

## 2. Non-goals

- **Recap completeness.** We do not back-fill historical plays on opt-in (see §6). Users who care about full-year Recap can manually play their heavy-rotation tracks on YT Music itself.
- **Read-direction sync.** We do not pull YT Music's web history into Stash. (Out of scope for v1; possible follow-up.)
- **Like / playlist signal sync.** Thumbs-up parity and playlist-add parity are documented as future signals but not shipped here.
- **Un-sending on track deletion.** Once YT has the ping, we can't retract it; deleting a track locally doesn't rewind YT history.

## 3. Requirements

### 3.1 Functional

- **FR1.** When the user plays a track in Stash past the listen threshold (30s / 50%), submit a single history ping to YouTube Music naming the canonical ATV or OMV video for that track.
- **FR2.** Skip submission entirely when the canonical video cannot be resolved (i.e., track is UGC-only or search misses). Prefer a missing Recap entry over polluting YT Music with a UGC reupload signal.
- **FR3.** Gate the feature behind an explicit opt-in toggle in Settings that requires an already-connected YouTube Music account.
- **FR4.** Show the user a pending-count and a health badge so they can tell the difference between "queued offline" and "auth / protocol broken."
- **FR5.** Auto-pause submission when ≥5 **consecutive** non-auth failures occur (the counter resets on any successful ping). Surface via the red health badge; self-heal on next app update.

### 3.2 Non-functional

- **NFR1.** No remote phone-home. The kill-switch is behavior-driven (local state), not a hosted flag.
- **NFR2.** Serial submission at ~750ms±250ms between pings; no parallel bursts.
- **NFR3.** Reuse existing YT auth (SAPISIDHASH via `YouTubeCookieHelper`). No separate cookie flow.
- **NFR4.** Reuse `ListeningEventEntity` + `ListeningRecorder` — no new capture pipeline.
- **NFR5.** Submission runs off the main thread via the existing coroutine scope pattern (parallel to `LastFmScrobbler`).

## 4. Architecture

```
User plays track in Stash
  │
  ▼
PlayerRepository.playerState ────────────▶ ListeningRecorder (existing)
                                              │  threshold (30s / 50%)
                                              ▼
                                    INSERT listening_events(
                                      track_id, started_at,
                                      scrobbled=0,                  ← Last.fm (existing)
                                      yt_scrobbled=0                ← NEW
                                    )
                                              │
                            ┌─────────────────┼─────────────────┐
                            ▼                                   ▼
              LastFmScrobbler (existing)         YouTubeHistoryScrobbler (NEW)
              drains where scrobbled=0           drains where yt_scrobbled=0
                                                   │
                                                   ▼
                                          canonicalResolver.resolve(track)
                                          ├─ musicVideoType == ATV or OMV → use track.youtubeId
                                          ├─ else, InnerTubeClient.searchCanonical(artist, title)
                                          │  on hit, cache to TrackEntity.ytCanonicalVideoId
                                          └─ on miss → skip this event, mark yt_scrobbled=1
                                                       (don't pollute Recap with UGC)
                                                   │
                                                   ▼
                                          InnerTubeClient.getPlaybackTracking(canonicalId)
                                          returns playbackTracking.videostatsPlaybackUrl
                                                   │
                                                   ▼
                                          HTTP POST to that URL, signed with
                                          YouTubeCookieHelper.generateAuthHeader(SAPISID)
                                                   │
                                                   ├─ 2xx → yt_scrobbled=1, green badge
                                                   ├─ 401/403 → red badge "Connection lost"
                                                   └─ other 4xx/5xx → retry w/ exp backoff;
                                                                       ≥5 consecutive →
                                                                       local kill-switch flag set,
                                                                       red badge "Disabled"
```

### 4.1 New components

| Component | Module | Role |
|---|---|---|
| `YouTubeHistoryScrobbler` | `core:data/lastfm/` (or new `core:data/youtube/` — TBD in plan) | Singleton with `scope.launch { combine(tokenManager.youTubeAuthState, ytEnabledPreference.enabled, listeningEventDao.pendingYtScrobbleCount()) ... }`. Drains reactively on any state change. |
| `YouTubeHistoryPreference` | `core:data/prefs/` | Wraps the user's opt-in toggle (DataStore). Default = `false`. First-enable also records a timestamp for audit. |
| `YtCanonicalResolver` | `core:data/youtube/` | Pure class. Given a `TrackEntity`, returns either `track.youtubeId` (if ATV/OMV), or performs a cached InnerTube search, or returns null (skip). Writes the resolved id to `tracks.yt_canonical_video_id`. |
| `InnerTubeClient.getPlaybackTracking(videoId)` | `data:ytmusic/` | New method: POST `/youtubei/v1/player` with videoId, parse **`playbackTracking.videostatsPlaybackUrl`** from the response (NOT `videostatsWatchtimeUrl` — the playback URL is the one `ytmusicapi.add_history_item` + SimpMusic use for history submission; the watchtime URL is a separate periodic-progress ping for in-app playback that we are not simulating). |
| `YouTubeHistoryScrobblerHealth` | in-memory state | `Flow<Health>` — { OK, OFFLINE, AUTH_FAILED, PROTOCOL_BROKEN, DISABLED }. Backs the Settings badge. |
| Settings row + first-enable dialog | `feature:settings/` | Toggle. First tap on `false → true` shows a one-shot confirmation modal with risk copy. |

### 4.2 Schema changes

```sql
-- listening_events
ALTER TABLE listening_events ADD COLUMN yt_scrobbled INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_listening_events_yt_scrobbled ON listening_events(yt_scrobbled);

-- tracks
ALTER TABLE tracks ADD COLUMN yt_canonical_video_id TEXT;
```

Both via a single Room migration (database version +1).

## 5. Detailed flows

### 5.1 First-time opt-in

1. User opens Settings → scrolls to "Downloads" card.
2. Sees a new row: **"Send plays to YouTube Music"**, toggle off. If YT not connected: greyed out with subtext *"Connect YouTube Music first"*, disabled.
3. User taps toggle → instead of flipping, opens a modal:
   > **Send your Stash plays to YouTube Music?**
   >
   > Every track you finish listening to in Stash will be added to your YouTube Music Watch History. Your Daily Mix and other YouTube Music recommendations will learn from your Stash listening.
   >
   > **How it works:** Stash uses an unofficial YouTube endpoint (the same one the YouTube Music app uses). Google tolerates this pattern for personal-use apps but could change the rules without notice. If that happens, Stash will automatically stop sending plays until a new update fixes the integration.
   >
   > **Risk:** rare but not zero chance of YouTube rate-limiting your account. Stash monitors for errors and pauses itself if it sees problems.
   >
   > [ Cancel ]    [ Enable ]
4. User taps "Enable" → toggle flips on, preference persists. Starting now, every new `listening_events` row with `yt_scrobbled=0` gets drained.
5. Toggle off → immediate. Pending queue retained. Re-enabling resumes draining.

### 5.2 Happy-path submission

1. `ListeningRecorder` inserts row `(trackId=123, yt_scrobbled=0)`.
2. `YouTubeHistoryScrobbler`'s combine flow fires (pending count changed).
3. Worker calls `YtCanonicalResolver.resolve(track)`:
   - Track 123's `musicVideoType` = ATV → returns `track.youtubeId` unchanged.
4. Worker calls `InnerTubeClient.getPlaybackTracking(videoId)`, receives `playbackTracking.videostatsPlaybackUrl`.
5. POSTs that URL (empty body), signed with SAPISIDHASH via `YouTubeCookieHelper.generateAuthHeader`.
6. 2xx → `listeningEventDao.markYtScrobbled(eventId)` → health = OK.
7. Delay 750ms±250ms, drain next pending event.

### 5.3 Sad paths

| Scenario | Response |
|---|---|
| Network offline | Transient failure → retry with exp backoff (1s → 2s → 4s → 8s, cap 5). After 5, leave in queue. Health = OFFLINE. |
| Auth cookie expired | 401/403 → no retry. Health = AUTH_FAILED, red badge with "Reconnect". Events stay queued. |
| Protocol break (unparseable `player` response, consistent 400s) | Counted toward consecutive-failure gate. ≥5 → local kill-switch flag, health = PROTOCOL_BROKEN. Cleared on next app update. |
| Track resolves to no canonical (UGC-only, search miss) | Mark `yt_scrobbled=1` (treated as "handled"), skip submission. No retry — there's nothing to retry with. |
| Google returns success but YT Music Recap/mixes don't update | Out of scope — we can't verify the recommender graph absorbed our signal. |

### 5.4 Kill-switch lifecycle

State lives in a single dedicated DataStore (`yt_scrobbler_state`), keys:
- `disabled_reason: String?` — null when healthy; `"protocol_errors"` when tripped.
- `consecutive_failures: Int` — counter, reset to 0 on any successful ping.
- `last_known_version_code: Int` — used for the "clear on app update" check.

Lifecycle:
1. `disabled_reason` starts null.
2. On each ping failure that's NOT 401/403: increment `consecutive_failures`.
3. On each ping success: reset `consecutive_failures` to 0.
4. When `consecutive_failures` >= 5: set `disabled_reason = "protocol_errors"`. Health = PROTOCOL_BROKEN.
5. Scrobbler checks `disabled_reason` at start of each drain pass — if set, no-op.
6. At `YouTubeHistoryScrobbler.start()`: compare current `BuildConfig.VERSION_CODE` with `last_known_version_code`. If current > stored → clear `disabled_reason`, reset counter, update `last_known_version_code`. This is the "fresh install / app update clears the flag" mechanism.
7. User can also manually clear via a "Retry YouTube sync" action on the red health badge.

## 6. Back-catalog policy

**Fresh start — no historical submission.**

Rationale from approval session: (a) user's priority is the recommender graph, not Recap; (b) burst submission of historical plays looks bot-like, which is the highest-risk behavior. Retained as a possible future feature ("Sync recent plays" button) if demand emerges.

## 7. Testing

### 7.1 Unit

- `YtCanonicalResolver` — decides correctly between cached canonical / search / skip based on `musicVideoType`.
- `YouTubeHistoryScrobbler` queue transitions — on-success marks row, on-failure enters backoff, consecutive-failure count trips kill-switch.
- `playbackTracking` URL parser — extracts URL from a fixture `player` JSON with both ATV and OMV response shapes.
- `SAPISIDHASH` header already covered by existing tests around `YouTubeCookieHelper`.

### 7.2 Instrumented / on-device

- Single happy-path loop: opt in, play one track with known ATV id, wait 60s, verify it appears at the top of `https://music.youtube.com/history` via the user's own browser.
- Offline drain: turn WiFi off, play 3 tracks, turn WiFi on, observe queue drain in logs + health badge transitioning OFFLINE → OK.
- Auth failure simulation: test via a stubbed `YouTubeCookieHelper` that returns an invalid hash (scripting cookie rotation by hand is too fragile for a regression check). Verify badge goes red with "Reconnect" action.

### 7.3 Not tested

- Recap / recommender graph absorption — the YT Music algorithm is opaque and timelapsed; we can't assert on it in CI. Manual observation over weeks is the only signal, and this spec doesn't commit to a measurable outcome there.

## 8. Open questions for the implementation plan

- New module `core:data/youtube/` vs putting the new scrobbler next to `LastFmScrobbler` in `core:data/lastfm/` (naming mismatch if colocated; new subpackage if not). Leaning new subpackage; let the plan decide.
- Whether to add a "Retry now" manual drain button parallel to the existing Last.fm "Sync scrobbles now" button. Low cost; defer unless the user asks.
- Notification channel for AUTH_FAILED: current design uses only the in-app badge. If users regularly go weeks without opening Stash, a sticky notification would be kinder. Captured as follow-up if the v1 badge is insufficient.

## 9. Success criteria

- The feature ships behind an opt-in toggle, off by default.
- A user who toggles it on and plays a track in Stash sees that track (its canonical ATV/OMV version) at the top of `music.youtube.com/history` within minutes.
- No reports of Google account rate-limiting attributable to this feature within the first 30 days after release.
- Over the month following enablement, the user reports their YT Music Daily Mix / My Supermix reflect their Stash listening patterns (qualitative, not measurable in CI).
