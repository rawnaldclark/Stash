# feature:record — Your record

The record from Stash on the web, built into the app (design: `~/.stash/design/record-in-app-2026-10-26/PLAN.md`). The fork of
On Record (`rawnaldclark/stash-on-record`, branch `stash/app-host`) is bundled here and runs offline in a full-screen WebView;
the app feeds it this phone's plays, and a play tapped in it plays in Stash.

## The bundled record

| Served at | From | What |
|---|---|---|
| `https://appassets.androidplatform.net/record/embed.html?embed=stash-app` | `src/main/assets/record/` | the fork's `dist/embed.html` and `dist/assets/` |
| `https://appassets.androidplatform.net/fonts/` | `src/main/assets/record-fonts/` | Inter and Space Grotesk (theme.css loads them from the site root), with their OFL licences |

**Bundled build:** stash-on-record `35fe50f` ("feat(stash): in the app, the record acks each archive chunk (stash:archive-ack), so
the app keeps two in flight"), 1,518,856 bytes, plus 65,663 bytes of fonts. The page at this commit takes the app's rows up to
24 h, keeps the first row per id, sorts them by id, acks each `stash:archive` chunk (`stash:archive-ack {seq}`, the last one too,
and a stale stream's chunk as well, though it drops it: the app keeps at most two chunks unacknowledged), sends `stash:save`
chunks two unacknowledged at a time (the app answers each with `stash:save-ack`, and hears `stash:save-abort` when the page gives
up), and answers every `stash:back` with `stash:backed` or `stash:close`.

The Home card's parity fixture (`src/test/resources/parity/`, `card-parity.rows.json` and `card-parity.expect.json`) is the
fork's `src/stash/fixtures/` at the same commit, byte for byte: refresh it with the build when the page's logic changes.

Left out on purpose: the landing page and the site's files (`index.html`, `index-*`, `og.png`, icons, `robots.txt`,
`sitemap.xml`, `_headers`), the demo's chunk (`load-*.js`, imported only with `?demo=1`), every `.woff` (the `.woff2` is used
everywhere) and Instrument Serif's upright 400 (the embed sets it italic only).

### Refreshing it

1. In the fork, on `stash/app-host`: `npm run build`.
2. From this repository's root: `node feature/record/scripts/record-assets.mjs --fork <stash-on-record> --fonts <stash-player>/static/fonts`
   (the defaults are the sibling checkouts next to the main one, `../stash-on-record` and `../stash-web/player-upi/static/fonts`).
   It empties both asset folders first and fails without copying when the build's `embed.html` names a file that isn't there.
3. Update the commit above, build, and check the record on a phone.

## The bridge (protocol v1)

`StashHost` is injected into the page before its scripts run (`WebViewCompat.addWebMessageListener`, this origin only). The page
posts JSON strings to it; the reply proxy from its `stash:hello` carries the app's answers. `bridge/RecordHost.kt` handles each
message and is tested apart from the WebView; `RecordMessages.kt` reads and writes them with the web player's rules.

## Covers

`art/` is the web's cover lookup run on the phone: `ArtLookup` is the Worker's (`worker/art.ts`: Deezer album, then Deezer
track, then Apple's iTunes search, sized 600; artist pictures from Deezer, its searches paced), `RecordArt` the client's
(`src/lib/spotify/art.ts`: batches of 25, a minute's pause after a refusal, one retry of what Deezer couldn't look up yet). Kept
in `noBackupFilesDir/record-art.tsv` (180 days a cover, 3 days a miss). Only Deezer CDN and mzstatic addresses are taken: the
record's page allows no other images.

## Bring your Spotify (the import)

The web player's import (stash-player `src/lib/spotify/`, spec `docs/spotify-export.md`) on the phone: the same files give the
same plays with the same ids.

| Where | What |
|---|---|
| `importer/ExportZip.kt` | zip.ts: the central directory, stored and deflated entries, Zip64, zips inside zips up to 3 deep (a deflated one inflated to a scratch file, never memory) |
| `importer/JsonPull.kt` | a pull reader that takes exactly what `JSON.parse` takes; a history file is read a token at a time |
| `importer/ExportReader.kt`, `ExportText.kt`, `JsDate.kt` | export.ts's row rules: Extended and Account history, V8's `Date.parse`, `Date.UTC`'s overflow, `str`'s 300-unit cut, reasons and flags; playlists and the library only counted (phase 5 brings them into the library) |
| `importer/PlanMerge.kt` | plays.ts `planMerge`, keyed by numbers so half a million plays stay small |
| `importer/SpotifyImporter.kt` | importer.ts: `prepare` (reads and plans, writes nothing) and `commit` (plans again, writes in batches of 5,000, adds before drops); the import record |
| `imports/RecordImports.kt` | service.ts: the state the sheet, the Home card and Settings show; files copied in first (`ImportIntake`), each step run by `RecordImportWorker`, a foreground job |
| `imports/ImportSheet.kt` | ImportFlow.svelte and ImportSheet.svelte: how it works, progress, the preview, a kind word when a file can't be used |

The plays go to `record_plays` (core:data, DB v68), never to `listening_events`: they are never scrobbled, mirrored or exported
as this phone's own. The record's archive is `record_plays` and then this phone's own plays, and the Home card is worked out
from the same rows.

### Its tests

- `SpotifyGoldenTest`: the web's fixture (`src/test/resources/spotify-export/`, stash-player `docs/spotify-export/fixture/` at
  0e2cb03, byte for byte) against its `expected.json`: files, unreadable, the preview, 6,814 plays, the first ids and the
  SHA-256 of every id. Refresh the three files when the web regenerates them.
- `SpotifyVectorsTest`: small exports crafted around each rule, run through the web's own importer by
  `scripts/spotify-vectors.ts` (the run is in its header; it needs a checkout of stash-player with its `node_modules`), compared
  row by row. Run it again after a change to the web's rules, and port the change until the test passes.
- `JsDateTest` and `JsonPullTest` hold node's own answers for `Date.parse` and `JSON.parse`.
