# Google Cast: design

**Status:** agreed on 2026-10-06. Implemented on `feat/cast`. A first device test on 2026-10-07 worked. Fixes from the review followed (§3, §4).

**Scope:** play Stash on Chromecast and "Chromecast built-in" speakers (Nest, and speakers from other brands). This covers downloaded songs and streams.

**Builds on:** §4 of `docs/superpowers/specs/2026-07-29-social-integrations-research.md`, which proposed the local HTTP proxy.

## 1. Decisions

| Decision | Choice | Why |
|---|---|---|
| Distribution | One build, the GitHub APK as today, with the Google Cast SDK in it. | Stash ships only on GitHub. The Cast SDK is closed source, so this rules out F-Droid as it stands. If Stash ever goes there, the Cast code is already isolated in `com.stash.app.cast` behind core:media's `CastDevices` contract, so a build without it would be a small, separate change (AntennaPod and Jellyfin split their builds that way). |
| Who owns the queue | The **phone**. The speaker only ever holds the song that is playing. | The app's queue logic (repository bookkeeping by timeline index, shuffle order, repeat, autoplay radio, the prefetch upgrades) stays exactly as it is. Media3's `RemoteCastPlayer` has no shuffle, and a Cast load of a 2,000-song queue goes over the 64 KB message limit. |
| How the speaker gets the audio | Through a small HTTP server on the phone, `CastMediaServer`. It reads through the app's own `DataSource` stack. | Downloaded `file://` and SAF `content://` songs mean nothing to the speaker. Signed stream URLs expire, and the speaker can't refresh them, but our `RefreshingDataSource` / `LazyResolvingDataSource` can. This is the research doc's proxy, built on the stack that already works. |
| Receiver | The Default Media Receiver (`CC1AD845`) | No custom receiver to host. It plays MP3, AAC/M4A, FLAC, Opus and Vorbis. |
| Device picker | Our own Compose sheet, built on `MediaRouter` | `MediaRouteButton` needs a `FragmentActivity` and an AppCompat theme, and Stash has neither. |
| Gaps between songs | None: once a song plays, the next one is queued on the receiver, which buffers it ahead | The receiver moves on by itself, gapless, and doesn't depend on the phone being awake at the seam. |

## 2. Modules

- `core:media/cast/`:
  - **`CastContract.kt`**: the interfaces `CastDevices` (discovery, connect, disconnect, connection state) and `CastRemote` (one connected receiver: load, play, pause, seek, volume, status).
  - **`CastSessionPlayer`**: the `ForwardingSimpleBasePlayer` that the MediaSession holds while casting.
  - **`CastMediaServer`**: the LAN proxy.
- `app` (`com.stash.app.cast`): `GoogleCastDevices` and `GoogleCastRemote` implement the contract over `CastContext`, `MediaRouter` and `RemoteMediaClient`. Also here are the `OptionsProvider` and the Hilt `@Binds`. Only the app module depends on the Cast SDK.

## 3. Playback while casting

`StashPlaybackService` watches `CastDevices.remote`. When a receiver connects, the service runs `enterCast`:
1. Suspends crossfade, the same way Listen Together does.
2. Pauses the master ExoPlayer and `stop()`s it. The queue and position stay, but nothing plays or buffers locally.
3. Starts `CastMediaServer` and takes a partial wake lock and a Wi-Fi lock. The phone's CPU and Wi-Fi must stay awake to serve audio while the screen is off. The locks are held only while the speaker plays: a pause, the end of the queue or a speaker error lets them go, and play takes them again.
4. Wraps the master in `CastSessionPlayer` and sets it as `mediaSession.player`.

Every controller (Now Playing, the notification, the lock screen, Bluetooth, Android Auto) now drives the wrapper:

- **Queue, shuffle, repeat and metadata** are forwarded to the local player, which keeps the timeline.
- **Play, pause, seek within the song, and position** go to the receiver. The current song's duration comes from the receiver once it knows it, and until then from the track's metadata.
- **When the current song changes** (a skip, a queue edit, a tap in the queue), the new song is loaded on the receiver.
- **The next song is queued on the receiver** (`CastRemote.setNext`, with a preload time) as soon as the current one plays. It is chosen by the local player's `nextMediaItemIndex`, so shuffle and repeat-all just work. When the receiver moves on to it, the wrapper moves the local queue along without a reload. A queue edit, or a change to shuffle or repeat, that changes what comes next replaces the queued song. Repeat-one queues nothing.
- **When the receiver finishes a song with nothing queued** (idle with reason `FINISHED`), the wrapper advances the local player the same way. With repeat-one it restarts the song.
- **Every song is prepared before the receiver sees it.** A stream that still has to be resolved (a `stash-resolve://` placeholder, possibly yt-dlp for up to 45 s) is opened for its first byte through the same routing, which resolves it and fills the URL cache. Until then the player reports buffering. A skip in the meantime drops the old song, and a pause makes it load paused. If preparing fails, that is a player error, and the repository's guard skips as usual.
- **When the receiver reports an error,** that becomes the player error. The repository's existing cascade guard then skips, exactly as it does locally.
- **Device volume** is the receiver's volume. The session reports `PLAYBACK_TYPE_REMOTE`, so the phone's volume keys control the speaker.
- **Speed** isn't offered while casting.

