# stash-share

Stores shared Stash mixes and songs and serves their links. See `docs/superpowers/specs/2026-09-23-shared-mixes-design.md`.

- Hosts: `https://stashfm.app` (canonical since 2026-10-03, route `stashfm.app/*`) and `https://stash-share.rawnaldclark.workers.dev` (kept forever: `workers_dev = true`). Every URL in a response is built from the request's own origin, so each host answers with its own links and older app versions keep getting workers.dev links. `assetlinks.json` is served on both. `GET /` on stashfm.app is a placeholder landing page; on workers.dev it stays a JSON 404.
- KV `SHARE_KV`: `mix:<id>` → `{ doc, keyHash, deleted }`. Deleted mixes keep a `{deleted:true}` tombstone for 180 days.
- Rate limits: `CREATE_RL` (5/min per IP), `WRITE_RL` (30/min per IP), `TRACK_RL` (30/min per IP), `ART_RL` (60 album-art lookups/min per IP; over it a song page uses hqdefault or no image).
- Routes: `POST /v1/mixes`, `PUT|DELETE|GET /v1/mixes/{id}`, `GET /v1/mixes/{id}/version`, `GET /m/{id}`, `GET /t` (legacy long song link), `POST /v1/tracks`, `GET /v1/tracks/{id}`, `GET /t/{id}`, `GET /.well-known/assetlinks.json`. Pages (`/m/`, `/t`, `/t/`, `/l/`) also accept one trailing slash.
- "Open in Stash" always opens the workers.dev form of the link (a short link's long `/t?…` form), the one every app version's App Links filter knows; the browser fallback is the page's own URL.
- Short song links (`src/tracks.js`): `POST /v1/tracks` takes `{ t, a, al?, d?, isrc?, sp?, yt?, art? }` with a mix track's limits (`art` only from a cover host in `COVER_HOSTS`, else dropped) and answers `{ id, url }`: 201 when new, 200 when that song was shared before. The id is derived from the song (base62 SHA-256 of the NFC-normalized, trimmed, lowercased title, artist, album, ISRC, Spotify id and YouTube id, plus the sharer's `art` URL exactly as stored), so one song with one cover always gets one link; if another song already holds those 8 characters, the next 8 are used, and when all five are taken the answer is a plain 503. A stored link never changes on a later share: the art is part of the identity so nobody can change the cover of a link they didn't make. KV `t:<id>` → `{ v: 1, track, createdAt }`, plus the server's own `found` (art a lookup found) and `artTriedAt` (a lookup found nothing), kept forever. `GET /v1/tracks/{id}` → `{ track }` with `art` being the sharer's, else the found art (cacheable 5 min), or 404.
- Song page previews (`src/art.js`): the og:image is the sharer's art, else Spotify oEmbed (moved to `i.scdn.co` at 640 px), else Deezer by ISRC, else YouTube's `hqdefault`, else none, with og:image:width/height when the size is known. Spotify and Deezer are asked at once (Spotify wins), each giving up after 2 s, and never fail the page. A short link's lookup result is written back (found art, or a miss that isn't retried for 6 h); a legacy `/t?…` link's is cached in the Cache API, a find for a day and a miss for 6 h (the Cache API does nothing on workers.dev, so there every view looks up again, within `ART_RL`). Cover URLs are stored and shown in their parsed form (`new URL(…).href`); ones with credentials or a port are dropped.
- Listen Together rooms (spec `docs/superpowers/specs/2026-09-24-listen-together-design.md`): Durable Object `ListenRoom`, bound as `ROOMS`, one per room code (`idFromName(code)`). `src/room.js` is the pure logic; `src/listen-room.js` wraps it (storage key `room`, one alarm, WebSocket Hibernation). Routes: `POST /v1/rooms` (`ROOM_RL`, 5/min per IP), `GET /v1/rooms/{code}`, `GET /v1/rooms/{code}/ws` (`?r=1` = rejoining with a resume token), `GET /l/{code}`. Codes are 8 characters, and the three `{code}` routes share `JOIN_RL` (60/min per IP, checked before the Durable Object is reached) so live rooms can't be found by guessing codes. A room keeps display names, song descriptors and the host key's SHA-256; everything is deleted when it closes (5 min after the last member leaves, or 12 h after creation).
- Stash Community (spec `docs/superpowers/specs/2026-09-26-stash-community-design.md`): D1 database `stash-community`, bound as `COMMUNITY_DB`, schema in `migrations/`. `src/community-post.js` is the pure part (cleaning a post, summarising a row); `src/community.js` holds the routes and all the Worker's SQL. Routes: `GET /v1/community/feed`, `GET /v1/community/mine`, `GET /v1/community/me`, `POST /v1/community/posts`, `GET|DELETE /v1/community/posts/{id}`, `PUT /v1/community/posts/{id}/vote`. A phone is identified by `X-Stash-Community-Key` (only its SHA-256 is stored); a network is a salted hash (`COMMUNITY_SALT` secret) of the IPv4 address or IPv6 /56. Limits: 2 posts a day and 5 live per phone, 10 a day per network, 2 counted votes per network per post each way; `COMMUNITY_WRITE_RL` 10/min, `COMMUNITY_VOTE_RL` 60/min, `COMMUNITY_READ_RL` 120/min, each per network. A daily cron (`17 4 * * *`) deletes expired and long-removed posts.

