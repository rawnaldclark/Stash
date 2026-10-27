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

The Home card's parity fixtures (`src/test/resources/parity/`) are the fork's `src/stash/fixtures/`, byte for byte:
`card-parity.rows.json` and `card-parity.expect.json` at the bundled commit, and `card-parity-sources.rows.json` and
`card-parity-sources.expect.json` (every source stacked, the switches) at `2e5d17a`, which adds only that fixture and its test
to the bundled build (the page is the same). Refresh them with the build when the page's logic changes.

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

## The sources and the switches

The record is the Spotify export, this phone's own plays and the Last.fm scrobbles together, a listen counted once (spec §7).
In the app the page stacks nothing itself (its `stack` drops only scrobbles a `stash:live` play covers, and the app sends no
`stash:live`), so the app does it before the archive goes out:

| Where | What |
|---|---|
| `archive/RecordArchive.kt` | the page's archive: the export's plays, this phone's own, then the scrobbles none of them cover |
| `archive/StashCover.kt` | which scrobbles this phone's plays cover: the same folded title, starting no more than 180 s before a play's start or after its end (a skip covers nothing; a scrobble with a skip's very id is left out, as the page keeps one row an id) |
| `archive/RecordSources.kt` | record.ts's switch rules (`readSources`, `present`, `shownSources`, `toggleSource`), the choice kept on this phone, and each source's listens counted as the page counts them |
| `RecordSourceBar.kt` | the switches, a bar right above the record (the web's sit in the header above it): one per source with plays, only with two or more, one always on |
| `card/RecordCardSource.kt` | the Home card as the sum of each source's part for the same switches; the scrobbles' part is read again only when what the plays here cover changed (`archive/ScrobbleIndex.kt`) |

The host counts each source as the archive's chunks go out and tells the page its sources (`stash:sources`) just before the
last chunk, so the page presses the record once with the right ones (the web sends it after the first `stash:ready`); a switch
flipped, or the page's own `stash:counts`, sends it again only when it changed.

## Your Last.fm scrobbles

The web player's Last.fm import (stash-player `src/lib/spotify/lastfm.ts`, `lengths.ts`, `plays.ts`, `service.ts`,
`worker/lastfm-history.ts`, spec §7) on the phone, reading the connected account (Settings › Accounts & Sync) straight from
Last.fm through the app's one client, never the Stash proxy; the song lengths after it, through the proxy only:

| Where | What |
|---|---|
| `lastfm/LastfmHistory.kt` | the Worker's reading of a `user.getRecentTracks` page: clean words, the now-playing row out, times 2002 to a day ahead; 6 and 17 on page 1 an empty last page, after it "try again"; a page before the last that lists nothing "try again", counted (`Empty`: a hole, with the pages Last.fm claims); 9 on a signed read a reconnect; anything else "try again" |
| `lastfm/PlanScrobbles.kt` | `planScrobbles`, `knownLengths`, `libraryLengths`, `bestLengths`, `scrobbleMs`, `coveredBy`: a scrobble's row (`textKey|uts·1000`, LASTFM; its song's length from a finished Spotify play, else the library's longest, never under 30 s; else 210,000 ms marked ESTIMATED), left out when kept already or a Spotify play covers it |
| `lastfm/ImportScrobbles.kt` | the resumable run and its mark (user, a fixed `to`, the next page, done, newest, the count, `base`): where a run starts (`startMark`: another account's rows the base; a read that found nothing read again; the count taken again from the archive), only a page at or past the last ending a read, a page claiming fewer pages (empty or not) asked again 6 times, a page that stays empty before the last first asks the claimed last page once (empty too: the tail is gone, the read done), then is skipped after 3 runs in a row over at least an hour (`stuck` with its first and latest run and the claimed pages, so skipping a first page never ends a read that hasn't begun; never for an outage or three quick Carry on taps), a skipped page kept (`skipped`) and asked once again on every later top-up (read in once it has plays; while one is left the done line says "Last.fm didn't send part of your history; Stash will ask again next time."), the top-up from 14 days below the newest (its window moved only once all of it is in), the mark saved every page; 350 ms between pages, a busy page asked again 10 s times the attempt (6 at most) |
| `lastfm/LastfmPages.kt` | a page from Last.fm: unsigned on the recap's read keys; a private profile reads itself with its own session key (the web always reads signed); a session Last.fm no longer takes, or an account changed under the run, is a reconnect |
| `lastfm/RecordLastfm.kt`, `RecordLastfmWorker.kt` | service.ts's Last.fm half: bring in, carry on, top up (hourly at most, on opening the record), stop, remove; the done line's count (`added`, and the whole import as `total` when the run read it); a foreground job with a notification, run again by WorkManager after a system stop or the app's death, from its mark; the archive's write lock (`imports/RecordWrites.kt`, shared with the Spotify import) held for the run. A job carries what it was asked for (`LastfmAsk`: the account and the scrobbles' generation) and does nothing for another; both removals cancel the job and move the generation on, so none can bring the scrobbles back |
| `lastfm/Lengths.kt` | lengths.ts: the scrobbles still guessed, planned by spelling once a run (`plan`: Spotify or library lengths that came later first, then Last.fm's, most played first) and asked a slice at a time (`slice`: up to 200 asks or 20 s, written in one go at its end; a miss kept 30 days; a song asked twice while busy, then waiting 1, 2, 4, 8, at most 16 days; 3 songs failing in a row, across slices, end the run; an answer that isn't one marks its song before the pass stops; a hold (a limit, no network the setting allows, no proxy) ends it marking nothing, with when to come back for a limit; what Spotify or the library times is filled first all the same) |
| `lastfm/RecordLengths.kt`, `RecordLengthsWorker.kt`, `LookupBudget.kt` | service.ts `timeSongs`: the pass in slices under the write lock, as a quiet job asked for with no network (the free fill needs none), needing no Last.fm session; its lookups only on a network the app's Wi-Fi-only setting allows (as its syncs and cloud uploads: unmetered when Wi-Fi only, else any), else the run ends after the fill and comes back as a job waiting for that network, which a change of the setting re-queues while it waits (SyncScheduler `applyWifiOnlyToWaitingSync`'s rule: never one running); runs in a row held so wait 15 minutes, doubling to 6 hours, after the first (a network the job's constraint takes but the lookups can't use, one not validated, never loops it); stopped first by a Last.fm run, a Spotify commit or a removal (an ask in flight cancelled); started after a run (one that ended for a reconnect too) and after a commit once Last.fm is in; each run holds a token, so only the current one goes on, lets the card go or asks for a wake-up (in the same lock a Remove or Stop takes, so neither is followed by one); the import record, the Home card and an open record read again after the first slice that timed songs, then every 2 minutes at most, and at the end. Its lengths come from `LastFmRecapRepository.songLength` (track.getInfo through the proxy only, on a breaker of its own), at least 1 s apart, at most 2,000 a rolling day per device (`DAILY_LOOKUPS`: the proxy's key is the one every listener's scrobbling uses); a rate limit holds them 30 minutes; a run held by either still plans and fills what needs no lookup, and comes back by itself when it lifts. The budget never trusts the wall clock: hours more than an hour ahead of now are dropped, and a hold is at most 30 minutes from now. Without a proxy in the build there are no lookups and nothing is marked |
| `lastfm/LastfmPart.kt` | LastfmPart.svelte: in the import sheet (its second way in), and as Settings' rows in the Your record group ("Import from Last.fm" with its status and its action, "Remove your Last.fm scrobbles"; handed to Settings by the app); not connected, it goes to the app's own Last.fm sign-in |

The scrobbles go to `record_plays` with the LASTFM flag, beside the export's plays; the mark is kept with the import record in
the `your_record` preferences, with the lengths pass's count and time; the songs the pass couldn't time are in `record_lengths`
(DB v69; `kind` 0 no length, else the failures in a row). Remove import clears them too. Scrobbles alone make an import
record. The pace is the Last.fm recap's (`LastFmHistoryPacer`, shared), so the two never ask faster than Last.fm allows together.

Left as they are, on purpose:
- The history is read unsigned first, on the recap's read keys, and signed only for a profile that hides its listening (the web
  always reads signed): a whole history's paging never lands on the key scrobbling and sign-in depend on. The same pages come
  back; a session revoked on a public profile isn't noticed by the import.
