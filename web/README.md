# stashfm.app

The website for Stash, built with [Astro](https://astro.build) as a fully static site: no client-side
framework, no JavaScript shipped to visitors, and no requests to anyone but stashfm.app itself.

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

`npx wrangler dev` (after a build) runs the site the way Cloudflare will, with the response headers from
`public/_headers` and the real 404 handling. Wrangler is a dev dependency pinned in `package.json`, so
`npm install` gets the same version Cloudflare's build uses.

Every push and pull request that changes `web/` runs the Website check in GitHub Actions
(`.github/workflows/web.yml`): `npm ci`, `npx astro check` and `npm run build`, plus a check that no
built page has a script.

The build asks GitHub's API for the latest release once, to show its version number. Offline, the build
still works and the version is simply left out.

## How it's organised

```
web/
├── astro.config.mjs        Site URL, fonts, sitemap, build format
├── wrangler.jsonc          Cloudflare Worker config (static assets only, not deployed yet)
├── public/                 Copied as-is: favicons, social card, robots.txt, _headers
├── scripts/make-assets.py  Regenerates the fonts and icons from the app's own files
└── src/
    ├── assets/             Images and fonts that Astro processes (resized, hashed)
    │   ├── brand/          The vinyl logo
    │   ├── fonts/          Inter and Space Grotesk, WOFF2, Latin subset
    │   └── screenshots/    App screenshots: dark/ and light/, plus the hero banners
    ├── components/         Shared pieces: header, footer, buttons, phone frames, icons
    │   └── home/           One component per home-page section, in page order
    ├── content/privacy.md  The words on /privacy
    ├── data/               Links and site facts (site.ts), screenshots and alt text (screenshots.ts)
    ├── layouts/            BaseLayout.astro: <head>, meta tags, header and footer
    ├── lib/release.ts      Reads the latest release from GitHub at build time
    ├── pages/              One file per page: index, privacy, 404
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

**Edit words.** Each home-page section is a component in `src/components/home/` (Hero, Trust,
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
`src/data/screenshots.ts`. The hero banners are `hero-dark.webp` and `hero-light.webp` (2560 × 1280).
`public/social-preview.jpg` is the card shown when someone shares a link: the dark banner trimmed a
little at the sides to 1200 × 630, saved as JPEG and kept under 200 KB, since some apps skip large
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
- **No cookies, no scripts.** The site ships no JavaScript, and `public/_headers` enforces it: the
  Content-Security-Policy says `script-src 'none'`, so browsers run no script on these pages, and the
  Website check fails if a built page has one. If something truly needs a script, keep it small and
  inline, allow that one script by its sha256 hash in `script-src` (never `'unsafe-inline'`), and
  update the check in `.github/workflows/web.yml`.
- **Astro's own telemetry is switched off** in `astro.config.mjs`, and Wrangler's in `wrangler.jsonc`
  (`send_metrics: false`), for everyone who works on the site.
- **In the Cloudflare dashboard, keep these off for stashfm.app**: Web Analytics (and its automatic
  setup), Zaraz, Rocket Loader and Email Address Obfuscation. Each one injects a script into the pages.
  The CSP blocks those scripts, so at best they do nothing and at worst they break something (an
  obfuscated email address would never show). Zaraz is worse: it rewrites the CSP so its own script
  can run. Bot Fight Mode stays off too, for the share Worker's sake (see
  `infra/share-worker/README.md`).

## Things to know

- **Whitespace.** Astro 7 applies JSX whitespace rules by default, which drops the space before a link
  that starts on a new line. `compressHTML: true` in the config keeps HTML's normal rules, so wrap text
  however you like.
- **The version number** comes from GitHub at build time (`src/lib/release.ts`), so it only changes
  when the site is rebuilt. The Download button always links to `/releases/latest`, which is never
  stale. Set `GITHUB_TOKEN` in the build environment if GitHub's limit of 60 anonymous requests an hour
  ever gets in the way, and only a token with no permissions (see [Deploying](#deploying)).
- **Pages build to files** (`dist/privacy.html`), which Cloudflare serves at `/privacy`.

## Deploying

The site is a Worker named `stashfm-site` that serves `dist/` with Workers Static Assets: no Worker
code, just files, headers (`public/_headers`) and a 404 page. Nothing is set up yet. The plan:

1. **Connect the repo with Workers Builds** (Cloudflare dashboard › Workers & Pages › Create ›
   Import a repository):
   - Root directory: `web`
   - Build command: `npm ci && npm run build`
   - Deploy command: `npx wrangler deploy`
   - Preview builds: on, with the Preview command `npx wrangler preview` (the default for a new Worker)
   - Build watch paths: include `web/*`, so app-only commits don't rebuild the site
   - Optional build secret: `GITHUB_TOKEN`, only if GitHub's anonymous limit gets in the way. Make it a
     fine-grained personal access token with no permissions added (every fine-grained token can read
     public repositories, which is all the build needs) and an expiry date. When it expires, builds
     just leave the version number out. Never use a classic token or one that can write: the build,
     and every package `npm ci` installs, can read it.

   Workers Builds uses the Wrangler version pinned in `package.json`.
2. **Previews.** Each branch pushed to this repo gets a preview URL, posted on its pull request, so
   changes can be checked before merging. Pull requests from forks get no preview; the Website check
   in GitHub Actions still type-checks and builds them. Merging to `master` deploys.
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
4. **After launch**, run the share-link checks again, then check the home page, `/privacy` and a
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
