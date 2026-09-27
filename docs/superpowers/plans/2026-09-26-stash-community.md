# Stash Community Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An opt-in Home section where Stash listeners post playlists, mixes and songs as frozen copies and vote them up or down, ranked by likes plus freshness.

**Architecture:** The existing `stash-share` Cloudflare Worker gains `/v1/community/*` routes backed by a D1 database (posts, votes, blocked), with per-phone keys (only their SHA-256 stored), per-network caps, and a daily cleanup cron. The app gets a `core/data/community` client and repository, a `PostTarget` + `LocalPostToCommunity` hook that every entry point uses, and a new `feature/community` module (Home section, See all, post screen, picker, confirm sheet) wired together in `StashNavHost`.

**Tech Stack:** Cloudflare Workers (ES modules), D1, `node --test` with Node 24's built-in `node:sqlite`; Kotlin, Jetpack Compose (Material3), Hilt, Room, DataStore, OkHttp, kotlinx.serialization, JUnit4, MockK, Truth.

**Spec:** `docs/superpowers/specs/2026-09-26-stash-community-design.md`. Read it before starting any task.

**Working directory:** the worktree `C:\Users\theno\Projects\MP3APK\.worktrees\community` (branch `feat/community`). Stage explicit paths only: never `git add -A` or `git add .` (the repo holds large untracked spike binaries). Check `git diff --cached --name-only` before every commit. End every commit message with the line `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

## As built (read this before the tasks)

The build followed this plan task by task, with a spec review and a code review after every task. Where the code departs from a task's text below, the code and this note are right; the task texts are left as they were written.

**Server**
- **Schema.** `posts_hot`, an expression index on the feed's exact ordering (`WHERE removed_at IS NULL`), replaces `posts_live`. There is still no index on `expires_at`: with literal values SQLite picks it and sorts again. `body` is the last column. `removed_by` (`'poster'` or `'owner'`, tied to `removed_at` by a CHECK) records who removed a post.
- **Posting.** A song post's title is its track's title, so a title can't be spoofed. Covers are cut to 4 before any is checked. The limits are defined once (`LIMITS_SQL`). An id collision retries through `ON CONFLICT (id) DO NOTHING`. `networkHash` fails closed without `COMMUNITY_SALT`.
- **Votes.** The upsert is an `INSERT … SELECT FROM posts`, so a vote can't outlive its post. The blocked check rides on the post lookup. `isGone(post, now)` is shared by opening, voting and taking down.
- **Take-down.** A hidden post answers 404 to anyone but its poster. The poster's take-down `UPDATE` is deliberately unguarded, so it always wins a race with the owner's `remove`.
- **Moderation CLI.** Every command prints what it hit.
  - `list` also shows blocked phones and `removed` (null, `poster` or `owner`).
  - `remove` only hits a live post.
  - `restore` undoes only the owner's own removals. Only when the post ends up live does it drop the downvotes and vouch for the post (`vouched`, migration 0002), so votes can't hide it again.
  - A guard rejects `--`, `"` and `%` before the SQL is collapsed onto one command line.
- **Tests.** 151 node tests, run in CI.

**App**
- **Keys.** Reading never makes a key (`existingKey()`); `me()` and `mine()` answer locally until a key exists. Settings backups leave the key out, on export and on restore, so a restore never changes a phone's Community identity.
- **Client.** A derived OkHttp client with a 15 s call timeout and no redirects. A bare 429 counts as rate-limited.
- **Repository.**
  - Every network call is refused while Community is off, so no request leaves the phone.
  - A take-down answered `gone` counts as done.
  - Names are cut without splitting an emoji.
  - `buildDocument` runs on `Dispatchers.Default`.
- **Votes, in the list and on the post screen.** A tap while a vote is out is sent after it. A refusal rolls back to the last vote the server confirmed.
- **Home layout.** `move()` is one atomic edit. The first turn-on puts Community at the top, and a hand-placed Community keeps its spot. Settings search finds "community".
- **Screens.**
  - Ages refresh on resume.
  - Ranks are sized in their own ems, so they fit at any font size.
  - Your own post's arrows are left out of TalkBack.
  - The post screen centers its header and wraps its actions (FlowRow). It shows progress, says why an open failed ("Slow down a moment."), and starts Like from the song's real liked state.
- **Posting.**
  - The picker lists only your playlists (CUSTOM) and mixes, A to Z.
  - A post in flight holds its sheet open, so a post can't go out twice or report on the wrong sheet.
  - Each opening of the sheet starts from Loading.
- **Wiring.**
  - The post route's Back and open act only while that entry is the top of a live back stack. A late take-down reply can't pop Home, and a callback left from before a rotation does nothing.
  - See my posts replaces a See all that's showing.
  - A Community screen that a tab restores after Community was turned off is closed at once.

**Deployed 2026-09-27.** D1 `stash-community` (`dfbf6783-001d-40ce-9412-1e56c9ab2d84`) with migrations 0001 and 0002, and Worker `stash-share` version `72ddf4fd`. Worker `f69039dc` (Community before `vouched`) runs fine on the migrated schema, so it's a safe rollback; `98c58f73` is the one before Community. Every shared mix and 6 other routes answered byte for byte the same across both deploys. Tested on the Pixel 5 and the Pixel 6 Pro; the live database was emptied afterwards.

**Decided 2026-09-27:**
- The Cloudflare account is on the Workers Paid plan, so capacity is fine.
- A song flagged as a wrong match posts as it is; someone who flagged it won't post it.
- Listen Together's privacy line in the README now mentions Cloudflare's 30-day recovery history.

**Left for later** (none blocks the release):
- **Phone-to-phone transfer.** Android 12+ may copy app files, the Community key included, despite `allowBackup="false"`.
- **Double-tap Back.** App-wide and older than this work: a fast double tap on any top-bar Back can blank the NavHost.
- **Shared mixes.** Follow and Save a copy navigate from a ViewModel coroutine, unguarded.
- **Listen Together names.** They are cut with `take(40)`, which can split an emoji.

---

## File Structure

### Server (`infra/share-worker`)

| File | Responsibility |
|---|---|
| `migrations/0001_community.sql` (new) | The D1 schema: `posts`, `votes`, `blocked`. |
| `src/http.js` (new) | Shared helpers moved out of `index.js`: `json`, `limitKey`, `ip`, plus the new `networkOf` (IPv4 as is, IPv6 by /56). |
| `src/community-post.js` (new) | Pure: `cleanPost` (validates and cleans a post request into body, summary and count) and `summaryOf` (a DB row to the public `PostSummary`). |
| `src/community.js` (new) | The routes (`communityRoute`), identity, limits, votes with the per-network recount, take-down, and `cleanup` for the cron. All SQL lives here. |
| `src/index.js` | Routes `/v1/community/*` to `communityRoute`; adds the `scheduled` handler; imports helpers from `http.js`. |
| `scripts/community.mjs` (new) | The owner's moderation CLI (`list`, `remove`, `restore`, `block`, `unblock`) over `wrangler d1 execute`. Its SQL builder is a pure exported function. |
| `test/fake-d1.js` (new) | A D1 shim over `node:sqlite` running the real migration. |
| `test/fake-kv.js` | `env()` gains `COMMUNITY_DB`, `COMMUNITY_SALT` and the three rate limits. |
| `test/community-*.test.js` (new) | One file per area: db, net, post cleaning, posting, reads, votes, take-down, cleanup, CLI. |
| `wrangler.toml` | D1 binding, three rate limits (2005–2007), cron trigger. |
| `package.json` | `"community"` script. |
| `README.md` | Community section: tables, routes, limits, deploy, moderation. |

### CI and docs

| File | Responsibility |
|---|---|
| `.github/workflows/tests.yml` | New `worker-tests` job. |
| `README.md` (repo root) | "What Stash talks to": the Community routes and the reworded `stash-share` privacy line. |

### App

| File | Responsibility |
|---|---|
| `core/model/.../community/Community.kt` (new) | `PostTarget` (Song or Playlist), `CommunityPost` (a list row; an opened post also carries its songs), `CommunityMe`. |
| `core/model/.../share/SharedTrack.kt` | `Track.toSharedTrackWithArt()`, moved here from Listen Together's catalog. |
| `core/media/.../listen/DefaultSessionCatalog.kt` | Uses `toSharedTrackWithArt()`; its private copy goes. |
| `core/data/.../db/dao/TrackDao.kt` | `getRecentlyPlayed(limit)`. |
| `core/data/.../share/SharedMixRepository.kt` | `buildDocument(..., withArt)`; `withinLimits` becomes file-level `internal` so Community reuses it. |
| `core/data/.../community/CommunityKeyStore.kt` (new) | The phone's key, made on first use; `newCommunityKey()`. |
| `core/data/.../community/CommunityApiClient.kt` (new) | HTTP client; reads the Worker's `error` code into `CommunityResult`. Also `NewPost` (a post request) and `VoteCounts`. |
| `core/data/.../community/CommunityRepository.kt` (new) | Home's last list, a `revision` that bumps after a post or take-down, `Draft`s from a `PostTarget` (re-reading a song's row), post/vote/take-down/me/mine/open, recent songs. |
| `core/data/.../community/CommunityMessages.kt` (new) | `communityMessage(result)`: every error code to its sentence. |
| `core/data/.../prefs/HomeSectionsPreference.kt` | `HomeSection.COMMUNITY`, the `community_on` switch, `visibleHomeSections`, `setCommunityOn`. |
| `core/network/.../di/NetworkModule.kt` | Redacts the community key header in logs. |
| `core/ui/.../components/PostToCommunity.kt` (new) | `LocalPostToCommunity`. |
| `core/ui/.../components/TrackOptionsSheet.kt` | "Post to Community" row and `onDismiss`. |
| 7 `TrackOptionsSheet` call sites; `NowPlayingScreen.kt` | `onDismiss`; Now Playing's options row. |
| `feature/library/.../share/ShareMixSheet.kt` | "Post to Community" in both states. |
| `feature/community/` (new module) | `CommunityRows.kt`, `CommunityViewModel.kt`, `CommunitySection.kt`, `CommunityScreen.kt`, `CommunityPostViewModel.kt`, `CommunityPostScreen.kt`, `CommunityPicker.kt`, `PostConfirm.kt`, `CommunityPostingHost.kt`. |
| `feature/home/.../HomeScreen.kt`, `HomeUiState.kt`, `HomeViewModel.kt` | The `communitySection` slot; COMMUNITY out of the defaults. |
| `feature/settings/.../SettingsAppearanceScreen.kt`, `SettingsViewModel.kt`, `SettingsUiState.kt` | Community's row: its own switch and a one-line disclosure. |
| `app/.../navigation/TopLevelDestination.kt`, `StashNavHost.kt`; `settings.gradle.kts`; `app/build.gradle.kts` | Routes, the posting host, the Home slot, the module. |

---

## Phase A: the server

### Task 1: The D1 schema and its test shim

**Files:**
- Create: `infra/share-worker/migrations/0001_community.sql`
- Create: `infra/share-worker/test/fake-d1.js`
- Test: `infra/share-worker/test/community-db.test.js`

- [ ] **Step 1: Write the failing test**

```js
// infra/share-worker/test/community-db.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import { fakeD1 } from "./fake-d1.js";

const row = (over = {}) => ({
    id: "AAAAAAAA", kind: "song", title: "garden", poster_name: "Sam", poster: "p".repeat(64), ip_hash: "n".repeat(64),
    summary: "{}", body: "{}", track_count: 1, created_at: 1, expires_at: 2, ...over,
});
const insert = (d, r) => d.prepare(
    "INSERT INTO posts (id, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at, expires_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
).bind(r.id, r.kind, r.title, r.poster_name, r.poster, r.ip_hash, r.summary, r.body, r.track_count, r.created_at, r.expires_at).run();

test("the migration creates posts, votes and blocked, and rows come back as plain objects", async () => {
    const d = fakeD1();
    await insert(d, row());
    const got = await d.prepare("SELECT id, up, down, removed_at FROM posts WHERE id = ?1").bind("AAAAAAAA").first();
    assert.deepEqual(got, { id: "AAAAAAAA", up: 0, down: 0, removed_at: null });
    assert.equal(await d.prepare("SELECT COUNT(*) AS n FROM votes").first("n"), 0);
    assert.equal(await d.prepare("SELECT COUNT(*) AS n FROM blocked").first("n"), 0);
});

test("the kind CHECK rejects anything but playlist, mix and song", async () => {
    await assert.rejects(() => insert(fakeD1(), row({ kind: "album" })));
});

test("batch runs in one transaction and returns each statement's rows", async () => {
    const d = fakeD1();
    await insert(d, row());
    const [, second] = await d.batch([
        d.prepare("UPDATE posts SET up = 3 WHERE id = ?1").bind("AAAAAAAA"),
        d.prepare("SELECT up FROM posts WHERE id = ?1").bind("AAAAAAAA"),
    ]);
    assert.deepEqual(second.results, [{ up: 3 }]);
    await assert.rejects(() => d.batch([
        d.prepare("UPDATE posts SET up = 9 WHERE id = ?1").bind("AAAAAAAA"),
        d.prepare("INSERT INTO votes (post_id) VALUES (?1)").bind("AAAAAAAA"), // NOT NULL violation
    ]));
    assert.equal(await d.prepare("SELECT up FROM posts").first("up"), 3, "the failed batch rolled back");
});
```

- [ ] **Step 2: Run it to see it fail**

Run (in `infra/share-worker`): `node --no-warnings --test test/community-db.test.js`
Expected: FAIL, `Cannot find module '.../test/fake-d1.js'`.

- [ ] **Step 3: Write the migration**

```sql
-- infra/share-worker/migrations/0001_community.sql
-- Stash Community (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2).

CREATE TABLE posts (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL CHECK (kind IN ('playlist', 'mix', 'song')),
  title        TEXT NOT NULL,
  poster_name  TEXT NOT NULL,
  poster       TEXT NOT NULL,
  ip_hash      TEXT NOT NULL,
  summary      TEXT NOT NULL,
  body         TEXT NOT NULL,
  track_count  INTEGER NOT NULL,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  up           INTEGER NOT NULL DEFAULT 0,
  down         INTEGER NOT NULL DEFAULT 0,
  removed_at   INTEGER
);
CREATE INDEX posts_live   ON posts (expires_at) WHERE removed_at IS NULL;
CREATE INDEX posts_poster ON posts (poster, created_at);
CREATE INDEX posts_ip     ON posts (ip_hash, created_at);

CREATE TABLE votes (
  post_id  TEXT NOT NULL,
  voter    TEXT NOT NULL,
  ip_hash  TEXT NOT NULL,
  value    INTEGER NOT NULL CHECK (value IN (-1, 1)),
  at       INTEGER NOT NULL,
  PRIMARY KEY (post_id, voter)
);

CREATE TABLE blocked (
  poster  TEXT PRIMARY KEY,
  at      INTEGER NOT NULL,
  note    TEXT
);
```

- [ ] **Step 4: Write the shim**

```js
// infra/share-worker/test/fake-d1.js
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";

const MIGRATION = readFileSync(new URL("../migrations/0001_community.sql", import.meta.url), "utf8");

/**
 * Just enough of Cloudflare D1 for src/community.js, over a real in-memory SQLite running the real
 * migration: prepare().bind().first(col?)/all()/run(), and batch() as one transaction.
 * Rows are copied into plain objects: node:sqlite rows have a null prototype, which
 * assert.deepStrictEqual treats as different from {}.
 */
export function fakeD1() {
    const db = new DatabaseSync(":memory:");
    db.exec(MIGRATION);
    const plain = (r) => (r ? { ...r } : null);
    const statement = (sql, args = []) => ({
        bind: (...a) => statement(sql, a),
        async first(col) {
            const r = plain(db.prepare(sql).get(...args));
            return col === undefined ? r : (r?.[col] ?? null);
        },
        async all() {
            return { results: db.prepare(sql).all(...args).map(plain), success: true };
        },
        async run() {
            const info = db.prepare(sql).run(...args);
            return { success: true, meta: { changes: Number(info.changes) } };
        },
    });
    return {
        db,
        prepare: (sql) => statement(sql),
        async batch(statements) {
            db.exec("BEGIN");
            try {
                const out = [];
                for (const s of statements) out.push(await s.all());
                db.exec("COMMIT");
                return out;
            } catch (e) {
                db.exec("ROLLBACK");
                throw e;
            }
        },
    };
}
```

- [ ] **Step 5: Run it to see it pass**

Run: `node --no-warnings --test test/community-db.test.js`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add infra/share-worker/migrations/0001_community.sql infra/share-worker/test/fake-d1.js infra/share-worker/test/community-db.test.js
git commit -m "feat(community): D1 schema and a node:sqlite test shim"
```

---

### Task 2: Shared HTTP helpers and the /56 network

**Files:**
- Create: `infra/share-worker/src/http.js`
- Modify: `infra/share-worker/src/index.js` (remove the local `json` and `limitKey`, and the `ip` const; import them)
- Test: `infra/share-worker/test/community-net.test.js`

- [ ] **Step 1: Write the failing test**

```js
// infra/share-worker/test/community-net.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import { networkOf } from "../src/http.js";
import { limitKey } from "../src/index.js";

test("an IPv4 address is its own network", () => {
    assert.equal(networkOf("203.0.113.9"), "203.0.113.9");
    assert.equal(networkOf("::ffff:203.0.113.9"), "::ffff:203.0.113.9");
});

test("IPv6 addresses are grouped by their /56", () => {
    // Same first 56 bits (2001:db8:aa:bb00::/56), different /64s and hosts.
    assert.equal(networkOf("2001:db8:aa:bb01::1"), networkOf("2001:0db8:00aa:bbff:1:2:3:4"));
    assert.equal(networkOf("2001:db8:aa:bb01::1"), "2001:db8:aa:bb/56");
    // A different /56.
    assert.notEqual(networkOf("2001:db8:aa:bb01::1"), networkOf("2001:db8:aa:cc01::1"));
    assert.equal(networkOf("2001:db8::1"), "2001:db8:0:0/56");
    // A short 4th group is still cut by value: ab (00ab) and ff00 are different /56s.
    assert.notEqual(networkOf("2001:db8:1:ab::"), networkOf("2001:db8:1:ff00::"));
    assert.equal(networkOf("2001:db8:1:ab::"), "2001:db8:1:0/56");
});

