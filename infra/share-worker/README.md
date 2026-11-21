# stash-share

Stores shared Stash mixes and songs and serves their links. See `docs/superpowers/specs/2026-09-23-shared-mixes-design.md`.

- Hosts: `https://stashfm.app` (canonical since 2026-10-03, route `stashfm.app/*`) and `https://stash-share.rawnaldclark.workers.dev` (kept forever: `workers_dev = true`). Every URL in a response is built from the request's own origin, so each host answers with its own links and older app versions keep getting workers.dev links. `assetlinks.json` is served on both. `GET /` on stashfm.app is a placeholder landing page; on workers.dev it stays a JSON 404.
- KV `SHARE_KV`: `mix:<id>` → `{ doc, keyHash, deleted }`. Deleted mixes keep a `{deleted:true}` tombstone for 180 days.
- Rate limits: `CREATE_RL` (5/min per IP), `WRITE_RL` (30/min per IP), `TRACK_RL` (30/min per IP), `ART_RL` (60 album-art lookups/min per IP; over it a song page uses hqdefault or no image). "Per IP" is per network, as Community counts it: an IPv4 address, or an IPv6 /56. On top of those, each network gets a daily budget of new records (`src/quota.js`: 50 mixes and 300 song links a UTC day, counted in D1's `share_quota`, pruned by the daily cron); over it, 429 with a `Retry-After` to the next UTC day. Re-sharing a song that already has a link doesn't count.
- Routes: `POST /v1/mixes`, `PUT|DELETE|GET /v1/mixes/{id}`, `GET /v1/mixes/{id}/version`, `GET /m/{id}`, `GET /t` (legacy long song link), `POST /v1/tracks`, `GET /v1/tracks/{id}`, `GET /t/{id}`, `GET /.well-known/assetlinks.json`. Pages (`/m/`, `/t`, `/t/`, `/l/`, `/s/`) also accept one trailing slash.
- "Open in Stash" always opens the workers.dev form of the link (a short link's long `/t?…` form), the one every app version's App Links filter knows; the browser fallback is the page's own URL.
- Short song links (`src/tracks.js`): `POST /v1/tracks` takes `{ t, a, al?, d?, isrc?, sp?, yt?, art? }` with a mix track's limits (`art` only from a cover host in `COVER_HOSTS`, else dropped) and answers `{ id, url }`: 201 when new, 200 when that song was shared before. The id is derived from the song (base62 SHA-256 of the NFC-normalized, trimmed, lowercased title, artist, album, ISRC, Spotify id and YouTube id, plus the sharer's `art` URL exactly as stored), so one song with one cover always gets one link; if another song already holds those 8 characters, the next 8 are used, and when all five are taken the answer is a plain 503. A stored link never changes on a later share: the art is part of the identity so nobody can change the cover of a link they didn't make. KV `t:<id>` → `{ v: 1, track, createdAt }`, plus the server's own `found` (art a lookup found) and `artTriedAt` (a lookup found nothing), kept forever. `GET /v1/tracks/{id}` → `{ track }` with `art` being the sharer's, else the found art (cacheable 5 min), or 404.
- Song page previews (`src/art.js`): the og:image is the sharer's art, else Spotify oEmbed (moved to `i.scdn.co` at 640 px), else Deezer by ISRC, else YouTube's `hqdefault`, else none, with og:image:width/height when the size is known. Spotify and Deezer are asked at once (Spotify wins), each giving up after 2 s, and never fail the page. A short link's lookup result is written back (found art, or a miss that isn't retried for 6 h); a legacy `/t?…` link's is cached in the Cache API, a find for a day and a miss for 6 h (the Cache API does nothing on workers.dev, so there every view looks up again, within `ART_RL`). Cover URLs are stored and shown in their parsed form (`new URL(…).href`); ones with credentials or a port are dropped.
- Listen Together rooms (spec `docs/superpowers/specs/2026-09-24-listen-together-design.md`): Durable Object `ListenRoom`, bound as `ROOMS`, one per room code (`idFromName(code)`). `src/room.js` is the pure logic; `src/listen-room.js` wraps it (storage key `room`, one alarm, WebSocket Hibernation). Routes: `POST /v1/rooms` (`ROOM_RL`, 5/min per IP), `GET /v1/rooms/{code}`, `GET /v1/rooms/{code}/ws` (`?r=1` = rejoining with a resume token), `GET /l/{code}`. Codes are 8 characters, and the three `{code}` routes share `JOIN_RL` (60/min per IP, checked before the Durable Object is reached) so live rooms can't be found by guessing codes. A room keeps display names, song descriptors and the host key's SHA-256; everything is deleted when it closes (5 min after the last member leaves, or 12 h after creation). Guests adding songs (spec `docs/superpowers/specs/2026-11-04-listen-together-guest-adds-design.md`): an app that sends `v: 2` in its hello can send `add {tracks, next}` (Play next or Add to queue; one message for a whole selection), `unqueue {track, at}` (the host any song, a guest only their own) and, as host, `settings {guestsAdd}`. Guests' adds go straight into the queue only while the host's app sent `v: 2` and its "Let guests add songs" switch is on; otherwise they become suggestions, exactly as an older app's `suggest` does (which, with the switch on, goes straight in as Play next). At most 20 of one guest's songs wait in the queue; the host only has the room's 200. The room numbers each queue change (`qv`, on every `queue` broadcast and in `state`), and a host's `load` or `queue` that carries the `qv` it was built from gets the adds and removals it hadn't seen put back on top; without `qv` it is taken as sent, as before.
- Stash Community (spec `docs/superpowers/specs/2026-09-26-stash-community-design.md`): D1 database `stash-community`, bound as `COMMUNITY_DB`, schema in `migrations/`. `src/community-post.js` is the pure part (cleaning a post, summarising a row); `src/community.js` holds the routes and all the Worker's SQL. Routes: `GET /v1/community/feed` (`?limit=` 1–100, default 5; `?sort=new` newest first, otherwise votes plus freshness), `GET /v1/community/mine`, `GET /v1/community/me`, `POST /v1/community/posts`, `GET|DELETE /v1/community/posts/{id}`, `PUT /v1/community/posts/{id}/vote`, `PUT /v1/community/posts/{id}/heard`. A phone is identified by `X-Stash-Community-Key` (only its SHA-256 is stored); a network is a salted hash (`COMMUNITY_SALT` secret) of the IPv4 address or IPv6 /56. Limits: 2 posts a day and 5 live per phone, 10 a day per network, 2 counted votes per network per post each way; `COMMUNITY_WRITE_RL` 10/min, `COMMUNITY_VOTE_RL` 60/min, `COMMUNITY_READ_RL` 120/min, each per network. A daily cron (`17 4 * * *`) deletes expired and long-removed posts, with their votes and heard marks. Heard (contract 2026-11-03): the app and web player send `PUT …/heard` (204, idempotent; 404 `gone`, 403 `own_post`, counted with `COMMUNITY_VOTE_RL`) after 30 s of playing a song from a post, and the server keeps (post, the key's hash, when), nothing else; reads with a key carry `heard` next to `myVote` (always true on your own post). With the `HEARD_REQUIRED` var at `"1"` (`[vars]` in `wrangler.toml`, `"0"` as shipped), a +1 or −1 vote without one is 403 `listen_first` and isn't stored; 0 (taking a vote back) always works.
- Sessions (spec `docs/superpowers/specs/2026-11-20-sessions-design.md`): hosts' stations, voted on like posts, in the same D1 database (`migrations/0007_sessions.sql`), with Live now, Upcoming, follows and the station page `GET /s/{id}`. Off until the `SESSIONS` var is `"1"`. See "Sessions" below.
- Proxy key (`SHARE_PROXY_KEY` secret, optional): the web player's Worker (play.stashfm.app) passes its listeners' calls on server to server, so they all arrive with the player's own `CF-Connecting-IP`. With the same secret on both Workers, the player adds `X-Stash-Proxy-Key` and the listener's `X-Stash-Client-IP`, and every per-IP limit above, the daily budgets and Community's per-network caps count that listener instead (`clientAddress` in `src/http.js`; IPv6 still by its /56). The forwarded address is used only when the key equals the secret (constant-time compare) and the address is a well-formed IPv4 or plain IPv6 address (no IPv4-in-IPv6 text such as `::ffff:203.0.113.9`, which would escape the /56 keying). A secret the player would refuse (under 32 characters, or outside `A-Za-z0-9_-.~+/=`) counts as unset. Unset, or for any other caller (a browser sending the headers itself, a wrong key): `CF-Connecting-IP`, as before. A key that doesn't match logs a count-only warning, never the key.
- Client address: `CF-Connecting-IP`. A zone rule can remove that header before any Worker runs (the "Remove visitor IP headers" Managed Transform, or a Request Header Transform Rule), and from 2026-10-07 to the fix every stashfm.app call arrived without it, so all app users counted as one network. Without it the Worker uses `X-Real-IP`, which Cloudflare's edge writes on every call (replacing a client's own) and no zone rule can change, but only on a call without `CF-Worker`, since a Worker's own `fetch()` can set it. With neither, the caller is `"?"`, logged as a count at the 1st, 10th, 100th… such call per isolate; a "CF-Connecting-IP is missing" line in `wrangler tail` means a zone rule is removing it again (`connectingAddress` in `src/http.js`).

