# Sessions step 1: the server

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Test first in every task: write the test, see it fail, write the code, see it pass, commit.

**Goal:** Everything the app's steps 2 and 3 talk to: stations, votes, follows, the live directory and Upcoming (1a); public session rooms with roles, requests, co-hosts, booting and presence, writing the directory as they go (1b). Shipped dark behind `SESSIONS = "0"`.

**Read first:** the build plan `2026-11-20-sessions.md` (its "Decisions this plan makes" are binding here), the system spec §3–§4 and §7–§8, the UX spec §8–§9. Code: `infra/share-worker/src/room.js` (pure, `step()`), `src/listen-room.js` (the Durable Object shell), `src/community.js` (post routes this copies), `test/fake-d1.js`, `test/fake-room.js`, `test/community-helpers.js`.

**Where:** two PRs, two worktrees from `origin/master`:
- **1a** `.worktrees/sessions-stations`, branch `feat/sessions-stations`: tasks 1–10.
- **1b** `.worktrees/sessions-rooms`, branch `feat/sessions-rooms`, stacked on `feat/sessions-stations` (so it can start while 1a is in review): tasks 11–21.

Run `npm test` in `infra/share-worker` after every task: the whole suite, private Listen Together's tests included, must stay green.

---

## 1a: stations

### Task 1: The schema

**Files:** create `migrations/0007_sessions.sql`; test `test/stations-db.test.js`.

- [ ] Write the migration:

```sql
-- Sessions (specs docs/superpowers/specs/2026-11-20-sessions-design.md §3, -ux.md §9).
CREATE TABLE stations (
  id           TEXT PRIMARY KEY,             -- 8 chars [A-Za-z0-9], like a post id
  owner        TEXT NOT NULL UNIQUE,         -- sha256(community key): one station per phone
  name         TEXT NOT NULL,                -- 1–40 characters
  host_name    TEXT,                         -- "Show my name as", ≤ 40
  vibe         TEXT,                         -- the host's line, ≤ 60
  auto_live    TEXT NOT NULL DEFAULT 'off' CHECK (auto_live IN ('off', 'schedule', 'always')),
  schedule     TEXT,                         -- JSON {tz, windows: [{day 1–7 (Mon = 1), start "HH:MM", end "HH:MM"}]}
  created_at   INTEGER NOT NULL,
  last_live_at INTEGER,                      -- the room's last directory write
  up           INTEGER NOT NULL DEFAULT 0,
  down         INTEGER NOT NULL DEFAULT 0,
  vouched      INTEGER NOT NULL DEFAULT 0,
  removed_at   INTEGER,
  removed_by   TEXT CHECK (removed_by IN ('owner') AND (removed_by IS NULL) = (removed_at IS NULL))  -- only the Stash owner removes; a host deletes outright
);
CREATE INDEX stations_schedule ON stations (auto_live) WHERE auto_live = 'schedule' AND removed_at IS NULL;

-- As posts' votes: one row per phone, the network for the per-network cap.
CREATE TABLE station_votes (
  station_id TEXT NOT NULL,
  voter      TEXT NOT NULL,
  ip_hash    TEXT NOT NULL,
  value      INTEGER NOT NULL CHECK (value IN (-1, 1)),
  at         INTEGER NOT NULL,
  PRIMARY KEY (station_id, voter)
);
-- 2 minutes listening to the station's sessions, in total (the app counts and sends it once).
CREATE TABLE station_heard (
  station_id TEXT NOT NULL,
  voter      TEXT NOT NULL,
  at         INTEGER NOT NULL,
  PRIMARY KEY (station_id, voter)
);
CREATE TABLE station_follows (
  station_id TEXT NOT NULL,
  follower   TEXT NOT NULL,
  at         INTEGER NOT NULL,
  PRIMARY KEY (station_id, follower)
);
CREATE INDEX station_follows_follower ON station_follows (follower);

-- The sessions on the air, written by their rooms (1b). A row the room hasn't touched for 3 minutes is ignored and swept.
CREATE TABLE live (
  station_id TEXT PRIMARY KEY,
  room       TEXT NOT NULL,                  -- the room code: public on purpose
  track      TEXT,                           -- SharedTrack JSON of the song playing
  listeners  INTEGER NOT NULL DEFAULT 0,
  paused     INTEGER NOT NULL DEFAULT 0,
  crowd      TEXT NOT NULL DEFAULT '[]',     -- up to 4 [{i: initial, id: memberId}], host first
  started_at INTEGER NOT NULL,               -- the room's createdAt: a newer room replaces an older one's row
  seen_at    INTEGER NOT NULL
);
```

- [ ] Test (`node:test`, `fakeD1()`):
  - one station per owner (a second INSERT with the same owner violates UNIQUE);
  - `auto_live` refuses `'sometimes'`;
  - `removed_by` and `removed_at` must be set together.
- [ ] `npm test`, then commit `feat(sessions): stations schema (0007)`.

### Task 2: Shared Community helpers

`identity`, `network`, `networkHash`, `readJson` and the error helpers are private to `community.js`; the station routes need them too.

**Files:** create `src/community-shared.js`; modify `src/community.js` (import from it, no behaviour change).

