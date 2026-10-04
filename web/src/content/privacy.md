---
# The /privacy page. Built from the README's "What Stash talks to" and "Privacy"
# sections, plus SECURITY.md's "Handling Your Credentials". When the README's
# list of hosts changes, change it here too, in the same words where you can.
# The README's two parked hosts are left out on purpose: the app never calls them.
#
# Figures that come straight from code. Check them when that code changes:
#   relay counts kept 7 days plus today  QUOTA_KEEP_DAYS, infra/lossless-relay/src/db.js
#   Last.fm proxy answers kept 14 days   CACHE_TTL_SECONDS, infra/lastfm-proxy/src/index.js
#   Qobuz login retried once a minute    DEAD_COOLDOWN_MS, data/download/.../qbdlx/QbdlxCredentialStore.kt
#   early-access emails hashed with a     EMAIL_PEPPER, required: the site's sign-in refuses to run without
#   secret ("pepper")                     it (missingForGate in web/worker/index.js), and the import too
#
# Shown as "Last updated" on the page. Change it whenever this file changes.
updated: 2026-10-04
---

## The short version

- There's no Stash account, no analytics, and no third-party crash reporters.
- Your credentials live on your phone. The Spotify, YouTube and Discord tokens are encrypted with AES-256-GCM via Google's [Tink](https://developers.google.com/tink), keyed per install in Android's hardware-backed Keystore; the rest (Qobuz, Last.fm, ListenBrainz) sit in app-private storage. When yt-dlp runs, Stash gives it a temporary copy of your YouTube cookie in a private file only Stash can read, and deletes it as soon as yt-dlp is done.
- Each credential is only ever sent to the service it belongs to, over TLS 1.2 or higher. A handful of hosts the project runs are contacted too, and none of them ever receives a credential.
- Stash is free software under the GNU General Public License, version 3 or later, and every line of code is open source, so anything on this page can be checked in the [source code](https://github.com/rawnaldclark/Stash).

## What Stash talks to

Stash has no account server, but it isn't a two-service app either. This is everything the official release APK can reach, and what for. A build you make yourself from a plain checkout reaches strictly less: it has no Last.fm keys or proxy and no relay config.

### Spotify and YouTube

- **Spotify** (`accounts.`, `open.`, `api-partner.`, `api.spotify.com`, `www.spotify.com`, `clienttoken.spotify.com`): sign-in and library sync. Two optional features, both off by default, also write to your account: mirroring your likes, and auto-saving songs you play often to your Liked Songs.
- **YouTube and YouTube Music** (`music.youtube.com`, `www.youtube.com`, and Google's OAuth endpoints if you use the Google sign-in): library sync, search, artist and album pages, artist photos, and the audio itself via yt-dlp. Two optional features, both off by default, also write to your account: mirroring your likes, and sending your plays to your YouTube Music history. Each play is reported to the tracking address that comes back in YouTube's player response.
- **`m.youtube.com`**: the YouTube sign-in window.
- **`*.googlevideo.com`**: YouTube's audio CDN. The URL comes back in the player response, so no hostname for it ships in the app.
- **Google OAuth** (`oauth2.googleapis.com`): only if you use the YouTube device-code sign-in.
- **What the sign-in windows load**: the Spotify, YouTube and Discord sign-in windows show those services' own web pages, and the pages load what they need from other hosts, such as Spotify's `accounts.scdn.co`, Google reCAPTCHA (`www.google.com`, `www.gstatic.com`), Google's sign-in pages, and Discord's image CDN and CAPTCHA. Stash itself never calls those hosts; the pages do.

### Lossless and fallback audio

Qobuz catalog requests go straight from your phone to `www.qobuz.com`, with no account, under Qobuz's public web-player app id. Stash also contacts Qobuz's web player, `open.qobuz.com`, when that id needs refreshing and when you connect an account. That host is sent nothing about you.

- **Qobuz on Home** (`www.qobuz.com`): the New Releases, Top Albums and Qobuz Playlists rows. No account needed and **on by default**. Turning off "Qobuz discovery on Home" in Settings › Library & Storage, or hiding those rows in Home layout, stops these requests.
- **Qobuz on artist pages** (`www.qobuz.com`): Qobuz is sent the artist's name, to fill in albums YouTube is missing, and an album's id when you open one of those albums. This runs whatever the Home setting is, and no setting turns it off.
- **Qobuz lossless lookups** (`www.qobuz.com`): while Lossless is on, which is the default, every song about to stream (including the next one up) or about to download is looked up here first, by its ISRC or by artist and title. This happens with or without an account, and even when the song then plays from somewhere else, so Qobuz learns what you play. Turning Lossless off in Settings › Audio & Quality stops these lookups, except when you ask for FLAC yourself with "Find in FLAC" in Now Playing or "Upgrade to FLAC" in Library.
- **Your own Qobuz account** (`www.qobuz.com`): only if you connect one in Settings › Audio & Quality. Connecting sends your Qobuz email and password to Qobuz once, to get a token; Stash keeps the token and your email, not the password. Each FLAC link is then requested with that token. While Lossless is on and your account is working, songs you scroll past in search results and on artist and album pages are also looked up ahead of time, so they start faster.
- **`stash-relay.rawnaldclark.workers.dev`**: the project's lossless relay, used only by the official release build. With no Qobuz account or relay endpoint of your own, Stash asks it for every FLAC link. With your own account connected, Stash asks your account first. It uses the relay for a song when the request on your account fails, and for every song while Qobuz is rejecting your login. Your login is retried once a minute, so a login Qobuz no longer accepts keeps using the relay until you sign in again or disconnect it. With your own relay endpoint set, the project's relay is used when yours fails.
- **What the relay is sent and keeps**: the Qobuz track id and format of each song, whether it's a download (Stash marks sync, playlist and mix downloads, their retries and its FLAC upgrade passes; anything else counts as playing, including songs you download from Search or an artist or album page), and a random id for your install. Nothing else about you, and never a credential, so this host learns what an anonymous install listens to. Like any server, it sees your IP address, and it uses it for a per-address rate limit. When it has to ask Qobuz for a new link instead of reusing a recent one, it adds one to a daily count for your install id, stored with a scrambled form of your IP address (a salted hash). That enforces the daily limits and shows when one connection poses as many installs. These counts are kept for the current day and the 7 before it, then deleted, and stay restorable by the owner from Cloudflare's database backups for up to 30 days. The relay's log line for each of those requests shows the track, the format, the first 8 characters of your install id, whether it's a download, your country, and which Cloudflare location answered. The relay hands back a short-lived link, never the audio. If you set your own relay endpoint instead, it is sent the same, and what it keeps is up to whoever runs it.
- **Qobuz's audio CDN** (today `streaming-qobuz-std.akamaized.net`): where the FLAC itself comes from, whether the link came from your account or the project's relay. The address comes back with each link, so it can change. It sees your IP address and which file you're getting. Share diagnostics, when you use it, also checks that this CDN, the relay and the relay config host answer.
- **`stash-tipjar.rawnaldclark.workers.dev/lossless.json`**: the signed relay config and its `.sig`, fetched at every cold start and every 6 hours after by the official release build. This URL *is* in the APK; it is fetched with nothing of yours connected, like the tip jar list on the same host. A plain checkout has no config URL and skips it.
- **JioSaavn** (`www.jiosaavn.com`, `aac.saavncdn.com`): the AAC 320 fallback when nothing lossless matched.

### Lyrics

- **LRCLIB** (`lrclib.net`): synced lyrics.
- **iTunes Search** (`itunes.apple.com`): the first step of word-synced Apple Music lyrics. It is sent the song's title and artist, to find its Apple Music song id.
- **paxsenix's lyrics API** (`lyrics.paxsenix.org`): the second step. It is sent that Apple song id, and nothing else about you, to get the word-synced lyrics (its cache first, then a live lookup).
- **KuGou** (`mobileservice.kugou.com`, `lyrics.kugou.com`): synced lyrics when the sources before it have none. It is sent the song's title and artist, and for some lookups its length. It sees an ordinary desktop-browser User-Agent, not Stash's.
- **YouTube Music** (`music.youtube.com`): plain-text lyrics as the last fallback, only for songs that have a YouTube id.

LRCLIB, iTunes Search and paxsenix see a `Stash/<version>` User-Agent. iTunes Search and paxsenix are asked whenever lyrics are looked up or fetched for a download while Apple Music is the preferred lyrics source, which is the default. They're also asked by a background pass that upgrades lyrics you already have (once after each app update, on Wi-Fi) and by "Fetch lyrics" in Library Health. LRCLIB, KuGou and YouTube Music come next, in that order, each only when the ones before it come back empty or fail: from Now Playing, after a download, and by "Fetch lyrics", never by the background pass. Setting the lyrics source to LRC only in Settings › Playback stops iTunes Search and paxsenix. LRCLIB, KuGou and YouTube Music keep working.

### Scrobbling and artist info

- **Last.fm** (`ws.audioscrobbler.com`, and `www.last.fm` for its sign-in page): scrobbling, only if you connect your account, and loved tracks if you also turn on mirroring likes to Last.fm. Official release builds also look things up on Last.fm without needing an account: artist bios on artist pages, missing album art, genre tags for your downloads, and the similar artists, songs and genres behind radios and Stash Mixes (see [What runs without being asked](#what-runs-without-being-asked)). Those lookups go through `stash-lastfm-proxy.rawnaldclark.workers.dev`, a caching Worker the project runs. It sees the artist or song being looked up, never your account, and keeps only Last.fm's answers, for up to 14 days. If the proxy fails, Stash asks Last.fm directly. With your account connected and Stash Mixes on, Stash also reads your top artists and songs, and looks up your downloaded songs under your username, straight from Last.fm.
- **ListenBrainz** (`api.listenbrainz.org`): optional scrobbling, only if you connect it.
- **MusicBrainz** (`musicbrainz.org`): artist metadata.

### Discord

- **Discord** (`discord.com`, `cdn.discordapp.com`): optional Rich Presence, only if you connect your account. While a song plays, Stash sends Discord its title, artist, album, a link to its cover and how far into the song you are; pausing clears it. Stash sets this status through your account's own session, which it gets from the sign-in window. If Discord needs your permission first, Settings opens Discord's permission page in your browser. Agreeing sends your browser on to a blank page at `paraliyzed.net`, with a one-time code in the address that Stash doesn't use. The README's [First-time setup](https://github.com/rawnaldclark/Stash#first-time-setup) explains exactly how it works and what that means for your account, so read it before you connect.

### Sharing, Listen Together and Community

These features use a Worker the project runs. It answers at `stashfm.app`, the domain this website is on, and at its first address, `stash-share.rawnaldclark.workers.dev`. Depending on its version, Stash uses one address or the other. Versions that use `stashfm.app` switch to the other address when `stashfm.app` can't be reached.

- **Shared mixes**, only when you share, open or follow one. Sharing a mix sends its name, its songs (title, artist and, when known, album, length, ISRC and Spotify/YouTube IDs), up to four album-art links and, if you choose, a display name. Editing it sends the new version, and deleting it sends a delete that takes the link down. Opening or following a mix reads it back; followed mixes are re-checked after syncs and on app start at most every 6 hours. Nothing about your account, device or listening is sent. The Worker uses your IP address to rate-limit and doesn't store it. Anyone with a mix's link can read it; links can't be guessed and nothing lists them.
- **Song links**, only when you share a song as a Stash link or open one. A long song link carries the song's details in the link itself, so making one, or opening one in Stash, sends nothing. A short song link (`stashfm.app/t/` and a code) is stored on the Worker instead. Making one sends the song's title, artist and, when known, album, length, ISRC, Spotify/YouTube IDs and album-art link, and opening one in Stash reads them back. A short link holds the song and when the link was first made, nothing about who made it, and it doesn't expire. Anyone with a song link can read it. The Worker uses your IP address to rate-limit and doesn't store it.
- **Listen Together**: starting or joining a session opens a connection to a short-lived room. The room sees each member's display name (if they set one), the songs played in it (the same descriptors as a shared mix), including the host's upcoming queue (up to 200 songs), sent when a session starts, and play, pause, seek, suggestion and reaction messages. Each phone also sends its sync status (ready, buffering, can't play this song, catching up) and exchanges clock pings with the room to stay in sync; a ping carries only the time since the session started, not how long your phone has been on. Room codes are 8 characters, and looking a room up is rate-limited (60 a minute per IP), so a live session can't be found by guessing codes. Your IP address is used only for rate limiting and isn't stored, and nothing is kept once the room closes (5 minutes after the last person leaves, or 12 hours after it started), apart from Cloudflare's 30-day recovery history, which only the owner could use to restore a closed room.
- **Community**, only while you've turned it on in Home layout. The first time you post or vote, your phone makes a random key that identifies it; posting, voting and taking a post down send it. Home reads the list each time it shows the section, and opening a post reads that post. Once your phone has a key, reads carry it, so your own votes and posts show as yours, and they send nothing else about you. Posting sends the playlist's or song's details (the same descriptors as a shared mix, with album-art links) and your display name; voting sends which post and which way. Posts are public to anyone, with your display name; vote counts are public, but who voted which way isn't.

  The server never stores your key or IP address as they are. Rate limiting uses your IP address without keeping it. Each post and vote is kept with a scrambled form of your key (a hash), and a scrambled form of your IP address (a salted hash) used for the limits per connection; for IPv6, Community uses only the part of the address that identifies your connection. A vote (which post, which way, when) is kept until you take it back or its post is deleted. If the owner blocks your phone, a record of the block (the key's hash, when, and which post) is kept until it's lifted. A post leaves Community as soon as it's taken down, or 30 days after posting; a once-a-day cleanup then deletes it and its votes 30–31 days after posting, or 1–2 days after a take-down, whichever comes first. Anything Community deletes stays restorable by the owner from Cloudflare's always-on database backups for up to 30 days.

### Updates and housekeeping

- **GitHub** (`api.github.com`): the update checks, for Stash itself and for yt-dlp's nightly builds, at every cold start and daily.
- **GitHub release downloads** (`github.com`, `release-assets.githubusercontent.com`): a newer yt-dlp nightly build when there is one, and a helper script yt-dlp needs for YouTube playback. These are fetched whether or not you use streaming.
- **`stash-tipjar.rawnaldclark.workers.dev`**: the public supporters list behind the supporter pill on Home. Fetch only; it's told nothing about you.

### Album art

- **Album art and artist photos** load straight from the service they come from: Spotify (`i.scdn.co`, `mosaic.scdn.co`, and `*.spotifycdn.com` hosts such as `pickasso.spotifycdn.com` for its mixes), YouTube (`i.ytimg.com`, `lh3.googleusercontent.com`, `yt3.googleusercontent.com`, `yt3.ggpht.com`), Last.fm (`lastfm.freetls.fastly.net`, `lastfm-img.freetls.fastly.net`), Qobuz (`static.qobuz.com`) and JioSaavn (`c.saavncdn.com`). A short song link can also bring a cover from Deezer (`cdn-images.dzcdn.net`, `e-cdns-images.dzcdn.net`). A playlist cover loads from whatever image address its service hands back. Each image host sees your IP address, as with any image on the web.

## What runs without being asked

Some of the requests above happen without you tapping anything. These are all of them, grouped by what turns them off.

**Every time Stash starts**, with no setting to turn it off:

- A visitor id from `music.youtube.com` and a security check with `www.youtube.com`, so YouTube playback starts sooner. This runs even if you never play anything from YouTube.
- With a connection: a warm-up request to `music.youtube.com`, the Stash and yt-dlp update checks on `api.github.com` (a newer yt-dlp is downloaded from GitHub when there is one), and a test run of yt-dlp on one public YouTube video, through `www.youtube.com`.
- In the official release build, the relay config, then every 6 hours while Stash runs.
- A fill-in for missing album art on downloaded songs. Their artist and title go to Last.fm through the proxy, or Stash uses the song's YouTube thumbnail from `i.ytimg.com`.
- On Wi-Fi, an artist-photo lookup that sends `music.youtube.com` the name of each artist in your library that has no photo yet. It also runs after each sync.
- Downloads you started that are still waiting are picked up again, and retried later.
- If you follow shared mixes, a check for new versions, at most every 6 hours.

**On a schedule**, with no setting to turn it off:

- Daily: the Stash and yt-dlp update checks.
- Once after each app update, on Wi-Fi: the lyrics upgrade pass described under Lyrics, unless the lyrics source is set to LRC only.
- Once after each app update, with a connection: album art for downloaded songs whose files don't carry their tags yet, from the hosts listed under Album art.

**While Stash Mixes is on**, which is the default (turn it off with "Stash Mixes (beta)" in Settings › Library & Storage):

- At each launch and daily: the artists and songs you've played most in the last day or two, your longer-term top artists and songs, and your top genres go to Last.fm's similar-artist, similar-song and genre lookups, through the proxy.
- Daily, on the network your download setting allows (Wi-Fi while charging, by default): the artist and title of each downloaded song that has no genre tags yet go to Last.fm, through the proxy, for its tags.
- Neither needs a Last.fm account. If the proxy fails, these go to Last.fm directly.
- With Last.fm connected, your top artists and songs are also read from your account, and your downloaded songs are looked up daily under your username, straight from `ws.audioscrobbler.com`.

**While Lossless is on**, which is the default:

- With Download on, after downloads finish: a pass that looks for FLAC copies of downloaded songs that came in lossy, in Qobuz's catalog, then through your account or the relay.

**Only if you turn it on**: Auto-sync, which syncs your library on the days and at the time you pick; and scrobbling, likes mirroring, YouTube Music history and Discord Rich Presence, as described above.

Opening Home also loads the Qobuz rows, unless you've turned them off, and the supporters list.

## This website

The site's own pages run no analytics and load their fonts and images from stashfm.app itself, nothing from anyone else. They set one cookie, and only when you sign in (below). The site is hosted on Cloudflare. Cloudflare's security features can set a cookie of their own, but only if they're turned on for this domain. The Download button takes you to GitHub, where the app is published.

Shared song pages, at `stashfm.app/t` and at the share Worker's first address, come from the share Worker described above. They set no cookies and run no analytics either, but they show the song's album art straight from where it's hosted (Spotify, YouTube, Last.fm, Qobuz, JioSaavn or Deezer). So your browser loads that image from there, and that service sees your IP address. To find the art, the Worker may look the song up on Spotify or Deezer by its Spotify id or ISRC; that request comes from the Worker, not your browser. Shared mix and Listen Together invite pages show no images.

### Early access and signing in

The website is in early access: people who have supported Stash are on the list as a thank-you, and anyone else can ask to be added. This is only about the website; the app needs no sign-in.

- **The access list** holds a scrambled form of each email address (a hash), never the address itself, with where it came from (Ko-fi, an approved request, or added by hand) and when. A plain hash can't be turned back into an address, but anyone holding a copy of the list could check whether an address they already know is on it. So the hash is keyed with a secret (a "pepper") that only the project's Workers hold, and without that secret the list can't be checked that way. Ko-fi's notice of each donation includes the donor's email, and the project's tip jar Worker puts that email's hash on the list. Past Ko-fi supporters are added the same way, from Ko-fi's records. GitHub Sponsors and PayPal supporters are added by hand. When a maintainer adds you or approves your request, the list also notes which maintainer did it (their address, not yours) and when.
- **Signing in**: you type your email, and if it's on the list, a 6-digit code is emailed to you from `access@stashfm.app`. The code is sent through Cloudflare's Email Service, which handles your address to deliver it, and it's in the email's body, not its subject. The page says the same thing whether or not your email is on the list, so it doesn't tell anyone who has access. Only scrambled forms of your codes are kept, each for 10 minutes, along with how many codes went to that email in the last hour (by its hash), to stop floods. Your IP address is used for rate limits and isn't stored.
- **The cookie**: signing in sets `stash_access`, the site's only cookie. It holds the hash of your email and an expiry date, signed so it can't be forged, and is sent only to stashfm.app. A session lasts 90 days unless you're taken off the list, which ends it within a few minutes. Signing out, at the bottom of the home page, deletes the cookie from that browser.
- **Asking for access** keeps the email and note you send until one of the project's two maintainers answers, and deletes them after 30 days if nobody has. If you're let in, you get one email saying so, and only the hash of your address stays.
- **The monthly goal** adds up the month's donations: Ko-fi's automatically, and GitHub Sponsors and PayPal by hand. It keeps each amount and where it came from, not who gave it.
- **To be taken off the list**, or to have a request deleted, email `access@stashfm.app`. Mail to that address is forwarded to the maintainer's own inbox.

## Security

Found a security issue? Please don't post it publicly. Use the disclosure process in [SECURITY.md](https://github.com/rawnaldclark/Stash/blob/master/SECURITY.md), which starts with a [private security advisory](https://github.com/rawnaldclark/Stash/security/advisories/new).