## Deploy

```bash
cd infra/share-worker
npx wrangler kv namespace create SHARE_KV   # paste the id into wrangler.toml
npm test
npx wrangler deploy
```

The first deploy after adding Listen Together applies the `v1` Durable Object migration (`new_sqlite_classes = ["ListenRoom"]`). Migrations are one-way: never rename or delete `ListenRoom` without a new migration tag.

The first deploy with Community needs its database, schema and salt first. Commit the `database_id` you paste: like `SHARE_KV`'s id, it isn't a secret. The salt is generated, not typed, so nobody can guess it; changing it later resets the per-network caps. Check that a shared-mix link answers the same before and after the deploy.

```bash
npx wrangler d1 create stash-community                      # paste the printed id into wrangler.toml's database_id
npx wrangler d1 migrations apply stash-community --remote
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))" | npx wrangler secret put COMMUNITY_SALT
npx wrangler deploy
```

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
```

Every command prints what it hit, and an empty result means nothing matched. For `block` and `restore`, read the last result (the blocked row, or the post's votes, `removed` and `vouched`): some of their statements print an empty result even when they change rows. `restore` never undoes a poster's own take-down: its `removed` is null when the post is up (and `vouched` is 1), and `poster` when its poster took it down and restore left it down.

- `remove` also prints nothing for a post that's already down: that's not a wrong id. If its poster took it down, `restore` leaves it down too.
- A post at −3 or below (up minus down) hides for everyone but its poster, and `list` marks it `hidden`. A vote attack buries a good post this way; `restore` undoes it and vouches for the post (`list` shows `vouched`), so votes can never hide it again. Its votes still count for ranking, and `remove` still takes it down.
- `list` shows each blocked phone's prefix, when it was blocked (`at`, in milliseconds since 1970), and a note, `post <id>`. That's where to find the prefix for `unblock` once the cleanup has deleted the phone's posts.
- `unblock` leaves the phone's posts removed. `restore` brings back any of them within a day, before the cleanup deletes them. `list` shows who removed each post (`removed`: `owner` or `poster`); the ones the poster took down themselves stay down.
- `list [n]` takes up to 200. A post buried by votes and older than the newest n can be in neither list, so `list 200` is the way to find it.

## Adding another host

Add a Worker route for it, add the host to `ShareConfig.HOSTS` in the app and to the manifest's App Links filter, and keep the old hosts working. `assetlinks.json` is served on every host automatically. Ship the route before the app release (see "stashfm.app" above for why).

## Cover hosts

`COVER_HOSTS` in `src/validate.js` and `ShareConfig.COVER_HOSTS` in the app (`ShareLinks.kt`) must list the same hosts. Deezer's (`cdn-images.dzcdn.net`, `e-cdns-images.dzcdn.net`) were added for song-link previews.
