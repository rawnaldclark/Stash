# Stash Tip Jar — Cloudflare Worker

Receives Ko-fi webhook posts → persists to KV → serves the live
supporter list to the Stash Android app.

After this is deployed once, every Ko-fi tip auto-populates the Tip
Jar pill on Home with no app rebuild and no manual JSON edits.

## Architecture

```
   Ko-fi (tip received)
         │  POST webhook
         ▼
   Cloudflare Worker  ◄── daily cron (rebuilds the list)
         │  one key per supporter, then a rebuild
         ▼
   Cloudflare KV ("supporter:<time>:<id>" keys → "supporters")
         ▲
         │  GET (60s edge cache)
         │
   Stash app on phone
```

One Worker, one URL. POST = webhook receiver. GET = JSON endpoint the
app reads. Cloudflare's free tier (100k requests/day, 1GB KV) covers
~100,000× more than this needs.

### How the list is stored

Each supporter is a KV key of their own, `supporter:<time>:<id>`: the
donation's time (ISO, so the keys sort oldest to newest) and Ko-fi's
transaction id (so a Ko-fi retry writes the same key again). Those keys
are kept for good. `supporters`, the JSON the app reads, is rebuilt from
them after every donation and once a day by a cron (`[triggers]` in
`wrangler.toml`); nothing edits it in place (`src/supporters.js`).

Before 2026-10 the webhook read `supporters`, added the new donor and
wrote it back, so two donations arriving at once kept only one of the two
donors, for good. Now a rebuild that misses someone (two at once, or KV's
`list()` lagging up to a minute behind a write) loses nothing: their own
key is still there, and the next rebuild, a day later at the latest,
lists them.

**The old list moves over by itself.** Each entry in `supporters` now
names the key it came from (`key`, which the app never sees). An entry
without one is from the list as it was before 2026-10, and the first
rebuild that sees it (after the next donation, or at the next daily cron)
gives it a key of its own, keeping the list's order and the exact JSON the
app gets. There's nothing to run by hand. It's safe to repeat: the same
old list always gets the same keys (`supporter:<time>:legacy-<n>`), so two
rebuilds converting it at once, or a stale read of it, write nothing new.
A one-off import script was the other option, but it can't be timed
safely: run before the deploy, it misses donations the old code lists in
between; run after, the new code's first rebuild could already have
dropped the entries it hadn't moved.

## One-time setup (~30 min)

### 1. Create a Cloudflare account
Free at https://dash.cloudflare.com. No credit card required for the
Workers free tier.

### 2. Install Wrangler
```bash
npm install
npx wrangler login   # opens browser for OAuth
```

### 3. Create the KV namespace
```bash
npx wrangler kv:namespace create STASH_KV
```
This prints something like `id = "abc123..."`. Paste that id into
`wrangler.toml` replacing `REPLACE_WITH_NAMESPACE_ID`.

### 4. Get your Ko-fi verification token
Go to https://ko-fi.com/manage/webhooks. Copy the **Verification Token**
shown on that page.

### 5. Set the token as a Worker secret
```bash
npx wrangler secret put KOFI_VERIFICATION_TOKEN
```
Paste the token when prompted.

### 6. Deploy
```bash
npx wrangler deploy
```
Wrangler prints the deployed URL, like `https://stash-tipjar.<your-account>.workers.dev`.

### 7. Configure the Ko-fi webhook
Back at https://ko-fi.com/manage/webhooks, set the **Webhook URL** to
the Worker URL from step 6. Save.

### 8. Point the Stash app at the new URL
Edit `app/build.gradle.kts`:
```kotlin
buildConfigField(
    "String",
    "SUPPORTERS_JSON_URL",
    "\"https://stash-tipjar.<your-account>.workers.dev\"",
)
```
Rebuild the app and ship.

## Verifying it works

### Send a fake webhook locally
Ko-fi has a "Send Test Donation" button on their webhook page. Click it.
Then `curl https://stash-tipjar.<your-account>.workers.dev` — you
should see the test donation in the JSON.

### Watch live logs
```bash
npx wrangler tail
```
Shows every webhook + GET in real-time. Useful for debugging.

## Cost

Free, indefinitely, for any realistic Stash supporter volume.
- Workers: 100,000 requests/day on the free plan.
- KV: 100,000 reads/day, 1,000 writes/day, 1GB storage.

