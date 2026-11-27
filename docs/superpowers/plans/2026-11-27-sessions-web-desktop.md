# Sessions on the web player and the desktop: launch plan

Owner, 2026-11-27: "we need to launch the sessions feature. ideally it'll go live on the web player first, and we can
test it's functionality locally with the debug app and pc build as well." Decisions the same day:

1. **The web listens and hosts.** The server never checks for a phone: a browser tab can host while it stays open.
2. **The desktop gets Sessions alongside the web**, not after.
3. **Layout B**, from the web mockups (https://claude.ai/artifact/429DUz1Qiy9VjxpbHG3yjq; sources in
   `~/.stash/design/sessions-web-2026-11-27/`): the room lives in the right side panel and you keep browsing. In a
   session, song rows say Request instead of play. The full-screen Now Playing becomes the big room (C). The owner
   said "mockups are fine"; B with C as its full-screen view was the recommendation they accepted.
4. **No emoji reactions** ("they look cheap"). A session's reactions are 17 old-school text faces, sent by id
   (`k:shrug` is ¯\_(ツ)_/¯), the last five big ones that play out as they rise. Private Listen Together rooms keep the
   six emoji, because the store app sends them. The list is `FACES` in `infra/share-worker/src/room.js` and
   `RoomProtocol.FACES` in core/model; the web and the desktop keep their own copies.

What exists: the server (stash-share, live dark: `SESSIONS = "0"`) and the Android app (steps 1–7, merged). The web
player and the desktop have no room code at all.

## How the web reaches the server

Everything goes through the player's own Worker, as Community does today (`worker/community.ts`, `worker/share.ts`):

- a plain `fetch` to `SHARE_API_URL` (unset: `https://stashfm.app`);
- `X-Stash-Proxy-Key` and `X-Stash-Client-IP` when `SHARE_PROXY_KEY` is set, so the share Worker's per-address limits
  count each listener, not the player (`src/http.js` `clientAddress`);
- the room socket at `/api/share/rooms/{code}/ws` (with `?r=1`), passed through like `handleLive` in `worker/sync.ts`.
  It is same-origin, so the CSP's `connect-src 'self'` already covers it;
- the Community key in the `hello` frame, and as `X-Stash-Community-Key` on station calls (follow, heard, vote, own
  stations).

No new server rules are needed for the web.

## Steps

Each step is a PR. Web PRs go to `rawnaldclark/stash-player`, desktop PRs to `rawnaldclark/stash-desktop`, and the
server and Android to MP3APK.

**0. Text faces** (MP3APK `feat/sessions-text-reactions`): server `FACES` (sessions take only these), Android tray
(face over its /name; the big ones in their own row) and the floating pills (a big one steps between its frames).
Server tests 527 green.

**1. Web: the proxy.** `worker/sessions.ts`:
- `GET /api/share/live` → `/v1/community/live`
- `GET /api/share/stations/{id}`
- the owner's station calls: create, edit, delete, `follow`, `heard`, `vote`
- `POST /api/share/rooms {public, stationId}`
- the room socket

Plus vite dev's upgrade handler (like `syncSocket` in `vite.config.ts`), and vitest tests beside it.

**2. Web: listening.** `src/lib/sessions/`:
- `faces.ts`: FACES and their frames, the same as Android's.
- `room.ts`: the socket client. Hello v3 with the Community key, a resume token, the ping clock, reconnect with `?r=1`,
  the close codes 4401/4403/4409/4426, and one tab at a time (a Web Lock).
- `follow.ts`: the listener drives the engine through driven mode (`setDriven`, `loadQueue({driven})`, `setTail`, as
  `sync/remote.ts` does), mapping `load`/`prepare`/`play`/`pause`/`seek`/`timeline` onto it. On top of that, a
  **listener lock**, which driven mode lacks: `next`, `seek`, `play`, `startRadio`, `setRate` and `playItems` are
  refused, along with the MediaSession handlers, the layout's keys, the progress bar, Transport and the mini player.
  The phone remote and handoff are kept out while it holds.
- `session.svelte.ts`: the room's state for the UI (crowd, feed, song stats, requests, recap).
- UI:
  - Community gets Sessions (Live now, Upcoming) beside Posts.
  - A station page.
  - Panel gets a Room tab while in a session.
  - NowPlaying's session view (C).
  - `TrackRow.rowClick` and `ContextMenu` turn play into Request in a session, in one place, not at the 17 call sites.
  - The React tray (faces, typed `/shrug`, phrases), Save, Follow, Vote (after 2 minutes heard), Leave, and the recap.

**3. Web: hosting.**
- The Go live dialog (name, vibe, Just now / On a schedule / Always), which creates or edits the station, then
  `POST rooms {public}`.
- `host.ts` drives the room from the engine: `load` with `qv`, `play`/`pause`/`seek`, `queue`, `status`, `lastSong`.
- The booth in the panel: requests (Play next / Add to queue / Pass, Auto-accept), the crowd (co-host, boot), and End
  or End after this song.
- Keeping the tab alive: a wake lock while playing, and a note that closing the tab ends the session. The server ends
  it after 60 s away (`host_left`) or 15 minutes without playing (`paused`).
- Open decks and auto-live by schedule come after the first launch, unless the owner wants them in.

**4. Desktop: listening and hosting.**
- `crates/stashd/src/session.rs`, modelled on `remote.rs`: tungstenite over `away::Conn::tls`, hello v3, follow or
  drive the native `Player`. Songs resolve through the existing sources, as `link::remote::PhoneSong` does.
- `/api/sessions/*` in `serve.rs`, typed in `core.ts`.
- A Community key in stashd (43 base64url characters, its own).
- `STASH_SHARE_URL` (default `https://stashfm.app`) for local tests, as `STASH_SYNC_URL` does.
- The UI ports the web's Svelte components by hand, as the rest of the desktop UI was.

**5. Local tests, before anything goes live.**
- **Share Worker:** `npx wrangler d1 migrations apply stash-community --local`, then
  `npx wrangler dev --ip 127.0.0.1 --port 8811 --var SESSIONS:1`. 8811 clashes with the player's e2e-worker
  stand-in; don't run that suite at the same time. Check the port is free first (Get-NetTCPConnection).
- **Web player:** `SHARE_API_URL=http://127.0.0.1:8811 npm run dev` (vite on 5173).
- **Android debug app:** `share.baseUrl=http://127.0.0.1:8811` in local.properties, then
  `adb -s <phone> reverse tcp:8811 tcp:8811`. Remove both afterwards.
- **Desktop:** `STASH_SHARE_URL=http://127.0.0.1:8811`.
- **Scripted peers:** `scripts/session-peer.mjs host|listen`.
- **Checks:**
  - a phone hosts, and the web and the desktop listen;
  - the web hosts, and a phone and the desktop listen;
  - the desktop hosts;
  - requests, faces, phrases, votes, follow, save, co-host, boot, End after this song, the recap;
  - closing the host's tab ends it for everyone within a minute.

**6. Production. Each line needs the owner's own go, asked separately.**
1. Apply `0009_session_stats.sql` `--remote`.
2. Deploy stash-share from master: the faces and the scoreboard.
3. Set `SHARE_PROXY_KEY` as a secret on stash-share and on stash-player. This also turns web Community on in production.
4. Deploy stash-player.
5. Flip `SESSIONS = "1"`, through the deploy that sets `[vars]`.

The web is then live first: store apps (0.9.125) have no Sessions. The desktop and the Android app ship together in
the next release ([[feedback_pc_and_app_release_together]]).
