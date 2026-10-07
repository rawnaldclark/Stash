# stashfm.app

The website for Stash, built with [Astro](https://astro.build) as a static site, with a small Worker in
front of it for early access (sign-in codes by email, access requests, the monthly goal and `/admin`;
see [Early access](#early-access)). No client-side framework, no JavaScript shipped to visitors, and no
requests to anyone but stashfm.app itself.

It is not deployed yet. See [Deploying](#deploying).

## Run it locally

You need Node 22.12 or later. `.node-version` pins 24, which the Cloudflare build and the Website check
both use.

```bash
cd web
npm install
npm run dev        # http://localhost:4321, reloads as you edit
npx astro check    # type-checks the site
npm run build      # builds the site into dist/
npm run preview    # serves dist/ the way it was built
```

`npm run dev` and `npm run preview` show the pages only: no Worker, so `/` shows both versions of the
parts the Worker picks between (the web player section's sign-in form and its "Open the player" button, for
example), and the goal shows $0. To run the site the way Cloudflare will, Worker and all, build it and use Wrangler:

```bash
npm run build
npm test                    # the Worker's tests (worker/test); one checks the built pages, so build first
npx wrangler dev --local    # http://localhost:8787
```

Always pass `--local`. It keeps every binding on your machine: a local copy of the KV namespace, and
email that's printed in the terminal instead of sent (the binding has `remote: true`, so plain
`npx wrangler dev` would send real email). The Worker needs a `SESSION_SECRET` and an `EMAIL_PEPPER` to
sign anyone in; put them in `web/.dev.vars`, which git ignores (local values, not the real ones):

```
SESSION_SECRET=<at least 32 random characters, e.g. from `openssl rand -base64 48`>
EMAIL_PEPPER=<any local value>
ADMIN_EMAILS=owner@example.com
# Only to try /player: a local key pair from the player repo's `npm run keys` (never the real one)
PLAYER_TICKET_PRIVATE_KEY=<the PLAYER_TICKET_PRIVATE_KEY line>
```

To try signing in, put an email on the local list. `<hash>` is the HMAC-SHA256 of the lowercased email
keyed with that pepper, for example from
`node -e "console.log(require('crypto').createHmac('sha256', process.argv[1]).update('you@example.com').digest('hex'))" "<pepper>"`:

```bash
npx wrangler kv key put --local --binding ACCESS_KV "access:<hash>" '{"source":"manual"}'
```

This needs the real namespace id in `wrangler.jsonc` (or any 32-character hex id, temporarily): with the
`REPLACE_WITH_...` placeholder, `wrangler dev` runs, but keys written with `kv key put --local` didn't
show up in it when this was tested (Wrangler 4.147).

Then enter that email at `/` and copy the code from the terminal. `/admin` refuses every request
locally, since there's no Cloudflare Access in front of it (the Worker's tests cover it). Wrangler is a
dev dependency pinned in `package.json`, so `npm install` gets the same version Cloudflare's build uses.

Every push and pull request that changes `web/` runs the Website check in GitHub Actions
(`.github/workflows/web.yml`): `npm ci`, `npx astro check`, `npm run build` and `npm test`, plus a check
that no built page has a script.

## How it's organised

```
web/
├── astro.config.mjs        Site URL, fonts, sitemap, build format
├── wrangler.jsonc          Cloudflare Worker config: assets, bindings, which paths run the Worker (not deployed yet)
├── public/                 Copied as-is: favicons, social card, robots.txt, _headers
├── scripts/make-assets.py  Regenerates the fonts and icons from the app's own files
├── worker/                 The early-access Worker (index.js routes everything) and its tests (test/)
└── src/
    ├── assets/             Images and fonts that Astro processes (resized, hashed)
    │   ├── brand/          The vinyl logo
    │   ├── fonts/          Inter and Space Grotesk, WOFF2, Latin subset
    │   └── screenshots/    App screenshots: dark/ and light/, plus the hero banners
    ├── components/         Shared pieces: header, footer, buttons, phone frames, icons
    │   ├── gate/           The early-access pages' frame, goal bar, donate links and code box
    │   └── home/           One component per home-page section, in page order
    ├── content/privacy.md  The words on /privacy
    ├── data/               Links and site facts (site.ts), screenshots and alt text (screenshots.ts)
    ├── layouts/            BaseLayout.astro: <head>, meta tags, header and footer
    ├── pages/              One file per page: index, privacy, 404
    │   └── gate/           The early-access pages, which the Worker fills in and serves (never at /gate/...)
    └── styles/             tokens.css (design tokens) and global.css (reset and shared classes)
```

## Design

The site copies the app's design system rather than inventing one, so it looks like Stash.

- **Colours** are in `src/styles/tokens.css`, each one commented with its name in
  `core/ui/src/main/kotlin/com/stash/core/ui/theme/Color.kt`. Dark is the default; the light theme
  overrides the same tokens inside `@media (prefers-color-scheme: light)`. Brand colours (purple, cyan)
  are shared. If the app's palette changes, change the token here too.
- **Light and dark** follow the visitor's system setting, for colours and screenshots alike. There's
  no manual switch: screenshots use `<picture>` with a `prefers-color-scheme` source, like the README.
  To check the other theme in Chrome: DevTools › More tools › Rendering › Emulate CSS media feature
  `prefers-color-scheme`.
- **Contrast**: `--text` and `--text-2` pass WCAG AA in both themes. `--text-3` doesn't, so use it for
  decoration and large text only. Links use `--link`, which is tuned per theme.
- **Type**: Space Grotesk for headings, Inter for everything else, as in `Type.kt`. The fonts are the
  app's own TTFs, subset to Latin and converted to WOFF2 by `scripts/make-assets.py`, and loaded
  through Astro's font API (`fonts` in `astro.config.mjs`, `<Font>` in the layout), which also preloads
  the ones the first screen needs. One quirk: the app's `space_grotesk_semibold.ttf` is really the
  Medium (500) cut, so the site uses Space Grotesk at 500 and 700.
- **Shapes and surfaces**: radii follow `Shape.kt`. The `.glass` class is the app's `GlassCard`
  (translucent fill, hairline border, no blur). Buttons are `.button` plus `.button-primary` or
  `.button-secondary`; icon tiles are `.icon-bubble`.
- **Icons** live in `src/components/Icon.astro` as inline SVG: Material Symbols Rounded (Apache-2.0,
  the family the app uses) and brand marks from Simple Icons (CC0). To add one, copy the `d` attribute
  from `@material-symbols/svg-400/rounded/<name>.svg` or `simple-icons/icons/<name>.svg` into `ICONS`.
- **The wordmark** in `src/components/Wordmark.astro` is the app's own vector drawable
  (`feature/home/src/main/res/drawable/wordmark_stash_*.xml`, Bungee Shade letters, SIL OFL) turned into SVG.

## Common changes

**Edit words.** Each home-page section is a component in `src/components/home/` (Hero, Trust, WebPlayer,
HowItWorks, Modes, Library, Listening, Lossless, Together, Gallery, Faq, Community, FinalCta). The
privacy page's text is `src/content/privacy.md`. Links live in `src/data/site.ts`.

**Add a home-page section.** Make a component in `src/components/home/`, then place it in
`src/pages/index.astro`. Start from the shared pieces: `<section class="section">` with a `.container`,
`.section-head` with `.overline`, `.section-title` and `.section-intro`, and `FeatureRow.astro` for
words-plus-phones rows (see `Library.astro`).

**Add a page.** Create `src/pages/<name>.astro` and wrap it in `BaseLayout` with a title, a description
and its path. It's served at `/<name>` and lands in the sitemap automatically. Add it to the nav in
`SiteHeader.astro` or the footer columns in `SiteFooter.astro` if people should find it.

**Update screenshots.** Drop full-size 1080 × 2340 WebP files (transparent rounded corners, as in
`docs/screenshots/`) into `src/assets/screenshots/dark/` and `light/` under the same name; Astro makes
the smaller sizes at build time. For a new screen, also add its name and alt text to
`src/data/screenshots.ts`.

The web player's screenshots (the hero's browser window and the web player section) are in
`src/assets/player/dark/` and `light/`: the real player (a local `npm run dev` in the player repo, pointed
at the live sources) with a small real library, so the albums and covers are real, captured at 1280 × 800
at 2x, so 2560 × 1600 WebP. Only a few lyric lines are left visible in them.
Their names and alt text are in `src/data/player-shots.ts`, and `BrowserFrame.astro` draws the window
around them.

`public/social-preview.jpg` is the card shown when someone shares a link: the app's three-phone banner
(`docs/screenshots/` in the repo) trimmed a little at the sides to 1200 × 630, saved as JPEG and kept under 200 KB, since some apps skip large
preview images. If its size changes, change `og:image:width` and `og:image:height` in
`BaseLayout.astro` too.

**Fonts or app icon changed?** Run `python scripts/make-assets.py` (needs
`pip install fonttools brotli pillow`) and commit what it writes.

## Content rules

- **Every claim must be true per the repo's `README.md` and the code.** No invented features, numbers
  or promises. When the README changes, check `Faq.astro`, `src/content/privacy.md`, the legal text in
  `SiteFooter.astro` and the links in `src/data/site.ts`.
- **Lossless is "if you bring the source".** Keep the README's careful wording. Don't market free
  lossless, catalogs, or third-party sources by name beyond what the README itself says.
- **`/privacy` mirrors the README's "What Stash talks to"**, in the same words where possible, plus
  `SECURITY.md`'s credential handling. Update its `updated:` date when it changes.

## Privacy rules for the site itself

The site practises what the app promises:

- **No third-party requests.** No Google Fonts, CDNs, embeds, analytics or trackers. Self-host
  anything you need. `public/_headers` sets a Content-Security-Policy that blocks other origins, so
  something that loads in `npm run dev` but not in production is probably being blocked on purpose.
- **No scripts, and one cookie.** The site ships no JavaScript, and the Content-Security-Policy enforces
  it with `script-src 'none'`: in `public/_headers` for the files Cloudflare serves directly, and in
  `worker/pages.js` for everything the Worker answers (a test keeps the two equal). The Website check
  fails if a built page has a script. Forms are plain HTML posts, allowed only to this site
  (`form-action 'self'`). If something truly needs a script, keep it small and inline, allow that one
  script by its sha256 hash in `script-src` (never `'unsafe-inline'`), and update the check in
  `.github/workflows/web.yml`. The only cookie is `stash_access`, set when someone signs in (see
  [Early access](#early-access)). Don't add another without updating the privacy page.
- **Astro's own telemetry is switched off** in `astro.config.mjs`, and Wrangler's in `wrangler.jsonc`
  (`send_metrics: false`), for everyone who works on the site.
- **In the Cloudflare dashboard, keep these off for stashfm.app**: Web Analytics (and its automatic
  setup), Zaraz, Rocket Loader and Email Address Obfuscation. Each one injects a script into the pages.
  The CSP blocks those scripts, so at best they do nothing and at worst they break something (an
  obfuscated email address would never show). Zaraz is worse: it rewrites the CSP so its own script
  can run. Bot Fight Mode stays off too, for the share Worker's sake (see
  `infra/share-worker/README.md`).

## Early access

The website is in early access. Supporters (Ko-fi automatically; GitHub Sponsors and PayPal added by
hand) are on the list as a thank-you, anyone else can ask, and the owner and Evo answer requests on
`/admin`. Never word it as "donate to get in", and keep Qobuz, lossless and the relay out of this copy.

The web player (play.stashfm.app) is in early access, and stashfm.app is where people sign in to it.

**What a visitor sees.** `/` is the home page for everyone. Its web player section has the sign-in form
for a visitor who isn't signed in and an "Open the player" button (to `/player`) for one who is, and only a
signed-in visitor gets the footer's Sign out button: the Worker removes the other version
(`data-if="signed-in"` and `data-if="signed-out"`). The Lossless section and the FAQ's lossless answer are
for signed-in visitors only, so the public page keeps quality and sources out of its words. `/access` is
the sign-in page on its own (`src/pages/gate/front.astro`), where `/player` sends a visitor who isn't
signed in. `/privacy`, the 404 page and every file (CSS, fonts, images, `robots.txt`, the sitemap) are
public. Any other page is private: a page added later needs a session until it's added to `PUBLIC_PAGES`
in `worker/index.js`.

**The routes** (`worker/index.js`):

| Route | What it does |
| --- | --- |
| `GET /` | The home page, with this month's goal, in its signed-in or signed-out version |
| `GET /access` | The early-access sign-in page, with the goal (signed in: back to `/`) |
| `POST /access` | Email in. If it's on the list, a 6-digit code goes out by email. The page is the same either way |
| `POST /access/verify` | Email and code in. The right code sets the `stash_access` cookie and goes to `/` (or back to `/player`, if signing in started there) |
| `GET` and `POST /request` | The request-access form, and storing a request (the same answer for everyone) |
| `POST /signout` | Clears the cookie (the button is in the home page's footer) |
| `GET` and `POST /admin` | Requests, the access list and the goal, behind Cloudflare Access |
| `GET /player` | Signed in with early access: a 2-minute ticket and a redirect to the web player (below) |

Which paths reach the Worker at all is `assets.run_worker_first` in `wrangler.jsonc`. With
`not_found_handling` set, a browser navigation (a form post too) to a path with no file never reaches
the Worker unless it's listed there, so a new route must be covered by it. A test checks this.

**How the pages are made.** They're ordinary Astro pages in `src/pages/gate/`, built with the rest of
the site, so they match it in both themes. The Worker fetches one from the static assets and fills its
blanks with HTMLRewriter (`data-fill`, `data-value`, `data-html`, `data-if` and `data-goal`, explained
in `worker/pages.js`). A test checks the built pages still have every blank the Worker fills, so rename
both sides together. `/gate/...` itself is never served.

**Signing in.** Emails are trimmed and lowercased, then hashed with HMAC-SHA256 keyed with
`EMAIL_PEPPER`, which is required (sign-in and requests say they aren't working without it), the same
way as the tip jar
(`infra/tipjar-worker/src/access.js`) and its import script; tests in both places check the same
vectors. Codes: only an HMAC of each is stored, each lasts 10 minutes, and an email can have up to 3
live at once (asking again adds one instead of cancelling the one on its way). Any of them signs you
in and clears them all; 5 wrong tries clear them all too. An email gets at most one code email a
minute per Cloudflare location, and about 5 an hour (a KV count, approximate under concurrency). The cookie is `b64url({h, exp}).b64url(HMAC-SHA256)` with `SESSION_SECRET`,
`HttpOnly; Secure; SameSite=Lax; Path=/`, and a session lasts 90 days unless the email is taken off the
list: each signed-in request checks the email's hash is still there (cached for 5 minutes), so removing
someone signs them out within a few minutes. Signing out deletes the cookie from that browser but
doesn't revoke a copy of it; removing the email does. Every POST must come from this site (`Origin`, or
`Sec-Fetch-Site: same-origin`).

**Saying nothing about who has access.** Sending a code, a wrong code and requesting access show the
same page for an email on the list and one that isn't, and every rate limit applies to both alike.
The KV writes and email behind them (issuing a code, counting a wrong try, storing a request) run
after the response, in `ctx.waitUntil`, so they don't show in the response time. What still differs:
checking a code reads the email's code record, and while a code is live that record exists, so the
read can take slightly longer than for an email with no record (a few milliseconds on `wrangler dev`).
Telling the two apart would mean first asking for a code for that address (which emails its owner)
and then timing guesses, at 5 a minute per Cloudflare location.

**Rate limits** (Workers ratelimit bindings in `wrangler.jsonc`, per Cloudflare location, namespace ids
2010 to 2015; the share Worker has 2001 to 2009). Per-IP limits key IPv6 by its /48.

| Limit | What | Over it |
| --- | --- | --- |
| `SEND_IP_RL` | Code emails asked for: 5 a minute per IP | "Slow down a little" (429) |
| `SEND_EMAIL_RL` | Code emails asked for: 3 a minute per email hash, listed or not | "Slow down a little" (429) |
| `CODE_SEND_RL` | Code emails sent: 1 a minute per email hash (plus about 5 an hour, a KV count) | Nothing shows; no email goes out |
| `VERIFY_IP_RL` | Code tries: 10 a minute per IP | "Slow down a little" (429) |
| `VERIFY_EMAIL_RL` | Code tries: 5 a minute per email hash, every email, before its code is looked up | "Slow down a little" (429) |
| `REQUEST_IP_RL` | Access requests: 3 a minute per IP | "Slow down a little" (429) |

**The goal.** Every donation is its own key, `goal:<YYYY-MM>:kofi:<id>` (the tip jar) or
`goal:<YYYY-MM>:manual:<id>` (`/admin`), with the amount in its metadata, so the two Workers never
overwrite each other's totals. A month's total (UTC) is the sum of the keys listed under
`goal:<YYYY-MM>:`, against `GOAL_CENTS` ($100). `/admin` can take a hand-added entry back out, not a
Ko-fi one. The pages show whole dollars, rounded down, and read the total at most once a minute.

**Storage.** The Worker binds one KV namespace, `ACCESS_KV` (`stash-early-access`), shared with the tip
jar, and never the tip jar's `STASH_KV` (the app's supporters list and the relay config). Every key of
both, who writes it and how long it's kept: [`infra/tipjar-worker/README.md`, "KV keys"](../infra/tipjar-worker/README.md#kv-keys).

**`/admin`.** Cloudflare Access (Zero Trust) guards it, and the Worker checks the Access token itself
(`Cf-Access-Jwt-Assertion`: RS256 against your team's certs, the app's AUD tag, the issuer and expiry),
then that the token's email is in the `ADMIN_EMAILS` secret. With either missing, or the placeholders
still in `wrangler.jsonc`, `/admin` refuses everyone. There's no fallback to the plain email header.
`/admin` pages also allow forms to go to the Access team domain (`form-action`), so a form posted after
the Access session expired can be sent to its sign-in page.

**Settings** (`wrangler.jsonc` and secrets):

| Name | Kind | What |
| --- | --- | --- |
| `SESSION_SECRET` | secret | Signs the cookie and the stored codes. 32+ random characters. Changing it signs everyone out |
| `ADMIN_EMAILS` | secret | Comma-separated emails allowed on `/admin` |
| `EMAIL_PEPPER` | secret, required | Keys the email hashes. The same value on the tip jar and for its import script. Without it, sign-in and requests are off |
| `ACCESS_TEAM_DOMAIN` | var | `https://<team>.cloudflareaccess.com` |
| `ACCESS_AUD` | var | The Access application's AUD tag |
| `GOAL_CENTS` | var | The monthly goal in US cents (`10000`) |
| `EMAIL_FROM` | var | `access@stashfm.app`, the only sender the `EMAIL` binding allows |

| `PLAYER_URL` | var | The web player's origin, `https://play.stashfm.app`. https only, no path; anything else turns `/player` off |
| `PLAYER_TICKET_PRIVATE_KEY` | secret | The private half of the web player's Ed25519 key pair (a JWK on one line). Signs `/player`'s tickets. Without it, `/player` is off |

Mail to `access@stashfm.app` (replies to a code, removal requests) is forwarded to the maintainer's inbox
by Email Routing.

**The web player** (`worker/player.js`). The Stash web player runs at `PLAYER_URL` and only lets in
visitors who bring a ticket from `stashfm.app/player`; its own gate sends everyone else there. For a
signed-in visitor still on the list, `/player` signs a ticket and redirects (302) to
`PLAYER_URL/auth#t=<ticket>`. The ticket is in the URL fragment, which no server or log ever sees, and
the redirect is `no-store` and `Referrer-Policy: no-referrer`. A ticket is
`b64url(JSON {sub, exp, aud}).b64url(Ed25519 signature)`, with `aud` `"stash-player"` and `exp` two
minutes away; `test/player.test.js` checks it against a copy of the player's own verifier. `sub` is an
HMAC of the email hash keyed with `SESSION_SECRET`: the same for the same person, but not the email and
not the access list's hash, so the player holds nothing that can be matched to either (changing
`SESSION_SECRET` changes everyone's `sub`, and signs everyone out of the site anyway). Signed out, `/player`
goes to the sign-in page (`/access`), and signing in comes back to `/player` (the forms carry a hidden `next`
that can only ever be `/player`). Signed in but taken off the list, it goes to `/`. A shared song or mix
link on the player arrives as `/player?next=/t/...`, `/m/...` or `/play...` and is passed on as
`PLAYER_URL/auth?next=...`; any other `next` is dropped. Without a valid `PLAYER_URL` or key, `/player` is a
503 that names neither (the log, `wrangler tail`, says which).

**The ticket key.** In the player repo (`stash-web/player`), `npm run keys` prints a key pair (nothing is
saved). The private half, `PLAYER_TICKET_PRIVATE_KEY`, goes on this Worker only: `npx wrangler secret put
PLAYER_TICKET_PRIVATE_KEY` from `web/`, pasting the JSON line. The public half, `TICKET_PUBKEY`, goes on the
player Worker. Never commit either. To rotate, run it again and change both at the same moment (tickets
last two minutes, so nothing breaks).

## Things to know

- **Whitespace.** Astro 7 applies JSX whitespace rules by default, which drops the space before a link
  that starts on a new line. `compressHTML: true` in the config keeps HTML's normal rules, so wrap text
  however you like.
- **Pages build to files** (`dist/privacy.html`), which Cloudflare serves at `/privacy`.

## Deploying

The site is a Worker named `stashfm-site`: `dist/` served with Workers Static Assets (files, headers from
`public/_headers`, a 404 page), plus the early-access Worker in `worker/`. Nothing is set up yet. The plan:

0. **Early access, before the site goes live**, in this order:
   1. **The early-access namespace** (done 2026-10-04): `stash-early-access`, id
      `9c1524fb52d64900b5ae05cca64efca3`, bound as `ACCESS_KV` in `infra/tipjar-worker/wrangler.toml` and
      `web/wrangler.jsonc`. If it's ever recreated, update both files.
   2. **Pick an email pepper** (required): a long random value, e.g. `openssl rand -base64 48`, kept in
      your password manager. Set it on the tip jar now (`npx wrangler secret put EMAIL_PEPPER` in
      `infra/tipjar-worker`), on the site in step 6, and in the environment when you run the import.
      The site won't sign anyone in without it, and the import won't run. It's what keeps someone
      with a copy of the list from checking a guessed email against it.
   3. **Deploy the tip jar.** From `infra/tipjar-worker`: `npm ci`, `npm test`, `npx wrangler deploy`.
      From then on Ko-fi donations also fill the access list and the goal; the app's supporters list
      doesn't change. Then check it: run `npx wrangler tail stash-tipjar`, and make a small test
      donation (or use Ko-fi's "Send test", which only lands once every 60 days). The log should show no
      "early access / goal update failed" line, and the webhook should answer 200; a 500 means the
      `ACCESS_KV` writes failed and Ko-fi will retry.
   4. **Import past supporters**, once: export your Ko-fi transactions as CSV, then follow "Importing
      past supporters" in `infra/tipjar-worker/README.md` (`node scripts/import-kofi-csv.mjs
      <export.csv>` with `EMAIL_PEPPER` set, then the `npx wrangler kv bulk put ... --binding ACCESS_KV
      --remote` command it prints). Delete the CSV and the JSON file afterwards.
   5. **Set up Cloudflare Access for `/admin`** (Zero Trust, free): Zero Trust › Access › Applications ›
      Add an application › Self-hosted. Application domain `stashfm.app`, path `admin`. To use the
      admin page before launch too, add a second destination: `stashfm-site.<account>.workers.dev`,
      path `admin`. Policy: Allow, Include › Emails › the owner's and Evo's addresses. Save, then copy
      the **Application Audience (AUD) Tag** (the application › Additional settings) and your **team
      domain** (Zero Trust › Settings, `<team>.cloudflareaccess.com`). Put both in `vars` in
      `wrangler.jsonc`: `ACCESS_AUD`, and `ACCESS_TEAM_DOMAIN` as `https://<team>.cloudflareaccess.com`.
      They aren't secrets.
   6. **The site's secrets**, once the Worker exists (after its first deploy, step 1 below; until
      then sign-in says it isn't working and `/admin` refuses everyone). From `web/`:
      `npx wrangler secret put SESSION_SECRET` (paste 48 random bytes, e.g. `openssl rand -base64 48`),
      `npx wrangler secret put ADMIN_EMAILS` (the owner's and Evo's addresses, comma-separated), and
      `npx wrangler secret put EMAIL_PEPPER` (the tip jar's value). Or set them in the dashboard:
      Workers & Pages › stashfm-site › Settings › Variables and Secrets, under Production.
   7. **Try it on the workers.dev address**: sign in with a supporter's email, ask for access with
      another, approve it on `/admin` (if Access covers workers.dev), add and remove a donation, sign
      out. Email Sending for stashfm.app is already set up (2026-10-04: DKIM, SPF, DMARC, bounce
      records), so the codes really go out.

1. **Connect the repo with Workers Builds** (Cloudflare dashboard › Workers & Pages › Create ›
   Import a repository):
   - Root directory: `web`
   - Build command: `npm ci && npm run build`
   - Deploy command: `npx wrangler deploy`
   - Preview builds: on, with the Preview command `npx wrangler preview` (the default for a new Worker)
   - Build watch paths: include `web/*`, so app-only commits don't rebuild the site
   Workers Builds uses the Wrangler version pinned in `package.json`.
2. **Previews.** Each branch pushed to this repo gets a preview URL, posted on its pull request, so
   changes can be checked before merging. Pull requests from forks get no preview; the Website check
   in GitHub Actions still type-checks, builds and tests them. Merging to `master` deploys. A Preview
   gets none of production's bindings, vars or secrets, only what the `previews` block in
   `wrangler.jsonc` gives it, and that block is empty on purpose: a Preview shows the pages (the
   home page as a visitor who isn't signed in sees it, with no goal bar), signing in says it isn't working, and `/admin`
   refuses everyone. To see the signed-in pages, use `npx wrangler dev --local`.
   - **Never** put the production `ACCESS_KV` or the `EMAIL` binding in `previews`, or production
     secrets in the dashboard's Previews Base configuration: every branch's Preview would then read
     and write the real access list and send real email.
   - **Don't** change the Preview command to `npx wrangler versions upload`: a version upload runs
     with production's bindings and secrets on a Version URL anyone can open, unless `preview_urls`
     is `false` or Cloudflare Access guards those URLs.
   - `preview_urls` is `false` in `wrangler.jsonc`, so no version, old or new, stays reachable at a
     Version URL with production's bindings. Branch Previews are a separate mechanism, but if they
     stop appearing once Workers Builds is connected, revisit this setting.
3. **At launch, hand stashfm.app over in this order**, or shared links break. Today the share Worker
   (`infra/share-worker`, Worker `stash-share`) owns all of `stashfm.app/*`, and its `/` is a
   placeholder page. A route pattern can belong to only one Worker, and the most specific one wins.
   1. **Narrow the share Worker.** In `infra/share-worker/wrangler.toml`, replace the `stashfm.app/*`
      route with the share Worker's own paths and nothing else, in a pull request merged to `master`:

      ```toml
      routes = [
        { pattern = "stashfm.app/m/*", zone_name = "stashfm.app" },
        { pattern = "stashfm.app/t*", zone_name = "stashfm.app" },
        { pattern = "stashfm.app/l/*", zone_name = "stashfm.app" },
        { pattern = "stashfm.app/v1/*", zone_name = "stashfm.app" },
        { pattern = "stashfm.app/.well-known/assetlinks.json", zone_name = "stashfm.app" },
      ]
      ```

      That's mix pages, song links, Listen Together invites, the app's API and Android App Links.
      `stashfm.app/t*` covers short song links (`/t/<id>`) and the older long ones (`/t?t=…`).
      Cloudflare matches a route against the whole URL, query included, so a pattern without a final
      `*`, like `stashfm.app/t`, misses every `/t?…` link. The flip side: no site page may start with
      `/t` (such as `/terms`), because the share Worker would get it.
      Change the toml, not the dashboard: `wrangler deploy` replaces the Worker's routes with the
      toml's list, so a dashboard edit is undone by the next deploy.
   2. **Deploy it** from `infra/share-worker`: `npm test`, then `npx wrangler deploy`. The output
      lists the five routes. If Wrangler also warns that previously deployed routes "have not been
      deleted" (it does that when its token can't manage every zone), remove `stashfm.app/*` from
      `stash-share` in the dashboard: Workers & Pages › stash-share › Settings › Domains & Routes.
   3. **Check the share links** with the commands below. With `stashfm.app/*` gone, each check proves
      its own route. Until step 4, the home page and every other path the share Worker doesn't claim
      show a Cloudflare error, so have step 4's pull request ready before you start.
   4. **Connect the site:** uncomment `routes` in `wrangler.jsonc` and merge to `master`. The deploy
      gives this Worker `stashfm.app/*`, on the proxied DNS record the share Worker already uses. If
      that deploy fails on the route, `stash-share` still holds `stashfm.app/*`: go back to step 2.
4. **After launch**, run the share-link checks again, then check the home page (the sign-in form
   when signed out, "Open the player" when signed in; sign in and out once), `/privacy`, `/admin` (Access should ask you to sign in) and a
   made-up path (which should show the 404 page). Once the app people use talks to stashfm.app, also
   share a song from it and join a Listen Together session, which uses the `/v1/rooms/<code>/ws`
   WebSocket.

The share-link checks. Every answer must come from the share Worker, never the site's 404 page:

```bash
# App Links: lists com.stash.app and com.stash.app.debug, with no redirect
curl -s https://stashfm.app/.well-known/assetlinks.json

# The API: 201 (200 if this song was shared before) with {"id":"<id>","url":"https://stashfm.app/t/<id>"}
curl -s -X POST https://stashfm.app/v1/tracks -H 'content-type: application/json' \
  -d '{"t":"Never Gonna Give You Up","a":"Rick Astley","isrc":"GBARL9300135","sp":"4uLU6hMCjMI75M1A2tKUQC","yt":"dQw4w9WgXcQ"}'

# Song links, short (the id from above) and legacy: 200 each
curl -s -o /dev/null -w '%{http_code}\n' https://stashfm.app/t/<id>
curl -s -o /dev/null -w '%{http_code}\n' 'https://stashfm.app/t?t=Test&a=Test'

# A shared mix, with a real mix id: 200
curl -s -o /dev/null -w '%{http_code}\n' https://stashfm.app/m/<id>

# An invite: a made-up code gets the share Worker's "Session ended" page (a 404, but not the site's)
curl -s https://stashfm.app/l/ABCDEFGH | grep -o 'Session ended'
```

## Credits

Fonts: Inter (SIL OFL 1.1) and Space Grotesk (SIL OFL 1.1); their licences stay embedded in the font
files. Wordmark: Bungee Shade by David Jonathan Ross (SIL OFL 1.1). Icons: Material Symbols
(Apache-2.0) and Simple Icons (CC0-1.0).
