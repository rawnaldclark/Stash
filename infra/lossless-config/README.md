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

## Tests

`npm test` (node's test runner): `src/config.js` builds the exact bytes, and `test/config.test.js`
checks the publishing rules and that a signature verifies the way the device checks it.
