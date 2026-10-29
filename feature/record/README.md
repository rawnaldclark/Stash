# feature:record — Your record

The record from Stash on the web, built into the app (design: `~/.stash/design/record-in-app-2026-10-26/PLAN.md`). The fork of
On Record (`rawnaldclark/stash-on-record`, branch `stash/app-host`) is bundled here and runs offline in a full-screen WebView;
the app feeds it this phone's plays, and a play tapped in it plays in Stash.

## The bundled record

| Served at | From | What |
|---|---|---|
| `https://appassets.androidplatform.net/record/embed.html?embed=stash-app` | `src/main/assets/record/` | the fork's `dist/embed.html` and `dist/assets/` |
| `https://appassets.androidplatform.net/fonts/` | `src/main/assets/record-fonts/` | Inter and Space Grotesk (theme.css loads them from the site root), with their OFL licences |
| `https://appassets.androidplatform.net/art/v1/<32 hex>.jpg` | `cacheDir/record-library-art/` | the library's own covers, by the names the host gave the page ([Covers](#covers)) |

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

Every other path on this origin (one no handler serves, one a handler refuses, a missing asset, a cover never given) is a 404
with an empty body (`recordResponse`), so the record's origin never reaches the network.

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
| `lastfm/ImportScrobbles.kt` | the resumable run and its mark (user, a fixed `to`, the next page, done, newest, the count, `base`): where a run starts (`startMark`: another account's rows the base; a read that found nothing read again; the count taken again from the archive), only a page at or past the last ending a read, a page claiming fewer pages (empty or not) asked again 6 times, a page that stays empty before the last asks what lies past it: the claimed last page once (with plays: a hole in the middle), and if that is empty the page before again (emptied too: an incident, plain busy and not counted; still full: the tail is gone). A hole or a tail is skipped only after 3 runs in a row over at least an hour (`stuck` with its first and latest run, the claimed pages and `tail`, so skipping a first page never ends a read that hasn't begun; never for an outage or three quick Carry on taps). What was skipped is kept as a stretch of time, never a page number (`holes: {from, to}`: from the newest play of the next page with plays, 0 for the tail, to the oldest play of the last page read with plays), and every later top-up, after its own window (new scrobbles never wait on a hole), asks each by time, its bounds a second wider, each page once, at most `HOLE_PAGES` (100) pages a run in all, the hole narrowed with every page saved, so a big one cut short (the system's ten minutes) goes on next time from there. One that reads through is gone; one Last.fm says is empty (total 0, or only what its bounds hold) only when it says so again an hour on; one that fails waits a day, doubling to 16 (`fails`, `next`); beside another account's rows a recheck counts this account's own plays inside it; while one is left the done line says "Last.fm didn't send part of your history; Stash will ask again next time." An earlier build's skipped pages load as the stretches they could hold (a whole read's page: everything below its bound, read once more), the top-up from 14 days below the newest (its window moved only once all of it is in), the mark saved every page; 350 ms between pages, a busy page asked again 10 s times the attempt (6 at most) |
| `lastfm/LastfmPages.kt` | a page from Last.fm: unsigned on the recap's read keys; a private profile reads itself with its own session key (the web always reads signed); a session Last.fm no longer takes, or an account changed under the run, is a reconnect |
| `lastfm/RecordLastfm.kt`, `RecordLastfmWorker.kt` | service.ts's Last.fm half: bring in, carry on, top up (hourly at most, on opening the record), stop, remove; the done line's count (`added`, and the whole import as `total` when the run read it); a foreground job with a notification, run again by WorkManager after a system stop or the app's death, from its mark; the archive's write lock (`imports/RecordWrites.kt`, shared with the Spotify import) held for the run. A job carries what it was asked for (`LastfmAsk`: the account and the scrobbles' generation) and does nothing for another; both removals cancel the job and move the generation on, so none can bring the scrobbles back |
| `lastfm/Lengths.kt` | lengths.ts: the scrobbles still guessed, planned by spelling once a run (`plan`: Spotify or library lengths that came later first, then Last.fm's, most played first) and asked a slice at a time (`slice`: up to 200 asks or 20 s, written in one go at its end; a miss kept 30 days; a song asked twice while busy, then waiting 1, 2, 4, 8, at most 16 days; 3 songs failing in a row, across slices, end the run; an answer that isn't one marks its song before the pass stops; a hold (a limit, no network the setting allows, no proxy) ends it marking nothing, with when to come back for a limit; what Spotify or the library times is filled first all the same) |
| `lastfm/RecordLengths.kt`, `RecordLengthsWorker.kt`, `LookupBudget.kt` | service.ts `timeSongs`: the pass in slices under the write lock, as a quiet job asked for with no network (the free fill needs none), needing no Last.fm session; its lookups only on a network the app's Wi-Fi-only setting allows (validated, as SyncScheduler counts one; unmetered when Wi-Fi only, else any; one just back given 30 s to validate, as WorkManager may start the waiting job before then), else the run ends after the fill and comes back as a job waiting for that network, which a change of the setting reaches while it waits, the job updated in place (`updateWork`, its id and delay kept; one replaced between the read and the update passed over, never a crash, and the following of the setting goes on through any failure), so one queued behind a pass still running takes it too, and the wait a run queues as it ends takes the setting as it is then. A temporarily unmetered network counts as unmetered. Only runs started by that waiting job back off when held again: 15 minutes, doubling to 6 hours, the count kept in prefs (a network the job's constraint takes but the lookups can't use, one not validated, never loops it); such a run asks the network before planning. A run asked for (opening the record, a run of the import) waits with no delay and never grows the count; stopped first by a Last.fm run, a Spotify commit or a removal (an ask in flight cancelled); started after a run (one that ended for a reconnect too) and after a commit once Last.fm is in; each run holds a token, so only the current one goes on, lets the card go or asks for a wake-up (in the same lock a Remove or Stop takes, so neither is followed by one); the import record, the Home card and an open record read again after the first slice that timed songs, then every 2 minutes at most, and at the end. Its lengths come from `LastFmRecapRepository.songLength` (track.getInfo through the proxy only, on a breaker of its own), at least 1 s apart, at most 2,000 a rolling day per device (`DAILY_LOOKUPS`: the proxy's key is the one every listener's scrobbling uses); a rate limit holds them 30 minutes; a run held by either still plans and fills what needs no lookup, and comes back by itself when it lifts. The budget never trusts the wall clock: hours more than an hour ahead of now are dropped, and a hold is at most 30 minutes from now. Without a proxy in the build there are no lookups and nothing is marked |
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
record's page allows no other images but its own origin's.

### The library's own covers (phase 6)

Owner decision D1 (`~/.stash/design/record-in-app-2026-10-26/PHASE6.md`): the person's own files' art comes first. A
`stash:art` is answered in three steps (`RecordHost.art`), each a `stash:covers` of its own names, then the whole list (the page
takes the last word for a name, so the last list repeats what the ones before said):

1. **Own files** (`RecordLibraryArt.owned`, the index alone, then `local` to make them): a local import's, a scanned song's or a
   cloud file's embedded cover, a download's file. These songs are never asked of `RecordArt`, unless their file couldn't be made.
2. **The lookups** (`RecordArt`, Deezer then iTunes) for the rest, at once: they never wait for a file being made.
3. **The library's remote covers** (i.scdn.co, ytimg, ...) only for what the lookups found nothing for, read from Coil's disk
   cache alone (never fetched). Artists' pictures (`ar3:`) are never the library's.

| Where | What |
|---|---|
| core:data `db/dao/LibraryArtDao.kt` | the two reads: tracks with a cover (title, artist, album artist, album, path, address) and scanned songs with an embedded one |
| `art/LibraryArtIndex.kt` | the covers by `ArtText.artKey`: `al:` artist and album, `al:` album artist and album, `tr:` artist and title; the first row a key wins, files and addresses kept apart so a file always comes first; words that fold to nothing make no key |
| `art/LibraryArt.kt` | the index read once per record opened, off the main thread, on the page's first ask (its size and build time logged under `RecordArt`); each source made once however many songs share it |
| `art/LibraryArtFiles.kt` | each cover made into `cacheDir/record-library-art/<hash>.jpg` before its name is given (the page's covers have no fallback, so it never meets a 404): decoded by Coil at 600 (at most 3 s; no source over 20 MB), the middle square, JPEG q85, so no EXIF and no byte of the source reaches the page. The name is 32 hex of sha256 of the file's canonical path, length and modification time, or of the address. Only files in the library's cover folders (`cacheDir/albumart`, `filesDir/scanned_art`, `filesDir/cloud_art`), made canonical first, are read: never external storage or `content://`. What each name was made from is kept in memory: only names given in this process are served, and a file removed is made again when asked for. The folder is trimmed to 40 MB, least recently shown first, each time the record opens and after every 50 covers made |
| `art/LibraryArtHandler.kt` | `/art/` on the record's origin: only `v1/<32 lowercase hex>.jpg`, only from its own folder, `Cache-Control: private, max-age=31536000, immutable`; anything else is null (nothing found) |

None of these addresses goes into `record-art.tsv`, and the Home card and the mixes' tiles still use the lookups alone.

### Its tests

- `LibraryArtIndexTest` (keys for remasters, feat. and the album artist; files before addresses), `LibraryArtEncodeTest`
  (3000 px to a 600 square through Coil, EXIF gone, a file that isn't a picture, paths outside the cover folders, 20 MB and the
  decode's time limit, the trim after 50), `LibraryArtHandlerTest` (the name's rule, traversal plain and encoded, a name never
  given or given by another process, a removed file made again, the trim, the headers), `LibraryArtTest` (the index read once
  for asks that come together, an album's cover made once, a database that fails), `RecordHostTest` (own files, the lookups for
  the rest at once without waiting for a file, a file that can't be made looked up, then the library's addresses; a library
  that fails), `RecordLoaderTest` (the origin's 404s, `/art/` reaching its handler) and `RecordCspTest` (the bundled
  `embed.html` lets `img-src` load `'self'`).

## Bring your Spotify (the import)

The web player's import (stash-player `src/lib/spotify/`, spec `docs/spotify-export.md`) on the phone: the same files give the
same plays with the same ids.

| Where | What |
|---|---|
| `importer/ExportZip.kt` | zip.ts: the central directory, stored and deflated entries, Zip64, zips inside zips up to 3 deep (a deflated one inflated to a scratch file, never memory); beyond the web, a 32 MiB directory cap and records whose data overlap refused |
| `importer/ImportBudget.kt` | one import's ceilings across every zip: 50,000 directory records (counted once a directory is read and checked), 2 GiB inflated, 2,000,000 rows, 256 zips opened, 1 GiB of nested zips on disk and never the last 64 MB free (a heavy export: a few dozen records, 300-400 MB, ~500k rows) |
| `importer/JsonPull.kt` | a pull reader that takes exactly what `JSON.parse` takes; a history file is read a token at a time |
| `importer/ExportReader.kt`, `ExportText.kt`, `JsDate.kt` | export.ts's row rules: Extended and Account history, V8's `Date.parse`, `Date.UTC`'s overflow, `str`'s 300-unit cut, reasons and flags; `playlistsFile`, `libraryFile`, `localSong` and `libSong`: each playlist's songs (tracks, and local files decoded as `decodeURIComponent` does, `+` a space; episodes and audiobooks left out) with `addedDate` read by `Date.parse` (its ISO forms; V8's looser fallback forms read as no date), Liked Songs, and the songs hidden on Spotify |
| `importer/PlanMerge.kt` | plays.ts `planMerge`, keyed by numbers so half a million plays stay small |
| `importer/SpotifyImporter.kt` | importer.ts: `prepare` (reads and plans, writes nothing) and `commit` (plans again, writes in batches of 5,000, adds before drops); the import record |
| `imports/RecordImports.kt` | service.ts: the state the sheet, the Home card and Settings show; a commit in four steps under the write lock (the plays; the hidden songs; the playlists and likes chosen, `ImportProgress.Library`; then the record, +1 import and the playlists and likes brought, with `pending` cleared in that same preferences write: the first three are safe to run again), the choice (`ImportChoice`) kept beside `pending` so a commit taken up after the app's death brings in the same, from the time it began; files copied in at once in the app (`ImportIntake`: content URIs of other apps only, 64 files and 2 GiB at most, bytes counted; a name reserved, the bytes streamed with no lock held, the part renamed, so a provider that never answers holds up nothing), each step run by `RecordImportWorker`, a foreground job. A system stop keeps `pending` and the copies and the step runs again (given up after 3); Cancel ends it; a failure ends at Failed, with the card's "Your import didn't finish" and a notification when something went wrong |
| `imports/RecordIntakeActivity.kt`, `RecordIntake.kt` | where an export opened with or shared to Stash lands: a see-through screen in the sender's task whose model (kept across a recreation) looks the types up off the main thread, hands the export over, forwards any other files to Stash's window with a grant of their own, and lets the screen go once the copy is done |
| `imports/ImportSheet.kt` | ImportFlow.svelte and ImportSheet.svelte: how it works, progress, the preview (a tick per playlist, "N of M"; a switch for the liked songs; Import waits until something is chosen; with no new plays, that every play is here already and the playlists and likes can still come in; a warning when Liked Songs is kept offline), a kind word when a file can't be used |
| `imports/RecordLibrary.kt`, core:data `record/SpotifyLibraryImport.kt` | the library half (service.ts `confirm`, `libraryData`): the chosen playlists and the liked songs through the web library's own importer (`WebLibraryImporter`, an internal overload with the source-id rule and a batch-at-a-time progress: songs matched by identity, else a stream-only row; blocked songs left out; adds only). A playlist is `custom_spotify_` + 24 hex of `sha256(name#n)`, `n` counting the earlier playlists of that name in the whole export, chosen or not: CUSTOM, source BOTH (a Spotify sync deactivates only SPOTIFY ones), never kept offline, so nothing downloads; with no map to keep, a playlist renamed here still gains its songs and Remove then the same export again makes nothing twice. Likes are Stash likes in Spotify's order (`likedAt = now − i`). Owner decision D3: an export playlist starts unticked, "Already in your library", when one of the listener's own playlists (as Library shows them, not one a Spotify import made) has the same folded name and at least half its songs by occurrence (importer.ts `alreadyIn`, the web's same rule) |

The songs hidden on Spotify go to `record_hidden` (core:data, DB v70), by song key, for the taste (phase 5b); Remove import
empties it, and so does removing the scrobbles when they were all there was. The playlists and liked songs stay in the library
through a removal (Settings says so).

The plays go to `record_plays` (core:data, DB v68), never to `listening_events`: they are never scrobbled, mirrored or exported
as this phone's own. The record's archive is the export's plays, this phone's own, then the scrobbles they don't cover ([The
sources and the switches](#the-sources-and-the-switches)), and the Home card is worked out from the same rows.

### Its tests

- `SpotifyGoldenTest`: the web's fixture (`src/test/resources/spotify-export/`, stash-player `docs/spotify-export/fixture/` at
  0e2cb03, byte for byte) against its `expected.json`: files, unreadable, the preview, 6,814 plays, the first ids and the
  SHA-256 of every id, the playlists (Road trip 9, Glass Harbor era 8) and the 8 likes. Refresh the three files when the web
  regenerates them.
