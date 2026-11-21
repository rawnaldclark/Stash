# Sessions: the room (UX design)

Companion to `2026-11-20-sessions-design.md` (the system). That spec says what a session is; this one says why anyone stays.

Owner, 2026-11-20: "the ui/ux of the sessions needs to be well thought out. it needs to be a place people are willing to hang out at." Agreed for v1, all of them: quick phrases and a live feed, saving songs with crowd counts, following stations, open decks (DJ turns), last song and recap, regulars, the host's vibe line, crowd favourites.

## 1. What makes a place worth hanging out in

Each rule below is tested against these. A feature that serves none of them is cut.

1. **It feels alive.** You see the people, and something is always moving: arrivals, reactions, the next pick.
2. **You're someone there.** A name and a colour; the host and the regulars know you.
3. **Joining in costs almost nothing, and there's always a next step.** Listen → react → say something → save a song → request → hear your pick played with your name on it → take a turn on the decks → co-host.
4. **What you put in lands where everyone sees it.** "Ben's pick" on the song, "12 people loved Ben's pick" in the feed and the recap.
5. **You leave with something:** songs you found, and the songs you got played.
6. **There are moments:** the crowd favourite, the last song, the recap.
7. **You know when to come back:** the stations you follow, their hours.
8. **Hosting feels like DJing, not admin.** The crowd is visible from the booth; chores have an automatic setting.

**Kept out of v1:** free typed chat, voice, uploaded pictures, profiles, direct messages, tips. Each is a moderation or safety job Stash has no staff for. Quick phrases (§4.3) give the room a voice without them.

## 2. The ladder, step by step

| Step | What the listener does | What everyone sees |
|---|---|---|
| Arrive | Taps a Live card | Feed: "Ben joined" (batched: "Ben and 2 others joined"); a regular's arrival says "Ben is back" |
| React | Taps an emoji or animation | It rises from Ben's face in the crowd |
| Say | Taps a phrase | Feed: "Ben · Who is this?" |
| Save | Taps the heart on the song | "9 saved this" on the song; nothing personal |
| Request | Searches, taps Request | Up next shows "Ben's request · waiting" to Ben; the host gets it |
| Played | The host or a co-host plays it | Song says "Ben's pick"; feed "Ben's pick is on"; reactions to it count toward Ben's recap |
| Decks | Taps Join the decks (when open) | Ben appears in the booth; his song plays on his turn |
| Co-host | Host makes Ben a co-host | Ben's ring turns to the co-host ring; feed "Ben is co-hosting" |

## 3. Community: finding a room

Community's Home section, top to bottom:

1. **Your stations** (only if you follow any): live ones first with their LIVE badge and listener count, then the next ones on air ("Coffee & Jazz · 8:30"). Small round cards: art of the song playing (or the station's colour when off air), the station's name.
2. **Live now**: wide cards, each one a window into a room:
   - the song playing (art), LIVE and the listener count;
   - the station's name and its **vibe line** ("90s house & garage, requests open");
   - **the crowd**: the first 4 faces in their colours, "+8";
   - a mark when it's a station you follow, and the station's score.
   Ranked as the system spec says (votes + listeners now). See all opens **Sessions** (Live, Upcoming, Your station).
3. **Top this week** (today's Community posts).

Tapping a Live card is joining: no preview step, no confirm. The music starts at the room's position within about a second, the room opens, and the phrase bar's "Hi all" is highlighted for the first 10 seconds.

## 4. The room (Now Playing, in a session)

The room replaces Now Playing while you're in a session. From the top:

1. **Header:** collapse (the session carries on in the mini player), the station's name and vibe line, LIVE · count, **Follow** (Following once tapped), and a menu (Leave, Share station, About this station).
2. **The song:** the art (smaller than the normal Now Playing, so the crowd fits), title, artist, and whose it is: "Ben's pick", "On the decks: Lee", or nothing for the host's own. Under it: the reaction count for this song, "9 saved this", and the **heart** to save it to your Liked Songs. A crowd favourite (§4.5) wears its badge here.
3. **The crowd:** one band. The **booth** on the left: the host (crown), co-hosts, DJs on the decks, each with their ring. Then the **floor**: listeners' faces, regulars with a small star, "+12" past what fits; tap it for everyone's names. Reactions rise from the face that sent them; a face that reacts bumps once.
4. **The feed:** the last few lines, newest at the bottom, older ones fading: arrivals, phrases, "Ben's request is up next", "Lee is on the decks", "Redbone is a crowd favourite", "Last song". Held only in the room: nothing is kept after the session.
5. **Up next:** one line, the next song and whose it is. Tap: the whole line ahead, with whose pick each is, and where your request stands ("Your request · 3rd").
6. **Your bar:** **Request** and **React**. React opens one tray with everything you can send: the six emoji, the animated reactions and the phrases (one place to look, one tap from anything). When the decks are open, **Join the decks** takes Request's place (requests are still in the Up next sheet).

### 4.1 Host presence

The host is either **in the booth** (Stash open on screen) or **away** (listening with the phone in a pocket, as at work). The header shows "Rawn is in the booth" or "Rawn is away · requests wait" (or "· requests play automatically" with Auto-accept). Listeners always know whether a request will be seen.

### 4.2 Requests

- Search inside the room (Stash's own search, results with a Request button).
- Up to 3 waiting per person; a fourth says "You have 3 requests waiting".
- Waiting requests show in Up next to their requester only; once accepted they show to everyone with the requester's name and their place.
- Passed: the requester sees "Rawn passed on Pink + White" in their own view only. Nobody else sees a pass.

### 4.3 Say: quick phrases

A fixed set, tapped from the React tray, shown in the feed under your name: **Hi all** · **Who is this?** · **Tune!** · **Run it back!** · **Turn it up** · **More like this** · **Chills** · **Thanks for the set** · **Good morning** · **Good night**.
- "Who is this?" also opens the song card for the one who asked (artist, album, Save, Go to artist).
- One phrase every 3 seconds per person; repeats of the same phrase within 10 seconds are folded ("Tune! ×4").
- Translated with the app; the room sends ids, never text.

### 4.4 Save and crowd counts

- The heart saves the song to your Liked Songs (and Download mode rules apply as everywhere).
- The room counts saves per song ("9 saved this"); who saved is never shown.
- The recap lists the songs you saved.

### 4.5 Crowd favourite

A song becomes a **crowd favourite** when at least a third of the room, and at least 3 people, react to it while it plays. It gets the badge, a feed line, and a place in the recap. Several songs can be favourites in one session.

### 4.6 Leaving

Collapse keeps you in (mini player shows "Rawn's Basement · LIVE"). **Leave** (menu, or the mini player's close) ends it. Leaving after 2 minutes or more shows one small sheet, once per session: rate the station (▲ / ▼, the station vote), Follow if you don't already. Skippable.

## 5. Open decks (DJ turns)

- The host turns the decks on from the booth. Up to **5 on the decks** (the host plus 4); more people wait in a line.
- **Join the decks:** you pick your next song (search) and take the next free spot, or the line. On the decks you always keep one song ready; the bar shows "Your turn in 2 songs".
- **Turns:** one song each, in the booth's order, starting after the host's current song. The host's own queue is the host's turn. A DJ with no song ready when their turn comes is skipped; skipped twice in a row, they're moved off the decks ("Lee left the decks").
- **Requests** still go to the host and co-hosts, and play on the host's turn.
- **Stepping down:** any DJ can leave the decks; the host can take anyone off.
- The decks close when the host turns them off (everyone's ready song is dropped, with a feed line) or when the session ends.

## 6. Last song and the recap

- The host's **End** offers **End now** or **End after this song**. After this song: everyone sees a "Last song" banner and a feed line; the session ends when it finishes.
- **The recap** (everyone who was in at the end, or who left after 10 minutes or more, opened from the "ended" screen or the notification):
  - For a listener: the station, how long you listened, how many songs played, the crowd favourites, your picks that played and the reactions they got, the songs you saved, Follow, rate the station.
  - For the host: how long, peak and total listeners, new followers, requests played, decks turns, crowd favourites, the songs the crowd saved most.
  - Recaps are made on the phone from what the room sent during the session, and kept on the phone for 7 days; the server keeps none.

## 7. Regulars

- After 3 sessions of the same station, at least 10 minutes each, a listener is a **regular** there: a small star on their face, "Ben is back" when they arrive.
- The host sees how often: tapping a face in the crowd says "Ben · 5th time here".
- Kept by the server per station (§9), deleted with the station or after 60 days without a visit.

## 8. The booth (the host's view)

The same room, with the host's tools in place of the listener's bar:
- the player controls (play, pause, skip, seek) as usual;
- **Up next** with requests waiting at the top (Play next / Add to queue / Pass), the decks' order, and your own queue;
- **Decks:** open or closed, who's on them, take someone off;
- **Crowd:** everyone, Make co-host, Boot; a regular's count;
- a **crowd energy** line in the header: reactions and phrases in the last minute, so the host can feel the room at a glance;
- **End** (now, or after this song).

Away (phone in a pocket): requests wait or auto-play as set; a quiet notification "2 requests waiting" once, not per request.

Going live asks for the vibe line once ("What's playing tonight?", optional, 60 characters), prefilled from last time and editable from the booth.

## 9. What this adds to the system spec

- **D1:** `station_follows (station_id, follower, at)`; `station_regulars (station_id, listener, sessions, last_at)`; `stations.vibe` (≤ 60 characters, checked like the name); follower count on `StationSummary`. `GET stations?ids=…` for Your stations.
- **Room messages:** `say {phrase}`, `saved {trackKey}` (counts only), `decks {open}`, `deckJoin {track}`, `deckLeave`, `deckPick {track}`, `deckRemove {memberId}`, `lastSong`, `presence {booth|away}`, and the room's `feed` lines, `songStats {trackKey, reactions, saves, favourite}`, `decksState`.
- **Feed and stats** live in the room's state only and go with it.
- **Build order** (replaces the system spec's §9): 1 server; 2 listen, go live, the room, Live now, follow; 3 requests, co-hosts, booting, presence; 4 phrases, feed, saves, crowd favourites, animated reactions; 5 last song, recap, regulars; 6 auto-live and Upcoming; 7 open decks. The build plan (`docs/superpowers/plans/2026-11-20-sessions.md`) splits step 1 into two PRs and ships the server parts of steps 4, 5 and 7 with those steps.

## 10. Measuring it (the scoreboard)

Counted by the room and the server as totals only, no person: median minutes a listener stays, share of listeners who react, say, save or request, requests played per session, returning listeners per station per week, follows per session. "A place people hang out" means minutes stayed and returning listeners go up; each release of Sessions is judged on those two.