## Deploy

```bash
cd infra/share-worker
npx wrangler kv namespace create SHARE_KV   # paste the id into wrangler.toml
npm test
npx wrangler deploy
```

The first deploy after adding Listen Together applies the `v1` Durable Object migration (`new_sqlite_classes = ["ListenRoom"]`). Migrations are one-way: never rename or delete `ListenRoom` without a new migration tag. Guest adds (2026-11-04) need no migration: the class is the same and a room's state is one JSON value, which the new code reads with defaults for the fields it adds. Rolling back to the previous version is safe too: it ignores those fields, and rooms keep `accepted` in the form it reads.

The first deploy with Community needs its database, schema and salt first. Commit the `database_id` you paste: like `SHARE_KV`'s id, it isn't a secret. The salt is generated, not typed, so nobody can guess it; changing it later resets the per-network caps. Check that a shared-mix link answers the same before and after the deploy.

```bash
npx wrangler d1 create stash-community                      # paste the printed id into wrangler.toml's database_id
npx wrangler d1 migrations apply stash-community --remote
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))" | npx wrangler secret put COMMUNITY_SALT
npx wrangler deploy
```

A later migration in `migrations/` is applied the same way, before the deploy that uses it: `npx wrangler d1 migrations apply stash-community --remote` (it applies only the new ones). `0005_posts_recent.sql` is the index for the feed's `?sort=new`; without it that order still answers, sorting every live post. `0006_heard.sql` adds the `heard` table, which the feed, the post read and votes all query: apply it before deploying the Worker that has it, or every Community read fails. `0007_sessions.sql` adds Sessions' tables (`stations`, `station_votes`, `station_heard`, `station_follows`, `live`). Nothing reads them while `SESSIONS` is `"0"`, but apply it before the deploy all the same, and always before turning `SESSIONS` on.

