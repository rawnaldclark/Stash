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

- Credential or cookie leakage (Spotify sp_dc, YouTube cookies, any other secrets)
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

- **Storage**: All tokens and cookies are encrypted at rest using Google's [Tink](https://developers.google.com/tink) library with AES-256-GCM. The encryption key is generated per-install and stored in Android's hardware-backed Keystore.
- **Transport**: Credentials are only sent to `open.spotify.com`, `clienttoken.spotify.com`, and `music.youtube.com` (the same hosts your web browser uses). TLS 1.2 or higher is enforced.
- **No server**: Stash has no backend. There is no Stash account. There is no telemetry. There is no "cloud sync." Your credentials never leave your phone except when authenticating with the actual music service.
- **Open source**: Every line of code that touches credentials is in this repo and can be audited. See `core/auth/` and `data/spotify/` and `data/ytmusic/`.

## Response Timeline

For reports filed via GitHub Security Advisories:

- **72 hours** — initial acknowledgment that we've seen the report.
- **7 days** — first assessment with a severity rating and whether it's accepted as in-scope.
- **30 days** — target for a fix to land in a release build, assuming the issue is reproducible and actionable.

Critical issues affecting credential handling will be prioritized above all other work.

## Thank You

Stash is a small hobby project maintained in spare time. Security researchers who report issues responsibly are genuinely appreciated and will be credited in release notes (with your permission).
