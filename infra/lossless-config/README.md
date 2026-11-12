# lossless.json (signed config publisher)

`lossless.json` is the small signed config every Stash install fetches at cold start
(`LOSSLESS_CONFIG_URL`, verified with `LOSSLESS_CONFIG_PUBKEY`; app side `LosslessConfigFetcher`).
It is served byte for byte by the tip jar Worker from its KV (`lossless_config`, `lossless_config_sig`;
see `../tipjar-worker/README.md`). This folder is not a Worker: it holds the script that builds,
signs and uploads that pair.

What it carries:

- `tidal_relay_url` and `tidal_relay_token`: where the app finds `stash-tidal-relay` and the
  `X-Access-Token` it sends (the relay's `ACCESS_TOKEN` secret).
- `relay_key`: the HMAC key the app signs its install headers with (`X-Stash-Install/-Ts/-Auth`).
  It must equal `stash-tidal-relay`'s `RELAY_KEY` secret: with `REQUIRE_INSTALL="1"` the relay answers a bad
  signature on the app's `/stream` with a 401.
- `relays`: the Qobuz relay list (`{"base","priority"}`). The Qobuz pool is retired: publish `[]`.
- `default_addons` (optional; add-ons phase 2): the add-ons every install turns on, in play order, each
  `{"manifest", "enabled", "first_party"}`. The manifest is an https URL ending in `/manifest.json` with no query or
  fragment; it can carry the add-on's token in its path (`/v1/<ACCESS_TOKEN>/manifest.json`), so the script prints only
  its origin. An enabled `first_party` entry whose manifest id is `tidal` is the app's switch to the Tidal v1 wire
  (decision D9): publish it to turn the wire on, publish without it to turn it off again. Released apps before
  phase 2 ignore the key. Keep `tidal_relay_url`, `tidal_relay_token` and `relay_key` in every publish anyway: they are
  what those apps, and the legacy wire, use.
- `addon_index` (optional; add-ons phase 4): the https URL of the signed add-on index, the official list and the
  blocklist (`https://raw.githubusercontent.com/stash-addons/registry/main/index.json`; the app also fetches
  `<url>.sig`). It ends in `.json`, with no query or fragment, and is public, so the script prints it. Moving the index
  (to Pages, or a Worker) is one publish of this key. Released apps before phase 4 ignore it. The index itself is built
  and signed by `scripts/publish-index.mjs` (below).
- `v: 1` and `updated_at` (set by the script: max(now, live + 1), never by hand).

**Leaving out `tidal_relay_url` or `relay_key` switches Tidal off in every installed app.** Always
publish the full object.

## Commands

Run from this folder after `npm ci` (so `npx wrangler` uses the pinned local copy). Needs a wrangler
login with access to the tip jar's KV, or `CLOUDFLARE_API_TOKEN`. Keep `key.pem` (the P-256 private
key) and `input.json` outside the repo.

```powershell
cd infra/lossless-config
npm ci
node scripts/publish-config.mjs verify                          # what is live now (read only)
node scripts/publish-config.mjs publish <key.pem> <input.json>  # sign, upload both KV keys, read back
node scripts/publish-config.mjs verify <key.pem>                # also checks the signature
```

`keygen`, `pubkey` and `relaykey` are in the script's header comment.

Kill switch for the relay list (keeps Tidal on):

```json
{"relays": [], "relay_key": "<current RELAY_KEY>", "tidal_relay_url": "https://stash-tidal-relay.rawnaldclark.workers.dev", "tidal_relay_token": "<current ACCESS_TOKEN>"}
```

## The add-on index (official list and blocklist)

The add-on index is a second signed file, for add-ons phase 4: the add-ons Stash itself runs (the official list)
and the add-ons Stash has turned off on every phone (the blocklist). It is `index.json` + `index.json.sig` in the
public registry repo (`stash-addons/registry`), and the app finds it through `addon_index` above. It is signed
with the same key as `lossless.json`, in the same way: ECDSA P-256 with SHA-256 over the exact bytes, the DER
signature in base64 in the `.sig`. Its `"type": "stash-addon-index"` is what keeps a signed `lossless.json` from
ever passing as an index.

`scripts/publish-index.mjs` builds, signs and checks it. It needs no wrangler login and no `npm ci`. **It never
commits, pushes or uploads anything:** it writes the two files into a local checkout of the registry repo, and
pushing them is a separate step by hand.

