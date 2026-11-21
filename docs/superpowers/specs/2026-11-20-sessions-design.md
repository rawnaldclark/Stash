# Sessions: design

Sessions puts your listening on the air. While you play music in Stash, anyone can tune in and hear what you hear, at the same moment, from their own sources. You run it like a DJ: listeners request songs, you and your co-hosts take them or pass, and the room reacts. You can go live with one tap, have Stash take you live during the hours you set ("weekdays 9–5 at work"), or leave it on whenever you use Stash. Each host has a station; listeners vote it up or down the way they vote on Community's top tracks, and the best stations rise in Community's **Live now**.

Status: owner's brief on 2026-11-20, decisions below agreed in chat that day. This is the design to approve before the plan. Mockups come next (Community's Live row, the Go live sheet, the host and listener views of Now Playing).

**Builds on:**
- Listen Together (`2026-09-24-listen-together-design.md`, `-ui-design.md`, `2026-11-04-listen-together-guest-adds-design.md`): the room, its clock and ready handshake, each phone playing the same recording from its own sources, suggestions, reactions.
- Community (`2026-09-26-stash-community-design.md`): the community key and poster id, network-capped votes, the "heard" gate (`0006_heard.sql`), hiding at −3, the owner's moderation commands.
- "Show my name as".

**The room's experience** (why people stay: phrases and the feed, saves, follows, open decks, last song and recap, regulars, the vibe line, crowd favourites) is in `2026-11-20-sessions-ux.md`. Where the two differ, that one wins; its §9 lists what it adds here.

**Built with these refinements.** The build plan (`docs/superpowers/plans/2026-11-20-sessions.md`, decisions 1–11) and step 1's plan (`2026-11-20-sessions-step1-server.md`, "1a as built") record what building settled differently from this spec: among them hyphenated moderation commands (`station-list`, …), Upcoming not cached (the stashfm.app zone's Cache API ignores the query), `next: {start, end}` in place of `nextStart`/`nextEnd`, the summaries' `vibe`, `followers`, `heard` and `following`, and stopping a follow always working. Where they differ from this spec, they win. The server's contract for the app is in `infra/share-worker/README.md`, "Sessions".

## 1. Decisions