- `SpotifyLibraryImportTest` (Robolectric): the fixture's playlists and likes through a real in-memory library: CUSTOM BOTH
  `custom_spotify_…` playlists, nothing queued to download, the same export again adds nothing, a renamed playlist gains its
  missing songs and keeps its own, blocked songs left out, a Spotify sync's sweep leaves them, Liked Songs in Spotify's order, D3
  against the listener's own playlists only, Liked Songs kept offline, the hidden table.
- `AlreadyInVectorsTest`: D3's rule against the web's `alreadyIn`, case by case (`vectors/already.json`).
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

## Your taste, and your mixes

The web player's taste (stash-player `src/lib/spotify/taste.ts`, `importer.ts` `refreshTaste`, `mix-taste.ts`, `kept-out.ts`,
`discover/mix.ts`, `discover/record-switch.ts`, spec `docs/spotify-export.md` §4) on the phone: the whole record becomes a taste,
and the taste feeds Stash's mixes.

| Where | What |
|---|---|
| core:model `weblink/SongKey.kt` (`bareTitle`, `songKey`), `Credits.kt`, `JsString.kt` | song-key.ts and station.ts `primaryArtist`, shared with the art and the mixes: JavaScript's classes spelled out (`/i` without `u` folds ASCII only, `\d` is `[0-9]`, `\s` its own set, `$` is `\z`), so ICU, the JVM and V8 agree |
| `taste/Taste.kt` | `computeTaste` and `findEras`, rule for rule: one play order (start, title, artist in UTF-16 units, length), sums and cosines in insertion order, `round9` half up, `StrictMath` log1p and pow, `INTERLUDE` with an ASCII word boundary |
| `taste/TasteInput.kt` | `refreshTaste`'s rows, a page at a time: `record_plays`, minus the scrobbles this phone's plays cover (same folded title, 180 s, the taste's spans `max(30 s, length)`), plus this phone's plays as `f = 0`, `ms = max(30 s, length)` unless the archive holds their id |
| `taste/RecordTaste.kt`, `RecordTasteWorker.kt` | the taste worked out under the record's write lock, only while there is an import: a commit (its own step in the sheet, the songs it brings kept, before its record), an interrupted import's end, a Last.fm run, the scrobbles' removal, the end of a lengths pass (its slices update the hours only), start with no taste, once a local day on Home or the record, and the daily job. Kept = liked songs and the own playlists' songs; hidden = `record_hidden`; never kept out = the songs put back. The first taste with seed artists asks for Daily Discover when it is empty; an artist whose name folds to nothing is no seed |
| `taste/TasteRows.kt` | where it is kept (core:data, DB v71): `record_taste_songs` (a row a song, its rank), `record_taste` (the rest as JSON: totals, eras, lists, years, top and seed artists), `record_taste_keep` (put back) |
| core:data `record/RecordMixTaste.kt` | the one seam the mixes read, once a run: `MixTaste(artists, avoid, weights)`, null with no taste or with the switch off; avoid = the stored avoid minus the songs put back minus the library's songs now (liked, in a playlist: liking a kept-out song puts it back at once), such a song given its weight (weight ÷ the top weight of every song) |
| core:data `mix/MixGenerator.kt`, `sync/workers/StashMixRefreshWorker.kt` | with a taste: avoided songs out of every recipe's library pool, its discovery queue and its finds (before the rotation window is cut, so the mix still fills); affinity `max(own, 0.6·w)`; Daily Discover's seeds the web's blend (the taste's artists keep `max(3, 8 − own)` places; with no own seeds the taste alone, before the library's top artists). With none, every path is the one before (the existing suites pass unmodified). The blocklist is never used for this: it deletes |
| `taste/RecordInMixes.kt`, `RecordInMixesPart.kt` | Settings › Your record (owner decisions D2, D1): "Use your record in mixes" (on by default; a flip makes today's Daily Discover again once, 1.5 s after the last) and "Kept out of your mixes (N)" with the web's reasons and words (a song in the library now left out), 50 a page, and Put back (one write under the lock, only with an import). The switch's line is the app's own: here the record shapes every Stash Mix |

Remove import clears the taste and the songs put back with the plays; removing the Last.fm scrobbles does too only when they
were all there was. The songs put back survive a newer import.

### Its tests

- `TasteVectorsTest`: cases crafted around every rule (remasters, KELVIN SIGN and LONG S, Arabic-Indic digits, word boundaries,
  astral characters, mode ties, songs only ever started, an artist that folds to "", an era cosine of exactly 0.4 after round9,
  quiet months, gaps of 3 and 4 months, the era's longest, lead ties, same-lead joins, a 14-day peak, UTC year edges, kept
  against hidden against put back), run through the web's own code by `scripts/taste-vectors.ts` (`src/test/resources/taste/`;
  the run is in its header): every field of the taste with doubles compared exactly, `mixTasteOf`, `seedArtists`' blend,
  `refreshTaste`, `keptOut` and `whyKeptOut`, and the words.
- `TasteIcuAndroidTest` (on a phone): the same words and every taste on ICU and ART.
- `SpotifyGoldenTest`'s taste block (top 10 weights to 6 places, `BigDecimal.setScale(6, HALF_UP)`), `TasteWordsTest` (the
  English against RecordInMixes.svelte as the file is), `RecordTasteTest` (a real database), `RecordInMixesTest`, and in core:data
  `MixGeneratorTasteTest`, `StashMixRefreshWorkerTasteTest`, `RoomRecordMixTasteTest` and `MigrationV70V71Test`.