### Proxy key (web player)

Generated, never typed or committed, and the same value on both Workers. Order: the secret on both, then the D1 migrations, then this Worker, then the player. Either half alone changes nothing else. `wrangler secret put` makes the secret live on the Worker's current version at once: harmless while the player's Community code isn't live yet; if it is, put the player's secret after step 3 instead, so listeners' addresses never go to code that ignores them.

```bash
# in infra/share-worker
KEY=$(node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))")   # 43 characters, only in this shell
# 1. the secret on both Workers (the player on its own account)
printf %s "$KEY" | npx wrangler secret put SHARE_PROXY_KEY
(cd <stash-player checkout> && printf %s "$KEY" | CLOUDFLARE_ACCOUNT_ID=<player account> npx wrangler secret put SHARE_PROXY_KEY --env production)
unset KEY
# 2. new D1 migrations (0005: the ?sort=new index); applies only the ones not applied yet
npx wrangler d1 migrations apply stash-community --remote
# 3. this Worker
npm test && npx wrangler deploy
# 4. then the player: in the stash-player checkout, `npm run deploy`
```

Rotating it: put a new value on both Workers, this one first. In between, the player's calls fall back to one shared connection (never an error), and each refused key logs a count-only warning (`share proxy: X-Stash-Proxy-Key doesn't match …`, never the key) that `npx wrangler tail` shows. To turn it off, `npx wrangler secret delete SHARE_PROXY_KEY` on either side.

### stashfm.app (first deploy with the route)

Order matters. The app's App Links and API base move to stashfm.app, and on Android 6–11 one host that can't be verified turns App Links off for every host, so the Worker must be live on stashfm.app before an app release that uses it.

1. DNS for stashfm.app must exist and be proxied before the route serves anything: in the stashfm.app zone, add `AAAA`, name `@`, content `100::`, Proxy status Proxied (orange cloud). `100::` is the discard prefix: the Worker answers every request, so nothing reaches an origin. Wait until `https://stashfm.app` has a certificate (Universal SSL on a new zone can take a few minutes).
   - Zone settings: turn on SSL/TLS → Edge Certificates → "Always Use HTTPS".
   - Keep Bot Fight Mode, "I'm Under Attack" mode and any WAF challenge or managed-challenge rule off for this zone. The app's API calls, Listen Together's WebSockets, link-preview crawlers (Discord, X, WhatsApp, iMessage, Facebook) and Google's App Links verifier fetching `assetlinks.json` can't solve a challenge, so a challenge breaks sharing, previews or App Links.
