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
   Cloudflare Worker
         │  read+write
         ▼
   Cloudflare KV (key: "supporters")
         ▲
         │  GET (60s edge cache)
         │
   Stash app on phone
```

One Worker, one URL. POST = webhook receiver. GET = JSON endpoint the
app reads. Cloudflare's free tier (100k requests/day, 1GB KV) covers
~100,000× more than this needs.

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
Worker requests/day. KV writes happen only when a tip arrives, so
maybe 10/day at the high end. Well under every limit.

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
via `SUPPORTERS_LIMIT` in `src/index.js`).

## Migrating off Cloudflare

If you ever want to leave Cloudflare, the Worker code is ~50 lines and
ports to:
- **Deno Deploy**: change `env.STASH_KV.put` to Deno KV API (`Deno.openKv()`).
- **Vercel Functions**: change to a Vercel Edge function + Vercel KV.
- **Self-hosted Node/Express**: change KV calls to a redis client.

The schema the Worker exposes (the GET response shape) stays
identical, so the Stash app needs no changes when you migrate.

## Manual seeding

If you want to pre-seed the KV with existing supporters before
plugging in Ko-fi:
```bash
npx wrangler kv:key put --binding=STASH_KV supporters '{"supporters":[{"name":"Cedric","amountUsd":10,"message":"Just downloaded..."}]}'
```
Or use the Cloudflare dashboard's KV editor.

## Lossless relay config

The same Worker also serves `GET /lossless.json` and `GET /lossless.json.sig` —
the signed relay list the Stash app fetches at every cold start. They come
byte-for-byte from the KV keys `lossless_config` / `lossless_config_sig`, which
only `infra/lossless-relay/scripts/publish-config.mjs` writes. 404 until the
first publish. Nothing about supporters changes.

## stashfm.app early access and the monthly goal

Since 2026-10 the webhook does two more things for each Donation or
Subscription (`src/access.js`). The webhook URL, its answers and the GET
JSON above don't change.

- **Early access.** The donor's email gets an `access:<hash>` entry, so they
  can sign in to stashfm.app with it (the website Worker, `web/worker`, reads
  it). Only the hash is stored, never the email.
- **The monthly goal.** The amount goes into `goal:<YYYY-MM>` (UTC month), in
  US cents. Other currencies are converted with the fixed, approximate table
  `USD_PER_UNIT` in `src/access.js`; a currency missing from it isn't counted
  (the donor still gets access).
- **No double counting.** Each `kofi_transaction_id` is remembered for 60
  days (`kofitxn:<id>`). If Ko-fi sends the same one again, nothing changes,
  the supporters list included.
- Ko-fi's **"Send test"** webhooks (transaction id
  `00000000-1111-2222-3333-444444444444`) still show up in the supporters
  list, as before, but never in the goal or the access list.
- If these extra writes fail, the error is logged (`npx wrangler tail`) and
  Ko-fi still gets its 200, since the supporters list was already saved.

**The email hash.** Trim and lowercase the email, then SHA-256 it (hex). If
the optional `EMAIL_PEPPER` secret is set, it's HMAC-SHA256 keyed with the
pepper instead. The website Worker and `scripts/import-kofi-csv.mjs` hash the
same way, and the tests in both places check the same vectors. Pick one setup
before launch: the same `EMAIL_PEPPER` on both Workers (and in the
environment when you run the import), or none anywhere. Changing it later
locks every supporter out until they're imported again. If the two Workers
disagree, the website's admin page says so (it compares `meta:hashcheck`).

### Importing past supporters

The webhook only sees new donations. To give everyone who supported before
early access, once, before launch:

1. On Ko-fi, export your transaction history as CSV.
2. From `infra/tipjar-worker` (set `EMAIL_PEPPER` in the environment first if
   the Workers use one):

   ```bash
   node scripts/import-kofi-csv.mjs path/to/kofi-export.csv
   ```

   It prints counts only, never an email, and writes the entries to a JSON
   file in your temp folder (`--out <file>` to choose; `--all-types` to
   include shop orders and commissions too). Rows count when their type is a
   donation, subscription, membership or tip.
3. Upload it with the command it prints:
   `npx wrangler kv bulk put <file> --binding STASH_KV --remote`
4. Delete the JSON file and the CSV.

Re-running is safe: each entry is rewritten with the same hash.

## STASH_KV keys

Every key in this namespace, in one place. The tip jar and the website Worker
(`web/wrangler.jsonc`) both bind it. `<hash>` is the email hash above.

| Key | Written by | Value | Kept |
| --- | --- | --- | --- |
| `supporters` | tip jar webhook | The list the app reads (`GET /`) | Until replaced |
| `lossless_config`, `lossless_config_sig` | `infra/lossless-relay/scripts/publish-config.mjs` | The signed relay config, served byte for byte | Until replaced |
| `access:<hash>` | tip jar webhook, the import script, the website's admin page | `{source: "kofi", firstAt, lastAt}`, or `{source: "approved" or "manual", by, at}` | Until removed on the admin page |
| `goal:<YYYY-MM>` | tip jar webhook (Ko-fi), the website's admin page (by hand) | `{cents, entries: [{cents, source, at, ...}], updatedAt}` | Forever (one per month) |
| `kofitxn:<id>` | tip jar webhook | `"1"`: this Ko-fi transaction was handled | 60 days |
| `meta:hashcheck` | tip jar webhook, the import script | The hash of `hashcheck@stashfm.app`, to spot a pepper mismatch | Until replaced |
| `code:<hash>` | website | A sign-in code's HMAC, tries so far, expiry | 10 minutes |
| `sends:<hash>` | website | How many codes went to this email this hour | 1 hour |
| `request:<hash>` | website | `{email, note, at}`: an access request, the only place a raw email is kept | Until approved or denied, or 30 days |
