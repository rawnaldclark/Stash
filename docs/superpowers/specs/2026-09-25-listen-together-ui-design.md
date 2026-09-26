# Listen Together UI: the Balanced look (design)

**Date:** 2026-09-25 · **Branch:** `feat/listen-together-ui` (stacked on `feat/listen-together`, PR #498) · **Status:** approved by the owner from the mockup (`.superpowers/brainstorm/9-1790383182/listen-together-core.html`).

**Goal:** Listen Together should feel like a room you share. You see who's there, who did what, and what's coming, all in one place. Each person has their own colour, and the rest of the screen stays calm.

**Input:** the research and inventory in `.superpowers/research/2026-09-25-listen-together-ux-research.md` (20+ apps; every current surface and its gaps).

## What changes for people

1. **Session bar** on Now Playing replaces today's member pills.
   - A glass pill with overlapping faces: up to 4, then "+n". The host's face has an amber ring and a crown.
   - Title: "Listening with Maya, Sam".
   - Subtitle: "You're hosting · 3 in the room" or "Rawn is hosting". While reconnecting it reads "Reconnecting…".
   - Tapping it opens the Session sheet.
2. **Faces** are initials on a colour chosen from the member id. The palette has 8 hues that suit the theme: #A78BFA, #F472B6, #22D3EE, #FBBF24, #34D399, #FB923C, #60A5FA, #A3E635.
   - A person keeps their colour everywhere: the bar, their reactions, "added by" badges, and notices.
   - No photos; names are all the room has.
3. **Session sheet**, the one home for the session, with these sections in order:
   - Header: "Listening together" / "3 people · you're hosting".
   - **People**: faces with names. A member's status shows as a word under their name ("catching up", "can't play this song"), not by colour alone.
   - **Up next**: the room's queue, read-only for everyone. Each row has an "added by" face. Tiles are colour placeholders, because songs carry no artwork URL.
   - **Suggestions**:
     - The host sees each one with "from Sam" and the buttons Dismiss / **Play next**.
     - A listener sees their own pending suggestions ("Waiting for Rawn").
   - **Invite**: the room code shown as `R2YH-WS55`, with **Copy link** and **Share**.
   - **Leave** and **End for everyone**. End is host-only, styled destructive, and its confirm says how many are listening. A host's Leave is confirmed ("Maya will host"); a listener's Leave is not.
   - This replaces the session rows in the ⋮ "Track Options" sheet. That sheet keeps a single "Listen together" row: it starts a session when idle and opens the Session sheet during one.
4. **Starting a session** opens the Session sheet straight away with "Starting…". When the room is ready, the Invite section is at hand, so there's no hunting for Invite afterwards.
5. **Same controls for everyone.**
   - The transport row is [gap] [prev] [play/pause] [next] [React] in both roles.
   - A listener's prev/next slots are empty spaces, never dead buttons.
   - A listener's play/pause pauses only their phone. This behaviour exists already; the button is simply shown now.
   - Under the title, listeners get a **LIVE** pill (accent, filled) when in sync, and **↻ Back to live** (outlined; tap rejoins) when paused on their own phone.
   - The separate Leave button and the "Paused — tap to rejoin" chip go away.
6. **Named notices** replace the session toasts.
   - A short glass chip with the person's face, above the controls, for about 3 s. The newest replaces the current one.
   - Events: "Maya joined", "Sam left", "Rawn skipped", "Rawn went back", "Rawn paused", "Rawn resumed", "You're hosting now", "Maya is hosting now".
   - "Rawn skipped" also covers the host tapping a new song (`why` = `pick`). Your own actions, natural song ends and radio make no chip.
   - Messages about suggestions and errors keep using the existing notices.
7. **"Sam's pick"** under the song title while a suggested song plays: the adder's face and their name in their colour. Nothing shows for the host's own songs.
8. **Reactions** carry the sender's colour as a ring around the floating emoji.
   - The picker stays open for quick repeat taps; tap outside to close.
   - Floating is skipped when the ambient-animation setting is off. The emoji then shows briefly at the React button.
9. **Haptics** at three moments only: the session starts or you join (Confirm), you become host (Confirm), and you send a reaction (a light tick).

**Not in this build:** Join screen polish, a mini-player badge, notification text, host editing Up next, showing a listener's pause to others, artwork in Up next, QR codes, chat, voting, turn-taking, recap.

## Protocol changes (backward compatible, all fields optional)

- **Who added a song:** queued songs and the room's current song may carry `by`, a member id.
  - `cleanTrack` accepts `by` (string, ≤ 16 characters).
  - The room stamps `by = event.from` on any `load` or `queue` item that lacks it.
  - An accepted suggestion gets `by = suggester`.
  - The host's app round-trips it through `SharedTrack.addedBy` (`@SerialName("by")`; left out when null, so shared mixes never carry it).
- **Who did what:**
  - `timeline` messages caused by play, pause or seek carry `by`.
  - `prepare` carries `by` and `why`. The host's `load` sends `why`: `skip`, `back`, `end`, `pick` or `radio`.
  - Timelines from the room itself, such as the start after the ready handshake, carry no `by`.
- Old apps ignore the new fields (`ignoreUnknownKeys`). The Worker deploy is code-only, with no migration. Before and after it, shared-mix links are checked to answer identically.

## Engine and UI wiring

- `ListenTogetherState.InRoom` gains `track` (the current room song, including `addedBy`) and `queue` (List<SharedTrack>). The engine already holds both; `publish()` used to drop them.
- `ListenTogetherController` gains `events: SharedFlow<SessionEvent>`. The session emits one from:
  - member-list diffs (joined, left);
  - host id changes;
  - `timeline` and `prepare` messages whose `by` isn't me.
  - It never emits from the first welcome snapshot.
- The session passes `why` on each `load`: next → `skip`, previous → `back`, advance → `end`, radio → `radio`, a tapped song or playlist → `pick`.
- UI lives in `feature/nowplaying/.../listen/`:
  - `PersonAvatar` and `personColor(id)`, `SessionBar`, `SessionSheet`, `SessionEventChip`, `LivePill`.
  - Reactions get a colour ring and the picker stays open.
  - `NowPlayingScreen` swaps the pills for the bar and drops the listener-only row.

## Testing
- Worker unit tests for `by` stamping, suggestion attribution, and `by`/`why` on `timeline`/`prepare`.
- Unit tests for model round-trips (`addedBy` omitted when null), for events derived from message sequences, and for the `why` sent on next/previous/advance/radio/pick.
- Unit tests for `personColor` (stable, spread across the palette) and name formatting ("Maya, Sam +1").
- Two-phone device pass:
  - Host + listener: bar, sheet, invite copy/share, Play next attribution.
  - Chips for skip/pause/resume/join/leave/handover.
  - Listener pause → Back to live.
  - Reactions in colour.
  - End and Leave confirms.
