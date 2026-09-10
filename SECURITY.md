# Security Policy

## Reporting a Vulnerability

If you discover a security issue in Stash — particularly anything related to credential handling, token storage, network transport, or data leakage — please report it privately so it can be fixed before public disclosure.

**How to report:**

1. Open a **[private security advisory](https://github.com/rawnaldclark/Stash/security/advisories/new)** on GitHub. This creates a confidential channel visible only to the maintainers.
2. Include:
   - A description of the issue and its potential impact.
   - Step-by-step reproduction if possible.
   - Your suggested severity (Critical / High / Medium / Low).
   - Whether you'd like credit in the release notes when the fix ships.

Please **do not** open a public issue, tweet about it, or post it on Reddit until a fix has been released and disclosed.

## Scope

The following are in scope for security reports:

- Credential or cookie leakage (Spotify sp_dc, YouTube cookies, Discord account token, any other secrets)
- Insecure storage of encrypted values (Tink/AES-256-GCM misuse)
- Network transport issues (TLS downgrade, certificate pinning bypass)
- SQL injection, path traversal, or arbitrary file write in the download pipeline
- Code execution via crafted audio files, malicious InnerTube responses, or yt-dlp edge cases
- Any issue that could let a malicious actor with network access to your device compromise your Stash credentials or downloaded library

The following are **out of scope** (but please still mention them in a regular issue if you see them):

- Issues that require physical device access, root, or ADB debugging
- Theoretical attacks without a working proof of concept
- Dependency vulnerabilities already tracked by Dependabot (we get those automatically)
- Social engineering or phishing scenarios
- Issues in Spotify, YouTube, or Google's own infrastructure

## Handling Your Credentials

Stash is designed so that you never have to trust the project maintainers with your accounts. Here's how credentials are handled:

- **Storage**: Spotify, YouTube, and Discord tokens are encrypted at rest using Google's [Tink](https://developers.google.com/tink) with AES-256-GCM, keyed per-install in Android's hardware-backed Keystore. Other service credentials (Qobuz, ARCOD, Last.fm, ListenBrainz) are stored in app-private storage without an additional encryption layer — they are protected by Android's app sandbox and, on modern devices, full-disk encryption.
- **Transport**: Each credential is only ever sent to the service it belongs to, over TLS 1.2 or higher. Spotify cookies go to `accounts.`/`open.`/`api-partner.`/`api.spotify.com` and `clienttoken.spotify.com`; YouTube cookies to `music.youtube.com` and `www.youtube.com`; a connected Discord account's session token to `discord.com` (Rich Presence only — see the README's Discord setup section for how this differs from an official API integration); a connected Qobuz account's token to `www.qobuz.com`; a connected ARCOD account's tokens to `arcod.xyz`, `api.arcod.xyz` and ARCOD's Supabase project; Last.fm and ListenBrainz tokens to their own APIs. No credential is ever sent anywhere else, and none is sent to a Stash-run host. The full host list is in the README under "What Stash talks to".
- **No account server**: There is no Stash account, no telemetry, and no "cloud sync". A few hosts the project runs are contacted, and none of them ever receives a credential: a Cloudflare Worker serving the public supporters list (fetch only, told nothing about you); in official release builds, a caching proxy in front of Last.fm's read API, which sees the artist and track names being looked up and nothing else; and, when a release build carries a lossless relay config, the host serving that signed config plus whichever relay it lists — a relay is sent the Qobuz track id of what you're playing, and no credential.
- **Open source**: Every line of code that touches credentials is in this repo and can be audited. See `core/auth/`, `data/spotify/`, `data/ytmusic/`, `core/data/.../discord/`, `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/`, `.../lossless/arcod/`, `core/data/.../lastfm/` and `core/data/.../listenbrainz/`.

## Response Timeline

For reports filed via GitHub Security Advisories:

- **72 hours** — initial acknowledgment that we've seen the report.
- **7 days** — first assessment with a severity rating and whether it's accepted as in-scope.
- **30 days** — target for a fix to land in a release build, assuming the issue is reproducible and actionable.

Critical issues affecting credential handling will be prioritized above all other work.

## Thank You

Stash is a small hobby project maintained in spare time. Security researchers who report issues responsibly are genuinely appreciated and will be credited in release notes (with your permission).
