# Listen Together: design

**Status:** approved in brainstorming on 2026-09-24. Next step: the implementation plan.

**Scope:** friends in different places listen to the same music at the same moment. One host controls playback and can hand control to someone else. Listeners can suggest songs, send emoji reactions and see who's listening.

**Builds on:** shared mixes (`docs/superpowers/specs/2026-09-23-shared-mixes-design.md`), specifically:
- the portable track descriptor, `SharedTrack`;
- the `stash-share` Worker and its host;
- App Links;
- the "Show my name as" preference.

## 1. Decisions

| Decision | Choice | Why |
|---|---|---|
| Situation | Friends in different places, over the internet | The case nobody else serves well. The same design also covers people in one room. |
| Control | One host controls play, pause, seek, skip and the queue. The host can hand control to anyone. | Predictable, and nobody fights over the skip button. |
| Size | Up to 10 people per room | A friend group. One Durable Object can hold every connection cheaply. |
| Which recording each listener hears | The same recording as the host (matched by ISRC and IDs), at each listener's best available quality. If a listener can't get it, they get the closest match plus a note. | Keeps timing tight without dragging everyone down to the lowest-quality source. |
| Extras in v1 | Who's listening, song suggestions, emoji reactions. No typed chat. | Social features that need no moderation. |
| Architecture | A server-side room with its own clock: one Cloudflare Durable Object per session, reached over WebSockets | See §9 for the rejected alternatives. |
| Accounts | None. Only an optional display name. | Same as shared mixes. |

## 2. Server: the Listen Together room (in `infra/share-worker`)

The room lives in the existing `stash-share` Worker, under the same host. It adds a Durable Object class `ListenRoom`, bound as `ROOMS`.

The class must also be exported from `src/index.js`, and `wrangler.toml` needs two new entries:
- `[[durable_objects.bindings]]`, with `name = "ROOMS"` and `class_name = "ListenRoom"`;
- `[[migrations]]`, with `tag = "v1"` and `new_sqlite_classes = ["ListenRoom"]`.

The Worker is on the Workers Paid plan, which includes Durable Objects. The room uses the WebSocket Hibernation API, so an idle room costs nothing while its connections stay open.

**Hibernation rules:**
- A hibernating room loses anything held in memory, so the room state lives in DO storage.
- Per-connection data (`memberId`, `token`) lives in `ws.serializeAttachment`.
- The in-memory rate counters may reset after hibernation, which is acceptable.
- A Durable Object has only **one** alarm, and it serves three timers: the room-close timer, the 60-second host handover and the 8-second prepare deadline. Always schedule the earliest pending one, and on wake work out which timers are due.

**Testable core:** the room logic lives in a pure module, `src/room.js`, which takes state plus an event and returns a new state plus the messages to send. It has an injectable clock. The `ListenRoom` DO class is a thin wrapper around it: storage, sockets and the alarm. This lets `node --test` cover the logic without the Workers runtime.

### Routes

