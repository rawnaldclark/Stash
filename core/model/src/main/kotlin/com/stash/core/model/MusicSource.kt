package com.stash.core.model

enum class MusicSource {
    SPOTIFY,
    YOUTUBE,

    /**
     * User-imported audio files from the local device (files picked via
     * SAF picker or shared into Stash). These are not tied to any
     * streaming service — `spotifyUri` and `youtubeId` are both null.
     * Files live in the same managed library location as downloaded
     * synced tracks (internal or SAF target, per storage preference).
     */
    LOCAL,

    BOTH,

    /**
     * A song in the user's own cloud storage (OneDrive first; spec
     * 2026-10-19-cloud-storage-design.md). It streams straight from the
     * user's cloud through a `stash-cloud://` URI ([Track.cloudUri]) until
     * downloaded, then plays from its file like any other download.
     *
     * Never a candidate for online matching, lossless lookups, the relays or
     * the YouTube/yt-dlp download path: it has no Spotify/YouTube ids and no
     * canonical words, and every by-source and by-quality sweep leaves
     * `'CLOUD'` out. The cloud bookkeeping (account, item id, cTag) lives in
     * `cloud_items`, keyed by the track id.
     */
    CLOUD,
}