- A scrobble's length depends on what was in when it came: a row already timed by the library or by Last.fm isn't revisited
  when a later Spotify import knows the song's length to the second. A few seconds a play, and the web does the same.

### Its tests

- `LastfmVectorsTest`: cases run through the web's own code by `scripts/lastfm-vectors.ts` (`src/test/resources/lastfm/`),
  compared field by field: Last.fm's answers (signed and not), plans with library lengths, coverings, whole runs (asks,
  progress, the mark they start from, the mark, rows), `startMark`, the lengths pass (plans, slices: asks, saves, rows) and
  the switch rules. The run, against a stash-player checkout with its `node_modules` (read only), is in the script's header.
- `LastfmWordsTest`: the English done line (with part of the history still missing too), reconnect line and hours note against
  the web's (`words.json`).
- `RecordLengthsWorkerTest`: what the pass's job asks of WorkManager (no network at once; unmetered or any network for the
  lookups; the setting re-applied to a waiting job, never a running one), on WorkManager's test runtime.
- `SourcesParityTest`: the page's `card-parity-sources` fixture: the scrobbles left out, the counts, and the record and the
  Home card for all eight switch states.
- `LastfmPagesTest`, `RecordLastfmTest` (with the web service's done states, `service.json`), `RecordLengthsTest`,
  `DaoLengthsStoreTest`, `RecordHostSourcesTest`, `StashCoverTest`: the reader's answers, the service's runs (stop and carry on,
  top-up, another account, busy, reconnect, the Spotify import first, remove, the pass around them), the pass's slices and
  refreshes, the database's own slice, `stash:sources`, the 180 s edges.

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
| `importer/ExportZip.kt` | zip.ts: the central directory, stored and deflated entries, Zip64, zips inside zips up to 3 deep (a deflated one inflated to a scratch file, never memory); beyond the web, a 32 MiB directory cap and records whose data overlap refused |
| `importer/ImportBudget.kt` | one import's ceilings across every zip: 50,000 directory records (counted once a directory is read and checked), 2 GiB inflated, 2,000,000 rows, 256 zips opened, 1 GiB of nested zips on disk and never the last 64 MB free (a heavy export: a few dozen records, 300-400 MB, ~500k rows) |
| `importer/JsonPull.kt` | a pull reader that takes exactly what `JSON.parse` takes; a history file is read a token at a time |
| `importer/ExportReader.kt`, `ExportText.kt`, `JsDate.kt` | export.ts's row rules: Extended and Account history, V8's `Date.parse`, `Date.UTC`'s overflow, `str`'s 300-unit cut, reasons and flags; playlists and the library only counted (phase 5 brings them into the library) |
| `importer/PlanMerge.kt` | plays.ts `planMerge`, keyed by numbers so half a million plays stay small |
| `importer/SpotifyImporter.kt` | importer.ts: `prepare` (reads and plans, writes nothing) and `commit` (plans again, writes in batches of 5,000, adds before drops); the import record |
| `imports/RecordImports.kt` | service.ts: the state the sheet, the Home card and Settings show; files copied in at once in the app (`ImportIntake`: content URIs of other apps only, 64 files and 2 GiB at most, bytes counted; a name reserved, the bytes streamed with no lock held, the part renamed, so a provider that never answers holds up nothing), each step run by `RecordImportWorker`, a foreground job. A system stop keeps `pending` and the copies and the step runs again (given up after 3); Cancel ends it; a failure ends at Failed, with the card's "Your import didn't finish" and a notification when something went wrong |
| `imports/RecordIntakeActivity.kt`, `RecordIntake.kt` | where an export opened with or shared to Stash lands: a see-through screen in the sender's task whose model (kept across a recreation) looks the types up off the main thread, hands the export over, forwards any other files to Stash's window with a grant of their own, and lets the screen go once the copy is done |
| `imports/ImportSheet.kt` | ImportFlow.svelte and ImportSheet.svelte: how it works, progress, the preview, a kind word when a file can't be used |

The plays go to `record_plays` (core:data, DB v68), never to `listening_events`: they are never scrobbled, mirrored or exported
as this phone's own. The record's archive is the export's plays, this phone's own, then the scrobbles they don't cover ([The
sources and the switches](#the-sources-and-the-switches)), and the Home card is worked out from the same rows.

### Its tests

- `SpotifyGoldenTest`: the web's fixture (`src/test/resources/spotify-export/`, stash-player `docs/spotify-export/fixture/` at
  0e2cb03, byte for byte) against its `expected.json`: files, unreadable, the preview, 6,814 plays, the first ids and the
  SHA-256 of every id. Refresh the three files when the web regenerates them.
- `SpotifyVectorsTest`: small exports crafted around each rule, run through the web's own importer by
  `scripts/spotify-vectors.ts` (the run is in its header; it needs a checkout of stash-player with its `node_modules`), compared
  row by row. Run it again after a change to the web's rules, and port the change until the test passes.
- `JsDateTest` and `JsonPullTest` hold node's own answers for `Date.parse` and `JSON.parse`.
- `RoomImportTest` (Robolectric): the fixture through an in-memory Room, the web's ids exactly. SQLite can't hold a lone
  surrogate (a title the web's `str` cut through an emoji): left alone it reads it as U+FFFD at the end of a text but pairs it
  with the next unit mid-text (an id's bar), so the store writes U+FFFD itself; such an id then differs from the web's in that
  one unit. The fixture has none.
- `ExportZipTest`, `ImportIntakeTest`, `RecordImportsTest`: hostile files (Zip64, encrypted, larger than it says, overlapping
  records, the budgets), the intake's bounds, resume from a half-written archive, a system stop against a Cancel, failures.