- [ ] Move `KEY_HEADER`, `rateLimited`, `notAllowed`, `gone`, `identity`, `network`, `networkHash`, `readJson`, `heardRequired` and `HIDE_AT` / `VOTES_PER_NETWORK` / `VOTE_ROWS_PER_NETWORK` / `MAX_REQUEST_BYTES` into `community-shared.js` as named exports. Re-export the constants from `community.js`, so the existing tests and `scripts/community.mjs` imports keep working.
- [ ] `npm test`: no test changes, all green. Commit `refactor(community): shared request helpers`.

### Task 3: Station rules (pure)

**Files:** create `src/stations.js`; test `test/stations-rules.test.js`.

- [ ] Write the tests first, then the code:
  - **Fields:**
    - `cleanStationFields({name: "  Rawn's Basement "})` → `{name: "Rawn's Basement"}`;
    - an empty or 41-character name → null;
    - `vibe` over 60 → null;
    - `vibe: ""` on a partial → `{vibe: null}` (clears it);
    - `autoLive: "always"` → kept, `"sometimes"` → null;
    - with `partial`, an empty object → null (nothing to change).
  - **Schedule:**
    - `cleanSchedule` accepts `{tz: "America/New_York", windows: [{day: 1, start: "09:00", end: "17:00"}]}`;
    - it rejects: an unknown tz, more than 7 windows, day 0 or 8, `"9:00"`, `"24:00"`, `start === end`, and extra keys (the extras are dropped, not stored).
  - **`nextWindow(schedule, now)`** returns `{start, end}` for the window in progress, or the soonest one to come, or null. Cases:
    - Mon 09:00–17:00 New York, asked Mon 10:00 local → in progress (start ≤ now < end);
    - asked Mon 18:00 → next Monday 09:00;
    - Fri 21:00–01:00 (crosses midnight), asked Sat 00:30 → in progress, end Sat 01:00;
    - DST: New York 2026-03-08, a Sun 02:30 start → 03:30 EDT (the skipped hour starts after the gap); 2026-11-01, a Sun 01:30 start → the first 01:30 (EDT);
    - Asia/Kolkata (+05:30) and Australia/Lord_Howe (30-minute DST) give the right instants;
    - no windows → null.
  - **`summaryOfStation(row, me)`:**
    - gives `{id, name, hostName, vibe, up, down, followers}`;
    - with `me` set, also `myVote`, `mine`, `heard`, `following`;
    - `owner` never appears in the output.
- [ ] The time-zone code (the rest is plain validation, written like `community-post.js`):

```js
const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const formats = new Map();
function wallParts(t, tz) {
    let f = formats.get(tz);
    if (!f) {
        f = new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit",
            day: "2-digit", hour: "2-digit", minute: "2-digit", weekday: "short" });
        formats.set(tz, f);
    }
    const p = Object.fromEntries(f.formatToParts(new Date(t)).map((x) => [x.type, x.value]));
    return { y: +p.year, mo: +p.month, d: +p.day, h: +p.hour, mi: +p.minute };
}
/** Local wall time at instant t, as if it were UTC (whole minutes). */
const wallAt = (t, tz) => { const p = wallParts(t, tz); return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi); };
const offsetAt = (t, tz) => wallAt(t, tz) - Math.floor(t / 60_000) * 60_000;
/**
 * The instant a local wall time (given as a UTC timestamp of the same digits) happens in tz. Ambiguous (fall back): the
 * first one. Skipped (spring forward): just after the gap.
 */
export function instantOf(wall, tz) {
    const first = wall - offsetAt(wall, tz);
    const second = wall - offsetAt(first, tz);
    return wallAt(second, tz) === wall ? second : first;
}
const minutes = (hhmm) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3));

/** The window in progress at `now`, or the next to start; null without windows. Looks from yesterday to a week ahead. */
export function nextWindow(schedule, now) {
    if (!schedule?.windows?.length) return null;
    const today = wallParts(now, schedule.tz);
    let best = null;
    for (let k = -1; k <= 7; k++) {
        const date = Date.UTC(today.y, today.mo - 1, today.d + k);
        const day = ((new Date(date).getUTCDay() + 6) % 7) + 1; // Mon = 1 … Sun = 7
        for (const w of schedule.windows) {
            if (w.day !== day) continue;
            const s = minutes(w.start), e = minutes(w.end);
            const start = instantOf(date + s * 60_000, schedule.tz);
            const end = instantOf(date + (e > s ? e : e + 1440) * 60_000, schedule.tz);
            if (end <= now) continue;
            if (!best || start < best.start) best = { start, end };
        }
    }
    return best;
}
```

- [ ] Commit `feat(sessions): station rules and schedule windows`.

### Task 4: The switch, and creating, reading, changing and deleting a station

**Files:** create `src/stations-api.js`; modify `src/community.js` (route `stations…`, `live` and `upcoming` paths to it, after the read rate limit it already applies to every GET); modify `wrangler.toml` (`SESSIONS = "0"` under `[vars]`). Test: `test/stations-crud.test.js` (env from `community-helpers.js` plus `SESSIONS: "1"`).

