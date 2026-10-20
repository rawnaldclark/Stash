# Cloud storage libraries (issue #247)

Owner decision 2026-10-19: people who already keep music in their own cloud storage can stream it in Stash and download it for offline, in the app and the web player. Both stream and download. Start with OneDrive in the app; then Dropbox; Google Drive only if the folder-picker test passes (below). Web player follows with the same providers.

## Ground rules

- The user's own files only. The audio goes straight from their cloud to their phone or browser. No Stash server ever fetches, proxies or stores audio or the user's cloud tokens (see the legal-risk notes: a dev-run machine moving audio is what got Echo Music taken down).
- No "save into your cloud" via a server. A later, optional client-side "upload my downloads to my cloud" is fine; not in this slice.
- Cloud songs are never sent to the lossless relays, never matched or swapped for an online version, never scrobbled differently from local files.
- A build without a client id (plain checkout, CI) has the feature hidden, like the Last.fm keys.

## What other players taught us (research 2026-10-19)

- Siku (OneDrive, open source): placeholder `onedrive://<id>` URIs resolved to `@microsoft.graph.downloadUrl` when ExoPlayer opens them, cached ~5 min because every seek reopens the source; tags from a 256 KB range read (93% of a 765-file FLAC library), a second exact range for a big embedded cover; `/delta` with a saved token; ~5 requests/s throttle with playback first; honour 429 Retry-After.
- Tempus: cache key = provider + file id, never the URL (tokens and links change). Separate stream cache (evicting) and download store (never evicted).
- CloudAmp: Drive sends no Content-Length on some answers; set the length from the file's metadata size or seeking breaks (Android Auto too).
- Symfonium: first scans of big libraries are slow and data-heavy; make the scan resumable and say Wi-Fi is best.

## Provider facts that shape the design

- OneDrive (Graph): `Files.Read` + `offline_access`, no admin consent, personal and work accounts, free app registration. `/content` 302s; use `@microsoft.graph.downloadUrl` (pre-authenticated, Range → 206, may expire within minutes). `delta` per folder; a 410 means rescan. Throttling: honour Retry-After.
- Dropbox: PKCE + `token_access_type=offline`; `get_temporary_link` (4 h); `list_folder` + cursor. 500-user cap until production approval (review opens at 50 linked users, 2 weeks to pass).
- Google Drive: `drive.readonly` is a restricted scope (yearly CASA audit, 100-user cap until verified). `drive.file` + Picker with folder selection is non-sensitive, but it is undocumented whether a picked folder grants its children and files added later. TEST BEFORE BUILDING: pick a folder, then `files.list q="'<folderId>' in parents"`, then add a file to the folder in Drive and list again. 1 TB/day download allowance per project, shared by every user.

## App design (OneDrive first)

1. **Sign-in:** AppAuth (already in libs) with PKCE against `https://login.microsoftonline.com/common/oauth2/v2.0/{authorize,token}`, scopes `Files.Read offline_access User.Read`, redirect `${applicationId}://oauth/onedrive` (so debug and release both work). Client id from `local.properties` `ONEDRIVE_CLIENT_ID` → `BuildConfig.ONEDRIVE_CLIENT_ID`; empty = hidden. Tokens in the Tink-encrypted token store, single-flight refresh. Read-only scope: Stash never writes to the drive in this slice.
2. **Folders:** after sign-in the user browses their drive and picks one or more music folders (default suggestion: a folder named Music). Stored per account.
3. **Scan (WorkManager, resumable):** `delta` on each picked folder; audio by extension and mime (flac, mp3, m4a/alac/aac, opus, ogg, wav); tags from a ranged read of the head (ID3v2, FLAC metadata blocks incl. PICTURE, MP4 `moov` may be at the end: allow a tail read), cover from embedded art or a sibling cover/folder/front image; fallback title from the filename and artist/album from the folder path. Remember item id, eTag/cTag, size, path. Next scans use the saved delta link; changed items re-read, deleted items removed. Throttle ~5 req/s, Retry-After on 429/503, playback requests jump the queue. Network rule follows the existing download setting (Wi-Fi by default).
4. **Library:** cloud songs are real library tracks (like, add to playlist, queue, download, share), with a "Cloud" filter/badge and their account named. Not candidates for online matching, lossless lookups or relays.
5. **Playback:** `stash-cloud://onedrive/<accountId>/<itemId>` handled in `StashMediaSourceFactory` by a resolving `DataSource`: fetch the item's `@microsoft.graph.downloadUrl` (cache ~5 min per item), open with Range, set length from the item size; on 401/403/404/410 refresh the token and/or link and retry once at the same position. Stream cache keyed `onedrive:<itemId>:<cTag>`.
6. **Download:** through the existing download pipeline, from the resolved link; the file lands where other downloads land and the track becomes downloaded (plays from the file after that).
7. **Settings:** Accounts › OneDrive (connect, disconnect = forget tokens and remove its cloud tracks unless downloaded, after a confirm); Library & Storage › Cloud folders (add/remove folders, Rescan now, last scan status).
8. **Disclosure:** PRIVACY.md gets a OneDrive entry (Graph + its download host, what is sent, read-only), README one line.

## Web player (after the app slice)

Built into the player, not a remote plugin (the plugin contract sends no credentials). Client-side PKCE, tokens and the index in IndexedDB, tags via `music-metadata` with a range tokenizer in a Web Worker, `<audio src=downloadUrl>` re-resolved before expiry, same `cloud:<provider>:<itemId>` refs. Reference implementation: Diffuse (icidasset/diffuse) for Dropbox/S3/WebDAV; Diffuse 3.x service worker for Drive's Bearer header.

## Not in this slice

Dropbox, Google Drive, WebDAV/SMB/S3/Subsonic, upload to cloud, web player.
