# Stash Community: design

Stash listeners post playlists, mixes and songs they're into, and everyone who turns Community on votes them up or down. The best of the week rises to the top of a Home section. It is the social side of Stash that shared mixes and Listen Together started: those are for friends you invite; Community is for everyone who opts in.

Approved in brainstorming on 2026-09-26. Mockups (git-ignored): `.superpowers/brainstorm/30-1790442326/` (`community-home-layout.html`, `community-posting.html`, `community-post-detail.html`). One change since the mockups: playlist detail has no ⋮ menu, so "Post to Community" for a playlist or mix lives in its Share sheet (§3 Posting).

## 1. Decisions

| Question | Decision | Why |
|---|---|---|
| Who removes bad posts | The community first: a post at −3 or below hides for everyone. The owner is the backstop: commands to remove any post, undo their removals and vote attacks (a post the owner restores can't be hidden by votes again), and block a phone from posting. | Posts go live at once, and nobody has to approve anything; the owner still has the last word. |
| What a post is | A **frozen copy**: the songs as they were when posted. Later changes to the poster's playlist don't show. | Votes always match what people heard. |
| Limits | Per phone: **2 posts per 24 hours, 5 live at once**, each live **30 days**. A looser per-network cap sits behind them. | Enough to share what you're into this week; nobody can flood the section. |
| Ordering | **Likes plus freshness** ("hot"): each net vote is worth 3.6 hours of freshness, so 10 more votes keep a post above a newer one for about a day and a half. | The section keeps moving, new posts get seen, and votes still lift posts. |
| Names | Posts show the poster's **"Show my name as" name** ("Maya's pick"). | The social point of the feature. |
| Home layout | **A ranked list** like Top Albums: top 5, votes on each row, `+` and See all in the header. | Most posts in the least space; votes without leaving Home. |
| Posting | From a **playlist's or mix's Share sheet**, from the **song ⋮ menus** (Library, playlists, albums, artists, Liked Songs, the queue, Now Playing), and from a `+` button on the section that opens a picker. All lead to one confirm sheet. | Where people already share, plus a button nobody can miss. |
| Accounts | **None.** A random key per phone identifies it, and the server keeps only its hash. Anyone can make keys, so **one internet connection counts for at most 2 votes each way on a post**, and posts are capped per connection. | Stash has no accounts. The per-connection caps make scripted voting and posting expensive without one. |
| Storage | **D1** (Cloudflare's SQL database) bound to the existing `stash-share` Worker. | One vote per phone per post, correct counts under concurrent votes, ranking in one place, and a console for moderation. |
| Opt-in | The Home section is **off for everyone** until turned on in Settings → Home layout. While off, the app never contacts the Community routes. | "An optional feature you can enable." |

**Not in this version:**
- A share link for a post (needs its own web page and App Link).
- A "New" tab.
- A report button (downvotes do that job).
- Editing a post (take it down and post again).
- Posting from search results: their ⋮ is a separate menu with no Share row. A song found in search can be posted from Now Playing or the `+` picker once it has played.

## 2. Server (in `infra/share-worker`)

Community lives in the same `stash-share` Worker as shared mixes and Listen Together rooms, under `/v1/community`. It adds one D1 database (`stash-community`, binding `COMMUNITY_DB`), three rate-limit bindings, a cron trigger and one secret. Mixes (KV) and rooms (Durable Objects) are untouched.

### Identity

The first time a phone uses Community it makes a **community key**: 32 random bytes, base64url, 43 characters (the same format as a mix edit key, checked by `validEditKey`). Every request that posts, votes, takes down or asks "how many posts do I have left" sends it in the header `X-Stash-Community-Key`. The server never stores the key: it stores `sha256(key)` in hex, called the phone's **poster id** (as a voter, the **voter id**). A malformed key is rejected with 401 `{error: "bad_key"}`.

Losing the key (clearing the app's data, a new phone) means a new identity: the old posts can no longer be taken down by that person and simply expire.

**Network.** Where the rules say "network", they mean `sha256(COMMUNITY_SALT + prefix)` in hex, where `prefix` is an IPv4 address as is, or an IPv6 address cut to its **/56**. That's a coarser grouping than the rate limits' /64 (`limitKey`): many ISPs give a home a whole /56, and a free tunnel hands out a /48, which would otherwise pose as tens of thousands of networks. `COMMUNITY_SALT` is a Worker secret, so the stored hash can't be reversed by trying every address.

### Tables (`migrations/0001_community.sql`, and `0002_vouched.sql`, which rebuilds `posts` to add `vouched` before `body`)

```sql
CREATE TABLE posts (
  id           TEXT PRIMARY KEY,               -- 8 chars [A-Za-z0-9], like a mix id
  kind         TEXT NOT NULL CHECK (kind IN ('playlist', 'mix', 'song')),
  title        TEXT NOT NULL,                  -- playlist/mix name, or the song title
  poster_name  TEXT NOT NULL,                  -- "Show my name as" at posting time
  poster       TEXT NOT NULL,                  -- sha256(key), hex
  ip_hash      TEXT NOT NULL,                  -- the network, hex
  summary      TEXT NOT NULL,                  -- small JSON for the list: {covers} or {art, artist}
  track_count  INTEGER NOT NULL,
  created_at   INTEGER NOT NULL,               -- ms
  expires_at   INTEGER NOT NULL,               -- created_at + 30 days
  up           INTEGER NOT NULL DEFAULT 0,     -- counted votes (per-network cap applied)
  down         INTEGER NOT NULL DEFAULT 0,
  removed_at   INTEGER,                        -- ms, when it was taken down or removed
  removed_by   TEXT CHECK (removed_by IN ('poster', 'owner') AND (removed_by IS NULL) = (removed_at IS NULL)),  -- who removed it; restore only undoes 'owner'
  vouched      INTEGER NOT NULL DEFAULT 0,     -- 1 once the owner restores it: votes never hide it again
  body         TEXT NOT NULL                   -- JSON: {covers, tracks} or {track}. Last, so reading the other columns never walks past it
);
-- Must match the feed's ORDER BY exactly (12960000.0 is MS_PER_VOTE), or the list goes back to sorting every live post.
-- No index on expires_at: with literal values (wrangler d1 execute) SQLite picks it and sorts again. The Worker and the EXPLAIN test bind their values, so no test would notice one.
CREATE INDEX posts_hot    ON posts ((up - down) + created_at / 12960000.0, created_at) WHERE removed_at IS NULL;
CREATE INDEX posts_poster ON posts (poster, created_at);
CREATE INDEX posts_ip     ON posts (ip_hash, created_at);

CREATE TABLE votes (
  post_id  TEXT NOT NULL,
  voter    TEXT NOT NULL,                      -- sha256(key), hex
  ip_hash  TEXT NOT NULL,                      -- the voter's network, for the per-network cap
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

### What a post holds

- **Playlist or mix:** `body` is `{covers, tracks}`. `covers` is up to 4 https links on `COVER_HOSTS` (the mix rule). `tracks` is 1–500 songs, each passed through `cleanTrack` (so each keeps an allowed `art` link, as in Listen Together) with its `by` removed (that's a Listen Together member id). `summary` is `{covers}`.
- **Song:** `body` is `{track}`, one song through `cleanTrack`, `by` removed. `summary` is `{art, artist}`, from that track. The title is the song's own title, trimmed and cut to 100 characters. A title sent with a song post is ignored.
- **Which kind:** the app sends `mix` for `DAILY_MIX` and `STASH_MIX` playlists, and `playlist` for every other type.
- Title 1–100 characters (playlist or mix), poster name 1–40. Anything else is 400 `{error: "bad_request"}`. A request body over 256 KB is 413 `{error: "too_large"}`, like the mix routes' size check.

The server writes `summary` itself from the cleaned body, so it can't disagree with the body.

### Rules

- **Posting limits,** checked in this order (the first one hit is the error): blocked, then daily, then live, then network.
  - A poster with 2 posts created in the last 24 hours gets 429 `{error: "daily_limit"}`. Taken-down posts still count for those 24 hours, so take-down-and-repost doesn't reset it.
  - A poster with 5 live posts (not removed, not expired) gets 429 `{error: "live_limit"}`.
  - A network with 10 posts in the last 24 hours gets 429 `{error: "network_limit"}`.
- **Rate limits** per network, per minute (rate-limit bindings, namespaces 2005–2007; 2001–2004 are taken). Over them: 429 `{error: "rate_limited"}` with `Retry-After`.
  - `COMMUNITY_WRITE_RL`: 10 posts or take-downs.
  - `COMMUNITY_VOTE_RL`: 60 votes.
  - `COMMUNITY_READ_RL`: 120 reads.
- **Blocked** posters get 403 `{error: "blocked"}` on post and vote.
- **Votes:**
  - Each phone has one vote per post, stored with its network. Changing it replaces it; `0` takes it back. Voting on your own post is 403 `{error: "own_post"}`.
  - **Counting:** one network counts for at most 2 votes in each direction on a post. After every vote, the post's `up` is recounted in the same D1 batch as the sum, over networks, of min(that network's upvotes, 2), and `down` the same way. A script making keys behind one connection can move a post by at most 2 either way.
- **Hidden:** a post with `up − down <= −3` leaves the list and opens as 404 for everyone except its poster, who still sees it in Mine with `hidden: true`. With the network cap, hiding takes downvotes from at least two networks. A post the owner has restored is `vouched`: votes never hide it again, though they still count for its ranking.
- **Ordering ("hot"):** done in the feed's SQL, `ORDER BY (up - down) + created_at / 12960000.0 DESC LIMIT ?`. 12,960,000 ms is 3.6 hours, so:
  - Each net vote is worth 3.6 hours of freshness: 10 more votes keep a post above a newer one for 36 hours.
  - A brand-new post with no votes starts above everything more than a few hours old that has few votes.
  - The 3.6 hours is the one constant to tune.
  - The `posts_hot` expression index serves this ordering, so the feed walks posts in hot order and stops at the limit instead of sorting every live post. Changing 12,960,000 means rebuilding that index in a new migration; without it, the list silently goes back to the full scan and sort.
- **The list never reads `body`:** the feed selects `id, kind, title, poster_name, poster, summary, track_count, created_at, up, down`, so a Home open never loads song lists.
- **Cleanup:** a daily cron (`[triggers] crons = ["17 4 * * *"]`) deletes, together with their votes (votes first, in one batch):
  - posts past `expires_at`;
  - posts removed more than 24 hours ago that were also created more than 24 hours ago. That keeps an owner's removal restorable for a day (a poster's take-down is kept too, but `restore` won't undo it), and keeps every post of the last 24 hours for `daily_limit`.

### Routes

All JSON. Summaries and details share these fields:

```
PostSummary {
  id, kind, title,
  name,          // poster name (not `by`: in a SharedTrack that's a Listen Together member id)
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
| `GET /v1/community/posts/{id}` | optional | `{post: PostDetail}`, or 404 `{error: "gone"}` if removed, expired or hidden (hidden is visible to its poster). |
| `POST /v1/community/posts` | required | Body `{kind, name, title, body}` → 201 `{id}`. Errors as in Rules. |
| `PUT /v1/community/posts/{id}/vote` | required | Body `{value: -1, 0, 1}` → `{up, down, myVote}`. 403 own post or blocked; 404 gone. |
| `DELETE /v1/community/posts/{id}` | required | 204. 403 `{error: "not_yours"}`; 404 if it's already gone. |

Reads are open to anyone (the Home section, and a phone that hasn't made a key yet), within the read limit. Every error body is `{error: "<code>"}` with the codes above.

### Moderation (the owner)

`npm run community -- <command>` runs `scripts/community.mjs`, which calls `wrangler d1 execute stash-community --remote --json` (the owner's own Cloudflare login; nothing is exposed on the internet):

- `list [n]`: the newest and the top posts (the top ones only while up), including hidden ones: id, kind, title, poster name, up/down, `hidden`, `vouched`, `removed` (null while the post is up, else `'poster'` or `'owner'`), age, and the first 8 characters of the poster id. Then the blocked phones: that prefix, when each was blocked, and a note naming the post.
- `remove <postId>`: sets `removed_at`, and `removed_by = 'owner'`, on a post that's still up. A post its poster took down is left alone, so `restore` can't bring it back.
- `restore <postId>`: undoes the owner's removal (clears `removed_at` and `removed_by`). If the post is up after that, it deletes the post's downvotes and sets `vouched`, so votes can't hide it again. Then it recounts. For a post that was removed by mistake, or buried by a vote attack. A post its poster took down (`removed_by = 'poster'`) stays down and unvouched, votes and all.
- `block <postId>`: blocks that post's poster and removes all their live posts.
- `unblock <posterIdPrefix>`: undoes a block, matched with `substr(poster, 1, length) = '<prefix>'` (D1 caps `LIKE` patterns at 50 bytes).

Ids are checked against `[A-Za-z0-9]{8}` and prefixes against `[0-9a-f]{8,64}` before they go into the SQL (`d1 execute` takes no bind parameters).

These commands are the real backstop. The network caps make vote attacks expensive, not impossible: someone with many addresses can still bury a post, and `restore` undoes it for good: fresh downvotes can't hide a vouched post again.

## 3. The app

### Turning it on

- `HomeSection.COMMUNITY` joins the section list, so it can be moved like the others.
- **Its visibility is its own switch, off by default**, stored next to the section order (`community_on`), not the hidden set. New sections are otherwise shown automatically, and this one must stay off until chosen.
- **The switch is applied in `HomeSectionsPreference.visibleSections`**, including its `.catch` fallback, which today returns every section. A DataStore error must not turn Community on.
- **It isn't in the Home defaults.** Home's default section lists (`HomeUiState.sections`, and `DiscoveryUi.sections`, which the Qobuz fetch uses before the preference loads) leave COMMUNITY out, so nothing Community appears or loads before the saved setting is read.
- **Where it lands:** the first time it's turned on, it moves to the top of the section order, directly under the Discover hero, where the mockups show it. From there it can be moved like any other section.
- **The Settings → Home layout row** reads "Community: playlists and songs other Stash listeners are into. Your posts show your name."
- **While it's off,** nothing Community appears anywhere (no section, no menu entries, no `+`), and the app makes no Community requests.

### Code layout

- `core/model`:
  - `CommunityPost`, both the list row and the opened post (which fills its `tracks` or `track`), and `CommunityMe`.
  - `PostTarget`: `Song(track: Track)` or `Playlist(playlistId: Long)`. It's what any entry point asks to post.
  - `Track.toSharedTrackWithArt()`, moved from `DefaultSessionCatalog`'s private `Track.shared()`: the descriptor plus its art link when it's on `COVER_HOSTS`. Listen Together and Community both use it.
- `core/data`:
  - **`community/CommunityApiClient`:** OkHttp against `ShareConfig.BASE_URL`, in the style of `ShareApiClient`. Unlike `shareCall`, it **reads the Worker's `error` code** from error bodies. It returns:
    - `CommunityResult.Ok(value)`;
    - `Rejected(code)` for a 4xx with one of the codes above;
    - `Failed(reason)` for offline, timeouts and any 5xx, including the catch-all 503 `unavailable`.
  - **`community/CommunityKeyStore`:** the key, in DataStore; made on first use.
  - **`community/CommunityRepository`:** the last feed kept in memory for Home, and building a post.
    - **A playlist or mix:** `SharedMixRepository.buildDocument(..., withArt = true)`, a new flag that keeps each song's art link through `toSharedTrackWithArt`.
    - **A song:** `toSharedTrackWithArt` on the song's **library row, re-read by id**. The queue and Now Playing hold a copy rebuilt from the player, with no ISRC or Spotify id and sometimes a local art path. Share already re-reads for this reason (`NowPlayingViewModel.onShareCurrent`), and without it every listener of the post would lose exact lossless matching. A synthetic id with no row falls back to the given track.
    - **Clipping:** titles are cut to 100 characters and names to 40 before sending, the way `buildDocument` already clips a playlist name, so a long song title never becomes a 400.
  - **`TrackDao.getRecentlyPlayed(limit)`:** a new query, the picker's recent songs. Rows with a `last_played`, newest first, downloaded or stream-only; it's what the person actually played. (The existing `getLastPlayedTrack()` returns one downloaded row.)
- `core/ui`: `LocalPostToCommunity`, a CompositionLocal holding a nullable `(PostTarget) -> Unit`, the same pattern as `LocalListenTogetherRole`. The app provides it only while Community is on. Every entry point uses it: the song menus, the playlist Share sheet and the `+` picker.
- `feature/community` (new module): the Home section, See all, the post screen, the picker, the confirm sheet, and their ViewModels.
- **Wiring, in the app module:** feature modules don't depend on each other, so the app connects them.
  - **Home:** `HomeScreen` (feature/home) gets a `communitySection: @Composable () -> Unit` slot. Its `when (section)` renders COMMUNITY by calling that slot, and `StashNavHost`'s Home route fills it with feature/community's section.
  - **The posting sheets:** `StashNavHost` provides `LocalPostToCommunity` and hosts the one confirm sheet and the picker, after its `NavHost`.
  - **Routes:** See all and the post screen are routes in `StashNavHost`.

### Home section

- Header "Community" with `+` and See all, and the line "What Stash listeners are digging this week".
- The top 5 posts as ranked rows: rank, cover or 2×2 mosaic, title, "Playlist · 42 songs · Maya" (or "Song · artist · Sam"), and ▲ count ▼ on the right.
- Tapping a row opens the post. Tapping ▲ or ▼ votes; your vote shows in purple; tapping it again takes it back.
- **Loading:** the list reloads when Home opens, and the last one loaded stays on screen meanwhile, so it doesn't flash empty.
- **Empty or offline:** with nothing loaded and no connection, one line reads "Couldn't load Community", with Retry. With no posts at all: "Nothing here yet. Be the first: tap +."

### See all

All / Mine chips, up to 100 posts, `+ Post` in the top bar. Mine lists your live posts, newest first, including ones hidden by votes (labelled "Hidden by votes").

### The post screen

- **Playlist or mix:** the 2×2 mosaic, title, "Playlist · 42 songs · 2 h 11 min", "Posted by Maya · 3 days ago", then **Play**, **Save a copy**, and your vote. A line reads "As posted. Maya's later changes don't show here.", then the songs with their covers.
  - **Play** streams the frozen songs the way a shared mix's Play preview does today. Like that path, it saves each song as a hidden stream-only library row (the Library tabs show only downloaded songs). This is deliberate: it's how previews already work.
  - **Save a copy** is `SharedMixRepository.saveCopy` on a `SharedMixDocument` built from the post.
- **Song:** the large cover, title, artist, "Posted by Sam · 1 day ago", then **Play**, **Like**, and your vote. Play and Like go through the song-link card's path: persist the song, then play or like it.
- **Your own post:** ⋮ → Take down my post (with a confirm). You can't vote on it: its ▲ ▼ just show the count.
- **A post that's gone** (taken down, removed, expired, hidden) reads "This post is no longer available".

### Posting

- **A playlist or mix:** the Share icon in its header opens `ShareMixSheet`.
  - While `LocalPostToCommunity` is set, the sheet shows **Post to Community** in both of its states: under "Create link" for a playlist that isn't shared yet, and under "Share again" and "Copy link" for one that already has a link.
  - Tapping it closes the sheet and calls the local with `PostTarget.Playlist(id)`.
- **A song:**
  - `TrackOptionsSheet` shows **Post to Community** next to Share whenever `LocalPostToCommunity` is set.
  - It gets an `onDismiss: () -> Unit` parameter: the new row calls it, then the local, because the sheet can't close itself (each call site closes its own `ModalBottomSheet`). Each of its 7 call sites passes its sheet-closing lambda, one line each:
    - `AlbumDetailScreen`
    - `ArtistDetailScreen`
    - `LibraryScreen` (two sites)
    - `LikedSongsDetailScreen`
    - `PlaylistDetailScreen`
    - `QueueBottomSheet`
  - Now Playing's options sheet reads the same local and uses its existing `onDismiss`.
- **`+`** opens the picker: your playlists and mixes, then your 20 most recently played songs (`TrackDao.getRecentlyPlayed`).
- **The confirm sheet:**
  - The item with its cover, "Playlist · 42 songs · posting as Maya".
  - The frozen-copy note: "Everyone with Community turned on will see these 42 songs exactly as they are now. Later changes to your playlist won't show. It stays up for 30 days, and you can take it down any time."
  - "2 of 2 posts left today · 4 of 5 spots free", from `GET /me` when the sheet opens.
  - Post and Cancel.
- **Checks before posting:**
  - A blank "Show my name as" asks for a name first.
  - "This playlist is empty."
  - "Too many songs to post (500 at most)." (`buildDocument` itself stops at 2000.)
- **Result:** "Posted to Community", and your post appears in the list tagged YOU.
- **Errors say which:**

| Code | Message |
|---|---|
| `daily_limit` | "You've posted twice today. Try again tomorrow." |
| `live_limit` | "You have 5 posts up. Take one down to post again", with a See my posts button. |
| `network_limit` | "Too many posts from your internet connection today. Try again tomorrow." |
| `blocked` | "You can't post or vote in Community." |
| `rate_limited` | "Slow down a moment." |
| `too_large` | "This playlist is too big to post." |
| Offline, timeout or 5xx (`Failed`) | "Couldn't reach Community. Try again." |
| Any other code | "Something went wrong. Try again." |

### Votes

Optimistic: the highlight and count change at once and flip back with a short message if the server refuses. A tap on your current vote sends `0`.

## 4. When things go wrong

| Situation | What happens |
|---|---|
| Server unreachable, a list was loaded before | The last list stays; votes and posts fail with "Couldn't reach Community. Try again." |
| Server unreachable, nothing loaded yet | "Couldn't load Community" with Retry, not an empty gap. |
| A vote fails | The highlight flips back; a short message. |
| Posting fails | The specific reason (above). The sheet stays open, so nothing is lost. |
| The post disappears while open | "This post is no longer available". |
| Rate limited (429 `rate_limited`) | "Slow down a moment" and nothing is retried automatically. |
| The key is lost | A new key is made on the next action; old posts can't be taken down from this phone and expire in 30 days. |

## 5. Privacy and abuse

- **Public:** your "Show my name as" name, your posts, and vote counts.
- **Never public:** your key; which posts you voted on (the server keeps voter ids only to stop double votes); your IP address. The server stores only the salted network hash, for the per-network caps.
- **Covers** only come from `COVER_HOSTS`, so no post can make other phones fetch from a server that logs IP addresses.
- **Abuse:**
  - Text lengths, a 500-song cap and a 256 KB body cap.
  - Per-minute limits on reads and writes.
  - The per-phone and per-network daily caps.
  - At most 2 counted votes per network per post.
- **Moderation:** −3 hides a post; the owner removes, restores and blocks.
- **Disclosure:**
  - The Home layout switch says it in one line.
  - The README's "What Stash talks to" lists the Community routes. Its `stash-share` entry, which today says the Worker doesn't store your IP address, is reworded: Community keeps a salted hash of it for the per-network limits.

## 6. Testing

- **Worker:** `node --test` against a real in-memory SQLite database through a small `test/fake-d1.js` shim over Node's built-in `node:sqlite` (no new dependency), running every migration in `migrations/` in order. The shim returns plain objects (`{...row}`), because `node:sqlite` rows have a null prototype and fail `assert.deepStrictEqual`. The tests cover:
  - the posting limits, including take-down-and-repost and `network_limit`;
  - one vote per phone, changing and taking back a vote, no voting on your own post, and the recount;
  - the per-network vote cap: many keys behind one network move a post by 2 at most;
  - IPv6 grouped by /56: two addresses in one /56 are one network;
  - the −3 hide from votes on two networks, the poster still seeing the post in Mine, and `restore`, after which votes can't hide the post again;
  - hot ordering: 10 more votes ≈ 36 hours;
  - the list never selecting `body`;
  - `by` removed from posted songs;
  - take-down permissions and block;
  - the cron cleanup, including an owner's removal staying restorable for a day;
  - input checks (kinds, lengths, 500 songs, 256 KB, cover hosts, bad keys);
  - `myVote` and `mine` only with a key;
  - every error body carrying its code.
- **CI:** a `worker-tests` job in `.github/workflows/tests.yml` runs `node --no-warnings --test test/*.test.js` in `infra/share-worker` on Node 24, with no `npm ci` since the tests import only local files. Today these tests run only on the owner's PC.
- **App unit tests:**
  - the switch is off by default, including after an update, before the preference loads (Home's defaults leave COMMUNITY out), and when the preference read fails (`visibleSections`' fallback);
  - turning it on moves it to the top of the order;
  - the key is made once and reused;
  - the API client's parsing, including each `error` code, and 5xx as `Failed`;
  - titles clipped to 100 and names to 40;
  - `getRecentlyPlayed`: newest first, stream-only rows included;
  - `ShareMixSheet` shows Post to Community in both of its states, and only while the local is set;
  - optimistic votes and their rollback;
  - each posting message;
  - the picker lists playlists, then recent songs;
  - the post screen for all three kinds;
  - `LocalPostToCommunity` hides the song entry while off.
- **Device, before the PR:** the Pixel 5 and Pixel 6, plus a PC script as a third voter.
  - Post from each way in (Share sheet, a song ⋮ and Now Playing's, `+`), and see it on the other phone. Check that the song's options sheet closes as the confirm sheet opens.
  - Vote from both, and hit the daily limit.
  - Hit the live limit on a phone still under its daily limit, since daily is checked first. After one real post, seed 4 copies of it with a `wrangler d1 execute` insert (`INSERT … SELECT … FROM posts WHERE id = '<its id>'`) that gives them new ids and a `created_at` more than 24 hours ago, keeping `expires_at` in the future. The next post gets `live_limit`.
  - Take a post down.
  - Hide one at −3: Wi-Fi counts for 2, so the Pixel 6 votes from mobile data. If that isn't available, use `remove` to check the "no longer available" path.
  - Run `block` and `restore`, and check that switching Community off leaves no trace.

## 7. Rollout

1. `wrangler d1 create stash-community`. Add the `COMMUNITY_DB` binding, the three rate limits and the cron trigger to `wrangler.toml`.
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
- **A logarithmic "hot" (Reddit's):** each step is ten times the votes, so at a small community's vote counts the list is just the newest posts, and −1, 0 and +1 rank alike.
- **One vote per network, full stop:** it would stop scripted voting cold, but a family or a dorm on one connection would share a single vote.
- **Anonymous posts:** less to moderate, but it drops the social point of the feature.
- **Carousel or a featured banner on Home:** the carousel fits three posts and hides votes; a banner would be the second one on Home, under Daily Discovery.
- **Accounts or sign-in:** Stash has none. A one-phone key with per-network caps is enough at this size. The known cost: behind a carrier's shared IPv4 address, many people count as one network for the 10-posts-a-day cap, the 2-vote cap and the read limit. That's fine at this size, and worth revisiting if Community grows.
