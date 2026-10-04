---
# The /privacy page. Built from the README's "What Stash talks to" and "Privacy"
# sections, plus SECURITY.md's "Handling Your Credentials". When the README's
# list of hosts changes, change it here too, in the same words where you can.
# The README's two parked hosts are left out on purpose: the app never calls them.
#
# Shown as "Last updated" on the page. Change it whenever this file changes.
updated: 2026-10-04
---

## The short version

- There's no Stash account. No subscription. No ads. No analytics, and no third-party crash reporters.
- Your credentials live on your phone. The Spotify, YouTube and Discord tokens are encrypted with AES-256-GCM via Google's [Tink](https://developers.google.com/tink), keyed per install in Android's hardware-backed Keystore; the rest (Qobuz, Last.fm, ListenBrainz) sit in app-private storage.
- Each credential is only ever sent to the service it belongs to, over TLS 1.2 or higher. A handful of hosts the project runs are contacted too, and none of them ever receives a credential.
- Stash is GPL-3.0 and every line of code is open source, so anything on this page can be checked in the [source code](https://github.com/rawnaldclark/Stash).

## What Stash talks to

Stash has no account server, but it isn't a two-service app either. This is everything the official release APK can reach, and what for. A build you make yourself from a plain checkout reaches strictly less: it has no Last.fm keys or proxy and no relay config.

### Spotify and YouTube

- **Spotify** (`accounts.`, `open.`, `api-partner.`, `api.spotify.com`, `www.spotify.com`, `clienttoken.spotify.com`): login, library sync, likes and history mirroring.
- **Google reCAPTCHA** (`www.google.com`, `www.gstatic.com`): loaded by Spotify's own login page inside the sign-in window. Stash never calls them; Spotify's page does.
- **YouTube and YouTube Music** (`music.youtube.com`, `www.youtube.com`, and Google's OAuth endpoints if you use the Google sign-in): library sync, and the audio itself via yt-dlp.
- **`m.youtube.com`**: the YouTube sign-in window.
- **`*.googlevideo.com`**: YouTube's audio CDN. The URL comes back in the player response, so no hostname for it ships in the app.
- **Google OAuth** (`oauth2.googleapis.com`): only if you use the YouTube device-code sign-in.

### Lossless and fallback audio

- **Qobuz catalog** (`www.qobuz.com`, and `open.qobuz.com` to refresh its public web-player key): the New Releases, Top Albums and Qobuz Playlists rows on Home. No account needed and **on by default**; turn it off with "Qobuz discovery on Home" in Settings › Library & Storage, or hide those rows in Home layout.
- **Qobuz lossless** (`www.qobuz.com`): FLAC streams and downloads, only once you connect your own account.
- **`stash-relay.rawnaldclark.workers.dev`**: the project's lossless relay, reached only from the official release build and only when no account of your own is connected. It is sent the Qobuz track id and format of what you're playing, a random per-install id used for rate limiting, and nothing else, so this host learns what an anonymous install listens to. No credential ever crosses it. If you configure your own relay endpoint instead, it gets the same.
- **`stash-tipjar.rawnaldclark.workers.dev/lossless.json`**: the signed relay config and its `.sig`, fetched at every cold start and every 6 hours after by the official release build. This URL *is* in the APK; it is fetched with nothing of yours connected, like the tip jar list on the same host. A plain checkout has no config URL and skips it.
- **JioSaavn** (`www.jiosaavn.com`, `aac.saavncdn.com`): the AAC 320 fallback when nothing lossless matched.

### Lyrics

- **LRCLIB** (`lrclib.net`): synced lyrics.
- **iTunes Search** (`itunes.apple.com`): the first step of word-synced Apple Music lyrics. It is sent the song's title and artist, to find its Apple Music song id.
- **paxsenix's lyrics API** (`lyrics.paxsenix.org`): the second step. It is sent that Apple song id, and nothing else about you, to get the word-synced lyrics (its cache first, then a live lookup).

Both lyrics hosts see a `Stash/<version>` User-Agent. They're asked whenever lyrics are looked up or fetched for a download while Apple Music is the preferred lyrics source, which is the default; also by a background pass that upgrades lyrics you already have, and by "Fetch lyrics" in Library Health. Set the lyrics source to LRC only in Settings to stop both.

### Scrobbling and artist info

- **Last.fm** (`ws.audioscrobbler.com`): optional scrobbling, plus artist bios and images. In official release builds the read lookups go through `stash-lastfm-proxy.rawnaldclark.workers.dev`, a caching Worker the project runs: it sees the artist or track being looked up, never your account.
- **ListenBrainz** (`api.listenbrainz.org`): optional scrobbling, only if you connect it.
- **MusicBrainz** (`musicbrainz.org`): artist metadata.

### Discord

- **Discord** (`discord.com`, `cdn.discordapp.com`): optional Rich Presence, only if you connect your account. Unlike everything else on this list, this isn't an official API integration. The README's [First-time setup](https://github.com/rawnaldclark/Stash#first-time-setup) explains exactly how it works and what that means for your account, so read it before you connect.

### Shared mixes, Listen Together and Community

`stash-share.rawnaldclark.workers.dev` is a Worker the project runs, behind three features.

- **Shared mixes**, only when you share, open or follow one. Sharing a mix sends its name, its songs (title, artist and, when known, album, length, ISRC and Spotify/YouTube IDs), up to four album-art links and, if you choose, a display name. Editing it sends the new version, and deleting it sends a delete that takes the link down. Opening or following a mix reads it back; followed mixes are re-checked after syncs and on app start at most every 6 hours. Song links send nothing: Stash reads them on your phone. Nothing about your account, device or listening is sent. The Worker uses your IP address to rate-limit and doesn't store it. Anyone with a mix's link can read it; links can't be guessed and nothing lists them.
- **Listen Together**: starting or joining a session opens a connection to a short-lived room. The room sees each member's display name (if they set one), the songs played in it (the same descriptors as a shared mix), including the host's upcoming queue (up to 200 songs), sent when a session starts, and play, pause, seek, suggestion and reaction messages. Each phone also sends its sync status (ready, buffering, can't play this song, catching up) and exchanges clock pings with the room to stay in sync; a ping carries only the time since the session started, not how long your phone has been on. Room codes are 8 characters, and looking a room up is rate-limited (60 a minute per IP), so a live session can't be found by guessing codes. Your IP address is used only for rate limiting and isn't stored, and nothing is kept once the room closes (5 minutes after the last person leaves, or 12 hours after it started), apart from Cloudflare's 30-day recovery history, which only the owner could use to restore a closed room.
- **Community**, only while you've turned it on in Home layout. The first time you post or vote, your phone makes a random key that identifies it; posting, voting and taking a post down send it. Home reads the list each time it shows the section, and opening a post reads that post. Once your phone has a key, reads carry it, so your own votes and posts show as yours, and they send nothing else about you. Posting sends the playlist's or song's details (the same descriptors as a shared mix, with album-art links) and your display name; voting sends which post and which way. Posts are public to anyone, with your display name; vote counts are public, but who voted which way isn't.

  The server never stores your key or IP address as they are. Rate limiting uses your IP address without keeping it. Each post and vote is kept with a scrambled form of your key (a hash), and a scrambled form of your IP address (a salted hash) used for the limits per connection; for IPv6, Community uses only the part of the address that identifies your connection. A vote (which post, which way, when) is kept until you take it back or its post is deleted. If the owner blocks your phone, a record of the block (the key's hash, when, and which post) is kept until it's lifted. A post leaves Community as soon as it's taken down, or 30 days after posting; a once-a-day cleanup then deletes it and its votes 30–31 days after posting, or 1–2 days after a take-down, whichever comes first. Anything Community deletes stays restorable by the owner from Cloudflare's always-on database backups for up to 30 days.

### Updates and housekeeping

- **GitHub** (`api.github.com`): the update check.
- **yt-dlp**: updates itself from its own nightly release channel on GitHub.
- **GitHub release downloads** (`github.com`, `objects.githubusercontent.com`): the yt-dlp nightly binary itself, refreshed every 24 hours whether or not you use streaming.
- **`stash-tipjar.rawnaldclark.workers.dev`**: the public supporters list behind the supporter pill on Home. Fetch only; it's told nothing about you.

### Album art

- **Album art CDNs**: `i.scdn.co`, `lh3.googleusercontent.com`, `yt3.googleusercontent.com`, `yt3.ggpht.com`, `i.ytimg.com`, `static.qobuz.com`, `c.saavncdn.com`, and whichever CDN the source that matched a song uses.

## What runs without being asked

Four of these run whether or not you stream anything: Stash warms its connection to `music.youtube.com` at launch, checks `api.github.com` for a new Stash release on every cold start and again daily, checks for a new yt-dlp once a day, and, in a release build carrying a relay config, fetches that config at launch and every 6 hours.

## This website

stashfm.app sets no cookies, runs no analytics, and loads its fonts and images from itself, nothing from anyone else. The Download button takes you to GitHub, where the app is published.

## Security

Found a security issue? Please don't post it publicly. Use the disclosure process in [SECURITY.md](https://github.com/rawnaldclark/Stash/blob/master/SECURITY.md), which starts with a [private security advisory](https://github.com/rawnaldclark/Stash/security/advisories/new).