test("limitKey is still exported from index.js and still cuts IPv6 to /64", () => {
    assert.equal(limitKey("2001:db8:aa:bb01::1"), "2001:db8:aa:bb01");
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `node --no-warnings --test test/community-net.test.js`
Expected: FAIL, `Cannot find module '.../src/http.js'`.

- [ ] **Step 3: Create `src/http.js`** (move `json` and `limitKey` verbatim from `index.js`, add `ip` and `networkOf`)

```js
// infra/share-worker/src/http.js
/** Helpers shared by the mix, room and Community routes. */

export function json(obj, status = 200, extra = {}) {
    return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...extra } });
}

/** Rate-limit key: an IPv4 address as is, an IPv6 one by its /64 (one host usually holds the whole /64). */
export function limitKey(addr) {
    if (!addr.includes(":") || addr.includes(".")) return addr; // IPv4, or IPv4-mapped IPv6
    const [head, tail] = addr.split("::");
    const left = head ? head.split(":") : [];
    const right = tail ? tail.split(":") : [];
    const groups = tail === undefined ? left : [...left, ...Array(8 - left.length - right.length).fill("0"), ...right];
    return groups.slice(0, 4).join(":");
}

export const ip = (request) => limitKey(request.headers.get("CF-Connecting-IP") || "?");

/**
 * Community's "network" (spec 2026-09-26 §2): an IPv4 address as is, an IPv6 address by its /56.
 * Coarser than limitKey's /64 because homes often get a whole /56, and a free tunnel a /48.
 */
export function networkOf(addr) {
    const key = limitKey(addr);
    if (!key.includes(":") || key.includes(".")) return key;
    const g = key.split(":").map((x) => parseInt(x || "0", 16) || 0);
    return `${g[0].toString(16)}:${g[1].toString(16)}:${g[2].toString(16)}:${(g[3] >> 8).toString(16)}/56`;
}
```

- [ ] **Step 4: Point `index.js` at it**

In `src/index.js`:
- Delete the `export function json(...)` block (lines 78–80), the `limitKey` block with its KDoc (lines 96–104), and `const ip = ...` (line 106).
- Add after the existing imports:

```js
import { ip, json, limitKey } from "./http.js";
export { limitKey }; // test/hardening.test.js imports it from here
```

- [ ] **Step 5: Run the new test and the whole suite**

Run: `node --no-warnings --test test/*.test.js`
Expected: PASS: the 87 existing tests plus the new ones (3 in community-db, 3 in community-net).

- [ ] **Step 6: Commit**

```bash
git add infra/share-worker/src/http.js infra/share-worker/src/index.js infra/share-worker/test/community-net.test.js
git commit -m "refactor(share-worker): shared http helpers; add the /56 network for Community"
```

---

### Task 3: Cleaning a post request

**Files:**
- Create: `infra/share-worker/src/community-post.js`
- Test: `infra/share-worker/test/community-post.test.js`

- [ ] **Step 1: Write the failing test**

```js
// infra/share-worker/test/community-post.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import { cleanPost, summaryOf, MAX_POST_TRACKS } from "../src/community-post.js";

const song = { t: "garden", a: "Death Plus", yt: "9Vz-MkbnSg4", art: "https://i.ytimg.com/vi/9Vz-MkbnSg4/maxresdefault.jpg", by: "M1" };
const playlist = (tracks = [song], covers = ["https://i.scdn.co/image/a"]) =>
    ({ kind: "playlist", name: "Maya", title: "sad boy hours", body: { covers, tracks } });

test("a song post keeps the cleaned track without its Listen Together adder", () => {
    const p = cleanPost({ kind: "song", name: " Sam ", title: "garden", body: { track: song } });
    assert.equal(p.name, "Sam");
    assert.equal(p.count, 1);
    assert.equal(p.body.track.by, undefined);
    assert.equal(p.body.track.art, song.art);
    assert.deepEqual(p.summary, { art: song.art, artist: "Death Plus" });
});

test("a playlist post keeps allowed covers and cleaned tracks, and summarises the covers", () => {
    const p = cleanPost(playlist([song, { t: "heart", a: "Lil Tracy", art: "https://evil.example/x.jpg" }], [
        "https://i.scdn.co/image/a", "https://evil.example/log.gif", "https://lh3.googleusercontent.com/b",
    ]));
    assert.equal(p.kind, "playlist");
    assert.equal(p.count, 2);
    assert.deepEqual(p.body.covers, ["https://i.scdn.co/image/a", "https://lh3.googleusercontent.com/b"]);
    assert.deepEqual(p.summary, { covers: p.body.covers });
    assert.equal(p.body.tracks[1].art, undefined, "an art link off COVER_HOSTS is dropped");
    assert.ok(p.body.tracks.every((t) => t.by === undefined));
});

test("bad requests are null", () => {
    const bad = [
        null,
        { ...playlist(), kind: "album" },
        { ...playlist(), name: "" },
        { ...playlist(), name: "x".repeat(41) },
        { ...playlist(), title: "" },
        { ...playlist(), title: "x".repeat(101) },
        playlist([]),
        playlist(Array.from({ length: MAX_POST_TRACKS + 1 }, () => song)),
        playlist([{ t: "", a: "A" }]),
        { kind: "song", name: "Sam", title: "garden", body: {} },
    ];
    for (const b of bad) assert.equal(cleanPost(b), null, JSON.stringify(b)?.slice(0, 80));
    assert.ok(cleanPost(playlist(Array.from({ length: MAX_POST_TRACKS }, () => song))), "500 songs is allowed");
});

test("a mix is a playlist-shaped post with kind mix", () => {
    assert.equal(cleanPost({ ...playlist(), kind: "mix" }).kind, "mix");
});

test("summaryOf exposes the public fields only, and myVote/mine only when asked by a key", () => {
    const r = {
        id: "AAAAAAAA", kind: "song", title: "garden", poster_name: "Sam", poster: "p".repeat(64),
        summary: JSON.stringify({ art: "https://i.ytimg.com/x", artist: "Death Plus" }), track_count: 1,
        created_at: 5, up: 3, down: 1, my_vote: 1,
    };
    assert.deepEqual(summaryOf(r, null), {
        id: "AAAAAAAA", kind: "song", title: "garden", name: "Sam", count: 1,
        art: "https://i.ytimg.com/x", artist: "Death Plus", createdAt: 5, up: 3, down: 1,
    });
    assert.deepEqual(summaryOf(r, "p".repeat(64)), { ...summaryOf(r, null), myVote: 1, mine: true });
    assert.equal(summaryOf({ ...r, my_vote: null }, "q".repeat(64)).myVote, 0);
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `node --no-warnings --test test/community-post.test.js`
Expected: FAIL, `Cannot find module '.../src/community-post.js'`.

- [ ] **Step 3: Write `src/community-post.js`**

```js
// infra/share-worker/src/community-post.js
/**
 * Stash Community posts (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2): the pure
 * parts, turning a request into what is stored and a stored row into what phones see.
 */
import { allowedCover, cleanTrack } from "./validate.js";

export const MAX_POST_TRACKS = 500;
const KINDS = new Set(["playlist", "mix", "song"]);
const text = (v, max) => (typeof v === "string" && v.trim() && v.trim().length <= max ? v.trim() : null);

/** One song, cleaned like a Listen Together song, without `by` (that's a room member id). */
function track(t) {
    const c = cleanTrack(t);
    if (c) delete c.by;
    return c;
}

/**
 * `{kind, name, title, body}` → `{kind, name, title, body, summary, count}`, or null when anything is off.
 * A bad song fails the whole post, as a bad track fails a mix.
 */
export function cleanPost(input) {
    if (!input || typeof input !== "object" || !KINDS.has(input.kind)) return null;
    const name = text(input.name, 40);
    const title = text(input.title, 100);
    if (!name || !title) return null;
    if (input.kind === "song") {
        const t = track(input.body?.track);
        if (!t) return null;
        return { kind: "song", name, title, body: { track: t }, summary: { ...(t.art ? { art: t.art } : {}), artist: t.a }, count: 1 };
    }
    const raw = input.body?.tracks;
    if (!Array.isArray(raw) || raw.length < 1 || raw.length > MAX_POST_TRACKS) return null;
    const tracks = raw.map(track);
    if (tracks.includes(null)) return null;
    const covers = (Array.isArray(input.body.covers) ? input.body.covers : [])
        .filter((c) => typeof c === "string" && c.length <= 1000 && allowedCover(c))
        .slice(0, 4);
    return { kind: input.kind, name, title, body: { covers, tracks }, summary: { covers }, count: tracks.length };
}

/**
 * A `posts` row (with `my_vote` from the voter join) → the public PostSummary. [me] is the caller's poster
 * id, or null without a key: only then do `myVote` and `mine` appear. The poster id itself never leaves.
 */
export function summaryOf(row, me) {
    const s = JSON.parse(row.summary);
    return {
        // `name`, not `by`: in a SharedTrack `by` is a Listen Together member id.
        id: row.id, kind: row.kind, title: row.title, name: row.poster_name, count: row.track_count,
        ...(s.covers ? { covers: s.covers } : {}),
        ...(s.art ? { art: s.art } : {}),
        ...(s.artist ? { artist: s.artist } : {}),
        createdAt: row.created_at, up: row.up, down: row.down,
        ...(me ? { myVote: row.my_vote ?? 0, mine: row.poster === me } : {}),
    };
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `node --no-warnings --test test/community-post.test.js`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/community-post.js infra/share-worker/test/community-post.test.js
git commit -m "feat(community): clean post requests and summarise stored posts"
```

---

### Task 4: Posting

**Files:**
- Create: `infra/share-worker/src/community.js`
- Modify: `infra/share-worker/src/index.js` (route `/v1/community/`)
- Modify: `infra/share-worker/test/fake-kv.js` (`env()` bindings)
- Create: `infra/share-worker/test/community-helpers.js`
- Test: `infra/share-worker/test/community-posts.test.js`

- [ ] **Step 1: Give the test env the Community bindings**

In `test/fake-kv.js`, add `import { fakeD1 } from "./fake-d1.js";` at the top and these entries to the object `env()` returns, before `...over`:

```js
        COMMUNITY_DB: fakeD1(),
        COMMUNITY_SALT: "test-salt",
        COMMUNITY_WRITE_RL: { limit: async () => ({ success: true }) },
        COMMUNITY_VOTE_RL: { limit: async () => ({ success: true }) },
        COMMUNITY_READ_RL: { limit: async () => ({ success: true }) },
```

- [ ] **Step 2: Write the shared test helpers, then the failing test**

The helpers get their own file. The `test/*.test.js` glob doesn't run it, so Tasks 5–9 import from it without re-running this task's tests.

```js
// infra/share-worker/test/community-helpers.js
import { handle } from "../src/index.js";
import { sha256Hex } from "../src/store.js";

export const BASE = "https://share.test";
export const KEY_A = "a".repeat(43);
export const KEY_B = "b".repeat(43);
export const KEY_C = "c".repeat(43);
export const HOUR = 3_600_000;
export const DAY = 24 * HOUR;
export const song = { t: "garden", a: "Death Plus", yt: "9Vz-MkbnSg4" };
export const songPost = (over = {}) => ({ kind: "song", name: "Sam", title: "garden", body: { track: song }, ...over });

/** A request to the Worker. `key` goes in X-Stash-Community-Key; `from` is the caller's IP. */
export function call(e, method, path, { key, body, from = "203.0.113.9" } = {}) {
    const headers = { "CF-Connecting-IP": from };
    if (key !== undefined) headers["X-Stash-Community-Key"] = key;
    if (body !== undefined) headers["content-type"] = "application/json";
    return handle(new Request(`${BASE}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }), e);
}

/** Inserts a post row directly, for states the API can't reach quickly (old, expired, many). */
export async function seed(e, { id, poster, key, ip = "n".repeat(64), created = Date.now(), removed = null, expires, up = 0, down = 0, kind = "song", summary = { artist: "A" }, body = { track: { t: "T", a: "A" } } } = {}) {
    const owner = poster ?? (key ? await sha256Hex(key) : "p".repeat(64));
    await e.COMMUNITY_DB.prepare(
        "INSERT INTO posts (id, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at, expires_at, up, down, removed_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14)",
    ).bind(id, kind, "T", "Seed", owner, ip, JSON.stringify(summary), JSON.stringify(body), 1, created, expires ?? created + 30 * DAY, up, down, removed).run();
}
```

```js
// infra/share-worker/test/community-posts.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call, seed, songPost, KEY_A, KEY_B, DAY } from "./community-helpers.js";

test("a post is stored with the key's hash and a salted network hash, never the key or the IP", async () => {
    const e = env();
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(r.status, 201);
    const { id } = await r.json();
    assert.match(id, /^[A-Za-z0-9]{8}$/);
    const row = await e.COMMUNITY_DB.prepare("SELECT * FROM posts WHERE id = ?1").bind(id).first();
    assert.equal(row.poster, await sha256Hex(KEY_A));
    assert.equal(row.ip_hash, await sha256Hex("test-salt|203.0.113.9"));
    assert.equal(row.expires_at - row.created_at, 30 * DAY);
    assert.ok(!JSON.stringify(row).includes(KEY_A) && !JSON.stringify(row).includes("203.0.113.9"));
});

test("a missing or malformed key is 401 bad_key; a bad body 400; an oversized one 413", async () => {
    const e = env();
    assert.equal((await call(e, "POST", "/v1/community/posts", { body: songPost() })).status, 401);
    const bad = await call(e, "POST", "/v1/community/posts", { key: "short", body: songPost() });
    assert.equal(bad.status, 401);
    assert.deepEqual(await bad.json(), { error: "bad_key" });
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost({ kind: "album" }) })).status, 400);
    const big = songPost({ kind: "playlist", body: { tracks: Array.from({ length: 500 }, () => ({ t: "T".repeat(500), a: "A".repeat(100) })) } });
    const tooBig = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: big });
    assert.equal(tooBig.status, 413);
    assert.deepEqual(await tooBig.json(), { error: "too_large" });
});

test("2 posts a day per phone, and a taken-down post still counts", async () => {
    const e = env();
    const first = await (await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).json();
    // Taken down (Task 7 adds the route; here the row is marked directly).
    await e.COMMUNITY_DB.prepare("UPDATE posts SET removed_at = ?2 WHERE id = ?1").bind(first.id, Date.now()).run();
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).status, 201);
    const third = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(third.status, 429);
    assert.deepEqual(await third.json(), { error: "daily_limit" });
});

test("5 live posts per phone", async () => {
    const e = env();
    for (let i = 0; i < 5; i++) await seed(e, { id: `LIVE000${i}`, key: KEY_A, created: Date.now() - 3 * DAY });
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "live_limit" });
});

test("10 posts a day per network, whoever posts them", async () => {
    const e = env();
    const net = await sha256Hex("test-salt|203.0.113.9");
    for (let i = 0; i < 10; i++) await seed(e, { id: `NETW000${i}`, poster: `${i}`.repeat(64), ip: net });
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_B, body: songPost() });
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "network_limit" });
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_B, body: songPost(), from: "198.51.100.7" })).status, 201);
});

test("a blocked phone can't post; the write limit answers 429 rate_limited", async () => {
    const e = env();
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_A)).run();
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(r.status, 403);
    assert.deepEqual(await r.json(), { error: "blocked" });
    const limited = env({ COMMUNITY_WRITE_RL: { limit: async () => ({ success: false }) } });
    const rl = await call(limited, "POST", "/v1/community/posts", { key: KEY_B, body: songPost() });
    assert.equal(rl.status, 429);
    assert.deepEqual(await rl.json(), { error: "rate_limited" });
});

test("the per-minute limits count by network, an IPv6 address by its /56", async () => {
    const keys = [];
    const e = env({ COMMUNITY_READ_RL: { limit: async ({ key }) => (keys.push(key), { success: false }) } });
    assert.equal((await call(e, "GET", "/v1/community/feed", { from: "2001:db8:aa:bb01::1" })).status, 429);
    assert.deepEqual(keys, ["2001:db8:aa:bb/56"]);
});
```

- [ ] **Step 3: Run it to see it fail**

Run: `node --no-warnings --test test/community-posts.test.js`
Expected: FAIL: every Community request is 404 `not_found` (no route yet).

- [ ] **Step 4: Write `src/community.js` with posting and the router**

```js
// infra/share-worker/src/community.js
/**
 * Stash Community routes (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2).
 * D1 tables: posts, votes, blocked (migrations/0001_community.sql). A phone is sha256(its key);
 * a network is sha256(COMMUNITY_SALT | IPv4, or IPv6 /56). Every error body is {error: "<code>"}.
 */
import { json, networkOf } from "./http.js";
import { validEditKey } from "./validate.js";
import { newId, sha256Hex } from "./store.js";
import { cleanPost, summaryOf } from "./community-post.js";

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
export const POST_TTL_MS = 30 * DAY;
export const DAILY_POSTS = 2;
export const LIVE_POSTS = 5;
export const NETWORK_DAILY_POSTS = 10;
export const HIDE_AT = -3; // up - down at or below this hides a post
export const VOTES_PER_NETWORK = 2; // counted votes per network per post, in each direction
export const MAX_REQUEST_BYTES = 256 * 1024;
/** 3.6 h per net vote: 10 more votes keep a post above a newer one for 36 h (spec §2 Ordering). */
export const MS_PER_VOTE = 12_960_000;

const POST_API = /^\/v1\/community\/posts\/([A-Za-z0-9]{8})(\/vote)?$/;
const KEY_HEADER = "X-Stash-Community-Key";
const rateLimited = () => json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
const notAllowed = () => json({ error: "method_not_allowed" }, 405);
const gone = () => json({ error: "gone" }, 404);

/** Routes every /v1/community/* request. [method] has HEAD folded into GET. */
export async function communityRoute(request, env, path, method) {
    const now = Date.now();
    if (method === "GET" && !(await env.COMMUNITY_READ_RL.limit({ key: network(request) })).success) return rateLimited();
    if (path === "/v1/community/posts") return method === "POST" ? createPost(request, env, now) : notAllowed();
    return json({ error: "not_found" }, 404);
}

/** The caller's poster id: {id} with a valid key, {none} without one, {bad} with a malformed one. */
async function identity(request) {
    const key = request.headers.get(KEY_HEADER);
    if (key === null) return { none: true };
    if (!validEditKey(key)) return { bad: true };
    return { id: await sha256Hex(key) };
}

/**
 * The caller's network (an IPv4 address, or an IPv6 /56). The per-minute limits count by it too (spec §2), so
 * hopping /64s inside one /56 doesn't dodge them; the stored form is salted and hashed.
 */
const network = (request) => networkOf(request.headers.get("CF-Connecting-IP") || "?");
const networkHash = (request, env) => sha256Hex(`${env.COMMUNITY_SALT ?? ""}|${network(request)}`);

async function readJson(request) {
    if (Number(request.headers.get("content-length")) > MAX_REQUEST_BYTES) return { tooBig: true };
    const buf = await request.arrayBuffer();
    if (buf.byteLength > MAX_REQUEST_BYTES) return { tooBig: true };
    try { return { body: JSON.parse(new TextDecoder().decode(buf)) }; } catch { return { body: null }; }
}

/** How many posts [poster] made in the last day, has live, and [net] made in the last day; and a block. */
const LIMITS_SQL = `SELECT
    (SELECT COUNT(*) FROM blocked WHERE poster = ?1) AS blocked,
    (SELECT COUNT(*) FROM posts WHERE poster = ?1 AND created_at > ?2) AS today,
    (SELECT COUNT(*) FROM posts WHERE poster = ?1 AND removed_at IS NULL AND expires_at > ?3) AS live,
    (SELECT COUNT(*) FROM posts WHERE ip_hash = ?4 AND created_at > ?2) AS network`;

async function createPost(request, env, now) {
    if (!(await env.COMMUNITY_WRITE_RL.limit({ key: network(request) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { body, tooBig } = await readJson(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    const post = cleanPost(body);
    if (!post) return json({ error: "bad_request" }, 400);
    const net = await networkHash(request, env);
    // The limits are checked inside the INSERT itself, so two posts at the same moment can't both slip under
    // them; only when nothing was inserted is the reason looked up.
    for (let attempt = 0; attempt < 5; attempt++) {
        const id = newId();
        let changes;
        try {
            ({ meta: { changes } } = await env.COMMUNITY_DB.prepare(
                `INSERT INTO posts (id, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at, expires_at)
                 SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11
                 WHERE NOT EXISTS (SELECT 1 FROM blocked WHERE poster = ?5)
                   AND (SELECT COUNT(*) FROM posts WHERE poster = ?5 AND created_at > ?12) < ${DAILY_POSTS}
                   AND (SELECT COUNT(*) FROM posts WHERE poster = ?5 AND removed_at IS NULL AND expires_at > ?10) < ${LIVE_POSTS}
                   AND (SELECT COUNT(*) FROM posts WHERE ip_hash = ?6 AND created_at > ?12) < ${NETWORK_DAILY_POSTS}`,
            ).bind(id, post.kind, post.title, post.name, who.id, net, JSON.stringify(post.summary), JSON.stringify(post.body),
                post.count, now, now + POST_TTL_MS, now - DAY).run());
        } catch (e) {
            if (String(e?.message).includes("UNIQUE")) continue; // an id collision: try another
            throw e;
        }
        if (changes === 1) return json({ id }, 201);
        const c = await env.COMMUNITY_DB.prepare(LIMITS_SQL).bind(who.id, now - DAY, now, net).first();
        if (c.blocked) return json({ error: "blocked" }, 403);
        if (c.today >= DAILY_POSTS) return json({ error: "daily_limit" }, 429);
        if (c.live >= LIVE_POSTS) return json({ error: "live_limit" }, 429);
        return json({ error: "network_limit" }, 429);
    }
    return json({ error: "unavailable" }, 503, { "Retry-After": "2" });
}
```

- [ ] **Step 5: Route `/v1/community/` in `index.js`**

In `src/index.js`, add `import { communityRoute } from "./community.js";` with the other imports, and in `handle()` insert, right after the `if (room) { ... }` block:

```js
    if (path.startsWith("/v1/community/")) return communityRoute(request, env, path, method);
```

- [ ] **Step 6: Run it to see it pass**

Run: `node --no-warnings --test test/community-posts.test.js`
Expected: PASS, 7 tests.

- [ ] **Step 7: Run the whole suite**

Run: `node --no-warnings --test test/*.test.js`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add infra/share-worker/src/community.js infra/share-worker/src/index.js infra/share-worker/test/fake-kv.js infra/share-worker/test/community-helpers.js infra/share-worker/test/community-posts.test.js
git commit -m "feat(community): post route with per-phone and per-network limits"
```

---

### Task 5: Reading the list, your posts, your limits, and one post

**Files:**
- Modify: `infra/share-worker/src/community.js`
- Test: `infra/share-worker/test/community-reads.test.js`

- [ ] **Step 1: Write the failing test**

```js
// infra/share-worker/test/community-reads.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call, seed, KEY_A, KEY_B, HOUR, DAY } from "./community-helpers.js";

const feed = async (e, opts = {}, q = "") => (await (await call(e, "GET", `/v1/community/feed${q}`, opts)).json()).posts;

test("the list is likes plus freshness: 10 more votes hold 35 hours, not 37", async () => {
    const now = Date.now();
    let e = env();
    await seed(e, { id: "OLDVOTED", created: now - 35 * HOUR, up: 10 });
    await seed(e, { id: "NEWPOSTS", created: now });
    assert.deepEqual((await feed(e)).map((p) => p.id), ["OLDVOTED", "NEWPOSTS"]);
    e = env();
    await seed(e, { id: "OLDVOTED", created: now - 37 * HOUR, up: 10 });
    await seed(e, { id: "NEWPOSTS", created: now });
    assert.deepEqual((await feed(e)).map((p) => p.id), ["NEWPOSTS", "OLDVOTED"]);
});

test("hidden (-3), removed and expired posts stay off the list; limit is 5 by default, 100 at most", async () => {
    const e = env();
    await seed(e, { id: "HIDDEN00", down: 3 });
    await seed(e, { id: "REMOVED0", removed: Date.now() });
    await seed(e, { id: "EXPIRED0", created: Date.now() - 31 * DAY });
    await seed(e, { id: "ALMOSTHI", up: 1, down: 3 }); // -2 still shows
    for (let i = 0; i < 6; i++) await seed(e, { id: `VISIBLE${i}`, created: Date.now() - i * HOUR });
    const ids = (await feed(e, {}, "?limit=100")).map((p) => p.id);
    assert.ok(!ids.includes("HIDDEN00") && !ids.includes("REMOVED0") && !ids.includes("EXPIRED0"));
    assert.ok(ids.includes("ALMOSTHI"));
    assert.equal((await feed(e)).length, 5);
    assert.equal((await feed(e, {}, "?limit=500")).length, 7);
});

test("the list never carries the poster id, the network or the songs; myVote and mine need a key", async () => {
    const e = env();
    await seed(e, { id: "MINE0000", key: KEY_A, kind: "playlist", summary: { covers: ["https://i.scdn.co/image/a"] }, body: { covers: [], tracks: [{ t: "T", a: "A" }] } });
    const [anon] = await feed(e);
    assert.deepEqual(Object.keys(anon).sort(), ["count", "covers", "createdAt", "down", "id", "kind", "name", "title", "up"]);
    const [mine] = await feed(e, { key: KEY_A });
    assert.equal(mine.mine, true);
    assert.equal(mine.myVote, 0);
    assert.equal((await feed(e, { key: KEY_B }))[0].mine, false);
    assert.equal((await call(e, "GET", "/v1/community/feed", { key: "short" })).status, 401);
});

test("mine lists your live posts, newest first, including hidden ones", async () => {
    const e = env();
    await seed(e, { id: "MINEOLD0", key: KEY_A, created: Date.now() - 2 * HOUR });
    await seed(e, { id: "MINEHIDE", key: KEY_A, down: 3 });
    await seed(e, { id: "MINEGONE", key: KEY_A, removed: Date.now() });
    await seed(e, { id: "SOMEONES", key: KEY_B });
    const posts = (await (await call(e, "GET", "/v1/community/mine", { key: KEY_A })).json()).posts;
    assert.deepEqual(posts.map((p) => [p.id, p.hidden]), [["MINEHIDE", true], ["MINEOLD0", false]]);
    assert.ok(posts[0].expiresAt > Date.now());
    assert.equal((await call(e, "GET", "/v1/community/mine")).status, 401);
});

test("me says how many posts are left today and how many spots are free", async () => {
    const e = env();
    await seed(e, { id: "TODAY000", key: KEY_A });
    await seed(e, { id: "LIVEOLD0", key: KEY_A, created: Date.now() - 3 * DAY });
    assert.deepEqual(await (await call(e, "GET", "/v1/community/me", { key: KEY_A })).json(), { postsLeftToday: 1, spotsFree: 3, blocked: false });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_B)).run();
    assert.equal((await (await call(e, "GET", "/v1/community/me", { key: KEY_B })).json()).blocked, true);
});

test("a post opens with its songs; gone ones are 404, and a hidden one only opens for its poster", async () => {
    const e = env();
    await seed(e, { id: "PLAYLIST", kind: "playlist", summary: { covers: [] }, body: { covers: [], tracks: [{ t: "T1", a: "A" }, { t: "T2", a: "A" }] } });
    await seed(e, { id: "SONGPOST", body: { track: { t: "garden", a: "Death Plus" } } });
    await seed(e, { id: "HIDDEN00", key: KEY_A, down: 3 });
    await seed(e, { id: "EXPIRED0", created: Date.now() - 31 * DAY });
    const pl = (await (await call(e, "GET", "/v1/community/posts/PLAYLIST")).json()).post;
    assert.deepEqual(pl.tracks.map((t) => t.t), ["T1", "T2"]);
    assert.equal((await (await call(e, "GET", "/v1/community/posts/SONGPOST")).json()).post.track.t, "garden");
    for (const id of ["HIDDEN00", "EXPIRED0", "NOSUCHID"]) {
        const r = await call(e, "GET", `/v1/community/posts/${id}`, { key: KEY_B });
        assert.equal(r.status, 404, id);
        assert.deepEqual(await r.json(), { error: "gone" });
    }
    assert.equal((await call(e, "GET", "/v1/community/posts/HIDDEN00", { key: KEY_A })).status, 200);
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `node --no-warnings --test test/community-reads.test.js`
Expected: FAIL: the read routes answer 404 `not_found`.

- [ ] **Step 3: Add the read routes to `src/community.js`**

In `communityRoute`, replace the single `/v1/community/posts` line with:

```js
    if (path === "/v1/community/feed") return method === "GET" ? feed(request, env, now) : notAllowed();
    if (path === "/v1/community/mine") return method === "GET" ? mine(request, env, now) : notAllowed();
    if (path === "/v1/community/me") return method === "GET" ? me(request, env, now) : notAllowed();
    if (path === "/v1/community/posts") return method === "POST" ? createPost(request, env, now) : notAllowed();
    const m = POST_API.exec(path);
    if (m) {
        if (method === "GET" && !m[2]) return getPost(request, env, m[1], now);
        return notAllowed();
    }
```

Then add, below `createPost`:

```js
/** The columns a list needs. Never `body`: a Home open must not load song lists (spec §2). */
const LIST_COLUMNS = "p.id, p.kind, p.title, p.poster_name, p.poster, p.summary, p.track_count, p.created_at, p.up, p.down";
const NO_STORE = { "cache-control": "no-store" };

async function feed(request, env, now) {
    const who = await identity(request);
    if (who.bad) return json({ error: "bad_key" }, 401);
    const asked = Number.parseInt(new URL(request.url).searchParams.get("limit"), 10);
    const limit = Math.min(Math.max(Number.isNaN(asked) ? 5 : asked, 1), 100);
    const { results } = await env.COMMUNITY_DB.prepare(
        `SELECT ${LIST_COLUMNS}, v.value AS my_vote FROM posts p
         LEFT JOIN votes v ON v.post_id = p.id AND v.voter = ?1
         WHERE p.removed_at IS NULL AND p.expires_at > ?2 AND (p.up - p.down) > ${HIDE_AT}
         ORDER BY (p.up - p.down) + p.created_at / ${MS_PER_VOTE}.0 DESC, p.created_at DESC
         LIMIT ?3`,
    ).bind(who.id ?? "", now, limit).all();
    return json({ posts: results.map((r) => summaryOf(r, who.id ?? null)) }, 200, NO_STORE);
}

async function mine(request, env, now) {
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { results } = await env.COMMUNITY_DB.prepare(
        `SELECT ${LIST_COLUMNS}, p.expires_at, NULL AS my_vote FROM posts p
         WHERE p.poster = ?1 AND p.removed_at IS NULL AND p.expires_at > ?2
         ORDER BY p.created_at DESC`,
    ).bind(who.id, now).all();
    const posts = results.map((r) => ({ ...summaryOf(r, who.id), hidden: r.up - r.down <= HIDE_AT, expiresAt: r.expires_at }));
    return json({ posts }, 200, NO_STORE);
}

async function me(request, env, now) {
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const c = await env.COMMUNITY_DB.prepare(LIMITS_SQL).bind(who.id, now - DAY, now, "").first();
    return json({
        postsLeftToday: Math.max(0, DAILY_POSTS - c.today),
        spotsFree: Math.max(0, LIVE_POSTS - c.live),
        blocked: c.blocked > 0,
    }, 200, NO_STORE);
}

async function getPost(request, env, id, now) {
    const who = await identity(request);
    if (who.bad) return json({ error: "bad_key" }, 401);
    const r = await env.COMMUNITY_DB.prepare(
        `SELECT ${LIST_COLUMNS}, p.body, p.removed_at, p.expires_at, v.value AS my_vote FROM posts p
         LEFT JOIN votes v ON v.post_id = p.id AND v.voter = ?2
         WHERE p.id = ?1`,
    ).bind(id, who.id ?? "").first();
    if (!r || r.removed_at !== null || r.expires_at <= now) return gone();
    if (r.up - r.down <= HIDE_AT && r.poster !== who.id) return gone(); // hidden: only its poster sees it
    const body = JSON.parse(r.body);
    const songs = body.track ? { track: body.track } : { tracks: body.tracks };
    return json({ post: { ...summaryOf(r, who.id ?? null), ...songs } }, 200, NO_STORE);
}
```

- [ ] **Step 4: Run it to see it pass, then the whole suite**

Run: `node --no-warnings --test test/community-reads.test.js`, then `node --no-warnings --test test/*.test.js`
Expected: PASS, 6 tests; then all PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/community.js infra/share-worker/test/community-reads.test.js
git commit -m "feat(community): the ranked list, your posts, your limits and one post"
```

---

### Task 6: Votes, capped per network

**Files:**
- Modify: `infra/share-worker/src/community.js`
- Test: `infra/share-worker/test/community-votes.test.js`

- [ ] **Step 1: Write the failing test**

```js
// infra/share-worker/test/community-votes.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call, seed, KEY_A, KEY_B, KEY_C } from "./community-helpers.js";

const vote = (e, id, value, key, from) => call(e, "PUT", `/v1/community/posts/${id}/vote`, { key, body: { value }, from });
const keyN = (n) => `${n}`.padStart(43, "k");

test("a vote counts once per phone, can change, and 0 takes it back", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_B)).json(), { up: 1, down: 0, myVote: 1 });
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_B)).json(), { up: 1, down: 0, myVote: 1 });
    assert.deepEqual(await (await vote(e, "POST0001", -1, KEY_B)).json(), { up: 0, down: 1, myVote: -1 });
    assert.deepEqual(await (await vote(e, "POST0001", 0, KEY_B)).json(), { up: 0, down: 0, myVote: 0 });
    const [p] = (await (await call(e, "GET", "/v1/community/feed", { key: KEY_B })).json()).posts;
    assert.equal(p.myVote, 0);
});

