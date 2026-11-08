# Listen Together: guests add songs

**Status:** approved by the owner on 2026-11-04 (every default below). Changes the "Control" row of
`2026-09-24-listen-together-design.md`: the host still controls play, pause, seek and skip, but guests can now add
songs to the queue.

## What changes for people

- The host's Session sheet has a switch, **Let guests add songs**. It is on by default, and the app remembers the
  host's last choice.
- On: a guest's **Play next** and **Add to queue** go straight into the room's queue, with the guest's face on the
  song. Off: every guest's Play next is a suggestion the host approves, as before.
- The queue, front to back: the Play next songs people added (first come, first played), then their Add to queue
  songs, then the rest of the host's album or playlist. The host's own Play next and Add to queue follow the same
  rules (before this, both went to the end).
- When the host starts a new album or playlist, the songs people added stay and play first.
- A guest can remove their own songs from Up next. Only the host reorders, or removes anyone's.
- At most 20 of one guest's songs wait in the queue at once. The host is limited only by the room's 200.
- Everyone except the adder sees a short "Sam added *Song*" (or "Sam added 3 songs") chip on Now Playing. There is no
  system notification.
- When the host's phone can't play a song, the room skips it for everyone and says "Skipped *Song*: Rawn can't play
  it".

## Protocol (additive; `infra/share-worker/src/room.js`, `core/model/.../listen/RoomProtocol.kt`)

| Message | Direction | Meaning |
|---|---|---|
| `hello` + `v: 2`, `guestsAdd` | phone → room | This app knows guest adds; its switch, which rules whenever this member hosts. |
| `state` + `v: 2`, `guestsAdd`, `qv` | room → phone | The room knows `add`; whether guests' adds go straight in now; the queue version. |
| `add {tracks, next}` | anyone → room | Play next (`next: true`) or Add to queue, a whole selection in one message. Stamped with the sender. A guest's becomes suggestions while adds are off. |
| `unqueue {track, at}` | anyone → room | Remove one song: the host any, a guest only their own. |
| `settings {guestsAdd}` | host → room, room → all | The switch, and the room's answer when the effective value changes. |
| `load` / `queue` + `qv` | host → room | The queue version the host's app built this whole queue from. |
| `queue` + `qv` | room → all | Every queue broadcast carries the version. |
| `added {by, next, count, track}` | room → all | For the "Sam added …" chip, and the adder's own confirmation. |
| `added {by, next, count: 0, suggested?}` | room → sender | Nothing of that `add` went in: refused (no `suggested`), or it became `suggested` suggestions. Every `add` gets one `added` back, so the app's toast says what really happened. |
| `load` / `prepare` `why: "unavailable"` | host → room → all | The host's phone couldn't play the last song. |

Effective switch: on only while the host's app sent `v: 2` and its switch is on, recomputed on every host change. An
older host keeps approvals; an older guest's `suggest` goes straight in as Play next while the switch is on. Turning
the switch on lets waiting suggestions into the queue as Play next, in the order they came.

## Ordering

- One Durable Object handles one message at a time, so two adds are applied in the order they arrive.
- The host's app sends whole queues (on every song change, and on each Up next edit). The room numbers every queue
  change (`qv`) and remembers which queued songs are adds and the version each arrived at, plus the last 50 removals.
  A host message built from an older version gets the adds it hadn't seen put back (by the same rules) and the
  removals it hadn't seen applied again. Without `qv` (an older host) the queue is taken as sent, as before.
- An added song's identity includes its adder, so a guest's copy is never confused with the same song on the host's
  album.
- The song a Back re-queues holds the front like a Play next, so what's added next goes behind it. It doesn't outlive
  a new album (it has already been heard). A song the host dragged to the top doesn't hold the front: a new Play next
  goes in front of it, as the host left it.
- When the room changes hands, the album or playlist still waiting becomes the new host's, on every path (hand over,
  the 60 s heir, the first back into a hostless room, the host key). Songs people added stay theirs, the former host's
  included.
- The queue stops taking adds at 170,000 characters of JSON, so the host's next Load (the whole queue) can't pass the
  room's 262,144 frame limit. Ordinary songs never meet it: the whole 200 come to about 49 KB. `add` and `unqueue` are
  ignored past a burst of 10, then 2 a second, per socket; only a client looping them reaches that.
- Known limit: the version covers one host action per round trip. Two host actions less than one round trip apart
  (two skips, a skip and an edit, two edits), with a guest add or removal crossing them, can replay a song or bring
  back a removed one, not only undo the first edit, and the slower the connection the wider the window. Two skips
  during an outage can lose a guest's song the same way, because the second replaces the first in the waiting Load.
  The reviewer's fuzz (4,000 runs per setup) finds nothing when the host acts at most once per round trip.

## Compatibility and deploy

- Worker first. With no app sending `v: 2`, it behaves as before (only `qv` appears on queue broadcasts, which older
  apps ignore). A room live during the deploy keeps working: missing fields get defaults. No Durable Object migration.
- A new app on an old Worker sees no `v` in `state` and keeps suggesting, so either deploy order is safe.
- Rollback = the previous Worker version: it ignores the new fields, and `accepted` stays in the form it reads.

## Not in this change

- An `sc` (SoundCloud) id on `SharedTrack`, so SoundCloud-only songs resolve on other phones.
- A host in Offline mode staying silent while the others hear a guest's song the host hasn't downloaded.
- Kick or ban.
