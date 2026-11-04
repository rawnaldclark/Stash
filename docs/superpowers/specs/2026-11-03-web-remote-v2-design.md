# Remote v2: play this phone's queue on a linked browser (phase 1, app side)

Date: 2026-11-03. Plan: `~/.stash/release/plan-2026-11-03-remote/PLAN.md` (owner said "go" with every §8 default).
Protocol: stash-player `docs/sync-v1.md` §5.7 "Driving", vectors `src/lib/sync/fixtures/remote-vectors.json`
(SHA-256 `eee899dd…53a7`, pinned in `RemoteVectorsTest`).

## What the user gets

- Now Playing's cast icon is **Play on**. It shows when the phone can Cast **or** the space has a linked browser.
- The sheet lists **This phone** (ticked when it plays here), **Browsers** (every linked browser with a line: "Ready",
  "Playing Pink + White", "Click the Stash tab once first", "Reload the Stash tab to play there", or greyed "Open
  play.stashfm.app to play there") and **Speakers** (the Chromecast list, only with Cast).
- Picking a browser moves the queue there, where the phone was, playing if it played. From then on every play and
  queue action, Now Playing, the mini player, the notification and the lock screen drive the browser.
- While a browser plays: a line under the seek bar ("Playing on Chrome on Windows", or "Starting on…",
  "Reconnecting to Chrome…", "Click the Stash tab once in Chrome"); the mini player shows the device under the title
  (Chromecast too) and a tap opens Play on; the queue sheet says "Playing on …" and dims songs only this phone has;
  the notification line is "On Chrome on Windows"; speed and Streaming quality are hidden; the browser's volume is the
  "Browser volume" slider in Play on, one tap from the line under the seek bar or the mini player (none when the browser
  can't set it: Safari on iOS); no remote card and no handoff card for that browser.
- The phone's own volume keys don't reach the browser on Android 14 (seen on the Pixel 5): the system hands volume keys
  to a remote media session only when the app holds a MediaRouter2 routing session, which the Cast SDK makes for a
  Chromecast and nothing makes for a browser yet. They change the phone's volume instead. Follow-up: a
  MediaRoute2ProviderService, which would also list the browser in the system output picker.

## Owner decisions (PLAN §8, defaults)

| | Decision | Where |
| --- | --- | --- |
| D1 | The phone keeps the queue; the browser holds the current song and the next. | `CastSessionPlayer` (unchanged model) |
| D2 | "This phone" carries the music on; a browser that went away leaves it here **paused**. | `BrowserOutputs.finish`, `StashPlaybackService.exitCast(resume)` |
| D3 | The phone counts plays (the browser sends no History, scrobbles or skips for driven songs). | web W1; phone unchanged |
| D4 | Songs only this phone has are stepped over, dimmed in the queue, one note per session. | `CastFeed.skipsUnsendable`, `BrowserOutputs.noteSkipped`, `QueueBottomSheet` |
| D5 | Picking a browser that plays its own music replaces it with the phone's song. | `BrowserOutput.Kind.PLAYING` |
| D6 | Download mode unchanged (nothing not downloaded is offered). | `PlayerRepositoryImpl.buildMediaItemForTrack` order |

## Shape

```
Now Playing / mini player / notification ──► MediaSession ──► CastSessionPlayer (phone's queue)
                                                                 │ CastFeed (per attach)
                                       speaker: CastMediaServer URL │ browser: the Song (WebSongs)
                                                                 ▼
                                   CastRemote = GoogleCast…   or   WebCastRemote ──► WebRemote.sendTo ──► relay ──► browser
                                                                        ▲                     │
                                                                        └── live[device] ◄────┘ (stash-remote-state)
```

- **One rule for what the web can play** (`core/data/.../weblink/WebSongs.kt`): the handoff, the mirror and Remote v2 all
  ask it. SoundCloud, cloud, All Songs scans, and local files or downloads with no ISRC / Spotify / YouTube id are
  `phoneOnly`. (The handoff used to drop SoundCloud songs silently; it now counts them as left out, as the mirror did.)
- **WebRemote** (the v1 card's socket) gains: `live` (every live browser's state with its `caps`), `linked`, `deviceId`,
  `pin`/`unpin` (the socket stays open in the background while pinned), `sendTo` (a named browser, in order with every
  other command), `release`. A drop while pinned keeps the driven browser's last state marked `lostAt` and parks commands
  (max 16) until it is heard again. A browser still driven by this phone after a service restart is told to let go
  (`release` with `pause: false`, once per browser session). The card leaves out the driven browser. `BrowserLink` is the
  interface `core:media` uses.
- **WebCastRemote** (`core/media/.../cast`): one browser as a `CastRemote`.
  - `load` → `load {item{id,song}, positionMs, play, from}` (the wrapper's content id is the wire id when it fits
    `^[A-Za-z0-9_.:#-]{1,64}$`, else made to); `setNext` → `upNext` (edits within 300 ms go as one); `playNext` → `jump`
    only when the browser's state says it holds that id in `next`, else false and the wrapper loads; play, pause, seek and
    volume are the v1 commands (shown at once); never v1 next or prev.
  - Status: `iid` (an id this phone handed it) is the content id; `phase` failed → idle + error (the queue skips it),
    ended → idle + finished, loading or `pendingPlay` → buffering; the position moves on while playing.
  - A new `sid` (the browser mints one on each reconnect, sync-v1 §5.7) is the same session: the browser is known by its
    `driver`, and what it missed is said again.
  - Ends by itself:
    - the tab says `bye` (a state sent sealed on `pagehide` as it closes or reloads): at once, as closed (paused here,
      "Chrome on Windows closed" with Play). Nothing else in that state is taken (sealed ahead, its position is old);
    - the browser gone without a bye (its socket, or silent 65 s) for 30 s, as "Reconnecting to Chrome…". A driven tab
      tries again after 1 s, then every 2 s; on the device run a 10 s network cut came back at 16.5 s, past the old 15 s;
    - this phone's socket down for 60 s from the drop;
    - the browser's `driver` no longer this phone (someone there picked their own music, or a reload with no bye);
    - the first song unanswered for 10 s.
  - A tab that said bye is "not open" in Play on (it can't be picked), the remote card leaves it out, and WebRemote doesn't
    tell it to let go (the next tab is told if it still names this phone).
- **BrowserOutputs** (`core/media/.../cast`): the browsers Play on lists, `connect` / `disconnect` / `end`, `remote`
  (what the service plays through), `session` (name + link state), `notices`. One output at a time: picking a browser
  disconnects a speaker; a speaker connecting, or Listen Together starting, ends a browser session; Listen Together
  refuses a browser.
- **StashPlaybackService**: follows both outputs, newest pick wins. A browser's feed hands the Song (the item's Room row,
  looked up once per song; else what the item carries); no LAN server, no Wi-Fi/wake locks, no prefetch poll. Ending:
  "This phone" and a never-answered browser resume if the wrapper was playing; anything else is paused. On destroy the
  browser is told to let go (pause); a web session is never resumed.
- **PlayerRepositoryImpl**: while a browser plays, the look-ahead, a quality change's re-resolve and a tap's resolve
  stand down (a tap becomes a `stash-resolve://` placeholder that resolves only if the music comes back). A browser's
  error is a plain skip through the cascade guard (no same-song retry: the browser retried once; three in a row halt). A
  Chromecast's errors keep their old path.

## Not in phase 1

- The browser's Up next shows the held song only (phase 2 sends the next 50).
- Editing the queue from the browser (phase 3).
- The phone's volume keys for a browser (above: a MediaRoute2ProviderService).
- Other languages for the new strings (English only, per the release rules).