If Stash had 1,000 daily active users each opening Home twice = 2,000
Worker requests/day. KV writes happen only when a tip arrives (a few per
tip), so maybe 30/day at the high end. A rebuild reads the supporters
with one KV `list()` per 1,000 of them (each key carries its entry as
metadata), and the daily cron writes only when the list changed. Well
under every limit.

## Schema the Worker exposes

```jsonc
{
  "supporters": [
    { "name": "Cedric", "amountUsd": 10, "message": "Just downloaded..." },
    { "name": "Slowcab", "amountUsd": 5,  "message": "Amazing work!..." }
  ]
}
```

Sorted newest-first. Capped at the 500 most recent entries (bumpable
via `SUPPORTERS_LIMIT` in `src/supporters.js`). Older supporters keep
their keys; they just aren't listed.

## Migrating off Cloudflare

If you ever want to leave Cloudflare, the Worker code is ~50 lines and
ports to:
- **Deno Deploy**: change `env.STASH_KV.put` to Deno KV API (`Deno.openKv()`).
- **Vercel Functions**: change to a Vercel Edge function + Vercel KV.
- **Self-hosted Node/Express**: change KV calls to a redis client.

The schema the Worker exposes (the GET response shape) stays
identical, so the Stash app needs no changes when you migrate.

## Adding or removing a supporter by hand

Don't edit `supporters` itself: it's rebuilt from the `supporter:*` keys
(see "How the list is stored"). To add someone, give them a key of their
own, with the time first so they sort into place:
```bash
npx wrangler kv key put --binding STASH_KV --remote "supporter:2026-10-11T12:00:00.000Z:manual-1" '{"name":"Cedric","amountUsd":10,"message":"Just downloaded...","timestamp":"2026-10-11T12:00:00.000Z"}'
```
To take someone off, delete their `supporter:` key
(`npx wrangler kv key delete --binding STASH_KV --remote "<key>"`). Either
change reaches the app at the next rebuild: the next donation, or the
daily cron. The Cloudflare dashboard's KV editor works too.

## Lossless relay config

The same Worker also serves `GET /lossless.json` and `GET /lossless.json.sig` —
the signed relay list the Stash app fetches at every cold start. They come
byte-for-byte from the KV keys `lossless_config` / `lossless_config_sig`, which
only `infra/lossless-relay/scripts/publish-config.mjs` writes. 404 until the
first publish. Nothing about supporters changes.

## stashfm.app early access and the monthly goal

Since 2026-10 the webhook does two more things for each Donation or
Subscription (`src/access.js`). They're written to a KV namespace of their
own, `ACCESS_KV`, which the website Worker (`web/worker`) binds too; the
website never sees `STASH_KV`. The webhook URL and the GET JSON above don't
change.

