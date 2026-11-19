# stash-home: the home relay

Lets a supporter play the library on their **Stash desktop** from their phone, anywhere. They use any Subsonic app
(Symfonium, Substreamer, DSub…), and later Stash for Android. The desktop connects out to the relay, so it works
behind any router or carrier NAT, with nothing to set up.

```
phone (Subsonic app) --https--> stash-home Worker --> Home Durable Object (one per desktop)
                                                         |  one WebSocket, kept open by the desktop
                                                         v
                                                   Stash desktop at home
```

- **The desktop** (`stash-desktop`, `crates/stashd/src/away.rs`):
  - Makes up a home id (16 base32 characters) and a home key (32 random bytes).
  - Connects to `wss://<host>/home/<id>/connect` with `Authorization: Bearer <key>` and the tip jar's supporter
    token in `X-Stash-Entitlement`.
  - The first connection with a key owns the home. Later connections need the same key, and a newer connection
    replaces the older one.
  - Pings with the text `ping` every 25 s. The Durable Object answers `pong` without waking.
- **A phone** uses `https://<host>/h/<id>` as its server address. Only `/h/<id>/rest/<method>` is passed on; every
  other path is answered 404 at the edge, without waking the home.
  1. Each request goes to the desktop as one JSON message (`src/frames.js`).
  2. The desktop checks the device's own user name and password, exactly as on its Wi-Fi.
  3. The desktop POSTs the answer to `/home/<id>/answer/<request>`, streamed.
  4. The Durable Object pipes that upload into the phone's response. Nothing is buffered beyond the streams'
     queues, and a phone that reads slowly slows the desktop's upload.
- **Supporters only.** The token is checked when the desktop connects:
  - Ed25519 with the tip jar's production public key, the same check as `infra/tidal-relay`.
  - Then `revoked_tokens` in `stash-quota`.
  - A database outage lets a valid token through.
  - No token answers 402; a revoked token answers 403.

## Cost

The Workers Paid plan has no egress charge for Workers or Durable Objects. The bill comes from:

- **Duration.** A Durable Object is billed only while awake, at 128 MB × wall-clock time. An idle home hibernates
  with its WebSocket open and costs nothing. A home that is playing costs about 450 GB-s per hour, about $0.0056
  per hour past the included 400,000 GB-s a month (about 900 listening hours).
- **Requests.** One Worker request per phone request, plus one per answer POST. Incoming WebSocket messages are
  billed 20:1 and the desktop sends only pings, which the runtime answers.

Cloudflare's terms let large audio go through the Developer Platform (Workers); the restriction on audio and other
large files applies to the plain CDN.

## Deploy (owner)

1. Pick the address phones will use, for example a custom domain on the account. Set it in `wrangler.toml`
   `routes`. Without one, it answers on its workers.dev address.
2. Deploy:
   ```
   npm install
   npx wrangler deploy
   ```
   The `QUOTA_DB` binding is the existing `stash-quota` database, read only. `ENTITLEMENT_PUBLIC_KEY` is the tip
   jar's public key, already in `wrangler.toml`. There are no secrets.
3. Build the desktop with the address. Add `HOME_RELAY_URL=https://<host>` to the Android repo's
   `local.properties`; the desktop release build passes it as `STASH_HOME_RELAY`. A desktop built without it shows
   no relay.

## Try it locally

`npx wrangler dev --port 8787 --var ENTITLEMENT_PUBLIC_KEY:<a test key>` runs the relay locally. Run the desktop
core with `STASH_HOME_RELAY=http://127.0.0.1:8787`, which is plain http and allowed only on this computer, and a
supporter token signed with the test key.

## Tests

`npm test` covers routing, the request and answer frames, which headers pass, the home key, and the supporter-token
check, using node's test runner. The full path was also exercised end to end against `wrangler dev` with a real
desktop core:

- whole songs and ranges;
- a phone stopping mid-song, then six requests at once;
- form POSTs;
- a new address replacing the old one;
- switching off;
- a refused token.

## Limits

- 24 requests waiting per home, 45 s for the desktop to start answering, 256 KB per form.
- One desktop per home.
- A token revoked while a desktop is connected keeps working until that desktop reconnects. It reconnects at least
  when Stash restarts or the network changes.