Routes (all JSON, errors `{error}` as Community's):

| Route | Key | Answer |
|---|---|---|
| `POST /v1/community/stations` `{name, hostName?, vibe?}` | required | 201 `{id}`; 409 `{error: "have_station", id}`; 403 `blocked`; 400 `bad_request` |
| `GET /v1/community/stations/mine` | required | `{station: null}` or `{station: Summary + {autoLive, schedule, hidden, lastLiveAt, deleteAt}}` |
| `GET /v1/community/stations/{id}` | optional | `{station: Summary + {schedule?, next?}, live?: {room, track, listeners, paused, full}}`; 404 `gone` if removed, or hidden and not yours |
| `PATCH /v1/community/stations/{id}` `{name?, hostName?, vibe?, autoLive?, schedule?}` | owner | 200 the `mine` shape; 403 `not_yours` |
| `DELETE /v1/community/stations/{id}` | owner | 204; deletes its votes, heard marks, follows and live row in one batch |

- [ ] Tests:
  - `SESSIONS` unset or `"0"` → every route 503 `{error: "sessions_off"}`, and Community's post routes are unaffected;
  - create, then `mine` shows it with `deleteAt = COALESCE(last_live_at, created_at) + 7 days`;
  - a second create gets 409 with the first id;
  - a blocked phone gets 403;
  - a 41-character name gets 400;
  - `PATCH` by another key gets 403, and an owner's `PATCH {schedule}` with a bad tz gets 400;
  - `GET` of a hidden station (up − down ≤ −3, not vouched) is 404 for others and 200 for the owner;
  - `DELETE` leaves no rows in the four tables;
  - writes go through `COMMUNITY_WRITE_RL` (429 when it says no).
- [ ] Create retries a colliding id like posts do: `INSERT … ON CONFLICT DO NOTHING RETURNING id`, three tries, then 503. A conflict on `owner` is read back as `have_station`.
- [ ] Commit `feat(sessions): station routes behind SESSIONS`.

### Task 5: Votes and heard

**Files:** modify `src/stations-api.js`; test `test/stations-votes.test.js` (model it on `community-votes.test.js` and `community-heard.test.js`).

- [ ] `PUT stations/{id}/vote {value: -1|0|1}` → `{up, down, myVote}`, and `PUT stations/{id}/heard` → 204: `vote()` and `heard()` from `community.js`, line for line, on `station_votes` / `station_heard`, with:

```js
export const STATION_RECOUNT_SQL = `UPDATE stations SET
    up = (SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT COUNT(*) AS n FROM station_votes WHERE station_id = ?1 AND value = 1 GROUP BY ip_hash)),
    down = (SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT COUNT(*) AS n FROM station_votes WHERE station_id = ?1 AND value = -1 GROUP BY ip_hash))
    WHERE id = ?1 RETURNING up, down`;
```