## Mixes only your years can make

The web player's history mixes (stash-player `src/lib/spotify/mixes.ts`, `src/lib/ui/spotify/mix.ts`, `MixView.svelte`,
`HomeShelf.svelte`, `routes/spotify/+page.svelte`, spec `docs/spotify-export.md` §4.5) on the phone: your eras, The ones that
stayed, Rediscover, On repeat back then, How it started, each year's most played ("so far" for the year still going), your top
artists' and This week years ago, from the same taste on the same day as the web.

| Where | What |
|---|---|
| `mixes/HistoryMixes.kt` | the pure port, rule for rule: `putBackIn`, `haveIndex`, `eraWhen` (its middle month from the integer middle), `andList`, `artistSongs`, `artistMix`, `firstSongs`, `historyMixes` (Rediscover minus the song keys The ones that stayed plays, unfiltered when that mix isn't made), `weekMix` (the UTC date, `Date.UTC`'s overflow for 29 February, [mid − 3 d, mid + 4 d), years newest first, the first spelling read wins, ties by key), `rotation`, `homePicks` (the local day), `mixHeading`, `mixLine`, `aboutLength` (`Math.round` half up) and the Mixes tab's `card` (an artist's mix falls through to "Lost favourites", never shown) |
| `mixes/MixWords.kt` | the words, from string resources whose English is the web's character for character (’ · –); the week's day of the year in the language's own form ("October 28" in English whatever the region, "28 октября", "10月28日"). Dates, digits and plurals follow the language the strings are in (`record_mix_locale`), never a device language the app has no strings for: on an Italian or Bengali phone the English falls back whole |
| `mixes/RecordMixes.kt` | the mixes now: the stored taste read once per `computed_at` (off the main thread), as the record shows it (kept out = stored avoid minus the songs put back minus the library's songs now); `have` = the library here (liked songs, then the own playlists' songs), so a mix's song you have is your row, read once per library stamp (core:data's `libraryStamp`: the likes and the songs of your own and Liked Songs playlists, never Daily or Stash Mixes, which a sync rewrites); this week's plays read once per taste and UTC day |
| `mixes/RecordMixesShelf.kt` | Home's "From your record": 3 cards from `homePicks(local day)`, their mosaics (`MixArt.kt`: your rows' covers, else the record's lookup for the first 4 albums), made again when the taste or the day changes; nothing without picks; Show all opens the record on Mixes |
| `mixes/RecordMixScreen.kt`, `RecordMixViewModel.kt` | a mix's page (`RecordMixRoute(id)`, a normal screen with the mini player): the mosaic, heading, line, "N songs · about X", Play, Shuffle, Save as playlist (`RecordMixSaver`: a CUSTOM playlist of the rows found, each once, never kept offline, so nothing downloads; which playlist each mix went to is kept with a hash of its song keys, `RecordMixSaves`, so coming back shows Open while those songs are the mix and the playlist is in the library, and Save again once the songs change, as This week, years ago and the year so far do), "That mix isn’t in your record any more" when the taste no longer makes it, a line and Try again when the record can't be read, the way back always; every row's cover looked up as the page opens |
| `mixes/RecordMixesTab.kt`, `RecordTabs.kt` | the record's "The record \| Mixes" (the tab picked last kept on this phone, Home's Show all opens Mixes): Mixes from your years (this week, the eras oldest first, the single mixes) and Your years, the web's cards over their albums' covers; with no import, what they need and Bring your listening. The source switches show on the record's tab only; the WebView is kept, hidden, while Mixes shows |
| `mixes/HistoryMixes.kt` (5d), `RecordMixesTab.kt` | the rest of the Mixes tab, under Your years (routes/spotify/+page.svelte): Top artists (`topArtists`: the first 10 by time listened, their pictures from the record's artist lookup, "412 hours · 9,021 plays"; a row opens the artist's mix, or with fewer than 8 songs for one plays their songs, `artistSongs`), Top songs (years.ts `topSongs`, 10) and "Not in your Stash yet" (years.ts `notInLibrary`: the taste's lost favourites minus every song of the library by its words, the song's own colours and a play mark on each square as on the web, 50 at a time with Show more, a Play for them all). Their plays are "Playing from" Your record |
| `mixes/RecordSongRow.kt`, `RecordSongActions.kt` | every song row of the Mixes tab and of a mix's page (TrackRow.svelte): a tap plays from it, the heart likes it, a long press opens the app's song menu (core:ui `TrackOptionsSheet`, as History's rows open it: Play next, Add to queue, Start radio, Save to playlist). The song is often only words, so each action finds or makes its row first, as a Search result's is kept (`RecordMatch`: your copy, the same song, else a stream-only row with the cover found). Like is Stash's Liked Songs alone (`StashLikedPlaylistRepository`: never Spotify or YouTube Music, nothing downloads unless Liked Songs itself is set to). A liked row leaves "Not in your Stash yet" at once (it is in the library now, so kept, never kept out); a heart fills at once and stays until the likes agree (`RowLikes`), and goes back if the like couldn't be written |
| `bridge/RecordMatch.kt`, `RecordPlayback.kt` | a mix's songs as this phone's rows: your own row by its id, the rest through the hand-off's matcher with the history's Spotify id; a row with no cover takes the one found, on the queue at once and kept on the row (no blank queue art, the web's 10-24 bug): every cover the phone already knows before matching (`RecordArt.cached`, no network), the rest looked up once the queue is set and handed to core:media's `PlayerRepository.learnArtwork` (the queue's rows, the player's items, the playing one and the media session included). Shuffle as a playlist's (the queue shuffled); offline, only the songs with a file here |

Home's section is `HomeSection.RECORD_MIXES` ("record_mixes"): a saved order without it gets it right before the first catalog
(New releases, Qobuz playlists, Top albums), as the web's shelf sits; hidden and moved like the others.

Adapted on purpose:
- **This week, years ago reads the record as the record stacks it**: `record_plays` by `s` (the v71 index) plus this phone's own
  plays and skips (`OwnArchive`), the Last.fm scrobbles they cover left out, each id once (the export's row first), sorted by start
  then id in Kotlin (IndexedDB's order on the web, never SQLite's on text). The web reads its archive alone, which holds this
  phone's plays only when the phone's export was brought there.
- Shuffle shuffles the queue (the app's own Shuffle), where the web turns the player's shuffle on.
- A history song's row shows no length (as the web's row): the record's own row (`RecordSongRow`, laid out as core:ui's
  `DetailTrackRow`) shows one only for your own copy.
- The Mixes tab without an import offers Spotify and Last.fm (the web's line also offers "your phone's history from the Stash app").
- **A heart is a Stash like** (`stash_liked_at`, the heart Now Playing shows; `WebLibraryExportDao.stashLikes`), where the web's is
  any like in its library: a song only in a Liked Songs playlist synced from Spotify shows an empty heart, and a tap likes it in
  Stash, so a tap is never a dead one (an unlike here couldn't take it off Spotify). "Not in your Stash yet" still leaves out the
  whole library, as the web's does.
- The song menu is the app's: no Post to Community (it would post a song that may have no row yet), no Share (its links need ids a
  history song lacks), no Download (nothing of the record downloads unasked), no Go to artist or album (the web's needs a source's
  ids). The web's Like is a row of its menu too; here it is the heart alone.
- A Top artists row that plays shows no mark, one that opens shows a faint chevron (the web's on a phone, where there is no hover).
- **A song is also a library row by its Spotify id** (`LibrarySong.spotifyId`, from `spotify_uri`): a Like by the history's id can
  land on a row a Spotify sync made under the catalog's credit ("Sia, Sean Paul" where the history says "Sia"), and that row is the
  song for its heart (`Likes`), "Not in your Stash yet", an unlike (every Stash-liked row of the song goes: by words, ISRC copy or
  id), and #715's "kept" at read time (the mixes' put-back set, `RoomRecordMixTaste` via `MixRow.sid`, the Kept-out list) and in
  the next taste (`TasteOptions.keptSpotifyIds`, empty on the web's vectors). The web's library has no such rows.
- **Blocked songs** (BlocklistGuard's rule: the words' canonical key, or the Spotify id) are never in Top songs or "Not in your
  Stash yet", and Like and the menu refuse one before any row is made, as Search does ("Can't add a blocked track").
- An artist's picture is the Worker's `pickArtist`: of the hits whose folded name (NFKD, marks removed, lower case, non-letters to
  one space, trimmed; "The" kept) is the name asked (the first credit, cut at `,` `;` feat. ft. featuring with, never `&`), the one
  with the most `nb_fan`; Deezer's placeholder or a picture this app won't load is none, never a namesake's (key `ar3:`). The app
  takes only https covers on Deezer's and Apple's hosts, a narrower check than the web's https alone.

### Its tests

- `HistoryMixesVectorsTest` (Robolectric, the English from the app's strings): cases run through the web's own code by
  `scripts/mix-vectors.ts` (`src/test/resources/mixes/`; the run is in its header): `historyMixes` over the golden fixture's taste
  (the export through the web's own importer) and crafted tastes (a year "so far", eras across years and early, mid, late, one
  month; Rediscover minus The ones that stayed and not; `firstSongs` ties; artists of 7 and 8 songs; put back and liked songs; the
  library's copies by key, by words, by ISRC), with headings, lines, lengths and cards; `weekMix` (29 February, the ±3 days' edges,
  one year and several, spellings, skips, avoided songs, the 50 cut, too few, the fixture's archive on 5 days); `rotation` and
  `homePicks` for every day 0..n+1 and far ones; `eraWhen` on a month's edge, `andList`, `aboutLength`; and the mix page's, the
  shelf's and the Mixes tab's words against the web's files. Run it again after a change to the web's rules.
- `HistoryMixesVectorsTest`'s lists (`lists.json`, the same script): Top artists over the fixture and crafted tastes (8 songs open
  and 7 play, a song put back or liked making the 8th, songs never listened to, a guest credit, an artist with no songs, the first
  10 only, the hours and plays as the page says them), Top songs (ties by key, an avoided song in, a song only started out) and "Not
  in your Stash yet" over tastes the web's `computeTaste` works out (library copies by words, ISRC, the second of two ISRC twins,
  guest credits; a remaster that stays; kept, hidden, skipped, put back and liked songs; past the page of 50), with their lines.
  The page's own <script> lines (the 10s, the 50, the library, the play-or-open rule) are ported in the script, which checks them
  against the file.
- `HistoryMixesVectorsTest`'s pictures (`artists.json`: 38 cases through the Worker's own `artistSearch` and `pickArtist`: Queen,
  Sia, Muse, Joji, boygenius, Low, The Band, Bush, ties, fans that aren't numbers, accents both ways, Mø, "The", near names, the
  placeholder, http). The lists now run the web's own `mixesLists`, `lostSongs`, `artistLine` and `libraryOf` (ui/spotify/mix.ts).
- `RecordSongActionsTest` (Like makes or finds the row then likes that row and nothing else; a failure says so; the menu's actions
  on the row; the hearts at once, kept until the likes agree, back on a failure) and `RecordLikeRoomTest` (on a real database: a
  lost song liked gets a stream-only row with its Spotify id and cover, joins Stash's Liked Songs, leaves the list, its heart
  fills, nothing queued to download, nothing saved to Spotify; Unlike brings it back; a song with no id liked twice is one row).
- `MixWordsLocalesTest` (every word in every language, plural forms included, a device language with no strings writing the
  English whole), `RecordMixesTest` (the taste read once per `computed_at`, kept out as the record shows it, the week stacked and
  read once a day, the local day, the library read once per stamp), `RecordMatchTest` (the
  wire's Spotify id and cover, your own row, covers on the queue and kept, the covers known before matching and the rest learnt
  after the queue is set, Shuffle, offline, Save as playlist), `MixArtTest` (Home's tile, the Mixes tab's card and what is asked
  for, against Tile.svelte, `coversOf` and `withCovers`), `RecordMixViewModelTest` (a read that fails; a mix saved before opens its playlist while its songs are the same, offers Save once they change, and an id-only entry reads as unknown),
  core:media's `QueueArtTest` and core:data's `HomeSectionOrderTest`.
- On a phone: a debug build writes what the mixes were made from and came to (`files/record-mixes.json`); `scripts/mix-device-check.ts`
  runs the web's `historyMixes`, `weekMix` and `homePicks` over it and compares the picks and every mix (the run is in its header).
