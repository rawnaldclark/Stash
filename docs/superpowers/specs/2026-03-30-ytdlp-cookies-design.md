# yt-dlp YouTube Cookie Authentication — Design Spec

**Date:** 2026-03-30
**Status:** Approved
**Problem:** yt-dlp downloads fail with "Sign in to confirm you're not a bot" because YouTube requires authenticated requests. The user's YouTube cookies are already stored in TokenManager but not passed to yt-dlp.

## Solution

Pass the user's stored YouTube cookies to yt-dlp via the `--cookies` flag using a temporary Netscape-format cookie file.

### 1. CookieFileWriter — New Utility

**File:** `data/download/src/main/kotlin/com/stash/data/download/CookieFileWriter.kt`

Converts a browser cookie string (e.g., `SAPISID=abc; SID=def; ...`) into a Netscape HTTP Cookie File.

**Format per line:** `domain\tflag\tpath\tsecure\texpiry\tname\tvalue`

**Rules:**
- Domain: `.youtube.com` for all cookies
- Flag: `TRUE` (domain-wide)
- Path: `/`
- Secure: `TRUE` (YouTube uses HTTPS)
- Expiry: `0` (session cookie)
- Header line: `# Netscape HTTP Cookie File`
- Newlines: `\n` (Unix format — Android is Linux)

**Parsing:** Split cookie string on `;`, trim each pair, split on first `=` to get name/value. Skip malformed pairs.

**Header:** `# Netscape HTTP Cookie File` followed by a blank line before cookie entries (canonical format).

### 2. DownloadExecutor Changes

**File:** `data/download/src/main/kotlin/com/stash/data/download/DownloadExecutor.kt`

**Changes:**
- Add `TokenManager` as constructor dependency
- Before yt-dlp execute: get cookie via `tokenManager.getYouTubeCookie()`. If null, skip `--cookies` (attempt unauthenticated download). If present, write to a unique file `context.noBackupFilesDir/yt_cookies_${System.nanoTime()}.txt` via `CookieFileWriter`, add `--cookies` option to request
- After yt-dlp execute (in `finally` block): delete the cookie file
- Unique filenames per download prevent concurrent downloads (Semaphore allows 3) from colliding on the same cookie file

**Security:**
- Cookie file written to `noBackupFilesDir` (excluded from Android Backup)
- File exists only during yt-dlp execution (seconds), deleted in `finally` block
- App sandbox provides kernel-level isolation from other apps

### Files Changed

| File | Change |
|------|--------|
| `data/download/.../CookieFileWriter.kt` | New — cookie string to Netscape file converter |
| `data/download/.../DownloadExecutor.kt` | Modified — add TokenManager dep, write/delete cookie file around yt-dlp calls |
| `data/download/build.gradle.kts` | Modified — add `implementation(project(":core:auth"))` for TokenManager access |

### Out of Scope

- Spotify client token fix (separate spec)
- YouTube Liked Songs parsing (separate issue)
- yt-dlp JavaScript runtime warning (non-blocking warning, not an error)
- Cookie refresh/rotation (cookies are read fresh from TokenManager on each download)