- **Early access, at $5 or more, added up per email** (since 2026-11-02;
  `ENTRY_USD_CENTS` in `src/access.js`). Once an email's Ko-fi payments add
  up to US$5 it gets an `access:<hash>` entry, so they can sign in to
  stashfm.app with it, and the welcome email. Only the hash is stored, never
  the email.
  - One-off donations and monthly payments both count, in any order.
  - Other currencies count at `USD_PER_UNIT` (below). A currency missing
    from that table can't be checked, so that donor is let in, and the log
    says so.
  - Under $5, the payment is kept as `kofipay:<hash>:<transaction id>`
    (`{cents, at}`, also as metadata) and nothing is granted yet. The next
    payment adds them all up with `list({prefix: "kofipay:<hash>:"})`, plus
    itself (so a lagging `list()` or a Ko-fi retry can't miss or double it).
    The log says `access: under the entry so far (300 of 500 US cents)`.
    Without `EMAIL_PEPPER` nothing is kept (the hash would be a plain,
    guessable SHA-256): only that payment counts, and the log says so.
  - **Everyone let in before 2026-11-02 keeps their place**, whatever they
    gave: an email that already has a `kofi` entry stays in on any new
    payment (only `lastAt` moves, as before) and keeps no amount. An
    `approved` or `manual` entry is left exactly as it is until the email
    reaches $5; then it becomes `kofi`, as any donation did before.
  - Known limit: two payments from one email whose webhooks land within a
    minute of each other (KV's `list()` lag) may each miss the other, so
    neither writes `access:<hash>`. The app's supporter claim still works for
    them: it adds up the `kofipay` entries itself (below). For the website,
    their next payment from that email adds everything up and lets them in;
    to let them in sooner, add the email by hand on the admin page (that's
    a `manual` entry: website access only, which is all that's missing).
  - The supporters list (the app's ticker) and the goal still count every
    payment, under $5 too.
- **The monthly goal.** Each donation is its own entry,
  `goal:<YYYY-MM>:kofi:<transaction id>` (UTC month), with the amount in US
  cents in the key's metadata. The website adds up a month's entries. Other
  currencies are converted with the fixed, approximate table `USD_PER_UNIT`
  in `src/access.js`; a currency missing from it isn't counted (the donor
  is still let in, as above).
- **Retries.** Ko-fi resends a webhook that didn't get a 200, with the same
  ids. `kofitxn:<id>` (in `STASH_KV`, 60 days; the transaction id, or the
  message id if there's none) is `listed` once the supporter's own key is
  written and `done` once the list is rebuilt and early access and the goal
  are written. If any of that fails, the webhook answers 500 so Ko-fi tries
  again, and the retry doesn't store the supporter again (if it did, it
  would write the same key), so nobody is listed twice. A retry of a `done`
  transaction changes nothing.
- Ko-fi's **"Send test"** webhooks (transaction id
  `00000000-1111-2222-3333-444444444444`) never reach the goal or the
  access list. They reach the supporters list the first time only: like any
  transaction, a test is then marked done for 60 days, so sending another
  in that time changes nothing (before 2026-10, every test was listed).
- **The welcome email.** When a donation gives an email access for the first
  time (no `access:<hash>` entry yet), the donor is emailed the welcome
  ("You're in: the Stash web player"). The tip jar doesn't send it itself: it
  asks the `stash-mailer` Worker (rawnaldclark/stash-web-workspace,
  `mailer/`) through the service binding `MAILER`, with the shared
  `MAILER_TOKEN` secret, after the webhook has answered (`waitUntil`), so a
  slow or failing mailer never slows or fails the webhook. Logs show the
  address masked (`a***@e***.org`). A later donation from the same email, a
  Ko-fi retry and Ko-fi's test webhook send nothing. Without `MAILER` or
  `MAILER_TOKEN`, nobody is emailed and everything else works as before.
  Deploy `stash-mailer` first: this Worker's deploy fails while the service
  it binds doesn't exist.
- Without `KOFI_VERIFICATION_TOKEN` set, every webhook gets a 500 (before,
  a payload with no token would have passed). Tokens are compared in
  constant time.

**The email hash.** Trim and lowercase the email, then HMAC-SHA256 it keyed
with the `EMAIL_PEPPER` secret (hex).
The website Worker and `scripts/import-kofi-csv.mjs` hash the same way, and
the tests in both places check the same vectors. **The pepper is required**:
the website won't sign anyone in without one, and the import refuses to run.
It means someone with a copy of the list can't check whether an email they
guess is on it without the secret too. Set the same `EMAIL_PEPPER` on both
Workers, and in the environment when you run the import, before anything
writes `access:*`. Changing it later locks every
supporter out until they're imported again. If the two Workers disagree, the
website's admin page says so (it compares `meta:hashcheck`).

### Importing past supporters

The webhook only sees new donations. To give everyone who supported before
early access, once, before launch:

1. On Ko-fi, export your transaction history as CSV.
2. From `infra/tipjar-worker`, with `EMAIL_PEPPER` set in the environment
   to the Workers' value (the script refuses to run without it):

   ```bash
   node scripts/import-kofi-csv.mjs path/to/kofi-export.csv
   ```

   It prints counts only, never an email or a row, and writes the entries to
   a JSON file in a new folder of its own in your temp folder, readable only
   by you (`--out <file>` to choose; it never writes over a file). Rows count
   when their type is a donation, subscription, membership or tip
   (`--all-types` to include shop orders and commissions too).
3. Upload it with the command it prints:
   `npx wrangler kv bulk put <file> --binding ACCESS_KV --remote`
4. Delete the JSON file and the CSV.

Re-running is safe: each entry is rewritten with the same hash. The import
doesn't read amounts, so it only takes rows dated before the $5 entry
(`ENTRY_SINCE`, 2026-11-02): those donors keep their place whatever they
gave. Rows from then on, and rows it can't date, are skipped and counted
("Skipped, from 2026-11-02 on or undated"); since then the webhook lets an
email in by amount. After a re-import (say, to recover from an
`EMAIL_PEPPER` mismatch), add anyone who has given $5 or more since
2026-11-02 by hand: on the admin page for the website, and in
`COMPED_EMAILS` for the app's supporter claim.

## Supporter claim

A Ko-fi donor proves they own the email they donated with and gets an
offline-signed **supporter token** (unlimited lossless; design:
`docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md`,
"Supporters"). Code: `src/claim.js`, `src/entitlement.js`. Every other
POST path is still the Ko-fi webhook and every GET path the list, as before.

| Request | Answers |
| --- | --- |
| `POST /claim/email/start` `{"email"}` | `202 {"ok":true}` for every valid address, donor or not; `400 {"error":"bad_email"}`; `400 {"error":"bad_request"}` (not a JSON object); `429 {"error":"rate_limited"}` + `Retry-After`; `503 {"error":"not_configured"}` |
| `POST /claim/email/verify` `{"email","code"}` | `200 {"token"}` (the claim is consumed); `401 {"error":"bad_code"}` for every failure: wrong code, no pending claim, expired, tries used up (the claim is deleted after the 5th try); `400 bad_email / bad_request`; `429 {"error":"rate_limited"}` + `Retry-After` (burst limit, per IP or per address); `503 {"error":"not_configured"}`; `503 {"error":"unavailable"}` + `Retry-After` (`CLAIMS_DB` down) |

- **Who counts as a donor:** an `access:<hash>` entry with `source: "kofi"`
  (the webhook, at $5 or more added up per email, and the Ko-fi CSV import
  write it; everyone from before the $5 entry keeps theirs), or `kofipay`
  entries that add up to $5 or more (the webhook race above), or an email in
  the `COMPED_EMAILS` secret. The website's `approved`, `manual` and `open`
  entries are early access, not donations. Someone added by hand on the
  admin page can't claim until their Ko-fi donations reach $5, or until
  they're in `COMPED_EMAILS` (the way to give the claim to a PayPal or
  GitHub Sponsors donor).
- **start never reveals donor status.** The answer is the same 202 either
  way, and the lookup, the claim write and the email all happen after the
  response (`waitUntil`). A donor's email holds a 6-digit code, valid 10
  minutes. Anyone else's says it isn't unlocked yet and that $5 or more on
  Ko-fi (ko-fi.com/rawnald) unlocks unlimited lossless, and donations add
  up: an address with Ko-fi payments under $5 (`kofipay:<hash>:*`) gets the
  `below_entry` email with its total so far, any other the `no_donation`
  one. Both count against the no-donation cap.
  At most one email per accepted start (see the no-donation cap below).
- **verify never reveals donor status either.** No pending claim, an
  expired one, a wrong code and used-up tries all answer the same
  `401 {"error":"bad_code"}`, byte for byte, after the same work (two
  HMACs, one D1 statement, one constant-time compare). The app can only say
  "that code didn't work, ask for a new one".
- **Pending claim:** a row in the D1 database `stash-claims` (binding
  `CLAIMS_DB`, schema `migrations/0001_claims.sql`), keyed by the email
  hash: `code_hash` = HMAC-SHA256 with `EMAIL_PEPPER` of
  `claim-code:v1:<hash>:<code>` (never the code; useless without the pepper,
  so a database read can't brute-force the million codes), `tries`,
  `expires_at` (10 minutes). Each verify takes a try with one
  `UPDATE ... SET tries = tries + 1 WHERE ... AND tries < 5 AND expires_at > now RETURNING`
  before comparing, and a right code is consumed with one
  `DELETE ... RETURNING`: parallel requests can't get a 6th guess or mint two
  tokens from one code. 5 tries in all; the 5th may still be the right code.
- **A claim is stored only once the mailer says sent.** A start the mailer
  refuses (its one-per-minute-per-address limit, or a failure) leaves the
  previous claim and the code its owner holds untouched (still 202). A start
  it sends replaces the claim: new code, tries back to 0.
- **Rate limits:** per IP 5 starts an hour and per email 3 an hour (KV
  counters `claimrl:ip|email:<hash>:<hour>`, the IP keyed with
  `EMAIL_PEPPER`, never stored raw), plus the `CLAIM_RL` binding
  (`[[ratelimits]]`, namespace `1004`, 10 a minute per key: start per IP,
  verify per IP and per email hash, `v:<hash>`). "Per IP" is the IPv4
  address or the IPv6 /64 (one host gets a whole /64). KV isn't atomic: a
  burst of parallel requests can slip a few past an hourly limit; the
  binding bounds that, and nothing secret depends on those counters.
- **No-donation cap:** at most 30 "no donation found" emails an hour, all
  addresses together (an atomic counter in `CLAIMS_DB`, `claim_counters`
  `nodonation:<hour>`). Past it those emails are skipped without a word
  (still 202), so start can't be used to mail strangers in bulk. Code emails
  to donors don't count against it.
- **Cleanup:** the daily cron also drops expired claims and counters (every
  query already ignores them).
- **Logs** never carry the email (not even masked), the code, the IP or the token.

### The token

```
payload = {"tier":"supporter","iat":<epoch seconds>,"tid":"<32 hex>","v":1}   (JSON.stringify, this key order)
token   = b64url(UTF-8 payload) + "." + b64url(Ed25519 signature over the ASCII bytes of the first segment)
```

`b64url` is base64url without padding. No personal data: `tid` is 16
random bytes. The relays and the app verify it with the public key.

- Secret **`ENTITLEMENT_PRIVATE_KEY`**: standard base64 of the PKCS#8 DER
  Ed25519 private key. Never committed. Without it start and verify answer 503.
  No other new secret: the code hashes are keyed with the existing
  `EMAIL_PEPPER`.
- **Production public key** (raw 32 bytes, standard base64):
  `IbghbSB8gJcuHbpSEf8tRKfCwzBfB14eBPew3nVkQFQ=`

Load the secret from a PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`) without
the key touching a file in the repo or your shell history:

```bash
# PEM -> one line of base64 of the DER (the PEM body is exactly that), piped straight in:
grep -v -- '-----' /path/outside/repo/entitlement-private.pem | tr -d '\r\n' | npx wrangler secret put ENTITLEMENT_PRIVATE_KEY
# Check it matches the public key above before deploying:
openssl pkey -in /path/outside/repo/entitlement-private.pem -pubout -outform DER | tail -c 32 | base64
```

### Deploying the claim's database (once, before the first deploy with `CLAIMS_DB`)

`wrangler.toml` binds `CLAIMS_DB` with `database_id = "TO_BE_CREATED"`, so a
deploy fails until this is done, on purpose:

```bash
cd infra/tipjar-worker
npx wrangler d1 create stash-claims          # prints the database_id
# paste that id into wrangler.toml ([[d1_databases]] CLAIMS_DB), commit it
npx wrangler d1 migrations apply stash-claims --remote
npx wrangler deploy
```

Claims pending in KV (`claim:<hash>`, written by the version before D1)
are ignored after this deploy and expire by themselves within 10 minutes;
anyone mid-claim asks for a new code.

### The mailer route it needs (stash-mailer)

The emails are stash-mailer's templates, like the welcome, so the
shared `MAILER_TOKEN` can't send arbitrary text. The tip jar calls:

```
POST https://stash-mailer/supporter-claim   Authorization: Bearer <MAILER_TOKEN>
  {"email":"<addr>","kind":"code","code":"123456"}
  {"email":"<addr>","kind":"no_donation"}
-> 200 {"sent":true}   (anything else is logged as not sent)
```

Only `200 {"sent":true}` stores a code. The mailer's `200 {"sent":false,"error"}`
(its per-address limit, or Email Service refusing), a 429, any other status
or a network error all leave the pending claim as it was.

**Deploy stash-mailer with this route first**; until then every claim
email fails (start still answers 202). Suggested copy:

- `code`: subject "Your Stash supporter code" (no code in the subject: it
  shows on lock screens); body "Your Stash supporter code: 123456. Enter it
  in the Stash app within 10 minutes. If you didn't ask for it, you can
  ignore this email."
- `no_donation`: subject "Stash supporter: no donation found"; body "We
  couldn't find a Ko-fi donation for this email address. $5 or more on
  Ko-fi (https://ko-fi.com/rawnald) unlocks unlimited lossless, and
  donations add up. Donate with this same address, then try again in the
  Stash app."
- `below_entry` (`{"email","kind":"below_entry","cents":300}`, cents 1 to
  499: the address's Ko-fi total so far, in US cents): subject "Stash
  supporter: not unlocked yet"; body thanks them, says unlimited lossless
  starts at $5 and how much this address has given so far, and how much
  more unlocks it. A mailer that doesn't know this kind answers
  `400 {"error":"bad_kind"}`, and the tip jar sends `no_donation` instead.
  The exact copy is the mailer's (`mailer/EMAIL-COPY.md`).

Its own per-address limit should use a key apart from the welcome's.

### Revoking a token

See `scripts/revoke-token.md`: insert the token's `tid` into the
`revoked_tokens` table of the `stash-quota` D1 database, which the relays
check:

```bash
npx wrangler d1 execute stash-quota --remote --command "INSERT INTO revoked_tokens(tid,revoked_at,reason) VALUES('<tid>',strftime('%s','now'),'shared publicly')"
```

## KV keys

Every key the tip jar and the website use, in one place. `<hash>` is the
email hash above.

**`STASH_KV`** (the tip jar only):

| Key | Written by | Value | Kept |
| --- | --- | --- | --- |
| `supporter:<time>:<id>` | tip jar webhook; the first rebuild moves each pre-2026-10 entry to `supporter:<time>:legacy-<n>` | One supporter: `{name, amountUsd, message, timestamp}`, also as metadata when it fits | Forever |
| `supporters` | tip jar webhook and its daily cron, rebuilt from the `supporter:*` keys | The list the app reads (`GET /`): the newest 500, each with the `key` it came from | Until rebuilt |
| `lossless_config`, `lossless_config_sig` | `infra/lossless-relay/scripts/publish-config.mjs` | The signed relay config, served byte for byte | Until replaced |
| `kofitxn:<id>` | tip jar webhook | `listed` (the supporter's key is written) or `done`: how far this Ko-fi donation got | 60 days |

**`ACCESS_KV`** (the tip jar and the website, `web/wrangler.jsonc`):

| Key | Written by | Value | Kept |
| --- | --- | --- | --- |
| `access:<hash>` | tip jar webhook, the import script, the website's admin page, the website's open window | `{source: "kofi", firstAt, lastAt}`, or `{source: "approved" or "manual", by, at}`; `by` is the maintainer who added it. An open-window sign-up (`web/worker/open.js`) is `{source: "open", at}` with the same in metadata; a Ko-fi donation that brings the email to $5 overwrites it as a supporter | Until removed on the admin page |
| `kofipay:<hash>:<id>` | tip jar webhook (since 2026-11-02, with `EMAIL_PEPPER` set) | One Ko-fi payment from an email not yet on the list as `kofi`, in US cents: `{cents, at}`, the same as metadata; `<id>` is Ko-fi's transaction id. Added up toward the $5 entry, and read by the claim (a code at $5 or more, else its `below_entry` email) | Until the email is removed on the admin page (Remove access deletes them too) |
| `goal:<YYYY-MM>:kofi:<id>` | tip jar webhook | One Ko-fi donation; metadata `{cents, source, at, orig?}` | Forever |
| `goal:<YYYY-MM>:manual:<id>` | the website's admin page | One donation added by hand; metadata `{cents, source, at, by}` | Until removed on the admin page |
| `meta:hashcheck` | tip jar webhook (when it changes), the import script | The hash of `hashcheck@stashfm.app`, to spot a pepper mismatch | Until replaced |
| `code:<hash>:<browser>` | website | Up to 3 live sign-in codes one browser asked for (their HMACs and expiry times) and that browser's wrong tries so far; `<browser>` is its `stash_signin` cookie | 10 minutes after the newest code |
| `sends:<hash>` | website | How many codes went to this email this hour | 1 hour |
| `claimrl:ip:<iphash>:<hour>`, `claimrl:email:<hash>:<hour>` | tip jar, `POST /claim/email/start` | How many starts this IP (HMAC with `EMAIL_PEPPER`) or email made this hour | The hour, plus a minute |
| `request:<hash>` | website | `{email, note, at}` (metadata `{at}`): an access request, the only place a raw email is kept | Until approved or denied, or 30 days |