- [ ] Tests (the post tests' cases, on stations):
  - own station → 403 `own_station`;
  - blocked → 403;
  - a vote without a heard mark → 403 `listen_first` whatever `HEARD_REQUIRED` says (system spec §3: no app ever voted on a station without one; the switch is only for posts' older votes), and `0` always allowed;
  - 3 voters behind one network count 2;
  - `VOTE_ROWS_PER_NETWORK` holds;
  - voting on a removed or hidden station → 404;
  - the vote rate limit applies.
- [ ] Commit `feat(sessions): station votes and heard`.

### Task 6: Following

**Files:** modify `src/stations-api.js`; test `test/stations-follow.test.js`.

- [ ] `PUT stations/{id}/follow {on: true|false}` → `{following, followers}`:
  - your own station → 403 `own_station`;
  - a removed or hidden station → 404;
  - at most **200** follows per phone (409 `too_many`);
  - counted with `COMMUNITY_VOTE_RL`.
- [ ] `GET stations/following` (key required) → `{stations: [Summary + {live?, next?}]}`:
  - live ones first (live row fresh, by listeners), then by `next.start`;
  - hidden and removed stations left out.
- [ ] Tests: follow, unfollow, the cap, the ordering with two live stations and two scheduled ones, and a followed station that gets hidden disappearing from the list.
- [ ] Commit `feat(sessions): follow stations`.

### Task 7: Live now and Upcoming

**Files:** modify `src/stations-api.js`; test `test/stations-live.test.js`. Seed `live` rows directly (1b writes them for real).

- [ ] `GET live?limit=N` (1–50, default 10):

```sql
SELECT s.id, s.owner, s.name, s.host_name, s.vibe, s.up, s.down,
       l.room, l.track, l.listeners, l.paused, l.crowd, l.started_at,
       v.value AS my_vote, h.voter IS NOT NULL AS heard, f.follower IS NOT NULL AS following,
       (SELECT COUNT(*) FROM station_follows c WHERE c.station_id = s.id) AS followers
FROM live l JOIN stations s ON s.id = l.station_id
LEFT JOIN station_votes v ON v.station_id = s.id AND v.voter = ?1
LEFT JOIN station_heard h ON h.station_id = s.id AND h.voter = ?1
LEFT JOIN station_follows f ON f.station_id = s.id AND f.follower = ?1
WHERE l.seen_at > ?2 AND s.removed_at IS NULL AND ((s.up - s.down) > -3 OR s.vouched = 1)
  AND NOT EXISTS (SELECT 1 FROM blocked b WHERE b.poster = s.owner)
ORDER BY (s.up - s.down) + l.listeners * 1.0 DESC, l.started_at ASC
LIMIT ?3
```

  with `?2 = now − 3 min`. Each answer is `{station: Summary, room, track, listeners, paused, crowd, startedAt, full: listeners >= 50}`, sent with `cache-control: no-store`.
- [ ] `GET upcoming?limit=N` (1–50, default 10): read every `auto_live = 'schedule'` station that isn't removed, hidden, blocked or live now (a fresh `live` row), work out `nextWindow` for each, keep those whose start is after now and within 24 h, sort by start, and cut to the limit. Each answer is `Summary + {next: {start, end}}`. It isn't cached (build plan, decision 11).
- [ ] Tests:
  - ranking: a station with 48 votes and 12 listeners is above one with 31 and 27;
  - a row seen 4 minutes ago is ignored;
  - hidden, removed and blocked stations are left out;
  - `full` at 50;
  - `myVote`, `heard` and `following` only with a key;
  - Upcoming across a time zone and the DST weeks, a station live now left out, the 24-hour edge (exactly 24 h in, 24 h + 1 min out).
- [ ] Commit `feat(sessions): live now and upcoming`.

### Task 8: The daily cleanup

**Files:** modify `src/stations-api.js` (export `cleanupStations`), `src/index.js` (`scheduled` runs it next to `cleanup`); test `test/stations-cleanup.test.js`.

- [ ] In one batch:
  - `live` rows with `seen_at` older than 3 minutes;
  - stations where `COALESCE(last_live_at, created_at) < now − 7 days`, or `removed_at < now − 1 day`;
  - their votes, heard marks, follows and live rows.
- [ ] Tests:
  - a station 6 days 23 h idle stays, and at 7 days it goes with all its rows;
  - one that went live yesterday stays;
  - a removed one stays a day (restorable), then goes;
  - posts are untouched.
- [ ] Commit `feat(sessions): stations idle 7 days are deleted`.

### Task 9: The owner's station commands

**Files:** modify `scripts/community.mjs` (`commandSql` cases), its README section; test: extend `test/community-cli.test.js`.

- [ ] The commands are hyphenated, because the CLI passes every argument on to `commandSql` and a third word would land in its `now`:
  - `station-list`: the latest 50, with id, name, host name, score, live or not, removed, and the first 8 characters of the owner;
  - `station-remove <id>`: `removed_by = 'owner'`;
  - `station-restore <id>`: only the owner's own removals; drops the downvotes and vouches, as `restore` does for posts;
  - `station-block <id>`: blocks the station's phone in `blocked` (posting, voting and going live), then removes the station.
- [ ] Each one prints what it hit. A live session ends within a minute, when its room's next directory write is refused (1b, task 18).
- [ ] Tests: the SQL for each command, and the guard against `--`, `"` and `%` still holding.
- [ ] Commit `feat(sessions): owner's station commands`.

### Task 10: The station page, the docs, the PR

**Files:** modify `src/pages.js` (`stationPage`), `src/index.js` (`GET /s/{id}`), `wrangler.toml` (route `{ pattern = "stashfm.app/s/*", zone_name = "stashfm.app" }`), `README.md`, the system spec (a "Built with these refinements" note pointing to the build plan's decisions); test `test/station-page.test.js`.

- [ ] First check that nothing else on stashfm.app serves `/s/`: the site Worker's routes (memory: `infra_stashfm_site_manual_deploy`) and `grep -rn '"/s/' web infra`. If something does, stop and ask the owner.
- [ ] The page is built like `roomPage`:
  - the station name, the host's name and the vibe line;
  - "Live now · 12 listening" with the song, or the hours ("Weekdays 9:00–17:00 (New York time)");
  - **Open in Stash** (`openInStash`);
  - with `SESSIONS` off, or the station hidden or removed: `messagePage("This station isn't available", …)`.
- [ ] Tests: the live page, the scheduled page, the gone page, HTML escaping of a hostile name (`<script>`), and `SESSIONS = "0"`.
- [ ] Docs:
  - README "Sessions": the routes, the switch, the commands, the cleanup;
  - a contract block for the app (request and response shapes of every route above).
- [ ] `npm test` all green, then `gh pr create` "Sessions 1a: stations (dark)". No deploy yet: 1a and 1b deploy together (task 21).

### 1a as built (2026-11-21)

Where the build differs from the tasks above, the code and `infra/share-worker/README.md` "Sessions" (the app's contract) win:

- **Heard is always required** for a +1 or −1 on a station (task 5 said "behind `HEARD_REQUIRED`"). The switch exists because older apps voted on posts without heard marks; no app ever voted on a station, so there's nothing to wait for. This is the system spec's own rule (§3).
- **`station_votes_net`**, an index on `station_votes (station_id, ip_hash, value)`, is in 0007, as `votes_net` is for posts: the per-network count and the recount walk it.
- **Stopping a follow always works**, even for a station hidden or removed since: it only deletes the caller's row. Otherwise a station hidden after you followed it would hold one of your 200 places with no way to free it.
- **A station the owner removed stays removed:** its phone's `DELETE` is 404 `gone`, as for a removed post, so deleting it to start a fresh station at once can't skip the day it can be restored in. `mine.deleteAt` follows the cleanup (a day after a removal).
- **The cleanup does nothing while `SESSIONS` is off**, so the Worker can be deployed before 0007 is applied, and no host loses a station while nobody can go live. README "Sessions" gives the one-line reset for coming back after more than a week off.
- **A removal's day is measured on the Worker's clock too:** a station removed over a day ago goes only once it also hasn't been made or on the air in that day, as posts use `created_at`, because `removed_at` comes from the PC running the command line.
- **The station page** shows the status as one line ("Live now · 12 listening · full · paused", "On the air: Weekdays 9:00–17:00 (New York time)", "Not live right now.", plus "It goes live whenever Rawn plays music in Stash." when always on). The room's code is not on the page: Open in Stash hands the app `/s/{id}`, and the app joins. `stashfm.app/s/*` was free: on 2026-11-21 the site answered `/s/…` with its own 404.
- **`station-list [n]`** takes a count as `list` does (50 if not given). After `station-block` and `unblock` the station stays down until `station-restore`; a phone blocked from a post with `block` keeps its station, hidden while the block lasts.
- Found in task 17's SQL while building 1a: `last_live_at` takes `?8` (`seen_at`), not `?2` (the room code). Fixed above.

---

## 1b: public rooms

`room.js` stays pure; everything below is `step()` logic with `node --test` cases in a new `test/room-public.test.js` (helpers as in `test/room.test.js`), except tasks 12, 18 and 19, which touch the shell and the Worker (`test/listen-room.test.js`, `test/rooms.test.js`). **A private room must behave exactly as today.** Every change is behind `s.pub`: each new message handler starts with `if (!ctx.s.pub) return false`. `PROTOCOL_V` stays 2 for private rooms, and all 90 existing room tests stay untouched and green.

### Task 11: A public room, its size, and protocol v3

**Files:** `src/room.js`.

- [ ] Constants:

```js
export const MAX_LISTENERS = 50;
export const MAX_COHOSTS = 3;
export const MAX_PUBLIC_MEMBERS = 1 + MAX_COHOSTS + MAX_LISTENERS; // 54 in all
export const PUBLIC_V = 3;                    // what a public room speaks, and the least an app must say in hello
export const PAUSE_END_MS = 15 * 60_000;      // paused this long, a session ends
export const LIVE_BEAT_MS = 60_000;           // the directory row's heartbeat
export const MAX_REQUESTS_EACH = 3;
export const MAX_REQUESTS = 30;
export const MAX_BOOTED = 200;
export const capacity = (s) => (s.pub ? MAX_PUBLIC_MEMBERS : MAX_MEMBERS);
```

- [ ] `createRoom({code, hostName, keyHash, stationId}, now)`: with `stationId`, it also sets
  `pub: { stationId, cohosts: [], booted: [], autoAccept: false, presence: "booth", pausedSince: now, liveAt: null, liveKey: null }`.
  `cohosts` and `booted` hold **key hashes**, never member ids, so they survive a reconnect (build plan, decision 3).
- [ ] In a public room:
  - `guestsCanAdd(s)` is false;
  - `publicState` sends `suggestions: []`, `v: PUBLIC_V` and `session: sessionInfo(s)`;
  - `preview` uses `capacity(s)` and adds `{station, listeners}`.
- [ ] `hello`, public room only, before anything else:
  - `msg.v < PUBLIC_V` → reject `{code: 4426, reason: "update"}`;
  - neither `event.hostKeyOk` nor `event.listenerHash` → reject `4401 key`;
  - a `listenerHash` in `booted` → reject `4403 booted`.
- [ ] The cap is `capacity(s)`, not `MAX_MEMBERS`. A new member stores `kh: event.listenerHash ?? null`; `publicMembers` already lists only safe fields, so `kh` never leaves.
- [ ] Tests:
  - a private room still caps at 10 and still answers `v: 2`;
  - a public room takes 54 and refuses the 55th with 4409;
  - a v2 hello gets 4426;
  - a hello with no key gets 4401;
  - no frame ever contains a `kh` value (stringify every `out` and search it).
- [ ] Commit `feat(sessions): public rooms, 54 members, v3`.

### Task 12: Who you are: the key hash, from the shell

**Files:** `src/listen-room.js`; test `test/listen-room.test.js`.

- [ ] In `webSocketMessage`'s hello branch, next to `hostKeyOk`:

```js
const listenerHash = typeof msg.communityKey === "string" && validEditKey(msg.communityKey)
    ? await sha256Hex(msg.communityKey) : null;
```

  Pass it in the hello event. The raw key is never stored, logged or echoed.
- [ ] The socket caps in `fetch("/ws")` use `capacity(state)`: `members.length >= capacity(state)` and `getWebSockets().length >= capacity(state) * 2`.
- [ ] `request` gets a `newId` like `suggest` and `add`.
- [ ] Tests:
  - with the fake ctx, the key's hash lands in storage and never in any sent frame;
  - 54 sockets connect to a public room and the 55th gets 409;
  - a private room still stops at 10.
- [ ] Commit `feat(sessions): listeners known by their key's hash`.

### Task 13: Listeners never hold up the music

**Files:** `src/room.js`.

- [ ] `startDue`: in a public room the room is settled once the host is: `hostSettled(s) && (s.pub || allSettled(s) || now >= s.phase.deadlineMs)`, with `hostDeadline` still the cap.
- [ ] `status` from anyone but the host in a public room: `return false` (no write, no `members` broadcast). The host's status still counts.
- [ ] `hello`, `close` and the alarm's expiry of a member, in a public room, also send `sessionMsg(s)` (the listener count changed).
- [ ] Tests:
  - the host ready with 50 listeners buffering → the song starts at once;
  - a listener's `ready` makes no write and sends nothing;
  - the same for a private room (unchanged: it waits for everyone up to 8 s).
- [ ] Commit `feat(sessions): the host's handshake only`.

### Task 14: How a session ends

**Files:** `src/room.js`.

- [ ] `setTimeline`, public room: `s.pub.pausedSince = timeline.playing ? null : (s.pub.pausedSince ?? ctx.now)`.
- [ ] `nextAlarm`, public room: also `pausedSince + PAUSE_END_MS` when it isn't null, and `liveAt + LIVE_BEAT_MS` once `liveAt` is set.
- [ ] `alarm`, public room:
  - `pausedSince` 15 minutes ago → `ended {reason: "paused"}`, closed;
  - the host's slot expired (60 s gone) → `ended {reason: "host_left"}`, closed, never a hand-over;
  - room age past `MAX_AGE_MS` → reason `"max_age"` (a private room keeps `"closed"`).
- [ ] `makeHost` in a public room → `return false`.
- [ ] New event `stationGone {reason}` (from the shell, tasks 18 and 19) → `ended {reason}`, closed.
- [ ] Tests:
  - pause, then +15 min → ended paused; pause, play at +14 min, +15 min → still open;
  - a room whose host never plays → ended paused at +15 min;
  - host drops → at +59 s still open, at +60 s ended host_left;
  - `makeHost` ignored;
  - 12 h → max_age.
- [ ] Commit `feat(sessions): sessions end with their host`.

### Task 15: Requests

**Files:** `src/room.js`.

- [ ] Helpers:

```js
const memberById = (s, id) => s.members.find((m) => m.id === id);
const isCohost = (s, id) => !!s.pub && !!memberById(s, id)?.kh && s.pub.cohosts.includes(memberById(s, id).kh);
const isStaff = (s, id) => id === s.host || isCohost(s, id);
/** Waiting requests: all of them to the host and co-hosts, a listener's own to [touched] (whose list just changed). */
function pushRequests(ctx, touched) {
    const { s } = ctx;
    for (const m of connected(s)) if (isStaff(s, m.id)) ctx.out.push({ to: m.id, msg: { t: "requests", requests: s.suggestions } });
    if (touched && !isStaff(s, touched) && findConnected(s, touched)) {
        ctx.out.push({ to: touched, msg: { t: "requests", requests: s.suggestions.filter((x) => x.from === touched) } });
    }
}
```

  Waiting requests live in `s.suggestions`, which a public room never broadcasts.
- [ ] Messages:
  - **`request {track}`** (a listener only; staff add directly):
    - with Auto-accept on, it goes into the queue as Add to queue, through the same size checks as `addSongs` (the 200-song and character caps), and the requester gets `requestAnswer {state: "queued"}`;
    - otherwise it waits: at most `MAX_REQUESTS_EACH` per listener and `MAX_REQUESTS` in all, or `requestAnswer {state: "full"}`;
    - then `pushRequests(ctx, from)`.
  - **`requestAction {id, action}`** (staff):
    - `next` / `queue`: `insertAdds(s, [{...track, by: requester}], action === "next")`, `settle`, `queueMsg`;
    - `pass`: `requestPassed {track}` to the requester only;
    - then `pushRequests(ctx, requester)`.
  - **`autoAccept {on}`** (host only, added to `HOST_ONLY`): turning it on accepts everything waiting, in order, as Add to queue; then `sessionMsg`, `queueMsg` and the requests lists.
- [ ] In a public room: `suggest` and `suggestion` → `return false`, and `add` from a listener → `return false`.
- [ ] The welcome is followed by that member's `requests` and the `session` message.
- [ ] Tests:
  - a listener's request reaches the host and co-hosts, nobody else, and the requester sees only their own;
  - a 4th waiting → full; the 31st in all → full;
  - next / queue land where Play next and Add to queue do, stamped with the requester;
  - pass reaches only the requester;
  - Auto-accept on drains the waiting list in order;
  - a requester gone for 60 s → their requests drop and staff hear.
- [ ] Commit `feat(sessions): requests wait for the host or a co-host`.

### Task 16: Co-hosts

**Files:** `src/room.js`.

- [ ] **`cohost {memberId, on}`** (host only):
  - the target must be connected, not the host, and have a `kh`;
  - `on` adds its `kh` to `s.pub.cohosts`, up to `MAX_COHOSTS`; past that the host gets `refused {what: "cohost", reason: "max_cohosts"}`;
  - `off` removes it;
  - then `sessionMsg`, and that member's new requests view.
- [ ] **Co-hosts' powers:**
  - `add` in a public room: allowed for staff (`addSongs`, a co-host's 20 waiting as a guest's);
  - `unqueue` in a public room: staff may take any song.
- [ ] **`command {action: "skip"}`** (a co-host): forwarded as-is to the host, plus `from` and the current `trackKey` (so a host whose song already changed ignores it), when the host is connected. Otherwise the co-host gets `refused {what: "skip", reason: "host_reconnecting"}`. It doesn't change the room.
- [ ] `sessionInfo(s)` = `{station, listeners: connected(s).length - 1, cohosts: [connected co-hosts' memberIds], autoAccept, presence}`.
- [ ] Tests:
  - make and unmake a co-host;
  - the 4th refused;
  - a co-host who drops and comes back (same key, new socket) is still a co-host;
  - a co-host's add and unqueue work, and a listener's don't;
  - a skip reaches the host with the trackKey; with the host dropped it's refused;
  - a listener's `command` is ignored.
- [ ] Commit `feat(sessions): co-hosts`.

### Task 17: Booting and presence

**Files:** `src/room.js`, `src/listen-room.js`.

- [ ] **`boot {memberId}`** (host only, added to `HOST_ONLY`):
  1. Refused for the host and for co-hosts (take co-host away first).
  2. Remember the member's `kh` in `booted` (capped at `MAX_BOOTED`).
  3. Send them `ended {reason: "booted"}` first, while they're still bound.
  4. Drop their member slot, their requests and their react clock, and set `ctx.kick = id`.
  5. Then `membersMsg`, `sessionMsg`, and `pushRequests`.
  - `step()` returns `kick`, and the shell closes that member's sockets with `4403 booted` after sending the frames.
- [ ] **`presence {state: "booth" | "away"}`** (host only): stored, then `sessionMsg`.
- [ ] Tests:
  - a booted listener gets the ended frame, then a 4403 close;
  - the same key comes back with a new socket and is refused 4403, and the same token can't rejoin either;
  - booting a co-host is refused;
  - presence reaches everyone;
  - a private room ignores all of these.
- [ ] Commit `feat(sessions): booting and presence`.

### Task 18: The live directory

**Files:** `src/room.js` (the intent), `src/listen-room.js` (the writes); tests in `test/room-public.test.js` and `test/listen-room.test.js` (fake ctx + `fakeD1()` in `env.COMMUNITY_DB`).

- [ ] In `step()`, after the handler, for a public room that isn't closing:

```js
const liveKeyOf = (s) => `${s.trackKey}|${s.timeline.playing ? 1 : 0}`;
const initialOf = (name) => (name ? [...name][0].toUpperCase() : null);
function crowdOf(s) {
    const host = findConnected(s, s.host);
    const rest = connected(s).filter((m) => m.id !== s.host).sort((a, b) => a.joinedAt - b.joinedAt);
    return [...(host ? [host] : []), ...rest].slice(0, 4).map((m) => ({ i: initialOf(m.name), id: m.id }));
}
/** A row to write when the song or play/pause changed, or the minute's heartbeat is due; nothing before the first song. */
function liveRow(s, now) {
    const p = s.pub;
    if (!s.track) return null;
    if (p.liveAt !== null && liveKeyOf(s) === p.liveKey && now < p.liveAt + LIVE_BEAT_MS) return null;
    p.liveAt = now;
    p.liveKey = liveKeyOf(s);
    return { upsert: { stationId: p.stationId, room: s.code, track: s.track, listeners: connected(s).length - 1,
        paused: !s.timeline.playing, crowd: crowdOf(s), startedAt: s.createdAt } };
}
```

  A row means the state changed (`liveAt` moved), so it's stored. On close, a public room returns `live: {remove: true, stationId, room}`.
- [ ] The shell (`apply`): after storing, `if (r.live)`, call `writeLive(r.live)`. If that returns a reason, apply `{type: "stationGone", reason}`. One batch:

```sql
-- 1: write our row, only for a station that's still allowed, and never over a newer room's row
INSERT INTO live (station_id, room, track, listeners, paused, crowd, started_at, seen_at)
SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8 FROM stations s
 WHERE s.id = ?1 AND s.removed_at IS NULL AND ((s.up - s.down) > -3 OR s.vouched = 1)
   AND NOT EXISTS (SELECT 1 FROM blocked b WHERE b.poster = s.owner)
ON CONFLICT (station_id) DO UPDATE SET room = excluded.room, track = excluded.track, listeners = excluded.listeners,
   paused = excluded.paused, crowd = excluded.crowd, started_at = excluded.started_at, seen_at = excluded.seen_at
 WHERE live.room = excluded.room OR excluded.started_at > live.started_at;
-- 2: a station no longer allowed loses its row
DELETE FROM live WHERE station_id = ?1 AND NOT EXISTS (SELECT 1 FROM stations s WHERE s.id = ?1 AND s.removed_at IS NULL
   AND ((s.up - s.down) > -3 OR s.vouched = 1) AND NOT EXISTS (SELECT 1 FROM blocked b WHERE b.poster = s.owner));
-- 3
UPDATE stations SET last_live_at = ?8 WHERE id = ?1;   -- ?8 = seen_at, now (not ?2, the room code)
-- 4: whose row it is now
SELECT room FROM live WHERE station_id = ?1;
```

  - Statement 4 finds no row → `"station_removed"`; another room's row → `"replaced"` (the app follows the station to its new room, step 2).
  - Remove: `DELETE FROM live WHERE station_id = ?1 AND room = ?2`.
  - A D1 error is logged and swallowed: a database hiccup never ends a session, and the next heartbeat writes again.
- [ ] Tests:
  - the first song writes the row;
  - a new song and a pause write again, a join doesn't, and the minute's heartbeat does;
  - the end deletes the row;
  - an older room's late write can't take a newer room's row, and the older room ends `replaced`;
  - the owner's `station-remove` (an UPDATE in the fake D1) → the next write ends the room `station_removed` and the row is gone;
  - a throwing D1 leaves the room open;
  - `crowd` holds initials and member ids only, host first.
- [ ] Commit `feat(sessions): sessions write the live directory`.

### Task 19: Opening a session, and ending one from outside

**Files:** `src/index.js` (`createRoom`), `src/listen-room.js` (`/init`, `/station-gone`), `src/stations-api.js` (DELETE pokes the room); test `test/rooms.test.js`, `test/stations-crud.test.js`.

- [ ] `POST /v1/rooms {public: true, stationId, hostName?}` with `X-Stash-Community-Key`:
  - `SESSIONS` not `"1"` → 503 `sessions_off`;
  - a bad key → 401 `bad_key`;
  - the station removed or hidden → 404 `gone`;
  - another phone's station → 403 `not_yours`;
  - a blocked phone → 403 `blocked`;
  - otherwise `/init` with `stationId`, and the answer is `{code, hostKey, url: <origin>/s/<stationId>}`.
  - `ROOM_RL` as today. A body without `public` is today's private room, byte for byte.
- [ ] Internal `POST https://room/station-gone {reason}` applies `stationGone`. `DELETE stations/{id}` reads the station's `live` row before deleting and calls it on that room, with `station_removed`.
- [ ] Tests:
  - each refusal;
  - a public create, then a v3 hello with the host key hosts it;
  - deleting a station while it's live ends the room and its listeners get `ended station_removed`;
  - a private create unchanged.
- [ ] Commit `feat(sessions): opening a session needs your station`.

### Task 20: Load check

**Files:** create `scripts/room-load.mjs` (not run in CI).

- [ ] Against `wrangler dev` (`npm run dev`, with `SESSIONS = "1"` in a `.dev.vars`):
  1. create a station and a session;
  2. connect a host and 53 listeners (`ws` from Node 24's global WebSocket);
  3. play 20 songs;
  4. send a reaction from every listener every second.
  It prints frames received per listener, the largest stored state (`JSON.stringify(room).length`) and the slowest round trip.
- [ ] Record the numbers in the PR description. If the stored state passes 1 MB or a round trip takes over a second, stop and tell the owner before going on.
- [ ] Commit `chore(sessions): a 54-socket load check`.

### Task 21: Docs, the PR, the deploy

- [ ] README "Sessions protocol": the `hello` additions (`communityKey`, `v: 3`), every message in tasks 11–19 with who may send it, and the close codes 4401 / 4403 / 4426. This is the contract step 2 builds against.
- [ ] `npm test`, then `gh pr create` "Sessions 1b: public rooms (dark)". Wait for CI green.
- [ ] **Ask the owner** before each of these, separately:
  1. `npx wrangler d1 migrations apply stash-community --remote` (0007);
  2. deploying `stash-share` (`npm run deploy`, `SESSIONS = "0"`).
- [ ] After the deploy:
  - `GET https://stashfm.app/v1/community/live` → 503 `sessions_off`;
  - `GET https://stashfm.app/s/abcdefgh` → "This station isn't available";
  - **a private Listen Together room on the Pixel 5 and the Pixel 6** (create, join, skip, a guest's Play next, reactions): exactly as before.
- [ ] Merge 1a, then 1b (retarget 1b to master before squash-merging 1a; memory `infra_stacked_pr_squash_merge`).
- [ ] Update the Sessions memory: step 1 done, live and dark, PR numbers, the load numbers.

### 1b as built (2026-11-21)

PR #932, on #931. Where the build differs from the tasks above, the code and `infra/share-worker/README.md` "Sessions protocol" (the app's contract) win:

- **The directory hears of a change at most once every 5 s** (`LIVE_GAP_MS`): a new song, pause or play inside the gap waits for its end and goes as one write of how things are then. Otherwise a host's phone could make D1 write at its message rate (20 a second). The first song still writes at once, and the heartbeat stays a minute.
- **`step()`'s `live` is `{upsert: row}` or `{remove: {stationId, room}}`**; the song in the row goes without `by`.
- **`last_live_at` is stamped only while the row is this session's** (task 18's statement 3 gained `AND EXISTS (… room = ?2)`), so a session refused or replaced doesn't move the 7-day clock, and a removed station's dying room can't hold off its cleanup.
- **A session's reactions don't store the room.** The load check showed each one rewrote the whole room (~60 KB, 53 times a second at 53 listeners). In a session the room passes a reaction on without a clock, and the shell holds each socket to one a second in memory. A private room keeps its own stored clock, which allows exactly the same.
- **`request` is under the edit limit** with `add` and `unqueue` (with Auto-accept on, each is an add that sends every phone the queue); over it, `requestAnswer {state: "full"}`.
- **Requests that fit:** taking one checks the queue's 200 adds and `MAX_QUEUE_CHARS` (30 waiting requests could otherwise carry it past a frame); one that doesn't fit stays waiting and the taker hears `refused {what: "request", reason: "queue_full"}`. Auto-accept also holds a listener to a guest's 20 in the queue; past that a request waits.
- **A pass** tells the requester alone, `requestPassed {track, by}`; every request is answered `requestAnswer {state}`.
- **A co-host gone for good frees the place:** when no member holds their key hash any more (the 60 s prune), it leaves `cohosts`. Booting one is refused (`refused {what: "boot", reason: "cohost"}`), and a boot takes every slot on that key.
- **Opening a session** also refuses a station hidden by votes (404 `gone`), since its first directory write would end it, and takes the station's DJ name when the app sends none. Deleting a station reads its room from `live` and ends the session at once (the room's internal `/station-gone`); if the room can't be reached, it ends at its next write.
- **Directory initials are whole graphemes** (a flag stays a flag), and a name with none is `null`.
- **The load check** (`scripts/room-load.mjs`) measures the stored room offline as well: a full session is 61 KB as JSON; 20,000 seeded random moves with the longest songs reach 723 KB (866 KB in a 40,000-move search), under the 1 MB bar. Live against `wrangler dev`, 54 sockets: round trips p50 56 ms, max 291 ms with every listener reacting once a second; 545 ms at worst with all 53 in the same instant. Numbers in PR #932.
