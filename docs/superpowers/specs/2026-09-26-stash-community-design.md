# Stash Community: design

Stash listeners post playlists, mixes and songs they're into, and everyone who turns Community on votes them up or down. The best of the week rises to the top of a Home section. It is the social side of Stash that shared mixes and Listen Together started: those are for friends you invite; Community is for everyone who opts in.

Approved in brainstorming on 2026-09-26. Mockups (git-ignored): `.superpowers/brainstorm/30-1790442326/` (`community-home-layout.html`, `community-posting.html`, `community-post-detail.html`).

## 1. Decisions

| Question | Decision | Why |
|---|---|---|
| Who removes bad posts | The community first: a post at −3 or below hides for everyone. The owner is the backstop: commands to remove any post and block a phone from posting. | Posts go live at once, and nobody has to approve anything; the owner still has the last word. |
| What a post is | A **frozen copy**: the songs as they were when posted. Later changes to the poster's playlist don't show. | Votes always match what people heard. |
| Limits | Per phone: **2 posts per 24 hours, 5 live at once**, each live **30 days**. A looser per-network cap sits behind them. | Enough to share what you're into this week; nobody can flood the section. |
| Ordering | **Likes plus freshness** ("hot"): votes lift a post, and a new post gets a head start that fades. | The section keeps moving and new posts get seen. |
| Names | Posts show the poster's **"Show my name as" name** ("Maya's pick"). | The social point of the feature. |
| Home layout | **A ranked list** like Top Albums: top 5, votes on each row, `+` and See all in the header. | Most posts in the least space; votes without leaving Home. |
| Posting | From the **⋮ menu** of any playlist, mix or song, **and** a `+` button on the section that opens a picker. Both lead to one confirm sheet. | Where people already share, plus a button nobody can miss. |
| Accounts | **None.** A random key per phone identifies it; the server keeps only its hash. | Stash has no accounts. A determined person who wipes the app can vote again; for a community this size that's acceptable. |
| Storage | **D1** (Cloudflare's SQL database) bound to the existing `stash-share` Worker. | One vote per phone per post, correct counts under concurrent votes, ranking in one place, and a console for moderation. |
| Opt-in | The Home section is **off for everyone** until turned on in Settings → Home layout. While off, the app never contacts the Community routes. | "An optional feature you can enable." |

**Not in this version:** a share link for a post (needs its own web page and App Link); a "New" tab; a report button (downvotes do that job); editing a post (take it down and post again).

## 2. Server (in `infra/share-worker`)

Community lives in the same `stash-share` Worker as shared mixes and Listen Together rooms, under `/v1/community`. It adds one D1 database (`stash-community`, binding `COMMUNITY_DB`), two rate-limit bindings and one secret. Mixes (KV) and rooms (Durable Objects) are untouched.

### Identity

The first time a phone uses Community it makes a **community key**: 32 random bytes, base64url, 43 characters (the same format as a mix edit key, checked by `validEditKey`). Every request that posts, votes, takes down or asks "how many posts do I have left" sends it in the header `X-Stash-Community-Key`. The server never stores the key: it stores `sha256(key)` in hex, called the phone's **poster id** (as a voter, the **voter id**). A malformed key is rejected with 401.

Losing the key (clearing the app's data, a new phone) means a new identity: the old posts can no longer be taken down by that person and simply expire.

### Tables (`migrations/0001_community.sql`)

```sql
CREATE TABLE posts (
  id           TEXT PRIMARY KEY,               -- 8 chars [A-Za-z0-9], like a mix id
  kind         TEXT NOT NULL CHECK (kind IN ('playlist', 'mix', 'song')),
  title        TEXT NOT NULL,                  -- playlist/mix name, or the song title
  poster_name  TEXT NOT NULL,                  -- "Show my name as" at posting time
  poster       TEXT NOT NULL,                  -- sha256(key), hex
  ip_hash      TEXT NOT NULL,                  -- sha256(COMMUNITY_SALT + network), hex
  body         TEXT NOT NULL,                  -- JSON: {covers, tracks} or {track}
  track_count  INTEGER NOT NULL,
  created_at   INTEGER NOT NULL,               -- ms
  expires_at   INTEGER NOT NULL,               -- created_at + 30 days
  up           INTEGER NOT NULL DEFAULT 0,
  down         INTEGER NOT NULL DEFAULT 0,
  removed_at   INTEGER                         -- taken down by the poster or removed by the owner
);
CREATE INDEX posts_live   ON posts (expires_at) WHERE removed_at IS NULL;
CREATE INDEX posts_poster ON posts (poster, created_at);
CREATE INDEX posts_ip     ON posts (ip_hash, created_at);

CREATE TABLE votes (
  post_id  TEXT NOT NULL,
  voter    TEXT NOT NULL,                      -- sha256(key), hex
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

`network` is the existing `limitKey()` of `CF-Connecting-IP`: an IPv4 address as is, an IPv6 address by its /64. `COMMUNITY_SALT` is a Worker secret, so the stored hash can't be reversed by trying every address.

### What a post holds

- **Playlist or mix:** `{covers, tracks}`. `covers` is up to 4 https links on `COVER_HOSTS` (the mix rule). `tracks` is 1–500 songs, each passed through `cleanTrack` (so each keeps an allowed `art` link, as in Listen Together). The app builds it from the playlist's current songs, like `SharedMixRepository.buildDocument` does for a mix link, plus each song's art link.
- **Song:** `{track}`, one song through `cleanTrack`.
- Title 1–100 characters, poster name 1–40, the whole body at most 256 KB. Anything else is 400 `{error: "bad_request"}`.

### Rules

- **Posting limits:** a poster with 2 posts created in the last 24 hours gets 429 `{error: "daily_limit"}`; taken-down posts still count for those 24 hours, so take-down-and-repost doesn't reset it. A poster with 5 live posts (not removed, not expired) gets 429 `{error: "live_limit"}`. A network with 10 posts in the last 24 hours gets 429 `{error: "daily_limit"}` too.
- **Flood limits** (per network, per minute, rate-limit bindings): `COMMUNITY_WRITE_RL` 10 posts or take-downs, `COMMUNITY_VOTE_RL` 60 votes. Over it: 429 `{error: "rate_limited"}` with `Retry-After`.
- **Blocked** posters get 403 `{error: "blocked"}` on post and vote.
- **Votes:** one per phone per post. Changing it replaces it; `0` takes it back. Voting on your own post is 403 `{error: "own_post"}`. After every vote the post's `up` and `down` are recounted from `votes` in the same D1 batch, so the counts can't drift.
- **Hidden:** a post with `up − down <= −3` leaves the list and opens as 404 for everyone except its poster, who still sees it in Mine with `hidden: true`.
- **Ordering ("hot"):** computed in the Worker over the live, visible posts:
  `hot = sign(s) · log10(max(|s|, 1)) + created_at / HOT_WINDOW_MS`, where `s = up − down` and `HOT_WINDOW_MS` = 36 hours. Ten more votes than a newer post keep a post above it for about a day and a half. One constant to tune.
  ponytail: sorting every live post in the Worker is fine up to a few thousand (5 per phone); past that, store `hot` in a column updated on each vote.
- **Cleanup:** a daily cron (`[triggers] crons = ["17 4 * * *"]`) deletes posts past `expires_at`, and removed posts created more than 24 hours ago, together with their votes (votes first, in one batch).

### Routes

All JSON. Summaries and details share these fields:

```
PostSummary {
  id, kind, title,
  by,            // poster name
  count,         // tracks in the post (1 for a song)
  covers,        // up to 4 links (playlist/mix)
  art, artist,   // the song's cover and artist (song posts)
  createdAt, up, down,
  myVote?,       // -1, 0 or 1: only when the request carries a key
  mine?          // true on your own posts: only when the request carries a key
}
PostDetail = PostSummary + { tracks: [SharedTrack] } or { track: SharedTrack }
```

| Route | Key | Result |
|---|---|---|
| `GET /v1/community/feed?limit=N` | optional | `{posts: [PostSummary]}`, hot order, `N` 1–100 (default 5). With a key: `myVote` and `mine` on each. |
| `GET /v1/community/mine` | required | `{posts: [PostSummary + hidden, expiresAt]}`, your live posts including hidden ones, newest first. |
| `GET /v1/community/me` | required | `{postsLeftToday, spotsFree, blocked}` for the confirm sheet. |
| `GET /v1/community/posts/{id}` | optional | `{post: PostDetail}`, or 404 if removed, expired or hidden (hidden is visible to its poster). |
| `POST /v1/community/posts` | required | Body `{kind, name, title, body}` → 201 `{id}`. Errors as in Rules. |
| `PUT /v1/community/posts/{id}/vote` | required | Body `{value: -1, 0, 1}` → `{up, down, myVote}`. 403 own post or blocked; 404 gone. |
| `DELETE /v1/community/posts/{id}` | required | 204. 403 if it isn't yours; 404 if it's already gone. |

Reads are open to anyone (the Home section, and a phone that hasn't made a key yet).

### Moderation (the owner)

`npm run community -- <command>` runs `scripts/community.mjs`, which calls `wrangler d1 execute stash-community --remote --json` (the owner's own Cloudflare login; nothing is exposed on the internet):

- `list [n]`: the newest and the top posts: id, kind, title, poster name, score, age, and the first 8 characters of the poster id.
- `remove <postId>`: sets `removed_at`.
- `block <postId>`: blocks that post's poster and removes all their live posts.
- `unblock <posterIdPrefix>`: undoes a block.

Ids are checked against `[A-Za-z0-9]{8}` and prefixes against `[0-9a-f]{8,64}` before they go into the SQL (`d1 execute` takes no bind parameters).

## 3. The app

### Turning it on

`HomeSection.COMMUNITY` joins the section list, so it can be moved like the others. Its visibility is **its own switch, off by default**, stored next to the section order (`community_on`), not the hidden set: new sections are otherwise shown automatically, and this one must stay off until chosen. In Settings → Home layout its row reads "Community: playlists and songs other Stash listeners are into. Your posts show your name." While it's off, nothing Community appears anywhere (no section, no ⋮ entries, no `+`), and the app makes no Community requests.

### Code layout

- `core/model`: `CommunityPost` (summary), `CommunityPostDetail`, `CommunityMe`.
- `core/data/community`:
  - `CommunityApiClient`: OkHttp, same call style and `ShareResult` as `ShareApiClient`, base URL `ShareConfig.BASE_URL`.
  - `CommunityKeyStore`: the key, in DataStore; made on first use.
  - `CommunityRepository`: the last feed kept in memory for Home; building a post from a playlist (`SharedMixRepository.buildDocument` plus each song's allowed art link) or from a song (`toSharedTrack` plus its art link).
- `feature/community` (new module): the Home section, See all, the post screen, the picker, the confirm sheet, and their ViewModels.

### Home section

- Header "Community" with `+` and See all, and the line "What Stash listeners are digging this week".
- The top 5 posts as ranked rows: rank, cover or 2×2 mosaic, title, "Playlist · 42 songs · Maya" (or "Song · artist · Sam"), and ▲ count ▼ on the right.
- Tapping a row opens the post. Tapping ▲ or ▼ votes; your vote shows in purple; tapping it again takes it back.
- The list reloads when Home opens, and the last one loaded stays on screen meanwhile, so it doesn't flash empty. With nothing loaded and no connection: one line, "Couldn't load Community", with Retry. With no posts at all: "Nothing here yet. Be the first: tap +."

### See all

All / Mine chips, up to 100 posts, `+ Post` in the top bar. Mine lists your live posts, newest first, including ones hidden by votes (labelled "Hidden by votes").

### The post screen

- **Playlist or mix:** the 2×2 mosaic, title, "Playlist · 42 songs · 2 h 11 min", "Posted by Maya · 3 days ago", then **Play**, **Save a copy**, and your vote. A line reads "As posted. Maya's later changes don't show here.", then the songs with their covers.
  - Play streams the frozen songs the way a shared mix's Play preview does today.
  - Save a copy is `SharedMixRepository.saveCopy` on a `SharedMixDocument` built from the post.
- **Song:** the large cover, title, artist, "Posted by Sam · 1 day ago", then **Play**, **Like**, and your vote. Play and Like go through the song-link card's path (the exact persist, then play or like).
- **Your own post:** ⋮ → Take down my post (with a confirm). You can't vote on it: its ▲ ▼ just show the count.
- A post that's gone (taken down, removed, expired, hidden) reads "This post is no longer available".

### Posting

- "Post to Community" sits next to Share in the ⋮ menu of a playlist or mix (playlist detail) and of a song (`TrackOptionsSheet` gets `onPostToCommunity`, where null hides it).
- `+` opens a picker: your playlists and mixes, then your 20 most recently played songs (a new `ListeningEventDao` query).
- **The confirm sheet:**
  - The item with its cover, "Playlist · 42 songs · posting as Maya".
  - The frozen-copy note: "Everyone with Community turned on will see these 42 songs exactly as they are now. Later changes to your playlist won't show. It stays up for 30 days, and you can take it down any time."
  - "2 of 2 posts left today · 4 of 5 spots free", from `GET /me` when the sheet opens.
  - Post and Cancel.
  - A blank "Show my name as" asks for a name first, and so does a playlist with no songs ("This playlist is empty").
- **Result:** "Posted to Community", and your post appears in the list tagged YOU.
- **Errors say which:**
  - "You've posted twice today. Try again tomorrow."
  - "You have 5 posts up. Take one down to post again", with a See my posts button.
  - "You can't post to Community."
  - The usual offline message.
  - "Too many songs (500 at most)."

### Votes

Optimistic: the highlight and count change at once and flip back with a short message if the server refuses. A tap on your current vote sends `0`.

## 4. When things go wrong

| Situation | What happens |
|---|---|
| Server unreachable, a list was loaded before | The last list stays; votes and posts fail with the offline message. |
| Server unreachable, nothing loaded yet | "Couldn't load Community" with Retry, not an empty gap. |
| A vote fails | The highlight flips back; a short message. |
| Posting fails | The specific reason (above). The sheet stays open, so nothing is lost. |
| The post disappears while open | "This post is no longer available". |
| Rate limited (429 `rate_limited`) | "Slow down a moment" and nothing is retried automatically. |
| The key is lost | A new key is made on the next action; old posts can't be taken down from this phone and expire in 30 days. |

## 5. Privacy and abuse

- **Public:** your "Show my name as" name, your posts, and vote counts.
- **Never public:** your key; which posts you voted on (the server keeps voter ids only to stop double votes); your IP address, stored only as a salted hash for the per-network cap.
- **Covers** only come from `COVER_HOSTS`, so no post can make other phones fetch from a server that logs IP addresses.
- **Limits:** text lengths, a 500-song cap, a 256 KB body cap, per-minute flood limits, and the per-phone and per-network daily caps.
- **Moderation:** −3 hides a post; the owner removes and blocks.
- **Disclosure:** the Home layout switch says it in one line. The README's "What Stash talks to" lists the Community routes.

## 6. Testing

- **Worker:** `node --test` against a real in-memory SQLite database through a small `test/fake-d1.js` shim over Node's built-in `node:sqlite` (no new dependency), running `0001_community.sql`. It covers:
  - the posting limits, including take-down-and-repost and the network cap;
  - one vote per phone, changing and taking back a vote, no voting on your own post, and the recount;
  - the −3 hide, including the poster still seeing the post in Mine;
  - hot ordering;
  - take-down permissions and block;
  - the cron cleanup;
  - input checks (kinds, lengths, 500 songs, cover hosts, bad keys);
  - `myVote` and `mine` only with a key.
- **CI:** a `worker-tests` job in `.github/workflows/tests.yml` runs `node --no-warnings --test test/*.test.js` in `infra/share-worker` on Node 24, with no `npm ci` since the tests import only local files. Today these tests run only on the owner's PC.
- **App unit tests:**
  - the switch is off by default, including after an update;
  - the key is made once and reused;
  - the API client's parsing and errors;
  - optimistic votes and their rollback;
  - each posting error message;
  - the picker lists playlists, then recent songs;
  - the post screen for all three kinds.
- **Device, before the PR:** the Pixel 5 and Pixel 6, plus a PC script as a third voter.
  - Post from each way in, and see it on the other phone.
  - Vote from both, and hit the daily and live limits.
  - Take a post down, and watch −3 hide one.
  - Run `block`, and check the switch off leaves no trace.

## 7. Rollout

1. `wrangler d1 create stash-community`; add the `COMMUNITY_DB` binding, `COMMUNITY_WRITE_RL`, `COMMUNITY_VOTE_RL` and the cron trigger to `wrangler.toml`.
2. `wrangler d1 migrations apply stash-community --remote`.
3. `wrangler secret put COMMUNITY_SALT`.
4. Deploy with the usual check: all 8 shared-mix links answer identically before and after.
5. The app ships with Community off. Nothing changes for anyone until they turn it on.

The migration only adds a new database; rolling back is deploying the previous Worker version. The database can stay.

## 8. Rejected alternatives

- **Live links** (the post follows the poster's playlist): votes could end up on content nobody voted for. The owner chose frozen copies.
- **Owner pre-approval:** safest, but posts wait on one person and the section goes quiet.
- **Votes only, no removal:** nothing could ever take a slur down.
- **KV for posts and votes:** eventually consistent (up to about 60 s) with no atomic counters, so two votes at once can overwrite each other and one-vote-per-phone can be dodged.
- **One Durable Object for the feed:** consistent, but every request goes through one object, and moderation would need admin routes on the internet.
- **Anonymous posts:** less to moderate, but it drops the social point of the feature.
- **Carousel or a featured banner on Home:** the carousel fits three posts and hides votes; a banner would be the second one on Home, under Daily Discovery.
- **Accounts or sign-in:** Stash has none, and a one-phone key is enough at this size.
