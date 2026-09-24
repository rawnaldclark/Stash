# Listen Together Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Friends in different places hear the same song at the same moment in Stash. One host controls play, pause, seek, skip and the queue and can hand control to anyone. Up to 10 people per room. Listeners can suggest songs, send emoji reactions and see who's listening. There are no accounts; the only identity is the optional "Show my name as" name.

**Architecture:**
- **Server:** each room is one Cloudflare Durable Object (`ListenRoom`, bound as `ROOMS`) in the existing `stash-share` Worker, reached over WebSockets using the Hibernation API. The room keeps its own clock and is the single source of truth. Its rules live in a pure module, `src/room.js` (state + event → new state + messages), so `node --test` covers them. The DO class is a thin wrapper that adds storage, sockets and one alarm.
- **Wire format:** JSON messages with a `t` type field. Songs travel as the shared-mixes `SharedTrack` descriptor. The Kotlin model is `core/model/listen/RoomProtocol.kt`.
- **App transport** (`core/data/listen`): `RoomApiClient` (REST: create, preview) and `RoomClient` (an OkHttp WebSocket that reconnects with backoff).
- **Engine** (`core/media/listen`): `ListenTogetherSession` lives inside `StashPlaybackService` and drives the service's own ExoPlayer through a small `SessionPlayer` interface. Pure `ClockSync` and `DriftController` keep it in time. `ListenTogetherController` (a Hilt singleton) carries commands in, and state and the `active` flag out. While `active` is set, `PlayerRepositoryImpl` switches off its own automation and persistence. A `ForwardingSimpleBasePlayer` placed in front of the `MediaSession` catches every transport command.
- **UI** (`feature/nowplaying/listen`): the Now Playing entry, the who's-listening bar, the listener lock, reactions, the suggestions tray and the Join screen for `https://…/l/{code}` App Links.

**Tech Stack:**
- **Worker:** JavaScript (ES modules), Durable Objects (SQLite-backed, WebSocket Hibernation), `[[ratelimits]]`, `node --test`.
- **App:** Kotlin 2.3, Media3 1.9.2 (`ForwardingSimpleBasePlayer`, `MediaLibraryService`), OkHttp 4.12 WebSockets, kotlinx-serialization 1.7.3 (sealed classes with a `t` discriminator), Hilt, Jetpack Compose with type-safe Navigation.
- **App tests:** JUnit4, Truth, MockK, Robolectric, kotlinx-coroutines-test, MockWebServer (including `withWebSocketUpgrade`).

**Spec:** `docs/superpowers/specs/2026-09-24-listen-together-design.md`. Read it first. Section numbers below (§n) refer to it.

---

## Ground rules for this repo (read before Task 1)

- **Branch:** cut `feat/listen-together` from `docs/listen-together-spec`, which is master plus the spec and this plan. If you use a worktree, copy `local.properties` into it by hand.
- **Staging:** never use `git add -A` or `git add .`: the repo has large untracked binaries under `spike/`. Stage explicit paths, and check `git diff --cached --name-only` before each commit. Don't use `git rm` to delete things mid-task: it stages the deletion into whatever you commit next.
- **Line endings:** the repo uses CRLF on Windows (`core.autocrlf=true`). Edit files with the editor tools; git normalises line endings. Bash heredocs longer than about 60 lines fail in this environment, so write files with the editor, not `cat <<EOF`.
- **Gradle:** run one Gradle invocation at a time. Two in parallel corrupt KSP caches (`NoSuchFileException … kspCaches`). If that happens, delete the module's `build/kspCaches` and rerun.
  - Unit tests for one module: `./gradlew :core:media:testDebugUnitTest --tests '<pattern>' -q`
  - Whole-app build: `./gradlew :app:assembleDebug -q`
- **`DatabaseBackupMergeTest` is flaky on Windows** (its temp path exceeds the path-length limit). If it's the only failure, rerun it alone. It isn't caused by your change. The `:core:media` unit-test job can also hang in CI; don't read a CI timeout there as a failure of this work.
- **Test names:** Kotlin backtick test names must not contain `;`, `:`, `.`, `/`, `<` or `>` (JVM method-name rules). Write "8 s", not "8.0s", and "host or listener", not "host/listener".
- **Worker tests:** `cd infra/share-worker && npm test`. They use Node's built-in runner and need no install; Node 24 is on this PC. The baseline on master is `ℹ pass 27`.
- **No Room schema change.** The database stays at version 50. The user's own queue already lives in `PlaybackStateStore` (DataStore), and the ISRC lookup is a plain query. If a later task seems to need a migration, stop and ask.
- **Commits:** end every commit message with the trailer
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`
  (the `git commit -m` lines below leave it out for brevity: add it as a second `-m`).

---

## File structure

**New: `infra/share-worker/`**

| File | Responsibility |
|---|---|
| `src/room.js` | Pure room logic: `step(state, event, now)`, `publicState`, `preview`, `nextAlarm` |
| `src/listen-room.js` | `ListenRoom` Durable Object: storage, hibernating sockets, the alarm, the 20 msg/s cap, codes and keys |
| `test/room.test.js`, `test/listen-room.test.js`, `test/rooms.test.js` | Node tests |
| `test/fake-room.js` | Fake DO context, fake sockets, fake `ROOMS` namespace |

**Modified: `infra/share-worker/`**
- `src/index.js`: room routes, `/l/{code}`, `export { ListenRoom }`.
- `src/pages.js`: `roomPage`.
- `src/validate.js`: `cleanTrack`.
- `test/fake-kv.js`: `ROOM_RL` and `ROOMS` in the fake env.
- `wrangler.toml`: DO binding, migration, `ROOM_RL`.
- `README.md`.

**New: `core/model/src/main/kotlin/com/stash/core/model/listen/RoomProtocol.kt`**: the message model and JSON.

**Modified: `core/model/.../share/ShareLinks.kt`**: `Parsed.Room`, `roomUrl`.

**New: `core/data/src/main/kotlin/com/stash/core/data/listen/`**

| File | Responsibility |
|---|---|
| `RoomApiClient.kt` | `POST /v1/rooms`, `GET /v1/rooms/{code}` |
| `RoomClient.kt` | `RoomConnection`, `RoomConnector`, `RoomEvent`, the OkHttp WebSocket client with reconnect and backoff |

**Modified: `core/data`**
- `TrackDao.kt`: `findByIsrc`.
- `MusicRepository.kt`, `MusicRepositoryImpl.kt`: `ensureExactTrackPersisted`.
- `share/ShareApiClient.kt`: its HTTP helper becomes the shared `shareCall`.

**New: `core/media/src/main/kotlin/com/stash/core/media/listen/`**

| File | Responsibility |
|---|---|
| `ClockSync.kt` | Ping samples → room-clock offset (pure) |
| `DriftController.kt` | Position error → none / speed / seek (pure) |
| `ListenTogetherController.kt` | Hilt singleton: `active`, `state`, reactions, messages, commands in |
| `SessionPlayer.kt` | The engine's view of the service: `SessionPlayer`, `SessionInterceptor`, `SessionCatalog` |
| `ListenTogetherPlayer.kt` | The `ForwardingSimpleBasePlayer` the `MediaSession` exposes during a session |
| `DefaultSessionCatalog.kt` | Descriptor ↔ playable `MediaItem` (exact persist), radio |
| `ListenTogetherSession.kt` | The orchestration, for host and listener |

**Modified: `core/media`**
- `PlayerRepositoryImpl.kt`: the session gates, and the paused restore after a session.
- `service/StashPlaybackService.kt`: the `SessionPlayer` implementation, the forwarding player, the crossfade override, keeping the notification in the foreground, idle-stop suppression, the Leave button.
- `res/drawable/ic_listen_leave.xml`.

**New: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/`**
- `ListenTogetherViewModel.kt`, `JoinSessionViewModel.kt`, `JoinSessionScreen.kt`, `ListenTogetherUi.kt`.

**Modified: UI and app**
- `core/ui/.../components/ListenTogetherRole.kt` (new), `TrackOptionsSheet.kt`.
- `feature/search/.../SongRow.kt`.
- `feature/nowplaying/.../NowPlayingScreen.kt`.
- `app/.../MainActivity.kt`, `navigation/TopLevelDestination.kt`, `navigation/StashScaffold.kt`, `navigation/StashNavHost.kt`, `AndroidManifest.xml`.
- `README.md`: the disclosure.

---

## Decisions this plan makes where the spec is silent or can't be followed literally

Each of these is small, and each is marked where it happens.

1. **`prepare` also carries `positionMs`, and `status` may carry `trackKey`.** A phone needs the start position before any `timeline` arrives. The `trackKey` stops a late `ready` for the previous song from counting toward this song's handshake.
2. **The handshake treats `unavailable` as settled.** A phone that can't get the song will never be ready, so waiting for it would always cost the full 8 s.
3. **The room ignores `play`, `pause` and `seek` during the handshake** (at most 8 s). The handshake starts playback anyway. Marked `ponytail:` in `room.js`.
4. **`startAt(roomMs)` lives in the session, not the service player.** Only the session knows the clock offset; the service exposes `play()`.
5. **The queue set-aside reuses `PlaybackStateStore`.** It already holds the user's queue (saved on every change) and position (up to 5 s stale). The session writes the exact position once, and `PlayerRepositoryImpl` stops saving while `active` is set. After a mid-session kill, the existing cold-start ghost restores the user's queue unchanged. When a session ends, the repository restores it paused on `sessionEnds`. That event fires even if the session failed faster than a `StateFlow` collector could see `true`. There is no new storage and no schema change.
6. **The interception is Media3's `ForwardingSimpleBasePlayer`, not the older `ForwardingPlayer`.** It can rewrite the available commands. That is what hides a listener's buttons and lets a host's skip reach the room even though the session player holds one song.
7. **"Suggest" is "Play next" relabelled.** Every track menu's Play next already turns into `addMediaItems` on the session player, and that player turns it into a suggestion (listener) or a queue entry (host). One `CompositionLocal` relabels the menus. Through a one-song player Media3 can't tell Play next from Add to queue, so in a session "Add to queue" and "Start radio" are hidden, and the host's pick is appended to the room's queue.
8. **A host who taps Leave hands the room over at once** (`makeHost` to the longest-joined listener), rather than leaving it hostless for 60 s. The 60 s handover still covers a host whose app dies.
9. **The "Listening together" notification is the media notification**, kept in the foreground through pauses (`onUpdateNotification(…, true)`), with "Listening together ·" in its text and a Leave button. A second foreground notification would fight Media3 for the service's single foreground slot.
10. **A full room still lets a member back in.** `GET …/ws` returns 409 when full unless the phone says it has a resume token (`?r=1`); the `hello` then checks the token.
11. **A descriptor this phone made from its own row plays that row.** Without that, a host's local-only file, which has no ids, would go through the exact persist and come back as a stream of some other upload.

---

# Part A: The Worker (`infra/share-worker`)

The room is pure logic in `src/room.js` (state + event → new state + messages), wrapped by a thin Durable Object in `src/listen-room.js`. Build the pure part first, one rule at a time.

### Task 1: The pure room — create, join, host key, resume, cap, ping

**Files:**
- Modify: `infra/share-worker/src/validate.js` (add `cleanTrack`)
- Create: `infra/share-worker/src/room.js`, `infra/share-worker/test/room.test.js`

- [ ] **Step 1: Write the failing test.** Create `test/room.test.js`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { step, preview, EMOJI, MAX_MEMBERS } from "../src/room.js";

const T0 = 1_000_000;
const TRACK = { t: "Avril 14th", a: "Aphex Twin", d: 125000, isrc: "GBBPW0100025" };
const NEXT = { t: "Xtal", a: "Aphex Twin" };

const created = () => step(null, { type: "create", code: "ABCDEF", hostName: "Rawn", keyHash: "h" }, T0).state;
/** A hello from a new socket. `id` becomes the new member's id and `tok-<id>` its token. */
const hello = (s, id, { name = id, hostKeyOk = false, resumeToken, at = T0 } = {}) =>
    step(s, { type: "hello", msg: { t: "hello", name, resumeToken }, hostKeyOk, newId: id, newToken: `tok-${id}` }, at);
const send = (s, from, msg, at = T0, extra = {}) => step(s, { type: "msg", from, msg, ...extra }, at);
const sent = (r, t) => r.out.filter((o) => o.msg.t === t);
/** A room with host "h" and listeners "a", "b" (joined in that order), everyone connected. */
function party() {
    let s = created();
    s = hello(s, "h", { hostKeyOk: true }).state;
    s = hello(s, "a", { at: T0 + 1 }).state;
    return hello(s, "b", { at: T0 + 2 }).state;
}

// ── Task 1: create, join, host key, resume, cap, ping ─────────────────────────

test("a new room is empty and closes 5 minutes later unless someone joins", () => {
    const r = step(null, { type: "create", code: "ABCDEF", hostName: "Rawn", keyHash: "h" }, T0);
    assert.equal(r.state.host, null);
    assert.equal(r.alarmAt, T0 + 5 * 60_000);
    assert.equal(step(r.state, { type: "create", code: "ABCDEF", keyHash: "x" }, T0).reject.code, 409);
});

test("the first hello with the host key becomes host and gets a private welcome", () => {
    const r = hello(created(), "h", { hostKeyOk: true });
    assert.equal(r.bind, "h");
    const [welcome] = sent(r, "welcome");
    assert.equal(welcome.to, "h");
    assert.equal(welcome.msg.memberId, "h");
    assert.equal(welcome.msg.token, "tok-h");
    assert.equal(welcome.msg.state.host, "h");
    const json = JSON.stringify(welcome.msg.state);
    assert.ok(!json.includes("tok-h") && !json.includes("keyHash"), "state never carries tokens or the key hash");
    assert.equal(sent(r, "members")[0].to, "all");
});

test("without the key you are a listener, and a later key can't take a claimed room", () => {
    let s = hello(created(), "a").state;
    assert.equal(s.host, null);
    s = hello(s, "h", { hostKeyOk: true }).state;
    assert.equal(s.host, "h");
    s = hello(s, "x", { hostKeyOk: true }).state;
    assert.equal(s.host, "h");
});

test("the room holds 10 members and turns the 11th away", () => {
    let s = created();
    for (let i = 0; i < MAX_MEMBERS; i++) s = hello(s, `m${i}`).state;
    const r = hello(s, "late");
    assert.deepEqual(r.reject, { code: 4409, reason: "full" });
    assert.equal(r.state, s);
    assert.equal(preview(s).full, true);
});

test("a resume token rejoins the same slot; someone else's memberId does not", () => {
    let s = party();
    s = step(s, { type: "close", from: "a" }, T0 + 10).state;
    const back = hello(s, "a2", { resumeToken: "tok-a", at: T0 + 20 });
    assert.equal(back.bind, "a");
    assert.equal(back.state.members.length, 3);
    const thief = hello(s, "t", { resumeToken: "a", at: T0 + 20 });
    assert.equal(thief.bind, "t", "a bare memberId is just a new member");
});

test("ping gets a pong with the room clock, to the sender only, and changes nothing", () => {
    const s = party();
    const r = send(s, "a", { t: "ping", c: 42 }, T0 + 500);
    assert.deepEqual(r.out, [{ to: "a", msg: { t: "pong", c: 42, r: T0 + 500 } }]);
    assert.equal(r.state, s);
});

test("strangers and listeners can't use host commands", () => {
    const s = party();
    assert.equal(send(s, "nobody", { t: "play" }).state, s);
    assert.equal(send(s, "a", { t: "load", track: TRACK, positionMs: 0, queue: [] }).state, s);
    assert.equal(send(s, "a", { t: "constructor" }).state, s);
});

test("a closed socket leaves the member list; the last one out starts the 5 minute close", () => {
    let s = party();
    let r = step(s, { type: "close", from: "a" }, T0 + 10);
    assert.deepEqual(sent(r, "members")[0].msg.members.map((m) => m.id), ["h", "b"]);
    s = step(r.state, { type: "close", from: "b" }, T0 + 20).state;
    r = step(s, { type: "close", from: "h" }, T0 + 30);
    assert.equal(r.state.lastLeftAt, T0 + 30);
    assert.equal(r.alarmAt, T0 + 10 + 60_000, "the earliest timer is a's 60 s grace");
});
```

- [ ] **Step 2: Run it and check it fails**

Run: `cd infra/share-worker && npm test`
Expected: FAIL, `Cannot find module '../src/room.js'`.

- [ ] **Step 3: Add `cleanTrack` to the end of `src/validate.js`.** It reuses the file's `str`, `optStr` and `pick` helpers and applies the same per-track limits as `validateDoc`:

```js
/** One song descriptor (the same fields and limits as a mix track), cleaned; null when invalid. Used by Listen Together. */
export function cleanTrack(t) {
    if (!t || typeof t !== "object" || !str(t.t, 1, 500) || !str(t.a, 1, 500)) return null;
    if (!optStr(t.al, 500) || !optStr(t.isrc, 20) || !optStr(t.sp, 40) || !optStr(t.yt, 20)) return null;
    if (t.d !== undefined && t.d !== null && !(Number.isInteger(t.d) && t.d > 0)) return null;
    return pick(t, ["t", "a", "al", "d", "isrc", "sp", "yt"]);
}
```

- [ ] **Step 4: Create `src/room.js`.** The whole framework: state shape, `publicState`, `preview`, `nextAlarm`, `step`, and the three non-message events (`hello`, `close`, `alarm`). The message table starts empty; Tasks 2–5 fill it. `alarm` is written here because `close` and the prepare deadline share it; Tasks 2 and 5 pin its behaviour with tests.

```js
/**
 * Listen Together room logic (spec docs/superpowers/specs/2026-09-24-listen-together-design.md §2–§4).
 *
 * Pure: step(state, event, now) → { state, out, alarmAt, bind?, reject?, closed? }.
 * No I/O, clock or randomness: the ListenRoom Durable Object supplies `now`, fresh ids and the
 * host-key check, so `node --test` covers every rule without the Workers runtime.
 *
 * Events:
 *   { type: "create", code, hostName, keyHash }
 *   { type: "hello", msg, hostKeyOk, newId, newToken }  a socket's first message
 *   { type: "msg", from, msg, newId? }                  any later message from member `from`
 *   { type: "close", from }                             member `from`'s socket closed
 *   { type: "alarm" }
 * out: [{ to: memberId | "all", msg }]. An unchanged room comes back as the SAME state object.
 */
import { cleanTrack } from "./validate.js";

export const MAX_MEMBERS = 10;
export const PREPARE_MS = 8_000;
export const START_LEAD_MS = 500;
export const COMMAND_LEAD_MS = 400;
export const HANDOVER_MS = 60_000;
export const EMPTY_CLOSE_MS = 5 * 60_000;
export const MAX_AGE_MS = 12 * 60 * 60_000;
export const MAX_PENDING_SUGGESTIONS = 3;
export const MAX_QUEUE = 200;
export const REACT_GAP_MS = 1_000;
/** The six reactions. Keep in sync with RoomProtocol.EMOJI in core/model. */
export const EMOJI = ["\u2764\uFE0F", "\u{1F525}", "\u{1F602}", "\u{1F62E}", "\u{1F44F}", "\u{1F3B6}"];

const HOST_ONLY = new Set(["load", "play", "pause", "seek", "queue", "suggestion", "makeHost", "end"]);
/** Phone status → stored member status (`ready` is stored as `ok`, spec §3). */
const STATUSES = { ready: "ok", buffering: "buffering", unavailable: "unavailable", drifting: "drifting" };

const cleanName = (n) => (typeof n === "string" && n.trim() ? n.trim().slice(0, 40) : null);
const nonNegInt = (v) => (Number.isInteger(v) && v >= 0 ? v : null);
const connected = (s) => s.members.filter((m) => m.leftAt === null);
const findConnected = (s, id) => s.members.find((m) => m.id === id && m.leftAt === null);
const cleanQueue = (q) => (Array.isArray(q) ? q.map(cleanTrack).filter(Boolean).slice(0, MAX_QUEUE) : []);

/** Where the song is at room time `now`. A start time still in the future counts as "not moved yet". */
export function positionAt(tl, now) {
    return tl.playing ? tl.positionMs + Math.max(0, now - tl.atRoomMs) : tl.positionMs;
}

export function createRoom({ code, hostName, keyHash }, now) {
    return {
        code, keyHash, createdAt: now, hostName: cleanName(hostName),
        rev: 0, host: null, track: null, trackKey: 0,
        timeline: { positionMs: 0, atRoomMs: now, playing: false },
        queue: [], members: [], suggestions: [], phase: { kind: "playing" },
        reactAt: {}, lastLeftAt: now,
    };
}

const publicMembers = (s) => connected(s).map(({ id, name, joinedAt, status }) => ({ id, name, joinedAt, status }));

/** What phones see: never the key hash, tokens, or members who have dropped. */
export function publicState(s) {
    return {
        rev: s.rev, host: s.host, track: s.track, trackKey: s.trackKey, timeline: s.timeline, queue: s.queue,
        members: publicMembers(s), suggestions: s.suggestions,
        phase: s.phase.kind === "preparing" ? { ...s.phase } : { kind: "playing" },
    };
}

/** The Join screen's view (GET /v1/rooms/{code}). */
export function preview(s) {
    const host = s.members.find((m) => m.id === s.host);
    return {
        hostName: host?.name ?? s.hostName,
        memberCount: connected(s).length,
        full: s.members.length >= MAX_MEMBERS,
        ...(s.track ? { track: s.track } : {}),
    };
}

/** The earliest pending timer: room expiry, empty-room close, a 60 s handover/prune, the prepare deadline (§2). */
export function nextAlarm(s) {
    const times = [s.createdAt + MAX_AGE_MS];
    if (connected(s).length === 0) times.push(s.lastLeftAt + EMPTY_CLOSE_MS);
    for (const m of s.members) if (m.leftAt !== null) times.push(m.leftAt + HANDOVER_MS);
    if (s.phase.kind === "preparing") times.push(s.phase.deadlineMs);
    return Math.min(...times);
}

const unchanged = (state, out = []) => ({ state, out, alarmAt: nextAlarm(state) });
const membersMsg = (s) => ({ to: "all", msg: { t: "members", members: publicMembers(s) } });
const suggestionsMsg = (s) => ({ to: "all", msg: { t: "suggestions", suggestions: s.suggestions } });
const stateMsg = (s, to = "all") => ({ to, msg: { t: "state", state: publicState(s) } });
/** Every connected phone has either buffered the song or can't get it, so nobody is worth waiting for. */
const allSettled = (s) => connected(s).every((m) => m.status === "ok" || m.status === "unavailable");

function setTimeline(ctx, timeline) {
    ctx.s.timeline = timeline;
    ctx.s.rev++;
    ctx.out.push({ to: "all", msg: { t: "timeline", rev: ctx.s.rev, trackKey: ctx.s.trackKey, ...timeline } });
}

function startPlayback(ctx) {
    ctx.s.phase = { kind: "playing" };
    setTimeline(ctx, { positionMs: ctx.s.timeline.positionMs, atRoomMs: ctx.now + START_LEAD_MS, playing: true });
}

export function step(state, event, now) {
    if (event.type === "create") {
        if (state) return { ...unchanged(state), reject: { code: 409, reason: "exists" } };
        const created = createRoom(event, now);
        return { state: created, out: [], alarmAt: nextAlarm(created) };
    }
    if (!state) return { state: null, out: [], alarmAt: null, reject: { code: 4404, reason: "closed" } };
    const msg = event.msg;
    if (event.type === "msg") {
        if (!findConnected(state, event.from) || !msg || typeof msg.t !== "string") return unchanged(state);
        if (HOST_ONLY.has(msg.t) && state.host !== event.from) return unchanged(state);
        if (msg.t === "ping") {
            return Number.isFinite(msg.c) ? unchanged(state, [{ to: event.from, msg: { t: "pong", c: msg.c, r: now } }]) : unchanged(state);
        }
    }
    const table = event.type === "msg" ? MESSAGES : EVENTS;
    const name = event.type === "msg" ? msg.t : event.type;
    if (!Object.hasOwn(table, name)) return unchanged(state); // never "constructor" & co.
    const handler = table[name];
    const ctx = { s: structuredClone(state), out: [], now, event, msg: msg ?? {} };
    const changed = handler(ctx);
    if (ctx.closed) return { state: null, out: ctx.out, alarmAt: null, closed: true };
    const next = changed ? ctx.s : state;
    return { state: next, out: ctx.out, alarmAt: nextAlarm(next), bind: ctx.bind, reject: ctx.reject };
}

const EVENTS = {
    hello(ctx) {
        const { s, now, event, msg } = ctx;
        const name = cleanName(msg.name);
        // Only the private token rejoins a slot: every member can see every memberId (spec §3).
        let m = typeof msg.resumeToken === "string" ? s.members.find((x) => x.token === msg.resumeToken) : undefined;
        if (!m) {
            if (s.members.length >= MAX_MEMBERS) { ctx.reject = { code: 4409, reason: "full" }; return false; }
            m = { id: event.newId, token: event.newToken, name, joinedAt: now, status: "buffering", leftAt: null };
            s.members.push(m);
        } else {
            m.leftAt = null;
            if (name) m.name = name;
        }
        // The host key proves only the original host, and only while nobody holds the room (spec §2).
        if (event.hostKeyOk && s.host === null) s.host = m.id;
        s.rev++;
        ctx.bind = m.id;
        ctx.out.push({ to: m.id, msg: { t: "welcome", memberId: m.id, token: m.token, state: publicState(s) } });
        ctx.out.push(membersMsg(s));
        return true;
    },

    close(ctx) {
        const { s, now, event } = ctx;
        const m = findConnected(s, event.from);
        if (!m) return false;
        m.leftAt = now;
        if (connected(s).length === 0) s.lastLeftAt = now;
        s.rev++;
        ctx.out.push(membersMsg(s));
        if (s.phase.kind === "preparing" && allSettled(s)) startPlayback(ctx);
        return true;
    },

    alarm(ctx) {
        const { s, now } = ctx;
        const empty = connected(s).length === 0;
        if (now >= s.createdAt + MAX_AGE_MS || (empty && now >= s.lastLeftAt + EMPTY_CLOSE_MS)) {
            ctx.out.push({ to: "all", msg: { t: "ended", reason: "closed" } });
            ctx.closed = true;
            return true;
        }
        let changed = false;
        const expired = s.members.filter((m) => m.leftAt !== null && now >= m.leftAt + HANDOVER_MS);
        if (expired.length) {
            s.members = s.members.filter((m) => !expired.includes(m));
            s.rev++;
            if (expired.some((m) => m.id === s.host)) {
                // Host gone for 60 s: the longest-joined connected member takes over (spec §2).
                const heir = connected(s).sort((a, b) => a.joinedAt - b.joinedAt)[0];
                s.host = heir ? heir.id : null;
                ctx.out.push(stateMsg(s));
            } else {
                ctx.out.push(membersMsg(s));
            }
            changed = true;
        }
        if (s.phase.kind === "preparing" && now >= s.phase.deadlineMs) {
            startPlayback(ctx);
            changed = true;
        }
        return changed;
    },
};

const MESSAGES = {
    // Tasks 2–5 add the host and member messages here.
};
```

- [ ] **Step 5: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ pass 35`, `ℹ fail 0` (27 existing + 8 new).