2. Before deploying, save a mix's answers on workers.dev to compare afterwards: `curl -s https://stash-share.rawnaldclark.workers.dev/v1/mixes/<id> > before.json`.
3. `npm test`, then `npx wrangler deploy`. It creates the `stashfm.app/*` route and the `TRACK_RL` and `ART_RL` bindings, and keeps workers.dev.
4. Check:
   - `curl -s https://stashfm.app/.well-known/assetlinks.json` lists `com.stash.app` and `com.stash.app.debug`, with no redirect.
   - `curl -s -X POST https://stashfm.app/v1/tracks -H 'content-type: application/json' -d '{"t":"Never Gonna Give You Up","a":"Rick Astley","isrc":"GBARL9300135","sp":"4uLU6hMCjMI75M1A2tKUQC","yt":"dQw4w9WgXcQ"}'` answers 201 with a `https://stashfm.app/t/<id>` URL; the same command again answers 200 with the same id; `curl -s https://stashfm.app/v1/tracks/<id>` returns the song.
   - `curl -s -A 'Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)' https://stashfm.app/t/<id> | grep -E 'og:|twitter:'` (and again with `-A Twitterbot/1.0`) shows og:image on `i.scdn.co` with og:image:width 640; that proves the Spotify lookup works from Cloudflare. After that view, `GET /v1/tracks/<id>` includes the art. The page's "Open in Stash" link starts `intent://stash-share.rawnaldclark.workers.dev/t?t=`.
   - `curl -s https://stash-share.rawnaldclark.workers.dev/v1/mixes/<id> | diff before.json -` prints nothing, and the workers.dev `/m/<id>` page's og:url is still on workers.dev.
   - `curl -s https://stashfm.app/` is the landing page; `curl -s https://stash-share.rawnaldclark.workers.dev/` is still `{"error":"not_found"}`.