test("one network counts for at most 2 votes each way, however many keys it makes", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    for (let i = 0; i < 6; i++) await vote(e, "POST0001", 1, keyN(i), "203.0.113.50");
    assert.equal((await (await vote(e, "POST0001", 1, KEY_C, "198.51.100.7")).json()).up, 3);
    // Two IPv6 addresses in one /56 are one network.
    await vote(e, "POST0001", -1, keyN(10), "2001:db8:aa:bb01::1");
    await vote(e, "POST0001", -1, keyN(11), "2001:db8:aa:bb02::1");
    const r = await (await vote(e, "POST0001", -1, keyN(12), "2001:db8:aa:bbff::9")).json();
    assert.equal(r.down, 2);
});

test("-3 from two networks hides a post: off the list, 404 to others, no more votes", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    await vote(e, "POST0001", -1, keyN(1), "203.0.113.50");
    await vote(e, "POST0001", -1, keyN(2), "203.0.113.50");
    assert.equal((await (await vote(e, "POST0001", -1, keyN(3), "198.51.100.7")).json()).down, 3);
    assert.equal((await (await call(e, "GET", "/v1/community/feed")).json()).posts.length, 0);
    assert.equal((await call(e, "GET", "/v1/community/posts/POST0001", { key: KEY_B })).status, 404);
    assert.equal((await vote(e, "POST0001", 1, keyN(4), "192.0.2.1")).status, 404);
});

test("no voting on your own post, while blocked, on a gone post, or with a bad value or key", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    await seed(e, { id: "GONE0001", removed: Date.now() });
    const own = await vote(e, "POST0001", 1, KEY_A);
    assert.equal(own.status, 403);
    assert.deepEqual(await own.json(), { error: "own_post" });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_C)).run();
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_C)).json(), { error: "blocked" });
    assert.equal((await vote(e, "GONE0001", 1, KEY_B)).status, 404);
    assert.equal((await vote(e, "NOSUCHID", 1, KEY_B)).status, 404);
    assert.equal((await vote(e, "POST0001", 2, KEY_B)).status, 400);
    assert.equal((await vote(e, "POST0001", 1, undefined)).status, 401);
});

test("votes are rate-limited per network", async () => {
    const e = env({ COMMUNITY_VOTE_RL: { limit: async () => ({ success: false }) } });
    await seed(e, { id: "POST0001", key: KEY_A });
    assert.equal((await vote(e, "POST0001", 1, KEY_B)).status, 429);
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `node --no-warnings --test test/community-votes.test.js`
Expected: FAIL: `PUT …/vote` answers 405.

- [ ] **Step 3: Add voting to `src/community.js`**

In `communityRoute`'s `if (m) { … }` block, add as its first line:

```js
        if (m[2]) return method === "PUT" ? vote(request, env, m[1], now) : notAllowed();
```

Add below `getPost`:

```js
/**
 * After every vote: each direction is the sum, over networks, of min(that network's votes, 2). A script
 * making keys behind one connection moves a post by 2 at most (spec §2 Votes). Exported for the CLI.
 */
export const RECOUNT_SQL = `UPDATE posts SET
    up = (SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT COUNT(*) AS n FROM votes WHERE post_id = ?1 AND value = 1 GROUP BY ip_hash)),
    down = (SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT COUNT(*) AS n FROM votes WHERE post_id = ?1 AND value = -1 GROUP BY ip_hash))
    WHERE id = ?1 RETURNING up, down`;

async function vote(request, env, id, now) {
    if (!(await env.COMMUNITY_VOTE_RL.limit({ key: network(request) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { body, tooBig } = await readJson(request);
    const value = body?.value;
    if (tooBig || ![-1, 0, 1].includes(value)) return json({ error: "bad_request" }, 400);
    const post = await env.COMMUNITY_DB.prepare("SELECT poster, up, down, removed_at, expires_at FROM posts WHERE id = ?1").bind(id).first();
    if (!post || post.removed_at !== null || post.expires_at <= now || post.up - post.down <= HIDE_AT) return gone();
    if (post.poster === who.id) return json({ error: "own_post" }, 403);
    if (await env.COMMUNITY_DB.prepare("SELECT 1 AS b FROM blocked WHERE poster = ?1").bind(who.id).first()) {
        return json({ error: "blocked" }, 403);
    }
    const net = await networkHash(request, env);
    const write = value === 0
        ? env.COMMUNITY_DB.prepare("DELETE FROM votes WHERE post_id = ?1 AND voter = ?2").bind(id, who.id)
        : env.COMMUNITY_DB.prepare(
            `INSERT INTO votes (post_id, voter, ip_hash, value, at) VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT (post_id, voter) DO UPDATE SET value = excluded.value, ip_hash = excluded.ip_hash, at = excluded.at`,
        ).bind(id, who.id, net, value, now);
    const [, counted] = await env.COMMUNITY_DB.batch([write, env.COMMUNITY_DB.prepare(RECOUNT_SQL).bind(id)]);
    const { up, down } = counted.results[0];
    return json({ up, down, myVote: value });
}
```

- [ ] **Step 4: Run it to see it pass, then the whole suite**

Run: `node --no-warnings --test test/community-votes.test.js`, then `node --no-warnings --test test/*.test.js`
Expected: PASS, 5 tests; then all PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/community.js infra/share-worker/test/community-votes.test.js
git commit -m "feat(community): votes, recounted with at most 2 per network each way"
```

---

### Task 7: Taking your own post down

**Files:**
- Modify: `infra/share-worker/src/community.js`
- Test: `infra/share-worker/test/community-takedown.test.js`

- [ ] **Step 1: Write the failing test**

```js
// infra/share-worker/test/community-takedown.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { call, seed, songPost, KEY_A, KEY_B } from "./community-helpers.js";

test("the poster takes a post down: it's gone everywhere, and still counts toward today", async () => {
    const e = env();
    const { id } = await (await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).json();
    assert.equal((await call(e, "DELETE", `/v1/community/posts/${id}`, { key: KEY_A })).status, 204);
    assert.equal((await call(e, "GET", `/v1/community/posts/${id}`, { key: KEY_A })).status, 404);
    assert.equal((await (await call(e, "GET", "/v1/community/feed")).json()).posts.length, 0);
    assert.equal((await (await call(e, "GET", "/v1/community/mine", { key: KEY_A })).json()).posts.length, 0);
    assert.equal((await call(e, "DELETE", `/v1/community/posts/${id}`, { key: KEY_A })).status, 404);
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).status, 201);
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).status, 429);
});

