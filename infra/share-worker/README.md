# stash-share

Stores shared Stash mixes and serves their links. See `docs/superpowers/specs/2026-09-23-shared-mixes-design.md`.

- KV `SHARE_KV`: `mix:<id>` → `{ doc, keyHash, deleted }`. Deleted mixes keep a `{deleted:true}` tombstone for 180 days.
- Rate limits: `CREATE_RL` (5/min per IP), `WRITE_RL` (30/min per IP).
- Routes: `POST /v1/mixes`, `PUT|DELETE|GET /v1/mixes/{id}`, `GET /v1/mixes/{id}/version`, `GET /m/{id}`, `GET /t`, `GET /.well-known/assetlinks.json`.
- Listen Together rooms (spec `docs/superpowers/specs/2026-09-24-listen-together-design.md`): Durable Object `ListenRoom`, bound as `ROOMS`, one per room code (`idFromName(code)`). `src/room.js` is the pure logic; `src/listen-room.js` wraps it (storage key `room`, one alarm, WebSocket Hibernation). Routes: `POST /v1/rooms` (`ROOM_RL`, 5/min per IP), `GET /v1/rooms/{code}`, `GET /v1/rooms/{code}/ws` (`?r=1` = rejoining with a resume token), `GET /l/{code}`. Codes are 8 characters, and the three `{code}` routes share `JOIN_RL` (60/min per IP, checked before the Durable Object is reached) so live rooms can't be found by guessing codes. A room keeps display names, song descriptors and the host key's SHA-256; everything is deleted when it closes (5 min after the last member leaves, or 12 h after creation).
- Stash Community (spec `docs/superpowers/specs/2026-09-26-stash-community-design.md`): D1 database `stash-community`, bound as `COMMUNITY_DB`, schema in `migrations/`. `src/community-post.js` is the pure part (cleaning a post, summarising a row); `src/community.js` holds the routes and all SQL. Routes: `GET /v1/community/feed`, `GET /v1/community/mine`, `GET /v1/community/me`, `POST /v1/community/posts`, `GET|DELETE /v1/community/posts/{id}`, `PUT /v1/community/posts/{id}/vote`. A phone is identified by `X-Stash-Community-Key` (only its SHA-256 is stored); a network is a salted hash (`COMMUNITY_SALT` secret) of the IPv4 address or IPv6 /56. Limits: 2 posts a day and 5 live per phone, 10 a day per network, 2 counted votes per network per post each way; `COMMUNITY_WRITE_RL` 10/min, `COMMUNITY_VOTE_RL` 60/min, `COMMUNITY_READ_RL` 120/min, each per network. A daily cron (`17 4 * * *`) deletes expired and long-removed posts.

## Deploy

```bash
cd infra/share-worker
npx wrangler kv namespace create SHARE_KV   # paste the id into wrangler.toml
npm test
npx wrangler deploy
```

The first deploy after adding Listen Together applies the `v1` Durable Object migration (`new_sqlite_classes = ["ListenRoom"]`). Migrations are one-way: never rename or delete `ListenRoom` without a new migration tag.

## Remove a mix by hand

Write a tombstone rather than deleting the key. Followers only stop following on a 410; a bare 404 is treated as a temporary miss.

```bash
npx wrangler kv key put --binding SHARE_KV --remote --ttl 15552000 "mix:<id>" '{"deleted":true}'
```

## Community moderation

```bash
cd infra/share-worker
npm run community -- list 20             # newest and top posts, then blocked phones; `poster` is the first 8 characters of the poster id
npm run community -- remove <postId>
npm run community -- restore <postId>    # undo a removal, or a vote attack (drops the post's downvotes)
npm run community -- block <postId>      # blocks that phone and removes all its live posts
npm run community -- unblock <prefix>
```

Every command prints what it changed, and an empty result means nothing matched. For `block`, the last result (the blocked row read back) is the one that shows whether the phone is blocked.

- `list` shows each blocked phone's prefix, when it was blocked, and a note, `post <id>`. That's where to find the prefix for `unblock` once the cleanup has deleted the phone's posts.
- `unblock` leaves the phone's posts removed. `restore` any you want back within a day, before the cleanup deletes them.
- `list [n]` takes up to 200. A post buried by votes and older than the newest n can be in neither list, so `list 200` is the way to find it.

## Moving to a custom domain later

Add the domain as a Worker route or custom domain, add its host to `ShareConfig.HOSTS` in the app and to the manifest intent filter, and keep the old host working. `assetlinks.json` is served on every host automatically.