- [ ] **Step 6: Commit**

```bash
git add infra/share-worker/src/validate.js infra/share-worker/src/room.js infra/share-worker/test/room.test.js
git commit -m "feat(listen): pure Listen Together room — join, host key, resume token, 10-member cap, ping"
```

### Task 2: The ready handshake (`load`, `status`, the prepare deadline)

**Files:**
- Modify: `infra/share-worker/src/room.js`, `infra/share-worker/test/room.test.js`

- [ ] **Step 1: Append the failing tests** to `test/room.test.js`:

```js
// ── Task 2: the ready handshake ───────────────────────────────────────────────

const loaded = (s = party(), at = T0 + 100) => send(s, "h", { t: "load", track: TRACK, positionMs: 30_000, queue: [NEXT] }, at);
const ready = (s, from, at = T0 + 200, trackKey = 1) => send(s, from, { t: "status", status: "ready", trackKey }, at);

test("load broadcasts prepare with an 8 s deadline, marks everyone buffering and sets the alarm", () => {
    const r = loaded();
    const [prep] = sent(r, "prepare");
    assert.equal(prep.to, "all");
    assert.deepEqual(prep.msg, { t: "prepare", trackKey: 1, track: TRACK, positionMs: 30_000, deadlineMs: T0 + 100 + 8_000 });
    assert.ok(r.state.members.every((m) => m.status === "buffering"));
    assert.deepEqual(r.state.queue, [NEXT]);
    assert.equal(r.alarmAt, T0 + 100 + 8_000);
});

test("when every connected phone is ready, playback starts 500 ms later for everyone", () => {
    let s = loaded().state;
    s = ready(s, "h").state;
    s = ready(s, "a").state;
    const r = ready(s, "b", T0 + 300);
    const [tl] = sent(r, "timeline");
    assert.deepEqual({ ...tl.msg, rev: 0 }, { t: "timeline", rev: 0, trackKey: 1, positionMs: 30_000, atRoomMs: T0 + 800, playing: true });
    assert.equal(r.state.phase.kind, "playing");
    assert.equal(r.state.members.find((m) => m.id === "b").status, "ok");
});

test("a ready for an earlier song doesn't count, and an unavailable phone isn't waited for", () => {
    let s = loaded().state;
    s = ready(s, "h").state;
    s = ready(s, "a").state;
    assert.equal(ready(s, "b", T0 + 300, 0).state, s, "stale trackKey ignored");
    const r = send(s, "b", { t: "status", status: "unavailable", trackKey: 1 }, T0 + 300);
    assert.equal(sent(r, "timeline").length, 1);
});

test("the deadline alarm starts playback without the slow phone", () => {
    const s = ready(loaded().state, "h").state;
    const early = step(s, { type: "alarm" }, T0 + 5_000);
    assert.equal(early.state, s);
    const r = step(s, { type: "alarm" }, T0 + 100 + 8_000);
    const [tl] = sent(r, "timeline");
    assert.equal(tl.msg.atRoomMs, T0 + 8_100 + 500);
    assert.equal(tl.msg.playing, true);
});
```

- [ ] **Step 2: Run them and check they fail**

Run: `cd infra/share-worker && npm test`
Expected: FAIL. `sent(r, "prepare")` is empty because `load` isn't handled yet.

- [ ] **Step 3: Add `load` and `status` inside `const MESSAGES = { … }`** in `src/room.js`:

```js
    load(ctx) {
        const { s, now, msg } = ctx;
        const track = cleanTrack(msg.track);
        if (!track) return false;
        s.track = track;
        s.trackKey++;
        s.queue = cleanQueue(msg.queue);
        s.timeline = { positionMs: nonNegInt(msg.positionMs) ?? 0, atRoomMs: now, playing: false };
        s.phase = { kind: "preparing", trackKey: s.trackKey, deadlineMs: now + PREPARE_MS };
        for (const m of connected(s)) m.status = "buffering";
        s.rev++;
        ctx.out.push({ to: "all", msg: {
            t: "prepare", trackKey: s.trackKey, track, positionMs: s.timeline.positionMs, deadlineMs: s.phase.deadlineMs,
        } });
        ctx.out.push(membersMsg(s));
        return true;
    },

    status(ctx) {
        const { s, msg, event } = ctx;
        const value = STATUSES[msg.status];
        if (!value) return false;
        // A report for an earlier song must not count toward this song's handshake.
        if (msg.trackKey !== undefined && msg.trackKey !== s.trackKey) return false;
        findConnected(s, event.from).status = value;
        s.rev++;
        ctx.out.push(membersMsg(s));
        if (s.phase.kind === "preparing" && allSettled(s)) startPlayback(ctx);
        return true;
    },
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ pass 39`, `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/room.js infra/share-worker/test/room.test.js
git commit -m "feat(listen): ready handshake — prepare, ready reports, 8 s deadline, start 500 ms ahead"
```

### Task 3: Play, pause and seek timing

**Files:**
- Modify: `infra/share-worker/src/room.js`, `infra/share-worker/test/room.test.js`

- [ ] **Step 1: Append the failing tests:**

```js
// ── Task 3: play, pause, seek ─────────────────────────────────────────────────

/** Loaded, everyone ready at T0 + 300: playing from 30 s in since T0 + 800. */
function playing() {
    let s = loaded().state;
    for (const id of ["h", "a", "b"]) s = ready(s, id, T0 + 300).state;
    return s;
}

test("pause freezes the position the song has reached", () => {
    const r = send(playing(), "h", { t: "pause" }, T0 + 10_800);
    assert.deepEqual(r.state.timeline, { positionMs: 40_000, atRoomMs: T0 + 10_800, playing: false });
    assert.equal(sent(r, "timeline")[0].msg.playing, false);
});

test("play and a seek while playing start 400 ms ahead so nobody arrives late", () => {
    const s = send(playing(), "h", { t: "pause" }, T0 + 10_800).state;
    let r = send(s, "h", { t: "play" }, T0 + 20_000);
    assert.deepEqual(r.state.timeline, { positionMs: 40_000, atRoomMs: T0 + 20_400, playing: true });
    r = send(r.state, "h", { t: "seek", positionMs: 90_000 }, T0 + 30_000);
    assert.deepEqual(r.state.timeline, { positionMs: 90_000, atRoomMs: T0 + 30_400, playing: true });
});

test("a seek while paused only moves the position; transport during the handshake is ignored", () => {
    const paused = send(playing(), "h", { t: "pause" }, T0 + 10_800).state;
    const r = send(paused, "h", { t: "seek", positionMs: 5_000 }, T0 + 11_000);
    assert.deepEqual(r.state.timeline, { positionMs: 5_000, atRoomMs: T0 + 11_000, playing: false });
    const preparing = loaded().state;
    assert.equal(send(preparing, "h", { t: "play" }).state, preparing);
    assert.equal(send(preparing, "h", { t: "seek", positionMs: 1 }).state, preparing);
    assert.equal(send(playing(), "h", { t: "seek", positionMs: -5 }).out.length, 0);
});
```

- [ ] **Step 2: Run them and check they fail**

Run: `cd infra/share-worker && npm test`
Expected: FAIL. `pause` leaves the timeline unchanged.

- [ ] **Step 3: Add `play`, `pause` and `seek` to `MESSAGES`:**

```js
    // ponytail: play/pause/seek during the ≤8 s ready handshake are dropped; the handshake starts playback anyway.
    play(ctx) {
        const { s, now } = ctx;
        if (s.phase.kind !== "playing" || s.timeline.playing || !s.track) return false;
        setTimeline(ctx, { positionMs: s.timeline.positionMs, atRoomMs: now + COMMAND_LEAD_MS, playing: true });
        return true;
    },

    pause(ctx) {
        const { s, now } = ctx;
        if (s.phase.kind !== "playing" || !s.timeline.playing) return false;
        setTimeline(ctx, { positionMs: positionAt(s.timeline, now), atRoomMs: now, playing: false });
        return true;
    },

    seek(ctx) {
        const { s, now, msg } = ctx;
        const positionMs = nonNegInt(msg.positionMs);
        if (s.phase.kind !== "playing" || positionMs === null || !s.track) return false;
        setTimeline(ctx, s.timeline.playing
            ? { positionMs, atRoomMs: now + COMMAND_LEAD_MS, playing: true }
            : { positionMs, atRoomMs: now, playing: false });
        return true;
    },
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ pass 42`, `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/room.js infra/share-worker/test/room.test.js
git commit -m "feat(listen): room play/pause/seek — 400 ms command lead, pause freezes the position"
```

### Task 4: The queue, suggestions and reactions

**Files:**
- Modify: `infra/share-worker/src/room.js`, `infra/share-worker/test/room.test.js`

- [ ] **Step 1: Append the failing tests:**

```js
// ── Task 4: queue, suggestions, reactions ─────────────────────────────────────

test("the host replaces the queue; bad songs are dropped and it is capped at 200", () => {
    const long = Array.from({ length: 250 }, (_, i) => ({ t: `T${i}`, a: "A" }));
    const r = send(party(), "h", { t: "queue", queue: [{ t: "", a: "x" }, ...long] });
    assert.equal(r.state.queue.length, 200);
    assert.equal(r.state.queue[0].t, "T0");
});

test("a listener may have 3 suggestions waiting; the host's own are ignored", () => {
    let s = party();
    for (let i = 0; i < 4; i++) s = send(s, "a", { t: "suggest", track: { t: `S${i}`, a: "A" } }, T0, { newId: `s${i}` }).state;
    assert.deepEqual(s.suggestions.map((x) => x.id), ["s0", "s1", "s2"]);
    assert.equal(send(s, "h", { t: "suggest", track: NEXT }, T0, { newId: "s9" }).state, s);
});

test("adding a suggestion queues it and tells the host; dismissing just removes it", () => {
    let s = send(party(), "a", { t: "suggest", track: NEXT }, T0, { newId: "s1" }).state;
    s = send(s, "b", { t: "suggest", track: TRACK }, T0, { newId: "s2" }).state;
    const r = send(s, "h", { t: "suggestion", id: "s1", action: "add" });
    assert.deepEqual(r.state.queue, [NEXT]);
    assert.deepEqual(sent(r, "suggestions")[0].msg.suggestions.map((x) => x.id), ["s2"]);
    assert.equal(sent(r, "state")[0].to, "h");
    const d = send(r.state, "h", { t: "suggestion", id: "s2", action: "dismiss" });
    assert.deepEqual(d.state.queue, [NEXT]);
    assert.equal(d.state.suggestions.length, 0);
});

test("reactions: one of the six, at most one per second per member", () => {
    const s = party();
    const r = send(s, "a", { t: "react", emoji: EMOJI[1] }, T0);
    assert.deepEqual(sent(r, "reaction")[0], { to: "all", msg: { t: "reaction", from: "a", emoji: EMOJI[1] } });
    assert.equal(send(r.state, "a", { t: "react", emoji: EMOJI[0] }, T0 + 999).out.length, 0);
    assert.equal(send(r.state, "a", { t: "react", emoji: EMOJI[0] }, T0 + 1_000).out.length, 1);
    assert.equal(send(s, "a", { t: "react", emoji: "x" }).out.length, 0);
});
```

- [ ] **Step 2: Run them and check they fail**

Run: `cd infra/share-worker && npm test`
Expected: FAIL. The queue stays empty.

- [ ] **Step 3: Add `queue`, `suggest`, `suggestion` and `react` to `MESSAGES`:**

```js
    queue(ctx) {
        ctx.s.queue = cleanQueue(ctx.msg.queue);
        ctx.s.rev++;
        return true;
    },

    suggest(ctx) {
        const { s, msg, event } = ctx;
        const track = cleanTrack(msg.track);
        if (!track || !event.newId || s.host === event.from) return false;
        if (s.suggestions.filter((x) => x.from === event.from).length >= MAX_PENDING_SUGGESTIONS) return false;
        s.suggestions.push({ id: event.newId, from: event.from, track });
        s.rev++;
        ctx.out.push(suggestionsMsg(s));
        return true;
    },

    suggestion(ctx) {
        const { s, msg } = ctx;
        const i = s.suggestions.findIndex((x) => x.id === msg.id);
        if (i < 0 || (msg.action !== "add" && msg.action !== "dismiss")) return false;
        const [picked] = s.suggestions.splice(i, 1);
        if (msg.action === "add" && s.queue.length < MAX_QUEUE) s.queue.push(picked.track);
        s.rev++;
        ctx.out.push(suggestionsMsg(s));
        // The host's app mirrors the room's queue, so it gets the new one straight away.
        if (msg.action === "add") ctx.out.push(stateMsg(s, s.host));
        return true;
    },

    react(ctx) {
        const { s, msg, event, now } = ctx;
        if (!EMOJI.includes(msg.emoji)) return false;
        const last = s.reactAt[event.from];
        if (last !== undefined && now - last < REACT_GAP_MS) return false;
        s.reactAt[event.from] = now;
        ctx.out.push({ to: "all", msg: { t: "reaction", from: event.from, emoji: msg.emoji } });
        return true;
    },
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ pass 46`, `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/room.js infra/share-worker/test/room.test.js
git commit -m "feat(listen): room queue, 3-per-member suggestions, one reaction a second"
```

### Task 5: `makeHost`, `end`, the 60 s handover and closing

**Files:**
- Modify: `infra/share-worker/src/room.js`, `infra/share-worker/test/room.test.js`

- [ ] **Step 1: Append the tests.** The handover and closing rules live in the `alarm` event from Task 1; `makeHost` and `end` are new.

```js
// ── Task 5: host handover, makeHost, end, closing ─────────────────────────────

test("makeHost hands control to a connected member and tells everyone", () => {
    const r = send(party(), "h", { t: "makeHost", memberId: "b" });
    assert.equal(r.state.host, "b");
    assert.equal(sent(r, "state")[0].msg.state.host, "b");
    assert.equal(send(r.state, "h", { t: "play" }).state, r.state, "the old host is a listener now");
    assert.equal(send(party(), "h", { t: "makeHost", memberId: "ghost" }).out.length, 0);
});

test("a host gone for 60 s is replaced by the longest-joined member", () => {
    const s = step(party(), { type: "close", from: "h" }, T0 + 1_000).state;
    assert.equal(step(s, { type: "alarm" }, T0 + 60_999).state.host, "h");
    const r = step(s, { type: "alarm" }, T0 + 61_000);
    assert.equal(r.state.host, "a");
    assert.deepEqual(r.state.members.map((m) => m.id), ["a", "b"]);
    assert.equal(sent(r, "state")[0].msg.state.host, "a");
});

test("a host back within 60 s on its resume token stays host", () => {
    let s = step(party(), { type: "close", from: "h" }, T0 + 1_000).state;
    s = hello(s, "h2", { resumeToken: "tok-h", at: T0 + 30_000 }).state;
    assert.equal(step(s, { type: "alarm" }, T0 + 61_000).state.host, "h");
});

test("end tells everyone and closes the room", () => {
    const r = send(party(), "h", { t: "end" });
    assert.equal(r.closed, true);
    assert.equal(r.state, null);
    assert.deepEqual(sent(r, "ended")[0], { to: "all", msg: { t: "ended", reason: "host_ended" } });
});

test("an empty room closes 5 minutes after the last member leaves; any room closes after 12 hours", () => {
    let s = party();
    for (const id of ["h", "a", "b"]) s = step(s, { type: "close", from: id }, T0 + 1_000).state;
    s = step(s, { type: "alarm" }, T0 + 61_000).state; // grace over: the slots are pruned
    assert.equal(s.members.length, 0);
    assert.equal(step(s, { type: "alarm" }, T0 + 1_000 + 5 * 60_000).closed, true);
    assert.equal(step(party(), { type: "alarm" }, T0 + 12 * 3_600_000).closed, true);
});

test("the alarm is always the earliest pending timer", () => {
    const s = step(loaded().state, { type: "close", from: "a" }, T0 + 150).state;
    assert.equal(step(s, { type: "msg", from: "b", msg: { t: "ping", c: 1 } }, T0 + 160).alarmAt, T0 + 100 + 8_000);
});
```

- [ ] **Step 2: Run them and check they fail**

Run: `cd infra/share-worker && npm test`
Expected: FAIL on `makeHost` and `end`. The alarm tests already pass: that is Task 1's code being pinned.

- [ ] **Step 3: Add `makeHost` and `end` to `MESSAGES`:**

```js
    makeHost(ctx) {
        const { s, msg } = ctx;
        if (msg.memberId === s.host || !findConnected(s, msg.memberId)) return false;
        s.host = msg.memberId;
        s.rev++;
        ctx.out.push(stateMsg(s));
        return true;
    },

    end(ctx) {
        ctx.out.push({ to: "all", msg: { t: "ended", reason: "host_ended" } });
        ctx.closed = true;
        return true;
    },
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ pass 52`, `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/room.js infra/share-worker/test/room.test.js
git commit -m "feat(listen): makeHost, end, 60 s host handover, empty-room and 12 h close"
```

### Task 6: The `ListenRoom` Durable Object

**Files:**
- Create: `infra/share-worker/src/listen-room.js`, `infra/share-worker/test/fake-room.js`, `infra/share-worker/test/listen-room.test.js`

The runtime pieces this wrapper uses: `ctx.storage.get/put/deleteAll/setAlarm/deleteAlarm`, `ctx.acceptWebSocket` (the Hibernation API), `ctx.getWebSockets()`, and `ws.serializeAttachment/deserializeAttachment` (the per-connection `memberId` survives hibernation). The room lives under one storage key, `"room"`. A plain class works as a Durable Object; it does not import `cloudflare:workers`, so `node --test` can load it.

- [ ] **Step 1: Create the fakes, `test/fake-room.js`:**

```js
/** Just enough of the Durable Object runtime for src/listen-room.js: storage, alarm, hibernatable sockets. */
import { ListenRoom } from "../src/listen-room.js";

export function fakeCtx() {
    const store = new Map();
    const sockets = [];
    const ctx = {
        store,
        sockets,
        alarm: null,
        storage: {
            async get(key) { return store.has(key) ? structuredClone(store.get(key)) : undefined; },
            async put(key, value) { store.set(key, structuredClone(value)); },
            async deleteAll() { store.clear(); },
            async setAlarm(at) { ctx.alarm = at; },
            async deleteAlarm() { ctx.alarm = null; },
        },
        acceptWebSocket(ws) { sockets.push(ws); },
        getWebSockets() { return sockets.filter((s) => !s.closed); },
    };
    return ctx;
}

export function fakeSocket() {
    let attachment = null;
    return {
        sent: [],
        closed: false,
        closeCode: null,
        send(text) { this.sent.push(JSON.parse(text)); },
        close(code) { this.closed = true; this.closeCode = code; },
        serializeAttachment(value) { attachment = structuredClone(value); },
        deserializeAttachment() { return attachment; },
    };
}

/** A fake `ROOMS` namespace: idFromName is the identity, get() makes one ListenRoom per name. */
export function roomsNamespace(now = () => Date.now()) {
    const rooms = new Map();
    return {
        rooms,
        idFromName: (name) => name,
        get(id) {
            if (!rooms.has(id)) rooms.set(id, new ListenRoom(fakeCtx(), {}, now));
            return rooms.get(id);
        },
    };
}
```

- [ ] **Step 2: Write the failing test, `test/listen-room.test.js`:**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { ListenRoom, MSG_PER_SECOND } from "../src/listen-room.js";
import { sha256Hex } from "../src/store.js";
import { fakeCtx, fakeSocket } from "./fake-room.js";

async function openRoom(clock) {
    const ctx = fakeCtx();
    const room = new ListenRoom(ctx, {}, () => clock.t);
    await room.apply({ type: "create", code: "ABCDEF", hostName: "Rawn", keyHash: await sha256Hex("KEY") });
    return { ctx, room };
}

async function connect(ctx, room, hello) {
    const ws = fakeSocket();
    ctx.acceptWebSocket(ws);
    await room.webSocketMessage(ws, JSON.stringify({ t: "hello", ...hello }));
    return ws;
}

test("the host key makes the first socket host; a wrong key is a plain listener", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const welcome = host.sent.find((m) => m.t === "welcome");
    assert.equal(welcome.state.host, welcome.memberId);
    assert.equal(host.deserializeAttachment().memberId, welcome.memberId);
    const other = await connect(ctx, room, { name: "Sam", hostKey: "WRONG" });
    assert.notEqual(other.sent.find((m) => m.t === "welcome").state.host, other.deserializeAttachment().memberId);
    assert.ok(host.sent.some((m) => m.t === "members" && m.members.length === 2), "everyone hears about the new member");
    assert.equal(ctx.alarm, 1_000 + 12 * 3_600_000);
});

test("messages before hello are ignored; a room that has closed turns the hello away", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const ws = fakeSocket();
    ctx.acceptWebSocket(ws);
    await room.webSocketMessage(ws, JSON.stringify({ t: "play" }));
    await room.webSocketMessage(ws, "not json");
    assert.equal(ws.sent.length, 0);
    await ctx.storage.deleteAll();
    await room.webSocketMessage(ws, JSON.stringify({ t: "hello" }));
    assert.equal(ws.closeCode, 4404);
});

test("a resume replaces the old socket, whose late close doesn't drop the member", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const first = await connect(ctx, room, { hostKey: "KEY" });
    const { memberId, token } = first.sent.find((m) => m.t === "welcome");
    const second = await connect(ctx, room, { resumeToken: token });
    assert.ok(first.closed);
    assert.equal(second.sent.find((m) => m.t === "welcome").memberId, memberId);
    await room.webSocketClose(first, 1000, "replaced");
    const stored = await ctx.storage.get("room");
    assert.equal(stored.members.find((m) => m.id === memberId).leftAt, null);
});

test("more than 20 messages in a second closes the connection", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const ws = await connect(ctx, room, {});
    for (let i = 1; i < MSG_PER_SECOND; i++) await room.webSocketMessage(ws, JSON.stringify({ t: "ping", c: i }));
    assert.equal(ws.closed, false);
    await room.webSocketMessage(ws, JSON.stringify({ t: "ping", c: 99 }));
    assert.equal(ws.closeCode, 1008);
});

test("end tells everyone, closes every socket and wipes the room", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { hostKey: "KEY" });
    const guest = await connect(ctx, room, {});
    await room.webSocketMessage(host, JSON.stringify({ t: "end" }));
    assert.ok(guest.sent.some((m) => m.t === "ended"));
    assert.equal(guest.closeCode, 4000);
    assert.equal(ctx.store.size, 0);
    assert.equal(ctx.alarm, null);
});

test("the alarm hands a vanished host's room to the next member", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { hostKey: "KEY" });
    clock.t = 2_000;
    const guest = await connect(ctx, room, {});
    const guestId = guest.deserializeAttachment().memberId;
    host.closed = true;
    await room.webSocketClose(host, 1006, "gone");
    assert.equal(ctx.alarm, 2_000 + 60_000);
    clock.t = 62_000;
    await room.alarm();
    assert.equal(guest.sent.filter((m) => m.t === "state").at(-1).state.host, guestId);
});
```

- [ ] **Step 3: Run it and check it fails**

Run: `cd infra/share-worker && npm test`
Expected: FAIL, `Cannot find module '../src/listen-room.js'`.

- [ ] **Step 4: Create `src/listen-room.js`:**

```js
/**
 * ListenRoom: one Durable Object per Listen Together room (spec 2026-09-24 §2). A thin shell around the
 * pure src/room.js: it keeps the room in storage (hibernation drops memory), binds each socket to its
 * member with serializeAttachment, sends what step() says to send, and arms the single alarm.
 */
import { step, preview, MAX_MEMBERS } from "./room.js";
import { sameHex, sha256Hex } from "./store.js";

/** 32 symbols with no 0/O or 1/I, so `byte & 31` is unbiased and codes read aloud cleanly. */
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const MSG_PER_SECOND = 20;

export function randomCode(length = 6) {
    return Array.from(crypto.getRandomValues(new Uint8Array(length)), (b) => ALPHABET[b & 31]).join("");
}

export function base64url(bytes) {
    return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const memberOf = (ws) => ws.deserializeAttachment()?.memberId ?? null;

export class ListenRoom {
    constructor(ctx, env, now = () => Date.now()) {
        this.ctx = ctx;
        this.env = env;
        this.now = now;
        // ponytail: per-socket message counters live in memory and reset when the room hibernates (spec §2 allows it).
        this.rates = new WeakMap();
    }

    /** Internal routes, called by the Worker through the ROOMS binding. */
    async fetch(request) {
        const url = new URL(request.url);
        if (url.pathname === "/init" && request.method === "POST") {
            const { code, hostName, keyHash } = await request.json();
            const r = await this.apply({ type: "create", code, hostName, keyHash });
            return new Response(null, { status: r.reject ? 409 : 201 });
        }
        const state = (await this.ctx.storage.get("room")) ?? null;
        if (url.pathname === "/preview") return state ? Response.json(preview(state)) : new Response(null, { status: 404 });
        if (url.pathname === "/ws") {
            if (!state) return new Response(null, { status: 404 });
            if (request.headers.get("Upgrade") !== "websocket") return new Response(null, { status: 426 });
            // `r=1` = "I have a resume token": a returning member may rejoin a full room; hello checks the token.
            if (state.members.length >= MAX_MEMBERS && url.searchParams.get("r") !== "1") return new Response(null, { status: 409 });
            const pair = new WebSocketPair();
            this.ctx.acceptWebSocket(pair[1]);
            return new Response(null, { status: 101, webSocket: pair[0] });
        }
        return new Response(null, { status: 404 });
    }

    async webSocketMessage(ws, data) {
        if (this.overLimit(ws)) { ws.close(1008, "too many messages"); return; }
        let msg;
        try { msg = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data)); } catch { return; }
        if (!msg || typeof msg !== "object" || typeof msg.t !== "string") return;
        const from = memberOf(ws);
        if (!from) {
            if (msg.t !== "hello") return; // the first message must be hello (spec §2)
            const state = (await this.ctx.storage.get("room")) ?? null;
            const hostKeyOk = !!state && typeof msg.hostKey === "string" && sameHex(await sha256Hex(msg.hostKey), state.keyHash);
            const r = await this.apply(
                { type: "hello", msg, hostKeyOk, newId: randomCode(8), newToken: base64url(crypto.getRandomValues(new Uint8Array(16))) },
                (r) => { if (r.bind) this.bind(ws, r.bind); },
            );
            if (r.reject) ws.close(r.reject.code, r.reject.reason);
            return;
        }
        await this.apply({ type: "msg", from, msg, newId: msg.t === "suggest" ? randomCode(8) : undefined });
    }

    async webSocketClose(ws, code, reason) {
        const from = memberOf(ws);
        try { ws.close(code, reason); } catch { /* already closed */ }
        // A socket replaced by a resume carries no member any more, so its close changes nothing.
        if (from) await this.apply({ type: "close", from });
    }

    async webSocketError(ws) {
        const from = memberOf(ws);
        if (from) await this.apply({ type: "close", from });
    }

    async alarm() {
        await this.apply({ type: "alarm" });
    }

    /** Ties `ws` to `memberId`, closing any older socket of the same member (a resume after a drop). */
    bind(ws, memberId) {
        for (const other of this.ctx.getWebSockets()) {
            if (other !== ws && memberOf(other) === memberId) {
                other.serializeAttachment({ memberId: null });
                try { other.close(1000, "replaced"); } catch { /* gone */ }
            }
        }
        ws.serializeAttachment({ memberId });
    }

    overLimit(ws) {
        const now = this.now();
        const r = this.rates.get(ws);
        if (!r || now - r.start >= 1000) { this.rates.set(ws, { start: now, count: 1 }); return false; }
        return ++r.count > MSG_PER_SECOND;
    }

    async apply(event, beforeSend) {
        const state = (await this.ctx.storage.get("room")) ?? null;
        const r = step(state, event, this.now());
        beforeSend?.(r);
        for (const { to, msg } of r.out) this.send(to, msg);
        if (r.closed) {
            for (const ws of this.ctx.getWebSockets()) { try { ws.close(4000, "ended"); } catch { /* gone */ } }
            await this.ctx.storage.deleteAlarm();
            await this.ctx.storage.deleteAll();
            return r;
        }
        if (r.state && r.state !== state) {
            await this.ctx.storage.put("room", r.state);
            await this.ctx.storage.setAlarm(r.alarmAt);
        }
        return r;
    }

    send(to, msg) {
        const text = JSON.stringify(msg);
        for (const ws of this.ctx.getWebSockets()) {
            const id = memberOf(ws);
            if (id && (to === "all" || id === to)) { try { ws.send(text); } catch { /* closing */ } }
        }
    }
}
```

- [ ] **Step 5: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ pass 58`, `ℹ fail 0`.