test("only the poster can take a post down", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    const r = await call(e, "DELETE", "/v1/community/posts/POST0001", { key: KEY_B });
    assert.equal(r.status, 403);
    assert.deepEqual(await r.json(), { error: "not_yours" });
    assert.equal((await call(e, "DELETE", "/v1/community/posts/POST0001")).status, 401);
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `node --no-warnings --test test/community-takedown.test.js`
Expected: FAIL: `DELETE` answers 405.

- [ ] **Step 3: Add take-down to `src/community.js`**

In `communityRoute`'s `if (m) { … }` block, before `return notAllowed();`, add:

```js
        if (method === "DELETE" && !m[2]) return takeDown(request, env, m[1], now);
```

Add below `vote`:

```js
async function takeDown(request, env, id, now) {
    if (!(await env.COMMUNITY_WRITE_RL.limit({ key: network(request) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const post = await env.COMMUNITY_DB.prepare("SELECT poster, removed_at, expires_at FROM posts WHERE id = ?1").bind(id).first();
    if (!post || post.removed_at !== null || post.expires_at <= now) return gone();
    if (post.poster !== who.id) return json({ error: "not_yours" }, 403);
    await env.COMMUNITY_DB.prepare("UPDATE posts SET removed_at = ?2 WHERE id = ?1").bind(id, now).run();
    return new Response(null, { status: 204 });
}
```

- [ ] **Step 4: Run it to see it pass, then the whole suite**

Run: `node --no-warnings --test test/community-takedown.test.js`, then `node --no-warnings --test test/*.test.js`
Expected: PASS, 2 tests; then all PASS.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/community.js infra/share-worker/test/community-takedown.test.js
git commit -m "feat(community): a poster can take their post down"
```

---

### Task 8: The daily cleanup, and the Worker's bindings

**Files:**
- Modify: `infra/share-worker/src/community.js` (add `cleanup`)
- Modify: `infra/share-worker/src/index.js` (add the `scheduled` handler)
- Modify: `infra/share-worker/wrangler.toml`
- Test: `infra/share-worker/test/community-cleanup.test.js`

- [ ] **Step 1: Write the failing test**

```js
// infra/share-worker/test/community-cleanup.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { cleanup } from "../src/community.js";
import { env } from "./fake-kv.js";
import { seed, HOUR, DAY } from "./community-helpers.js";

const ids = async (e) => (await e.COMMUNITY_DB.prepare("SELECT id FROM posts ORDER BY id").all()).results.map((r) => r.id);
const voteRow = (e, postId) => e.COMMUNITY_DB.prepare("INSERT INTO votes (post_id, voter, ip_hash, value, at) VALUES (?1, 'v', 'n', 1, 1)").bind(postId).run();

test("cleanup drops expired posts and long-removed ones with their votes, and keeps the rest", async () => {
    const e = env();
    const now = Date.now();
    await seed(e, { id: "EXPIRED0", created: now - 31 * DAY });
    await seed(e, { id: "OLDREMOV", created: now - 3 * DAY, removed: now - 2 * DAY });
    await seed(e, { id: "RESTORAB", created: now - 3 * DAY, removed: now - 2 * HOUR }); // removed today: restorable
    await seed(e, { id: "TODAYREM", created: now - 2 * HOUR, removed: now - HOUR }); // counts for today's limit
    await seed(e, { id: "LIVEPOST", created: now - 3 * DAY });
    await voteRow(e, "EXPIRED0");
    await voteRow(e, "LIVEPOST");
    await cleanup(e, now);
    assert.deepEqual(await ids(e), ["LIVEPOST", "RESTORAB", "TODAYREM"]);
    const votes = (await e.COMMUNITY_DB.prepare("SELECT post_id FROM votes").all()).results.map((r) => r.post_id);
    assert.deepEqual(votes, ["LIVEPOST"]);
});

test("the cron handler runs cleanup through waitUntil", async () => {
    const e = env();
    await seed(e, { id: "EXPIRED0", created: Date.now() - 31 * DAY });
    const pending = [];
    await worker.scheduled({ cron: "17 4 * * *" }, e, { waitUntil: (p) => pending.push(p) });
    await Promise.all(pending);
    assert.deepEqual(await ids(e), []);
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `node --no-warnings --test test/community-cleanup.test.js`
Expected: FAIL: `cleanup` is not exported, and `worker.scheduled` is not a function.

- [ ] **Step 3: Add `cleanup` to `src/community.js`**

```js
/**
 * The daily cron (spec §2 Cleanup): expired posts, and posts removed more than a day ago that were also
 * created more than a day ago, with their votes. A removed post stays restorable for a day, and every post
 * of the last 24 hours stays for the daily limit.
 */
export async function cleanup(env, now) {
    const doomed = "SELECT id FROM posts WHERE expires_at <= ?1 OR (removed_at IS NOT NULL AND removed_at < ?2 AND created_at < ?2)";
    await env.COMMUNITY_DB.batch([
        env.COMMUNITY_DB.prepare(`DELETE FROM votes WHERE post_id IN (${doomed})`).bind(now, now - DAY),
        env.COMMUNITY_DB.prepare(`DELETE FROM posts WHERE id IN (${doomed})`).bind(now, now - DAY),
    ]);
}
```

- [ ] **Step 4: Add the cron handler to `src/index.js`**

Change the import to `import { cleanup, communityRoute } from "./community.js";` and add to the default export object, after `fetch`:

```js
    /** The daily cron in wrangler.toml: expired and long-removed Community posts go (spec 2026-09-26 §2). */
    async scheduled(_controller, env, ctx) {
        ctx.waitUntil(cleanup(env, Date.now()));
    },
```

- [ ] **Step 5: Add the bindings to `wrangler.toml`** (append at the end)

```toml
# Stash Community (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2).
[[d1_databases]]
binding = "COMMUNITY_DB"
database_name = "stash-community"
database_id = "SET-BY-TASK-24" # `npx wrangler d1 create stash-community` prints it
migrations_dir = "migrations"

[[ratelimits]]
name = "COMMUNITY_WRITE_RL"
namespace_id = "2005"
simple = { limit = 10, period = 60 }

[[ratelimits]]
name = "COMMUNITY_VOTE_RL"
namespace_id = "2006"
simple = { limit = 60, period = 60 }

[[ratelimits]]
name = "COMMUNITY_READ_RL"
namespace_id = "2007"
simple = { limit = 120, period = 60 }

[triggers]
crons = ["17 4 * * *"]
```

`COMMUNITY_SALT` is a secret (`wrangler secret put`, Task 24), never written in this file.

- [ ] **Step 6: Run it to see it pass, then the whole suite**

Run: `node --no-warnings --test test/community-cleanup.test.js`, then `node --no-warnings --test test/*.test.js`
Expected: PASS, 2 tests; then all PASS.

- [ ] **Step 7: Commit**

```bash
git add infra/share-worker/src/community.js infra/share-worker/src/index.js infra/share-worker/wrangler.toml infra/share-worker/test/community-cleanup.test.js
git commit -m "feat(community): daily cleanup cron, and the D1 and rate-limit bindings"
```

---

### Task 9: The owner's moderation commands

**Files:**
- Create: `infra/share-worker/scripts/community.mjs`
- Modify: `infra/share-worker/package.json` (add `"community": "node scripts/community.mjs"` to `scripts`)
- Test: `infra/share-worker/test/community-cli.test.js`

- [ ] **Step 1: Write the failing test**

```js
// infra/share-worker/test/community-cli.test.js
import { test } from "node:test";
import assert from "node:assert/strict";
import { commandSql } from "../scripts/community.mjs";
import { env } from "./fake-kv.js";
import { seed, DAY } from "./community-helpers.js";

const run = async (e, sql) => {
    for (const s of sql.split(";\n")) await e.COMMUNITY_DB.prepare(s).all();
};

test("remove and restore: restore clears the removal, drops the downvotes and recounts", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3 });
    await e.COMMUNITY_DB.prepare("INSERT INTO votes (post_id, voter, ip_hash, value, at) VALUES ('POST0001','v1','n1',-1,1),('POST0001','v2','n2',-1,1),('POST0001','v3','n3',-1,1),('POST0001','v4','n4',1,1)").run();
    await run(e, commandSql("remove", "POST0001", 42));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT removed_at FROM posts").first("removed_at"), 42);
    await run(e, commandSql("restore", "POST0001", 43));
    const p = await e.COMMUNITY_DB.prepare("SELECT removed_at, up, down FROM posts").first();
    assert.deepEqual(p, { removed_at: null, up: 1, down: 0 });
});

test("block records the poster and removes all their live posts; unblock matches a prefix", async () => {
    const e = env();
    const poster = "ab".repeat(32);
    await seed(e, { id: "POST0001", poster });
    await seed(e, { id: "POST0002", poster });
    await seed(e, { id: "OTHER001", poster: "cd".repeat(32) });
    await run(e, commandSql("block", "POST0001", 7));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT poster FROM blocked").first("poster"), poster);
    const removed = (await e.COMMUNITY_DB.prepare("SELECT id FROM posts WHERE removed_at IS NOT NULL ORDER BY id").all()).results.map((r) => r.id);
    assert.deepEqual(removed, ["POST0001", "POST0002"]);
    await run(e, commandSql("unblock", poster.slice(0, 12)));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM blocked").first("n"), 0);
});

test("list shows the newest and the top posts, with hidden and removed flags", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3, created: Date.now() - DAY });
    const sql = commandSql("list", "10");
    assert.ok(!sql.includes('"'), "no double quotes: the SQL rides in a quoted shell argument");
    const [newest] = (await e.COMMUNITY_DB.prepare(sql.split(";\n")[0]).all()).results;
    assert.equal(newest.id, "POST0001");
    assert.equal(newest.hidden, 1);
});

test("bad arguments never reach the SQL", () => {
    assert.throws(() => commandSql("remove", "x'; DROP TABLE posts; --"));
    assert.throws(() => commandSql("block", "short"));
    assert.throws(() => commandSql("unblock", "zz"));
    assert.throws(() => commandSql("unblock", "abc")); // under 8 characters
    assert.throws(() => commandSql("list", "0"));
    assert.throws(() => commandSql("wipe", "POST0001"));
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `node --no-warnings --test test/community-cli.test.js`
Expected: FAIL, `Cannot find module '.../scripts/community.mjs'`.

- [ ] **Step 3: Write `scripts/community.mjs`**

```js
// infra/share-worker/scripts/community.mjs
/**
 * The owner's Community moderation (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2):
 *
 *   npm run community -- list [n]                 newest and top posts, with hidden/removed flags
 *   npm run community -- remove <postId>          take a post down
 *   npm run community -- restore <postId>         undo a removal or a vote attack (drops its downvotes)
 *   npm run community -- block <postId>           block that post's phone and remove all its live posts
 *   npm run community -- unblock <posterIdPrefix> undo a block (list shows the first 8 characters)
 *
 * Runs on the live database through `wrangler d1 execute` with your own Cloudflare login. `d1 execute` takes
 * no bind parameters, so every argument is checked against a strict pattern before it goes into the SQL.
 */
import { execSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname } from "node:path";
import { RECOUNT_SQL } from "../src/community.js";

const POST_ID = /^[A-Za-z0-9]{8}$/;
const PREFIX = /^[0-9a-f]{8,64}$/;
const USAGE = "usage: npm run community -- list [n] | remove <postId> | restore <postId> | block <postId> | unblock <posterIdPrefix>";

function postId(arg) {
    if (!POST_ID.test(arg ?? "")) throw new Error("expected a post id: 8 letters or digits");
    return arg;
}

/** The SQL for one command; statements are joined with ";\n". */
export function commandSql(command, arg, now = Date.now()) {
    switch (command) {
        case "list": {
            const n = Number.parseInt(arg ?? "20", 10);
            if (!(n >= 1 && n <= 200)) throw new Error("list takes a count from 1 to 200");
            const cols = `id, kind, title, poster_name, up, down, (up - down) <= -3 AS hidden, removed_at IS NOT NULL AS removed,
                ROUND((${now} - created_at) / 3600000.0, 1) AS age_h, substr(poster, 1, 8) AS poster`;
            return [`SELECT ${cols} FROM posts ORDER BY created_at DESC LIMIT ${n}`,
                `SELECT ${cols} FROM posts WHERE removed_at IS NULL ORDER BY (up - down) DESC LIMIT ${n}`].join(";\n");
        }
        case "remove":
            return `UPDATE posts SET removed_at = ${now} WHERE id = '${postId(arg)}'`;
        case "restore": {
            const id = postId(arg);
            return [`UPDATE posts SET removed_at = NULL WHERE id = '${id}'`,
                `DELETE FROM votes WHERE post_id = '${id}' AND value = -1`,
                RECOUNT_SQL.replaceAll("?1", `'${id}'`)].join(";\n");
        }
        case "block": {
            const id = postId(arg);
            return [`INSERT OR IGNORE INTO blocked (poster, at, note) SELECT poster, ${now}, 'post ${id}' FROM posts WHERE id = '${id}'`,
                `UPDATE posts SET removed_at = ${now} WHERE removed_at IS NULL AND poster = (SELECT poster FROM posts WHERE id = '${id}')`].join(";\n");
        }
        case "unblock": {
            if (!PREFIX.test(arg ?? "")) throw new Error("unblock takes the start of a poster id: 8 to 64 hex characters");
            // substr, not LIKE: D1 caps LIKE patterns at 50 bytes.
            return `DELETE FROM blocked WHERE substr(poster, 1, ${arg.length}) = '${arg}'`;
        }
        default:
            throw new Error(USAGE);
    }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
    const [command, arg] = process.argv.slice(2);
    try {
        const sql = commandSql(command, arg).replace(/\s+/g, " ");
        execSync(`npx wrangler d1 execute stash-community --remote --json --command ${JSON.stringify(sql)}`, {
            stdio: "inherit",
            cwd: dirname(dirname(fileURLToPath(import.meta.url))),
        });
    } catch (e) {
        console.error(e.message);
        process.exit(1);
    }
}
```

- [ ] **Step 4: Add the npm script**

In `package.json` `scripts`, add after `"test"`:

```json
    "community": "node scripts/community.mjs"
```

- [ ] **Step 5: Run it to see it pass, then the whole suite**

Run: `node --no-warnings --test test/community-cli.test.js`, then `node --no-warnings --test test/*.test.js`
Expected: PASS, 4 tests; then all PASS.

- [ ] **Step 6: Commit**

```bash
git add infra/share-worker/scripts/community.mjs infra/share-worker/package.json infra/share-worker/test/community-cli.test.js
git commit -m "feat(community): owner moderation commands (list, remove, restore, block, unblock)"
```

---

### Task 10: CI runs the Worker's tests; the READMEs

**Files:**
- Modify: `.github/workflows/tests.yml`
- Modify: `infra/share-worker/README.md`
- Modify: `README.md` (repo root, "What Stash talks to")

- [ ] **Step 1: Add the CI job**

In `.github/workflows/tests.yml`, add under `jobs:` (after `compile-tests`), using the same `if:` as the other jobs so it runs on PRs and on master:

```yaml
  # The share Worker's tests (mixes, rooms, Community). They import only local files, so no npm install.
  worker-tests:
    name: Share worker tests
    if: >-
      github.event_name != 'push' ||
      github.ref == 'refs/heads/master' ||
      contains(github.event.head_commit.message, '[do test]')
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - name: Check out repository
        uses: actions/checkout@v4
      - name: Set up Node
        uses: actions/setup-node@v4
        with:
          node-version: 24
      - name: Run the Worker's tests
        working-directory: infra/share-worker
        run: node --no-warnings --test test/*.test.js
```

Match the `actions/checkout` version the other jobs in the file already use.

- [ ] **Step 2: Document Community in `infra/share-worker/README.md`**

Add after the Listen Together bullet:

```markdown
- Stash Community (spec `docs/superpowers/specs/2026-09-26-stash-community-design.md`): D1 database `stash-community`, bound as `COMMUNITY_DB`, schema in `migrations/`. `src/community-post.js` is the pure part (cleaning a post, summarising a row); `src/community.js` holds the routes and all SQL. Routes: `GET /v1/community/feed`, `GET /v1/community/mine`, `GET /v1/community/me`, `POST /v1/community/posts`, `GET|DELETE /v1/community/posts/{id}`, `PUT /v1/community/posts/{id}/vote`. A phone is identified by `X-Stash-Community-Key` (only its SHA-256 is stored); a network is a salted hash (`COMMUNITY_SALT` secret) of the IPv4 address or IPv6 /56. Limits: 2 posts a day and 5 live per phone, 10 a day per network, 2 counted votes per network per post each way; `COMMUNITY_WRITE_RL` 10/min, `COMMUNITY_VOTE_RL` 60/min, `COMMUNITY_READ_RL` 120/min, each per network. A daily cron (`17 4 * * *`) deletes expired and long-removed posts.
```

And a new section before "## Moving to a custom domain later":

````markdown
## Community moderation

```bash
cd infra/share-worker
npm run community -- list 20             # newest and top posts; `poster` is the first 8 characters of the poster id
npm run community -- remove <postId>
npm run community -- restore <postId>    # undo a removal, or a vote attack (drops the post's downvotes)
npm run community -- block <postId>      # blocks that phone and removes all its live posts
npm run community -- unblock <prefix>
```
````

- [ ] **Step 3: Reword the root README's `stash-share` entry**

In `README.md`, in the `stash-share.rawnaldclark.workers.dev` bullet under "## What Stash talks to":
- Replace the sentence "The Worker uses your IP address to rate-limit and doesn't store it." with "For mixes and Listen Together the Worker uses your IP address to rate-limit and doesn't store it."
- Append at the end of the bullet: "Community, only while you've turned it on in Home layout: reading the list sends nothing about you; posting sends the playlist's or song's details (the same descriptors as a shared mix, with album-art links) and your display name; voting sends which post and which way. A random key made on your phone identifies it for posting and voting; the server keeps only a hash of that key, and a salted hash of your IP address (IPv6 by its /56) for the per-network limits. Posts are public to everyone with Community on and are deleted 30 days after posting, or a day after you take them down."

- [ ] **Step 4: Check the workflow parses**

Run: `python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/tests.yml')); print('ok')"`
Expected: `ok`.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/tests.yml infra/share-worker/README.md README.md
git commit -m "ci+docs(community): run the Worker's tests in CI; document routes, limits, moderation and privacy"
```

---

## Phase B: the app's data layer and the switch

Gradle notes for every app task: run from the worktree root, one Gradle run at a time (parallel runs corrupt KSP's cache). If a run dies with `BindException`, run `./gradlew --stop`, delete `~/.gradle/daemon/*/registry.bin*`, and rerun with `--no-watch-fs`.

**No Compose UI tests.** The repo has no Compose test setup, and adding one (Robolectric plus Hilt test runners) is out of scope. The spec's checks that are about rendering (Post to Community in both `ShareMixSheet` states, the song entry hidden while off) are split: the deciding logic lives in ViewModels and pure functions with unit tests, and the rendering is checked on the phones in Task 25, where each such check is listed.

**One post type.** The spec names `CommunityPost` and `CommunityPostDetail`; the plan uses one `CommunityPost` whose `tracks`/`track` are empty in lists and filled when a post is opened, as the Worker's JSON already is.

### Task 11: Community models, and the song descriptor with art

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/community/Community.kt`
- Modify: `core/model/src/main/kotlin/com/stash/core/model/share/SharedTrack.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/listen/DefaultSessionCatalog.kt`
- Test: `core/model/src/test/kotlin/com/stash/core/model/share/SharedTrackTest.kt`

- [ ] **Step 1: Write the failing test** (append inside `SharedTrackTest`)

```kotlin
    @Test fun `toSharedTrackWithArt keeps an art link on the cover hosts and drops any other`() {
        assertThat(full.copy(albumArtUrl = "https://i.scdn.co/image/abc").toSharedTrackWithArt().artUrl)
            .isEqualTo("https://i.scdn.co/image/abc")
        assertThat(full.copy(albumArtUrl = "https://evil.example/a.jpg").toSharedTrackWithArt().artUrl).isNull()
        assertThat(full.copy(albumArtUrl = "file:///data/art.jpg").toSharedTrackWithArt().artUrl).isNull()
        assertThat(full.toSharedTrackWithArt().copy(artUrl = null)).isEqualTo(full.toSharedTrack())
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :core:model:testDebugUnitTest --tests "com.stash.core.model.share.SharedTrackTest"`
Expected: FAIL to compile, `Unresolved reference: toSharedTrackWithArt`.

- [ ] **Step 3: Add `toSharedTrackWithArt` to `SharedTrack.kt`** (after `toSharedTrack()`)

```kotlin
/**
 * [toSharedTrack] plus the cover this phone shows, when it's an https link on [ShareConfig.COVER_HOSTS].
 * Listen Together and Community send it so other phones show the same art (a local art path never leaves).
 */
fun Track.toSharedTrackWithArt(): SharedTrack =
    toSharedTrack().copy(artUrl = albumArtUrl?.takeIf(ShareConfig::isAllowedCover))
```

- [ ] **Step 4: Point Listen Together's catalog at it**

In `DefaultSessionCatalog.kt`:
- Delete `private fun Track.shared(): SharedTrack = toSharedTrack().copy(artUrl = albumArtUrl?.takeIf(ShareConfig::isAllowedCover))` (line 106) and any KDoc directly above it.
- Replace both calls: `row.toDomain().shared()` → `row.toDomain().toSharedTrackWithArt()`, and `.map { it.shared() }` → `.map { it.toSharedTrackWithArt() }`.
- Add `import com.stash.core.model.share.toSharedTrackWithArt`. Remove the `ShareConfig` and `toSharedTrack` imports only if nothing else in the file still uses them (`grep -n "ShareConfig\|toSharedTrack()" DefaultSessionCatalog.kt`).

- [ ] **Step 5: Create the models**

```kotlin
// core/model/src/main/kotlin/com/stash/core/model/community/Community.kt
package com.stash.core.model.community

import com.stash.core.model.Track
import com.stash.core.model.share.SharedTrack
import kotlinx.serialization.Serializable

/** What any "Post to Community" entry point asks to post (spec 2026-09-26 §3). */
sealed interface PostTarget {
    data class Song(val track: Track) : PostTarget
    data class Playlist(val playlistId: Long) : PostTarget
}

/**
 * A Community post as the Worker sends it (spec §2 Routes). Lists leave [tracks] and [track] empty; an
 * opened post fills [tracks] (a playlist or mix) or [track] (a song). [myVote] and [mine] are only set when
 * the request carried this phone's key; [hidden] and [expiresAt] only in Mine.
 */
@Serializable
data class CommunityPost(
    val id: String,
    /** "playlist", "mix" or "song". */
    val kind: String,
    val title: String,
    /** The poster's "Show my name as". */
    val name: String,
    val count: Int,
    val covers: List<String> = emptyList(),
    val art: String? = null,
    val artist: String? = null,
    val createdAt: Long,
    val up: Int,
    val down: Int,
    val myVote: Int = 0,
    val mine: Boolean = false,
    val hidden: Boolean = false,
    val expiresAt: Long? = null,
    val tracks: List<SharedTrack> = emptyList(),
    val track: SharedTrack? = null,
)

/** `GET /v1/community/me`: what the confirm sheet shows before posting. */
@Serializable
data class CommunityMe(val postsLeftToday: Int, val spotsFree: Int, val blocked: Boolean)
```

- [ ] **Step 6: Run the test, then compile Listen Together's module**

Run: `./gradlew :core:model:testDebugUnitTest --tests "com.stash.core.model.share.SharedTrackTest"`, then `./gradlew :core:media:compileDebugKotlin`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/community/Community.kt core/model/src/main/kotlin/com/stash/core/model/share/SharedTrack.kt core/model/src/test/kotlin/com/stash/core/model/share/SharedTrackTest.kt core/media/src/main/kotlin/com/stash/core/media/listen/DefaultSessionCatalog.kt
git commit -m "feat(community): post models; toSharedTrackWithArt shared with Listen Together"
```

---

### Task 12: Recent songs, and a playlist document with art

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/share/SharedMixRepository.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoRecentlyPlayedTest.kt` (new)
- Test: `core/data/src/test/kotlin/com/stash/core/data/share/SharedMixRepositoryOwnerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoRecentlyPlayedTest.kt
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [TrackDao.getRecentlyPlayed]: the Community picker's recent songs (spec 2026-09-26 §3). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoRecentlyPlayedTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `played songs come newest first, downloaded or stream-only; never-played ones don't come`() = runTest {
        dao.insert(TrackEntity(title = "old", artist = "A", isDownloaded = true, lastPlayed = Instant.ofEpochMilli(100)))
        dao.insert(TrackEntity(title = "streamed", artist = "A", youtubeId = "y1", lastPlayed = Instant.ofEpochMilli(300)))
        dao.insert(TrackEntity(title = "never", artist = "A"))
        assertThat(dao.getRecentlyPlayed(20).map { it.title }).containsExactly("streamed", "old").inOrder()
        assertThat(dao.getRecentlyPlayed(1).map { it.title }).containsExactly("streamed")
    }
}
```

Append inside `SharedMixRepositoryOwnerTest` (its `setUp` has "One" with an `i.scdn.co` cover and "Two" with an `evil.example` one):

```kotlin
    @Test fun `buildDocument withArt keeps each song's allowed art link, and the default leaves art out`() = runBlocking {
        assertThat(repo.buildDocument(playlistId, "Ambient", null).tracks.map { it.artUrl }).containsExactly(null, null)
        assertThat(repo.buildDocument(playlistId, "Ambient", null, withArt = true).tracks.map { it.artUrl })
            .containsExactly("https://i.scdn.co/image/1", null).inOrder()
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.TrackDaoRecentlyPlayedTest" --tests "com.stash.core.data.share.SharedMixRepositoryOwnerTest"`
Expected: FAIL to compile: `getRecentlyPlayed` and the `withArt` parameter don't exist.

- [ ] **Step 3: Add the query to `TrackDao.kt`** (under `getLastPlayedTrack()`)

```kotlin
    /**
     * The Community picker's recent songs (spec 2026-09-26 §3): every played row, newest first, downloaded
     * or stream-only, since it's what the person actually played. [getLastPlayedTrack] is downloaded-only.
     */
    @Query("SELECT * FROM tracks WHERE last_played IS NOT NULL ORDER BY last_played DESC LIMIT :limit")
    suspend fun getRecentlyPlayed(limit: Int): List<TrackEntity>
```

- [ ] **Step 4: Give `buildDocument` its `withArt` flag, and make `withinLimits` reusable**

In `SharedMixRepository.kt`:
- Change the signature to `suspend fun buildDocument(playlistId: Long, name: String, sharedBy: String?, withArt: Boolean = false): SharedMixDocument`, add to its KDoc: "[withArt] keeps each song's cover link (Community posts; a shared mix never sends it).", and change its `tracks =` line to:

```kotlin
            tracks = tracks.take(MAX_TRACKS).map { (if (withArt) it.toSharedTrackWithArt() else it.toSharedTrack()).withinLimits() },
```

- Delete the member `private fun SharedTrack.withinLimits() = copy(...)` and add it at file level, after the `PublishOutcome` enum, as `internal` (Community's repository, in the same module, clips songs the same way), with the art link capped as the Worker caps it:

```kotlin
/** Clips a descriptor to the Worker's limits (worker src/validate.js), so one odd row can't fail a whole mix or post. */
internal fun SharedTrack.withinLimits() = copy(
    title = title.take(500),
    artist = artist.take(500),
    album = album?.take(500),
    isrc = isrc?.takeIf { it.length <= 20 },
    spotifyId = spotifyId?.takeIf { it.length <= 40 },
    youtubeId = youtubeId?.takeIf { it.length <= 20 },
    artUrl = artUrl?.takeIf { it.length <= 1000 },
)
```

- Add `import com.stash.core.model.share.toSharedTrackWithArt`.

- [ ] **Step 5: Run them to see them pass**

Run: the Step 2 command.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt core/data/src/main/kotlin/com/stash/core/data/share/SharedMixRepository.kt core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoRecentlyPlayedTest.kt core/data/src/test/kotlin/com/stash/core/data/share/SharedMixRepositoryOwnerTest.kt
git commit -m "feat(community): recently played songs; buildDocument can keep art links"
```

---

### Task 13: The phone's Community key

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/community/CommunityKeyStore.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/community/CommunityKeyStoreTest.kt`

- [ ] **Step 1: Write the failing test** (the DataStore pattern of `NowPlayingPreferenceTest`: delete the file around each test)

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/community/CommunityKeyStoreTest.kt
package com.stash.core.data.community

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommunityKeyStoreTest {
    private lateinit var context: Context
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("community_preference")
        if (file.exists()) file.delete()
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun `new keys are 43 base64url characters, the format the Worker accepts`() {
        val keys = List(20) { newCommunityKey() }
        keys.forEach { assertThat(it).matches("[A-Za-z0-9_-]{43}") }
        assertThat(keys.toSet()).hasSize(20)
    }

    // One test for the whole life of the key: the DataStore instance outlives a single test.
    @Test fun `the key is made on first use and reused after, and reads never make one`() = runTest {
        val store = CommunityKeyStore(context)
        assertThat(store.existingKey()).isNull()
        val key = store.key()
        assertThat(store.key()).isEqualTo(key)
        assertThat(store.existingKey()).isEqualTo(key)
        assertThat(CommunityKeyStore(context).key()).isEqualTo(key)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.community.CommunityKeyStoreTest"`
Expected: FAIL to compile, `Unresolved reference: CommunityKeyStore`.

- [ ] **Step 3: Write the store**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/community/CommunityKeyStore.kt
package com.stash.core.data.community

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.communityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "community_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 32 random bytes as base64url, 43 characters: the format the Worker's `validEditKey` accepts. */
internal fun newCommunityKey(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

/**
 * This phone's Community key (spec 2026-09-26 §2 Identity); the Worker keeps only its SHA-256. Made on the
 * first post, vote or limits check. Reads send it only once it exists, so just reading never makes one.
 */
@Singleton
class CommunityKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val keyPref = stringPreferencesKey("community_key")
    private val lock = Mutex()

    suspend fun existingKey(): String? =
        try { context.communityDataStore.data.first()[keyPref] } catch (e: IOException) { null }

    /** The key, made and saved first when there is none. */
    suspend fun key(): String = lock.withLock {
        existingKey() ?: newCommunityKey().also { k ->
            // Unsaved, this action still works; the next one makes a new key (a new identity).
            try { context.communityDataStore.edit { it[keyPref] = k } } catch (e: IOException) { }
        }
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: the Step 2 command.
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/community/CommunityKeyStore.kt core/data/src/test/kotlin/com/stash/core/data/community/CommunityKeyStoreTest.kt
git commit -m "feat(community): the phone's community key, made on first use"
```

---

### Task 14: The Community API client and its messages

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/community/CommunityApiClient.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/community/CommunityMessages.kt`
- Modify: `core/network/src/main/kotlin/com/stash/core/network/di/NetworkModule.kt` (redact the key header)
- Test: `core/data/src/test/kotlin/com/stash/core/data/community/CommunityApiClientTest.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/community/CommunityMessagesTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/community/CommunityApiClientTest.kt
package com.stash.core.data.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CommunityApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CommunityApiClient
    private val key = "k".repeat(43)
    private val summary = """{"id":"AAAAAAAA","kind":"playlist","title":"sad boy hours","name":"Maya","count":42,"covers":["https://i.scdn.co/image/a"],"createdAt":5,"up":18,"down":0}"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = CommunityApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `the list carries the key only when there is one, and reads posts`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"posts":[$summary]}"""))
        val posts = (client.feed(5, key = null) as CommunityResult.Ok).value
        assertThat(posts.single().title).isEqualTo("sad boy hours")
        assertThat(posts.single().myVote).isEqualTo(0)
        val first = server.takeRequest()
        assertThat(first.path).isEqualTo("/v1/community/feed?limit=5")
        assertThat(first.getHeader(CommunityApiClient.KEY_HEADER)).isNull()
        server.enqueue(MockResponse().setBody("""{"posts":[]}"""))
        client.feed(100, key)
        assertThat(server.takeRequest().getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
    }

    @Test fun `a post goes out with the key, and empty fields stay out of the body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"BBBBBBBB"}"""))
        val song = NewPost("song", "Sam", "garden", NewPost.Body(track = SharedTrack("garden", "Death Plus", youtubeId = "9Vz")))
        assertThat(client.create(song, key)).isEqualTo(CommunityResult.Ok("BBBBBBBB"))
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/community/posts")
        assertThat(req.getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
        val body = req.body.readUtf8()
        assertThat(body).contains(""""kind":"song","name":"Sam","title":"garden"""")
        assertThat(body).contains(""""track":{"t":"garden","a":"Death Plus","yt":"9Vz"}""")
        assertThat(body).doesNotContain("tracks")
        assertThat(body).doesNotContain("covers")
    }

    @Test fun `a 4xx keeps the Worker's error code; one without a code is unknown`() = runBlocking {
        val codes = listOf(429 to "daily_limit", 429 to "live_limit", 429 to "network_limit", 403 to "blocked",
            429 to "rate_limited", 413 to "too_large", 404 to "gone", 403 to "own_post", 403 to "not_yours", 401 to "bad_key")
        for ((status, code) in codes) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"$code"}"""))
            assertThat(client.me(key)).isEqualTo(CommunityResult.Rejected(code))
        }
        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(client.me(key)).isEqualTo(CommunityResult.Rejected("unknown"))
    }

    @Test fun `a 5xx or no connection is Failed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
        assertThat(client.feed(5, null)).isInstanceOf(CommunityResult.Failed::class.java)
        val offline = CommunityApiClient(OkHttpClient()).apply { baseUrl = "http://127.0.0.1:1" }
        assertThat(offline.feed(5, null)).isInstanceOf(CommunityResult.Failed::class.java)
    }

    @Test fun `a vote sends its value and reads the counts; a take-down answers 204`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"up":3,"down":1,"myVote":-1}"""))
        assertThat(client.vote("AAAAAAAA", -1, key)).isEqualTo(CommunityResult.Ok(VoteCounts(3, 1, -1)))
        val put = server.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.path).isEqualTo("/v1/community/posts/AAAAAAAA/vote")
        assertThat(put.body.readUtf8()).isEqualTo("""{"value":-1}""")
        server.enqueue(MockResponse().setResponseCode(204))
        assertThat(client.takeDown("AAAAAAAA", key)).isEqualTo(CommunityResult.Ok(Unit))
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test fun `an opened post carries its songs with their art`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"post":{"id":"AAAAAAAA","kind":"playlist","title":"sad boy hours","name":"Maya","count":1,"covers":[],"createdAt":5,"up":0,"down":0,"tracks":[{"t":"T1","a":"A","art":"https://i.scdn.co/image/t"}]}}""",
        ))
        val post = (client.post("AAAAAAAA", null) as CommunityResult.Ok).value
        assertThat(post.tracks.single().artUrl).isEqualTo("https://i.scdn.co/image/t")
        assertThat(post.track).isNull()
    }
}
```

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/community/CommunityMessagesTest.kt
package com.stash.core.data.community

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommunityMessagesTest {
    @Test fun `each refusal says which (spec 2026-09-26 §3 Errors)`() {
        mapOf(
            "daily_limit" to "You've posted twice today. Try again tomorrow.",
            "live_limit" to "You have 5 posts up. Take one down to post again.",
            "network_limit" to "Too many posts from your internet connection today. Try again tomorrow.",
            "blocked" to "You can't post to Community.",
            "rate_limited" to "Slow down a moment.",
            "too_large" to "This playlist is too big to post.",
            "gone" to "This post is no longer available.",
            "own_post" to "You can't vote on your own post.",
            "bad_request" to "Something went wrong. Try again.",
        ).forEach { (code, message) -> assertThat(communityMessage(CommunityResult.Rejected(code))).isEqualTo(message) }
        assertThat(communityMessage(CommunityResult.Failed("timeout"))).isEqualTo("Couldn't reach Community. Try again.")
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.community.CommunityApiClientTest" --tests "com.stash.core.data.community.CommunityMessagesTest"`
Expected: FAIL to compile, `Unresolved reference: CommunityApiClient`.

- [ ] **Step 3: Write the client**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/community/CommunityApiClient.kt
package com.stash.core.data.community

import com.stash.core.data.share.ShareJson
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The result of one Community request (spec 2026-09-26 §3). */
sealed interface CommunityResult<out T> {
    data class Ok<T>(val value: T) : CommunityResult<T>

    /** A 4xx: [code] is the Worker's `error` ("daily_limit", "gone", …), or "unknown" when the body had none. */
    data class Rejected(val code: String) : CommunityResult<Nothing>

    /** Offline, a timeout, or a 5xx (the Worker's catch-all is 503 `unavailable`): worth trying again. */
    data class Failed(val reason: String?) : CommunityResult<Nothing>
}

/** `POST /v1/community/posts`'s body. [body] is `{covers, tracks}` for a playlist or mix, `{track}` for a song. */
@Serializable
data class NewPost(val kind: String, val name: String, val title: String, val body: Body) {
    @Serializable
    data class Body(val covers: List<String>? = null, val tracks: List<SharedTrack>? = null, val track: SharedTrack? = null)
}

/** A vote's answer: the post's counted votes and this phone's vote. */
@Serializable
data class VoteCounts(val up: Int, val down: Int, val myVote: Int)

/** HTTP client for the Worker's `/v1/community` routes. Unlike `shareCall`, it keeps a 4xx's `error` code. */
@Singleton
class CommunityApiClient @Inject constructor(private val okHttpClient: OkHttpClient) {
    /** Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrl: String = ShareConfig.BASE_URL

    @Serializable private data class Posts(val posts: List<CommunityPost>)
    @Serializable private data class One(val post: CommunityPost)
    @Serializable private data class Created(val id: String)
    @Serializable private data class Vote(val value: Int)
    @Serializable private data class ErrorBody(val error: String? = null)

    suspend fun feed(limit: Int, key: String?): CommunityResult<List<CommunityPost>> =
        call(request("/v1/community/feed?limit=$limit", key)) { ShareJson.decodeFromString(Posts.serializer(), it).posts }

    suspend fun mine(key: String): CommunityResult<List<CommunityPost>> =
        call(request("/v1/community/mine", key)) { ShareJson.decodeFromString(Posts.serializer(), it).posts }

    suspend fun me(key: String): CommunityResult<CommunityMe> =
        call(request("/v1/community/me", key)) { ShareJson.decodeFromString(CommunityMe.serializer(), it) }

    suspend fun post(id: String, key: String?): CommunityResult<CommunityPost> =
        call(request("/v1/community/posts/$id", key)) { ShareJson.decodeFromString(One.serializer(), it).post }

    suspend fun create(post: NewPost, key: String): CommunityResult<String> =
        call(request("/v1/community/posts", key).post(ShareJson.encodeToString(NewPost.serializer(), post).toRequestBody(JSON))) {
            ShareJson.decodeFromString(Created.serializer(), it).id
        }

    suspend fun vote(id: String, value: Int, key: String): CommunityResult<VoteCounts> =
        call(request("/v1/community/posts/$id/vote", key).put(ShareJson.encodeToString(Vote.serializer(), Vote(value)).toRequestBody(JSON))) {
            ShareJson.decodeFromString(VoteCounts.serializer(), it)
        }

    suspend fun takeDown(id: String, key: String): CommunityResult<Unit> =
        call(request("/v1/community/posts/$id", key).delete()) { }

    private fun request(path: String, key: String?): Request.Builder =
        Request.Builder().url(baseUrl + path).apply { if (key != null) header(KEY_HEADER, key) }

    private suspend fun <T> call(builder: Request.Builder, parse: (String) -> T): CommunityResult<T> =
        withContext(Dispatchers.IO) {
            try {
                okHttpClient.newCall(builder.build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    when (r.code) {
                        in 200..299 -> CommunityResult.Ok(parse(text))
                        in 400..499 -> CommunityResult.Rejected(errorCode(text))
                        else -> CommunityResult.Failed("HTTP ${r.code}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CommunityResult.Failed(e.message)
            }
        }

    private fun errorCode(text: String): String =
        runCatching { ShareJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull() ?: "unknown"

    companion object {
        const val KEY_HEADER = "X-Stash-Community-Key"
        private val JSON = "application/json".toMediaType()
    }
}
```

- [ ] **Step 4: Write the messages**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/community/CommunityMessages.kt
package com.stash.core.data.community

/** The sentence for a Community action that didn't go through (spec 2026-09-26 §3 Errors, §4). */
fun communityMessage(result: CommunityResult<*>): String = when (result) {
    is CommunityResult.Ok -> ""
    is CommunityResult.Failed -> "Couldn't reach Community. Try again."
    is CommunityResult.Rejected -> when (result.code) {
        "daily_limit" -> "You've posted twice today. Try again tomorrow."
        "live_limit" -> "You have 5 posts up. Take one down to post again."
        "network_limit" -> "Too many posts from your internet connection today. Try again tomorrow."
        "blocked" -> "You can't post to Community."
        "rate_limited" -> "Slow down a moment."
        "too_large" -> "This playlist is too big to post."
        "gone" -> "This post is no longer available."
        "own_post" -> "You can't vote on your own post."
        else -> "Something went wrong. Try again."
    }
}
```

- [ ] **Step 5: Keep the key out of debug logs**

In `NetworkModule.kt`, after `redactHeader("X-Stash-Edit-Key")`, add:

```kotlin
            redactHeader("X-Stash-Community-Key")
```

- [ ] **Step 6: Run them to see them pass**

Run: the Step 2 command.
Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/community/CommunityApiClient.kt core/data/src/main/kotlin/com/stash/core/data/community/CommunityMessages.kt core/data/src/test/kotlin/com/stash/core/data/community/CommunityApiClientTest.kt core/data/src/test/kotlin/com/stash/core/data/community/CommunityMessagesTest.kt core/network/src/main/kotlin/com/stash/core/network/di/NetworkModule.kt
git commit -m "feat(community): API client that keeps the Worker's error codes, and their messages"
```

---

### Task 15: The Community repository

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/community/CommunityRepository.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/community/CommunityRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/community/CommunityRepositoryTest.kt
package com.stash.core.data.community

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.share.ShareApiClient
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.community.PostTarget
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
class CommunityRepositoryTest {
    private lateinit var db: StashDatabase
    private lateinit var server: MockWebServer
    private lateinit var shared: SharedMixRepository
    private lateinit var repo: CommunityRepository
    private val prefs = mockk<SharePreference>(relaxed = true)
    private val key = "k".repeat(43)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        val base = server.url("/").toString().removeSuffix("/")
        shared = SharedMixRepository(
            db, db.sharedMixDao(), db.playlistDao(), db.trackDao(), mockk(relaxed = true),
            ShareApiClient(OkHttpClient()).apply { baseUrl = base }, ApplicationProvider.getApplicationContext(),
        )
        repo = repository(existingKey = key)
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private fun repository(existingKey: String?): CommunityRepository {
        val keys = mockk<CommunityKeyStore> {
            coEvery { key() } returns key
            coEvery { existingKey() } returns existingKey
        }
        val api = CommunityApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
        return CommunityRepository(api, keys, shared, db.playlistDao(), db.trackDao(), prefs)
    }

    private suspend fun playlist(type: PlaylistType, songs: Int): Long {
        val id = db.playlistDao().insert(PlaylistEntity(name = "sad boy hours", source = MusicSource.BOTH, sourceId = "p_${type}_$songs", type = type))
        repeat(songs) { i ->
            val t = db.trackDao().insert(TrackEntity(title = "Song $i", artist = "A", youtubeId = "y_${type}_${songs}_$i", source = MusicSource.YOUTUBE))
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(id, t, position = i))
        }
        return id
    }

    @Test fun `a song is posted from its library row, not the player's copy`() = runBlocking {
        val rowId = db.trackDao().insert(TrackEntity(
            title = "garden", artist = "Death Plus", isrc = "USX1", spotifyUri = "spotify:track:abc",
            albumArtUrl = "https://i.scdn.co/image/g", source = MusicSource.SPOTIFY,
        ))
        val playerCopy = Track(id = rowId, title = "garden", artist = "Death Plus", albumArtUrl = "file:///cache/x.jpg")
        val draft = repo.draft(PostTarget.Song(playerCopy)) as Draft.Ready
        assertThat(draft.kind).isEqualTo("song")
        assertThat(draft.tracks.single().isrc).isEqualTo("USX1")
        assertThat(draft.tracks.single().spotifyId).isEqualTo("abc")
        assertThat(draft.covers).containsExactly("https://i.scdn.co/image/g")
    }

    @Test fun `a song with no library row is posted as given`() = runBlocking {
        val draft = repo.draft(PostTarget.Song(Track(id = 999_999, title = "us", artist = "sincewhen", youtubeId = "yt1"))) as Draft.Ready
        assertThat(draft.tracks.single().youtubeId).isEqualTo("yt1")
    }

    @Test fun `playlists post as playlist, Daily and Stash mixes as mix`() = runBlocking {
        assertThat((repo.draft(PostTarget.Playlist(playlist(PlaylistType.CUSTOM, 2))) as Draft.Ready).kind).isEqualTo("playlist")
        assertThat((repo.draft(PostTarget.Playlist(playlist(PlaylistType.DAILY_MIX, 2))) as Draft.Ready).kind).isEqualTo("mix")
        assertThat((repo.draft(PostTarget.Playlist(playlist(PlaylistType.STASH_MIX, 2))) as Draft.Ready).kind).isEqualTo("mix")
    }

    @Test fun `an empty playlist and one over 500 songs can't be posted`() = runBlocking {
        assertThat(repo.draft(PostTarget.Playlist(playlist(PlaylistType.CUSTOM, 0)))).isEqualTo(Draft.Problem("This playlist is empty."))
        assertThat(repo.draft(PostTarget.Playlist(playlist(PlaylistType.CUSTOM, 501))))
            .isEqualTo(Draft.Problem("Too many songs to post (500 at most)."))
    }

    @Test fun `titles are cut to 100 and names to 40 before sending, and the name is remembered`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"AAAAAAAA"}"""))
        val rowId = db.trackDao().insert(TrackEntity(title = "t".repeat(300), artist = "A", youtubeId = "long1", source = MusicSource.YOUTUBE))
        val draft = repo.draft(PostTarget.Song(Track(id = rowId, title = "", artist = ""))) as Draft.Ready
        assertThat(repo.post(draft, "  " + "n".repeat(60))).isEqualTo(CommunityResult.Ok("AAAAAAAA"))
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains(""""title":"${"t".repeat(100)}"""")
        assertThat(body).contains(""""name":"${"n".repeat(40)}"""")
        assertThat(body).contains(""""track":{""")
        coVerify { prefs.setDisplayName("n".repeat(40)) }
    }

    @Test fun `a post and a take-down each bump the revision, a failed one doesn't`() = runBlocking {
        val draft = repo.draft(PostTarget.Song(Track(id = 999_999, title = "us", artist = "sincewhen"))) as Draft.Ready
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"AAAAAAAA"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"daily_limit"}"""))
        val before = repo.revision.value
        repo.post(draft, "Sam")
        repo.takeDown("AAAAAAAA")
        assertThat(repo.post(draft, "Sam")).isEqualTo(CommunityResult.Rejected("daily_limit"))
        assertThat(repo.revision.value).isEqualTo(before + 2)
    }

    @Test fun `a vote that goes through is remembered, and a refused one isn't`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"up":1,"down":0,"myVote":1}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"gone"}"""))
        repo.vote("AAAAAAAA", 1)
        val at = repo.lastVoteAt
        assertThat(at).isGreaterThan(0L)
        repo.vote("AAAAAAAA", 1)
        assertThat(repo.lastVoteAt).isEqualTo(at)
    }

    @Test fun `Mine asks nothing before this phone has a key`() = runBlocking {
        assertThat(repository(existingKey = null).mine()).isEqualTo(CommunityResult.Ok(emptyList<CommunityPost>()))
        assertThat(server.requestCount).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.community.CommunityRepositoryTest"`
Expected: FAIL to compile, `Unresolved reference: CommunityRepository`.

- [ ] **Step 3: Write the repository**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/community/CommunityRepository.kt
package com.stash.core.data.community

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.data.share.withinLimits
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.community.PostTarget
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrackWithArt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What posting would send, or why it can't be posted (spec 2026-09-26 §3 Posting). */
sealed interface Draft {
    /** [kind] is "playlist", "mix" or "song"; [covers] are the playlist's, or the song's art; a song has one track. */
    data class Ready(val kind: String, val title: String, val covers: List<String>, val tracks: List<SharedTrack>) : Draft

    data class Problem(val message: String) : Draft
}

/** Stash Community (spec 2026-09-26 §3): the lists, votes, take-downs, and building and sending a post. */
@Singleton
class CommunityRepository @Inject constructor(
    private val api: CommunityApiClient,
    private val keys: CommunityKeyStore,
    private val sharedMixRepository: SharedMixRepository,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val sharePreference: SharePreference,
) {
    /** Home's last loaded list, shown at once on the next Home open while it reloads. */
    @Volatile var lastHome: List<CommunityPost>? = null

    private val _revision = MutableStateFlow(0)

    /** Goes up after a post or a take-down, so lists on screen reload. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** When this phone's last vote went through. Home reloads on its next showing after one (CommunityViewModel.onShown). */
    @Volatile var lastVoteAt: Long = 0L
        private set

    /** The ranked list. It carries the key only once this phone has one: reading never makes one. */
    suspend fun feed(limit: Int): CommunityResult<List<CommunityPost>> = api.feed(limit, keys.existingKey())

    /** Your live posts, hidden ones included; none, without asking, before this phone has a key. */
    suspend fun mine(): CommunityResult<List<CommunityPost>> =
        keys.existingKey()?.let { api.mine(it) } ?: CommunityResult.Ok(emptyList())

    suspend fun me(): CommunityResult<CommunityMe> = api.me(keys.key())

    suspend fun open(id: String): CommunityResult<CommunityPost> = api.post(id, keys.existingKey())

    suspend fun vote(id: String, value: Int): CommunityResult<VoteCounts> =
        api.vote(id, value, keys.key()).also { if (it is CommunityResult.Ok) lastVoteAt = System.currentTimeMillis() }

    suspend fun takeDown(id: String): CommunityResult<Unit> =
        api.takeDown(id, keys.key()).also { if (it is CommunityResult.Ok) _revision.update { n -> n + 1 } }

    suspend fun displayName(): String? = sharePreference.displayName()

    /** The picker's recent songs: what this phone actually played, newest first. */
    suspend fun recentSongs(): List<Track> = trackDao.getRecentlyPlayed(RECENT_SONGS).map { it.toDomain() }

    suspend fun draft(target: PostTarget): Draft = when (target) {
        is PostTarget.Song -> {
            // The queue and Now Playing hold a copy rebuilt from the player, without ISRC or Spotify id and
            // sometimes with a local art path. Post the library row, as Share does (NowPlayingViewModel.onShareCurrent).
            val track = trackDao.getById(target.track.id)?.toDomain() ?: target.track
            val song = track.toSharedTrackWithArt().withinLimits()
            Draft.Ready("song", song.title.trim().take(100), listOfNotNull(song.artUrl), listOf(song))
        }
        is PostTarget.Playlist -> playlistDraft(target.playlistId)
    }

    private suspend fun playlistDraft(playlistId: Long): Draft {
        val playlist = playlistDao.getById(playlistId) ?: return Draft.Problem("This playlist is empty.")
        val doc = sharedMixRepository.buildDocument(playlistId, playlist.name, sharedBy = null, withArt = true)
        return when {
            doc.tracks.isEmpty() -> Draft.Problem("This playlist is empty.")
            doc.tracks.size > MAX_POST_TRACKS -> Draft.Problem("Too many songs to post (500 at most).")
            else -> Draft.Ready(
                kind = if (playlist.type in MIX_TYPES) "mix" else "playlist",
                title = doc.name.ifBlank { "Playlist" },
                covers = doc.covers,
                tracks = doc.tracks,
            )
        }
    }

    /** Sends [draft] under [name], cut to the Worker's 40 characters and remembered as "Show my name as". */
    suspend fun post(draft: Draft.Ready, name: String): CommunityResult<String> {
        val poster = name.trim().take(40)
        sharePreference.setDisplayName(poster)
        val body = if (draft.kind == "song") NewPost.Body(track = draft.tracks.single())
        else NewPost.Body(covers = draft.covers, tracks = draft.tracks)
        return api.create(NewPost(draft.kind, poster, draft.title, body), keys.key())
            .also { if (it is CommunityResult.Ok) _revision.update { n -> n + 1 } }
    }

    companion object {
        const val HOME_SIZE = 5
        const val ALL_SIZE = 100
        const val MAX_POST_TRACKS = 500
        private const val RECENT_SONGS = 20
        private val MIX_TYPES = setOf(PlaylistType.DAILY_MIX, PlaylistType.STASH_MIX)
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: the Step 2 command.
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/community/CommunityRepository.kt core/data/src/test/kotlin/com/stash/core/data/community/CommunityRepositoryTest.kt
git commit -m "feat(community): repository: drafts from a playlist or a song's row, posting, votes, take-down"
```

---

### Task 16: Turning Community on

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/prefs/HomeSectionsPreference.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`, `HomeUiState.kt`, `HomeViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAppearanceScreen.kt`, `SettingsUiState.kt`, `SettingsViewModel.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/prefs/HomeSectionOrderTest.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/prefs/HomeSectionsCommunityTest.kt` (new)

- [ ] **Step 1: Write the failing tests**

In `HomeSectionOrderTest.kt`:
- The expected lists in `saved permutation is honored` and `unknown keys are dropped and missing sections appended in default order` gain `HomeSection.COMMUNITY` as their last element (a section new in an update is appended at the end).
- Add `import org.junit.Assert.assertFalse` and these tests:

```kotlin
    @Test
    fun `community is off by default, including right after the update that adds it`() {
        assertFalse(HomeSection.COMMUNITY in visibleHomeSections(resolveHomeSectionOrder(emptyList()), emptySet(), communityOn = false))
        val savedBeforeTheUpdate = listOf("your_playlists", "new_releases", "qobuz_playlists", "top_albums", "made_for_you", "radios", "mood_decades")
        assertEquals(
            savedBeforeTheUpdate.mapNotNull(HomeSection::fromKey),
            visibleHomeSections(resolveHomeSectionOrder(savedBeforeTheUpdate), emptySet(), communityOn = false),
        )
        // Home before the preference loads, and the preference's fallback when it can't be read.
        assertFalse(HomeSection.COMMUNITY in DEFAULT_HOME_SECTIONS)
    }

    @Test
    fun `turned on, community shows where it sits in the order`() {
        val order = withCommunityFirst(resolveHomeSectionOrder(emptyList()))
        assertEquals(HomeSection.COMMUNITY, visibleHomeSections(order, emptySet(), communityOn = true).first())
        assertEquals(order - HomeSection.COMMUNITY, visibleHomeSections(order, emptySet(), communityOn = false))
    }

    @Test
    fun `withCommunityFirst moves community to the top and keeps the rest in order`() {
        val order = resolveHomeSectionOrder(listOf("radios", "top_albums"))
        val moved = withCommunityFirst(order)
        assertEquals(HomeSection.COMMUNITY, moved.first())
        assertEquals(order - HomeSection.COMMUNITY, moved.drop(1))
    }
```

And the DataStore test (one test for the whole sequence, as the store outlives a single test):

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/prefs/HomeSectionsCommunityTest.kt
package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeSectionsCommunityTest {
    private lateinit var context: Context
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("home_sections_preference")
        if (file.exists()) file.delete()
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun `off until turned on; the first time it goes to the top, after that it stays where it was put`() = runTest {
        val pref = HomeSectionsPreference(context)
        assertFalse(pref.communityOn.first())
        assertFalse(HomeSection.COMMUNITY in pref.visibleSections.first())

        pref.setCommunityOn(true)
        assertTrue(pref.communityOn.first())
        assertEquals(HomeSection.COMMUNITY, pref.visibleSections.first().first())

        pref.move(HomeSection.COMMUNITY, up = false)
        pref.setCommunityOn(false)
        assertFalse(HomeSection.COMMUNITY in pref.visibleSections.first())
        pref.setCommunityOn(true)
        assertEquals(HomeSection.COMMUNITY, pref.visibleSections.first()[1])
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.prefs.HomeSectionOrderTest" --tests "com.stash.core.data.prefs.HomeSectionsCommunityTest"`
Expected: FAIL to compile: `COMMUNITY`, `visibleHomeSections`, `withCommunityFirst`, `DEFAULT_HOME_SECTIONS`, `communityOn` and `setCommunityOn` don't exist.

- [ ] **Step 3: Add the section and its switch to `HomeSectionsPreference.kt`**

In the enum, after `MOOD_DECADES("mood_decades")` (change its `;` to `,`):

```kotlin
    /** Stash Community (spec 2026-09-26 §3). Has its own switch, off by default: see [HomeSectionsPreference.communityOn]. */
    COMMUNITY("community");
```

After `resolveHomeSectionOrder`:

```kotlin
/** Home before the preference loads, and when it can't be read: every section but Community, which is opt-in. */
val DEFAULT_HOME_SECTIONS: List<HomeSection> = HomeSection.entries - HomeSection.COMMUNITY

/**
 * What Home renders: [order] minus [hidden], and minus COMMUNITY unless [communityOn]. Community has its own
 * switch, off by default (spec 2026-09-26 §3), because a section new in an update is otherwise shown.
 */
fun visibleHomeSections(order: List<HomeSection>, hidden: Set<HomeSection>, communityOn: Boolean): List<HomeSection> =
    order.filter { it !in hidden && (communityOn || it != HomeSection.COMMUNITY) }

/** [order] with COMMUNITY first: where it lands the first time it's turned on, under the Discover hero. */
fun withCommunityFirst(order: List<HomeSection>): List<HomeSection> =
    listOf(HomeSection.COMMUNITY) + (order - HomeSection.COMMUNITY)
```

In the class, next to the other keys:

```kotlin
    private val communityOnKey = booleanPreferencesKey("community_on")
```

After `hidden`:

```kotlin
    /** Community's own switch (spec 2026-09-26 §3): off until turned on in Settings ▸ Home layout. */
    val communityOn: Flow<Boolean> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[communityOnKey] ?: false
    }.distinctUntilChanged().catch { emit(false) }
```

Replace `visibleSections` (keep its KDoc, changing its first line to "What Home actually renders: [order] minus [hidden], and minus Community while its switch is off."):

```kotlin
    val visibleSections: Flow<List<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        val hiddenSet = prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toSet()
        visibleHomeSections(resolveHomeSectionOrder(prefs[orderKey].toKeys()), hiddenSet, prefs[communityOnKey] ?: false)
    }.distinctUntilChanged().catch { emit(DEFAULT_HOME_SECTIONS) }
```

After `setShowLikedOnHome`:

```kotlin
    /** Community's switch. The first time it's turned on, it moves to the top of the order. */
    suspend fun setCommunityOn(on: Boolean) {
        context.homeSectionsDataStore.edit { prefs ->
            if (on && prefs[communityOnKey] == null) {
                prefs[orderKey] = withCommunityFirst(resolveHomeSectionOrder(prefs[orderKey].toKeys())).joinToString(",") { it.key }
            }
            prefs[communityOnKey] = on
        }
    }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: the Step 2 command.
Expected: PASS.

- [ ] **Step 5: Home: the defaults leave Community out, and a slot renders it**

- `HomeUiState.kt`: the `sections` default becomes `com.stash.core.data.prefs.DEFAULT_HOME_SECTIONS`.
- `HomeViewModel.kt`: `DiscoveryUi.sections`' default becomes `com.stash.core.data.prefs.DEFAULT_HOME_SECTIONS`.
- `HomeScreen.kt`: add the parameter `communitySection: @Composable () -> Unit = {},` after `onShareMix`, with the KDoc line `/** Community's section, from feature/community; the app fills it (spec 2026-09-26 §3). */`, and a branch in the `when (section)`, after `MOOD_DECADES`:

```kotlin
                HomeSection.COMMUNITY -> item(key = "section_community") { communitySection() }
```

- [ ] **Step 6: Settings: Community's row with its own switch**

- `SettingsUiState.kt`, after `showLikedOnHome`:

```kotlin
    /** Home's Community section (spec 2026-09-26 §3): its own switch, off by default. */
    val communityOn: Boolean = false,
```

- `SettingsViewModel.kt`: in the `combine(...)` list add `homeSectionsPreference.communityOn,` directly after `homeSectionsPreference.showLikedOnHome,`; in the reads add `val communityOn = v.next<Boolean>()` directly after `val showLikedOnHome = v.next<Boolean>()`; in `SettingsUiState(...)` add `communityOn = communityOn,` after `showLikedOnHome = showLikedOnHome,`. After `onShowLikedOnHomeChanged` add:

```kotlin
    /** Community's switch (Settings > Appearance > Home layout); the first time on, it moves to the top. */
    fun onCommunityOnChanged(on: Boolean) {
        viewModelScope.launch { homeSectionsPreference.setCommunityOn(on) }
    }
```

- `SettingsAppearanceScreen.kt`:
  - `displayLabel()` gains `com.stash.core.data.prefs.HomeSection.COMMUNITY -> "Community"`.
  - The `forEachIndexed` body becomes:

```kotlin
        uiState.homeSectionOrder.forEachIndexed { index, section ->
            // Community's switch is its own, off by default (spec 2026-09-26 §3), not the hidden set.
            val community = section == com.stash.core.data.prefs.HomeSection.COMMUNITY
            HomeSectionRow(
                label = section.displayLabel(),
                subtitle = if (community) "Playlists and songs other Stash listeners are into. Your posts show your name." else null,
                shown = if (community) uiState.communityOn else section !in uiState.homeSectionsHidden,
                canMoveUp = index > 0,
                canMoveDown = index < uiState.homeSectionOrder.lastIndex,
                onMoveUp = { viewModel.onHomeSectionMoved(section, up = true) },
                onMoveDown = { viewModel.onHomeSectionMoved(section, up = false) },
                onShownChange = { shown ->
                    if (community) viewModel.onCommunityOnChanged(shown) else viewModel.onHomeSectionHiddenChanged(section, hide = !shown)
                },
            )
        }
```

  - `HomeSectionRow` gains `subtitle: String? = null` after `label`, and its label `Text` (which carries `Modifier.weight(1f)`) becomes a column:

```kotlin
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (shown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
```

- [ ] **Step 7: Compile the two screens' modules and run their tests**

Run: `./gradlew :feature:home:testDebugUnitTest :feature:settings:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all PASS. (`HomeViewModelTest` stubs `visibleSections` with every entry, COMMUNITY included; the ViewModel passes sections through, so nothing there changes.)

- [ ] **Step 8: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/prefs/HomeSectionsPreference.kt core/data/src/test/kotlin/com/stash/core/data/prefs/HomeSectionOrderTest.kt core/data/src/test/kotlin/com/stash/core/data/prefs/HomeSectionsCommunityTest.kt feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAppearanceScreen.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt
git commit -m "feat(community): Home section with its own switch, off by default; first turned on, it goes to the top"
```

---

## Phase C: the Community screens, the entry points and posting

### Task 17: The feature/community module, and its list ViewModel

**Files:**
- Create: `feature/community/build.gradle.kts`, `feature/community/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts`
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/CommunityViewModel.kt`
- Test: `feature/community/src/test/kotlin/com/stash/feature/community/CommunityViewModelTest.kt`

- [ ] **Step 1: Create the module**

```kotlin
// feature/community/build.gradle.kts
plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.community"

    testOptions {
        unitTests {
            // android.util.Log in ViewModels returns defaults instead of throwing "not mocked".
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("io.mockk:mockk:1.13.8") // same version core/data uses
}
```

`feature/community/src/main/AndroidManifest.xml`, the same two lines as every other feature module's (an XML declaration must be a file's first line, so no path comment):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

In `settings.gradle.kts`, add `include(":feature:community")` after `include(":feature:search")`.

- [ ] **Step 2: Write the failing test**

```kotlin
// feature/community/src/test/kotlin/com/stash/feature/community/CommunityViewModelTest.kt
package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.VoteCounts
import com.stash.core.model.community.CommunityPost
import com.stash.feature.community.CommunityViewModel.Tab
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val revision = MutableStateFlow(0)
    private val repo = mockk<CommunityRepository>(relaxed = true)

    private fun post(up: Int = 3, down: Int = 1, myVote: Int = 0, mine: Boolean = false) = CommunityPost(
        id = "AAAAAAAA", kind = "song", title = "garden", name = "Sam", count = 1, createdAt = 1,
        up = up, down = down, myVote = myVote, mine = mine,
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repo.revision } returns revision
        every { repo.lastHome } returns null
        every { repo.lastVoteAt } returns 0L
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `withVote moves the counts by the change`() {
        assertThat(post().withVote(1)).isEqualTo(post(up = 4, myVote = 1))
        assertThat(post(up = 4, myVote = 1).withVote(-1)).isEqualTo(post(up = 3, down = 2, myVote = -1))
        assertThat(post(up = 3, down = 2, myVote = -1).withVote(0)).isEqualTo(post())
    }

    @Test fun `tapping your current vote again takes it back`() {
        assertThat(post(myVote = 1).nextVote(1)).isEqualTo(0)
        assertThat(post(myVote = 1).nextVote(-1)).isEqualTo(-1)
        assertThat(post().nextVote(1)).isEqualTo(1)
    }

    @Test fun `a vote shows at once, then takes the server's counts`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(post()))
        coEvery { repo.vote("AAAAAAAA", 1) } returns CommunityResult.Ok(VoteCounts(up = 9, down = 1, myVote = 1))
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(post(), 1)
        assertThat(vm.state.value.posts).containsExactly(post(up = 4, myVote = 1))
        advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post(up = 9, myVote = 1))
        verify { repo.lastHome = listOf(post(up = 9, myVote = 1)) }
    }

    @Test fun `a refused vote flips back and says why`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(post()))
        coEvery { repo.vote(any(), any()) } returns CommunityResult.Failed("offline")
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(post(), -1); advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post())
        assertThat(vm.state.value.message).isEqualTo("Couldn't reach Community. Try again.")
    }

    @Test fun `your own post can't be voted on`() = runTest(dispatcher) {
        val vm = CommunityViewModel(repo)
        vm.vote(post(mine = true), 1); advanceUntilIdle()
        coVerify(exactly = 0) { repo.vote(any(), any()) }
    }

    @Test fun `Home shows the last list at once and keeps it when a reload fails`() = runTest(dispatcher) {
        every { repo.lastHome } returns listOf(post())
        coEvery { repo.feed(5) } returns CommunityResult.Failed("offline")
        val vm = CommunityViewModel(repo)
        assertThat(vm.state.value.posts).containsExactly(post())
        vm.show(Tab.HOME); advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post())
        assertThat(vm.state.value.failed).isFalse()
    }

    @Test fun `nothing loaded and no connection is a failure to retry`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Failed("offline")
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        assertThat(vm.state.value.posts).isNull()
        assertThat(vm.state.value.failed).isTrue()
    }

    @Test fun `Mine replaces the list instead of showing All's while it loads`() = runTest(dispatcher) {
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(post()))
        coEvery { repo.mine() } returns CommunityResult.Ok(emptyList())
        val vm = CommunityViewModel(repo)
        vm.show(Tab.ALL); advanceUntilIdle()
        vm.show(Tab.MINE)
        assertThat(vm.state.value.posts).isNull()
        advanceUntilIdle()
        assertThat(vm.state.value.posts).isEmpty()
    }

    @Test fun `Home reloads when it shows again only after 30 seconds or a vote`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returnsMany listOf(CommunityResult.Ok(listOf(post())), CommunityResult.Ok(listOf(post(up = 7))))
        val vm = CommunityViewModel(repo)
        vm.onShown(); advanceUntilIdle()
        vm.onShown(); advanceUntilIdle()
        coVerify(exactly = 1) { repo.feed(5) }
        every { repo.lastVoteAt } returns Long.MAX_VALUE
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post(up = 7))
    }

    @Test fun `a post or take-down elsewhere reloads the list on screen`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returnsMany listOf(CommunityResult.Ok(emptyList()), CommunityResult.Ok(listOf(post())))
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        revision.value = 1; advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post())
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./gradlew :feature:community:testDebugUnitTest`
Expected: FAIL to compile, `Unresolved reference: CommunityViewModel`.

- [ ] **Step 4: Write the ViewModel**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/CommunityViewModel.kt
package com.stash.feature.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.communityMessage
import com.stash.core.model.community.CommunityPost
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [this] as if [value] were now this phone's vote: the optimistic change (spec 2026-09-26 §3 Votes). */
internal fun CommunityPost.withVote(value: Int): CommunityPost = copy(
    up = up - (if (myVote == 1) 1 else 0) + (if (value == 1) 1 else 0),
    down = down - (if (myVote == -1) 1 else 0) + (if (value == -1) 1 else 0),
    myVote = value,
)

/** Tapping ▲ (1) or ▼ (-1). Tapping your current vote again takes it back (0). */
internal fun CommunityPost.nextVote(tapped: Int): Int = if (myVote == tapped) 0 else tapped

/** A list of posts with optimistic votes: Home's section (HOME) and See all (ALL, MINE). */
@HiltViewModel
class CommunityViewModel @Inject constructor(private val repository: CommunityRepository) : ViewModel() {
    enum class Tab { HOME, ALL, MINE }

    /** [posts] is null until something loads; [failed] means nothing loaded and the last try failed (Retry). */
    data class UiState(
        val tab: Tab = Tab.HOME,
        val posts: List<CommunityPost>? = null,
        val failed: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState(posts = repository.lastHome))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var loading: Job? = null
    private var loadedAt = 0L
    private val voting = mutableSetOf<String>()

    init {
        // A post or a take-down (the posting sheet, a post screen) reloads whatever is on screen.
        viewModelScope.launch { repository.revision.drop(1).collect { show(_state.value.tab) } }
    }

    /**
     * Home calls this each time the section appears. It reloads unless it loaded in the last 30 seconds and no
     * vote went through since (See all and the post screen keep their own lists).
     */
    fun onShown() {
        if (System.currentTimeMillis() - loadedAt > 30_000 || repository.lastVoteAt > loadedAt) show(Tab.HOME)
    }

    /** Loads [tab]. The list on screen stays until the new one arrives, unless one is Mine and the other isn't. */
    fun show(tab: Tab) {
        _state.update { s ->
            val sameKind = (tab == Tab.MINE) == (s.tab == Tab.MINE)
            s.copy(tab = tab, posts = if (sameKind) s.posts else null, failed = false)
        }
        loading?.cancel()
        loading = viewModelScope.launch {
            val result = when (tab) {
                Tab.HOME -> repository.feed(CommunityRepository.HOME_SIZE)
                Tab.ALL -> repository.feed(CommunityRepository.ALL_SIZE)
                Tab.MINE -> repository.mine()
            }
            if (result is CommunityResult.Ok) {
                loadedAt = System.currentTimeMillis()
                if (tab == Tab.HOME) repository.lastHome = result.value
            }
            _state.update { s ->
                when {
                    s.tab != tab -> s
                    result is CommunityResult.Ok -> s.copy(posts = result.value, failed = false)
                    else -> s.copy(failed = s.posts == null)
                }
            }
        }
    }

    fun vote(post: CommunityPost, tapped: Int) {
        if (post.mine || !voting.add(post.id)) return
        val value = post.nextVote(tapped)
        patch(post.id) { it.withVote(value) }
        viewModelScope.launch {
            try {
                when (val r = repository.vote(post.id, value)) {
                    is CommunityResult.Ok -> patch(post.id) { it.copy(up = r.value.up, down = r.value.down, myVote = r.value.myVote) }
                    else -> {
                        patch(post.id) { post }
                        _state.update { it.copy(message = communityMessage(r)) }
                    }
                }
            } finally {
                voting.remove(post.id)
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun patch(id: String, change: (CommunityPost) -> CommunityPost) {
        _state.update { s -> s.copy(posts = s.posts?.map { if (it.id == id) change(it) else it }) }
        if (_state.value.tab == Tab.HOME) repository.lastHome = _state.value.posts
    }
}
```

- [ ] **Step 5: Run it to see it pass**

Run: `./gradlew :feature:community:testDebugUnitTest`
Expected: PASS, 10 tests.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts feature/community/build.gradle.kts feature/community/src/main/AndroidManifest.xml feature/community/src/main/kotlin/com/stash/feature/community/CommunityViewModel.kt feature/community/src/test/kotlin/com/stash/feature/community/CommunityViewModelTest.kt
git commit -m "feat(community): feature module; list ViewModel with optimistic votes"
```

---

### Task 18: Rows, the Home section, and See all

**Files:**
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/CommunityRows.kt`
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/CommunitySection.kt`
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/CommunityScreen.kt`
- Test: `feature/community/src/test/kotlin/com/stash/feature/community/CommunityRowsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// feature/community/src/test/kotlin/com/stash/feature/community/CommunityRowsTest.kt
package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.community.CommunityPost
import org.junit.Test

class CommunityRowsTest {
    private val day = 86_400_000L
    private val now = 10 * day

    private fun post(kind: String = "playlist", mine: Boolean = false, age: Long = 3 * day) = CommunityPost(
        id = "AAAAAAAA", kind = kind, title = "sad boy hours", name = "Maya", count = 42,
        artist = "Death Plus".takeIf { kind == "song" }, createdAt = now - age, up = 18, down = 0, mine = mine,
    )

    @Test fun `rows say what the post is and who posted it`() {
        assertThat(post().subtitle(withAge = false, now)).isEqualTo("Playlist · 42 songs · Maya")
        assertThat(post(kind = "mix").subtitle(withAge = true, now)).isEqualTo("Mix · 42 songs · Maya · 3d")
        assertThat(post(kind = "song").subtitle(withAge = false, now)).isEqualTo("Song · Death Plus · Maya")
        // Your own post is tagged YOU, so its age takes the name's place.
        assertThat(post(mine = true, age = 30_000).subtitle(withAge = false, now)).isEqualTo("Playlist · 42 songs · just now")
    }

    @Test fun `ages read short in lists and long on a post`() {
        assertThat(ago(now - 5 * 60_000, now)).isEqualTo("5m")
        assertThat(ago(now - 4 * 3_600_000, now)).isEqualTo("4h")
        assertThat(ago(now - day, now, long = true)).isEqualTo("1 day ago")
        assertThat(ago(now - 3 * day, now, long = true)).isEqualTo("3 days ago")
    }

    @Test fun `a song's cover is its art, a playlist's its covers`() {
        assertThat(post(kind = "song").copy(art = "https://i.ytimg.com/a").coverUrls()).containsExactly("https://i.ytimg.com/a")
        assertThat(post().copy(covers = listOf("https://i.scdn.co/1", "https://i.scdn.co/2")).coverUrls()).hasSize(2)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :feature:community:testDebugUnitTest --tests "com.stash.feature.community.CommunityRowsTest"`
Expected: FAIL to compile, `Unresolved reference: subtitle`.

- [ ] **Step 3: Write the rows**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/CommunityRows.kt
package com.stash.feature.community

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.ShareConfig
import com.stash.core.ui.theme.StashTheme

internal fun kindLabel(kind: String): String = when (kind) {
    "mix" -> "Mix"
    "song" -> "Song"
    else -> "Playlist"
}

internal fun songs(n: Int): String = if (n == 1) "1 song" else "$n songs"

/** How long ago [then] was: "just now", "5m", "4h", "3d"; [long] spells it out: "5 minutes ago", "3 days ago". */
internal fun ago(then: Long, now: Long, long: Boolean = false): String {
    val minutes = (now - then).coerceAtLeast(0) / 60_000
    fun of(n: Long, short: String, word: String) = if (long) "$n $word${if (n == 1L) "" else "s"} ago" else "$n$short"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> of(minutes, "m", "minute")
        minutes < 24 * 60 -> of(minutes / 60, "h", "hour")
        else -> of(minutes / (24 * 60), "d", "day")
    }
}

/**
 * "Playlist · 42 songs · Maya", "Song · Death Plus · Sam". Your own post is tagged YOU, so its age takes the
 * name's place; [withAge] adds the age to everyone else's (See all).
 */
internal fun CommunityPost.subtitle(withAge: Boolean, now: Long): String {
    val what = if (kind == "song") artist.orEmpty() else songs(count)
    val who = when {
        mine -> ago(createdAt, now)
        withAge -> "$name · ${ago(createdAt, now)}"
        else -> name
    }
    return listOf(kindLabel(kind), what, who).filter { it.isNotBlank() }.joinToString(" · ")
}

internal fun CommunityPost.coverUrls(): List<String> = if (kind == "song") listOfNotNull(art) else covers

/** One cover, or a 2×2 mosaic when there are four (spec 2026-09-26 §3). Only cover-host links are ever loaded. */
@Composable
internal fun CoverArt(urls: List<String>, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val safe = urls.filter(ShareConfig::isAllowedCover)
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(StashTheme.extendedColors.glassBackground),
        contentAlignment = Alignment.Center,
    ) {
        when {
            safe.size >= 4 -> Column(Modifier.fillMaxSize()) {
                for (pair in safe.take(4).chunked(2)) Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (url in pair) AsyncImage(url, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
            }
            safe.isNotEmpty() -> AsyncImage(safe.first(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Icon(Icons.Default.MusicNote, contentDescription = null, tint = StashTheme.extendedColors.textTertiary)
        }
    }
}

/** ▲ score ▼ (spec §3 Votes): your vote lights in purple; on your own post the arrows just frame the score. */
@Composable
internal fun VoteControl(post: CommunityPost, onVote: (Int) -> Unit, modifier: Modifier = Modifier, vertical: Boolean = true) {
    val up = @Composable { VoteArrow(Icons.Default.KeyboardArrowUp, "Vote up", lit = post.myVote == 1, enabled = !post.mine) { onVote(1) } }
    val score = @Composable {
        Text(
            text = "${post.up - post.down}",
            style = MaterialTheme.typography.labelLarge,
            color = if (post.myVote != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
    val down = @Composable { VoteArrow(Icons.Default.KeyboardArrowDown, "Vote down", lit = post.myVote == -1, enabled = !post.mine) { onVote(-1) } }
    if (vertical) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { up(); score(); down() }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) { up(); score(); down() }
    }
}

@Composable
private fun VoteArrow(icon: ImageVector, label: String, lit: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (lit) MaterialTheme.colorScheme.primary else StashTheme.extendedColors.textTertiary,
        modifier = Modifier
            .size(width = 40.dp, height = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/** One ranked post (the Top Albums row, with votes on the right). [rank] null leaves the number out (Mine). */
@Composable
internal fun PostRow(rank: Int?, post: CommunityPost, withAge: Boolean, onOpen: () -> Unit, onVote: (Int) -> Unit) {
    val now = remember { System.currentTimeMillis() }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (rank != null) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                color = StashTheme.extendedColors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(22.dp),
            )
        }
        CoverArt(post.coverUrls(), Modifier.size(46.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = post.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (post.mine) YouTag()
            }
            Text(
                text = post.subtitle(withAge, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (post.hidden) {
                Text("Hidden by votes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        VoteControl(post, onVote)
    }
}

@Composable
private fun YouTag() {
    Text(
        text = "YOU",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** What shows instead of rows (spec §3): loading, "Couldn't load Community" with Retry, or [emptyText]. */
@Composable
internal fun ListStatus(state: CommunityViewModel.UiState, emptyText: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
        val posts = state.posts
        when {
            posts == null && state.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Couldn't load Community",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            posts == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            posts.isEmpty() -> Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shows [message] once as a short toast, then clears it. */
@Composable
internal fun MessageToast(message: String?, onShown: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onShown()
        }
    }
}
```

- [ ] **Step 4: Write the Home section**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/CommunitySection.kt
package com.stash.feature.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Home's Community section (spec 2026-09-26 §3): the top 5 as ranked rows with votes, `+` and See all.
 * Composed only while Community is on, so nothing here runs, or asks the Worker anything, while it's off.
 */
@Composable
fun CommunitySection(
    onOpenPost: (String) -> Unit,
    onSeeAll: () -> Unit,
    onPost: () -> Unit,
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onShown() }
    MessageToast(state.message, viewModel::messageShown)
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Community", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "What Stash listeners are digging this week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPost) {
                Icon(Icons.Default.Add, contentDescription = "Post to Community", tint = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        val posts = state.posts
        if (posts.isNullOrEmpty()) {
            ListStatus(state, "Nothing here yet. Be the first: tap +.", onRetry = { viewModel.show(CommunityViewModel.Tab.HOME) })
        } else {
            posts.forEachIndexed { i, p ->
                PostRow(rank = i + 1, post = p, withAge = false, onOpen = { onOpenPost(p.id) }, onVote = { viewModel.vote(p, it) })
            }
        }
    }
}
```

- [ ] **Step 5: Write See all**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/CommunityScreen.kt
package com.stash.feature.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.feature.community.CommunityViewModel.Tab

/** See all (spec 2026-09-26 §3): All / Mine, up to 100 posts, `+ Post` in the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    mine: Boolean,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onPost: () -> Unit,
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(if (mine) Tab.MINE else Tab.ALL) }
    LaunchedEffect(tab) { viewModel.show(tab) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MessageToast(state.message, viewModel::messageShown)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Community", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onPost) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Post")
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == Tab.ALL, onClick = { tab = Tab.ALL }, label = { Text("All") })
            FilterChip(selected = tab == Tab.MINE, onClick = { tab = Tab.MINE }, label = { Text("Mine") })
        }
        val posts = state.posts
        if (posts.isNullOrEmpty()) {
            ListStatus(
                state,
                if (tab == Tab.MINE) "You have no posts up." else "Nothing here yet. Be the first: tap + Post.",
                onRetry = { viewModel.show(tab) },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                itemsIndexed(posts, key = { _, p -> p.id }) { i, p ->
                    PostRow(
                        rank = if (tab == Tab.MINE) null else i + 1,
                        post = p,
                        withAge = true,
                        onOpen = { onOpenPost(p.id) },
                        onVote = { viewModel.vote(p, it) },
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 6: Run the tests and compile**

Run: `./gradlew :feature:community:testDebugUnitTest`
Expected: PASS (the Task 17 tests plus 3 new), BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add feature/community/src/main/kotlin/com/stash/feature/community/CommunityRows.kt feature/community/src/main/kotlin/com/stash/feature/community/CommunitySection.kt feature/community/src/main/kotlin/com/stash/feature/community/CommunityScreen.kt feature/community/src/test/kotlin/com/stash/feature/community/CommunityRowsTest.kt
git commit -m "feat(community): ranked rows with votes, the Home section and See all"
```

---

### Task 19: The post screen

**Files:**
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostViewModel.kt`
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostScreen.kt`
- Test: `feature/community/src/test/kotlin/com/stash/feature/community/CommunityPostViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// feature/community/src/test/kotlin/com/stash/feature/community/CommunityPostViewModelTest.kt
package com.stash.feature.community

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.SharedMixDocument
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.SharedTrack
import com.stash.feature.community.CommunityPostViewModel.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityPostViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CommunityRepository>(relaxed = true)
    private val sharedMix = mockk<SharedMixRepository>(relaxed = true)
    private val music = mockk<MusicRepository>(relaxed = true)
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val likes = mockk<LikeCoordinator>(relaxed = true)

    private val tracks = listOf(SharedTrack("T1", "A", durationMs = 3_600_000), SharedTrack("T2", "B", durationMs = 300_000))
    private fun playlistPost(kind: String = "playlist") = CommunityPost(
        id = "AAAAAAAA", kind = kind, title = "sad boy hours", name = "Maya", count = 2,
        covers = listOf("https://i.scdn.co/image/a"), createdAt = 1, up = 3, down = 0, tracks = tracks,
    )
    private val songPost = CommunityPost(
        id = "AAAAAAAA", kind = "song", title = "garden", name = "Sam", count = 1, artist = "Death Plus",
        createdAt = 1, up = 1, down = 0, track = SharedTrack("garden", "Death Plus", youtubeId = "9Vz"),
    )

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = CommunityPostViewModel(SavedStateHandle(mapOf("postId" to "AAAAAAAA")), repo, sharedMix, music, player, likes)

    @Test fun `the post screen reads each kind`() {
        assertThat(playlistPost().headline()).isEqualTo("Playlist · 2 songs · 1 h 5 min")
        assertThat(playlistPost("mix").headline()).isEqualTo("Mix · 2 songs · 1 h 5 min")
        assertThat(songPost.headline()).isEqualTo("Death Plus")
    }

    @Test fun `a gone post says so, and a failed load can be retried`() = runTest(dispatcher) {
        coEvery { repo.open("AAAAAAAA") } returnsMany listOf(
            CommunityResult.Rejected("gone"), CommunityResult.Failed("offline"), CommunityResult.Ok(songPost),
        )
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Gone)
        vm.load(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Failed)
        vm.load(); advanceUntilIdle()
        assertThat((vm.state.value as UiState.Loaded).post).isEqualTo(songPost)
    }

    @Test fun `Play queues the frozen songs, and Save a copy saves the post as posted`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(playlistPost())
        val queued = listOf(Track(id = 1, title = "T1", artist = "A"), Track(id = 2, title = "T2", artist = "B"))
        coEvery { sharedMix.tracksFor(any()) } returns queued
        coEvery { sharedMix.saveCopy(any()) } returns 7L
        val vm = vm(); advanceUntilIdle()
        vm.play(); advanceUntilIdle()
        coVerify { player.setQueue(queued, 0, any()) }
        var saved: Long? = null
        vm.saveCopy { saved = it }; advanceUntilIdle()
        assertThat(saved).isEqualTo(7L)
        coVerify {
            sharedMix.saveCopy(SharedMixDocument(name = "sad boy hours", sharedBy = "Maya", covers = listOf("https://i.scdn.co/image/a"), tracks = tracks))
        }
    }

    @Test fun `Like saves the song and likes it`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost)
        coEvery { music.ensureTrackPersisted(any()) } returns 5L
        every { music.observeTrackById(5L) } returns flowOf(Track(id = 5, title = "garden", artist = "Death Plus"))
        val vm = vm(); advanceUntilIdle()
        vm.like(); advanceUntilIdle()
        coVerify { likes.setLiked(5L, true) }
        assertThat((vm.state.value as UiState.Loaded).liked).isTrue()
    }

    @Test fun `a vote shows at once and flips back when refused`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost)
        coEvery { repo.vote("AAAAAAAA", 1) } returns CommunityResult.Rejected("gone")
        val vm = vm(); advanceUntilIdle()
        vm.vote(1)
        assertThat((vm.state.value as UiState.Loaded).post.up).isEqualTo(2)
        advanceUntilIdle()
        val s = vm.state.value as UiState.Loaded
        assertThat(s.post).isEqualTo(songPost)
        assertThat(s.message).isEqualTo("This post is no longer available.")
    }

    @Test fun `taking your post down leaves the screen`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost.copy(mine = true))
        coEvery { repo.takeDown("AAAAAAAA") } returns CommunityResult.Ok(Unit)
        val vm = vm(); advanceUntilIdle()
        var left = false
        vm.takeDown { left = true }; advanceUntilIdle()
        assertThat(left).isTrue()
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :feature:community:testDebugUnitTest --tests "com.stash.feature.community.CommunityPostViewModelTest"`
Expected: FAIL to compile, `Unresolved reference: CommunityPostViewModel`.

- [ ] **Step 3: Write the ViewModel**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostViewModel.kt
package com.stash.feature.community

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.communityMessage
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.SharedMixDocument
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.toTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** "Playlist · 42 songs · 2 h 11 min", "Mix · 34 songs", or a song's artist (spec 2026-09-26 §3 The post screen). */
internal fun CommunityPost.headline(): String {
    if (kind == "song") return track?.artist ?: artist.orEmpty()
    val minutes = tracks.sumOf { it.durationMs ?: 0L } / 60_000
    val length = when {
        minutes <= 0 -> null
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
    return listOfNotNull(kindLabel(kind), songs(count), length).joinToString(" · ")
}

/** A playlist or mix post as the shared-mix document that Play and Save a copy already take. */
internal fun CommunityPost.toDocument() = SharedMixDocument(name = title, sharedBy = name, covers = covers, tracks = tracks)

/** One post (spec 2026-09-26 §3 The post screen). */
@HiltViewModel
class CommunityPostViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CommunityRepository,
    private val sharedMixRepository: SharedMixRepository,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val likeCoordinator: LikeCoordinator,
) : ViewModel() {
    private val postId: String = checkNotNull(savedStateHandle.get<String>("postId"))

    sealed interface UiState {
        data object Loading : UiState
        /** Taken down, removed, expired or hidden (spec §3). */
        data object Gone : UiState
        data object Failed : UiState
        data class Loaded(val post: CommunityPost, val busy: Boolean = false, val liked: Boolean = false, val message: String? = null) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var voting = false

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = repository.open(postId)) {
                is CommunityResult.Ok -> UiState.Loaded(r.value)
                is CommunityResult.Rejected -> if (r.code == "gone") UiState.Gone else UiState.Failed
                is CommunityResult.Failed -> UiState.Failed
            }
        }
    }

    /** Plays the frozen songs, as a shared mix's Play does: each is kept as a hidden stream-only row first. */
    fun play() = withPost("play") { post ->
        if (post.kind == "song") {
            songRow(post)?.let { playerRepository.setQueue(listOf(it), 0) }
        } else {
            val tracks = sharedMixRepository.tracksFor(post.toDocument())
            if (tracks.isNotEmpty()) playerRepository.setQueue(tracks, 0)
        }
    }

    fun saveCopy(onSaved: (Long) -> Unit) = withPost("save") { post -> onSaved(sharedMixRepository.saveCopy(post.toDocument())) }

    fun like() = withPost("like") { post ->
        val row = songRow(post) ?: return@withPost
        likeCoordinator.setLiked(row.id, true)
        update { it.copy(liked = true) }
    }

    fun vote(tapped: Int) {
        val s = _state.value as? UiState.Loaded ?: return
        if (s.post.mine || voting) return
        voting = true
        val before = s.post
        val value = before.nextVote(tapped)
        update { it.copy(post = it.post.withVote(value)) }
        viewModelScope.launch {
            try {
                when (val r = repository.vote(postId, value)) {
                    is CommunityResult.Ok -> update { it.copy(post = it.post.copy(up = r.value.up, down = r.value.down, myVote = r.value.myVote)) }
                    else -> update { it.copy(post = before, message = communityMessage(r)) }
                }
            } finally {
                voting = false
            }
        }
    }

    fun takeDown(onDone: () -> Unit) = withPost("take down") { _ ->
        when (val r = repository.takeDown(postId)) {
            is CommunityResult.Ok -> onDone()
            else -> update { it.copy(message = communityMessage(r)) }
        }
    }

    fun messageShown() = update { it.copy(message = null) }

    /** The song post's library row: saved like a song link's (SharedTrackViewModel), then read back. */
    private suspend fun songRow(post: CommunityPost): Track? {
        val song = post.track ?: return null
        return musicRepository.observeTrackById(musicRepository.ensureTrackPersisted(song.toTrack())).first()
    }

    /** Runs one action on the loaded post; a failure (DB, IO) becomes a message, never a crash. */
    private fun withPost(action: String, block: suspend (CommunityPost) -> Unit) {
        val s = _state.value as? UiState.Loaded ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block(s.post)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$action failed", e)
                update { it.copy(message = "Couldn't $action this. Try again.") }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    private fun update(change: (UiState.Loaded) -> UiState.Loaded) {
        (_state.value as? UiState.Loaded)?.let { _state.value = change(it) }
    }

    private companion object {
        const val TAG = "CommunityPostVM"
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: the Step 2 command.
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the screen**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostScreen.kt
package com.stash.feature.community

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.share.SharedTrack
import com.stash.feature.community.CommunityPostViewModel.UiState

/** One post (spec 2026-09-26 §3): Play, then Save a copy or Like, your vote, and the songs as posted. */
@Composable
fun CommunityPostScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: CommunityPostViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmTakeDown by remember { mutableStateOf(false) }
    MessageToast((state as? UiState.Loaded)?.message, viewModel::messageShown)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            if ((state as? UiState.Loaded)?.post?.mine == true) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Take down my post") }, onClick = { menu = false; confirmTakeDown = true })
                    }
                }
            }
        }
        when (val s = state) {
            UiState.Loading -> Centered { CircularProgressIndicator() }
            UiState.Gone -> Centered { Text("This post is no longer available", style = MaterialTheme.typography.titleMedium) }
            UiState.Failed -> Centered {
                Text("Couldn't load this post.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::load) { Text("Retry") }
            }
            is UiState.Loaded -> PostBody(s, viewModel, onOpenPlaylist)
        }
    }
    if (confirmTakeDown) {
        AlertDialog(
            onDismissRequest = { confirmTakeDown = false },
            title = { Text("Take down this post?") },
            text = { Text("It leaves Community for everyone. It still counts toward today's two posts.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTakeDown = false
                    viewModel.takeDown {
                        Toast.makeText(context, "Post taken down", Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                }) { Text("Take down") }
            },
            dismissButton = { TextButton(onClick = { confirmTakeDown = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun PostBody(s: UiState.Loaded, viewModel: CommunityPostViewModel, onOpenPlaylist: (Long) -> Unit) {
    val post = s.post
    val song = post.kind == "song"
    val now = remember { System.currentTimeMillis() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CoverArt(post.coverUrls(), Modifier.size(if (song) 220.dp else 180.dp), corner = 12.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(post.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(post.headline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(post.name.take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Posted by ${post.name} · ${ago(post.createdAt, now, long = true)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::play, enabled = !s.busy) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                if (song) {
                    OutlinedButton(onClick = viewModel::like, enabled = !s.busy && !s.liked) {
                        Icon(if (s.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (s.liked) "Liked" else "Like")
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.saveCopy(onOpenPlaylist) }, enabled = !s.busy) { Text("Save a copy") }
                }
                Spacer(Modifier.weight(1f))
                VoteControl(post, onVote = viewModel::vote, vertical = false)
            }
            if (!song) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "As posted. ${post.name}'s later changes don't show here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!song) items(post.tracks) { SongLine(it) }
    }
}

@Composable
private fun SongLine(t: SharedTrack) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArt(listOfNotNull(t.artUrl), Modifier.size(40.dp), corner = 4.dp)
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

- [ ] **Step 6: Compile and run the module's tests**

Run: `./gradlew :feature:community:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all PASS.

- [ ] **Step 7: Commit**

```bash
git add feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostViewModel.kt feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostScreen.kt feature/community/src/test/kotlin/com/stash/feature/community/CommunityPostViewModelTest.kt
git commit -m "feat(community): the post screen: play, save a copy or like, vote, take down"
```

---

### Task 20: "Post to Community" in the song menus, Now Playing and the Share sheet

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/PostToCommunity.kt`
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt`
- Modify (one `onDismiss` line each): `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt`, `ArtistDetailScreen.kt`, `LibraryScreen.kt` (two sites), `LikedSongsDetailScreen.kt`, `PlaylistDetailScreen.kt`; `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/QueueBottomSheet.kt`
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/share/ShareMixSheet.kt`

No unit test: this task only adds rows that render when a CompositionLocal is set (see the Phase B note). The compiler checks the wiring (`onDismiss` is a required parameter, so a missed call site fails the build); Task 25 checks every entry on the phones.

- [ ] **Step 1: The CompositionLocal**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/PostToCommunity.kt
package com.stash.core.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.stash.core.model.community.PostTarget

/**
 * Opens Stash Community's confirm sheet for a song or playlist (spec 2026-09-26 §3). The app provides it only
 * while Community is on, so every "Post to Community" entry hides while it's null.
 */
val LocalPostToCommunity = compositionLocalOf<((PostTarget) -> Unit)?> { null }
```

- [ ] **Step 2: The song menu's row**

In `TrackOptionsSheet.kt`:
- Add the parameter `onDismiss: () -> Unit,` right after `onSaveToPlaylist: (Track) -> Unit,`, and to the KDoc: `@param onDismiss Closes the sheet. Each call site owns its ModalBottomSheet, so a row that hands off to another sheet (Post to Community) calls this first.`
- Add imports `androidx.compose.material.icons.filled.Public` and `com.stash.core.model.community.PostTarget`.
- After the `// -- Share option --` block, add:

```kotlin
        // -- Post to Community (only while Community is on) --
        LocalPostToCommunity.current?.let { post ->
            SheetOptionRow(
                icon = Icons.Default.Public,
                label = "Post to Community",
                onClick = { onDismiss(); post(PostTarget.Song(track)) },
            )
        }
```

- [ ] **Step 3: Pass `onDismiss` at the seven call sites**

Each closes its own sheet the way its other rows do:
- `AlbumDetailScreen.kt`, `ArtistDetailScreen.kt`, `LikedSongsDetailScreen.kt`, `PlaylistDetailScreen.kt`, and both `LibraryScreen.kt` sites: add `onDismiss = { selectedTrack = null },` after the `onSaveToPlaylist = …` argument.
- `QueueBottomSheet.kt`: add `onDismiss = { menuTrack = null },` after the `onSaveToPlaylist = …` argument.

Then check none was missed: `grep -rn "TrackOptionsSheet(" --include=*.kt feature core | grep -v "fun TrackOptionsSheet"` lists 7 sites, and each has an `onDismiss =` within its argument list.

- [ ] **Step 4: Now Playing's options sheet**

In `NowPlayingScreen.kt`:
- Add `onPostToCommunity: (() -> Unit)?,` to `NowPlayingOptionsSheet`'s parameters after `onShareClick: () -> Unit,`, and `import androidx.compose.material.icons.filled.Public`.
- Inside the sheet, after the "Share song" row and before its following `Spacer`:

```kotlin
            // Post to Community (only while Community is on)
            if (onPostToCommunity != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SheetOptionRow(
                    icon = Icons.Default.Public,
                    label = "Post to Community",
                    onClick = { onPostToCommunity(); onDismiss() },
                )
            }
```

- At the call site (`if (showOptionsSheet && track != null) {`), read the local first and pass it on. `track` is the player's slim copy; the repository re-reads its library row before posting (Task 15).

```kotlin
    if (showOptionsSheet && track != null) {
        val postToCommunity = com.stash.core.ui.components.LocalPostToCommunity.current
        NowPlayingOptionsSheet(
            isDownloaded = track.isDownloaded,
            onSaveClick = { showSaveSheet = true },
            onDownloadTap = viewModel::toggleDownloadForCurrentTrack,
            onShareClick = viewModel::onShareCurrent,
            onPostToCommunity = postToCommunity?.let { post -> { post(com.stash.core.model.community.PostTarget.Song(track)) } },
            // … the remaining arguments unchanged
```

- [ ] **Step 5: The playlist Share sheet, in both states**

In `ShareMixSheet.kt`, add imports `com.stash.core.model.community.PostTarget` and `com.stash.core.ui.components.LocalPostToCommunity`. After `val context = LocalContext.current` add:

```kotlin
    // Stash Community (spec 2026-09-26 §3): offered in both states while Community is on.
    val postToCommunity = LocalPostToCommunity.current
```

In the `NotShared` branch, after the "Create link" `Button`:

```kotlin
                    postToCommunity?.let { post ->
                        OutlinedButton(onClick = { onDismiss(); post(PostTarget.Playlist(playlistId)) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Post to Community")
                        }
                    }
```

In the `Shared` branch, right after the `Row` holding "Share again" and "Copy link":

```kotlin
                    postToCommunity?.let { post ->
                        OutlinedButton(onClick = { onDismiss(); post(PostTarget.Playlist(playlistId)) }) { Text("Post to Community") }
                    }
```

- [ ] **Step 6: Compile every touched module and run their tests**

Run: `./gradlew :core:ui:testDebugUnitTest :feature:library:testDebugUnitTest :feature:nowplaying:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, all PASS.

- [ ] **Step 7: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/PostToCommunity.kt core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/share/ShareMixSheet.kt feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/QueueBottomSheet.kt feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "feat(community): Post to Community in the song menus, Now Playing and the Share sheet"
```

---

### Task 21: Posting: the picker, the confirm sheet, and the host

**Files:**
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/CommunityPicker.kt`
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/PostConfirm.kt`
- Create: `feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostingHost.kt`
- Test: `feature/community/src/test/kotlin/com/stash/feature/community/PostComposerViewModelTest.kt`
- Test: `feature/community/src/test/kotlin/com/stash/feature/community/CommunityPickerViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// feature/community/src/test/kotlin/com/stash/feature/community/PostComposerViewModelTest.kt
package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.Draft
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.PostTarget
import com.stash.core.model.share.SharedTrack
import com.stash.feature.community.PostComposerViewModel.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostComposerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CommunityRepository>(relaxed = true)
    private val target = PostTarget.Playlist(1L)
    private val draft = Draft.Ready("playlist", "sad boy hours", emptyList(), List(42) { SharedTrack("T$it", "A") })

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.draft(target) } returns draft
        coEvery { repo.displayName() } returns "Maya"
        coEvery { repo.me() } returns CommunityResult.Ok(CommunityMe(postsLeftToday = 2, spotsFree = 4, blocked = false))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.opened() = PostComposerViewModel(repo).also { it.open(target); advanceUntilIdle() }

    @Test fun `opening reads the draft, your saved name and your limits`() = runTest(dispatcher) {
        assertThat(opened().state.value).isEqualTo(UiState.Ready(draft, "Maya", CommunityMe(2, 4, false)))
    }

    @Test fun `an empty playlist can't be posted`() = runTest(dispatcher) {
        coEvery { repo.draft(target) } returns Draft.Problem("This playlist is empty.")
        assertThat(opened().state.value).isEqualTo(UiState.Problem("This playlist is empty."))
    }

    @Test fun `a blank name asks for one and sends nothing`() = runTest(dispatcher) {
        val vm = opened()
        vm.post("  ") {}
        advanceUntilIdle()
        assertThat((vm.state.value as UiState.Ready).error).isEqualTo("Add the name to show on your post.")
        coVerify(exactly = 0) { repo.post(any(), any()) }
    }

    @Test fun `a post goes out under the given name and closes the sheet`() = runTest(dispatcher) {
        coEvery { repo.post(draft, "Maya") } returns CommunityResult.Ok("AAAAAAAA")
        val vm = opened()
        var closed = false
        vm.post("Maya") { closed = true }
        advanceUntilIdle()
        assertThat(closed).isTrue()
    }

    @Test fun `a refusal stays on the sheet with its reason, and live_limit offers your posts`() = runTest(dispatcher) {
        val vm = opened()
        coEvery { repo.post(any(), any()) } returns CommunityResult.Rejected("live_limit")
        vm.post("Maya") {}; advanceUntilIdle()
        (vm.state.value as UiState.Ready).let {
            assertThat(it.error).isEqualTo("You have 5 posts up. Take one down to post again.")
            assertThat(it.seeMyPosts).isTrue()
            assertThat(it.posting).isFalse()
        }
        coEvery { repo.post(any(), any()) } returns CommunityResult.Rejected("daily_limit")
        vm.post("Maya") {}; advanceUntilIdle()
        (vm.state.value as UiState.Ready).let {
            assertThat(it.error).isEqualTo("You've posted twice today. Try again tomorrow.")
            assertThat(it.seeMyPosts).isFalse()
        }
    }
}
```

```kotlin
// feature/community/src/test/kotlin/com/stash/feature/community/CommunityPickerViewModelTest.kt
package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val music = mockk<MusicRepository>(relaxed = true)
    private val repo = mockk<CommunityRepository>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun playlist(id: Long, name: String, type: PlaylistType, count: Int) =
        Playlist(id = id, name = name, source = MusicSource.BOTH, type = type, trackCount = count)

    @Test fun `the picker lists your playlists and mixes, then your recent songs`() = runTest(dispatcher) {
        every { music.getAllPlaylists() } returns flowOf(listOf(
            playlist(1, "sad boy hours", PlaylistType.CUSTOM, 42),
            playlist(2, "Downloads", PlaylistType.DOWNLOADS_MIX, 10),
            playlist(3, "Empty", PlaylistType.CUSTOM, 0),
            playlist(4, "Daily Discovery", PlaylistType.STASH_MIX, 34),
        ))
        coEvery { repo.recentSongs() } returns listOf(Track(id = 9, title = "garden", artist = "Death Plus"))
        val vm = CommunityPickerViewModel(music, repo)
        vm.load(); advanceUntilIdle()
        assertThat(vm.state.value.playlists.map { it.name }).containsExactly("sad boy hours", "Daily Discovery").inOrder()
        assertThat(vm.state.value.songs.map { it.title }).containsExactly("garden")
        assertThat(vm.state.value.loading).isFalse()
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :feature:community:testDebugUnitTest --tests "com.stash.feature.community.PostComposerViewModelTest" --tests "com.stash.feature.community.CommunityPickerViewModelTest"`
Expected: FAIL to compile, `Unresolved reference: PostComposerViewModel`.

- [ ] **Step 3: Write the picker**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/CommunityPicker.kt
package com.stash.feature.community

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.community.PostTarget
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class CommunityPickerViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val repository: CommunityRepository,
) : ViewModel() {
    data class UiState(val playlists: List<Playlist> = emptyList(), val songs: List<Track> = emptyList(), val loading: Boolean = true)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Your playlists and mixes (not the Downloads bucket, not empty ones), then your 20 most recently played songs. */
    fun load() {
        _state.value = UiState()
        viewModelScope.launch {
            _state.value = try {
                val playlists = musicRepository.getAllPlaylists().first()
                    .filter { it.type != PlaylistType.DOWNLOADS_MIX && it.trackCount > 0 }
                UiState(playlists, repository.recentSongs(), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CommunityPicker", "load failed", e)
                UiState(loading = false)
            }
        }
    }
}

/** `+`'s picker (spec 2026-09-26 §3 Posting): your playlists and mixes, then your recent songs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityPicker(onPick: (PostTarget) -> Unit, onDismiss: () -> Unit, viewModel: CommunityPickerViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = StashTheme.extendedColors.elevatedSurface) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("What are you digging?", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your playlists and mixes, then recent songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (state.loading) item { CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp)) }
            items(state.playlists, key = { "p${it.id}" }) { p ->
                val mix = p.type == PlaylistType.DAILY_MIX || p.type == PlaylistType.STASH_MIX
                PickRow(p.artUrl, p.name, "${if (mix) "Mix" else "Playlist"} · ${songs(p.trackCount)}") { onPick(PostTarget.Playlist(p.id)) }
            }
            items(state.songs, key = { "s${it.id}" }) { t ->
                PickRow(t.albumArtPath ?: t.albumArtUrl, t.title, "Song · ${t.artist}") { onPick(PostTarget.Song(t)) }
            }
            if (!state.loading && state.playlists.isEmpty() && state.songs.isEmpty()) {
                item {
                    Text(
                        "Nothing to post yet. Play a song or make a playlist first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickRow(art: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Your own art, local files included: nothing leaves the phone until you post.
        AsyncImage(art, null, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

- [ ] **Step 4: Write the confirm sheet**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/PostConfirm.kt
package com.stash.feature.community

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.Draft
import com.stash.core.data.community.communityMessage
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.PostTarget
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The confirm sheet's state (spec 2026-09-26 §3 Posting). One instance serves every opening; [open] resets it. */
@HiltViewModel
class PostComposerViewModel @Inject constructor(private val repository: CommunityRepository) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState

        /** It can't be posted: "This playlist is empty." and the like. */
        data class Problem(val message: String) : UiState

        /**
         * [name] is the saved "Show my name as" (blank: the sheet asks for one). [me] is null until the limits
         * arrive, or when they can't be read. [seeMyPosts] offers See all ▸ Mine after a live_limit.
         */
        data class Ready(
            val draft: Draft.Ready,
            val name: String,
            val me: CommunityMe? = null,
            val posting: Boolean = false,
            val error: String? = null,
            val seeMyPosts: Boolean = false,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var opening: Job? = null

    /** Called each time the sheet opens. */
    fun open(target: PostTarget) {
        opening?.cancel()
        _state.value = UiState.Loading
        opening = viewModelScope.launch {
            try {
                when (val draft = repository.draft(target)) {
                    is Draft.Problem -> _state.value = UiState.Problem(draft.message)
                    is Draft.Ready -> {
                        _state.value = UiState.Ready(draft, repository.displayName().orEmpty())
                        val me = repository.me()
                        if (me is CommunityResult.Ok) update { it.copy(me = me.value) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "draft failed", e)
                _state.value = UiState.Problem("Something went wrong. Try again.")
            }
        }
    }

    /** Posts under [name]. [onPosted] closes the sheet; a refusal stays on it with the reason. */
    fun post(name: String, onPosted: () -> Unit) {
        val s = _state.value as? UiState.Ready ?: return
        if (s.posting) return
        if (name.isBlank()) {
            _state.value = s.copy(error = "Add the name to show on your post.")
            return
        }
        _state.value = s.copy(posting = true, error = null, seeMyPosts = false)
        viewModelScope.launch {
            val r = try {
                repository.post(s.draft, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "post failed", e)
                CommunityResult.Failed(e.message)
            }
            if (r is CommunityResult.Ok) {
                onPosted()
            } else {
                update { it.copy(posting = false, error = communityMessage(r), seeMyPosts = (r as? CommunityResult.Rejected)?.code == "live_limit") }
            }
        }
    }

    private fun update(change: (UiState.Ready) -> UiState.Ready) {
        (_state.value as? UiState.Ready)?.let { _state.value = change(it) }
    }

    private companion object {
        const val TAG = "CommunityPost"
    }
}

/** The frozen-copy note (spec §3 Posting). */
internal fun frozenNote(draft: Draft.Ready): String =
    if (draft.kind == "song") {
        "Everyone with Community turned on will see this song. It stays up for 30 days, and you can take it down any time."
    } else {
        "Everyone with Community turned on will see these ${songs(draft.tracks.size)} exactly as they are now. " +
            "Later changes to your ${if (draft.kind == "mix") "mix" else "playlist"} won't show. " +
            "It stays up for 30 days, and you can take it down any time."
    }

/** The one confirm sheet every way in leads to (spec §3 Posting). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostConfirmSheet(
    target: PostTarget,
    onDismiss: () -> Unit,
    onSeeMyPosts: () -> Unit,
    viewModel: PostComposerViewModel = hiltViewModel(),
) {
    LaunchedEffect(target) { viewModel.open(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = StashTheme.extendedColors.elevatedSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Post to Community", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            when (val s = state) {
                PostComposerViewModel.UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                is PostComposerViewModel.UiState.Problem -> {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("OK") }
                }
                is PostComposerViewModel.UiState.Ready -> ReadyContent(
                    s,
                    onPost = { name ->
                        viewModel.post(name) {
                            Toast.makeText(context, "Posted to Community", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                    onDismiss = onDismiss,
                    onSeeMyPosts = onSeeMyPosts,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    s: PostComposerViewModel.UiState.Ready,
    onPost: (String) -> Unit,
    onDismiss: () -> Unit,
    onSeeMyPosts: () -> Unit,
) {
    val draft = s.draft
    val askName = s.name.isBlank()
    var name by rememberSaveable(draft.title) { mutableStateOf(s.name) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CoverArt(draft.covers, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(draft.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val what = if (draft.kind == "song") draft.tracks.single().artist else songs(draft.tracks.size)
            Text(
                listOfNotNull(kindLabel(draft.kind), what, s.name.takeUnless { askName }?.let { "posting as $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (askName) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Show my name as") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(frozenNote(draft), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    s.me?.let { me ->
        Spacer(Modifier.height(8.dp))
        if (me.blocked) {
            Text("You can't post to Community.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } else {
            Text(
                "${me.postsLeftToday} of 2 posts left today · ${me.spotsFree} of 5 spots free",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    s.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (s.seeMyPosts) TextButton(onClick = onSeeMyPosts) { Text("See my posts") }
    Spacer(Modifier.height(16.dp))
    Button(onClick = { onPost(name) }, enabled = !s.posting && s.me?.blocked != true, modifier = Modifier.fillMaxWidth()) {
        Text(if (s.posting) "Posting…" else "Post")
    }
    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}
```

- [ ] **Step 5: Write the host**

```kotlin
// feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostingHost.kt
package com.stash.feature.community

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.model.community.PostTarget
import com.stash.core.ui.components.LocalPostToCommunity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Which posting sheet is open (spec 2026-09-26 §3 Posting). The app's nav host holds one for every screen. */
@Stable
class CommunityPosting {
    internal var picking by mutableStateOf(false)
    internal var target by mutableStateOf<PostTarget?>(null)

    /** Opens the confirm sheet for a song or playlist: what LocalPostToCommunity hands out. */
    val post: (PostTarget) -> Unit = { picking = false; target = it }

    /** The section's `+` and See all's `+ Post`: opens the picker. */
    val pick: () -> Unit = { target = null; picking = true }
}

@Composable
fun rememberCommunityPosting(): CommunityPosting = remember { CommunityPosting() }

/** Is Community on: all the host reads while it's off, so the app makes no Community requests (spec §3). */
@HiltViewModel
class CommunityHostViewModel @Inject constructor(homeSections: HomeSectionsPreference) : ViewModel() {
    val on: StateFlow<Boolean> = homeSections.communityOn.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}

/**
 * Provides [LocalPostToCommunity] to [content] while Community is on, and hosts the picker and the confirm
 * sheet over it. [onSeeMyPosts] opens See all ▸ Mine (the live_limit message's button).
 */
@Composable
fun CommunityPostingHost(
    posting: CommunityPosting,
    onSeeMyPosts: () -> Unit,
    viewModel: CommunityHostViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val on by viewModel.on.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalPostToCommunity provides posting.post.takeIf { on }) { content() }
    if (!on) return
    if (posting.picking) CommunityPicker(onPick = posting.post, onDismiss = { posting.picking = false })
    posting.target?.let { target ->
        PostConfirmSheet(
            target = target,
            onDismiss = { posting.target = null },
            onSeeMyPosts = { posting.target = null; onSeeMyPosts() },
        )
    }
}
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :feature:community:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all PASS (6 new).

- [ ] **Step 7: Commit**

```bash
git add feature/community/src/main/kotlin/com/stash/feature/community/CommunityPicker.kt feature/community/src/main/kotlin/com/stash/feature/community/PostConfirm.kt feature/community/src/main/kotlin/com/stash/feature/community/CommunityPostingHost.kt feature/community/src/test/kotlin/com/stash/feature/community/PostComposerViewModelTest.kt feature/community/src/test/kotlin/com/stash/feature/community/CommunityPickerViewModelTest.kt
git commit -m "feat(community): the picker, the confirm sheet and the posting host"
```

---

## Phase D: wiring, the full check, deploy, the phones, the PR

### Task 22: Wiring it into the app

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`

- [ ] **Step 1: The dependency and the routes**

In `app/build.gradle.kts`, add `implementation(project(":feature:community"))` after `implementation(project(":feature:search"))`.

In `TopLevelDestination.kt`, after `ListenJoinRoute`:

```kotlin
/** Community's See all (spec 2026-09-26 §3); [mine] opens it on Mine (after a live_limit). */
@Serializable data class CommunityRoute(val mine: Boolean = false)
@Serializable data class CommunityPostRoute(val postId: String)
```

- [ ] **Step 2: Host the posting sheets over every screen**

Feature modules don't depend on each other, so the app connects them (spec §3 Wiring). In `StashNavHost.kt`, rename the existing function to a private `StashNavGraph` that takes the posting state, and put a public `StashNavHost` with the same signature in front of it. The `NavHost(...)` body keeps its indentation:

```kotlin
@Composable
fun StashNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    // … the existing two parameters with their comments, unchanged
    onSelectionModeChanged: (Boolean) -> Unit = {},
    onNavigateToTab: (TopLevelDestination) -> Unit = {},
) {
    // Stash Community (spec 2026-09-26 §3): LocalPostToCommunity and the posting sheets sit over every screen.
    val posting = rememberCommunityPosting()
    CommunityPostingHost(posting, onSeeMyPosts = { navController.navigate(CommunityRoute(mine = true)) }) {
        StashNavGraph(navController, modifier, onSelectionModeChanged, onNavigateToTab, posting)
    }
}

@Composable
private fun StashNavGraph(
    navController: NavHostController,
    modifier: Modifier,
    onSelectionModeChanged: (Boolean) -> Unit,
    onNavigateToTab: (TopLevelDestination) -> Unit,
    posting: CommunityPosting,
) {
    NavHost(
        // … unchanged
```

Add the imports `com.stash.feature.community.CommunityPostScreen`, `com.stash.feature.community.CommunityPosting`, `com.stash.feature.community.CommunityPostingHost`, `com.stash.feature.community.CommunityScreen`, `com.stash.feature.community.CommunitySection` and `com.stash.feature.community.rememberCommunityPosting`.

- [ ] **Step 3: Fill Home's slot, and add the two routes**

In the `composable<HomeRoute>` block, add to `HomeScreen(...)` after `onReportIssue = …`:

```kotlin
                communitySection = {
                    CommunitySection(
                        onOpenPost = { id -> navController.navigate(CommunityPostRoute(id)) },
                        onSeeAll = { navController.navigate(CommunityRoute()) },
                        onPost = posting.pick,
                    )
                },
```

After the `composable<SharedTrackRoute>` block:

```kotlin
        composable<CommunityRoute> {
            CommunityScreen(
                mine = it.toRoute<CommunityRoute>().mine,
                onBack = { navController.popBackStack() },
                onOpenPost = { id -> navController.navigate(CommunityPostRoute(id)) },
                onPost = posting.pick,
            )
        }

        composable<CommunityPostRoute> {
            // Save a copy opens the new playlist; Back returns to the post.
            CommunityPostScreen(
                onBack = { navController.popBackStack() },
                onOpenPlaylist = { id -> navController.navigate(PlaylistDetailRoute(id)) },
            )
        }
```

- [ ] **Step 4: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(community): wire the section, See all, the post screen and the posting sheets into the app"
```

---

### Task 23: The full check, and a first install

- [ ] **Step 1: Every unit test, and the Worker's**

Run: `./gradlew testDebugUnitTest --continue --max-workers=3`
Expected: BUILD SUCCESSFUL, zero failures (master's baseline is green, so any red test is this branch's). If `:core:media`'s suite hangs, rerun that module alone before calling it: it has a known flaky hang.

Run (in `infra/share-worker`): `node --no-warnings --test test/*.test.js`
Expected: all PASS.

- [ ] **Step 2: Install on the Pixel 5**

The Pixel 5 test rig is `192.168.137.230:5555` (find it with `arp -a` if the address moved); always pass `-s`, since two phones may be connected.

Run: `./gradlew :app:assembleDebug`, then `adb -s 192.168.137.230:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`. Check the APK's modified time is from this build before trusting anything seen on the phone.

- [ ] **Step 3: Off by default, and the failure path**

On the Pixel 5, without touching Settings: Home shows no Community section, a song's ⋮ and a playlist's Share sheet show no "Post to Community", and `adb -s 192.168.137.230:5555 logcat -d | grep -c "v1/community"` prints `0` after browsing Home, Library and Now Playing.

Then turn it on (Settings ▸ Appearance ▸ Home layout ▸ Community). It moves to the top of the list, and Home shows it under the Discover hero reading "Couldn't load Community" with Retry: the Worker has no Community routes yet, so this checks the failure path.

---

### Task 24: Deploy the Worker (ask the owner first)

This changes the live Worker that shared mixes and Listen Together use. **Ask the owner for a go before Step 1**, and stop if anything in Step 6 differs.

Every command runs in `infra/share-worker`. `$SCRATCH` is the session scratchpad directory.

- [ ] **Step 1: Record every shared mix as it answers today**

```bash
npx wrangler kv key list --binding SHARE_KV --remote --prefix "mix:" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).map(k=>k.name.slice(4)).join("\n")))' > "$SCRATCH/mix-ids.txt"
wc -l < "$SCRATCH/mix-ids.txt"
for id in $(cat "$SCRATCH/mix-ids.txt"); do echo "$id $(curl -s -o /dev/null -w '%{http_code}' https://stash-share.rawnaldclark.workers.dev/v1/mixes/$id) $(curl -s https://stash-share.rawnaldclark.workers.dev/v1/mixes/$id | sha256sum | cut -c1-16)"; done > "$SCRATCH/mixes-before.txt"
cat "$SCRATCH/mixes-before.txt"
```

Expected: 8 ids, each with its status and a hash.

- [ ] **Step 2: Create the database and bind it**

Run: `npx wrangler d1 create stash-community`
It prints a `database_id`. Put it in `wrangler.toml` in place of `SET-BY-TASK-24` (a D1 id isn't a secret).

- [ ] **Step 3: Apply the migration**

Run: `npx wrangler d1 migrations apply stash-community --remote`
Expected: `0001_community.sql` applied.

- [ ] **Step 4: Set the salt**

Run: `node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))" | npx wrangler secret put COMMUNITY_SALT`
Expected: the secret is uploaded. Nobody needs to know its value; changing it later only resets the per-network caps.

- [ ] **Step 5: Deploy**

Run: `npx wrangler deploy`
Expected: the upload lists the `COMMUNITY_DB` binding, the three rate limits and the `17 4 * * *` trigger.

- [ ] **Step 6: The mixes answer exactly as before, and Community answers**

```bash
for id in $(cat "$SCRATCH/mix-ids.txt"); do echo "$id $(curl -s -o /dev/null -w '%{http_code}' https://stash-share.rawnaldclark.workers.dev/v1/mixes/$id) $(curl -s https://stash-share.rawnaldclark.workers.dev/v1/mixes/$id | sha256sum | cut -c1-16)"; done > "$SCRATCH/mixes-after.txt"
diff "$SCRATCH/mixes-before.txt" "$SCRATCH/mixes-after.txt" && echo "mixes unchanged"
curl -s https://stash-share.rawnaldclark.workers.dev/v1/community/feed
npm run community -- list 5
```

Expected: `mixes unchanged`; `{"posts":[]}`; the CLI prints two empty result sets.

- [ ] **Step 7: Commit the database id**

```bash
git add infra/share-worker/wrangler.toml
git commit -m "chore(community): bind the live stash-community database"
```

---

### Task 25: The two phones

The Pixel 5 is the test rig. The Pixel 6 is the owner's daily phone: use it only once the owner says it's free, check `adb -s <pixel6> shell dumpsys window | grep mCurrentFocus` before every tap, and stop if they're using it. A PC script is the third voter.

- [ ] **Step 1: The PC voter**

Save as `$SCRATCH/voter.mjs`. Each key file is one phone-like identity; the PC's Wi-Fi is the same network as the Pixel 5's.

```js
// node voter.mjs <postId> <1|-1|0> [keyFile]
import { randomBytes } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const [, , id, value, file = "pc-voter.key"] = process.argv;
const key = existsSync(file) ? readFileSync(file, "utf8").trim() : randomBytes(32).toString("base64url");
writeFileSync(file, key);
const r = await fetch(`https://stash-share.rawnaldclark.workers.dev/v1/community/posts/${id}/vote`, {
    method: "PUT",
    headers: { "content-type": "application/json", "X-Stash-Community-Key": key },
    body: JSON.stringify({ value: Number(value) }),
});
console.log(r.status, await r.text());
```

- [ ] **Step 2: Every way in, on the Pixel 5** (Community on since Task 23; both phones on the new build)

The daily limit allows two posts, so every way in is opened and cancelled, and only two are posted.

1. Each of these opens the confirm sheet for the right item, and the sheet it came from closes as it opens. Cancel each one:
   - a playlist that isn't shared yet: its Share icon ▸ "Post to Community", under "Create link";
   - a playlist that already has a link: "Post to Community", under "Share again" and "Copy link";
   - a song's ⋮ in Library, and in a playlist;
   - Now Playing ▸ options ▸ "Post to Community";
   - a queue row's ⋮ ▸ "Post to Community";
   - Community's `+`: the picker lists playlists and mixes, then recent songs; picking one opens the sheet.
2. The confirm sheet shows "Playlist · N songs · posting as …" (or "Song · artist · posting as …"), the frozen-copy note and "2 of 2 posts left today · 5 of 5 spots free".
3. Post a playlist from its Share sheet and a song from Now Playing: each says "Posted to Community" and appears on Home tagged YOU.
4. A third post says "You've posted twice today. Try again tomorrow." and the sheet stays open.

- [ ] **Step 3: The other phone and the votes** (Pixel 6, once free)

1. Turn Community on; the Pixel 5's posts are listed. Vote ▲ on one: the highlight and the score change at once. Tap ▲ again: the vote is taken back.
2. On the Pixel 5, Home shows the new score once it reloads: each time Home opens, at most every 30 seconds unless this phone voted. Its own posts' arrows don't respond.
3. Open a playlist post: Play plays it; Save a copy lands on a new playlist in the Library. Open a song post: Play, then Like (it shows Liked).

- [ ] **Step 4: The live limit on a phone still under its daily limit**

Post once from the Pixel 6, note its id (`npm run community -- list 5`), then seed four older copies of it:

```bash
npx wrangler d1 execute stash-community --remote --command "INSERT INTO posts (id, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at, expires_at) SELECT 'SEEDPST' || n.x, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at - 90000000, expires_at FROM posts, (SELECT 1 AS x UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) AS n WHERE id = '<its id>'"
```

The Pixel 6's next post gets "You have 5 posts up. Take one down to post again." with See my posts, which opens See all ▸ Mine listing 5. Afterwards: `npm run community -- remove SEEDPST1` (and 2, 3, 4).

- [ ] **Step 5: Take down, hide, block, restore**

1. On the Pixel 5, open one of its posts ▸ ⋮ ▸ Take down my post ▸ Take down: "Post taken down", and it's gone from both phones' lists.
2. Hide one of the Pixel 5's posts at −3: `node voter.mjs <id> -1 a.key` and `node voter.mjs <id> -1 b.key` from the PC (one network: 2 counted), then ▼ from the Pixel 6 on mobile data (Wi-Fi off). The post leaves the list; the Pixel 6 opening it reads "This post is no longer available"; the Pixel 5 still sees it in Mine as "Hidden by votes". Without mobile data, check the same with `npm run community -- remove <id>` instead.
3. `npm run community -- restore <id>`: it's back on the list.
4. `npm run community -- block <id of a Pixel 6 post>`: the Pixel 6's confirm sheet now says "You can't post to Community." Then `npm run community -- unblock <prefix from list>`.

- [ ] **Step 6: Off leaves no trace**

On the Pixel 5, turn Community off. No section on Home; no "Post to Community" in a song's ⋮, Now Playing's options or a playlist's Share sheet; and `adb -s 192.168.137.230:5555 logcat -c`, browsing Home, Library and Now Playing, then `adb -s 192.168.137.230:5555 logcat -d | grep -c "v1/community"` prints `0`.

- [ ] **Step 7: Leave the live database clean**

Remove the test posts (`npm run community -- list 20`, then `remove` each), so the first real listeners start with an empty list.

---

### Task 26: The PR

- [ ] **Step 1: Push and open it**

```bash
git push -u origin feat/community
gh pr create --base master --head feat/community --title "Stash Community: post playlists and songs, vote, opt-in Home section"
```

The body covers, in plain words: what Community is (the spec's opening paragraph), that it's off until turned on in Home layout, what the Worker stores (key hash, salted network hash, posts, votes) and the limits, the moderation commands, what was checked on the two phones (Task 25), and that the Worker is already deployed. It ends with the line `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

The owner merges.

