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
| `added {by, next, count, track}` | room → all | For the "Sam added …" chip. |
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
- Known limit: the host's own two quick Up next edits less than one round trip apart, with a guest add arriving
  between them, can undo the first edit (the same as before this change for two quick edits).

## Compatibility and deploy

- Worker first. With no app sending `v: 2`, it behaves as before (only `qv` appears on queue broadcasts, which older
  apps ignore). A room live during the deploy keeps working: missing fields get defaults. No Durable Object migration.
- A new app on an old Worker sees no `v` in `state` and keeps suggesting, so either deploy order is safe.
- Rollback = the previous Worker version: it ignores the new fields, and `accepted` stays in the form it reads.

## Not in this change

- An `sc` (SoundCloud) id on `SharedTrack`, so SoundCloud-only songs resolve on other phones.
- A host in Offline mode staying silent while the others hear a guest's song the host hasn't downloaded.
- Kick or ban.