- [ ] **Step 6: Commit**

```bash
git add infra/share-worker/src/listen-room.js infra/share-worker/test/fake-room.js infra/share-worker/test/listen-room.test.js
git commit -m "feat(listen): ListenRoom Durable Object — hibernating sockets, storage, single alarm, 20 msg/s cap"
```

### Task 7: Routes, the invite page and `wrangler.toml`

**Files:**
- Modify: `infra/share-worker/src/index.js`, `infra/share-worker/src/pages.js`, `infra/share-worker/test/fake-kv.js`, `infra/share-worker/wrangler.toml`
- Create: `infra/share-worker/test/rooms.test.js`

- [ ] **Step 1: Give the fake env the new bindings.** In `test/fake-kv.js`, add at the top:

```js
import { roomsNamespace } from "./fake-room.js";
```

and inside `env()`, after `WRITE_RL`:

```js
        ROOM_RL: { limit: async () => ({ success: true }) },
        ROOMS: roomsNamespace(),
```

- [ ] **Step 2: Write the failing test, `test/rooms.test.js`:**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { sha256Hex } from "../src/store.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";
const create = (e, body = { hostName: "Rawn" }) => handle(new Request(`${BASE}/v1/rooms`, {
    method: "POST", headers: { "CF-Connecting-IP": "203.0.113.9" }, body: JSON.stringify(body),
}), e);

test("creating a room returns a code, a host key and the invite link; only the key's hash is stored", async () => {
    const e = env();
    const r = await create(e);
    assert.equal(r.status, 201);
    const { code, hostKey, url } = await r.json();
    assert.match(code, /^[A-HJ-NP-Z2-9]{6}$/);
    assert.match(hostKey, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(url, `${BASE}/l/${code}`);
    const stored = await e.ROOMS.rooms.get(code).ctx.storage.get("room");
    assert.equal(stored.keyHash, await sha256Hex(hostKey));
    assert.equal(stored.hostName, "Rawn");
    assert.ok(!JSON.stringify(stored).includes(hostKey));
});

test("room creation has its own rate limit", async () => {
    const e = env({ ROOM_RL: { limit: async () => ({ success: false }) } });
    assert.equal((await create(e)).status, 429);
    assert.equal((await handle(new Request(`${BASE}/v1/rooms`), env())).status, 405);
});

test("the preview shows the host, the count and whether it is full; unknown rooms are 404", async () => {
    const e = env();
    const { code } = await (await create(e)).json();
    const r = await handle(new Request(`${BASE}/v1/rooms/${code}`), e);
    assert.equal(r.status, 200);
    assert.deepEqual(await r.json(), { hostName: "Rawn", memberCount: 0, full: false });
    assert.equal((await handle(new Request(`${BASE}/v1/rooms/ZZZZZZ`), e)).status, 404);
    assert.equal((await handle(new Request(`${BASE}/v1/rooms/abc`), e)).status, 404, "not a room code");
});

test("the socket route: 404 for a closed room, 426 without an upgrade, 409 when full", async () => {
    const e = env();
    const ws = (code, query = "") => handle(new Request(`${BASE}/v1/rooms/${code}/ws${query}`, { headers: { Upgrade: "websocket" } }), e);
    assert.equal((await ws("ZZZZZZ")).status, 404);
    const { code } = await (await create(e)).json();
    assert.equal((await handle(new Request(`${BASE}/v1/rooms/${code}/ws`), e)).status, 426);
    const room = e.ROOMS.rooms.get(code);
    for (let i = 0; i < 10; i++) await room.apply({ type: "hello", msg: { t: "hello" }, hostKeyOk: false, newId: `m${i}`, newToken: `t${i}` });
    assert.equal((await ws(code)).status, 409);
});

test("the invite page names the host (escaped) and 404s once the room is gone", async () => {
    const e = env();
    const { code } = await (await create(e, { hostName: "<b>Rawn</b>" })).json();
    const r = await handle(new Request(`${BASE}/l/${code}`), e);
    assert.equal(r.status, 200);
    const html = await r.text();
    assert.ok(html.includes("Join &lt;b&gt;Rawn&lt;/b&gt;&#39;s session in Stash"));
    assert.ok(html.includes("intent://") && html.includes("releases/latest"));
    const gone = await handle(new Request(`${BASE}/l/ZZZZZZ`), e);
    assert.equal(gone.status, 404);
    assert.match(await gone.text(), /has ended/);
});
```

- [ ] **Step 3: Run it and check it fails**

Run: `cd infra/share-worker && npm test`
Expected: FAIL. `POST /v1/rooms` is a 404.

- [ ] **Step 4: Add `roomPage` to the end of `src/pages.js`:**

```js
/** GET /l/{code}: the Listen Together invite page (spec 2026-09-24 §2), built like the mix page. */
export function roomPage(pv, pageUrl) {
    const host = pv.hostName || "A friend";
    const listening = `${pv.memberCount} listening${pv.full ? " · full" : ""}`;
    const now = pv.track ? `<p class="muted">Now playing: ${esc(pv.track.t)} · ${esc(pv.track.a)}</p>` : "";
    return shell({
        title: `Join ${host}'s session in Stash`,
        description: `${listening} · Listen Together on Stash`,
        pageUrl,
        body: `<h1>Join ${esc(host)}'s session in Stash</h1><p class="muted">${listening}</p>${now}`,
    });
}
```

- [ ] **Step 5: Wire the routes in `src/index.js`.**

Replace the `pages.js` import line with these lines (the `export` makes the class visible to the runtime):

```js
import { assetLinks, messagePage, mixPage, roomPage, trackPage } from "./pages.js";
import { base64url, randomCode } from "./listen-room.js";

export { ListenRoom } from "./listen-room.js";
```

Under `const MIX_API = …`, add:

```js
/** Room codes: 6 of the 32 unambiguous symbols in listen-room.js (no 0/O, 1/I). */
const ROOM_API = /^\/v1\/rooms\/([A-HJ-NP-Z2-9]{6})(\/ws)?$/;
const ROOM_PAGE = /^\/l\/([A-HJ-NP-Z2-9]{6})$/;
```

In `handle`, directly before `if (method === "GET" && path === "/.well-known/assetlinks.json")`, add:

```js
    if (path === "/v1/rooms") return method === "POST" ? createRoom(request, env, url) : methodNotAllowed();
    const room = ROOM_API.exec(path);
    if (room) {
        if (method !== "GET") return methodNotAllowed();
        const stub = env.ROOMS.get(env.ROOMS.idFromName(room[1]));
        if (room[2]) return stub.fetch(new Request(`https://room/ws${url.search}`, request));
        const pv = await roomPreview(stub);
        return pv ? json(pv, 200, { "cache-control": "no-store" }) : json({ error: "not_found" }, 404);
    }
```

and directly before the final `return json({ error: "not_found" }, 404);` of `handle`, add:

```js
    const invite = ROOM_PAGE.exec(path);
    if (method === "GET" && invite) {
        const pv = await roomPreview(env.ROOMS.get(env.ROOMS.idFromName(invite[1])));
        return pv ? html(roomPage(pv, url.href)) : html(messagePage("Session ended", "This listening session has ended."), 404);
    }
```

At the end of the file, add:

```js
async function roomPreview(stub) {
    const r = await stub.fetch(new Request("https://room/preview"));
    return r.status === 200 ? r.json() : null;
}

/** POST /v1/rooms (spec 2026-09-24 §2): a fresh room, its code and the host key (only its SHA-256 is kept). */
async function createRoom(request, env, url) {
    if (!(await env.ROOM_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { body, tooBig } = await readBody(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    const hostName = typeof body?.hostName === "string" ? body.hostName.trim().slice(0, 40) || undefined : undefined;
    const hostKey = base64url(crypto.getRandomValues(new Uint8Array(32)));
    const keyHash = await sha256Hex(hostKey);
    for (let i = 0; i < 5; i++) {
        const code = randomCode();
        const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
        const r = await stub.fetch(new Request("https://room/init", { method: "POST", body: JSON.stringify({ code, hostName, keyHash }) }));
        if (r.status === 201) return json({ code, hostKey, url: `${url.origin}/l/${code}` }, 201);
    }
    return json({ error: "unavailable" }, 503, { "Retry-After": "2" });
}
```

- [ ] **Step 6: Add the bindings to `wrangler.toml`** (at the end). The `ROOM_RL` budget is separate from the shared-mix `CREATE_RL` (spec §2).

```toml
# Listen Together (spec 2026-09-24 §2): one Durable Object per room, reached over WebSockets.
[[durable_objects.bindings]]
name = "ROOMS"
class_name = "ListenRoom"

[[migrations]]
tag = "v1"
new_sqlite_classes = ["ListenRoom"]

[[ratelimits]]
name = "ROOM_RL"
namespace_id = "2003"
simple = { limit = 5, period = 60 }
```

- [ ] **Step 7: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ pass 63`, `ℹ fail 0`.

- [ ] **Step 8: Check the config parses** (no deploy, no login needed):

Run: `cd infra/share-worker && npx wrangler deploy --dry-run --outdir /tmp/stash-share-dry`
Expected: ends with `--dry-run: exiting now.` and no binding errors.

- [ ] **Step 9: Commit**

```bash
git add infra/share-worker/src/index.js infra/share-worker/src/pages.js infra/share-worker/test/fake-kv.js infra/share-worker/test/rooms.test.js infra/share-worker/wrangler.toml
git commit -m "feat(listen): room routes — POST /v1/rooms, preview, /ws upgrade, /l/{code} invite page"
```

### Task 8: Worker README

**Files:**
- Modify: `infra/share-worker/README.md`

- [ ] **Step 1: Document the rooms.** After the `- Routes: …` line, add:

```markdown
- Listen Together rooms (spec `docs/superpowers/specs/2026-09-24-listen-together-design.md`): Durable Object `ListenRoom`, bound as `ROOMS`, one per room code (`idFromName(code)`). `src/room.js` is the pure logic; `src/listen-room.js` wraps it (storage key `room`, one alarm, WebSocket Hibernation). Routes: `POST /v1/rooms` (`ROOM_RL`, 5/min per IP), `GET /v1/rooms/{code}`, `GET /v1/rooms/{code}/ws` (`?r=1` = rejoining with a resume token), `GET /l/{code}`. A room keeps display names, song descriptors and the host key's SHA-256; everything is deleted when it closes (5 min after the last member leaves, or 12 h after creation).
```

and under `## Deploy`, after the code block:

```markdown
The first deploy after adding Listen Together applies the `v1` Durable Object migration (`new_sqlite_classes = ["ListenRoom"]`). Migrations are one-way: never rename or delete `ListenRoom` without a new migration tag.
```

- [ ] **Step 2: Commit**

```bash
git add infra/share-worker/README.md
git commit -m "docs(listen): Worker README — rooms, routes, migration note"
```

# Part B: Shared logic

### Task 9: Room links (`ShareLinks.Parsed.Room`, `roomUrl`)

**Files:**
- Modify: `core/model/src/main/kotlin/com/stash/core/model/share/ShareLinks.kt`
- Modify: `app/src/main/kotlin/com/stash/app/MainActivity.kt` (its `when` over `Parsed` must stay exhaustive)
- Test: `core/model/src/test/kotlin/com/stash/core/model/share/ShareLinksTest.kt`

- [ ] **Step 1: Write the failing tests.** Add to `ShareLinksTest`:

```kotlin
    @Test fun `room url and parse round-trip, lower case and a trailing slash accepted`() {
        assertThat(ShareLinks.roomUrl("K7QA2P")).isEqualTo("$base/l/K7QA2P")
        assertThat(ShareLinks.parse("$base/l/K7QA2P")).isEqualTo(ShareLinks.Parsed.Room("K7QA2P"))
        assertThat(ShareLinks.parse("$base/l/k7qa2p/")).isEqualTo(ShareLinks.Parsed.Room("K7QA2P"))
    }

    @Test fun `room codes that are the wrong length, use ambiguous characters or come from another host are rejected`() {
        assertThat(ShareLinks.parse("$base/l/K7QA2")).isNull()
        assertThat(ShareLinks.parse("$base/l/K7QA2O")).isNull() // no O in the alphabet
        assertThat(ShareLinks.parse("$base/l/K7QA21")).isNull() // no 1 either
        assertThat(ShareLinks.parse("https://evil.example/l/K7QA2P")).isNull()
    }
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew :core:model:testDebugUnitTest --tests 'com.stash.core.model.share.ShareLinksTest' -q`
Expected: FAIL, unresolved reference `roomUrl` / `Room`.

- [ ] **Step 3: Implement.** In `ShareLinks`:

Add to `sealed interface Parsed`:

```kotlin
        /** A Listen Together invite, `https://…/l/{code}` (spec 2026-09-24 §5). */
        data class Room(val code: String) : Parsed
```

Next to `private val ID`:

```kotlin
    /** 6 of the Worker's 32 room-code symbols (no 0/O, 1/I). Keep in sync with ROOM_API in infra/share-worker/src/index.js. */
    private val ROOM_CODE = Regex("^[A-HJ-NP-Z2-9]{6}$")
```

Next to `mixUrl`:

```kotlin
    fun roomUrl(code: String): String = "${ShareConfig.BASE_URL}/l/$code"
```

In `parse`, inside the `scheme == "https" && host in ShareConfig.HOSTS -> when {` block, after the `/m/` line:

```kotlin
                path.startsWith("/l/") -> path.removePrefix("/l/").uppercase().takeIf { ROOM_CODE.matches(it) }?.let { Parsed.Room(it) }
```

- [ ] **Step 4: Keep `MainActivity` compiling.** Its `val handled = when (val parsed = ShareLinks.parse(…))` must cover the new case. Add after the `Parsed.Track` branch:

```kotlin
                is ShareLinks.Parsed.Room -> {
                    pendingDeepLink.value = DEEP_LINK_LISTEN_PREFIX + parsed.code
                    true
                }
```

and in the `companion object`:

```kotlin
        /** [pendingDeepLink] prefix for a Listen Together invite; the room code follows. */
        const val DEEP_LINK_LISTEN_PREFIX = "listen:"
```

Until Task 25 adds the route, `StashScaffold` clears the unknown target without navigating.

- [ ] **Step 5: Run the tests and build**

Run: `./gradlew :core:model:testDebugUnitTest --tests 'com.stash.core.model.share.ShareLinksTest' -q`
Expected: PASS.
Run: `./gradlew :app:compileDebugKotlin -q`
Expected: success.

- [ ] **Step 6: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/share/ShareLinks.kt core/model/src/test/kotlin/com/stash/core/model/share/ShareLinksTest.kt app/src/main/kotlin/com/stash/app/MainActivity.kt
git commit -m "feat(listen): parse and build /l/{code} room links"
```

### Task 10: The message model (`RoomProtocol`)

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/listen/RoomProtocol.kt`
- Test: `core/model/src/test/kotlin/com/stash/core/model/listen/RoomProtocolTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.model.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import org.junit.Test

class RoomProtocolTest {
    private val track = SharedTrack("Avril 14th", "Aphex Twin", durationMs = 125_000, isrc = "GBBPW0100025")

    @Test fun `every client message survives a round trip`() {
        val all = listOf(
            ClientMessage.Hello(name = "Rawn", hostKey = "KEY"), ClientMessage.Ping(42),
            ClientMessage.Load(track, 30_000, listOf(track)), ClientMessage.Play, ClientMessage.Pause,
            ClientMessage.Seek(90_000), ClientMessage.Queue(emptyList()), ClientMessage.Status("ready", 3),
            ClientMessage.Suggest(track), ClientMessage.SuggestionAction("s1", "add"),
            ClientMessage.React(RoomProtocol.EMOJI[0]), ClientMessage.MakeHost("m2"), ClientMessage.End,
        )
        for (m in all) {
            assertThat(RoomProtocol.json.decodeFromString(ClientMessage.serializer(), RoomProtocol.encode(m))).isEqualTo(m)
        }
    }

    @Test fun `the wire form puts the type in t and leaves out nulls`() {
        assertThat(RoomProtocol.encode(ClientMessage.Play)).isEqualTo("""{"t":"play"}""")
        assertThat(RoomProtocol.encode(ClientMessage.Hello(name = "Rawn"))).isEqualTo("""{"t":"hello","name":"Rawn"}""")
        assertThat(RoomProtocol.encode(ClientMessage.Load(track, 0, emptyList()))).isEqualTo(
            """{"t":"load","track":{"t":"Avril 14th","a":"Aphex Twin","d":125000,"isrc":"GBBPW0100025"},"positionMs":0,"queue":[]}""",
        )
    }

    @Test fun `a welcome exactly as the Worker sends it decodes`() {
        val text = """{"t":"welcome","memberId":"m1","token":"tok","state":{"rev":3,"host":"m1","track":null,"trackKey":0,
            "timeline":{"positionMs":0,"atRoomMs":1000,"playing":false},"queue":[],
            "members":[{"id":"m1","name":null,"joinedAt":1000,"status":"buffering"}],"suggestions":[],"phase":{"kind":"playing"}}}"""
        val welcome = RoomProtocol.decode(text) as ServerMessage.Welcome
        assertThat(welcome.memberId).isEqualTo("m1")
        assertThat(welcome.state.host).isEqualTo("m1")
        assertThat(welcome.state.members.single().name).isNull()
        assertThat(welcome.state.phase.kind).isEqualTo(RoomPhase.PLAYING)
    }

    @Test fun `unknown types and junk decode to null, extra fields are ignored`() {
        assertThat(RoomProtocol.decode("""{"t":"pong","c":1,"r":2,"extra":true}""")).isEqualTo(ServerMessage.Pong(1, 2))
        assertThat(RoomProtocol.decode("""{"t":"prepare","trackKey":2,"track":{"t":"Xtal","a":"Aphex Twin"},"positionMs":0,"deadlineMs":9}"""))
            .isEqualTo(ServerMessage.Prepare(2, SharedTrack("Xtal", "Aphex Twin"), 0, 9))
        assertThat(RoomProtocol.decode("""{"t":"nope"}""")).isNull()
        assertThat(RoomProtocol.decode("not json")).isNull()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:model:testDebugUnitTest --tests 'com.stash.core.model.listen.RoomProtocolTest' -q`
Expected: FAIL, unresolved references.

- [ ] **Step 3: Create `RoomProtocol.kt`**

```kotlin
package com.stash.core.model.listen

import com.stash.core.model.share.SharedTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Listen Together wire format (spec docs/superpowers/specs/2026-09-24-listen-together-design.md §3).
 * Each message is a JSON object whose `t` field names its type. The room side is
 * infra/share-worker/src/room.js. Songs travel as [SharedTrack] descriptors.
 */
object RoomProtocol {
    /** The six reactions, in picker order. Keep in sync with EMOJI in infra/share-worker/src/room.js. */
    val EMOJI: List<String> = listOf("\u2764\uFE0F", "\uD83D\uDD25", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDC4F", "\uD83C\uDFB6")

    val json: Json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    /** A room message, or null for anything unknown or malformed: those are ignored (spec §6). */
    fun decode(text: String): ServerMessage? =
        runCatching { json.decodeFromString(ServerMessage.serializer(), text) }.getOrNull()
}

/** Phone → room (spec §3). Host-only messages sent by a listener are ignored by the room. */
@Serializable
sealed interface ClientMessage {
    @Serializable @SerialName("hello")
    data class Hello(val name: String? = null, val hostKey: String? = null, val resumeToken: String? = null) : ClientMessage

    @Serializable @SerialName("ping")
    data class Ping(val c: Long) : ClientMessage

    @Serializable @SerialName("load")
    data class Load(val track: SharedTrack, val positionMs: Long, val queue: List<SharedTrack>) : ClientMessage

    @Serializable @SerialName("play")
    data object Play : ClientMessage

    @Serializable @SerialName("pause")
    data object Pause : ClientMessage

    @Serializable @SerialName("seek")
    data class Seek(val positionMs: Long) : ClientMessage

    @Serializable @SerialName("queue")
    data class Queue(val queue: List<SharedTrack>) : ClientMessage

    /** `ready`, `buffering`, `unavailable` or `drifting`, for song [trackKey] so a late report can't count for the next song. */
    @Serializable @SerialName("status")
    data class Status(val status: String, val trackKey: Int? = null) : ClientMessage

    @Serializable @SerialName("suggest")
    data class Suggest(val track: SharedTrack) : ClientMessage

    /** [action] is `add` or `dismiss`. */
    @Serializable @SerialName("suggestion")
    data class SuggestionAction(val id: String, val action: String) : ClientMessage

    @Serializable @SerialName("react")
    data class React(val emoji: String) : ClientMessage

    @Serializable @SerialName("makeHost")
    data class MakeHost(val memberId: String) : ClientMessage

    @Serializable @SerialName("end")
    data object End : ClientMessage
}

/** Where the song is: at room time [atRoomMs] it is at [positionMs], moving if [playing] (spec §4). */
@Serializable
data class RoomTimeline(val positionMs: Long = 0, val atRoomMs: Long = 0, val playing: Boolean = false)

/** [status] is `ok`, `buffering`, `unavailable` or `drifting`. */
@Serializable
data class RoomMember(val id: String, val name: String? = null, val joinedAt: Long = 0, val status: String = "buffering")

@Serializable
data class RoomSuggestion(val id: String, val from: String, val track: SharedTrack)

@Serializable
data class RoomPhase(val kind: String = PLAYING, val trackKey: Int? = null, val deadlineMs: Long? = null) {
    companion object {
        const val PLAYING = "playing"
        const val PREPARING = "preparing"
    }
}

@Serializable
data class RoomState(
    val rev: Int = 0,
    val host: String? = null,
    val track: SharedTrack? = null,
    val trackKey: Int = 0,
    val timeline: RoomTimeline = RoomTimeline(),
    val queue: List<SharedTrack> = emptyList(),
    val members: List<RoomMember> = emptyList(),
    val suggestions: List<RoomSuggestion> = emptyList(),
    val phase: RoomPhase = RoomPhase(),
)

/** Room → phone (spec §3). */
@Serializable
sealed interface ServerMessage {
    @Serializable @SerialName("welcome")
    data class Welcome(val memberId: String, val token: String, val state: RoomState) : ServerMessage

    /** [c] is the phone's ping time echoed back; [r] is the room clock. */
    @Serializable @SerialName("pong")
    data class Pong(val c: Long, val r: Long) : ServerMessage

    @Serializable @SerialName("state")
    data class StateSync(val state: RoomState) : ServerMessage

    @Serializable @SerialName("timeline")
    data class TimelineUpdate(
        val rev: Int,
        val trackKey: Int,
        val positionMs: Long,
        val atRoomMs: Long,
        val playing: Boolean,
    ) : ServerMessage

    /** [positionMs] is where to start the song; the spec's payload plus this one field, so phones needn't wait for `state`. */
    @Serializable @SerialName("prepare")
    data class Prepare(val trackKey: Int, val track: SharedTrack, val positionMs: Long = 0, val deadlineMs: Long) : ServerMessage

    @Serializable @SerialName("members")
    data class Members(val members: List<RoomMember>) : ServerMessage

    @Serializable @SerialName("suggestions")
    data class Suggestions(val suggestions: List<RoomSuggestion>) : ServerMessage

    @Serializable @SerialName("reaction")
    data class Reaction(val from: String, val emoji: String) : ServerMessage

    @Serializable @SerialName("ended")
    data class Ended(val reason: String) : ServerMessage
}
```

- [ ] **Step 4: Run the test and check it passes**

Run: `./gradlew :core:model:testDebugUnitTest --tests 'com.stash.core.model.listen.RoomProtocolTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/listen/RoomProtocol.kt core/model/src/test/kotlin/com/stash/core/model/listen/RoomProtocolTest.kt
git commit -m "feat(listen): Listen Together message model with a t-discriminated sealed hierarchy"
```

### Task 11: `ClockSync` and `DriftController`

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/listen/ClockSync.kt`, `core/media/src/main/kotlin/com/stash/core/media/listen/DriftController.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/listen/ClockSyncTest.kt`, `core/media/src/test/kotlin/com/stash/core/media/listen/DriftControllerTest.kt`

- [ ] **Step 1: Write the failing tests**

`ClockSyncTest.kt`:

```kotlin
package com.stash.core.media.listen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClockSyncTest {
    @Test fun `offset is the room time minus the moment halfway through the round trip`() {
        val sync = ClockSync()
        sync.onPong(clientSentMs = 1_000, roomMs = 5_060, nowMs = 1_100) // rtt 100, halfway at 1_050
        assertThat(sync.offsetMs).isEqualTo(4_010)
        assertThat(sync.roomNow(1_200)).isEqualTo(5_210)
    }

    @Test fun `the fastest round trip wins`() {
        val sync = ClockSync()
        sync.onPong(1_000, 5_060, 1_100) // rtt 100 → 4_010
        sync.onPong(2_000, 6_030, 2_020) // rtt 20 → 4_020
        sync.onPong(3_000, 7_300, 3_400) // rtt 400 → 4_100
        assertThat(sync.offsetMs).isEqualTo(4_020)
    }

    @Test fun `samples older than five minutes are forgotten`() {
        val sync = ClockSync()
        sync.onPong(0, 4_010, 20) // rtt 20 → 4_000
        sync.onPong(399_900, 404_150, 400_000) // rtt 100 → 4_200, and the first sample is now too old
        assertThat(sync.offsetMs).isEqualTo(4_200)
    }

    @Test fun `no samples means no room time, and a negative round trip is ignored`() {
        val sync = ClockSync()
        assertThat(sync.roomNow(5)).isNull()
        sync.onPong(clientSentMs = 500, roomMs = 1, nowMs = 400)
        assertThat(sync.offsetMs).isNull()
    }
}
```

`DriftControllerTest.kt`:

```kotlin
package com.stash.core.media.listen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriftControllerTest {
    private fun speedOf(r: DriftResult) = (r.action as DriftAction.Speed).speed

    @Test fun `under 40 ms nothing happens`() {
        assertThat(DriftController().onTick(errorMs = 39, nowMs = 0).action).isEqualTo(DriftAction.None)
        assertThat(DriftController().onTick(errorMs = -39, nowMs = 0).action).isEqualTo(DriftAction.None)
    }

    @Test fun `40 ms to 1 s nudges the speed by error over 2000, capped at 3 percent`() {
        assertThat(speedOf(DriftController().onTick(50, 0))).isWithin(1e-6f).of(0.975f)  // ahead → slow down
        assertThat(speedOf(DriftController().onTick(-50, 0))).isWithin(1e-6f).of(1.025f) // behind → speed up
        assertThat(speedOf(DriftController().onTick(100, 0))).isWithin(1e-6f).of(0.97f)  // 0.05 capped at 0.03
        assertThat(speedOf(DriftController().onTick(1_000, 0))).isWithin(1e-6f).of(0.97f)
    }

    @Test fun `a correction keeps going until the error is under 20 ms, then returns to normal speed once`() {
        val d = DriftController()
        d.onTick(100, 0)
        assertThat(speedOf(d.onTick(30, 1_000))).isWithin(1e-6f).of(0.985f)
        assertThat(speedOf(d.onTick(10, 2_000))).isWithin(1e-6f).of(1f)
        assertThat(d.onTick(10, 3_000).action).isEqualTo(DriftAction.None)
    }

    @Test fun `over 1 s it seeks`() {
        assertThat(DriftController().onTick(1_001, 0).action).isEqualTo(DriftAction.Seek)
        assertThat(DriftController().onTick(-5_000, 0).action).isEqualTo(DriftAction.Seek)
    }

    @Test fun `drifting is reported only after 10 s above 250 ms, and clears when it recovers`() {
        val d = DriftController()
        assertThat(d.onTick(300, 0).drifting).isFalse()
        assertThat(d.onTick(300, 9_999).drifting).isFalse()
        assertThat(d.onTick(300, 10_000).drifting).isTrue()
        assertThat(d.onTick(100, 11_000).drifting).isFalse()
    }
}
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.*' -q`
Expected: FAIL, unresolved references.

- [ ] **Step 3: Create `ClockSync.kt`**

```kotlin
package com.stash.core.media.listen

/**
 * Turns ping/pong samples into the offset between this phone's clock and the room's (spec §4).
 * For each reply: `rtt = now − c`, `offset = r − (c + rtt / 2)`. The sample with the smallest
 * round trip in the last five minutes wins. Room time is `now + offset`. (YumaPlayer had the
 * sign of this backwards.) Not thread-safe; the session calls it on the main thread.
 */
class ClockSync(private val windowMs: Long = 5 * 60_000L) {
    private class Sample(val atMs: Long, val rttMs: Long, val offsetMs: Long)
    private val samples = ArrayDeque<Sample>()

    fun onPong(clientSentMs: Long, roomMs: Long, nowMs: Long) {
        val rtt = nowMs - clientSentMs
        if (rtt < 0) return
        samples.addLast(Sample(nowMs, rtt, roomMs - (clientSentMs + rtt / 2)))
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > windowMs) samples.removeFirst()
    }

    val offsetMs: Long? get() = samples.minByOrNull { it.rttMs }?.offsetMs

    fun roomNow(nowMs: Long): Long? = offsetMs?.let { nowMs + it }

    fun reset() = samples.clear()
}
```

- [ ] **Step 4: Create `DriftController.kt`**

```kotlin
package com.stash.core.media.listen

import kotlin.math.abs

sealed interface DriftAction {
    data object None : DriftAction
    /** Pitch-preserving speed (Sonic, at the end of the StashRenderersFactory chain). */
    data class Speed(val speed: Float) : DriftAction
    /** Seek to the expected position. */
    data object Seek : DriftAction
}

data class DriftResult(val action: DriftAction, val drifting: Boolean)

/**
 * Once-a-second drift correction (spec §4). `errorMs = actual − expected`:
 * - under 40 ms: nothing;
 * - 40 ms to 1 s: speed `1 − clamp(e / 2000, −0.03, 0.03)` until the error is under 20 ms, then 1.0;
 * - over 1 s: seek.
 * [DriftResult.drifting] turns true once the error has stayed above 250 ms for 10 s.
 */
class DriftController {
    private var correcting = false
    private var overSinceMs: Long? = null

    fun onTick(errorMs: Long, nowMs: Long): DriftResult {
        val size = abs(errorMs)
        overSinceMs = if (size > DRIFTING_MS) overSinceMs ?: nowMs else null
        val drifting = overSinceMs?.let { nowMs - it >= DRIFTING_FOR_MS } ?: false
        val action = when {
            size > SEEK_MS -> { correcting = false; DriftAction.Seek }
            size >= START_MS -> { correcting = true; DriftAction.Speed(speedFor(errorMs)) }
            correcting && size >= STOP_MS -> DriftAction.Speed(speedFor(errorMs))
            correcting -> { correcting = false; DriftAction.Speed(1f) }
            else -> DriftAction.None
        }
        return DriftResult(action, drifting)
    }

    fun reset() {
        correcting = false
        overSinceMs = null
    }

    companion object {
        const val START_MS = 40L
        const val STOP_MS = 20L
        const val SEEK_MS = 1_000L
        const val DRIFTING_MS = 250L
        const val DRIFTING_FOR_MS = 10_000L
        private const val MAX_ADJUST = 0.03f

        fun speedFor(errorMs: Long): Float = 1f - (errorMs / 2000f).coerceIn(-MAX_ADJUST, MAX_ADJUST)
    }
}
```

- [ ] **Step 5: Run the tests and check they pass**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.*' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/listen/ClockSync.kt core/media/src/main/kotlin/com/stash/core/media/listen/DriftController.kt core/media/src/test/kotlin/com/stash/core/media/listen/ClockSyncTest.kt core/media/src/test/kotlin/com/stash/core/media/listen/DriftControllerTest.kt
git commit -m "feat(listen): ClockSync (min-RTT offset) and DriftController (speed nudge, seek, drifting)"
```

### Task 12: The exact persist (`ensureExactTrackPersisted`, `TrackDao.findByIsrc`)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`, `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt`, `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/repository/MusicRepositoryExactPersistTest.kt`

A listener must not use `ensureTrackPersisted`: its fuzzy title-and-artist step can return the listener's own different edit and drop the host's ISRC (spec §4). The exact persist matches only by YouTube id, then Spotify URI, then ISRC. With no match it inserts a stream-only row carrying the descriptor's ids.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MusicRepositoryExactPersistTest {
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val descriptor = SharedTrack("Avril 14th", "Aphex Twin", durationMs = 125_000, isrc = "GBBPW0100025", spotifyId = "sp1", youtubeId = "yt1")

    // Same argument list as MusicRepositorySharedMixUnshareTest.repo(). If the constructor has changed since, match that test.
    private fun repo() = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = trackDao,
        playlistDao = mockk(relaxed = true),
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = mockk(relaxed = true),
    )

    private fun row(id: Long) = TrackEntity(id = id, title = "Avril 14th", artist = "Aphex Twin")

    @Test fun `a YouTube id match wins, and the host's ISRC is filled in if the row had none`() = runTest {
        coEvery { trackDao.findByYoutubeId("yt1") } returns row(7)
        assertThat(repo().ensureExactTrackPersisted(descriptor)).isEqualTo(7)
        coVerify { trackDao.backfillIsrcIfMissing(7, "GBBPW0100025") }
        coVerify(exactly = 0) { trackDao.insert(any()) }
    }

    @Test fun `then the Spotify URI, then the exact ISRC`() = runTest {
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findBySpotifyUri("spotify:track:sp1") } returns row(8)
        assertThat(repo().ensureExactTrackPersisted(descriptor)).isEqualTo(8)
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        coEvery { trackDao.findByIsrc("GBBPW0100025") } returns row(9)
        assertThat(repo().ensureExactTrackPersisted(descriptor)).isEqualTo(9)
    }

    @Test fun `with no exact match it inserts a stream-only row with every id, and never matches by title`() = runTest {
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        coEvery { trackDao.findByIsrc(any()) } returns null
        val inserted = slot<TrackEntity>()
        coEvery { trackDao.insert(capture(inserted)) } returns 42
        assertThat(repo().ensureExactTrackPersisted(descriptor)).isEqualTo(42)
        with(inserted.captured) {
            assertThat(id).isEqualTo(0)
            assertThat(isrc).isEqualTo("GBBPW0100025")
            assertThat(spotifyUri).isEqualTo("spotify:track:sp1")
            assertThat(youtubeId).isEqualTo("yt1")
            assertThat(isStreamable).isTrue()
            assertThat(canonicalTitle).isEqualTo("avril 14th")
        }
        coVerify(exactly = 0) { trackDao.findByCanonicalIdentity(any(), any()) }
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.stash.core.data.repository.MusicRepositoryExactPersistTest' -q`
Expected: FAIL, unresolved `ensureExactTrackPersisted` / `findByIsrc`.

- [ ] **Step 3: Add the DAO query** in `TrackDao.kt`, after `findByYoutubeId`:

```kotlin
    /** Find a track by exact ISRC, preferring a downloaded row (Listen Together's exact match, spec 2026-09-24 §4). */
    @Query("SELECT * FROM tracks WHERE isrc = :isrc ORDER BY is_downloaded DESC LIMIT 1")
    suspend fun findByIsrc(isrc: String): TrackEntity?
```

It's a plain query: no index and no migration. The table holds a few thousand rows.

- [ ] **Step 4: Declare it** in `MusicRepository.kt`, after `ensureTrackPersisted`:

```kotlin
    /**
     * Listen Together's exact persist (spec 2026-09-24 §4). Matches an existing row ONLY by YouTube
     * id, then Spotify URI, then exact ISRC — never by fuzzy title and artist, which could pick the
     * listener's own different edit and lose the host's ISRC. With no exact match, inserts a
     * stream-only row carrying the descriptor's ISRC, Spotify and YouTube ids. Returns the row id.
     */
    suspend fun ensureExactTrackPersisted(track: com.stash.core.model.share.SharedTrack): Long
```

- [ ] **Step 5: Implement it** in `MusicRepositoryImpl.kt`, directly after `ensureTrackPersisted`:

```kotlin
    override suspend fun ensureExactTrackPersisted(track: com.stash.core.model.share.SharedTrack): Long {
        val incoming = track.toTrack()
        val existing = incoming.youtubeId?.takeIf { it.isNotBlank() }?.let { trackDao.findByYoutubeId(it) }
            ?: incoming.spotifyUri?.let { trackDao.findBySpotifyUri(it) }
            ?: incoming.isrc?.takeIf { it.isNotBlank() }?.let { trackDao.findByIsrc(it) }
        if (existing != null) {
            backfillFrom(existing, incoming) // an exact match is the same recording, so its ISRC is trusted
            return existing.id
        }
        return trackDao.insert(
            incoming.toEntity().copy(
                id = 0L,
                canonicalTitle = canonicalizeIdentity(incoming.title),
                canonicalArtist = canonicalizeIdentity(incoming.artist),
                isStreamable = true,
            ),
        )
    }
```

Add `import com.stash.core.model.share.toTrack` at the top if it isn't already imported.

- [ ] **Step 6: Run the test and check it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.stash.core.data.repository.MusicRepositoryExactPersistTest' -q`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt core/data/src/test/kotlin/com/stash/core/data/repository/MusicRepositoryExactPersistTest.kt
git commit -m "feat(listen): exact persist — match by YouTube id, Spotify URI or ISRC only"
```

### Task 13: `RoomApiClient` (create and preview)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/share/ShareApiClient.kt` (its private `call` becomes a shared `shareCall`)
- Create: `core/data/src/main/kotlin/com/stash/core/data/listen/RoomApiClient.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/listen/RoomApiClientTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.share.ShareResult
import com.stash.core.model.share.SharedTrack
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RoomApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = RoomApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `create posts the display name and returns the code, host key and link`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"code":"K7QA2P","hostKey":"KEY","url":"https://x/l/K7QA2P"}"""))
        assertThat(client.create("Rawn")).isEqualTo(ShareResult.Ok(RoomApiClient.Created("K7QA2P", "KEY", "https://x/l/K7QA2P")))
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/rooms")
        assertThat(req.body.readUtf8()).isEqualTo("""{"hostName":"Rawn"}""")
    }

    @Test fun `create without a name sends an empty object, and 429 is a retryable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(client.create(null)).isInstanceOf(ShareResult.Failed::class.java)
        assertThat(server.takeRequest().body.readUtf8()).isEqualTo("{}")
    }

    @Test fun `preview maps 200 and 404`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"hostName":"Rawn","memberCount":3,"full":false,"track":{"t":"Xtal","a":"Aphex Twin"}}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.preview("K7QA2P"))
            .isEqualTo(ShareResult.Ok(RoomApiClient.Preview("Rawn", 3, false, SharedTrack("Xtal", "Aphex Twin"))))
        assertThat(server.takeRequest().path).isEqualTo("/v1/rooms/K7QA2P")
        assertThat(client.preview("K7QA2P")).isEqualTo(ShareResult.NotFound)
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.stash.core.data.listen.RoomApiClientTest' -q`
Expected: FAIL, unresolved `RoomApiClient`.

- [ ] **Step 3: Share the HTTP helper.** In `ShareApiClient.kt`, cut the body of `private suspend fun <T> call(…)` out into a top-level function at the end of the file, and make `call` delegate to it:

```kotlin
    private suspend fun <T> call(builder: Request.Builder, parse: (String) -> T): ShareResult<T> =
        okHttpClient.shareCall(builder, parse)
```

```kotlin
/** One request to the stash-share Worker, mapped to a [ShareResult]. Shared by the mix and room clients. */
internal suspend fun <T> OkHttpClient.shareCall(builder: Request.Builder, parse: (String) -> T): ShareResult<T> =
    withContext(Dispatchers.IO) {
        runCatching {
            newCall(builder.build()).execute().use { r ->
                when (r.code) {
                    in 200..299 -> ShareResult.Ok(parse(r.body?.string().orEmpty()))
                    403 -> ShareResult.Forbidden
                    404 -> ShareResult.NotFound
                    410 -> ShareResult.Gone
                    429 -> ShareResult.Failed("HTTP 429")
                    in 400..499 -> ShareResult.Rejected(r.code)
                    else -> ShareResult.Failed("HTTP ${r.code}")
                }
            }
        }.getOrElse { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            ShareResult.Failed(t.message)
        }
    }
```

Run `./gradlew :core:data:testDebugUnitTest --tests 'com.stash.core.data.share.ShareApiClientTest' -q` and check it still passes.

- [ ] **Step 4: Create `RoomApiClient.kt`**

```kotlin
package com.stash.core.data.listen

import com.stash.core.data.share.ShareJson
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.shareCall
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** REST half of Listen Together (spec 2026-09-24 §2): create a room, preview one for the Join screen. */
@Singleton
class RoomApiClient @Inject constructor(private val okHttpClient: OkHttpClient) {
    /** Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrl: String = ShareConfig.BASE_URL

    @Serializable data class Created(val code: String, val hostKey: String, val url: String)

    @Serializable
    data class Preview(val hostName: String? = null, val memberCount: Int = 0, val full: Boolean = false, val track: SharedTrack? = null)

    suspend fun create(hostName: String?): ShareResult<Created> {
        val body = buildJsonObject { hostName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("hostName", it.take(40)) } }
        return okHttpClient.shareCall(Request.Builder().url("$baseUrl/v1/rooms").post(body.toString().toRequestBody(JSON))) {
            ShareJson.decodeFromString(Created.serializer(), it)
        }
    }

    /** [ShareResult.NotFound] = the room has ended or never existed. */
    suspend fun preview(code: String): ShareResult<Preview> =
        okHttpClient.shareCall(Request.Builder().url("$baseUrl/v1/rooms/$code").get()) {
            ShareJson.decodeFromString(Preview.serializer(), it)
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
```

- [ ] **Step 5: Run the tests and check they pass**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.stash.core.data.listen.RoomApiClientTest' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/share/ShareApiClient.kt core/data/src/main/kotlin/com/stash/core/data/listen/RoomApiClient.kt core/data/src/test/kotlin/com/stash/core/data/listen/RoomApiClientTest.kt
git commit -m "feat(listen): RoomApiClient — create a room and preview one"
```

### Task 14: `RoomClient` (the WebSocket, reconnect and backoff)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/listen/RoomClient.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/listen/RoomClientTest.kt`

This is the first OkHttp WebSocket in the app. OkHttp 4.12's `newWebSocket` is built in, and MockWebServer can accept a WebSocket upgrade with `withWebSocketUpgrade`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.ServerMessage
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomClientTest {
    private val server = MockWebServer()
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After fun tearDown() { server.shutdown() }

    private fun upgrade(onOpen: (WebSocket) -> Unit = {}) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) { received += text }
    })

    private fun client(scope: CoroutineScope, now: () -> Long = { System.currentTimeMillis() }, hello: () -> ClientMessage.Hello) =
        RoomClient(
            http = OkHttpClient(),
            url = { resume -> server.url("/v1/rooms/K7QA2P/ws").toString() + if (resume) "?r=1" else "" },
            scope = scope,
            hello = hello,
            now = now,
            backoffMs = listOf(10L),
        )

    @Test fun `hello goes first, then server messages arrive parsed`() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.send("""{"t":"pong","c":1,"r":2}""") }))
        val c = client(this) { ClientMessage.Hello(name = "Rawn") }
        val events = withTimeout(5_000) { c.events.take(2).toList() }
        assertThat(events).containsExactly(RoomEvent.Connected, RoomEvent.Message(ServerMessage.Pong(1, 2))).inOrder()
        withTimeout(5_000) { while (received.isEmpty()) delay(10) }
        assertThat(received.first()).isEqualTo("""{"t":"hello","name":"Rawn"}""")
        c.close()
    }

    @Test fun `a dropped socket reconnects`() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.close(1001, "going away") }))
        server.enqueue(upgrade())
        val c = client(this) { ClientMessage.Hello() }
        val events = withTimeout(5_000) { c.events.take(3).toList() }
        assertThat(events).containsExactly(RoomEvent.Connected, RoomEvent.Reconnecting, RoomEvent.Connected).inOrder()
        assertThat(server.requestCount).isEqualTo(2)
        c.close()
    }

    @Test fun `a resume token asks for the rejoin route`() = runBlocking {
        server.enqueue(upgrade())
        val c = client(this) { ClientMessage.Hello(resumeToken = "TOK") }
        withTimeout(5_000) { c.events.take(1).toList() }
        assertThat(server.takeRequest().path).isEqualTo("/v1/rooms/K7QA2P/ws?r=1")
        c.close()
    }

    @Test fun `404 and 409 at the upgrade are final`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val ended = client(this) { ClientMessage.Hello() }
        assertThat(withTimeout(5_000) { ended.events.take(1).toList() }).containsExactly(RoomEvent.Closed(CloseReason.ENDED))
        server.enqueue(MockResponse().setResponseCode(409))
        val full = client(this) { ClientMessage.Hello() }
        assertThat(withTimeout(5_000) { full.events.take(1).toList() }).containsExactly(RoomEvent.Closed(CloseReason.FULL))
    }

    @Test fun `it gives up after two minutes of failed reconnects`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(500)) }
        var t = 0L
        val c = client(this, now = { t.also { t += 61_000 } }) { ClientMessage.Hello() }
        val events = withTimeout(5_000) { c.events.take(2).toList() }
        assertThat(events).containsExactly(RoomEvent.Reconnecting, RoomEvent.Closed(CloseReason.GAVE_UP)).inOrder()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.stash.core.data.listen.RoomClientTest' -q`