```powershell
cd infra/lossless-config
node scripts/publish-index.mjs check <index.source.json>                                # rules only: no key, no network
node scripts/publish-index.mjs publish <key.pem> <index.source.json> <registry checkout> # build, sign, write, read back, verify
node scripts/publish-index.mjs verify <key.pem>                                         # the live pair, with its signature
node scripts/publish-index.mjs verify --dir <registry checkout> --pubkey <base64>       # a checkout's pair, against the app's key
```

The source is a small JSON file kept in the registry repo (`index.source.json`):

```json
{
  "addons": [
    { "id": "tidal", "name": "Tidal", "summary": "Lossless and Hi-Res", "default": true }
  ],
  "blocked": [
    { "origin": "https://bad.example", "id": "bad-addon", "reason": "harmful" }
  ]
}
```

The rules (`src/index.js`; a bad entry is refused, never repaired, and an unknown key is a typo):

| Field | Rule |
|---|---|
| `addons` | At most 64, shown in the order written. |
| `id` | The manifest id rule, `^[a-z0-9][a-z0-9.-]{0,63}$`; listed once. |
| `name`, `summary` | Required. One line of at most 60 characters: no control, bidi, zero-width or other invisible character, and no whitespace but single plain spaces between words. |
| `icon` | Optional. An https URL. |
| `manifest` or `"default": true` | Exactly one. `manifest` is a public https URL ending in `/manifest.json`, with no query, fragment, credentials or token. `"default": true` names an add-on that every install already has from `default_addons`: its manifest URL, which can carry a token, stays in `lossless.json` and never appears here. |
| `min_build`, `platforms` | Optional. An Android versionCode; a list from `android`, `web`. |
| `blocked` | At most 256. |
| `origin` | Exactly `https://host` or `https://host:port`: lower case, no default port, no path, no trailing slash. |
| `id` (in `blocked`) | Optional. Without it the whole origin is blocked. |
| `reason` | `harmful`, `broken` or `impersonation`. The app shows its own fixed sentence for each; no text from the file is ever shown. The publisher refuses any other word. A reader does not: if a later publisher adds a reason, an older app still blocks that add-on, with a generic sentence (`readIndex` reports the reason as `unknown`). A newer list must never make an older app drop a block. |

Things worth knowing:

- **`updated_at` is never hand-edited.** It becomes max(now, newest known + 1), so a phone's rollback floor always
  passes. "Newest known" is the checkout's current index and the live one, each counted only when its signature
  verifies. A copy that does not verify stops the run. `--offline` skips the live copy.
- **The whole file is at most 64 KiB** (the app reads no more). A full list of ordinary entries is about 63 KB,
  so the size cap can bind before the count caps do; the build says so when it does.
- **Use `--pubkey`** with the app's public key (the text of `LOSSLESS_CONFIG_PUBKEY`) to check against exactly
  the key phones hold. Without it, checks use the public half of `key.pem`, which proves the files are
  consistent but not that `key.pem` is the right key. `publish` prints the public key it used either way.
- **The bytes are the contract.** The signature covers `index.json` byte for byte: UTF-8, LF line endings, one
  final line feed. A tool that rewrites the file (an editor, a formatter, a line-ending conversion) breaks it; the
  registry repo's `.gitattributes` keeps git from doing so. `verify` prints `written_by_this_publisher=false` when
  the bytes are not the publisher's own.
- **raw.githubusercontent.com caches** for about five minutes, so `verify` can show the previous copy just after a
  push.
- **Printed output is ids, origins and reasons only**, never a manifest path.
- **Moving the index** is one publish of `addon_index` in `lossless.json`; pass `--url` here to read the live copy
  from the new place.
- **A token cannot be caught in every case.** The publisher refuses a manifest path segment that looks like one
  (long, mixed letters and digits, upper case), but a short token looks like a word. The rule is the author's to
  keep: an add-on whose URL carries a token is listed with `"default": true`.

`test/fixtures/` holds signed sample indexes (throwaway key) that the app's reader is tested against, with a
README; `scripts/make-index-fixtures.mjs` regenerates them.

## Tests

`npm test` (node's test runner; local imports only, so no `npm ci`). CI runs them in the `worker-tests` job of
`.github/workflows/tests.yml` (`node --no-warnings --test test/*.test.js` in this folder).

- `test/config.test.js`: `src/config.js` builds the exact bytes of `lossless.json`; the publishing rules; a
  signature verifies the way the device checks it.
- `test/index.test.js`: every index rule, the `updated_at` floor, reading published bytes, and the signature
  (the device's way, and WebCrypto's).
- `test/publish-index.test.js`: the `check`, `publish` and `verify` commands, with throwaway keys in a temporary
  folder and a stand-in for the network.
- `test/fixtures.test.js`: the committed fixtures are what their README says.