When the website ships, it takes `stashfm.app/*` and this Worker keeps only its own routes (see the comment in `wrangler.toml`).

## Remove a mix by hand

Write a tombstone rather than deleting the key. Followers only stop following on a 410; a bare 404 is treated as a temporary miss.

```bash
npx wrangler kv key put --binding SHARE_KV --remote --ttl 15552000 "mix:<id>" '{"deleted":true}'
```

## Take down a song link

A `t:` key holds one shared song. Deleting it makes `/t/<id>` a "Song not found" page and `GET /v1/tracks/<id>` a 404:

```bash
npx wrangler kv key delete --binding SHARE_KV --remote "t:<id>"
```

The id comes from the song and its cover URL, so sharing that exact song with that exact cover makes the same link again. A cover that must never come back needs its host (or URL) refused in `cleanCover` (`src/validate.js`).

## Community moderation

The script needs Node 24.2 or later (22.18 or later also works); older versions silently do nothing.

```bash
cd infra/share-worker
npm run community -- list 20             # newest and top posts, then blocked phones; `poster` is the first 8 characters of the poster id
npm run community -- remove <postId>
npm run community -- restore <postId>    # undo your removal, or a vote attack (drops the post's downvotes); votes can't hide it again
npm run community -- block <postId>      # blocks that phone and removes all its live posts
npm run community -- unblock <prefix>
npm run community -- drop-unheard-downvotes   # once, right after HEARD_REQUIRED goes on
```

Every command prints what it hit, and an empty result means nothing matched. For `block` and `restore`, read the last result (the blocked row, or the post's votes, `removed` and `vouched`): some of their statements print an empty result even when they change rows. `restore` never undoes a poster's own take-down: its `removed` is null when the post is up (and `vouched` is 1), and `poster` when its poster took it down and restore left it down.

- `remove` also prints nothing for a post that's already down: that's not a wrong id. If its poster took it down, `restore` leaves it down too.
- A post at −3 or below (up minus down) hides for everyone but its poster, and `list` marks it `hidden`. A vote attack buries a good post this way; `restore` undoes it and vouches for the post (`list` shows `vouched`), so votes can never hide it again. Its votes still count for ranking, and `remove` still takes it down.
- `list` shows each blocked phone's prefix, when it was blocked (`at`, in milliseconds since 1970), and a note, `post <id>`. That's where to find the prefix for `unblock` once the cleanup has deleted the phone's posts.
- `unblock` leaves the phone's posts removed. `restore` brings back any of them within a day, before the cleanup deletes them. `list` shows who removed each post (`removed`: `owner` or `poster`); the ones the poster took down themselves stay down.
- `drop-unheard-downvotes` deletes every downvote whose voter has no heard mark on that post, keeps every upvote, and recounts every post. Run it once, right after the deploy that turns `HEARD_REQUIRED` on: until then nobody can have a mark, so every older downvote goes. The first result says how many it's deleting (and from how many voters and posts), the last how many downvotes are left. With `HEARD_REQUIRED` on, running it again finds nothing new.
- `list [n]` takes up to 200. A post buried by votes and older than the newest n can be in neither list, so `list 200` is the way to find it.

## Sessions

A host's live listening becomes a station others can join (spec `docs/superpowers/specs/2026-11-20-sessions-design.md`, the room as a hangout in `2026-11-20-sessions-ux.md`; build plan `docs/superpowers/plans/2026-11-20-sessions.md`). This Worker keeps the stations, their votes, follows and heard marks, and the directory of sessions on the air (`live`, one row per station, written by the station's room); the rooms themselves are Listen Together's `ListenRoom`. `src/stations.js` is the pure part (cleaning fields, schedules, summaries), `src/stations-api.js` the routes and their SQL.

