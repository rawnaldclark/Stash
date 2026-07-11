# Contributing to Stash

Thanks for your interest in improving Stash. This guide covers how to build the app, how the codebase is laid out, and how to get a change merged.

Stash is GPL-3.0. By contributing, you agree that your contributions are licensed under the same terms.

---

## Before you write code

**For anything substantial, open an issue (or ask on [Discord](https://discord.gg/vcbjEby5PC)) first.** A quick conversation about the approach saves everyone time — it's no fun to sink a weekend into a PR only to find the change doesn't fit the direction of the project. Small, obvious fixes (typos, crashes with a clear cause, a one-line bug) can go straight to a PR.

Good first contributions:

- Bug fixes with a clear reproduction
- Documentation improvements
- Small, self-contained UI polish
- Test coverage for existing behavior

---

## Building the app

You'll need:

- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 17**
- **Android SDK 35**

```bash
git clone https://github.com/rawnaldclark/Stash.git
cd Stash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

To install straight to a connected device:

```bash
./gradlew installDebug
```

Run the unit tests:

```bash
./gradlew test
```

> **Note:** Stash talks to third-party music services using your own login cookies (see the [README](README.md) for how that works). You don't need any of that configured to build the app or work on most of the codebase — only to exercise the sync/streaming paths end to end on a device.

---

## How the codebase is organized

Stash is a multi-module Gradle project. Modules keep responsibilities isolated so a change in one place doesn't ripple everywhere. From the top:

### `:app`
The application shell — dependency-injection wiring (Hilt), top-level navigation, and the `Application` class. Thin by design; the real work lives in the modules below.

### `:core:*` — shared foundations
| Module | Responsibility |
| --- | --- |
| `:core:ui` | Design system: Compose components, theme, shared UI building blocks |
| `:core:model` | Plain domain models shared across the app |
| `:core:common` | Cross-cutting utilities and helpers |
| `:core:data` | Persistence (database, preferences) and repository plumbing |
| `:core:media` | Playback — Media3/ExoPlayer, the player service, audio processing |
| `:core:auth` | Encrypted on-device credential storage (Tink, AES-256-GCM) |
| `:core:network` | HTTP clients and networking primitives |

### `:data:*` — external integrations
| Module | Responsibility |
| --- | --- |
| `:data:spotify` | Spotify library sync and resolution |
| `:data:ytmusic` | YouTube Music library sync and resolution |
| `:data:download` | The download pipeline (including yt-dlp) |
| `:data:lyrics` | Lyrics fetching and parsing (LRCLIB) |

### `:feature:*` — user-facing screens
Each feature module owns one area of the app: `:feature:home`, `:feature:library`, `:feature:nowplaying`, `:feature:sync`, `:feature:settings`, and `:feature:search`. Feature modules depend on `:core:*` and `:data:*`, never on each other.

### Other top-level directories
- `build-logic/` — Gradle convention plugins shared across modules
- `infra/` — small server-side helpers (Cloudflare Workers) that back some features
- `docs/` — project documentation

---

## Coding conventions

- **Kotlin**, Jetpack Compose for UI, Hilt for DI, Coroutines/Flow for async.
- Match the style of the code around you — naming, formatting, and patterns. When in doubt, look at a neighboring file.
- Keep modules honest: put shared code in `:core`, feature code in `:feature`, integration code in `:data`. Don't reach across feature modules.
- Prefer small, focused changes. One PR should do one thing.

---

## Submitting a pull request

1. Fork the repo and create a branch from `master` (e.g. `fix/playback-crash` or `feat/queue-shuffle`).
2. Make your change. Add or update tests where it makes sense.
3. Make sure `./gradlew test` passes and the app builds.
4. Open the PR against `master` and fill out the PR template — especially **what you tested and on which device**. "It compiles" isn't testing.
5. Keep the PR focused. Unrelated cleanup belongs in its own PR.

A maintainer will review as time allows. This is a small project, so please be patient — and thank you for helping.

---

## Reporting bugs and requesting features

Please use the [issue forms](https://github.com/rawnaldclark/Stash/issues/new/choose) — they ask for the details (version, device, repro steps) that make a report actionable. For questions, setup help, or open-ended ideas, the [Discord](https://discord.gg/vcbjEby5PC) is the right place.

## Security issues

Do **not** open a public issue for security vulnerabilities. Follow the disclosure process in [SECURITY.md](SECURITY.md).