Expected: FAIL, unresolved `RoomClient`.

- [ ] **Step 3: Create `RoomClient.kt`**

```kotlin
package com.stash.core.data.listen

import android.os.SystemClock
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.share.ShareConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface RoomEvent {
    /** The socket is open and `hello` has gone out. */
    data object Connected : RoomEvent
    data class Message(val message: com.stash.core.model.listen.ServerMessage) : RoomEvent
    /** The socket dropped; a reconnect is scheduled (spec §5: 1, 2, 4, 8, 15 s). */
    data object Reconnecting : RoomEvent
    /** Final: no more reconnects. */
    data class Closed(val reason: CloseReason) : RoomEvent
}

enum class CloseReason { ENDED, FULL, GAVE_UP }

/** One room connection. The session only sees this, so tests can fake it. */
interface RoomConnection {
    val events: Flow<RoomEvent>
    fun send(message: ClientMessage): Boolean
    fun close()
}

fun interface RoomConnector {
    /** [hello] runs on every (re)connect, so it can carry the newest resume token. */
    fun connect(code: String, scope: CoroutineScope, hello: () -> ClientMessage.Hello): RoomConnection
}

@Singleton
class OkHttpRoomConnector @Inject constructor(okHttpClient: OkHttpClient) : RoomConnector {
    internal var baseUrl: String = ShareConfig.BASE_URL

    // A room socket can sit quiet for 30 s between pings, and the shared client's 30 s read
    // timeout would kill it. OkHttp's own ping keeps NAT mappings warm.
    private val client = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun connect(code: String, scope: CoroutineScope, hello: () -> ClientMessage.Hello): RoomConnection =
        RoomClient(client, { resume -> "$baseUrl/v1/rooms/$code/ws" + if (resume) "?r=1" else "" }, scope, hello)
}

/**
 * The Listen Together WebSocket (spec §5): sends `hello` first on every connect, parses room
 * messages, and reconnects with backoff (1, 2, 4, 8, 15, 15… s) until two minutes have passed
 * since the connection was last up. Rejections (404 closed, 409 full, a 4xxx close from the room)
 * are final.
 */
internal class RoomClient(
    private val http: OkHttpClient,
    private val url: (resume: Boolean) -> String,
    scope: CoroutineScope,
    private val hello: () -> ClientMessage.Hello,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val backoffMs: List<Long> = BACKOFF_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
) : RoomConnection {
    private val channel = Channel<RoomEvent>(Channel.UNLIMITED)
    override val events: Flow<RoomEvent> = channel.receiveAsFlow()

    @Volatile private var socket: WebSocket? = null
    private val job = scope.launch { loop() }

    override fun send(message: ClientMessage): Boolean = socket?.send(RoomProtocol.encode(message)) ?: false

    override fun close() {
        job.cancel()
        socket?.close(1000, "leave")
        socket = null
        channel.close()
    }

    private sealed interface End {
        data class Dropped(val wasOpen: Boolean) : End
        data class Final(val reason: CloseReason) : End
    }

    private suspend fun loop() {
        var downSince: Long? = null
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            when (val end = connectOnce()) {
                is End.Final -> { channel.trySend(RoomEvent.Closed(end.reason)); return }
                is End.Dropped -> {
                    if (end.wasOpen) { downSince = null; attempt = 0 }
                    val since = downSince ?: now().also { downSince = it }
                    if (now() - since >= giveUpMs) { channel.trySend(RoomEvent.Closed(CloseReason.GAVE_UP)); return }
                    channel.trySend(RoomEvent.Reconnecting)
                    delay(backoffMs[minOf(attempt, backoffMs.lastIndex)])
                    attempt++
                }
            }
        }
    }

    private suspend fun connectOnce(): End = suspendCancellableCoroutine { cont ->
        val greeting = hello()
        var opened = false
        fun finish(end: End) {
            socket = null
            if (cont.isActive) cont.resume(end)
        }
        val ws = http.newWebSocket(
            Request.Builder().url(url(greeting.resumeToken != null)).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    socket = webSocket
                    webSocket.send(RoomProtocol.encode(greeting))
                    channel.trySend(RoomEvent.Connected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    RoomProtocol.decode(text)?.let { channel.trySend(RoomEvent.Message(it)) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(
                        when (code) {
                            4000, 4404 -> End.Final(CloseReason.ENDED)
                            4409 -> End.Final(CloseReason.FULL)
                            else -> End.Dropped(opened)
                        },
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(
                        when (response?.code) {
                            404 -> End.Final(CloseReason.ENDED)
                            409 -> End.Final(CloseReason.FULL)
                            else -> End.Dropped(opened)
                        },
                    )
                }
            },
        )
        cont.invokeOnCancellation { ws.cancel() }
    }

    private companion object {
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        const val GIVE_UP_MS = 120_000L
    }
}
```

The room closes the socket with 4000 (ended), 4404 (closed) or 4409 (full), all from `src/listen-room.js`. Anything else, including OkHttp's own ping timeout, is a drop and triggers a reconnect.

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.stash.core.data.listen.*' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/listen/RoomClient.kt core/data/src/test/kotlin/com/stash/core/data/listen/RoomClientTest.kt
git commit -m "feat(listen): RoomClient — OkHttp WebSocket, hello first, backoff reconnect, 2 min give-up"
```

# Part C: The playback engine (`core/media`)

**How the pieces fit** (spec §5). `ListenTogetherController` is a Hilt singleton, so the UI, `PlayerRepositoryImpl` and `StashPlaybackService` all share one instance. The UI sends it commands. The service owns a `ListenTogetherSession`, which reads those commands, talks to the room, and drives the service's master ExoPlayer directly through `SessionPlayer`. While a session runs, the service's `MediaSession` exposes a `ListenTogetherPlayer` wrapper instead of the bare ExoPlayer. Every controller goes through that session (Now Playing through `PlayerRepositoryImpl`'s `MediaController`, the notification, the lock screen, headset buttons, Android Auto), so every transport command lands in one interceptor. `PlayerRepository` never drives session playback.

### Task 15: `ListenTogetherController` and the engine's interfaces

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/listen/ListenTogetherController.kt`, `core/media/src/main/kotlin/com/stash/core/media/listen/SessionPlayer.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/listen/ListenTogetherControllerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.media.listen

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.listen.ListenTogetherController.Command
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherControllerTest {
    private val context: Context = mockk(relaxed = true)

    @Test fun `a command sent while no service is running starts the service and waits in line`() {
        val controller = ListenTogetherController(context)
        controller.send(Command.Join("K7QA2P"))
        verify(exactly = 1) { context.startService(any()) }
        assertThat(controller.commands.tryReceive().getOrNull()).isEqualTo(Command.Join("K7QA2P"))
        controller.serviceAttached = true
        controller.send(Command.Leave)
        verify(exactly = 1) { context.startService(any()) }
    }

    @Test fun `the end of a session is announced once, however fast it was`() = runTest {
        val controller = ListenTogetherController(context)
        val ends = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { controller.sessionEnds.collect { ends += it } }
        controller.setActive(true)
        controller.setActive(false) // too fast for a StateFlow collector to have seen `true`
        controller.setActive(false)
        assertThat(ends).hasSize(1)
        job.cancel()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.ListenTogetherControllerTest' -q`
Expected: FAIL, unresolved `ListenTogetherController`.

- [ ] **Step 3: Create `ListenTogetherController.kt`**

```kotlin
package com.stash.core.media.listen

import android.content.Context
import android.content.Intent
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomSuggestion
import com.stash.core.model.listen.ServerMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ListenTogetherState {
    data object Idle : ListenTogetherState
    data class Connecting(val hosting: Boolean) : ListenTogetherState
    data class InRoom(
        val code: String,
        /** The invite link, `https://…/l/{code}`. */
        val url: String,
        val myId: String,
        val isHost: Boolean,
        val hostId: String?,
        val members: List<RoomMember>,
        val suggestions: List<RoomSuggestion>,
        /** "Reconnecting…": playback carries on from the last timeline meanwhile (spec §6). */
        val reconnecting: Boolean = false,
        /** "This song isn't available to you". */
        val unavailable: Boolean = false,
        /** "Your version may be a few seconds off": duration differs from the host's by over 2 s (spec §4). */
        val versionMismatch: Boolean = false,
    ) : ListenTogetherState
}