**A brief network loss is not a disconnect.** The SDK reports it as a suspended session. The speaker plays on, so nothing changes on the phone. If the session resumes, `GoogleCastRemote` re-registers on the session's media client and casting continues as before. Only an ended session hands playback back to the phone.

**A paused speaker gets 30 minutes** (`CAST_IDLE_STOP_TIMEOUT_MS`) before the idle stop, not the phone's usual 5. Stopping the service ends the cast session, and the speaker then has to be picked again.

When the receiver disconnects, `exitCast` puts the master back on the session, loads the cast position into it, and leaves it paused. It also releases the server and the locks, and lifts the crossfade suspension. Stopping the cast should never start the phone's loudspeaker.

**Listen Together and Cast exclude each other.** The service ends a cast before a Listen Together session takes the player, and it won't start casting while a session is active.

The idle-stop countdown and the prefetch poll read the wrapper while casting. Without that, the stopped local player would look idle and stop the service under a playing speaker.

## 4. `CastMediaServer`

- **Binding and URLs.** It binds to the phone's LAN IPv4 address, on a random port, only while casting. Wi-Fi is preferred, then Ethernet, then a hotspot. Private addresses come first, but carrier-grade NAT and public addresses on the LAN are accepted too. Mobile-data and VPN interfaces are never used. With no LAN address, the service says so in a toast and disconnects. Every URL carries a 128-bit random token: `http://<ip>:<port>/<token>/m/<n>` for audio and `/<token>/a/<n>` for local artwork.
- **What it reads.** Each URL maps to the queued `MediaItem` it was made for. It reads through the same `DataSource` routing that ExoPlayer uses:
  - local file or content
  - `stash-resolve://` lazy resolve
  - the YouTube refresh chain, without the stream cache. On every seek the speaker opens a new range request while the old one may still be draining, and the cache's span lock would make the new request wait for the old one.
  - JioSaavn without redirects
  - plain HTTP
- **Requests.** It handles `GET` and `HEAD` with single `Range` requests, which the receiver uses to seek. When the upstream length is unknown, it answers `200` with the whole stream.
- **Content-Type.** This comes from the file's first bytes (`fLaC`, `ID3`, MPEG frame sync, `OggS`, `ftyp`, EBML). Speakers trust it more than the file extension.
- **`HEAD` requests.** Once a read has learned the length and type, a `HEAD` is answered from those, without opening the source again (which could mean a full stream resolve).
- **Artwork.** `http(s)` artwork goes to the receiver as is. Local cover files go through the server.
- **Staying responsive.** Asking for the same file again (a re-queued song, its cover) reuses its URL, so queue edits can't push the playing song out of the server's table. Connections run on an unbounded pool of threads: a speaker switched off mid-song leaves its thread stuck in a write until TCP gives up, and a fixed pool would run out. Stopping the server closes the connections still being served.
- **A changed LAN address.** Before each song goes to the receiver, the server checks the phone's address, and if it changed (a new DHCP lease, after a reconnect) it moves to the new one. The song playing at the time can't be saved, since the receiver holds its old URL, but the songs after it can.

## 4a. Staying alive and diagnosable

- **Foreground while paused.** Casting keeps the service in the foreground while paused, the same way Listen Together does (`onUpdateNotification`, plus the artwork-callback redirect). Otherwise Media3 demotes a paused service after 10 minutes, and on Android 12 and later the frozen process could no longer serve the speaker its next song. The 10-minute ceiling Android 16 imposes on paused media services still applies.
- **Connecting.** If no session comes up within 20 s, the attempt is cancelled and the speaker selection is released, with a "Couldn't connect" message. A route MediaRouter still holds as selected from an earlier session is released before connecting, because MediaRouter silently ignores selecting it again. The sheet offers Cancel while connecting. A suspended session that doesn't resume within 60 s is ended.
- **Diagnostics.** The "Playback" section of the diagnostics bundle has a cast block: where casting stands, and the last 15 cast events of this app run. These are connects, timeouts, session start, suspend, resume and end with their codes, server starts and address changes, failed prepares and speaker errors. They are fixed words and numbers only, never a speaker's name or an address.

## 5. UI

- Now Playing gets a cast icon. It is absent when Play Services can't start Cast.
- Tapping the icon opens a sheet:
  - **While searching:** speakers on the network, discovered for as long as the sheet is open.
  - **While connected:** "Playing on *speaker*", a volume slider and **Stop casting**.
- The sheet says that the equalizer, crossfade, loudness levelling and speed don't apply while casting, because the speaker does the decoding.

## 6. Known limits and follow-ups

- The phone has to stay on the same Wi-Fi as the speaker. If it changes networks, the speaker stops.
- Since Android 14, the Wi-Fi lock does nothing while the screen is off: the platform turns it into a low-latency lock, which needs the app in the foreground. Media3's own streaming lock has the same limit. Wi-Fi power save can then slow the server's replies, but the connection stays.
- The 30-minute idle stop counts only the time the phone is awake: its countdown is a coroutine `delay`, and a paused speaker holds no wake lock. With the screen off it can fire later. An exact stop would need an `AlarmManager` alarm.
- Opening casting from the system output switcher, rather than from our button, isn't wired yet.
