# Stash

> **Your Spotify + YouTube Music library, downloaded and playable offline. Bulletproof matching, premium UI, zero subscriptions.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-purple.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-purple)](#requirements)
[![Release](https://img.shields.io/github/v/release/rawnaldclark/Stash?color=purple&include_prereleases)](https://github.com/rawnaldclark/Stash/releases)

Stash is an offline-first Android music player that syncs your liked songs, playlists, daily mixes, and discover mixes from both **Spotify** and **YouTube Music** into a single unified local library. Tracks are downloaded as high-quality Opus audio and played through a premium Material 3 interface with a full equalizer, queue management, and smart source-aware browsing.

**Stash is not a Spotify replacement.** It's a personal-library tool for people who already have Spotify or YouTube Music accounts and want their library available offline on their terms.

---

## Features

- 🎵 **Dual-source sync** — Spotify *and* YouTube Music, combined or filtered by source
- 💾 **Offline library** — every track downloaded as Opus audio at your chosen bitrate
- 🎯 **Bulletproof matching** — album-first pipeline eliminates "wrong track, same artist" mismatches that plague simpler tools
- 🎚️ **Full equalizer** — 5-band EQ with presets, bass boost, and virtualizer
- 📱 **Premium UI** — Jetpack Compose, Material 3, Light/Dark/System themes
- 🔁 **Smart playback** — queue management, drag-to-reorder, swipe-to-remove
- 🔒 **Private by design** — all credentials stored locally with AES-256-GCM, nothing ever leaves your device except the calls to Spotify/YouTube you'd make anyway
- 🔓 **Free and open source** — no subscriptions, no ads, no telemetry, GPL-3.0

## Screenshots

*Coming with the first release.*

---

## Requirements

- Android **8.0 (API 26)** or later
- Roughly **5-10 GB** of free storage for a medium library (scales with your library size)
- An active **Spotify account** (free or premium) and/or a **YouTube Music account**
- Willingness to **sideload APKs** — Stash is not on the Google Play Store and won't be (see [Why Not Play Store?](#why-not-play-store) below)

---

## Installation

### Option 1 — Download the APK (recommended)

1. Open **[the Releases page](https://github.com/rawnaldclark/Stash/releases)** on your Android device's browser.
2. Download the latest `Stash-v*.apk` file.
3. Open the downloaded file.
4. If Android warns you about "installing from unknown sources," tap **Settings** and allow it for your browser, then try opening the file again.
5. Tap **Install** when prompted.
6. Done — open Stash from your app drawer.

### Option 2 — Auto-update via Obtainium (advanced)

[Obtainium](https://obtainium.imranr.dev/) is a free app that tracks GitHub Releases and notifies you when a new version is out. If you don't want to manually re-download APKs each release:

1. Install Obtainium.
2. Tap **Add App** and paste `https://github.com/rawnaldclark/Stash`.
3. Obtainium will now prompt you to update whenever a new Stash release ships.

### Option 3 — Build from source

```bash
git clone https://github.com/rawnaldclark/Stash.git
cd Stash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

You'll need **Android Studio** (Hedgehog / 2023.1.1 or later), **JDK 17**, and **Android SDK 35**. Open the project in Android Studio, let Gradle sync, then Run.

---

## First-Time Setup

Stash doesn't use Spotify's or YouTube's official APIs (they don't offer what Stash needs). Instead, you paste in your login cookies from a web browser. This sounds scary but takes about two minutes per service. Your cookies live **only on your phone**, encrypted with AES-256-GCM, and are sent **only to Spotify and YouTube themselves** — never to a Stash server (there isn't one).

<details>
<summary><b>🎵 Connect Spotify (click to expand)</b></summary>

### What you need
- A computer or another device with a desktop browser (Chrome, Firefox, Edge, or Safari)
- Your Spotify account logged in on that browser

### Steps

1. On your computer, open **[https://open.spotify.com](https://open.spotify.com)** and make sure you're logged in.
2. Press **F12** on your keyboard to open Developer Tools. A panel will open on the right or bottom of your browser.
3. Find the **Application** tab at the top of the DevTools panel (on Firefox it's called **Storage**). If you don't see it, click the `>>` arrows to find it.
4. In the left sidebar of that tab, expand **Cookies** → click **`https://open.spotify.com`**.
5. You'll see a list of cookies. Find the one named **`sp_dc`**.
6. Double-click the value next to `sp_dc` and copy it (Ctrl+C / Cmd+C). It's a long string of random characters.
7. Open Stash on your phone → **Settings** → tap **Spotify** under Accounts → tap **Connect**.
8. Paste the `sp_dc` cookie into the dialog and tap **Connect**.

You're done. Stash will start fetching your Liked Songs, daily mixes, and playlists.

> **Why a cookie and not a password?** Spotify's mobile login API doesn't allow third-party apps. The cookie approach lets Stash authenticate as your browser session does. The cookie is session-scoped and can be revoked by logging out of Spotify on the web.

</details>

<details>
<summary><b>📺 Connect YouTube Music (click to expand)</b></summary>

### What you need
- A computer or another device with a desktop browser
- Your YouTube Music account logged in on that browser

### Steps

1. On your computer, open **[https://music.youtube.com](https://music.youtube.com)** and make sure you're logged in.
2. Press **F12** to open Developer Tools.
3. Click the **Network** tab at the top of DevTools.
4. Refresh the YouTube Music page (F5 / Cmd+R).
5. In the Network tab's filter/search box, type **`browse`** and press Enter.
6. Click any of the requests in the list (they should all start with `browse`).
7. Scroll down in the right panel until you find **Request Headers**.
8. Find the line starting with **`cookie:`** and copy the *entire* value after `cookie:` — it will be a very long string with many `=` and `;` characters.
9. Open Stash on your phone → **Settings** → tap **YouTube Music** under Accounts → tap **Connect**.
10. Paste the full cookie string and tap **Connect**.

Stash will start fetching your YouTube Music daily mixes, discover mix, replay mix, and liked music.

> **Why the whole cookie header?** YouTube uses multiple cookies together to authenticate (`SAPISID`, `__Secure-3PAPISID`, and `LOGIN_INFO`). Grabbing all of them at once is easier than finding each individually.

</details>

### After setup

Once both services are connected, go to the **Sync** tab and tap **Sync Now**. The first sync takes a while — Stash downloads every track in your liked songs, mixes, and playlists at your chosen quality. You can set this to run automatically on a daily schedule in Sync settings.

---

## Why Not the Play Store?

Stash downloads audio from YouTube and Spotify, which violates both services' Terms of Service. Google Play policy bans apps that facilitate unauthorized downloads. Every app in this space — **NewPipe, YTDLnis, SpotTube, InnerTune** — is distributed outside the Play Store for the same reason.

That's not a bug, it's a principled stance: open-source tools that give users control over their own libraries don't belong in a gatekept store that could revoke them on a whim. Distribution via GitHub Releases and **F-Droid** (once we're ready) is the right home for Stash.

---

## Privacy and Security

- **Nothing leaves your device** except the API calls to Spotify and YouTube themselves.
- **No analytics**, no telemetry, no crash reporting to third parties.
- **Cookies are encrypted** at rest with AES-256-GCM via Google's [Tink](https://developers.google.com/tink) library.
- **No Stash servers** exist. There's no account, no backend, no "cloud sync" of anything.
- **All code is open source** and auditable — see the repo.

If you find a security issue, please see [SECURITY.md](SECURITY.md) for responsible disclosure guidelines.

---

## Legal Disclaimer

Stash is an independent, unofficial project. It is **not affiliated with, endorsed by, or sponsored by Spotify AB, YouTube LLC, Google LLC, or Alphabet Inc.** All trademarks are the property of their respective owners.

Stash is provided **for personal use only** as a tool for managing your own library. You are responsible for complying with the Terms of Service of any music service you use Stash with. Downloading copyrighted content without a license may be illegal in your jurisdiction. The Stash project accepts no responsibility for misuse.

---

## Contributing

Contributions are welcome. Issues and pull requests through GitHub are the primary channel. Before sending a large PR, please open an issue to discuss the change.

Stash is licensed under **GPL-3.0**, which means:
- You can use, copy, modify, and redistribute Stash freely.
- If you distribute a modified version, you must also release your source code under GPL-3.0.
- No warranty is provided.

See the [LICENSE](LICENSE) file for the full text.

---

## Acknowledgments

Stash stands on the shoulders of several open-source projects:

- **[yt-dlp](https://github.com/yt-dlp/yt-dlp)** — the backbone of all YouTube downloading
- **[JunkFood02/youtubedl-android](https://github.com/JunkFood02/youtubedl-android)** — Android bindings for yt-dlp
- **[QuickJS-NG](https://github.com/quickjs-ng/quickjs)** — lightweight JS engine for YouTube's signature challenges
- **[Media3 / ExoPlayer](https://github.com/androidx/media)** — audio playback
- **[ytmusicapi](https://github.com/sigma67/ytmusicapi)** — YouTube Music API reverse-engineering reference
- **[Bungee Shade](https://fonts.google.com/specimen/Bungee+Shade)** — the retro wordmark font, by David Jonathan Ross (SIL OFL)

---

## License

Copyright © 2026 Rawnald Clark

Stash is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.