/**
 * The one door between the UI and the Listen Together engine (spec §5). A singleton, so the UI,
 * PlayerRepositoryImpl and StashPlaybackService share it. The session inside the service reads
 * [commands] and publishes [state]. [active] gates everything else that reacts to the player.
 */
@Singleton
class ListenTogetherController @Inject constructor(@ApplicationContext private val context: Context) {
    sealed interface Command {
        data object Host : Command
        data class Join(val code: String) : Command
        /** A listener leaves; a host hands the room to the longest-joined listener, then leaves. */
        data object Leave : Command
        /** Host only: ends the room for everyone. */
        data object End : Command
        data class React(val emoji: String) : Command
        data class Suggestion(val id: String, val add: Boolean) : Command
        data class MakeHost(val memberId: String) : Command
    }

    private val _active = MutableStateFlow(false)
    /** True from the moment a session starts until it ends (spec §4 "One session-active flag"). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _sessionEnds = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires once each time a session ends; PlayerRepositoryImpl puts the user's queue back on it. */
    val sessionEnds: SharedFlow<Unit> = _sessionEnds.asSharedFlow()

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state.asStateFlow()

    private val _reactions = MutableSharedFlow<ServerMessage.Reaction>(extraBufferCapacity = 16)
    val reactions: SharedFlow<ServerMessage.Reaction> = _reactions.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-line notices for a toast: "Session ended", "This session is full", … */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Read by the session in the playback service; commands queue up until it is running. */
    internal val commands = Channel<Command>(Channel.UNLIMITED)

    @Volatile internal var serviceAttached = false

    fun send(command: Command) {
        commands.trySend(command)
        // The session lives in the playback service, which stops itself after 5 idle minutes.
        // The user is looking at the app when they tap Start or Join, so a plain start is allowed.
        if (!serviceAttached) runCatching { context.startService(Intent(context, StashPlaybackService::class.java)) }
    }

    internal fun setActive(on: Boolean) {
        val was = _active.value
        _active.value = on
        if (was && !on) _sessionEnds.tryEmit(Unit)
    }

    internal fun publish(state: ListenTogetherState) { _state.value = state }
    internal fun reaction(reaction: ServerMessage.Reaction) { _reactions.tryEmit(reaction) }
    internal fun message(text: String) { _messages.tryEmit(text) }
}
```

- [ ] **Step 4: Create `SessionPlayer.kt`**

```kotlin
package com.stash.core.media.listen

import androidx.media3.common.MediaItem
import com.stash.core.model.share.SharedTrack

/** The user's own queue as the service held it when the session began. */
data class UserQueue(val current: MediaItem?, val upcoming: List<MediaItem>, val positionMs: Long)

/**
 * What the session engine needs from the playback service (spec §5 "New player abilities").
 * StashPlaybackService implements it over its master ExoPlayer; tests fake it. The spec's
 * `startAt(roomMs)` is not here: only the session knows the room clock, so it schedules [play].
 */
interface SessionPlayer {
    fun userQueue(): UserQueue

    /** Writes the exact current position into PlaybackStateStore; the queue itself is already saved there (spec §4). */
    suspend fun saveUserPosition()

    /**
     * Puts ListenTogetherPlayer in front of the MediaSession for this role, suspends crossfade in
     * memory and keeps the notification in the foreground. Called again when the role changes.
     */
    fun enterSession(isHost: Boolean, interceptor: SessionInterceptor)

    /** Undoes [enterSession] and empties the player. PlayerRepositoryImpl then restores the user's queue. */
    fun exitSession()

    /** prepareAt: one item at [positionMs], prepared and held with playWhenReady = false. */
    fun load(item: MediaItem, positionMs: Long)
    fun seekTo(positionMs: Long)
    fun play()
    fun pause()

    /** Pitch-preserving speed (PlaybackParameters; Sonic is at the end of the StashRenderersFactory chain). */
    fun setSpeed(speed: Float)

    val positionMs: Long

    /** The loaded item's duration, or null while unknown. */
    val durationMs: Long?

    var events: SessionPlayerEvents?
}

interface SessionPlayerEvents {
    fun onReady()
    fun onBuffering()
    fun onEnded()
    fun onError()
}

/** Transport and queue commands from every MediaSession client (spec §5 "One place catches every playback command"). */
interface SessionInterceptor {
    fun onPlay()
    fun onPause()
    fun onSeek(positionMs: Long)
    fun onNext()
    fun onPrevious()

    /** "Play next" or "Add to queue" from any track menu: the host queues it, a listener suggests it. */
    fun onAdd(items: List<MediaItem>)

    /** Tapping a song or a playlist to play it. */
    fun onSet(items: List<MediaItem>, startIndex: Int)
}

/** Song descriptor ↔ playable item. */
interface SessionCatalog {
    /** A playable item for [track] through the exact persist, or null when that fails. */
    suspend fun mediaItemFor(track: SharedTrack): MediaItem?

    suspend fun sharedTrackFor(item: MediaItem): SharedTrack?

    /** A song radio seeded from [track], without [track] itself; empty on any failure. */
    suspend fun radioAfter(track: SharedTrack): List<SharedTrack>
}
```

- [ ] **Step 5: Run the test and check it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.ListenTogetherControllerTest' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/listen/ListenTogetherController.kt core/media/src/main/kotlin/com/stash/core/media/listen/SessionPlayer.kt core/media/src/test/kotlin/com/stash/core/media/listen/ListenTogetherControllerTest.kt
git commit -m "feat(listen): ListenTogetherController singleton and the session engine's interfaces"
```

### Task 16: The session gates in `PlayerRepositoryImpl`, and the paused restore

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryListenTogetherTest.kt`

While a session is active (spec §4), `PlayerRepositoryImpl` must not:
- auto-skip on a stream error;
- silent-skip offline stream-only songs;
- `recoverOrStop`;
- run the autoplay-radio watcher;
- grow radio or library shuffle;
- prefetch the next song;
- save the queue or position.

The user's queue already sits in `PlaybackStateStore`. Stopping the saves keeps it there untouched, so a restart after a mid-session kill brings it back through the existing cold-start ghost (#462, pinned by `PlayerRepositoryColdStartTest`). When the session ends, the repository restores that queue, paused and unprepared.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.AutoplayRadioPreference
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryListenTogetherTest {
    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk { every { trackDeletions } returns MutableSharedFlow() }
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true) { every { get(any()) } returns null }
    private val controller: MediaController = mockk(relaxed = true) { every { isConnected } returns true }
    private val playbackResumer: PlaybackResumer = mockk(relaxed = true)
    private val radioGenerator: RadioStationGenerator = mockk { coEvery { start(any()) } returns (mockk<RadioSession>() to emptyList()) }
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }
    private val together = ListenTogetherController(ApplicationProvider.getApplicationContext())

    private val autoplayOn = object : AutoplayRadioPreference {
        override val enabled = flowOf(true)
        override suspend fun setEnabled(value: Boolean) = Unit
    }

    private fun item(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("https://example.test/$id")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle("T$id").setArtist("A")
                .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build(),
        )
        .build()

    private fun build(autoplay: AutoplayRadioPreference = AutoplayRadioPreference.Off): PlayerRepositoryImpl {
        val repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = mockk(relaxed = true),
            streamResolver = mockk(),
            streamUrlCache = streamUrlCache,
            connectivity = mockk(relaxed = true),
            trackDao = mockk(relaxed = true),
            playbackResumer = playbackResumer,
            radioGenerator = radioGenerator,
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
            autoplayRadioPreference = autoplay,
            listenTogether = together,
        )
        repo.controllerDeferred = controller
        shadowOf(Looper.getMainLooper()).idle()
        // The session-bus collector's initial "not alive" releases the seam; re-seat it (see PlayerRepositoryIdleResumeTest).
        repo.controllerDeferred = controller
        clearMocks(playbackStateStore, playbackResumer, answers = false)
        return repo
    }

    /** The one-song player a session leaves behind, playing. */
    private fun sessionPlayer() {
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns item(99)
        every { controller.currentMediaItem } returns item(99)
        every { controller.currentMediaItemIndex } returns 0
        every { controller.isPlaying } returns true
    }

    @Test fun `outside a session a refresh saves the queue, which is what the gate must stop`() {
        val repo = build()
        sessionPlayer()
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify { playbackStateStore.saveQueue(listOf(99L), any(), any(), any()) }
    }

    @Test fun `during a session the one-song player is never saved over the user's queue`() {
        val repo = build()
        sessionPlayer()
        together.setActive(true)
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 0) { playbackStateStore.saveQueue(any(), any(), any(), any()) }
        coVerify(exactly = 0) { playbackStateStore.savePosition(any(), any(), any()) }
    }

    @Test fun `during a session autoplay radio stays out of it`() {
        val repo = build(autoplay = autoplayOn)
        together.setActive(true)
        sessionPlayer()
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 0) { radioGenerator.start(any()) }
    }

    @Test fun `without a session the same last song would have started autoplay radio`() {
        val repo = build(autoplay = autoplayOn)
        sessionPlayer()
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 1) { radioGenerator.start(any()) }
    }

    @Test fun `during a session next-track prefetch does nothing`() = runTest {
        val repo = build()
        together.setActive(true)
        repo.prefetchNextTrack()
        verify(exactly = 0) { controller.nextMediaItemIndex }
    }

    @Test fun `when a session ends the saved queue comes back paused and unprepared`() {
        val plan = PlaybackResumer.ResumePlan(
            tracks = listOf(TrackEntity(id = 1, title = "15 Step", artist = "Radiohead"), TrackEntity(id = 2, title = "Reckoner", artist = "Radiohead")),
            startIndex = 1, positionMs = 44_000L, isShuffled = false, repeatMode = RepeatMode.OFF, source = PlaybackSource.Unknown,
        )
        build()
        coEvery { playbackResumer.buildResumePlan() } returns plan
        every { controller.mediaItemCount } returns 0
        together.setActive(true)
        together.setActive(false)
        shadowOf(Looper.getMainLooper()).idle()
        verify { controller.setMediaItems(any<List<MediaItem>>(), 1, 44_000L) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.PlayerRepositoryListenTogetherTest' -q`
Expected: FAIL to compile: no `listenTogether` parameter.

- [ ] **Step 3: Add the constructor parameter.** After the `autoplayRadioPreference` parameter:

```kotlin
    // Listen Together (spec 2026-09-24 §4): while a session owns the player, this class's own
    // automation and persistence stand aside. Null in hand-built tests.
    private val listenTogether: com.stash.core.media.listen.ListenTogetherController? = null,
```

and after `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)`:

```kotlin
    /** True while a Listen Together session owns the player; every session gate below reads it. */
    private val inListenTogether: Boolean get() = listenTogether?.active?.value == true
```

- [ ] **Step 4: Restore the user's queue when a session ends.** In `init`, after the `musicRepository.trackDeletions` collector:

```kotlin
        // Listen Together ended: put the user's own queue back, paused (spec §6). sessionEnds fires
        // once per session, even one that fails too fast for `active` to be observed as true.
        listenTogether?.let { together ->
            scope.launch { together.sessionEnds.collect { resumeQueue(play = false) } }
        }
```

Replace the whole of `override fun resumeLastQueue()` with:

```kotlin
    override fun resumeLastQueue() {
        // Fire-and-forget on the repository scope so a no-UI trampoline
        // activity can finish immediately while resolution + playback
        // continue. Reuses setQueue, so offline and online queues both work
        // with the same proven resolution + background-fill path.
        scope.launch { resumeQueue(play = true) }
    }

    /** Restores the persisted queue: playing ([resumeLastQueue]) or paused and unprepared (after Listen Together). */
    private suspend fun resumeQueue(play: Boolean) {
        val plan = playbackResumer.buildResumePlan()
        if (plan != null) {
            val tracks = plan.tracks.map { it.toDomain() }
            val controller = ensureController()
            controller?.shuffleModeEnabled = plan.isShuffled
            controller?.repeatMode = plan.repeatMode.toPlayerRepeatMode()
            setQueueInternal(tracks, plan.startIndex, plan.positionMs, plan.source, play)
            return
        }
        if (!play) return // after a session with nothing saved, there is nothing to put back
        // No persisted queue yet — fall back to the most recently played
        // (or most recently added) single track, matching the service's
        // onPlaybackResumption fallback.
        val fallback = trackDao.getLastPlayedTrack()
            ?: trackDao.getRecentlyAdded(1).first().firstOrNull()
        if (fallback != null) {
            setQueueInternal(listOf(fallback.toDomain()), startIndex = 0, startPositionMs = 0L)
        } else {
            Log.i(TAG, "resumeLastQueue: nothing to resume")
        }
    }
```

In `setQueueInternal`, add a last parameter `play: Boolean = true,` after `source`, and replace

```kotlin
        controller.setMediaItems(items, startInPlayable, startPositionMs)
        controller.prepare()
        controller.play()
```

with

```kotlin
        controller.setMediaItems(items, startInPlayable, startPositionMs)
        // After Listen Together the user's queue comes back paused and unprepared, like the cold-start
        // ghost: nothing resolves until they press play (Media3 prepares an idle player on play).
        if (play) {
            controller.prepare()
            controller.play()
        }
```

- [ ] **Step 5: Add the gates.** Each is one line at the named spot:

| Where | Change |
|---|---|
| library-shuffle watcher in `init` | `if (!libraryShuffleActive) return@collect` → `if (!libraryShuffleActive \|\| inListenTogether) return@collect` |
| radio watcher in `init` | `if (!radioActive) return@collect` → `if (!radioActive \|\| inListenTogether) return@collect` |
| autoplay watcher in `init` | first line inside `.collect { (state, on) ->`: `if (inListenTogether) return@collect` |
| `prefetchNextTrack()` | first line: `if (inListenTogether) return` |
| `onPlayerError(...)` in the controller listener | first line: `if (inListenTogether) return // the session reports "unavailable" and stays silent (spec §6)` |
| `MediaController.recoverOrStop()` | first line: `if (inListenTogether) return` |
| `maybeSkipOfflineStreamOnly(...)` | first line: `if (inListenTogether) return` |
| `updateState(...)`, directly above the `// Persist position for resume-on-restart` comment | `if (inListenTogether) return // the user's queue waits in PlaybackStateStore; never save the session's one song over it` |
| `play()` | `if (controller.mediaItemCount == 0) {` → `if (controller.mediaItemCount == 0 && !inListenTogether) {` |

The last one matters for the host: an empty session player plus the old "rebuild from the saved queue" fallback would turn the host's play button into "replace the room's music with my own queue".

- [ ] **Step 6: Bind it in Hilt.** Nothing to add: `ListenTogetherController` has an `@Inject` constructor and `MediaModule` binds `PlayerRepositoryImpl`, so Hilt passes the singleton. Check that the other repository tests still build with the default `null`:

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.PlayerRepository*' -q`
Expected: PASS, including the new class.

- [ ] **Step 7: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryListenTogetherTest.kt
git commit -m "feat(listen): PlayerRepositoryImpl stands aside during a session and restores the queue paused after"
```

### Task 17: `ListenTogetherPlayer`, the one place every command lands

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/listen/ListenTogetherPlayer.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/listen/ListenTogetherPlayerTest.kt`

Media3 1.9's `ForwardingSimpleBasePlayer` is the right base. Its `getState()` can rewrite the available commands, and every command arrives as one `handle…` call. `handleSeek` even says which command caused it (next, previous, a seek in the current item). A host's skip on a one-song player still reaches `handleSeek`, because `BasePlayer.seekToNext()` calls `seekTo(C.INDEX_UNSET, …, COMMAND_SEEK_TO_NEXT)` when there is no next item, as long as the command is advertised. So the host role adds the four next/previous commands, and the listener role removes every transport command. That hides the buttons in the notification and makes `MediaController` drop the calls.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.media.listen

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListenTogetherPlayerTest {
    /** A one-song player that records what reaches it. */
    private class StubPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val calls = mutableListOf<String>()
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(ImmutableList.of(MediaItemData.Builder("one").setMediaItem(MediaItem.Builder().setMediaId("1").build()).build()))
            .setPlaybackState(Player.STATE_READY)
            .build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> { calls += "playWhenReady=$playWhenReady"; return Futures.immediateVoidFuture() }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> { calls += "seek"; return Futures.immediateVoidFuture() }
    }

    private class Recorder : SessionInterceptor {
        val calls = mutableListOf<String>()
        override fun onPlay() { calls += "play" }
        override fun onPause() { calls += "pause" }
        override fun onSeek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun onNext() { calls += "next" }
        override fun onPrevious() { calls += "previous" }
        override fun onAdd(items: List<MediaItem>) { calls += "add:${items.single().mediaId}" }
        override fun onSet(items: List<MediaItem>, startIndex: Int) { calls += "set:$startIndex" }
    }

    @Test fun `a listener's headset, notification and lock-screen commands go nowhere`() {
        val stub = StubPlayer()
        val recorder = Recorder()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = false, interceptor = recorder) }
        player.play(); player.pause(); player.seekTo(5_000); player.seekToNext(); player.seekToPrevious(); player.stop()
        assertThat(recorder.calls).isEmpty()
        assertThat(stub.calls).isEmpty()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
    }

    @Test fun `a listener's add from a track menu becomes a suggestion`() {
        val recorder = Recorder()
        val player = ListenTogetherPlayer(StubPlayer()).apply { configure(isHost = false, interceptor = recorder) }
        assertThat(player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)).isTrue()
        player.addMediaItem(MediaItem.Builder().setMediaId("7").build())
        assertThat(recorder.calls).containsExactly("add:7")
    }

    @Test fun `the host's commands go to the session, never straight to the player`() {
        val stub = StubPlayer()
        val recorder = Recorder()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = true, interceptor = recorder) }
        player.play(); player.pause(); player.seekTo(90_000); player.seekToNext(); player.seekToPrevious()
        player.setMediaItems(listOf(MediaItem.Builder().setMediaId("8").build()), 0, 0L)
        assertThat(recorder.calls).containsExactly("play", "pause", "seek:90000", "next", "previous", "set:0").inOrder()
        assertThat(stub.calls).isEmpty()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.ListenTogetherPlayerTest' -q`
Expected: FAIL, unresolved `ListenTogetherPlayer`.

- [ ] **Step 3: Create `ListenTogetherPlayer.kt`**

```kotlin
package com.stash.core.media.listen

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * What the MediaSession exposes during a Listen Together session (spec §5). Every controller —
 * Now Playing via PlayerRepositoryImpl, the notification, the lock screen, headset and Bluetooth
 * buttons, Android Auto — reaches the player through here, so this is the one place that catches
 * them all. The host's commands become room messages and the local player moves only when the
 * room replies. A listener's transport commands are withdrawn entirely.
 *
 * ponytail: one instance per service, rewrapped on each session. ForwardingSimpleBasePlayer
 * listens to the wrapped player from construction and has no detach short of release().
 */
@OptIn(UnstableApi::class)
class ListenTogetherPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    private var isHost = false
    private var interceptor: SessionInterceptor? = null

    fun configure(isHost: Boolean, interceptor: SessionInterceptor?) {
        this.isHost = isHost
        this.interceptor = interceptor
        invalidateState()
    }

    fun rewrap(player: Player) = setPlayer(player)

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
        if (isHost) commands.addAll(*HOST_ADDS) else commands.removeAll(*LISTENER_REMOVES)
        return state.buildUpon().setAvailableCommands(commands.build()).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (isHost) interceptor?.let { if (playWhenReady) it.onPlay() else it.onPause() }
        return DONE
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = interceptor
        if (isHost && target != null) {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> target.onNext()
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> target.onPrevious()
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD ->
                    target.onSeek(positionMs.coerceAtLeast(0))
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> target.onSeek(0)
                else -> Unit // COMMAND_SEEK_TO_MEDIA_ITEM: the session player only ever holds one song
            }
        }
        return DONE
    }

    override fun handleStop(): ListenableFuture<*> {
        if (isHost) interceptor?.onPause()
        return DONE
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        interceptor?.onSet(mediaItems, startIndex)
        return DONE
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        interceptor?.onAdd(mediaItems)
        return DONE
    }

    // The room owns the queue, and the session owns speed (drift correction) and the single item.
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = DONE
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = DONE
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = DONE
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = DONE
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = DONE
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> = DONE

    /** Never release the service's ExoPlayer from here; the crossfade engine owns it. */
    override fun handleRelease(): ListenableFuture<*> = DONE

    private companion object {
        val DONE: ListenableFuture<*> = Futures.immediateVoidFuture()
        val HOST_ADDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        /** Everything but volume, metadata and COMMAND_CHANGE_MEDIA_ITEMS (a listener's "Suggest" rides on add). */
        val LISTENER_REMOVES = intArrayOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_MEDIA_ITEM,
        )
    }
}
```

- [ ] **Step 4: Run the test and check it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.ListenTogetherPlayerTest' -q`
Expected: PASS. If a `handle…` signature doesn't match, check `ForwardingSimpleBasePlayer` in media3-common 1.9.2 (`javap -p` on the jar in `~/.gradle/caches`) and fix the override. The design doesn't change.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/listen/ListenTogetherPlayer.kt core/media/src/test/kotlin/com/stash/core/media/listen/ListenTogetherPlayerTest.kt
git commit -m "feat(listen): ListenTogetherPlayer — one interception point for every playback command"
```

### Task 18: `DefaultSessionCatalog`

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/listen/DefaultSessionCatalog.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/listen/DefaultSessionCatalogTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.media.listen

import androidx.media3.common.MediaItem
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.Track
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultSessionCatalogTest {
    private val musicRepository: MusicRepository = mockk()
    private val trackDao: TrackDao = mockk()
    private val radio: RadioStationGenerator = mockk()
    private val catalog = DefaultSessionCatalog(musicRepository, trackDao, radio)
    private val descriptor = SharedTrack("Avril 14th", "Aphex Twin", youtubeId = "yt1")

    @Test fun `a listener plays the exact-persisted row, streamed when its download has gone`() = runTest {
        coEvery { musicRepository.ensureExactTrackPersisted(descriptor) } returns 7
        coEvery { trackDao.getById(7) } returns TrackEntity(
            id = 7, title = "Avril 14th", artist = "Aphex Twin", youtubeId = "yt1", isDownloaded = true, filePath = "/nope/missing.flac",
        )
        val item = catalog.mediaItemFor(descriptor)!!
        assertThat(item.mediaId).isEqualTo("7")
        assertThat(item.localConfiguration!!.uri.scheme).isEqualTo("stash-resolve")
    }

    @Test fun `a descriptor this phone made from its own row plays that row`() = runTest {
        coEvery { trackDao.getById(3) } returns TrackEntity(id = 3, title = "Home Row", artist = "Me")
        val made = catalog.sharedTrackFor(MediaItem.Builder().setMediaId("3").build())!!
        assertThat(catalog.mediaItemFor(made)!!.mediaId).isEqualTo("3")
        coVerify(exactly = 0) { musicRepository.ensureExactTrackPersisted(any()) }
    }

    @Test fun `radio leaves out the seed song`() = runTest {
        coEvery { radio.start(RadioSeed.Song("Avril 14th", "Aphex Twin", "yt1")) } returns (mockk<RadioSession>() to listOf(
            Track(title = "avril  14th ", artist = "Aphex Twin", youtubeId = "yt1"),
            Track(title = "Xtal", artist = "Aphex Twin", youtubeId = "yt2"),
        ))
        assertThat(catalog.radioAfter(descriptor).map { it.title }).containsExactly("Xtal")
    }

    @Test fun `a radio that fails is an empty station`() = runTest {
        coEvery { radio.start(any()) } throws java.io.IOException("offline")
        assertThat(catalog.radioAfter(descriptor)).isEmpty()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.DefaultSessionCatalogTest' -q`
Expected: FAIL, unresolved `DefaultSessionCatalog`.

- [ ] **Step 3: Create `DefaultSessionCatalog.kt`**

```kotlin
package com.stash.core.media.listen

import android.util.Log
import androidx.media3.common.MediaItem
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_YOUTUBE_ID
import com.stash.core.media.service.toAutoMediaItem
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrack
import java.io.File
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Song descriptor ↔ playable item for Listen Together (spec §4 "Which recording is played").
 * A descriptor becomes a row through the EXACT persist (YouTube id, Spotify URI, ISRC — never a
 * fuzzy title match), then plays through the normal resolver: a `stash-resolve://` placeholder
 * that looks up lossless by ISRC and YouTube by id, or the downloaded file when the exact row has
 * one.
 */
class DefaultSessionCatalog @Inject constructor(
    private val musicRepository: MusicRepository,
    private val trackDao: TrackDao,
    private val radioGenerator: RadioStationGenerator,
) : SessionCatalog {

    /**
     * Descriptors this phone built from its own rows. Such a descriptor IS that row, so it plays
     * the row directly. This matters for the host's local-only files, which carry no id the exact
     * persist could match.
     * ponytail: an in-memory LRU of 500; a miss just falls back to the exact persist.
     */
    private val own: MutableMap<SharedTrack, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<SharedTrack, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SharedTrack, Long>?) = size > 500
        },
    )

    override suspend fun mediaItemFor(track: SharedTrack): MediaItem? = withContext(Dispatchers.IO) {
        try {
            val id = own[track] ?: musicRepository.ensureExactTrackPersisted(track)
            val row = trackDao.getById(id) ?: return@withContext null
            // An exact match may be this phone's download: the same recording, so play the file.
            // A download whose file has gone streams instead of failing.
            val path = row.filePath
            val fileOk = row.isDownloaded && !path.isNullOrBlank() &&
                (path.startsWith("content://") || File(path.removePrefix("file://")).length() > 0)
            (if (fileOk) row else row.copy(isDownloaded = false)).toAutoMediaItem()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "exact persist failed for '${track.title}'", e)
            null
        }
    }

    override suspend fun sharedTrackFor(item: MediaItem): SharedTrack? = withContext(Dispatchers.IO) {
        val extras = item.mediaMetadata.extras
        val id = item.mediaId.toLongOrNull()?.takeIf { it > 0 } ?: extras?.getLong(EXTRA_TRACK_ID, -1L)?.takeIf { it > 0 }
        id?.let { trackDao.getById(it) }?.let { row ->
            return@withContext row.toDomain().toSharedTrack().also { own[it] = row.id }
        }
        // Radio and search rows can carry synthetic ids with no Room row: fall back to the metadata.
        val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val artist = item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: return@withContext null
        SharedTrack(
            title = title,
            artist = artist,
            durationMs = extras?.getLong(EXTRA_TRACK_DURATION_MS, 0L)?.takeIf { it > 0 },
            youtubeId = extras?.getString(EXTRA_TRACK_YOUTUBE_ID),
        )
    }

    override suspend fun radioAfter(track: SharedTrack): List<SharedTrack> = try {
        val (_, batch) = radioGenerator.start(RadioSeed.Song(track.title, track.artist, track.youtubeId))
        // Drop the seed itself, matched by title and artist like PlayerRepositoryImpl.startRadio's keepCurrent.
        val seed = identity(track.title, track.artist)
        batch.filter { identity(it.title, it.artist) != seed }.map { it.toSharedTrack() }.take(MAX_STATION)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "radio for '${track.title}' failed", e)
        emptyList()
    }

    private fun identity(title: String, artist: String): String {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        return norm(title) + "|" + norm(artist)
    }

    private companion object {
        const val TAG = "ListenTogether"
        const val MAX_STATION = 200
    }
}
```

- [ ] **Step 4: Run the test and check it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.DefaultSessionCatalogTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/listen/DefaultSessionCatalog.kt core/media/src/test/kotlin/com/stash/core/media/listen/DefaultSessionCatalogTest.kt
git commit -m "feat(listen): session catalog — exact-persisted playback, own-row descriptors, radio without the seed"
```