**The switch.** `SESSIONS` in `wrangler.toml`'s `[vars]`, `"0"` as shipped. Until it's `"1"`, every route below answers 503 `{"error":"sessions_off"}`, `/s/{id}` says the station isn't available, and the cleanup does nothing. None of them reads a station table then, so the Worker can go out before migration 0007 does. Turning it on is a deploy with `SESSIONS = "1"`, after 0007 is applied. Turning it off again hides everything at once and deletes nothing. Off for more than a week, every station would look idle when it comes back, and the next cron would delete them all: before switching it back on, give them a fresh start with `npx wrangler d1 execute stash-community --remote --command "UPDATE stations SET last_live_at = <now in ms> WHERE removed_at IS NULL"`.

### Contract for the app

Keyed like Community: `X-Stash-Community-Key`, the phone's 43-character key, of which only the SHA-256 is stored. A route whose key is "required" answers 401 `bad_key` without a valid one; "optional" means a missing key is fine (the answer then has no `myVote`, `mine`, `heard` or `following`) and only a malformed one is 401. Answers are JSON, reads with `cache-control: no-store`; errors are `{"error": "<code>"}`. Every route is under `/v1/community/`.

```text
Schedule  {tz: "America/New_York", windows: [{day: 1, start: "09:00", end: "17:00"}, ...]}
          tz: an IANA zone, stored as Intl spells it. At most 7 windows. day 1-7, Monday = 1; times 00:00-23:59 on the
          host's wall clock in tz; start != end, and an end before its start runs past midnight.
Summary   {id, name, hostName, vibe, up, down, followers}
          with a key, also {myVote: -1, 0 or 1, mine, heard, following}; your own station is mine and heard.
Own       Summary + {autoLive: "off", "schedule" or "always", schedule: Schedule or null, hidden, removed,
          lastLiveAt: ms or null, deleteAt: ms}
          deleteAt: from when the daily cleanup deletes it (7 days after it was last on the air, or made; a removed
          one a day after its removal).
Window    {start: ms, end: ms}: the window in progress, or else the next one.
Live      {room, track, listeners, paused, full}: room is the code to join; track is the room's song; full at 50.
Crowd     [{i, id}]: up to 4 faces, host first. i is the initial (null without a name); id is the member id.
```

