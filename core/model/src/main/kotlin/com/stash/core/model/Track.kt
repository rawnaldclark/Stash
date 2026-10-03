package com.stash.core.model

data class Track(
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val filePath: String? = null,
    val fileFormat: String = "opus",
    val qualityKbps: Int = 0,
    val fileSizeBytes: Long = 0,
    val source: MusicSource = MusicSource.SPOTIFY,
    val spotifyUri: String? = null,
    val youtubeId: String? = null,
    val albumArtUrl: String? = null,
    val albumArtPath: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
    val isDownloaded: Boolean = false,
    val matchConfidence: Float = 0f,
    val matchDismissed: Boolean = false,
    /**
     * International Standard Recording Code — Spotify's per-master unique
     * identifier. Null for YouTube-sourced tracks and for legacy Spotify
     * rows inserted before the matcher started requesting it. Used as the
     * highest-precision signal when matching to canonical YouTube uploads.
     */
    val isrc: String? = null,
    /**
     * Spotify's parental-advisory flag. Null for YouTube-sourced tracks and
     * legacy rows; true/false for Spotify rows synced post-v12. Matcher
     * prefers candidates whose explicitness matches the source.
     */
    val explicit: Boolean? = null,
    /**
     * Bit-depth of the on-disk audio (16, 24, 32). NULL when unknown
     * (lossy codecs, legacy rows pre-backfill, unparseable files).
     */
    val bitsPerSample: Int? = null,
    /**
     * Audio sample rate in Hz (44100, 48000, 96000, 192000). NULL when unknown.
     */
    val sampleRateHz: Int? = null,
    /**
     * v0.9.13 — see TrackEntity.spotifySavedAt KDoc.
     */
    val spotifySavedAt: Long? = null,
    /**
     * When Stash loved this track on Last.fm. NULL = Stash never did, which is
     * what stops an un-heart from deleting a love the user made independently.
     * See TrackEntity.lastFmLovedAt.
     */
    val lastFmLovedAt: Long? = null,
    val ytMusicSavedAt: Long? = null,
    val stashLikedAt: Long? = null,
    /**
     * v0.9.27: cached Kennyy-proxy resolvability. True = the streaming
     * engine can play this track without a downloaded file. Defaults to
     * false so legacy rows surface as "not yet known streamable" until
     * `AvailabilityCheckWorker` drains the backlog. See
     * `TrackEntity.isStreamable`.
     */
    val isStreamable: Boolean = false,
    /**
     * v0.9.27: epoch-millis of the streamability lookup (success OR
     * failure). NULL = never checked; tristate sentinel that pairs with
     * [isStreamable] to distinguish "unavailable on Kennyy" from "we
     * haven't asked yet." Drives the library row's greyed-out treatment
     * — only checked-and-unavailable rows go grey. See
     * `TrackEntity.isStreamableCheckedAt`.
     */
    val isStreamableCheckedAt: Long? = null,
    /**
     * Resolver origin that produced the *currently active* stream URL
     * for this track, when it's playing. Set on the in-memory Track
     * reconstructed by `PlayerRepositoryImpl.MediaItem.toTrack` from
     * the playing `MediaItem`'s extras. Never persisted to Room.
     *
     * Conventional values: `"kennyy"` / `"squid"` (Qobuz lossless) or
     * `"youtube"` (yt-dlp / InnerTube extraction, lossy). NULL when
     * playback is from a downloaded file or no extras were attached.
     *
     * Used by `NowPlayingScreen` to render a "via YT" suffix on the
     * format chip when the streaming fallback kicks in.
     */
    val streamOrigin: String? = null,
    /**
     * Album-level artist for grouping multi-artist releases in Plex /
     * Foobar / Symfonium. Distinct from [artist] (the track-level
     * credit) — a feature credit may say `artist = "Drake, 21 Savage"`
     * while `albumArtist = "Drake"`. Empty string when unknown.
     * Mirror of `TrackEntity.album_artist`, added v0.9.26 but only
     * surfaced through the domain layer in v0.9.35.
     */
    val albumArtist: String = "",
    /**
     * Epoch-millis of the most recent successful tag + art embedding
     * pass. NULL = never tagged (legacy v0.9.34 rows, or rows whose
     * embed step failed); 0L = backfill tried and failed
     * unrecoverably (file missing, ffmpeg error). Non-null non-zero
     * = the file on disk carries the v0.9.35 tag set.
     */
    val metadataEmbeddedAt: Long? = null,
    /**
     * Epoch-millis of the most recent lyrics fetch attempt that produced a result.
     * NULL = never tried; 0L = tried, none available; non-zero = success epoch-millis.
     * Mirrors `tracks.lyrics_fetched_at` (v27 → v28 migration).
     */
    val lyricsFetchedAt: Long? = null,
    /**
     * When the user picked this track's audio themselves (Failed Matches,
     * #531). Mirrors `tracks.match_picked_at`. While set, nothing automatic
     * replaces the audio with a lossless or "canonical" match: a re-download
     * fetches the picked video.
     */
    val matchPickedAt: Long? = null,
)