### Task 19: `ListenTogetherSession`, the orchestration (joining and listening)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/listen/ListenTogetherSession.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/listen/ListenTogetherSessionTest.kt`

This task writes the whole class. Its tests cover joining and listening; Task 20 adds the hosting tests.

**The rules it implements:**
- **Start:** the user's queue is set aside (`setActive(true)`, then `saveUserPosition`, then `enterSession`) before anything touches the player.
- **Song change** (a `prepare`, or a `welcome`/`state` whose `trackKey` differs from the loaded one): exact persist → `load` paused → `status ready` once the player reports READY.
- **Timeline:** start at `atRoomMs` if it's still ahead. Otherwise (a mid-song join or a late ready) pick the next whole room second at least 1 s ahead and seek to where the song will be then (spec §4). A timeline identical to the one already applied changes nothing, so a reconnect or a `state` doesn't restart the song.
- **Drift:** one tick a second through `DriftController`; report `drifting` and recover.
- **Host:** transport becomes room messages; song boundaries come from the host's queue, then from autoplay radio once per song; see Task 20.
- **Leave, end, ended, give up:** `exitSession` → `setActive(false)`, and the repository restores the queue.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.media.listen

import androidx.media3.common.MediaItem
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.listen.RoomConnection
import com.stash.core.data.listen.RoomEvent
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomPhase
import com.stash.core.model.listen.RoomState
import com.stash.core.model.listen.RoomTimeline
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.share.SharedTrack
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ListenTogetherSessionTest {
    val track = SharedTrack("Avril 14th", "Aphex Twin", durationMs = 125_000, isrc = "GBBPW0100025")
    val next = SharedTrack("Xtal", "Aphex Twin")
    val third = SharedTrack("Rhubarb", "Aphex Twin")
    fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    class FakePlayer : SessionPlayer {
        val calls = mutableListOf<String>()
        var interceptor: SessionInterceptor? = null
        var queue = UserQueue(null, emptyList(), 0)
        override var positionMs = 0L
        override var durationMs: Long? = 125_000L
        override var events: SessionPlayerEvents? = null
        override fun userQueue() = queue
        override suspend fun saveUserPosition() { calls += "save" }
        override fun enterSession(isHost: Boolean, interceptor: SessionInterceptor) {
            calls += if (isHost) "enter:host" else "enter:listener"
            this.interceptor = interceptor
        }
        override fun exitSession() { calls += "exit" }
        override fun load(item: MediaItem, positionMs: Long) { calls += "load:${item.mediaId}@$positionMs" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs"; this.positionMs = positionMs }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun setSpeed(speed: Float) { if (speed != 1f) calls += "speed:$speed" }
    }

    class FakeConnection : RoomConnection {
        val sent = mutableListOf<ClientMessage>()
        val incoming = Channel<RoomEvent>(Channel.UNLIMITED)
        var closed = false
        var hello: (() -> ClientMessage.Hello)? = null
        override val events: Flow<RoomEvent> = incoming.receiveAsFlow()
        override fun send(message: ClientMessage): Boolean { sent += message; return true }
        override fun close() { closed = true }
    }

    inner class FakeCatalog : SessionCatalog {
        val items = mutableMapOf(track to item("1"), next to item("2"), third to item("3"))
        var radio: List<SharedTrack> = emptyList()
        var radioCalls = 0
        override suspend fun mediaItemFor(track: SharedTrack) = items[track]
        override suspend fun sharedTrackFor(item: MediaItem) = items.entries.firstOrNull { it.value.mediaId == item.mediaId }?.key
        override suspend fun radioAfter(track: SharedTrack): List<SharedTrack> { radioCalls++; return radio }
    }

    val player = FakePlayer()
    val connection = FakeConnection()
    val catalog = FakeCatalog()
    val api: RoomApiClient = mockk()
    val controller = ListenTogetherController(mockk(relaxed = true)).apply { serviceAttached = true }
    var autoplay = false

    fun TestScope.session() = ListenTogetherSession(
        scope = backgroundScope,
        controller = controller,
        player = player,
        catalog = catalog,
        api = api,
        connector = { _, _, hello -> connection.hello = hello; connection },
        autoplayRadio = { autoplay },
        displayName = { "Rawn" },
        clock = { testScheduler.currentTime },
    ).also { it.start() }

    fun state(
        host: String? = "h",
        track: SharedTrack? = this.track,
        key: Int = 1,
        timeline: RoomTimeline = RoomTimeline(0, 0, false),
        queue: List<SharedTrack> = emptyList(),
    ) = RoomState(
        rev = 1, host = host, track = track, trackKey = key, timeline = timeline, queue = queue,
        members = listOf(RoomMember("h", "Host", 1, "ok"), RoomMember("me", "Me", 2, "ok")), phase = RoomPhase(),
    )

    fun TestScope.receive(m: ServerMessage) { connection.incoming.trySend(RoomEvent.Message(m)); runCurrent() }

    /** A pong with no round trip: room time = local time + 10 s from here on. */
    fun TestScope.syncClock() = receive(ServerMessage.Pong(c = testScheduler.currentTime, r = testScheduler.currentTime + 10_000))

    fun TestScope.join() {
        session()
        controller.send(Command.Join("K7QA2P")); runCurrent()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
    }

    @Test fun `joining sets the user's queue aside before touching the player`() = runTest {
        join()
        assertThat(controller.active.value).isTrue()
        assertThat(player.calls.take(3)).containsExactly("save", "enter:listener", "pause").inOrder()
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn"))
    }

    @Test fun `a prepared song is loaded paused and ready is reported once the player can play it`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(track = null, key = 0)))
        receive(ServerMessage.Prepare(trackKey = 1, track = track, positionMs = 0, deadlineMs = 8_000))
        assertThat(player.calls).contains("load:1@0")
        assertThat(connection.sent.filterIsInstance<ClientMessage.Status>()).isEmpty()
        player.events!!.onReady()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Status>()).containsExactly(ClientMessage.Status("ready", 1))
        assertThat(connection.hello!!().resumeToken).isEqualTo("tok")
    }

    @Test fun `a start time in the future starts the player exactly then`() = runTest {
        join(); syncClock()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        player.events!!.onReady()
        player.calls.clear()
        receive(ServerMessage.TimelineUpdate(rev = 2, trackKey = 1, positionMs = 30_000, atRoomMs = 10_500, playing = true))
        assertThat(player.calls).containsExactly("pause", "seek:30000").inOrder()
        advanceTimeBy(499); runCurrent()
        assertThat(player.calls).doesNotContain("play")
        advanceTimeBy(1); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
    }

    @Test fun `joining mid-song starts on the next whole room second from where the song will be then`() = runTest {
        join(); advanceTimeBy(200); runCurrent(); syncClock() // local 200 = room 10 200
        receive(ServerMessage.Welcome("me", "tok", state(key = 1, timeline = RoomTimeline(positionMs = 0, atRoomMs = 5_000, playing = true))))
        player.events!!.onReady()
        // the first whole second at least 1 s ahead of room 10 200 is 12 000; the song is then at 12 000 − 5 000
        assertThat(player.calls).contains("seek:7000")
        advanceTimeBy(1_799); runCurrent() // local 1 999 = room 11 999
        assertThat(player.calls).doesNotContain("play")
        advanceTimeBy(1); runCurrent()
        assertThat(player.calls.last()).isEqualTo("play")
    }

    @Test fun `a reconnect keeps playing and an unchanged timeline doesn't restart the song`() = runTest {
        join(); syncClock()
        val playing = state(key = 1, timeline = RoomTimeline(0, 10_000, true))
        receive(ServerMessage.Welcome("me", "tok", playing))
        player.events!!.onReady()
        advanceTimeBy(1_000); runCurrent()
        player.calls.clear()
        connection.incoming.trySend(RoomEvent.Reconnecting); runCurrent()
        assertThat((controller.state.value as ListenTogetherState.InRoom).reconnecting).isTrue()
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("me", "tok", playing.copy(rev = 7)))
        assertThat(player.calls).isEmpty()
        assertThat((controller.state.value as ListenTogetherState.InRoom).reconnecting).isFalse()
    }

    @Test fun `a listener's transport commands reach neither the room nor the player`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        val before = player.calls.toList()
        connection.sent.clear()
        player.interceptor!!.run {
            onPlay(); onPause(); onSeek(5_000); onNext(); onPrevious(); onSet(listOf(item("2")), 0)
        }
        runCurrent()
        assertThat(connection.sent).isEmpty()
        assertThat(player.calls).isEqualTo(before)
    }

    @Test fun `a listener's add from a track menu is sent as a suggestion`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        connection.sent.clear()
        player.interceptor!!.onAdd(listOf(item("2"))); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Suggest(next))
    }

    @Test fun `a song that can't be resolved is reported unavailable and shown`() = runTest {
        catalog.items.remove(track)
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        assertThat(connection.sent.filterIsInstance<ClientMessage.Status>()).containsExactly(ClientMessage.Status("unavailable", 1))
        assertThat((controller.state.value as ListenTogetherState.InRoom).unavailable).isTrue()
    }

    @Test fun `a player error mid-song is reported unavailable too`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        player.events!!.onError()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Status>().last()).isEqualTo(ClientMessage.Status("unavailable", 1))
    }

    @Test fun `leaving puts everything back`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        controller.send(Command.Leave); runCurrent()
        assertThat(player.calls.last()).isEqualTo("exit")
        assertThat(connection.closed).isTrue()
        assertThat(controller.active.value).isFalse()
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
    }

    @Test fun `when the room ends everyone gets their own music back and a notice`() = runTest {
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { notices += it } }
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        receive(ServerMessage.Ended("host_ended"))
        assertThat(player.calls.last()).isEqualTo("exit")
        assertThat(controller.active.value).isFalse()
        assertThat(notices).containsExactly("Session ended")
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.ListenTogetherSessionTest' -q`
Expected: FAIL, unresolved `ListenTogetherSession`.

- [ ] **Step 3: Create `ListenTogetherSession.kt`**

```kotlin
package com.stash.core.media.listen

import android.util.Log
import androidx.media3.common.MediaItem
import com.stash.core.data.listen.CloseReason
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.listen.RoomConnection
import com.stash.core.data.listen.RoomConnector
import com.stash.core.data.listen.RoomEvent
import com.stash.core.data.share.ShareResult
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomState
import com.stash.core.model.listen.RoomTimeline
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Listen Together engine (spec docs/superpowers/specs/2026-09-24-listen-together-design.md §4–§6).
 * One per playback service, on the service's main-thread scope. Reads [ListenTogetherController]'s
 * commands, talks to the room through a [RoomConnection], and drives the service's player through
 * [SessionPlayer]. Nobody's player advances on its own: every song change comes from the room.
 */
class ListenTogetherSession(
    private val scope: CoroutineScope,
    private val controller: ListenTogetherController,
    private val player: SessionPlayer,
    private val catalog: SessionCatalog,
    private val api: RoomApiClient,
    private val connector: RoomConnector,
    private val autoplayRadio: suspend () -> Boolean,
    private val displayName: suspend () -> String?,
    /** Monotonic local time in ms (SystemClock.elapsedRealtime in the service). */
    private val clock: () -> Long,
) {
    private var connection: RoomConnection? = null
    private var eventsJob: Job? = null
    private var pingJob: Job? = null
    private var startJob: Job? = null
    private var driftJob: Job? = null

    private var code: String? = null
    private var url: String? = null
    private var myId: String? = null
    private var token: String? = null
    private var room: RoomState? = null
    private var isHost = false
    private var pendingLoad: ClientMessage.Load? = null

    private val clockSync = ClockSync()
    private val drift = DriftController()
    private var timeline: ServerMessage.TimelineUpdate? = null
    private var applied: ServerMessage.TimelineUpdate? = null
    private var loadedKey = NONE
    private var readyKey = NONE
    private var unavailableKey = NONE
    private var lastStatus: String? = null
    private var reconnecting = false
    private var versionMismatch = false
    private val history = ArrayDeque<SharedTrack>()
    private var radioTriedKey = NONE

    fun start(): Job = scope.launch { for (command in controller.commands) handle(command) }

    /** The service is going away: leave quietly. */
    fun shutdown() = teardown(message = null)

    private suspend fun handle(command: Command) {
        when (command) {
            Command.Host -> if (code == null) host()
            is Command.Join -> {
                if (command.code == code) return
                if (code != null) leave()
                join(command.code)
            }
            Command.Leave -> leave()
            Command.End -> end()
            is Command.React -> send(ClientMessage.React(command.emoji))
            is Command.Suggestion -> send(ClientMessage.SuggestionAction(command.id, if (command.add) "add" else "dismiss"))
            is Command.MakeHost -> send(ClientMessage.MakeHost(command.memberId))
        }
    }

    // ── Starting ──────────────────────────────────────────────────────────────

    private suspend fun host() {
        controller.publish(ListenTogetherState.Connecting(hosting = true))
        val name = displayName()
        val created = (api.create(name) as? ShareResult.Ok)?.value
        if (created == null) {
            controller.publish(ListenTogetherState.Idle)
            controller.message("Couldn't start a session. Check your connection and try again.")
            return
        }
        // Read the user's queue before the session touches the player: it becomes the room's (spec §4).
        val mine = player.userQueue()
        val first = mine.current?.let { catalog.sharedTrackFor(it) }
        val upcoming = mine.upcoming.take(MAX_QUEUE).mapNotNull { catalog.sharedTrackFor(it) }
        pendingLoad = first?.let { ClientMessage.Load(it, mine.positionMs, upcoming) }
        enter(created.code, created.url, name, hostKey = created.hostKey, asHost = true)
    }

    private suspend fun join(code: String) {
        controller.publish(ListenTogetherState.Connecting(hosting = false))
        enter(code, ShareLinks.roomUrl(code), displayName(), hostKey = null, asHost = false)
    }

    private suspend fun enter(code: String, url: String, name: String?, hostKey: String?, asHost: Boolean) {
        this.code = code
        this.url = url
        isHost = asHost
        controller.setActive(true) // gates PlayerRepositoryImpl's saves before anything moves
        player.saveUserPosition()
        player.events = playerEvents
        player.enterSession(asHost, interceptor)
        player.pause()
        Log.i(TAG, "entering room ${code.take(2)}… as ${if (asHost) "host" else "listener"}")
        // The host key goes out only until the room hands back a token (spec §2–§3).
        val conn = connector.connect(code, scope) { ClientMessage.Hello(name, hostKey.takeIf { token == null }, token) }
        connection = conn
        eventsJob = scope.launch { conn.events.collect { onEvent(it) } }
    }

    // ── The room ──────────────────────────────────────────────────────────────

    private suspend fun onEvent(event: RoomEvent) {
        when (event) {
            RoomEvent.Connected -> { reconnecting = false; publish(); startPings() }
            RoomEvent.Reconnecting -> { reconnecting = true; publish() } // keep playing from the last timeline (§6)
            is RoomEvent.Closed -> teardown(
                when (event.reason) {
                    CloseReason.ENDED -> "Session ended"
                    CloseReason.FULL -> "This session is full"
                    CloseReason.GAVE_UP -> "Lost connection to the session"
                },
            )
            is RoomEvent.Message -> onMessage(event.message)
        }
    }

    private suspend fun onMessage(m: ServerMessage) {
        when (m) {
            is ServerMessage.Welcome -> {
                myId = m.memberId
                token = m.token
                applyState(m.state)
                if (isHost) pendingLoad?.let { load(it.track, it.positionMs, it.queue) }
                pendingLoad = null
            }
            is ServerMessage.StateSync -> applyState(m.state)
            is ServerMessage.Pong -> {
                val first = clockSync.offsetMs == null
                clockSync.onPong(m.c, m.r, clock())
                if (first) applyTimeline()
            }
            is ServerMessage.TimelineUpdate -> onTimeline(m)
            is ServerMessage.Prepare -> {
                room = room?.copy(track = m.track, trackKey = m.trackKey)
                timeline = null
                prepare(m.trackKey, m.track, m.positionMs)
            }
            is ServerMessage.Members -> { room = room?.copy(members = m.members); publish() }
            is ServerMessage.Suggestions -> { room = room?.copy(suggestions = m.suggestions); publish() }
            is ServerMessage.Reaction -> controller.reaction(m)
            is ServerMessage.Ended -> teardown("Session ended")
        }
    }

    private suspend fun applyState(s: RoomState) {
        val wasHost = isHost
        room = s
        isHost = s.host != null && s.host == myId
        if (isHost != wasHost) player.enterSession(isHost, interceptor) // makeHost or a 60 s handover
        publish()
        val track = s.track ?: return
        if (s.trackKey != loadedKey) prepare(s.trackKey, track, s.timeline.positionMs)
        onTimeline(ServerMessage.TimelineUpdate(s.rev, s.trackKey, s.timeline.positionMs, s.timeline.atRoomMs, s.timeline.playing))
    }

    // ── One song ──────────────────────────────────────────────────────────────

    private suspend fun prepare(key: Int, track: SharedTrack, positionMs: Long) {
        if (key == loadedKey) return
        loadedKey = key
        readyKey = NONE
        unavailableKey = NONE
        lastStatus = null
        versionMismatch = false
        applied = null
        startJob?.cancel()
        stopDrift()
        player.pause()
        publish()
        Log.i(TAG, "prepare #$key '${track.title}'")
        val item = catalog.mediaItemFor(track)
        if (key != loadedKey) return // a newer song arrived while this one was being looked up
        if (item == null) { markUnavailable(key); return }
        player.load(item, positionMs)
    }

    private val playerEvents = object : SessionPlayerEvents {
        override fun onReady() {
            val key = loadedKey
            if (key == NONE || key == unavailableKey) return
            if (readyKey != key) {
                readyKey = key
                val want = room?.track?.durationMs
                val have = player.durationMs
                versionMismatch = want != null && have != null && abs(have - want) > VERSION_TOLERANCE_MS
                report(READY, force = true)
                publish()
                applyTimeline()
            } else if (lastStatus == BUFFERING) {
                report(READY)
            }
        }

        override fun onBuffering() {
            if (readyKey != NONE && readyKey == loadedKey && timeline?.playing == true) report(BUFFERING)
        }

        override fun onEnded() {
            if (isHost) scope.launch { advance() }
        }

        override fun onError() {
            if (loadedKey != NONE) markUnavailable(loadedKey)
        }
    }

    /** "This song isn't available to you": report it, stay silent, wait for the next prepare (spec §6). */
    private fun markUnavailable(key: Int) {
        if (unavailableKey == key) return
        unavailableKey = key
        startJob?.cancel()
        stopDrift()
        player.pause()
        report(UNAVAILABLE, force = true)
        publish()
    }

    private fun onTimeline(t: ServerMessage.TimelineUpdate) {
        val current = timeline
        if (current != null && current.trackKey == t.trackKey && t.rev < current.rev) return // out of order
        timeline = t
        room = room?.copy(timeline = RoomTimeline(t.positionMs, t.atRoomMs, t.playing))
        applyTimeline()
    }

    private fun applyTimeline() {
        val t = timeline ?: return
        if (t.trackKey != readyKey || t.trackKey == unavailableKey) return // the ready handler comes back here
        if (applied?.sameAs(t) == true) return // e.g. a state after a reconnect: keep playing
        val roomNow = clockSync.roomNow(clock())
        if (t.playing && roomNow == null) return // the first pong comes back here
        applied = t
        startJob?.cancel()
        stopDrift()
        player.pause()
        if (!t.playing) {
            player.seekTo(t.positionMs)
            return
        }
        // Start at atRoomMs while it's still ahead. Otherwise (a mid-song join or a late ready)
        // start on the next whole room second at least JOIN_LEAD_MS away, from where the song will
        // be at that moment, not where it was when we arrived (spec §4).
        val startAt = if (t.atRoomMs > roomNow!!) t.atRoomMs else nextWholeSecond(roomNow + JOIN_LEAD_MS)
        player.seekTo(t.positionMs + (startAt - t.atRoomMs))
        startJob = scope.launch {
            val wait = startAt - (clockSync.roomNow(clock()) ?: startAt)
            if (wait > 0) delay(wait)
            player.play()
            Log.i(TAG, "start #${t.trackKey} at room $startAt")
            startDrift()
        }
    }

    private fun startDrift() {
        driftJob?.cancel()
        driftJob = scope.launch {
            while (true) {
                delay(DRIFT_TICK_MS)
                val t = timeline ?: return@launch
                if (!t.playing || t.trackKey != readyKey) return@launch
                val roomNow = clockSync.roomNow(clock()) ?: continue
                val expected = t.positionMs + (roomNow - t.atRoomMs)
                val duration = player.durationMs
                if (duration != null && expected >= duration) continue // past the end: the host moves the room on
                val result = drift.onTick(player.positionMs - expected, clock())
                when (val action = result.action) {
                    DriftAction.None -> Unit
                    is DriftAction.Speed -> player.setSpeed(action.speed)
                    DriftAction.Seek -> player.seekTo(expected)
                }
                if (result.drifting) report(DRIFTING) else if (lastStatus == DRIFTING) report(READY)
            }
        }
    }

    private fun stopDrift() {
        driftJob?.cancel()
        driftJob = null
        drift.reset()
        player.setSpeed(1f)
    }

    private fun startPings() {
        pingJob?.cancel()
        pingJob = scope.launch {
            repeat(FIRST_PINGS) { send(ClientMessage.Ping(clock())); delay(FIRST_PING_GAP_MS) }
            while (true) { delay(PING_EVERY_MS); send(ClientMessage.Ping(clock())) }
        }
    }

    private fun report(status: String, force: Boolean = false) {
        if (!force && status == lastStatus) return
        lastStatus = status
        send(ClientMessage.Status(status, loadedKey.takeIf { it != NONE }))
    }

    // ── Hosting ───────────────────────────────────────────────────────────────

    private val interceptor = object : SessionInterceptor {
        override fun onPlay() { if (isHost) send(ClientMessage.Play) }
        override fun onPause() { if (isHost) send(ClientMessage.Pause) }
        override fun onSeek(positionMs: Long) { if (isHost) send(ClientMessage.Seek(positionMs)) }
        override fun onNext() { if (isHost) scope.launch { advance() } }
        override fun onPrevious() { if (isHost) previous() }
        override fun onAdd(items: List<MediaItem>) { scope.launch { add(items) } }
        override fun onSet(items: List<MediaItem>, startIndex: Int) {
            if (!isHost) {
                controller.message("Leave the session to play something else")
                return
            }
            scope.launch { replace(items, startIndex) }
        }
    }

    /** The song ended or the host skipped: the next queued song, else autoplay radio once per song, else idle (§4). */
    private suspend fun advance() {
        val r = room ?: return
        val current = r.track
        val next = r.queue.firstOrNull()
        if (next != null) {
            current?.let(::remember)
            load(next, 0, r.queue.drop(1))
            return
        }
        // Read the preference itself: shouldAutoplayRadio only says yes while a song is still playing.
        if (current != null && radioTriedKey != r.trackKey && autoplayRadio()) {
            radioTriedKey = r.trackKey
            val station = catalog.radioAfter(current)
            if (station.isNotEmpty()) {
                remember(current)
                load(station.first(), 0, station.drop(1))
                return
            }
        }
        if (timeline?.playing == true) send(ClientMessage.Pause) // idle, paused at the end of the last song
    }

    private fun previous() {
        val r = room ?: return
        if (player.positionMs > RESTART_THRESHOLD_MS || history.isEmpty()) {
            send(ClientMessage.Seek(0))
            return
        }
        load(history.removeLast(), 0, listOfNotNull(r.track) + r.queue)
    }

    private suspend fun add(items: List<MediaItem>) {
        val tracks = items.take(MAX_QUEUE).mapNotNull { catalog.sharedTrackFor(it) }
        if (tracks.isEmpty()) return
        if (isHost) {
            setQueue((room?.queue.orEmpty() + tracks).take(MAX_QUEUE))
        } else {
            tracks.take(MAX_SUGGESTIONS).forEach { send(ClientMessage.Suggest(it)) }
            controller.message("Suggested to the host")
        }
    }

    private suspend fun replace(items: List<MediaItem>, startIndex: Int) {
        val index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val first = items.getOrNull(index)?.let { catalog.sharedTrackFor(it) } ?: return
        val rest = items.drop(index + 1).take(MAX_QUEUE).mapNotNull { catalog.sharedTrackFor(it) }
        room?.track?.let(::remember)
        load(first, 0, rest)
    }

    private fun load(track: SharedTrack, positionMs: Long, queue: List<SharedTrack>) {
        room = room?.copy(queue = queue)
        send(ClientMessage.Load(track, positionMs, queue))
    }

    private fun setQueue(queue: List<SharedTrack>) {
        room = room?.copy(queue = queue)
        send(ClientMessage.Queue(queue))
    }

    private fun remember(track: SharedTrack) {
        history.addLast(track)
        if (history.size > MAX_HISTORY) history.removeFirst()
    }

    // ── Leaving ───────────────────────────────────────────────────────────────

    private fun leave() {
        val r = room
        if (isHost && r != null) {
            // Hand the room to the longest-joined listener instead of leaving it hostless for 60 s.
            r.members.filter { it.id != myId }.minByOrNull { it.joinedAt }?.let { send(ClientMessage.MakeHost(it.id)) }
        }
        teardown(message = null)
    }

    private fun end() {
        if (isHost) send(ClientMessage.End)
        teardown("Session ended")
    }

    private fun teardown(message: String?) {
        if (code == null) return
        Log.i(TAG, "leaving the room${message?.let { " ($it)" }.orEmpty()}")
        eventsJob?.cancel()
        pingJob?.cancel()
        startJob?.cancel()
        stopDrift()
        connection?.close()
        connection = null
        player.events = null
        player.exitSession()
        code = null; url = null; myId = null; token = null; room = null; isHost = false; pendingLoad = null
        timeline = null; applied = null; loadedKey = NONE; readyKey = NONE; unavailableKey = NONE; lastStatus = null
        reconnecting = false; versionMismatch = false; history.clear(); radioTriedKey = NONE
        clockSync.reset()
        controller.setActive(false) // PlayerRepositoryImpl puts the user's queue back, paused
        controller.publish(ListenTogetherState.Idle)
        message?.let(controller::message)
    }

    private fun send(message: ClientMessage) {
        connection?.send(message)
    }

    private fun publish() {
        val code = code ?: return
        val url = url ?: return
        val r = room
        val id = myId
        if (r == null || id == null) {
            controller.publish(ListenTogetherState.Connecting(hosting = isHost))
            return
        }
        controller.publish(
            ListenTogetherState.InRoom(
                code = code, url = url, myId = id, isHost = isHost, hostId = r.host,
                members = r.members, suggestions = r.suggestions, reconnecting = reconnecting,
                unavailable = unavailableKey != NONE && unavailableKey == loadedKey,
                versionMismatch = versionMismatch,
            ),
        )
    }

    private fun ServerMessage.TimelineUpdate.sameAs(o: ServerMessage.TimelineUpdate) =
        trackKey == o.trackKey && positionMs == o.positionMs && atRoomMs == o.atRoomMs && playing == o.playing

    private companion object {
        const val TAG = "ListenTogether"
        const val NONE = -1
        const val MAX_QUEUE = 200
        const val MAX_SUGGESTIONS = 3
        const val MAX_HISTORY = 50
        const val JOIN_LEAD_MS = 1_000L
        const val DRIFT_TICK_MS = 1_000L
        const val FIRST_PINGS = 5
        const val FIRST_PING_GAP_MS = 200L
        const val PING_EVERY_MS = 30_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val VERSION_TOLERANCE_MS = 2_000L
        const val READY = "ready"
        const val BUFFERING = "buffering"
        const val UNAVAILABLE = "unavailable"
        const val DRIFTING = "drifting"

        fun nextWholeSecond(ms: Long): Long = (ms + 999) / 1000 * 1000
    }
}
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.ListenTogetherSessionTest' -q`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/listen/ListenTogetherSession.kt core/media/src/test/kotlin/com/stash/core/media/listen/ListenTogetherSessionTest.kt
git commit -m "feat(listen): ListenTogetherSession — ready handshake, timed starts, mid-song join, drift, leave"
```

### Task 20: Hosting tests for `ListenTogetherSession`

**Files:**
- Modify: `core/media/src/test/kotlin/com/stash/core/media/listen/ListenTogetherSessionTest.kt`

These pin the host half of Task 19's class: the room is created from the user's queue, commands become room messages, song boundaries, radio once per song, previous, adding, and the hand-over on leaving.

- [ ] **Step 1: Add the tests** inside `ListenTogetherSessionTest`, plus these imports:

```kotlin
import com.stash.core.data.share.ShareResult
import io.mockk.coEvery
```

```kotlin
    fun TestScope.host(upcoming: List<MediaItem> = listOf(item("2"))) {
        coEvery { api.create("Rawn") } returns ShareResult.Ok(RoomApiClient.Created("K7QA2P", "KEY", "https://x/l/K7QA2P"))
        player.queue = UserQueue(current = item("1"), upcoming = upcoming, positionMs = 42_000)
        session()
        controller.send(Command.Host); runCurrent()
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", hostKey = "KEY"))
        connection.incoming.trySend(RoomEvent.Connected); runCurrent()
        receive(ServerMessage.Welcome("h", "tok", state(host = "h", track = null, key = 0)))
    }

    @Test fun `hosting sets the queue aside and sends the user's song and queue to the new room`() = runTest {
        host()
        assertThat(player.calls.take(3)).containsExactly("save", "enter:host", "pause").inOrder()
        assertThat(connection.sent.filterIsInstance<ClientMessage.Load>()).containsExactly(ClientMessage.Load(track, 42_000, listOf(next)))
        assertThat(connection.hello!!()).isEqualTo(ClientMessage.Hello(name = "Rawn", resumeToken = "tok"))
        assertThat((controller.state.value as ListenTogetherState.InRoom).url).isEqualTo("https://x/l/K7QA2P")
    }

    @Test fun `a room that can't be created leaves everything alone`() = runTest {
        coEvery { api.create(any()) } returns ShareResult.Failed("offline")
        session()
        controller.send(Command.Host); runCurrent()
        assertThat(player.calls).isEmpty()
        assertThat(controller.active.value).isFalse()
        assertThat(controller.state.value).isEqualTo(ListenTogetherState.Idle)
    }

    @Test fun `the host's own controls go to the room and its player waits for the reply`() = runTest {
        host()
        player.calls.clear(); connection.sent.clear()
        player.interceptor!!.run { onPause(); onPlay(); onSeek(90_000) }
        assertThat(connection.sent).containsExactly(ClientMessage.Pause, ClientMessage.Play, ClientMessage.Seek(90_000)).inOrder()
        assertThat(player.calls).isEmpty()
    }

    @Test fun `when a song ends the host loads the next one from its queue`() = runTest {
        host()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.events!!.onEnded(); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, emptyList()))
    }

    @Test fun `with the queue empty and autoplay radio on the host starts a station`() = runTest {
        autoplay = true
        catalog.radio = listOf(next, third)
        host(upcoming = emptyList())
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.events!!.onEnded(); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, listOf(third)))
    }

    @Test fun `a station that can't be built isn't retried for the same song, and the room pauses`() = runTest {
        autoplay = true
        host(upcoming = emptyList())
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        receive(ServerMessage.TimelineUpdate(2, 1, 42_000, 0, true))
        connection.sent.clear()
        player.events!!.onEnded(); runCurrent()
        player.events!!.onEnded(); runCurrent()
        assertThat(catalog.radioCalls).isEqualTo(1)
        assertThat(connection.sent.filterIsInstance<ClientMessage.Pause>()).isNotEmpty()
    }

    @Test fun `previous restarts the song once it is more than 3 s in`() = runTest {
        host()
        receive(ServerMessage.Prepare(1, track, 42_000, 8_000))
        connection.sent.clear()
        player.positionMs = 10_000
        player.interceptor!!.onPrevious()
        assertThat(connection.sent).containsExactly(ClientMessage.Seek(0))
    }

    @Test fun `the host's add from a track menu joins the room's queue`() = runTest {
        host()
        connection.sent.clear()
        player.interceptor!!.onAdd(listOf(item("3"))); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Queue(listOf(next, third)))
    }

    @Test fun `tapping a playlist while hosting plays it for everyone`() = runTest {
        host()
        connection.sent.clear()
        player.interceptor!!.onSet(listOf(item("2"), item("3")), 0); runCurrent()
        assertThat(connection.sent).containsExactly(ClientMessage.Load(next, 0, listOf(third)))
    }

    @Test fun `a host who leaves hands the room to the longest-joined listener`() = runTest {
        host()
        receive(ServerMessage.Members(listOf(RoomMember("h", "Host", 1), RoomMember("a", "A", 5), RoomMember("b", "B", 3))))
        controller.send(Command.Leave); runCurrent()
        assertThat(connection.sent).contains(ClientMessage.MakeHost("b"))
        assertThat(connection.closed).isTrue()
    }

    @Test fun `end session tells the room and restores the host's own music`() = runTest {
        host()
        controller.send(Command.End); runCurrent()
        assertThat(connection.sent).contains(ClientMessage.End)
        assertThat(player.calls.last()).isEqualTo("exit")
        assertThat(controller.active.value).isFalse()
    }

    @Test fun `becoming host through a state message switches the player to the host role`() = runTest {
        join()
        receive(ServerMessage.Welcome("me", "tok", state(key = 1)))
        receive(ServerMessage.StateSync(state(host = "me", key = 1)))
        assertThat(player.calls).contains("enter:host")
        assertThat((controller.state.value as ListenTogetherState.InRoom).isHost).isTrue()
    }