| Question | Decision | Why |
|---|---|---|
| When is a session live | **Only while the host is playing music in Stash.** Paused for 15 minutes, the app closed, or the host offline for 60 s: the session ends. No always-on stations. | Owner: sessions are live listening, not a jukebox that runs without you. |
| How you go live | Three ways: **Go live now** (one tap), **on a schedule** (hours when Stash takes you live if you're playing), **always** (live whenever you play). Auto-live is off until the host turns it on. | Owner's brief. Off by default because going live shares what you play in real time. |
| What a schedule shows others | The hours also appear in Community's **Upcoming** ("Maya's Desk Radio · weekdays 9–5"), so listeners know when to come back. No reminders or notifications in v1. | Owner chose this. Reminders need push notifications, which Stash doesn't have. |
| Votes | **On the host's station**, not on one session. Every session adds to the station's score; the score ranks the station whenever it's live. A vote needs **2 minutes listening** to that station first. One phone, one vote; at most 2 per network each way; a station at −3 or below is hidden. | Owner chose this. Sessions come and go; a station builds a reputation. Same rules as top tracks, so the same abuse limits hold. |
| Requests | Listeners request songs. A request **waits** until the host or a co-host taps Play next, Add to queue or Pass. An **Auto-accept** switch sends every request to the end of the queue. | Owner chose this. A DJ takes requests; strangers don't get to run your music. |
| Co-hosts | The host can make up to **3** people co-hosts. Co-hosts skip, edit the queue and handle requests. Only the host ends the session or changes co-hosts. | Owner's brief ("can cohost"). |
| Talking | **Reactions only, no typed chat:** the six emoji, plus a small set of **animated reactions** Stash ships in the app (§5). No GIF search. | Owner: reactions, maybe GIFs. Stash's own set needs no moderation and sends nothing to a GIF service. GIF search can come later. |
| Who sees whom | **Everyone in a session sees everyone in it:** the host, the co-hosts, the listeners, and who requested each accepted song. Names are "Show my name as" or "Listener 7". | Owner: listeners see names. It's a room you walked into. |
| Booting | **The host can boot a listener.** A booted listener is out of that session and can't rejoin it while it lasts; their next session from that host is fine. | Owner. The host's answer to someone being a nuisance, with no reports to review. |
| Size | Up to **50 listeners** a session (`MAX_LISTENERS`, tunable), plus host and co-hosts. | One room holds that cheaply. Private Listen Together stays at 10. |
| Accounts | **None.** Your station belongs to your community key, as your posts do. | Same as Community. |
| Where it lives | Server: the existing `stash-share` Worker (rooms and Community). App: **Android first**; the web player and desktop listen in later. | Community and Listen Together are Android features today. |
| Opt-in | Inside Community, which is off until turned on in Settings › Home layout. While off, the app never contacts the Sessions routes and never goes live. | As Community. |

**Not in v1:** typed chat; GIF search; "remind me" notifications; a host handing the session to someone else; listening on the web player or desktop; tips or gifts.

## 2. What people see

### Listener

1. **Community → Live now.** When any session is live, Community's Home section opens with a row of cards: the song playing (art), the station name, the host, "12 listening", and the station's votes. See all opens **Sessions**: **Live** (ranked, §3) and **Upcoming** (next 24 hours, soonest first).
2. **Tune in.** Tapping a card starts the session's song at the room's position. The mini player and Now Playing show the station ("Maya's Desk Radio · LIVE · 12") instead of your queue; your own queue is kept for when you leave.
3. **In the session** (Now Playing): who's here (host, co-hosts, listeners), Request a song (search, then Request), reactions, the vote pill (greyed out with "Listen 2 minutes to vote" until then), Leave.
4. **When it ends**, the player stops with "Maya's session ended" and offers your own queue back.

A station also has a link, `stashfm.app/s/{id}`, that opens it in the app (live, or its schedule when it isn't), so a DJ can share it anywhere.

### Host

1. **Go live** from Now Playing (next to Listen Together). The first time, a sheet asks for a station name and says plainly what going live shares (§8). After that, one tap.
2. **On the air:** a LIVE pill with the listener count on the mini player and Now Playing, always visible while live. Tapping it opens the session panel: listeners (tap one for **Make co-host** or **Boot**), requests waiting (Play next / Add to queue / Pass), co-hosts, the Auto-accept switch, and **End session**.
3. **Your station** (the Sessions screen's Your station row): name, auto-live (**Off / On a schedule / Always**), the schedule (up to 7 time windows a week), the station link, and Delete station.
4. **Going private:** End session, or switching auto-live off, ends it at once. While a schedule or Always is on, End session keeps you off the air until playback stops and starts again.

### Co-host

The host taps a listener and **Make co-host**. The co-host's Now Playing gains the queue editor, skip and the requests list. They hear the session like any listener.

## 3. Stations, votes and ranking (D1 `stash-community`)

Migration `0007_stations.sql`:

```sql
CREATE TABLE stations (
  id          TEXT PRIMARY KEY,          -- 8 base62 characters, like a post id
  owner       TEXT NOT NULL UNIQUE,      -- sha256(community key): one station per phone
  name        TEXT NOT NULL,             -- 1–40 characters, checked like a post title
  host_name   TEXT,                      -- "Show my name as" at the last update
  schedule    TEXT,                      -- JSON: { tz: "America/New_York", windows: [{ day: 1–7, start: "09:00", end: "17:00" }] } or null
  auto_live   TEXT NOT NULL DEFAULT 'off',  -- off | schedule | always (shown in Upcoming only for schedule)
  created_at  INTEGER NOT NULL,
  last_live_at INTEGER,
  up INTEGER NOT NULL DEFAULT 0, down INTEGER NOT NULL DEFAULT 0,
  vouched INTEGER NOT NULL DEFAULT 0,
  removed_at INTEGER, removed_by TEXT
);
CREATE TABLE station_votes (station_id TEXT, voter TEXT, net TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (station_id, voter));
CREATE TABLE station_heard (station_id TEXT, voter TEXT, at INTEGER NOT NULL, PRIMARY KEY (station_id, voter));
CREATE TABLE live (                        -- the directory of sessions on the air; the room writes it (§4)
  station_id  TEXT PRIMARY KEY,
  room        TEXT NOT NULL,               -- the room code: public on purpose, anyone may join
  track       TEXT,                        -- SharedTrack JSON of the song playing
  listeners   INTEGER NOT NULL DEFAULT 0,
  paused      INTEGER NOT NULL DEFAULT 0,
  started_at  INTEGER NOT NULL,
  seen_at     INTEGER NOT NULL             -- the room's last write; rows older than 3 minutes are ignored and swept
);
```

- **Votes** copy the post rules exactly: one per phone, `0` takes it back, the station's `up`/`down` recounted in the same batch with each network capped at 2 each way, no vote on your own station, a `+1` or `−1` needs a `station_heard` mark. The app sends `PUT …/heard` after **2 minutes** in that station's sessions (in total, across sessions).
- **Hidden** at `up − down <= −3` unless `vouched`: not in Live or Upcoming, its link says it's unavailable; the host still sees it in Your station marked hidden.
- **Live ranking:** `ORDER BY (up − down) + listeners * 1.0 DESC`: each listener right now counts as one vote, so a new station with people in it surfaces, and a well-voted one leads. The `1.0` is the constant to tune.
- **Upcoming:** stations with `auto_live = 'schedule'` whose next window starts within 24 hours and that aren't live now, soonest first. Worked out in the Worker from `schedule` (the time zone makes it awkward in SQL); fine at this scale, and cached 60 s.
- **Clean-up** (the daily cron): `live` rows past `seen_at` + 3 minutes; stations not live for **7 days** (with their votes and heard marks; a station that has never gone live counts from when it was made). The host's app says so in Your station: "Go live within 7 days or your station and its votes are deleted".
- **Moderation:** `npm run community -- station list | remove | restore | block` with the post commands' rules (`block` blocks the phone from posting, voting and going live).

### Routes (`/v1/community/…`, key as in Community)

| Route | Key | Result |
|---|---|---|
| `GET live?limit=N` | optional | `{sessions: [{station: StationSummary, room, track, listeners, paused, startedAt}]}`, ranked. |
| `GET upcoming?limit=N` | optional | `{stations: [StationSummary + {nextStart, nextEnd}]}` |
| `GET stations/{id}` | optional | `{station: StationSummary + schedule, live?: {room, track, listeners}}`; 404 `gone` when removed or hidden. |
| `GET stations/mine` | required | Your station with `hidden`, or `{station: null}`. |
| `POST stations` | required | `{name, hostName}` → 201 `{id}`; 409 `have_station`. |
| `PATCH stations/{id}` | required (owner) | `{name?, hostName?, autoLive?, schedule?}` |
| `DELETE stations/{id}` | required (owner) | 204; ends a live session. |
| `PUT stations/{id}/vote` | required | `{value}` → `{up, down, myVote}`; 403 `listen_first`, `own_station`, `blocked`. |
| `PUT stations/{id}/heard` | required | 204. |

`StationSummary = {id, name, hostName, up, down, myVote?, mine?}`. Station names and host names are checked as a post's title and name are (plain text, 40 characters); as for posts there is no word list: votes hide a bad name and the owner removes it.

## 4. The session room (Listen Together, public)

A session is a Listen Together room created with `{public: true, stationId}` and the host's community key (`POST /v1/rooms`). The room checks that the key owns the station and that the station isn't hidden or blocked, then runs the same engine with these differences:

| | Private room (today) | Session |
|---|---|---|
| Who joins | People with the code or link | Anyone: the code is in `live` |
| Size | 10 | Host + 3 co-hosts + 50 listeners |
| Roles | Host and guests | Host, co-hosts, listeners |
| Host gone 60 s | Control passes to the longest-joined guest | **The session ends** |
| Paused 15 minutes | Nothing | **The session ends** |
| Guests adding songs | "Let guests add songs" switch | Requests wait for the host or a co-host (or Auto-accept) |
| Members list | Everyone sees everyone | Everyone sees everyone |
| Removing someone | Nobody can | The host boots a listener; out for the rest of the session |

**The host's phone stays the player.** "Only the host moves the room on" (Listen Together) still holds: when a co-host skips, accepts a request or edits the queue, the room forwards it to the host's phone as a `command`, the host's app applies it to its own player and queue, and the room follows the host as it does today. A co-host's command while the host's app is reconnecting waits up to 10 s, then is refused with "The host is reconnecting".

**Directory.** The room upserts its `live` row on the first song, on each new song, on pause and play, and at most once a minute for the listener count; it deletes the row when it ends. `seen_at` older than 3 minutes means a room that died without saying so, and reads skip it.

**Lifetime.** A session ends at 12 hours (Listen Together's limit); if the host is still playing, the app opens a new session for the same station right away, and listeners move over with a 2-second gap.

### Protocol additions (all optional; a private room never sends them)

| `t` | Sent by | Payload |
|---|---|---|
| `hello` (+) | anyone | `{ role?: "listener", listenerId }`: `listenerId` is the phone's community poster id (`sha256(key)`; the app makes a key on first tune-in), so a boot holds across a reconnect. `cohostKey?`: a co-host rejoining. |
| `request` | listener | `{ track }`: replaces `suggest` in sessions; 3 waiting per listener, 30 in all |
| `requestAction` | host, co-host | `{ id, action: "next" \| "queue" \| "pass" }` |
| `autoAccept` | host | `{ on }` |
| `cohost` | host | `{ memberId, on }`; the room answers the new co-host with a private `cohostKey` |
| `command` | co-host → room → host | `{ from, action: "skip" \| "next" \| "queue" \| "remove" \| "move", … }` |
| `react` (+) | anyone | `{ emoji }` or `{ anim }`: one of the app's animated reactions (§5), at most one a second |
| `boot` | host | `{ memberId }`: a listener only (not a co-host: take co-host away first). The room closes their sockets and refuses their `resumeToken` and listener id (below) until the session ends. |
| `session` | room | `{ listeners, cohosts, requests?, autoAccept }`: everyone gets the listener and co-host lists; only the host and co-hosts get the waiting requests |
| `ended` (+) | room | `{ reason: "host_left" \| "paused" \| "ended_by_host" \| "max_age" \| "station_removed" \| "booted" }`: a booted listener sees "You were removed from this session". |

## 5. Animated reactions

Twelve short animations in the app (animated WebP, under 40 KB each): applause, fire, hearts, dancing, mind blown, lighter, headphones, rewind ("again!"), crying-laughing, bass drop, wave, clap-along. Sent as their id (`a:fire`), checked by the room against a fixed list kept in step with `RoomProtocol` (as `EMOJI` is today), and drawn over Now Playing like the emoji. An app that doesn't know an id shows nothing. Later sets ship with app updates.

## 6. Auto-live in the app

- **Schedule:** while auto-live is "On a schedule", playback that starts inside a window (or a window that opens while playing) opens a session. At the window's end the session ends at the end of the current song.
- **Always:** any playback opens a session.
- **Never auto-live:** Download mode (offline), while hosting or guesting a private Listen Together room, when Community is off, or when the station is hidden or blocked. A song from the phone's own files is broadcast by its tags like any other; listeners who can't find it skip it (the room's existing "unavailable").
- The LIVE pill appears the moment a session opens, so auto-live is never silent. The first auto-live of the day also posts a quiet notification ("You're live on Maya's Desk Radio · End").
- Sessions need the network; with none, auto-live waits and says nothing.

## 7. When things go wrong

- **Host's network drops:** listeners keep the current song; the room waits 60 s for the host, then ends.
- **A listener can't get the song:** as in Listen Together, the closest match with a note, or it's skipped for that listener only.
- **The directory row outlives its room:** readers ignore rows older than 3 minutes; tapping one that is gone says "This session just ended".
- **Session full:** the card says Full and can't be tapped.
- **Sessions switched off on the server** (`SESSIONS = "0"`): the routes answer 503, the app hides Live now and Go live; existing private Listen Together is untouched.

## 8. Privacy and abuse

- **What going live shares:** each song you play (title, artist, album, art) as you play it, your station name, your "Show my name as", your schedule if you set one, and how many people listen. Not your library, your playlists, your location or your accounts. The Go live sheet says this in one sentence the first time.
- **What tuning in shares:** your "Show my name as" name (or "Listener 7") with everyone in that session, and the songs you request. The first tune-in says so in one line.
- **What the server keeps:** the station and its votes (hashes, as posts); the `live` row only while you're on the air. The room keeps nothing after it ends. No history of what was played.
- **Strangers can't type anything.** Requests are songs, reactions are fixed sets. Station and host names are the only text: checked like a post's title, hidden by votes, removable by the owner.
- **Rate limits:** joining (`JOIN_RL`, per network), requests (room: 3 waiting per listener), reactions (1 a second), station writes (`COMMUNITY_WRITE_RL`), votes (`COMMUNITY_VOTE_RL`).
- **Being blocked** (owner): no going live, no voting, no posting.

## 9. Build order (each a PR, each tested on two phones before the next)

Superseded by `2026-11-20-sessions-ux.md` §9 (seven steps). The original order:


1. **Server:** stations, votes, heard, `live`, routes, cron, moderation commands; public rooms with roles, the 50-listener cap, host-gone and pause endings, the directory writes. `SESSIONS = "0"` until the app is ready.
2. **App, listen and go live:** Community Live now + Sessions screen, tune in, Go live now, your station, the LIVE pill, votes after 2 minutes, station links.
3. **App, the DJ:** requests (request, accept, pass, Auto-accept), co-hosts and their commands.
4. **App, auto-live:** schedule editor, Always, Upcoming.
5. **Animated reactions.**

## 10. Testing

- `room.js` (pure): public rooms: roles, caps, host-gone and pause endings, request flow, co-host commands forwarded to the host and refused while the host reconnects, the private room unchanged (today's tests stay green).
- `community.js`: station rules (one per key, names, votes with the network cap and heard gate, hiding, vouching, ranking, Upcoming across time zones and DST, cleanup).
- App: the auto-live rules (windows, offline, Listen Together, Community off), the LIVE pill always shown while live, request and co-host flows against a fake room.
- On phones: Pixel 5 + Pixel 6 as host and listener, a co-host on a third, a 12-hour rollover, the host's network cut mid-song.

## 11. Rejected alternatives

- **Always-on stations from a playlist on a server clock:** cheap, but not what Sessions is (owner: live only while you play).
- **Votes per session:** a session's score would vanish every time it ended.
- **GIF search (GIPHY, Tenor):** strangers can find anything a filter misses, and search terms go to a third party.
- **Typed chat:** needs reporting, blocking and filtering for strangers' words.
- **Handing a session to a co-host when the host leaves:** the session is the host's listening; it ends with it.
- **Push reminders for Upcoming:** no push service in Stash today.

## 12. Owner's answers (2026-11-20)

1. **50 listeners** a session.
2. **Listeners see names:** everyone in a session sees everyone in it.
3. **Hosts can boot listeners** (added to §1, §2, §4).
4. **7 days** without going live deletes a station and its votes.
5. **"Station"** is the word in the app; **Sessions** is the feature.