| Route | Key | Answer |
|---|---|---|
| `POST stations` `{name, hostName?, vibe?, autoLive?, schedule?}` | required | 201 `{id}`. 409 `{error: "have_station", id}`: one station a phone, and a removed one counts until it's deleted. 400 `bad_request`, 403 `blocked`, 413 `too_large`. |
| `GET stations/mine` | required | `{station: Own}`, or `{station: null}` without one. |
| `GET stations/{id}` | optional | `{station, live?}`: Summary, plus `{schedule, next}` (Window, or null) when it runs on a schedule; `live` is a Live while it's on the air. 404 `gone` when missing or removed, or hidden or its phone blocked and it isn't yours. |
| `PATCH stations/{id}` with any of the create fields | required, its owner | 200 `{station: Own}`. `null` or blank text clears `hostName` and `vibe`, `null` clears `schedule`; the name can't be cleared. A bad field refuses the whole change: 400. 403 `not_yours`, 404 `gone` (also once removed). |
| `DELETE stations/{id}` | required, its owner | 204: the station goes with its votes, heard marks, follows and directory row. 403 `not_yours`, 404 `gone` (also once removed: the owner's removal stands for its day). |
| `PUT stations/{id}/heard` | required | 204, again changes nothing. The app sends it after 2 minutes in this station's sessions, in total. 403 `own_station`, 404 `gone`. |
| `PUT stations/{id}/vote` `{value}` (-1, 0 or 1) | required | `{up, down, myVote}`. +1 and -1 need the heard mark first: 403 `listen_first`; 0 always works. 403 `own_station` or `blocked`; 404 `gone`, also while it's hidden. |
| `PUT stations/{id}/follow` `{on}` (true or false) | required | `{following, followers}`. Following: 403 `own_station`, 404 `gone`, 409 `too_many` (200 a phone). Stopping always works. |
| `GET stations/following` | required | `{stations: [Summary + {live?, next?}]}`, `live` a Live with `crowd`: live ones first (most listeners first), then the soonest on the air, then by name. Hidden, removed and blocked ones are left out. |
| `GET live?limit=` (1 to 50, 10 if not given) | optional | `{sessions: [{station: Summary, room, track, listeners, paused, full, crowd, startedAt}]}`, best first: up minus down, plus one for each listener. |
| `GET upcoming?limit=` (1 to 50, 10 if not given) | optional | `{stations: [Summary + {next: Window}]}`: on a schedule, not on the air, next on within 24 hours, soonest first. |

- Lengths are counted in UTF-16 units, as Kotlin's `String.length` counts, after trimming: name 1 to 40, hostName up to 40, vibe up to 60. Unknown fields are dropped.
- Limits: `POST`, `PATCH` and `DELETE` count against `COMMUNITY_WRITE_RL`; `heard`, `vote` and `follow` against `COMMUNITY_VOTE_RL`; reads against `COMMUNITY_READ_RL`. Over one: 429 `rate_limited` with `Retry-After: 60`.
- Votes are the post rules on a station: one per phone, each network counted at most twice each way, at most 20 stored rows per network. At up minus down of -3 or below, a station is hidden from everyone but its owner until `station-restore` vouches for it. A blocked phone's station is hidden while the block lasts. Unlike posts, the heard mark is always required, whatever `HEARD_REQUIRED` says: no app ever voted on a station without sending one.
- Going live (a public room for a station, with `POST /v1/rooms`) comes with step 1b of the plan, which also writes the `live` rows. Until then nothing writes them.

### The station page and the cleanup

`GET /s/{id}` (route `stashfm.app/s/*`): the station's name, host and vibe line, then "Live now · 12 listening" with the song, or its hours ("Weekdays 9:00–17:00 (New York time)"), and **Open in Stash**, which hands the app the workers.dev form of the link, as the other pages do. The room's code isn't on the page: the app joins. A missing, removed or hidden station, or one whose phone is blocked, is a 404 "This station isn't available"; over `COMMUNITY_READ_RL` it's a 429 "Slow down".

The daily cron also deletes directory rows whose room stopped writing (over 3 minutes old; reads skip them anyway), stations not on the air for 7 days (one never live counts from when it was made), and stations you removed over a day ago (unless made or on the air within that day), each with its votes, heard marks, follows and directory row.

### Moderation

```bash
npm run community -- station-list [n]            # the latest n stations (50), then the sessions on the air
npm run community -- station-remove <stationId>  # take a station down; it leaves Live now at once
npm run community -- station-restore <stationId> # undo your removal or a vote attack (drops its downvotes); votes can't hide it again
npm run community -- station-block <stationId>   # block its phone (no going live, voting or posting) and remove its station and posts
```

They work as the post commands do (see "Community moderation"). `unblock <prefix>` undoes a block from either kind (`station-list` shows the `owner` prefix). After `station-block` and `unblock`, the station stays down until `station-restore`, within its day; a phone blocked from a post with `block` keeps its station, hidden while the block lasts. A session already on the air ends within a minute, when its room's next directory write is refused (step 1b).

## Adding another host

Add a Worker route for it, add the host to `ShareConfig.HOSTS` in the app and to the manifest's App Links filter, and keep the old hosts working. `assetlinks.json` is served on every host automatically. Ship the route before the app release (see "stashfm.app" above for why).

## Cover hosts

`COVER_HOSTS` in `src/validate.js` and `ShareConfig.COVER_HOSTS` in the app (`ShareLinks.kt`) must list the same hosts. Deezer's (`cdn-images.dzcdn.net`, `e-cdns-images.dzcdn.net`) were added for song-link previews.