```

- [ ] **Step 2: Run the tests and check they pass**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.listen.ListenTogetherSessionTest' -q`
Expected: PASS (23 tests). These pin code written in Task 19, so a failure here is a bug in that code. Fix it there.

- [ ] **Step 3: Commit**

```bash
git add core/media/src/test/kotlin/com/stash/core/media/listen/ListenTogetherSessionTest.kt
git commit -m "test(listen): host half of the session — room from my queue, commands to the room, song boundaries, radio once"
```

### Task 21: Wire the session into `StashPlaybackService`

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt`
- Create: `core/media/src/main/res/drawable/ic_listen_leave.xml`
- Test: `core/media/src/test/kotlin/com/stash/core/media/service/StashPlaybackServiceCrossfadeOverrideTest.kt`

This task wires in the player abilities (§5):
- `prepareAt` → `load`, and `setSpeed`;
- queue set-aside, via `saveUserPosition` plus the repository restore;
- the crossfade in-memory override;
- the `ListenTogetherPlayer` in the `MediaSession`;
- a foreground notification that survives pauses ("Listening together" in its text);
- suppression of `performIdleStop` and the `onTaskRemoved` stop;
- a **Leave** notification button (only Leave for a listener).

- [ ] **Step 1: Write the failing test** (it uses the same no-`onCreate` Robolectric pattern as `StashPlaybackServiceLoudnessTest`)

```kotlin
package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.CrossfadePreference
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StashPlaybackServiceCrossfadeOverrideTest {
    private val crossfadePreference = mockk<CrossfadePreference>(relaxed = true)

    private fun newService() = Robolectric.buildService(StashPlaybackService::class.java).get()
        .also { it.crossfadePreference = crossfadePreference }

    @Test fun `a session suspends crossfade in memory and restores it, never writing the preference`() {
        val service = newService()
        service.onCrossfadePreference(true)
        assertThat(service.crossfadeActive).isTrue()
        service.setCrossfadeSuspended(true)
        assertThat(service.crossfadeActive).isFalse()
        service.onCrossfadePreference(true) // a preference re-emit mid-session doesn't undo the suspension
        assertThat(service.crossfadeActive).isFalse()
        service.setCrossfadeSuspended(false)
        assertThat(service.crossfadeActive).isTrue()
        coVerify(exactly = 0) { crossfadePreference.setEnabled(any()) }
    }

    @Test fun `ending a session with crossfade off leaves it off`() {
        val service = newService()
        service.onCrossfadePreference(false)
        service.setCrossfadeSuspended(true)
        service.setCrossfadeSuspended(false)
        assertThat(service.crossfadeActive).isFalse()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests 'com.stash.core.media.service.StashPlaybackServiceCrossfadeOverrideTest' -q`
Expected: FAIL, unresolved `onCrossfadePreference`.

- [ ] **Step 3: Injections and constants.** After `@Inject lateinit var discordRpcCoordinator …`:

```kotlin
    // Listen Together (spec 2026-09-24 §5): the session engine lives here and drives the master player.
    @Inject lateinit var listenTogetherController: com.stash.core.media.listen.ListenTogetherController
    @Inject lateinit var listenTogetherCatalog: com.stash.core.media.listen.DefaultSessionCatalog
    @Inject lateinit var roomApiClient: com.stash.core.data.listen.RoomApiClient
    @Inject lateinit var roomConnector: com.stash.core.data.listen.OkHttpRoomConnector
    @Inject lateinit var autoplayRadioPreference: com.stash.core.data.prefs.AutoplayRadioPreference
    @Inject lateinit var sharePreference: com.stash.core.data.share.SharePreference
    @Inject lateinit var playbackStateStore: com.stash.core.media.PlaybackStateStore
```

In the `companion object`, after `COMMAND_STOP_SLEEP_TIMER`:

```kotlin
        /** Custom command action for leaving a Listen Together session from the notification. */
        const val COMMAND_LEAVE_SESSION = "com.stash.LEAVE_SESSION"
```

After `@Volatile private var crossfadePreparedId: String? = null`:

```kotlin
    /** The crossfade preference as last read; [crossfadeEnabled] is this AND not suspended. */
    @Volatile private var crossfadePrefEnabled = false

    /** Listen Together's session-scoped crossfade override. In memory only, so a crash can't leave crossfade off (spec §4). */
    private var crossfadeSuspended = false

    private var listenTogetherSession: com.stash.core.media.listen.ListenTogetherSession? = null
    private var togetherPlayer: com.stash.core.media.listen.ListenTogetherPlayer? = null
    private var togetherListener: Player.Listener? = null
```

- [ ] **Step 4: The crossfade override.** In `onCreate`, replace

```kotlin
        serviceScope.launch { crossfadePreference.enabled.collect { onCrossfadeEnabledChanged(it) } }
```

with

```kotlin
        serviceScope.launch { crossfadePreference.enabled.collect { onCrossfadePreference(it) } }
```

and after `onCrossfadeEnabledChanged(...)` add:

```kotlin
    /** Collector body for the crossfade preference; Listen Together can hold crossfade off without writing it. */
    internal fun onCrossfadePreference(enabled: Boolean) {
        crossfadePrefEnabled = enabled
        onCrossfadeEnabledChanged(enabled && !crossfadeSuspended)
    }

    /** Suspends or restores crossfade for a Listen Together session. */
    internal fun setCrossfadeSuspended(suspended: Boolean) {
        crossfadeSuspended = suspended
        if (suspended) {
            crossfadeEngine?.cancelTransition()
            crossfadePreparedId = null
        }
        onCrossfadeEnabledChanged(crossfadePrefEnabled && !suspended)
    }

    /** The effective crossfade switch (test view). */
    internal val crossfadeActive: Boolean get() = crossfadeEnabled
```

Run the Step 1 test now: it should pass.

- [ ] **Step 5: The player abilities.** Add this property and inner class directly above `// ---- MediaLibrarySession.Callback ----`:

```kotlin
    private val sessionPlayer = ServiceSessionPlayer()

    /** The Listen Together engine's hands on the master player (spec §5 "New player abilities"). */
    @OptIn(UnstableApi::class)
    private inner class ServiceSessionPlayer : com.stash.core.media.listen.SessionPlayer {
        private val master: ExoPlayer get() = checkNotNull(crossfadeEngine).masterPlayer
        override var events: com.stash.core.media.listen.SessionPlayerEvents? = null

        override fun userQueue(): com.stash.core.media.listen.UserQueue {
            val m = master
            val timeline = m.currentTimeline
            // Upcoming songs in play order (shuffle included), as the user would have heard them.
            val upcoming = if (timeline.isEmpty) emptyList() else buildList {
                var i = m.currentMediaItemIndex
                while (size < 200) {
                    i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, m.shuffleModeEnabled)
                    if (i == C.INDEX_UNSET) break
                    add(m.getMediaItemAt(i))
                }
            }
            return com.stash.core.media.listen.UserQueue(m.currentMediaItem, upcoming, m.currentPosition.coerceAtLeast(0))
        }

        override suspend fun saveUserPosition() {
            // The queue ids are already saved on every change; only the position may lag, by up to 5 s.
            val saved = playbackStateStore.getLastPlaybackState() ?: return
            val m = master
            val currentId = m.currentMediaItem?.mediaMetadata?.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: return
            if (currentId == saved.trackId) {
                playbackStateStore.savePosition(saved.trackId, m.currentPosition.coerceAtLeast(0), saved.queueIndex)
            }
        }

        override fun enterSession(isHost: Boolean, interceptor: com.stash.core.media.listen.SessionInterceptor) {
            setCrossfadeSuspended(true)
            val m = master
            // The session player holds one song, and the room decides what comes next (spec §4).
            m.repeatMode = Player.REPEAT_MODE_OFF
            m.shuffleModeEnabled = false
            val wrapper = togetherPlayer?.also { it.rewrap(m) }
                ?: com.stash.core.media.listen.ListenTogetherPlayer(m).also { togetherPlayer = it }
            wrapper.configure(isHost, interceptor)
            if (togetherListener == null) {
                togetherListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> events?.onReady()
                            Player.STATE_BUFFERING -> events?.onBuffering()
                            Player.STATE_ENDED -> events?.onEnded()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        events?.onError()
                    }
                }.also { m.addListener(it) }
            }
            mediaSession?.player = wrapper
            // Keep the notification (and so the foreground service) even while the one song is idle or failed.
            setShowNotificationForIdlePlayer(androidx.media3.session.MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
            updateCustomLayout()
            triggerNotificationUpdate()
        }

        override fun exitSession() {
            val m = master
            togetherListener?.let { m.removeListener(it) }
            togetherListener = null
            togetherPlayer?.configure(isHost = false, interceptor = null)
            mediaSession?.player = m
            m.setPlaybackSpeed(1f)
            m.stop()
            m.clearMediaItems() // PlayerRepositoryImpl puts the user's own queue back on sessionEnds
            setCrossfadeSuspended(false)
            setShowNotificationForIdlePlayer(androidx.media3.session.MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR)
            updateCustomLayout()
            triggerNotificationUpdate()
        }

        override fun load(item: MediaItem, positionMs: Long) {
            val m = master
            m.playWhenReady = false
            m.setMediaItem(item, positionMs)
            m.prepare()
        }

        override fun seekTo(positionMs: Long) = master.seekTo(positionMs)
        override fun play() = master.play()
        override fun pause() = master.pause()

        /** Player.setPlaybackSpeed keeps pitch at 1, so Sonic time-stretches (spec §4). */
        override fun setSpeed(speed: Float) = master.setPlaybackSpeed(speed)

        override val positionMs: Long get() = master.currentPosition
        override val durationMs: Long? get() = master.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    }
```

`SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR` is Media3 1.9.2's default (checked in the jar: the manager initialises the mode to 3), so exiting restores exactly what was there.

- [ ] **Step 6: Start the engine.** In `onCreate`, first thing after `super.onCreate()`, give the notification its session text:

```kotlin
        // Listen Together: "Listening together · <artist>" while a session runs. Otherwise the stock provider.
        setMediaNotificationProvider(object : androidx.media3.session.DefaultMediaNotificationProvider(this) {
            override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
                val base = super.getNotificationContentText(metadata)
                return if (listenTogetherController.active.value) listOfNotNull("Listening together", base).joinToString(" · ") else base
            }
        })
```

and directly before the final `updateCustomLayout()` of `onCreate`:

```kotlin
        // Listen Together (spec 2026-09-24 §5). Logged at the wiring site: green unit tests say nothing about this line.
        listenTogetherSession = com.stash.core.media.listen.ListenTogetherSession(
            scope = serviceScope,
            controller = listenTogetherController,
            player = sessionPlayer,
            catalog = listenTogetherCatalog,
            api = roomApiClient,
            connector = roomConnector,
            autoplayRadio = { autoplayRadioPreference.enabled.first() },
            displayName = { sharePreference.displayName() },
            clock = { android.os.SystemClock.elapsedRealtime() },
        ).also { it.start() }
        listenTogetherController.serviceAttached = true
        android.util.Log.i("StashPlayback", "listen-together engine attached")
        serviceScope.launch { listenTogetherController.state.collect { updateCustomLayout() } }
```

- [ ] **Step 7: Stay alive during a session.** Add, next to `onGetSession`:

```kotlin
    /** During a session the notification stays in the foreground even while paused, so Android can't drop us (spec §5). */
    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val inSession = ::listenTogetherController.isInitialized && listenTogetherController.active.value
        super.onUpdateNotification(session, startInForegroundRequired || inSession)
    }
```

At the top of `performIdleStop()`:

```kotlin
        // A paused listener, or a host in a long pause, must not drop out of the session (spec §5).
        if (listenTogetherController.active.value) return
```

At the top of `onTaskRemoved(...)`:

```kotlin
        if (listenTogetherController.active.value) return // swiping the app away doesn't leave the session
```

At the top of `onDestroy()`, before `playbackSessionBus.onServiceStopping()`, while the session and players still exist:

```kotlin
        listenTogetherSession?.shutdown()
        listenTogetherSession = null
        if (::listenTogetherController.isInitialized) listenTogetherController.serviceAttached = false
```

- [ ] **Step 8: The notification's Leave button.** Replace `pushLayout` with:

```kotlin
    @OptIn(UnstableApi::class)
    private fun pushLayout(session: MediaSession, player: Player, isLiked: Boolean) {
        val together = if (::listenTogetherController.isInitialized) listenTogetherController.state.value else null
        val buttons = mutableListOf<CommandButton>()
        when {
            // A listener's notification shows only Leave (spec §5); repeat does nothing in a one-song session.
            together is com.stash.core.media.listen.ListenTogetherState.InRoom && !together.isHost ->
                buttons.add(buildLeaveSessionButton())
            together is com.stash.core.media.listen.ListenTogetherState.InRoom -> {
                buttons.add(buildLikeButton(isLiked))
                buttons.add(buildLeaveSessionButton())
            }
            else -> {
                buttons.add(buildLikeButton(isLiked))
                buttons.add(buildRepeatButton(player.repeatMode))
            }
        }
        if (sleepTimerActive) buttons.add(buildStopSleepTimerButton())
        session.setCustomLayout(ImmutableList.copyOf(buttons))
    }

    @OptIn(UnstableApi::class)
    private fun buildLeaveSessionButton(): CommandButton = CommandButton.Builder()
        .setDisplayName("Leave session")
        .setIconResId(R.drawable.ic_listen_leave)
        .setSessionCommand(SessionCommand(COMMAND_LEAVE_SESSION, android.os.Bundle.EMPTY))
        .build()
```

In `onConnect`'s `customCommands` list, add:

```kotlin
                SessionCommand(COMMAND_LEAVE_SESSION, /* extras = */ android.os.Bundle.EMPTY),
```

In `onCustomCommand`'s `when`, add:

```kotlin
                COMMAND_LEAVE_SESSION -> listenTogetherController.send(
                    com.stash.core.media.listen.ListenTogetherController.Command.Leave,
                )
```

Create `core/media/src/main/res/drawable/ic_listen_leave.xml` (Material "logout"):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h8v-2H4V5z" />
</vector>
```

- [ ] **Step 9: Run the module's tests and build the app**

Run: `./gradlew :core:media:testDebugUnitTest -q`
Expected: PASS (all of `:core:media`).
Run: `./gradlew :app:assembleDebug -q`
Expected: success. Hilt resolves `DefaultSessionCatalog`, `OkHttpRoomConnector`, `RoomApiClient` and `ListenTogetherController` through their `@Inject` constructors, so no module is needed.

- [ ] **Step 10: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt core/media/src/main/res/drawable/ic_listen_leave.xml core/media/src/test/kotlin/com/stash/core/media/service/StashPlaybackServiceCrossfadeOverrideTest.kt
git commit -m "feat(listen): service runs the session — forwarding player, crossfade override, foreground while paused, Leave"
```

# Part D: UI

Follow the existing Now Playing look: `SheetOptionRow` rows, `StashTheme.extendedColors.elevatedSurface` sheets, and `npAccent(...)` for the accent. Don't restyle anything that exists.

### Task 22: "Suggest" in every track menu

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/ListenTogetherRole.kt`
- Modify:
  - `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt`
  - `feature/search/src/main/kotlin/com/stash/feature/search/SongRow.kt`
  - `app/src/main/kotlin/com/stash/app/MainActivity.kt`

"Play next" from any menu already reaches the session: `PlayerRepository.addNext` becomes `addMediaItems` on `ListenTogetherPlayer`, which turns it into a suggestion from a listener or a queue entry from the host (Tasks 17 and 19). So the menus only need new labels. One `CompositionLocal` provided at the root is enough; no call site changes. "Add to queue" and "Start radio" are hidden during a session. The session player holds one song, and a radio start would try to rebuild the queue.

- [ ] **Step 1: Create `ListenTogetherRole.kt`**

```kotlin
package com.stash.core.ui.components

import androidx.compose.runtime.compositionLocalOf

/** This phone's part in a Listen Together session (spec 2026-09-24 §5), provided once at the app root. */
enum class ListenTogetherRole { NONE, HOST, LISTENER }

val LocalListenTogetherRole = compositionLocalOf { ListenTogetherRole.NONE }

/** "Play next" in a session: a host's pick joins the room's queue; a listener's goes to the host as a suggestion. */
fun playNextLabel(role: ListenTogetherRole, default: String): String = when (role) {
    ListenTogetherRole.NONE -> default
    ListenTogetherRole.HOST -> "Add to session queue"
    ListenTogetherRole.LISTENER -> "Suggest to the host"
}
```

- [ ] **Step 2: `TrackOptionsSheet`.** At the top of the `TrackOptionsSheet` body add `val role = LocalListenTogetherRole.current`. Then:
  - the Play Next row gets `label = playNextLabel(role, "Play Next"),`;
  - `if (onAddToQueue != null) {` becomes `if (onAddToQueue != null && role == ListenTogetherRole.NONE) {`;
  - `if (onStartRadio != null) {` becomes `if (onStartRadio != null && role == ListenTogetherRole.NONE) {`.

- [ ] **Step 3: Search's `SongRow` overflow menu.** At the top of the `Box(modifier) {` that holds the menu, add `val role = com.stash.core.ui.components.LocalListenTogetherRole.current`. Then:
  - the "Play next" item gets `text = { Text(com.stash.core.ui.components.playNextLabel(role, "Play next")) },`;
  - the "Add to queue" and "Start radio" `DropdownMenuItem`s are wrapped in `if (role == com.stash.core.ui.components.ListenTogetherRole.NONE) { … }`.

- [ ] **Step 4: Provide the role at the root.** In `MainActivity`:

```kotlin
    @Inject
    lateinit var listenTogether: com.stash.core.media.listen.ListenTogetherController
```

In `setContent`, wrap the `StashScaffold(...)` call inside `StashTheme { … }`:

```kotlin
            val together by listenTogether.state.collectAsState()
            val role = (together as? com.stash.core.media.listen.ListenTogetherState.InRoom)
                ?.let { if (it.isHost) ListenTogetherRole.HOST else ListenTogetherRole.LISTENER }
                ?: ListenTogetherRole.NONE
            StashTheme(darkTheme = darkTheme, amoled = amoledDark) {
                CompositionLocalProvider(LocalListenTogetherRole provides role) {
                    StashScaffold(
                        pendingDeepLink = pendingDeepLink.value,
                        onDeepLinkConsumed = { pendingDeepLink.value = null },
                    )
                }
            }
```

with the imports `androidx.compose.runtime.CompositionLocalProvider`, `com.stash.core.ui.components.ListenTogetherRole` and `com.stash.core.ui.components.LocalListenTogetherRole`.

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug -q`
Expected: success.

- [ ] **Step 6: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/ListenTogetherRole.kt core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt feature/search/src/main/kotlin/com/stash/feature/search/SongRow.kt app/src/main/kotlin/com/stash/app/MainActivity.kt
git commit -m "feat(listen): track menus say Suggest (listener) or Add to session queue (host) during a session"
```

### Task 23: The Now Playing entry — Start, Invite, Leave, End

**Files:**
- Create:
  - `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/ListenTogetherViewModel.kt`
  - `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/ListenTogetherUi.kt`
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`
- Test: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/listen/ListenTogetherViewModelTest.kt`

This is a separate ViewModel, so `NowPlayingViewModel`'s constructor and its 660-line test stay untouched.

- [ ] **Step 1: Write the failing test** (`:feature:nowplaying` tests use plain JUnit asserts; the module has no Truth)

```kotlin
package com.stash.feature.nowplaying.listen

import com.stash.core.data.share.SharePreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherViewModelTest {
    private val controller: ListenTogetherController = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(ListenTogetherState.Idle)
    }
    private val sharePreference: SharePreference = mockk(relaxed = true) { coEvery { displayName() } returns "Rawn" }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the name field starts from the saved Show my name as`() {
        assertEquals("Rawn", ListenTogetherViewModel(controller, sharePreference).name)
    }

    @Test fun `starting saves the name, then asks for a room`() {
        val vm = ListenTogetherViewModel(controller, sharePreference)
        vm.onNameChange("Sam")
        vm.start()
        coVerifyOrder {
            sharePreference.setDisplayName("Sam")
            controller.send(Command.Host)
        }
    }

    @Test fun `leave, end, react and make host go straight to the controller`() {
        val vm = ListenTogetherViewModel(controller, sharePreference)
        vm.leave(); vm.end(); vm.react("x"); vm.makeHost("m2"); vm.answerSuggestion("s1", add = true)
        verify { controller.send(Command.Leave) }
        verify { controller.send(Command.End) }
        verify { controller.send(Command.React("x")) }
        verify { controller.send(Command.MakeHost("m2")) }
        verify { controller.send(Command.Suggestion("s1", true)) }
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests 'com.stash.feature.nowplaying.listen.ListenTogetherViewModelTest' -q`
Expected: FAIL, unresolved `ListenTogetherViewModel`.

- [ ] **Step 3: Create `ListenTogetherViewModel.kt`**

```kotlin
package com.stash.feature.nowplaying.listen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.share.SharePreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.ServerMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Now Playing's Listen Together controls (spec §5). All the work happens in the session; this only forwards. */
@HiltViewModel
class ListenTogetherViewModel @Inject constructor(
    private val controller: ListenTogetherController,
    private val sharePreference: SharePreference,
) : ViewModel() {
    val state: StateFlow<ListenTogetherState> = controller.state
    val reactions: SharedFlow<ServerMessage.Reaction> = controller.reactions
    val messages: SharedFlow<String> = controller.messages

    /** The "Show my name as" field. Compose state, so the TextField reads it synchronously. */
    var name by mutableStateOf("")
        private set

    init {
        viewModelScope.launch { name = sharePreference.displayName().orEmpty() }
    }

    fun onNameChange(value: String) { name = value.take(40) }

    fun start() {
        viewModelScope.launch {
            sharePreference.setDisplayName(name)
            controller.send(Command.Host)
        }
    }

    fun leave() = controller.send(Command.Leave)
    fun end() = controller.send(Command.End)
    fun react(emoji: String) = controller.send(Command.React(emoji))
    fun makeHost(memberId: String) = controller.send(Command.MakeHost(memberId))
    fun answerSuggestion(id: String, add: Boolean) = controller.send(Command.Suggestion(id, add))
}
```

- [ ] **Step 4: Create `ListenTogetherUi.kt`** with the start dialog and the invite share (Task 24 adds the rest of the file):

```kotlin
package com.stash.feature.nowplaying.listen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Starting a session: the only thing to decide is the name others will see (spec §1: no accounts). */
@Composable
fun ListenTogetherStartDialog(name: String, onNameChange: (String) -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listen together") },
        text = {
            Column {
                Text(
                    "Friends who open your invite hear what you play, in time with you. You stay in control and can hand it over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Show my name as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Android share sheet with the invite link (`https://…/l/{code}`, an App Link). */
fun shareInvite(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Listen with me on Stash: $url")
    context.startActivity(Intent.createChooser(send, "Invite friends").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
```

- [ ] **Step 5: Wire it into `NowPlayingScreen`.**

(a) After `val shareTrack by viewModel.shareTrack.collectAsStateWithLifecycle()`:

```kotlin
    // Listen Together (spec 2026-09-24 §5).
    val together: com.stash.feature.nowplaying.listen.ListenTogetherViewModel = hiltViewModel()
    val togetherState by together.state.collectAsStateWithLifecycle()
    val room = togetherState as? ListenTogetherState.InRoom
    val isListener = room != null && !room.isHost
    var showStartTogether by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
```

(b) After the `LaunchedEffect(Unit) { viewModel.userMessages.collect { … } }` block (which defines `toastContext`):

```kotlin
    LaunchedEffect(Unit) {
        together.messages.collect { msg ->
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
```

(c) Next to the sleep-timer sheet block:

```kotlin
    if (showStartTogether) {
        com.stash.feature.nowplaying.listen.ListenTogetherStartDialog(
            name = together.name,
            onNameChange = together::onNameChange,
            onStart = { together.start(); showStartTogether = false },
            onDismiss = { showStartTogether = false },
        )
    }
```

(d) Give `NowPlayingOptionsSheet` five new parameters after `onViewAlbum`:

```kotlin
    together: ListenTogetherState,
    onStartTogether: () -> Unit,
    onInvite: () -> Unit,
    onLeaveTogether: () -> Unit,
    onEndTogether: () -> Unit,
```

and inside it, directly after the "Track Options" title `Text(...)`:

```kotlin
            // Listen Together: Start, or Invite / End / Leave once in a room.
            when (together) {
                is ListenTogetherState.InRoom -> {
                    if (together.isHost) {
                        SheetOptionRow(icon = Icons.Default.PersonAdd, label = "Invite friends", onClick = { onInvite(); onDismiss() })
                        Spacer(modifier = Modifier.height(8.dp))
                        SheetOptionRow(icon = Icons.Default.StopCircle, label = "End session for everyone", onClick = { onEndTogether(); onDismiss() })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    SheetOptionRow(icon = Icons.AutoMirrored.Filled.Logout, label = "Leave session", onClick = { onLeaveTogether(); onDismiss() })
                }
                is ListenTogetherState.Connecting -> SheetOptionRow(icon = Icons.Default.Groups, label = "Connecting…", onClick = onDismiss)
                ListenTogetherState.Idle -> SheetOptionRow(icon = Icons.Default.Groups, label = "Listen together", onClick = { onStartTogether(); onDismiss() })
            }
            Spacer(modifier = Modifier.height(8.dp))
```

Pass them at the call site (`if (showOptionsSheet && track != null) { NowPlayingOptionsSheet(…) }`):

```kotlin
            together = togetherState,
            onStartTogether = { showStartTogether = true },
            onInvite = { room?.let { com.stash.feature.nowplaying.listen.shareInvite(toastContext, it.url) } },
            onLeaveTogether = together::leave,
            onEndTogether = together::end,
```

Imports for the screen: `com.stash.core.media.listen.ListenTogetherState`, `androidx.compose.material.icons.filled.Groups`, `androidx.compose.material.icons.filled.PersonAdd`, `androidx.compose.material.icons.filled.StopCircle` and `androidx.compose.material.icons.automirrored.filled.Logout`. `:feature:nowplaying` already depends on `material-icons-extended` and `:core:media`.

- [ ] **Step 6: Run the test and build**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests 'com.stash.feature.nowplaying.listen.ListenTogetherViewModelTest' -q`
Expected: PASS.
Run: `./gradlew :app:assembleDebug -q`
Expected: success.

- [ ] **Step 7: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/ListenTogetherViewModel.kt feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/ListenTogetherUi.kt feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/listen/ListenTogetherViewModelTest.kt
git commit -m "feat(listen): Now Playing — Listen together, Invite friends, End session, Leave session"
```

### Task 24: In the room — who's listening, notices, the listener lock, reactions, suggestions

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/ListenTogetherUi.kt`, `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`

- [ ] **Step 1: Add the composables** to the end of `ListenTogetherUi.kt`, and merge these imports into the top of the file:

```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.listen.ServerMessage
import com.stash.core.ui.theme.StashTheme
import kotlin.random.Random
import kotlinx.coroutines.flow.SharedFlow
```

```kotlin
/** Who's in the room, the host marked, each with a status dot (spec §5). The host hands control over from here. */
@Composable
fun WhosListeningBar(
    room: ListenTogetherState.InRoom,
    accent: Color,
    onMakeHost: (String) -> Unit,
    onOpenSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(room.members, key = { it.id }) { member ->
                MemberPill(
                    member = member,
                    isHost = member.id == room.hostId,
                    isMe = member.id == room.myId,
                    accent = accent,
                    onMakeHost = if (room.isHost && member.id != room.myId) ({ onMakeHost(member.id) }) else null,
                )
            }
        }
        if (room.isHost && room.suggestions.isNotEmpty()) {
            TextButton(onClick = onOpenSuggestions) { Text("Suggestions (${room.suggestions.size})") }
        }
    }
}

@Composable
private fun MemberPill(member: RoomMember, isHost: Boolean, isMe: Boolean, accent: Color, onMakeHost: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val dot = when (member.status) {
        "ok" -> accent
        "unavailable" -> MaterialTheme.colorScheme.error
        else -> Color(0xFFFFB300) // buffering or drifting
    }
    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            modifier = Modifier.clickable(enabled = onMakeHost != null) { menu = true },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = buildString {
                        append(member.name ?: "Someone")
                        if (isMe) append(" (you)")
                        if (isHost) append(" · host")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Make host") }, onClick = { menu = false; onMakeHost?.invoke() })
        }
    }
}

/** The one-line notices from spec §4 and §6, most urgent first. */
@Composable
fun ListenTogetherNotice(room: ListenTogetherState.InRoom, modifier: Modifier = Modifier) {
    val text = when {
        room.reconnecting -> "Reconnecting…"
        room.unavailable -> "This song isn't available to you"
        room.versionMismatch -> "Your version may be a few seconds off"
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 4.dp),
    )
}

/** A listener's controls: no play, pause, skip or seek (spec §5). Volume stays their own. */
@Composable
fun ListenerControls(onLeave: () -> Unit, onReact: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onLeave) { Text("Leave") }
        Spacer(Modifier.width(16.dp))
        ReactionButton(onReact = onReact)
    }
}

@Composable
fun ReactionButton(onReact: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.AddReaction, contentDescription = "React") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                RoomProtocol.EMOJI.forEach { emoji ->
                    TextButton(onClick = { open = false; onReact(emoji) }) { Text(emoji, fontSize = 22.sp) }
                }
            }
        }
    }
}

private data class Floater(val id: Long, val emoji: String, val lane: Float)

/** Reactions float up and fade (spec §5). */
@Composable
fun FloatingReactions(reactions: SharedFlow<ServerMessage.Reaction>, modifier: Modifier = Modifier) {
    val live = remember { mutableStateListOf<Floater>() }
    LaunchedEffect(reactions) {
        var next = 0L
        reactions.collect { r -> live += Floater(next++, r.emoji, Random.nextFloat()) }
    }
    Box(modifier) {
        live.forEach { f ->
            key(f.id) {
                val progress = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    progress.animateTo(1f, tween(durationMillis = 2_400, easing = LinearOutSlowInEasing))
                    live.remove(f)
                }
                Text(
                    text = f.emoji,
                    fontSize = 32.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (24 + f.lane * 240).dp, y = (-(120 + 320 * progress.value)).dp)
                        .alpha(1f - progress.value),
                )
            }
        }
    }
}

/** The host's suggestions tray (spec §5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsSheet(room: ListenTogetherState.InRoom, onAnswer: (id: String, add: Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = StashTheme.extendedColors.elevatedSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
            Text("Suggestions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            if (room.suggestions.isEmpty()) {
                Text("Nothing waiting", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            room.suggestions.forEach { s ->
                val from = room.members.firstOrNull { it.id == s.from }?.name ?: "Someone"
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${s.track.artist} · from $from",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onAnswer(s.id, false) }) { Text("Dismiss") }
                    Button(onClick = { onAnswer(s.id, true) }) { Text("Add") }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Put them on the Now Playing screen.** In `NowPlayingScreen.kt`:

(a) Directly after the `TopBar(...)` call:

```kotlin
                if (room != null) {
                    com.stash.feature.nowplaying.listen.WhosListeningBar(
                        room = room,
                        accent = npAccent(uiState.vibrantColor),
                        onMakeHost = together::makeHost,
                        onOpenSuggestions = { showSuggestions = true },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    com.stash.feature.nowplaying.listen.ListenTogetherNotice(room)
                }
```

and in that same `TopBar(...)` call, keep radio out of a session: `onStartRadio = { if (room == null) viewModel.startRadioFromCurrent() },`.

(b) Stop a listener seeking: in `GlowingProgressBar(...)`, `onSeek = viewModel::onSeekTo,` becomes `onSeek = { if (!isListener) viewModel.onSeekTo(it) },`. The bar still shows progress.

(c) Replace the `PlaybackControls(...)` call with:

```kotlin
                if (isListener) {
                    com.stash.feature.nowplaying.listen.ListenerControls(onLeave = together::leave, onReact = together::react)
                } else {
                    PlaybackControls(
                        // …the existing arguments, unchanged…
                    )
                    if (room != null) com.stash.feature.nowplaying.listen.ReactionButton(onReact = together::react)
                }
```

(d) Floating reactions over everything: directly after the outer `Column(modifier = Modifier.fillMaxSize()) { … }` that ends with `LiveLyricsBar(…)`, still inside the root `Box`:

```kotlin
        if (room != null) com.stash.feature.nowplaying.listen.FloatingReactions(together.reactions, Modifier.fillMaxSize())
```

(e) The host's tray, next to the start dialog from Task 23:

```kotlin
    if (showSuggestions && room != null && room.isHost) {
        com.stash.feature.nowplaying.listen.SuggestionsSheet(
            room = room,
            onAnswer = together::answerSuggestion,
            onDismiss = { showSuggestions = false },
        )
    }
```

- [ ] **Step 3: Build and install on the Pixel 5 rig** (ask for its current wireless port; see Task 28):

Run: `./gradlew :app:assembleDebug -q`
Expected: success. Visual checks happen in Task 28.

- [ ] **Step 4: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/ListenTogetherUi.kt feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "feat(listen): who's listening, notices, listener lock, floating reactions, host suggestions tray"
```

### Task 25: The Join screen and `/l/` App Links

**Files:**
- Create:
  - `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/JoinSessionViewModel.kt`
  - `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/JoinSessionScreen.kt`
- Modify:
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
  - `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt`
  - `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`
- Test: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/listen/JoinSessionViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.feature.nowplaying.listen

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoinSessionViewModelTest {
    private val api: RoomApiClient = mockk()
    private val controller: ListenTogetherController = mockk(relaxed = true)
    private val sharePreference: SharePreference = mockk(relaxed = true) { coEvery { displayName() } returns null }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = JoinSessionViewModel(SavedStateHandle(mapOf("code" to "K7QA2P")), api, controller, sharePreference)

    @Test fun `an open room shows the host, the count and the song`() {
        coEvery { api.preview("K7QA2P") } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 3, false, SharedTrack("Xtal", "Aphex Twin")))
        assertEquals(JoinSessionViewModel.UiState.Ready("Rawn", 3, SharedTrack("Xtal", "Aphex Twin")), vm().state.value)
    }

    @Test fun `full, ended and unreachable rooms each say so`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 10, true))
        assertEquals(JoinSessionViewModel.UiState.Full, vm().state.value)
        coEvery { api.preview(any()) } returns ShareResult.NotFound
        assertEquals(JoinSessionViewModel.UiState.Ended, vm().state.value)
        coEvery { api.preview(any()) } returns ShareResult.Failed("offline")
        assertEquals(JoinSessionViewModel.UiState.Failed, vm().state.value)
    }

    @Test fun `join saves the name, asks the session to join, then moves on`() {
        coEvery { api.preview(any()) } returns ShareResult.Ok(RoomApiClient.Preview("Rawn", 1, false))
        val vm = vm()
        vm.onNameChange("Sam")
        var joined = false
        vm.join { joined = true }
        coVerifyOrder {
            sharePreference.setDisplayName("Sam")
            controller.send(ListenTogetherController.Command.Join("K7QA2P"))
        }
        assertTrue(joined)
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests 'com.stash.feature.nowplaying.listen.JoinSessionViewModelTest' -q`
Expected: FAIL, unresolved `JoinSessionViewModel`.

- [ ] **Step 3: Create `JoinSessionViewModel.kt`**

```kotlin
package com.stash.feature.nowplaying.listen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.model.share.SharedTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The Join screen for a `/l/{code}` link (spec §5–§6): preview first, so a full or ended room says so before connecting. */
@HiltViewModel
class JoinSessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: RoomApiClient,
    private val controller: ListenTogetherController,
    private val sharePreference: SharePreference,
) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val hostName: String?, val memberCount: Int, val track: SharedTrack?) : UiState
        data object Full : UiState
        data object Ended : UiState
        data object Failed : UiState
    }

    /** ListenJoinRoute(code). Type-safe navigation stores it under the property name. */
    val code: String = savedStateHandle.get<String>("code").orEmpty()

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    var name by mutableStateOf("")
        private set

    init {
        load()
        viewModelScope.launch { name = sharePreference.displayName().orEmpty() }
    }

    fun onNameChange(value: String) { name = value.take(40) }

    fun retry() = load()

    private fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = api.preview(code)) {
                is ShareResult.Ok -> if (r.value.full) UiState.Full else UiState.Ready(r.value.hostName, r.value.memberCount, r.value.track)
                ShareResult.NotFound, ShareResult.Gone -> UiState.Ended
                else -> UiState.Failed
            }
        }
    }

    fun join(onJoined: () -> Unit) {
        viewModelScope.launch {
            sharePreference.setDisplayName(name)
            controller.send(ListenTogetherController.Command.Join(code))
            onJoined()
        }
    }
}
```

- [ ] **Step 4: Create `JoinSessionScreen.kt`**

```kotlin
package com.stash.feature.nowplaying.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun JoinSessionScreen(onBack: () -> Unit, onJoined: () -> Unit, viewModel: JoinSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(horizontal = 24.dp),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Spacer(Modifier.height(24.dp))
        Text("Listen together", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (val s = state) {
            JoinSessionViewModel.UiState.Loading -> CircularProgressIndicator()
            is JoinSessionViewModel.UiState.Ready -> {
                Text("Join ${s.hostName ?: "a friend"}'s session", style = MaterialTheme.typography.titleMedium)
                Text("${s.memberCount} listening", style = MaterialTheme.typography.bodyMedium, color = muted)
                s.track?.let { Text("Now playing: ${it.title} · ${it.artist}", style = MaterialTheme.typography.bodyMedium, color = muted) }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = viewModel.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text("Show my name as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.join(onJoined) }, modifier = Modifier.fillMaxWidth()) { Text("Join") }
                Text(
                    "Your own queue is set aside and comes back when you leave.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            JoinSessionViewModel.UiState.Full -> Text("This session is full", style = MaterialTheme.typography.titleMedium)
            JoinSessionViewModel.UiState.Ended -> Text("This session has ended", style = MaterialTheme.typography.titleMedium)
            JoinSessionViewModel.UiState.Failed -> {
                Text("Couldn't reach the session", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::retry) { Text("Try again") }
            }
        }
    }
}
```

- [ ] **Step 5: Route the link.**

`AndroidManifest.xml`: in the shared-links `<intent-filter android:autoVerify="true">`, after the `/t` line, add

```xml
                <data android:scheme="https" android:host="stash-share.rawnaldclark.workers.dev" android:pathPrefix="/l/" />
```

and extend its comment to say "Shared mix, track and Listen Together links". `assetlinks.json` already covers the whole host, so no Worker change is needed.

`TopLevelDestination.kt`, next to `SharedTrackRoute`:

```kotlin
@Serializable data class ListenJoinRoute(val code: String)
```

`StashScaffold.kt`, in the `else ->` branch of the deep-link `LaunchedEffect`, add a third case before `onDeepLinkConsumed()`:

```kotlin
                } else if (pendingDeepLink.startsWith(com.stash.app.MainActivity.DEEP_LINK_LISTEN_PREFIX)) {
                    val code = pendingDeepLink.removePrefix(com.stash.app.MainActivity.DEEP_LINK_LISTEN_PREFIX)
                    navController.navigate(ListenJoinRoute(code))
```

`StashNavHost.kt`, next to `composable<SharedTrackRoute>`:

```kotlin
        composable<ListenJoinRoute> {
            com.stash.feature.nowplaying.listen.JoinSessionScreen(
                onBack = { navController.popBackStack() },
                // After joining, Now Playing is the session; Back from it skips the Join screen.
                onJoined = { navController.navigate(NowPlayingRoute) { popUpTo<ListenJoinRoute> { inclusive = true } } },
            )
        }
```

- [ ] **Step 6: Run the test and build**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests 'com.stash.feature.nowplaying.listen.*' -q`
Expected: PASS.
Run: `./gradlew :app:assembleDebug -q`
Expected: success.

- [ ] **Step 7: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/JoinSessionViewModel.kt feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/listen/JoinSessionScreen.kt feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/listen/JoinSessionViewModelTest.kt app/src/main/AndroidManifest.xml app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(listen): Join screen and /l/{code} App Links"
```

# Part E: Disclosure, deploy, device test, PR

### Task 26: README disclosure

**Files:**
- Modify: `README.md` (the `stash-share.rawnaldclark.workers.dev` entry under "What Stash talks to", line ~100)

- [ ] **Step 1: Add one sentence** to the end of that entry (spec §7):

```markdown
Listen Together uses the same host: starting or joining a session opens a connection to a short-lived room that sees each member's display name (if they set one), the songs played in it (the same descriptors as a shared mix), and play, pause, seek, suggestion and reaction messages. Your IP address is used only for rate limiting and isn't stored, and nothing is kept once the room closes (5 minutes after the last person leaves, or 12 hours after it started).
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: disclose what Listen Together sends to stash-share"
```

### Task 27: Deploy the Worker — ⚠️ OWNER GO REQUIRED

**Files:** none (deploy only)

> **Stop. Don't run this task without the owner's explicit go-ahead in this session.** The first deploy applies a Durable Object migration (`tag = "v1"`, `new_sqlite_classes = ["ListenRoom"]`). Migrations are one-way: undoing it takes another migration (`deleted_classes`), and that deletes every room's storage. The same Worker serves shared mixes, which are live for users, so a bad deploy breaks mix links too.

- [ ] **Step 1: Pre-flight** (safe, no deploy)

```bash
cd infra/share-worker
npm test                                   # ℹ fail 0
npx wrangler deploy --dry-run --outdir /tmp/stash-share-dry
npx wrangler whoami                        # the right Cloudflare account (Workers Paid)
```

- [ ] **Step 2: Deploy** (after the owner says go)

```bash
npx wrangler deploy
```

Expected: the output lists the `ROOMS (ListenRoom)` Durable Object binding, the `ROOM_RL` rate limit, and the applied migration `v1`.

- [ ] **Step 3: Smoke test production.** Shared mixes first, since they must not regress:

```bash
BASE=https://stash-share.rawnaldclark.workers.dev
curl -s -o /dev/null -w '%{http_code}\n' $BASE/.well-known/assetlinks.json      # 200
curl -s -o /dev/null -w '%{http_code}\n' $BASE/v1/mixes/zzzzzzzz               # 404 (routing alive)
CREATED=$(curl -s -X POST $BASE/v1/rooms -H 'content-type: application/json' -d '{"hostName":"Smoke"}')
echo "$CREATED"                                                                 # {"code":"XXXXXX","hostKey":"…","url":"…/l/XXXXXX"}
CODE=$(echo "$CREATED" | python -c "import json,sys;print(json.load(sys.stdin)['code'])")
curl -s $BASE/v1/rooms/$CODE                                                    # {"hostName":"Smoke","memberCount":0,"full":false}
curl -s -o /dev/null -w '%{http_code}\n' $BASE/l/$CODE                          # 200
node -e '
const ws = new WebSocket(`wss://stash-share.rawnaldclark.workers.dev/v1/rooms/${process.argv[1]}/ws`);
ws.onopen = () => { ws.send(JSON.stringify({ t: "hello", name: "smoke" })); ws.send(JSON.stringify({ t: "ping", c: Date.now() })); };
ws.onmessage = (e) => { const m = JSON.parse(e.data); console.log(m.t); if (m.t === "pong") ws.close(); };
' "$CODE"                                                                       # welcome, members, pong
```

The room closes by itself 5 minutes after the smoke socket leaves. Check with `curl -s -o /dev/null -w '%{http_code}\n' $BASE/v1/rooms/$CODE` afterwards: it should be 404.

- [ ] **Step 4: Watch it briefly:** `npx wrangler tail` while the smoke test runs: no exceptions.

### Task 28: Two-phone device test, then the PR

**Devices:**
- **Pixel 6 Pro:** the owner's daily phone. Before ANY tap, check `adb -s <serial> shell dumpsys window | grep mCurrentFocus`. Stop if the owner is using it. Ask before touching app data; offer a backup first.
- **Pixel 5 rig:** wireless at `192.168.137.35`. The port changes, so ask for it each session: `adb connect 192.168.137.35:<port>`.

With two devices attached, always pass `adb -s`.

- [ ] **Step 1: Install a fresh build on both**

```bash
./gradlew :app:assembleDebug
ls -l app/build/outputs/apk/debug/*.apk        # mtime is now
adb -s <pixel6> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <pixel5> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <pixel5> shell dumpsys package com.stash.app.debug | grep versionName
adb -s <pixel6> shell pm get-app-links com.stash.app.debug    # stash-share…: verified
```

Only trust a device check once the build finished cleanly, the APK's mtime is fresh, and the installed versionName matches.

- [ ] **Step 2: Watch the wiring logs** in a second terminal per phone:

`adb -s <serial> logcat -s ListenTogether:I StashPlayback:I`

You should see:
- `listen-together engine attached` when the service starts;
- `entering room …`, `prepare #1 …`, `start #1 at room …` when a session starts;
- `leaving the room` when it ends.

- [ ] **Step 3: Walk the spec §8 scenario.** Check each result:

1. **Start** (Pixel 5): play a song, then Now Playing → ⋮ → Listen together → Start. Then ⋮ → Invite friends → copy the link.
2. **Join** (Pixel 6): `adb -s <pixel6> shell am start -a android.intent.action.VIEW -d "<link>" com.stash.app.debug`. The Join screen opens (not a browser) and shows the host's name and song. Tap Join. Within about 8 s both phones play the same song. The Pixel 6's Now Playing has Leave + React instead of the transport controls, and its notification shows only Leave.
3. **In sync:** put the phones side by side and film them, or run a stopwatch app, through a pause and resume, a seek, and a skip. They stay within about 50 ms. Logcat shows no seek-storm of drift corrections.
4. **Listener lock:** the Pixel 6's headset button, lock-screen play/pause and notification do nothing. Its volume still works.
5. **Suggest:** on the Pixel 6, open any song's ⋮ → "Suggest to the host". The Pixel 5 shows "Suggestions (1)"; tap Add. The song plays after the current one.
6. **React:** a reaction from either phone floats up on both.
7. **Song end, then radio:** with autoplay radio on and the host's queue empty, let the last song end. A station starts on both.
8. **Drop:** turn the Pixel 6's Wi-Fi off for 10 s and back on. It keeps playing, shows "Reconnecting…", and comes back in time.
9. **End:** host → ⋮ → End session for everyone. Both phones show "Session ended" and their own queue returns, paused, at the right song and position. Crossfade is back to how it was.
10. **Process death mid-session:** start a new session, then `adb -s <pixel6> shell am force-stop com.stash.app.debug` (the listener) and relaunch it. Its own queue is back (the cold-start ghost), not the session's song.
11. **Host killed** (last, because it leaves the host phone stopped): start a new session with the Pixel 6 joined, then `adb -s <pixel5> shell am force-stop com.stash.app.debug`. After about 60 s the Pixel 6 becomes host: its transport controls come back, and its skip moves the room on.

- [ ] **Step 4: Open the PR**

```bash
git push -u origin feat/listen-together
gh pr create --base master --title "feat: Listen Together — synced sessions with a host, suggestions and reactions" --body "<summary; links to the spec and this plan; the Task 28 results, including the measured sync; the deploy status from Task 27>"
```

End the PR body with the attribution line the session asks for.