| Route | Behaviour |
|---|---|
| `POST /v1/rooms` | Body: `{ hostName? }`. Creates a room with a random code of 8 base32 characters (no ambiguous characters, retries on collision; 32^8 ≈ 10^12 codes, too many to find a live room by guessing) and a host key of 32 random bytes in base64url. The room stores only the SHA-256 of the key. Returns `{ code, hostKey, url }`. Rate-limited by a new `ROOM_RL` binding (`[[ratelimits]]`, 5 per minute per IP), kept separate from the shared-mix `CREATE_RL` budget. |
| `GET /v1/rooms/{code}` | A public preview for the Join screen: `{ hostName, memberCount, full, track? }`. Returns 404 if the room doesn't exist or has closed. |
| `GET /v1/rooms/{code}/ws` | WebSocket upgrade. The first message must be `hello`. Returns 404 for a closed room and 409 when the room is full, or when it already holds 20 sockets (so sockets that never send `hello` can't pile up). |
| `GET /l/{code}` | HTML preview page ("Join <host>'s session in Stash"), built the same way as `/m/{id}`, with "Open in Stash" and "Get Stash" buttons. |

**Join rate limit:** the three `{code}` routes share a `JOIN_RL` binding (`[[ratelimits]]`, 60 per minute per IP), checked before any Durable Object is touched, so nobody can walk the code space looking for live rooms. Over the limit, the API routes answer a JSON 429 with `Retry-After: 60` and the page an HTML 429.

### Room state

The room is the single source of truth. Every change increments `rev`.

```
{
  rev,
  host: memberId,
  track: SharedTrack?,
  trackKey,                        // bumps on every song change
  timeline: { positionMs, atRoomMs, playing },
  queue: [SharedTrack],            // the host's upcoming songs, so a new host can continue
  members: [{ id, name, joinedAt, status: ok | buffering | unavailable | drifting }],
  suggestions: [{ id, from, track }],
  phase: playing | preparing(trackKey, deadlineMs)
}
```

### Limits and lifetime

- **Room size:** at most 10 members.
- **Closing:** the room closes 5 minutes after the last member leaves, or 12 hours after it was created, whichever comes first. It uses a DO alarm, and all state is dropped when it closes.
- **Host handover:** if the host is disconnected for 60 seconds, the longest-joined connected member becomes host. The host key only proves the original host; after a handover, host rights are tied to the new host's connection (`memberId` plus a per-connection token issued in `welcome`).
- **Rate limits:**
  - a reaction at most once per second per member (extras are dropped);
  - at most 3 pending suggestions per member;
  - at most 20 messages per second per connection, after which the connection is closed.

## 3. The message protocol

All messages are JSON with a `t` field giving the message type.

### Phone → room

| `t` | Sent by | Payload |
|---|---|---|
| `hello` | anyone | `{ name?, hostKey?, resumeToken? }`. `resumeToken` is the private per-connection `token` from `welcome`, and it rejoins as the same member after a drop. A `memberId` alone is never enough, because every member can see everyone else's. |
| `ping` | anyone | `{ c }`, the client clock in ms |
| `load` | host | `{ track, positionMs, queue }`. Starts the ready handshake (§4). |
| `play`, `pause` | host | `{}` |
| `seek` | host | `{ positionMs }` |
| `queue` | host | `{ queue }` |
| `status` | anyone | `{ status }`: `ready`, `buffering`, `unavailable` or `drifting`. `ready` is stored as the member status `ok`. |
| `suggest` | listener | `{ track }` |
| `suggestion` | host | `{ id, action: add \| dismiss }` |
| `react` | anyone | `{ emoji }`, one of six fixed emoji |
| `makeHost` | host | `{ memberId }` |
| `end` | host | `{}` |

### Room → phone

| `t` | Payload |
|---|---|
| `welcome` | `{ memberId, token, state }` |
| `pong` | `{ c, r }`: `c` is echoed back, `r` is the room clock |
| `state` | `{ state }`: the full state. Sent on join, on host change, and whenever the phone's `rev` has fallen behind. |
| `timeline` | `{ rev, trackKey, positionMs, atRoomMs, playing }`: the frequent, small update |
| `prepare` | `{ trackKey, track, deadlineMs }` |
| `members` | `{ members }` |
| `suggestions` | `{ suggestions }` |
| `reaction` | `{ from, emoji }` |
| `ended` | `{ reason }` |

## 4. Keeping playback in sync

### Clock offset

A phone sends 5 pings on connect and 1 ping every 30 seconds after that. For each reply it records the round trip `rtt = now − c` and the clock offset `offset = r − (c + rtt / 2)`. It uses the offset from the sample with the smallest round trip seen in the last 5 minutes. Room time on the phone is `now + offset`.

This fixes YumaPlayer's reversed-sign bug, and the logic is unit-tested with fixed numbers.

### Starting a new song (the ready handshake)

1. The host sends `load`.
2. The room broadcasts `prepare` with a deadline 8 seconds away.
3. Each phone resolves the descriptor through the normal stream chain, buffers the song at the start position, then reports `status: ready`.
4. When every connected member is ready, or the deadline passes, the room sets `timeline` to `{ positionMs, atRoomMs: roomNow + 500, playing: true }` and broadcasts it.
5. A phone that isn't ready yet joins late by seeking to the computed position.

### Play, pause and seek timing

- **`play`, and `seek` while playing:** the room sets `atRoomMs = roomNow + 400` with the target `positionMs`. Each phone seeks to the target straight away while held paused, then starts at `atRoomMs`, so nobody arrives late while buffering.
- **`pause`:** the room freezes `positionMs` at the position computed for `roomNow`, sets `atRoomMs = roomNow` and `playing = false`. Every phone pauses and seeks to that position.
- **`seek` while paused:** only updates `positionMs`.

### Song boundaries: nobody's player advances on its own

During a session, every phone's player, the host's included, holds **only the current song**. That means Media3 never moves on to a next item by itself. Song changes happen only through the room:
- **The song ends on its own:** the host's app sends `load` for the next song in its queue.
- **The host taps skip or previous:** the host's app sends `load`. The host's own player does not start the song early; it waits for the ready handshake and starts at `atRoomMs`, like everyone else.
- **The queue runs out:** if the host has autoplay radio on, read directly from `autoplayRadioPreference.enabled` (not `shouldAutoplayRadio`, which only returns true while a song is still playing), the host's session code builds a station with `RadioStationGenerator.start(RadioSeed.Song(...))`. That lives in `core/data/radio`, the same source `PlayerRepositoryImpl.startRadio` uses. The host's code drops the seed song from the first batch, matching it by title and artist the way `startRadio`'s `keepCurrent` does. It turns the rest into `SharedTrack`s, sends them with `queue`, then `load`s the first one. A per-song "already tried radio" guard stops it retrying in a loop. If autoplay radio is off, the session idles, paused at the end of the last song.
- **Joining mid-song:** a phone that joins, or reconnects, while a song is playing prepares that song, then seeks to `expected(t)` for the whole second of room time `t` at which it starts, and starts there. It does not seek to the value computed when it joined, which would leave it behind by however long preparing took.

### Drift correction

Once a second while playing, each phone works out the expected position, `expected = positionMs + (roomNow − atRoomMs)`, and the error, `e = actual − expected`. Then:
- **`|e|` under 40 ms:** do nothing, at speed 1.0.
- **40 ms to 1 s:** set the playback speed to `1 − clamp(e / 2000, −0.03, 0.03)`, a speed change that keeps pitch, until `|e|` drops below 20 ms. Then go back to speed 1.0.
- **Over 1 s:** seek to `expected`.

After a correction, the phone reports `drifting` if `|e|` stays above 250 ms for 10 seconds.

### Which recording is played

A listener must not use `ensureTrackPersisted`. Its fuzzy title-and-artist match can return the listener's own row: a different edit, possibly their downloaded file, and it drops the host's ISRC.

Instead, the session uses an **exact persist**. It matches an existing row only by `yt` id, then Spotify URI, then an exact `isrc`. With no exact match it inserts a new stream-only row that carries the descriptor's `isrc`, `sp` and `yt`.

The row is then played through the existing resolver. Lossless looks it up by ISRC first, and YouTube uses the `yt` id when it's present. Both paths are confirmed in the resolvers. A downloaded local file is used only when the row was matched exactly, because then it is the same recording.

**One session-active flag gates everything else that reacts to the player.** `ListenTogetherController.active` is a `StateFlow<Boolean>` readable from both the service and `PlayerRepositoryImpl`, which runs on the controller side. While it's true:
- These are switched off: the stream-error auto-skip (`onPlayerError`), `maybeSkipOfflineStreamOnly`, `recoverOrStop`, the autoplay-radio watcher, the radio and library-shuffle growers, and `prefetchNextTrack`. A failed stream therefore leads to `unavailable` and silence (§6). The phone never drifts off into its own next song, and it never adds radio songs to the one-song player.
- `playbackStateStore.saveQueue` and `savePosition` are skipped. The user's own queue is snapshotted to storage when the session starts, restored when it ends, and still restored at the next app start if the app was killed mid-session.

If the listener's resolved duration differs from the descriptor's `d` by more than 2 seconds, their Now Playing shows "Your version may be a few seconds off". No time-stretch alignment is attempted.

### Crossfade

Crossfade is suspended while a session is active, on every member's phone, and restored afterwards. This is an **in-memory, session-scoped override** inside the playback service. It never writes the user's crossfade preference, so a crash can't leave crossfade switched off for good.

## 5. The app

**Layering:**
- The session engine lives in `core/media`, inside `StashPlaybackService`, and drives the service's own `ExoPlayer` directly.
- The UI lives in `feature/nowplaying`, and the Join screen goes there too, beside Now Playing. It talks to the session only through a `ListenTogetherController`, a Hilt singleton that exposes a `StateFlow` and command functions. The service binds it.
- `PlayerRepository` is **not** used to drive session playback.

**New player abilities,** used only by the session engine:
- `prepareAt(track, positionMs)`: load a single item, seek, prepare, and hold with `playWhenReady = false`;
- `startAt(roomMs)`: start playing at a given room time;
- `setSpeed(x)`: speed change via Media3 `PlaybackParameters`, with pitch preserved. Sonic is already at the end of the `StashRenderersFactory` audio chain.
- snapshot and restore of the user's own queue and position;
- the crossfade override;
- honouring the session-active flag. The auto-skip, radio and saving gates themselves live in `PlayerRepositoryImpl` and read the same flag.

**One place catches every playback command.** While a session is active, the service wraps its session player in a `ForwardingPlayer`, and that wrapper is what the `MediaSession` exposes. It catches play, pause, seek and skip from every source: Now Playing, the notification, the lock screen, headphone and Bluetooth buttons, and Android Auto.
- **On the host,** each command becomes a room command. The local player only changes when the room replies.
- **On a listener,** seek, skip and queue changes are ignored. Pause pauses only their own phone, and Play rejoins the room where it is now. A phone paused locally (Pause, an unplug, a call) stays paused until the user acts: new songs load but don't play until Play, **Paused — tap to rejoin**, or the end of a transient focus loss. The media notification shows only a **Leave** action.

**Staying alive:** while a session is active, the service keeps an ongoing "Listening together" foreground notification, even while the music is paused. It also suppresses `performIdleStop` and the `onTaskRemoved` stop. Otherwise Android could kill a paused listener, or a host in a long pause, and drop them from the session.

**Pieces:**

| Piece | Responsibility |
|---|---|
| `RoomClient` | The OkHttp WebSocket: connect, send `hello`, reconnect with backoff (1, 2, 4, 8 and 15 s, for 2 minutes in total, then give up), and parse messages. |
| `ClockSync` | Pure logic: turns ping samples into an offset. |
| `DriftController` | Pure logic: turns an error into an action (none, a speed change, or a seek). |
| `ListenTogetherSession` | Lives in `core/media`, inside the playback service. Wires `RoomClient` to the service's `ExoPlayer` through the new player abilities above. Publishes session state through `ListenTogetherController`, and sets the member's own queue aside and restores it when they leave. |
| Host adapter | While hosting, the host's play, pause, seek and skip actions and queue changes are sent to the room as commands. The local player changes only when the room's reply arrives, so the host follows the room timeline too. Song-boundary rules are in §4. |
| UI | A **Listen Together** entry in the Now Playing menu (start, or "Invite" when already hosting). A Join screen for `/l/{code}` links, reusing the shared-mixes App Link filter with a new `pathPrefix="/l/"`. A who's-listening bar in Now Playing. A suggestions tray for the host. A **Suggest** item in the track menus while in a session. A reaction button, with emoji that float up. A **Leave** or **End session** button. |
| Links | `ShareLinks` gains `Parsed.Room(code)` for `https://…/l/{code}`. |

While a listener is in a session, their Now Playing hides play, pause, skip and seek and shows **Leave** instead. Volume stays their own.

## 6. When things go wrong

| Situation | Behaviour |
|---|---|
| Connection drops | Keep playing from the last known timeline and show "Reconnecting…". Reconnect with `resumeToken`, then apply the latest `state`. |
| A listener can't resolve the song | Report `unavailable`, stay silent for that song, and show "This song isn't available to you". The next `prepare` brings them back in. |
| The room is full or closed | The Join screen shows "This session is full" or "This session has ended". |
| The host's app is killed | The 60-second handover described in §2. |
| The host taps **End session** | Every phone gets `ended`, sees "Session ended", and gets its own queue back, paused. |
| A message is malformed or too frequent | It's ignored. Repeated abuse closes the connection. |

## 7. Privacy

- The room sees display names, song descriptors, room codes and IP addresses (the IPs are used only for rate limiting and are not stored).
- Nothing is kept once the room closes.
- The README's "What Stash talks to" entry for `stash-share` gets a sentence about Listen Together.

## 8. Testing

**Worker**, using `node --test` against the pure `src/room.js` module, with an injectable clock and fake sockets. The tests cover:
- create and join;
- the host key check;
- limits (10 members, reaction and suggestion caps);
- the ready handshake, both when everyone is ready and when the deadline passes;
- host handover after 60 seconds;
- `makeHost`;
- `end`;
- the alarm closing the room;
- `resumeToken` rejoin, including a check that someone else's `memberId` can't take over their slot.

**App:**
- unit tests for `ClockSync` and `DriftController` with fixed numbers;
- a round-trip test of the message format;
- tests for `ListenTogetherSession` with a fake `RoomClient` and a fake player: queue set aside and restored (including after a simulated process death), listener commands ignored when they come through the `ForwardingPlayer` (the headphone and notification path), host commands sent to the room, crossfade suspended, and the autoplay and saving gates off while the session is active.

**Device:** the Pixel 6 Pro and the Pixel 5 test rig in one session. Check, against a high-speed camera or a stopwatch app, that they stay within about 50 ms of each other, including after a seek, a pause and resume, and a song change.

## 9. Rejected alternatives

- **The room only relays the host's messages**, as YumaPlayer does. Timing would depend on the host's connection, and the session would suffer or die when the host drops.
- **Phones connect to each other directly (WebRTC).** It needs NAT traversal and a relay fallback anyway, which is heavy for the tiny amount of data involved.
- **Local-network mode.** It isn't needed for the main situation (friends in different places), and it would double the testing.
- **Each phone picks its own best match.** Different edits and intros could put listeners seconds apart.
