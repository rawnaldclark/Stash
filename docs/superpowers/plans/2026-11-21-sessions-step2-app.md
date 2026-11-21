# Sessions step 2: listen, go live, the room, Live now, follow (app)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Test first in every task: write the test, see it fail, write the code, see it pass, commit.

**Goal:** The app's half of what step 1 put on the server: tune in to a station from Community's Live now, go live from Now Playing, the room a session plays in, follow a station, vote on it after 2 minutes of listening, and open a station's link. All of it stays dark. The app shows none of it while the server answers `sessions_off`, and the server stays at `SESSIONS = "0"` until the owner turns it on (after step 3 at the earliest).

**Read first:**
- The build plan `2026-11-20-sessions.md`: its rules for every step and its decisions bind here, and its step 2 is the outline this file details.
- The UX spec `2026-11-20-sessions-ux.md`: §3, §4 (its subsections §4.1–§4.5 are later steps), §4.6 and §8. The system spec: §2, §4, §7 and §8.
- **The contract:** `infra/share-worker/README.md`, "Sessions" and "Sessions protocol". It's what the deployed server does, so where it differs from the specs, it wins.
- The mockups (the Design canvas, private to the owner): Home, Sessions, the room, the booth, Go live, Your station.
- Code:
  - `core/model/.../listen/RoomProtocol.kt`;
  - `core/media/.../listen/ListenTogetherSession.kt` and `ListenTogetherController.kt`;
  - `core/data/.../listen/RoomApiClient.kt` and `RoomClient.kt`;
  - `core/data/.../community/` (`CommunityApiClient`, `CommunityRepository`, `CommunityKeyStore`, `CommunityHeard`);
  - `feature/community/` (`CommunitySection`, `CommunityViewModel`, `CommunityVotePill`);
  - `feature/nowplaying/` (`NowPlayingScreen`, `MiniPlayer`, `listen/`);
  - `app/.../MainActivity.kt` and `navigation/StashNavHost.kt`.

**Where:** two PRs, stacked like step 1's:
- **2a, the engine and the data:** `.worktrees/sessions-engine`, branch `feat/sessions-engine` from `origin/master`, tasks 1–14. Nothing anyone sees changes.
- **2b, the screens:** `.worktrees/sessions-listen`, branch `feat/sessions-listen`, stacked on `feat/sessions-engine`, tasks 15–26.

2a changes the engine every Listen Together room runs on, so it's reviewed and tried on phones with private rooms before any screen depends on it.

After every task, run the unit tests of each module it touched: `./gradlew :<module>:testDebugUnitTest --max-workers=3`, or `test` for a plain Kotlin module. Private Listen Together's tests must stay green throughout: `RoomProtocolTest`, `RoomClientTest`, `RoomApiClientTest`, `ListenTogetherSessionTest`, `PlayerRepositoryListenTogetherTest`.

## Decisions this step makes

