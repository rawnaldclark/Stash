# Daily lossless counter: 500 free a day, unlimited for supporters

Status: draft for the owner's review (2026-10-17). Decisions already made by the owner are marked **(decided)**.

## Goal

Every Stash install gets **500 lossless lookups a day free** (decided). Ko-fi supporters get **unlimited** (decided).
The app and the website both show the count ("Lossless today: 412 / 500"). Hitting 500 never stops the music:
playback **falls back to lossy** (Tidal AAC 320, then YouTube) until the reset (decided).

## What counts (decided)

One unit = one **lossless answer a relay serves**: a Qobuz mint or a Tidal FLAC stream/download answer.

- Counts: a new lossless stream or a lossless download served by the Qobuz or Tidal relay, including answers the
  relay serves from its own shared cache (the user still got a lossless stream).
- Does not count: replays the phone already has (StreamUrlCache, downloaded files), local files, Tidal AAC answers,
  "not available" / errors / busy answers, searches.

## Who (decided: per install)

### App

- **Identity:** the Qobuz relay already identifies installs: the app sends `X-Stash-Install` (random id generated on
  first run) signed with HMAC (`X-Stash-Ts`, `X-Stash-Auth`, key `RELAY_KEY`). The Tidal relay gets the same three
  headers and verifies them with the same key (new Worker secret `RELAY_KEY` + `RELAY_KEY_PREV`).
- **Known limit:** the install id is chosen by the app and the key ships in the APK, so a script can mint new ids.
  The per-install 500 restrains honest users only; the per-network, per-/48 and global caps (hardened by the
  2026-10-17 audit) are what stop scripts. Reinstalling resets the count (accepted).

### Website

Per signed-in stashfm.app account (the site already has email-code sign-in). The player's API calls carry the
account's subject id (hashed) instead of an install id.

## One counter across both relays

Today each relay counts in its own D1 database. A user's 500 must cover Qobuz **and** Tidal, so the count lives in
one place:

- **New D1 database `stash-quota`**, bound to both relays (and the player Worker) as `QUOTA_DB`. One table:
  `usage(day TEXT, subject TEXT, n INTEGER, PRIMARY KEY(day, subject))`, subject = `i:<install id>` or
  `a:<account hash>`. Increment with one atomic upsert after a lossless answer is served; read before serving.
- The Qobuz relay's existing `INSTALL_DAILY_CAP` (already "500") moves to this shared count; the per-relay install row
  stays only as abuse telemetry.
- Alternative considered: a Durable Object per subject (strictly serialised counts). Not needed: a race can at most
  serve a couple of answers past 500, and D1 keeps the design to one table both Workers already know how to use.

**Reset:** midnight UTC, one moment for everyone. The app and site show it in local time ("resets at 2:00 AM").

## Supporters: unlimited (decided: Ko-fi email claim)

Revive the June 2026 design (branch `feat/kofi-supporter-tier`: offline-signed Ed25519 entitlement), with one change:

- **Proof of email.** The June design accepted a typed email, so anyone could claim with a known supporter's
  address. The claim becomes: enter the Ko-fi email → the existing mailer Worker sends a 6-digit code → enter the code
  → the tip-jar Worker checks the email hash against Ko-fi donors (ACCESS_KV `access:<hash>`, already written by the
  Ko-fi webhook) → returns a signed token `{tier:"supporter", iat, tid, v:1}` (no personal data).
- **Use:** the app stores the token and sends it as `X-Stash-Entitlement` on relay calls. Both relays verify the
  Ed25519 signature with the public key (Worker var) and skip the 500 check. The website marks a signed-in donor's
  session as a supporter the same way (server side, no token in the browser).
- **Sharing:** tokens are not device-locked (June decision: frictionless reinstall). To keep a posted token from
  becoming a free pass for everyone: a per-token soft cap (e.g. 5,000 lossless a day, far above any listener) and a
  revocation list (`tid` in KV) the relays check.
- **Abuse caps still apply to supporters:** per-network, per-/48 and global caps are about protecting the pools,
  not about the free tier. (Open question 3.)

## At the limit (decided: fall back to lossy)

- Relays answer `429 {"error":"daily_lossless_cap", "used":500, "limit":500, "resetsAt":<epoch s>}` with
  `Retry-After` = seconds to the reset.
- **App:** stores "lossless spent until resetsAt" and skips the Qobuz and Tidal lossless rungs entirely until then
  (no requests). Streaming falls to Tidal AAC 320 (the Tidal relay keeps serving `?aac=1` answers: AAC never counts),
  then JioSaavn / YouTube. Downloads take the same lossy path; the existing FLAC-upgrade sweep upgrades them later.
  The quality badge already shows AAC / lossy, so no silent downgrade.
- **Website:** the same fallback in the player's source order (lossless plugin → Tidal AAC → JioSaavn → YouTube).

## The counter in the UI

- **Every lossless answer** carries `X-Stash-Lossless: <used>/<limit>` (or `<used>/unlimited`) and
  `X-Stash-Lossless-Reset: <epoch s>`; a new `GET /quota` on each relay returns the same as JSON for a cold start.
  The app keeps the latest value in memory and DataStore.
- **App placement (proposal):** a small line in Settings → Audio & Quality ("Lossless today: 412 of 500 · resets
  2:00 AM"), the same line in the Now Playing quality sheet, and a one-time snackbar at 450 and at 500 with the
  supporter nudge ("Unlimited lossless for supporters: any amount on Ko-fi"). Supporters see "Unlimited (supporter)".
- **Website (proposal):** the same line in the player's settings panel and under the quality badge in Now Playing.

## Build plan

1. **Shared count:** create `stash-quota` D1 + migration; bind to both relays; Tidal relay verifies install headers.
   App sends the install headers to the Tidal relay too.
2. **Counting + 429 + headers** in both relays; `/quota` endpoints; tests. App: parse headers, spent-until state,
   skip lossless rungs, AAC fallback; counter line + snackbars.
3. **Supporters:** tip-jar `/claim/email/start` (send code) and `/claim/email/verify` (token); Ed25519 keypair (private
   = tip-jar secret, public = relay vars + app BuildConfig); app "Stash Supporter" screen (claim, status); relays
   honour `X-Stash-Entitlement`; revocation KV.
4. **Website:** player API passes the account subject; supporter sessions skip the count; counter in the player UI.

Each step ships behind the existing release flow; steps 1–2 can go out before supporters exist (everyone gets 500,
which is today's Qobuz behaviour).

## Open questions for the owner

1. **Reset time:** midnight UTC for everyone (proposed), or midnight in each user's local time?
2. **Shared token cap:** is 5,000 lossless a day per supporter token a fine safety ceiling?
3. **Supporters and the network cap:** should a supporter skip the per-network 2,000/day cap too? (Proposed: no;
   it protects the pools and a single listener never gets near it.)
4. **Where the counter shows in the app:** Settings + Now Playing sheet + two snackbars (proposed), or also somewhere
   always visible (e.g. Home header)?
5. **Downloads at the limit:** lossy now and upgrade later (proposed), or hold lossless downloads until the reset?