1. **Two protocol versions, never one.** The app says `v: 3` in every hello. A private room still answers `v: 2`, so every capability check compares with a separate `ADDS_VERSION = 2`. If it compared with the hello's version, guest adds would quietly switch off in every private room.
2. **One `Station` class** for the README's `Summary` and every shape built on it (`Own`, `following`'s entries, a station's page). Each route's extras are optional fields.
3. **The app learns whether Sessions is on from the server.** It does that from answers: `sessions_off` means hidden, any answer from a Sessions route means shown. One `GET live?limit=1` asks, once per app run, before anything else has. Until it knows, nothing about Sessions shows.
4. **A session's host can't hand it over.** Their Leave is End, and `MakeHost` is never sent in a session.
5. **Until step 3's Request exists**, a session listener's song menus show neither Play next nor Add to queue.
6. **Reactions are drawn at most 4 a second.** A full room sends each phone about 50 reaction frames a second (step 1's load check). The rest still count for the song, but don't each get a floater.
7. **Phones test against a Worker on this PC** (task 2). It runs with `SESSIONS` on, through a debug-only override of the share API that a release build can't take. Pointing phones at production with Sessions on instead is the owner's call.

---

## 2a: the engine and the data

### Task 1: Worktree and a green baseline

- [ ] Survey `.worktrees/` and `gh pr list` (the build plan's rules). Then `git fetch && git worktree add .worktrees/sessions-engine -b feat/sessions-engine origin/master` and copy `local.properties` in.
- [ ] Baseline: the unit tests of `:core:model`, `:core:data`, `:core:media`, `:core:ui`, `:feature:community`, `:feature:nowplaying` and `:app` are green before any change. Master CI is green, so red after this is yours.

### Task 2: A Worker on this PC for phone tests

**Files:** modify `core/model/.../share/ShareLinks.kt` (`ShareConfig`), `app/build.gradle.kts`, `app/src/main/kotlin/com/stash/app/StashApplication.kt`. Create `infra/share-worker/scripts/session-peer.mjs`. Test `ShareConfigTest`.

This copies `sync.baseUrl`, the existing way a debug build talks to a local stash-sync Worker. The debug network security config needs nothing: `SyncDebugNetworkConfigTask` already allows cleartext to `127.0.0.1` and `localhost`, which the add-ons' phone runs over `adb reverse` use.

- [ ] Test first:
  - without an override, `ShareConfig.API_BASE_URLS` is `[BASE_URL, LEGACY_BASE_URL]`;
  - with one, it's only the override;
  - `HOSTS`, the hosts a link may come from, never changes.
- [ ] `ShareConfig`:
  - `PRODUCTION_BASE_URLS` holds today's two, and `HOSTS` is built from it;
  - `@Volatile var apiOverride: List<String>?`;
  - `API_BASE_URLS` becomes a getter: the override, or else the production list.
- [ ] Debug builds only: a `SHARE_BASE_URL` `buildConfigField` read from `local.properties`' `share.baseUrl`, with no environment fallback, so CI can never set it. It's empty when absent, and always `""` in release.
  - `StashApplication` sets `ShareConfig.apiOverride` in its `init` block, when `BuildConfig.DEBUG` and the field isn't blank. That's before Hilt builds any client. `ShareApiClient`, `CommunityApiClient`, `RoomApiClient` and `RoomClient` each copy `API_BASE_URLS` once, when they're made.
  - It logs once that it's on.
  - While it's set, everything on the share API goes to the local Worker: mixes and song links, Community, Listen Together.
- [ ] `scripts/session-peer.mjs`, a scripted second phone. Each run makes a fresh random Community key and logs every frame. Modes:
  - `host <origin>`: makes a station, opens its session, plays two songs, answers each `prepare`, and prints the station's id. Ctrl-C sends `end`.
  - `listen <origin> <stationId>`: reads the station's room (`GET stations/{id}`), tunes in with `v: 3`, and reacts every 10 s until `ended`.
  - `private-host <origin>` and `private-guest <origin> <code>`: a private room with today's `v: 2`. This is the deploy check's peer, kept so a private-room run can be repeated.
- [ ] The run book, here and in both PRs:
  1. In `infra/share-worker`:
     - install dependencies with `npm install --no-package-lock`;
     - create a `.dev.vars` holding a random `COMMUNITY_SALT` (git-ignored);
     - apply the migrations locally: `npx wrangler d1 migrations apply stash-community --local`.
  2. Check port 8811 is free (`Get-NetTCPConnection -LocalPort 8811`). On 2026-11-21, port 8799 was held by another session's `wrangler dev`, and Windows let a second one bind it without a word.
  3. `npx wrangler dev --ip 127.0.0.1 --port 8811 --var SESSIONS:1`.
  4. Put `share.baseUrl=http://127.0.0.1:8811` in the worktree's `local.properties`, then `assembleDebug`. Take the line out before any build meant for production.
  5. On each phone, `adb -s <serial> reverse tcp:8811 tcp:8811`. Afterwards remove only that rule: `adb -s <serial> reverse --remove tcp:8811`. Never `--remove-all`, which cuts the rig's internet.
  6. The rig streams songs over its USB tunnel. While the PC's VPN is on, the tunnel needs `-d 10.46.132.1` (memory `infra_pixel5_usb_internet_gnirehtet`).
- [ ] Commit `test(sessions): a Worker on this PC for phone tests, and a scripted second phone`.

### Task 3: Protocol v3's messages

**Files:** modify `core/model/.../listen/RoomProtocol.kt`; test `RoomProtocolTest`.

The README's "Sessions protocol" lands whole here, including step 3's messages, so the wire format changes once. `VERSION` stays 2 until task 4 moves the engine.

- [ ] Tests first:
  - a hello with `communityKey` sends it, and leaves it out when null;
  - each new frame decodes from the README's own shape;
  - a private room's state, which has no `session`, still decodes;
  - an unknown `t` is still null.
- [ ] Add:

```kotlin
/** A room or member that knows guest adds. Compare capabilities with this, never with VERSION (a private room answers 2). */
const val ADDS_VERSION = 2

// ClientMessage
Hello(…, val communityKey: String? = null)  // a session listener's, on every hello; never to a private room
@SerialName("request") data class Request(val track: SharedTrack)
@SerialName("requestAction") data class RequestAction(val id: String, val action: String)  // next | queue | pass
@SerialName("autoAccept") data class AutoAccept(val on: Boolean)
@SerialName("cohost") data class Cohost(val memberId: String, val on: Boolean)
@SerialName("boot") data class Boot(val memberId: String)
@SerialName("presence") data class Presence(val state: String)  // booth | away
@SerialName("command") data class Command(val action: String)  // a co-host's "skip"

// The room's view of a session, in the welcome's state and (its fields flat) in each `session` frame
@Serializable data class SessionInfo(
    val station: String, val listeners: Int = 0, val cohosts: List<String> = emptyList(),
    val autoAccept: Boolean = false, val presence: String = "booth",
)
@Serializable data class RoomRequest(val id: String, val from: String, val track: SharedTrack)
RoomState(…, val session: SessionInfo? = null)

// ServerMessage
@SerialName("session") data class Session(
    val station: String, val listeners: Int = 0, val cohosts: List<String> = emptyList(),
    val autoAccept: Boolean = false, val presence: String = "booth",
)
@SerialName("requests") data class Requests(val requests: List<RoomRequest>)
@SerialName("requestAnswer") data class RequestAnswer(val state: String)  // queued | waiting | full
@SerialName("requestPassed") data class RequestPassed(val track: SharedTrack, val by: String)
@SerialName("refused") data class Refused(val what: String, val reason: String)
@SerialName("command") data class Command(val action: String, val from: String, val trackKey: Int)  // to the host
```

- [ ] `object EndedReason` holds the strings room.js sends: `host_ended`, `host_left`, `paused`, `max_age`, `station_removed`, `replaced`, `closed`, `booted`.
- [ ] Commit `feat(sessions): protocol v3's messages in the app`.

### Task 4: The app says v3, private rooms unchanged

**Files:** modify `ListenTogetherSession.kt`, `RoomProtocol.kt`; tests `ListenTogetherSessionTest`, `RoomProtocolTest` (its hello test now expects `"v":3`).

- [ ] Tests first, against today's `FakeConnection`:
  - a private room answering `v: 2` still takes a guest's Play next straight in;
  - the host still sees the "Let guests add songs" switch (`roomKnowsAdds`);
  - a v2 host isn't "outdated" (`hostAppOutdated` false);
  - the hello says `v: 3`.
- [ ] Every check that compares a room's or member's `v` with `RoomProtocol.VERSION` today compares with `ADDS_VERSION` instead. That's `canAddDirectly`, `deliver`, `unqueue`, `setGuestsAdd`, and `publish`'s `roomKnowsAdds`, `guestsAdd` and `hostAppOutdated`. Then set `VERSION = 3` (keep in sync with `PUBLIC_V` in room.js). The hello keeps sending `VERSION`.
- [ ] Commit `feat(sessions): the app says v3, private rooms unchanged`.

### Task 5: A session's refusals are final

**Files:** modify `core/data/.../listen/RoomClient.kt`; test `RoomClientTest`.

- [ ] Tests first:
  - closes 4426, 4401 and 4403 end the connection, with no reconnect, each with its reason;
  - 4409 is still `FULL`;
  - any other close still reconnects.
- [ ] `CloseReason` gains `UPDATE` (4426), `KEY` (4401) and `BOOTED` (4403), and the class comment lists them.
- [ ] Commit `feat(sessions): a session's refusals are final`.

### Task 6: Station models

**Files:** create `core/model/.../community/Stations.kt`; test `StationsTest`.

- [ ] Tests first. Decode each shape in the README's "Contract for the app":
  - a station without a key (no `myVote`, `mine`, `heard` or `following`), and one with a key;
  - `Own`;
  - `following`'s entries, with `live` and `next`;
  - `live`'s sessions, with `crowd`.

```kotlin
@Serializable
data class Station(
    val id: String, val name: String, val hostName: String? = null, val vibe: String? = null,
    val up: Int = 0, val down: Int = 0, val followers: Int = 0,
    val myVote: Int = 0, val mine: Boolean = false, val heard: Boolean = false, val following: Boolean = false,  // with a key
    val autoLive: String? = null, val schedule: StationSchedule? = null, val hidden: Boolean = false,  // Own
    val removed: Boolean = false, val lastLiveAt: Long? = null, val deleteAt: Long? = null,           // Own
    val next: OnAirWindow? = null,   // on a schedule: stations/{id}, following, upcoming
    val live: StationLive? = null,   // following
) { val score: Int get() = up - down }

@Serializable data class StationSchedule(val tz: String, val windows: List<ScheduleWindow>)
@Serializable data class ScheduleWindow(val day: Int, val start: String, val end: String)
@Serializable data class OnAirWindow(val start: Long, val end: Long)
@Serializable data class CrowdFace(val i: String? = null, val id: String)
@Serializable data class StationLive(
    val room: String, val track: SharedTrack? = null, val listeners: Int = 0,
    val paused: Boolean = false, val full: Boolean = false, val crowd: List<CrowdFace> = emptyList(),
)
@Serializable data class LiveSession(
    val station: Station, val room: String, val track: SharedTrack? = null, val listeners: Int = 0,
    val paused: Boolean = false, val full: Boolean = false, val crowd: List<CrowdFace> = emptyList(), val startedAt: Long = 0,
)
@Serializable data class StationPage(val station: Station, val live: StationLive? = null)
@Serializable data class FollowState(val following: Boolean, val followers: Int)
```

- [ ] Commit `feat(sessions): station models`.

### Task 7: The stations client

**Files:**
- Create `core/data/.../community/StationsApiClient.kt`.
- Move `CommunityApiClient`'s private `call` into an internal `CommunityHttp`, so both clients send alike: the same timeouts, no redirects (the key must never follow one to another host), and the key header.
- Test `StationsApiClientTest`, on `CommunityApiClientTest`'s pattern.

- [ ] Tests first:
  - for each route in the README's table: the method, path, body and key header, and the parsed answer;
  - a read without a key sends no header;
  - `503 {"error":"sessions_off"}` is `Rejected("sessions_off")`, while any other 5xx is `Failed`. Today's `call` would read it as a network failure;
  - a 429 without a body is `rate_limited`;
  - `heard`: a 2xx is SENT; the Worker's 403, or its 404 `gone`, is DROPPED; anything else is RETRY.
- [ ] `live(limit)`, `following(key)`, `station(id, key?)`, `mine(key)`, `create(fields, key)`, `update(id, fields, key)`, `delete(id, key)`, `heard(id, key)`, `vote(id, value, key)`, `follow(id, on, key)`. `upcoming` waits for step 6.
- [ ] Commit `feat(sessions): the stations client`.

### Task 8: The stations repository and the Sessions switch

**Files:** create `core/data/.../community/StationsRepository.kt`; test `StationsRepositoryTest`.

- [ ] Tests first:
  - while Community is off, every call answers `Rejected("off")` and sends nothing;
  - `sessionsOn` starts unknown (null), turns false on `sessions_off`, and true on any answer from a Sessions route;
  - `ensureKnown()` asks once per app run with `GET live?limit=1`, only while it's unknown and Community is on;
  - reads carry the key only once the phone has one. Create, update, delete, follow and vote make one (`CommunityKeyStore.key()`);
  - a +1 or −1 sends the station's waiting heard mark first (task 9). A `listen_first` answer is handled as a post's vote handles it;
  - `revision` goes up after a create, update, delete or follow, so lists on screen reload.
- [ ] Shaped like `CommunityRepository`: `whileOn { }` around each call, results as `CommunityResult`.
- [ ] Commit `feat(sessions): the stations repository and the Sessions switch`.

### Task 9: Two minutes before a vote

**Files:** create `core/data/.../community/StationHeard.kt`; test `StationHeardTest`.

- [ ] Tests first:
  - `listened(stationId, ms)` adds up across calls and across sessions;
  - at 2 minutes the station is marked at once (its `ids` flow) and `PUT …/heard` goes out, once;
  - a RETRY waits for the next `listened` or Community read, and a DROPPED is forgotten;
  - nothing is sent while Community is off, or before the phone has a key (tuning in makes one, task 10);
  - at most 200 stations are kept, oldest forgotten first, and each is forgotten 31 days after its last listen (`CommunityHeard`'s `KEEP_MS`). The vote pill also reads the server's `heard`, so a forgotten mark costs nothing.
- [ ] Kept in the Community key's own DataStore (`communityDataStore`), which a backup never carries, as `CommunityHeard` keeps posts' marks.
- [ ] Commit `feat(sessions): two minutes in a station's sessions before a vote`.

### Task 10: Tune in and go live

**Files:** modify `ListenTogetherController.kt` (commands), `ListenTogetherSession.kt`, `RoomApiClient.kt`; tests `ListenTogetherSessionTest`, `RoomApiClientTest`.

- [ ] Tests first:
  - `Command.TuneIn(code, stationId)` connects with `v: 3`, the name and the phone's Community key, on every hello (reconnects too), and never a host key;
  - a private `Join` never sends the key;
  - `Command.GoLive(stationId, name)` asks for a session with the key (`{public: true, stationId, hostName: name}`), then hosts it as `Host` hosts a private room: the user's queue becomes the room's;
    - `name` is the station's DJ name, or else the user's display name;
    - the hello uses the same name, so the room's crowd and Live now's card name the host alike;
  - each refusal of `GoLive` says why and leaves the phone as it was: `sessions_off`, `gone`, `not_yours`, `blocked`, `rate_limited`, offline;
  - `GoLive` in a private room is refused ("End Listen Together first"), and `GoLive` while live does nothing;
  - `TuneIn` while in a room leaves it first, as `Join` does.
- [ ] `RoomApiClient.createSession(stationId, name, key): CommunityResult<Created>` keeps the Worker's error codes; the private `create` stays as it is. `Preview` gains `station: String?` and `listeners: Int?`.
- [ ] The session gets the key from an injected `communityKey: suspend () -> String`: `CommunityKeyStore.key()`, which makes one on the first tune-in. Read it before connecting, since the hello's builder isn't suspending.
- [ ] Commit `feat(sessions): tune in and go live`.

### Task 11: What the app knows about a session

**Files:** modify `ListenTogetherController.kt` (`ListenTogetherState.InRoom`), `ListenTogetherSession.kt`; test `ListenTogetherSessionTest`.

- [ ] Tests first:
  - the welcome's `state.session` and each `session` frame reach `InRoom`;
  - the role follows `myId`: the host, a co-host (in `cohosts`), or else a listener;
  - `requests` frames are kept for step 3;
  - a session listener's add from a song menu is dropped: never sent, nor turned into a suggestion. In a session, the room answers a listener's `add` with `added {count: 0}` and ignores `suggest`;
  - a private room's `InRoom` has no session, and nothing else in it changes.
- [ ] `InRoom` gains:
  - `session: SessionInfo?` (null in a private room);
  - `role: SessionRole?` (`HOST`, `COHOST` or `LISTENER`; null in a private room);
  - `requests: List<RoomRequest>`;
  - `onAir: Boolean`: the room's song is playing on this phone (a playing timeline, not paused here, not reconnecting).
- [ ] Commit `feat(sessions): the session's state in the app`.

### Task 12: How a session ends

**Files:** modify `ListenTogetherSession.kt`, `core/media/src/main/res/values/strings.xml`; test `ListenTogetherSessionTest`.

- [ ] Tests first: one for each row and line below. Whenever the phone ends up out of a session, the user's own queue comes back (today's restore). A move from one session to the next restores nothing in between.
- [ ] `ended {reason}` in a session:

| Reason | A listener sees | The host sees |
|---|---|---|
| `host_ended`, `paused`, `closed` | "Maya's session ended" ("The session ended" without a name) | "Session ended" |
| `host_left` | One look at `stationRoom(id)`: a newer room means the host's app came back and went live again, and they move there quietly. Otherwise "Maya's session ended". | "Session ended", if the phone gets back after the 60 s |
| `booted` | "You were removed from this session" | (never sent to a host) |
| `station_removed` | "This station is off the air" | "Your station is off the air" |
| `max_age`, `replaced` | They move to the station's new session. The new room's row appears only once its first song is ready, so `stationRoom(id)` is asked every 3 s for 30 s, showing "Reconnecting…", and the phone tunes in quietly when it answers. Nothing by then: "Maya's session ended". | `max_age` while still playing: goes live again at once, carrying the song, where it is in it, and the room's queue into the new session. Otherwise "Session ended". |

- [ ] Closes before a welcome:
  - `UPDATE`: "Update Stash to join this session";
  - `KEY`: "Couldn't join this session";
  - `BOOTED`: "You were removed from this session";
  - `FULL`: today's "This session is full".
- [ ] No hand-over in a session: the host's Leave ends it (`End`), and `MakeHost` is never sent.
- [ ] The station's room comes from an injected `stationRoom: suspend (String) -> String?`.
- [ ] The words go in `core/media`'s strings, where the engine's notices already are.
- [ ] Commit `feat(sessions): how a session ends, in plain words`.

### Task 13: A listener's time, and the wiring

**Files:** modify `ListenTogetherSession.kt`, `core/media/.../service/StashPlaybackService.kt`, `core/ui/.../components/ListenTogetherRole.kt`, `app/.../MainActivity.kt`; tests `ListenTogetherSessionTest` and a role test.

- [ ] Tests first:
  - a listener's time counts only while `onAir`;
  - it's reported every 15 s and when the session ends, through an injected `stationListened(stationId, ms)`;
  - a host's time never counts, nor anything in a private room.
- [ ] `StashPlaybackService` passes:
  - `communityKey`: `CommunityKeyStore.key()`;
  - `stationRoom`: `StationsRepository.station(id)`'s `live.room`;
  - `stationListened`: `StationHeard.listened`.

  Log the wiring once, as the session's own line does: green unit tests say nothing about that line.
- [ ] `ListenTogetherRole.SESSION_LISTENER`: a guest whose menus show neither Play next nor Add to queue (step 3 puts Request there), with no transport controls.
  - `MainActivity`'s mapping (`isHost`, then `guestsAdd`) puts a session first: the host is `HOST`, a co-host `GUEST` (their adds go straight in), anyone else `SESSION_LISTENER`. A session's `guestsAdd` is always false, so today's mapping would make listeners `LISTENER`, whose Play next sends a suggestion the room ignores.
  - A test pins its `isGuest`, its `canQueue` and its menu label.
- [ ] The notification's "Leave session" button reads "End session" for a session's host, since their Leave ends it.
- [ ] Commit `feat(sessions): a listener's time toward the vote, and the engine wired in`.

### Task 14: 2a on a phone, and its PR

Nothing in 2a can open a session from the UI yet. Its phone check is that private rooms stay exactly as they were, with this app saying `v: 3` to them.

- [ ] Against production, a private room on the rig, both ways. The other side is the Pixel 6 with the owner's OK, or `session-peer.mjs private-host` / `private-guest`, which speak today's `v: 2`. Check:
  - create, join, play;
  - a guest's Play next, straight in;
  - reactions, skip and End;
  - the user's own queue back afterwards.
- [ ] A session from the command line against the local Worker (task 2): `session-peer.mjs host`, then `listen`. This proves the run book before 2b leans on it.
- [ ] All modules green, then `gh pr create` "Sessions 2a: the engine and the data (dark)". Wait for CI green.

---

## 2b: the screens

### Task 15: Worktree, and the shared pieces to core/ui

- [ ] `git worktree add .worktrees/sessions-listen -b feat/sessions-listen feat/sessions-engine`, and copy `local.properties` in.
- [ ] Move the vote pill to a public `core/ui/.../components/VotePill.kt`: `VotePill`, `VotePillColors`, `VotePillDefaults` and the arrows from `feature/community/CommunityVotePill.kt`, and `VotePillUi` from `CommunityUi.kt`. The room (`feature/nowplaying`) and the cards (`feature/community`) then share one pill: neither feature module depends on the other. Its test moves with it.
- [ ] Move the member colour (`personColor`, in `feature/nowplaying/.../listen/SessionUi.kt`) to `core/ui` too, for the cards' faces.
- [ ] Nothing changes on screen: screenshot Home and a post's page on the rig before and after.
- [ ] Commit `refactor(sessions): the vote pill and member colours in core/ui`.

### Task 16: Sessions' view model

**Files:** create `feature/community/.../sessions/SessionsViewModel.kt`; test `SessionsViewModelTest`.

- [ ] Tests first:
  - nothing loads while Community is off or `sessionsOn` isn't true, and the state says to show nothing;
  - Live now keeps the server's order (no re-sorting on the phone);
  - Your stations are the followed stations, live ones first, as the server sends them;
  - Follow is optimistic (the mark and the count change at once), and rolls back with a message when it fails;
  - a full session can't be tuned in to;
  - reloads are throttled as `CommunityViewModel.onShown` throttles Home's.
- [ ] `tuneIn(session)` sends `TuneIn(room, stationId)`, then opens the player, as the Join screen does after joining.
- [ ] Commit `feat(sessions): Live now and Your stations' data`.

### Task 17: Home: Your stations and Live now

**Files:** modify `feature/community/.../CommunitySection.kt`; create `sessions/StationCards.kt`; strings in `feature/community`.

- [ ] Study the design system first (memory `feedback_stash_design_system`).
- [ ] These rows show only while Community is on, `sessionsOn` is true, and there's something in them. They sit above the posts, in this order (UX §3):
  1. **Your stations**, if you follow any. Round cards:
     - live ones first, with LIVE, the listener count, the art of the song playing and the station's name;
     - then the rest, in the station's colour, with the next time on the air when there is one.
  2. **Live now**: wide cards, as in the Home mockup:
     - the song's art and LIVE · count;
     - the station's name and its vibe line;
     - up to 4 faces in their colours, then "+N";
     - a mark on stations you follow, and the score.

     A full session says "Full" and can't be tapped. See all opens Sessions.
- [ ] Tapping a live card tunes in, with no preview and no confirm, and the player opens on the room.
- [ ] Check every card on the rig against the mockup, in the light and dark themes: sizes, type and colours.
- [ ] Commit `feat(sessions): Your stations and Live now on Home`.

### Task 18: The Sessions screen

**Files:** create `sessions/SessionsScreen.kt`; add `SessionsRoute` to `StashNavHost.kt`.

- [ ] The Live tab only; Upcoming waits for step 6. It lists:
  - the live stations you follow, then everyone else live, as ranked;
  - your station's row on top: Go live, or "Start a station" without one.

  Pull to refresh. Empty: "Nobody's live right now".
- [ ] Commit `feat(sessions): the Sessions screen`.

### Task 19: Your station

**Files:** create `sessions/YourStationScreen.kt` and `YourStationViewModel.kt` (test); add `YourStationRoute`.

- [ ] Tests first:
  - the 7-day note comes from `deleteAt`: "Go live by Tue 28 Nov or this station and its votes are deleted";
  - a hidden station says "Hidden by votes: only you see it";
  - Delete asks first, then deletes the station and leaves the screen;
  - a refused rename keeps the old name and says why.
- [ ] The screen:
  - the name and vibe line, both editable;
  - the station's link (`https://stashfm.app/s/{id}`), with Share;
  - the follower count;
  - Go live now, the note, and Delete.

  Regulars and the auto-live controls stay hidden until steps 5 and 6.
- [ ] Commit `feat(sessions): Your station`.

### Task 20: Go live

**Files:** modify `core/data/.../prefs/NowPlayingAction.kt` and `feature/nowplaying/NowPlayingActions.kt`; create `feature/nowplaying/.../session/GoLiveSheet.kt` and `GoLiveViewModel.kt` (test).

- [ ] Tests first:
  - with no station, Go live opens the sheet, and its Go live makes the station (`POST stations`), then goes live;
  - with a station, Go live goes live at once;
  - a changed vibe line is saved first (`PATCH`);
  - `have_station`, `blocked` and `sessions_off` each say why.
- [ ] `NowPlayingAction.GO_LIVE`, right after `LISTEN_TOGETHER` in the menu:
  - it shows only while Community and Sessions are on and the phone isn't in a private room;
  - while live, it reads "On the air · 12" and opens the booth;
  - it needs no migration: pins are saved by name, and the menu lists every unpinned action in declaration order, so a new one shows up by itself (`NowPlayingAction`'s own note).
- [ ] The sheet, the first time only:
  - the station's name;
  - "What's playing tonight?": optional, 60 characters, prefilled from last time;
  - one sentence: "Going live shares each song you play, your station's name and your name with anyone who tunes in.";
  - then Go live.
- [ ] Commit `feat(sessions): go live`.

### Task 21: The room

**Files:** create `feature/nowplaying/.../session/SessionRoomScreen.kt`, `SessionRoomViewModel.kt` (test) and `CrowdBand.kt`. Modify `NowPlayingScreen.kt`: the room replaces its content while `InRoom.session != null`, inside the same player sheet.

- [ ] Tests first (the view model):
  - the station's details come from `GET stations/{id}`, read on entering and after each Follow;
  - "Ben's pick" comes from the song's `addedBy` and the names the room sent; the host's own songs say nothing;
  - the crowd splits into the booth (the host with the crown, then co-hosts) and the floor (everyone else, in the order they joined), with "+N" past what fits;
  - Follow is optimistic, with roll-back;
  - reactions are drawn at most 4 a second (decision 6).
- [ ] The screen, from the top (UX §4):
  - the header: collapse (the session carries on in the mini player), the name, LIVE · count, Follow / Following, and the menu (Leave, Share station, About this station);
  - the vibe line;
  - the art, smaller than Now Playing's, then the title, the artist and whose pick it is;
  - the crowd band; a tap shows everyone's names in a sheet;
  - reactions rising from the face of whoever sent them, with today's six emoji (`FloatingReactions`, placed by face);
  - Up next, on one line; a tap shows the songs ahead and whose pick each is;
  - the bar: Request (disabled until step 3) and React.
- [ ] Commit `feat(sessions): the room`.

### Task 22: The vote and the leave sheet

**Files:** create `session/SessionVote.kt` and `session/LeaveSheet.kt`; tests in `SessionRoomViewModelTest`.

- [ ] Tests first:
  - the pill is greyed with "Listen 2 minutes to vote" until the station is heard (`StationHeard.ids`, or the server's `heard`);
  - a vote is optimistic, with roll-back;
  - Leaving after 2 minutes or more in this session shows the leave sheet, once per session; under 2 minutes, it leaves at once.
- [ ] The leave sheet: rate the station (▲ ▼, its vote), and Follow if you don't already. It can be skipped.
- [ ] Commit `feat(sessions): the station vote and the leave sheet`.

### Task 23: The booth, and LIVE everywhere

**Files:** the room's host view (in `SessionRoomScreen.kt`, or `BoothScreen.kt` if it grows), `MiniPlayer.kt`, `NowPlayingScreen.kt`; tests.

- [ ] The host's room:
  - the player controls, as usual;
  - the crowd list;
  - End now ("End the session for everyone?").

  Requests, co-hosts and presence come in step 3.
- [ ] In a session, as host or listener, the mini player says "Rawn's Basement · LIVE" with the count. It has no close today; in a session it gains one (×), which is Leave, with the leave sheet's rule.
- [ ] Commit `feat(sessions): the booth, and LIVE on the mini player`.

### Task 24: The first tune-in

**Files:** `SharePreference` (a flag), `session/FirstTuneIn.kt`; a test.

- [ ] Test first: it's shown before the first tune-in only.
- [ ] One line, then Tune in: "Everyone in a session sees your name: Rawn", with Change, which opens the "Show my name as" field. It never shows again.
- [ ] Commit `feat(sessions): the first tune-in says who sees your name`.

### Task 25: Station links

**Files:** `core/model/.../share/ShareLinks.kt`, `app/src/main/AndroidManifest.xml`, `MainActivity.kt`, `StashNavHost.kt`; create `feature/community/.../sessions/StationScreen.kt` and its view model (test); test `ShareLinksTest`.

- [ ] Tests first:
  - `/s/{id}` parses on both hosts, with or without a trailing slash or a query;
  - the id keeps its case: station ids are case-sensitive, unlike room codes;
  - anything but 8 letters or digits isn't a station link.
- [ ] `ShareLinks.Parsed.Station(id)` and `stationUrl(id)`.
  - The manifest's App Links filter gains `android:pathPrefix="/s/"` for both hosts. The station page's Open in Stash hands the app the workers.dev form.
  - `MainActivity` routes the link as it routes `/l/` (`DEEP_LINK_STATION_PREFIX`), to `StationRoute(id)`.
- [ ] The station screen:
  - live: tune in, and open the room;
  - not live: the station's name, host, vibe line, next time on the air (on a schedule), Follow and score;
  - gone, hidden, or Sessions off: "This station isn't available".
- [ ] Join: a typed code whose preview has a `station` tunes in, instead of joining.
- [ ] Commit `feat(sessions): station links`.

### Task 26: Phones, and the PR

- [ ] Build against the local Worker (task 2), or against production if the owner has turned Sessions on for testing.
- [ ] The phones:
  - **The rig hosts.** It goes live from Now Playing: the first-time sheet, then one tap.
  - **The second phone tunes in from Live now.** That's the Pixel 6 with the owner's OK: debug app only, every tap gated on `mCurrentFocus` in the same command.
  - **Without the Pixel 6,** `session-peer.mjs listen` tunes in to the rig instead. Then swap roles: the rig as listener, tuning in to `session-peer.mjs host`.
- [ ] Check:
  - the phones play in sync;
  - reactions arrive both ways;
  - Follow works, and so does the follower count;
  - the vote is greyed until 2 minutes, then works;
  - the host's network cut: listeners see "Rawn's session ended" at 60 s;
  - paused 15 minutes, the session ends;
  - the station link opens from a chat app;
  - the user's own queue is back after each end;
  - a private Listen Together room is exactly as before.
- [ ] Remove the `adb reverse` rules you added. Take `share.baseUrl` out of `local.properties`.
- [ ] `gh pr create` "Sessions 2b: listen, go live, the room, Live now (dark)", with the rig's screenshots. Nothing shows while the server says `sessions_off`. Wait for CI green.

---

## Risks to watch

- **Guest adds in private rooms:** the version split (decision 1). Task 4's tests and task 14's phone run guard it.
- **The override in a release build:** it's read only in debug builds, from a file CI doesn't have. Check the release APK's `BuildConfig` in the PR.
- **A busy room on a phone:** about 50 reaction frames a second in a full one (decision 6). Watch the frame rate on the rig with `session-peer.mjs` listeners reacting.
- **A host whose app dies:** as in a private room, only the resume token takes back a held slot. The app keeps it in memory, so a restarted app can't reclaim its session, which ends `host_left` after 60 s. Going live again from the restarted app is how a host gets back: the old session then ends `replaced` or `host_left`, whichever comes first, and its listeners move over either way (task 12). Fine for v1; restoring sessions after the process dies would need the token kept.

---

## As built: 2a (2026-11-21, `feat/sessions-engine`)

Where 2a differs from its tasks above:

- **A session checks who's knocking on every hello, resumes included.** room.js's `hello` closes 4401 without the host key or a valid Community key. So a session's host sends its host key in every hello, and a listener its Community key. A private room's hello is unchanged: the host key only until the room hands back a token. Task 10's tests pin both.
- **Decision 5 changed: a session listener's Play next is Request.** `playNextLabel` puts Play next in about 10 menus with no role check, so hiding it meant editing 7 files that step 3 would undo. The listener's half of step 3's requests is here instead:
  - `SESSION_LISTENER`'s Play next reads "Request" and sends `request`. It sends at most the 3 the listener may have waiting, and holds them while the socket is down.
  - The toast comes from the room's `requestAnswer`s.
  - A pass says "Maya passed on Xtal".
  - Add to queue is hidden for them (`canQueue` is false).

  The host's and co-hosts' half (Play next, Add to queue or Pass on a waiting request) is still step 3.
- **Co-hosts add straight in and take out any song**, since `cohosts` already arrives in `session`. Making one is still step 3.
- **Sessions' engine tests are their own class:** `StationSessionTest` in core:media, reusing `ListenTogetherSessionTest`'s fakes. That file still covers private rooms. Its only changes are the hello's `v: 3` and one test that pins the version split. Without the split, guest adds silently became suggestions in private rooms: 87 of its tests failed.
- **Moves (task 12) split `teardown`:**
  - `dropRoom` lets go of the room. The player stays in session mode, and the user's queue stays aside.
  - The exit then ends the session.
  - `enter(moved = true)` never saves the player's position, which would write the session's song over the user's.
  - A move publishes `Connecting(hosting, station)`. Leave, or anything that starts a room, stops it.
- **`CommunityHttp`** is `CommunityApiClient`'s request code, now shared with `StationsApiClient` and `RoomApiClient.createSession`. They read a 503 `sessions_off` as a refusal (`CommunityResult.SESSIONS_OFF`), not as a network failure.
- **`StationHeard`** reuses `CommunityHeard`'s marks under its own pref names (`station_heard`, `station_heard_waiting`).
- **The menus' role** moved out of `MainActivity` into `listenTogetherRole()` in the app module, which a JVM test pins.
- **Task 14's phone check** ran on the rig against production, without the Pixel 6:
  - The rig hosted and `session-peer.mjs private-guest` joined. Then the rig joined `private-host`'s room from its `/l/` link. The rig said `v: 3`; both rooms stayed `v: 2`.
  - Both ways, a guest's Play next went straight in, and reactions crossed. Skip, End for everyone and Leave worked as before. Each time, the user's own queue came back paused, at the place it was left.
  - The command-line session against the local Worker worked by the run book in the PR.
  - Seen, not from 2a: the script adds Dreams with no YouTube id, so the rig had to find it by search. Tidal served it 3 s after the prepare, but the player still wasn't ready at 20 s. The host's 20 s limit (`HOST_PREPARE_MS`) moved the room to the next song. A phone's add carries the YouTube id and art.
