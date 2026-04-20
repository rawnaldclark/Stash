package com.stash.data.ytmusic.model

/**
 * YouTube Music's authoritative classification for a video, extracted from
 * InnerTube's `watchEndpointMusicConfig.musicVideoType` field.
 *
 * This is the structured replacement for the brittle string-matching
 * heuristic (`resultType == "Song"`) the matcher currently uses. The
 * same enum is surfaced both on search results
 * ([com.stash.data.download.ytdlp.YtDlpSearchResult]) and on library/
 * playlist imports ([YTMusicTrack]) so downstream scoring and Mode-B
 * canonicalization can make decisions without re-walking JSON.
 *
 * Mapping from InnerTube's `MUSIC_VIDEO_TYPE_*` strings to enum values
 * lives in [fromInnerTube]. Unknown or missing values are returned as
 * `null` — callers should treat `null` as "type not verifiable" rather
 * than silently falling back to a default.
 */
enum class MusicVideoType {
    /**
     * Audio-Topic-channel song. YouTube's auto-generated Topic channels
     * mirror the label's studio master; this is the highest-confidence
     * "correct version" signal we have for popular music.
     */
    ATV,

    /**
     * Official Music Video. The official video upload (VEVO, artist
     * channel). Audio often differs from the album master — intros,
     * outros, alternate mixes — so the matcher should prefer an ATV
     * equivalent when one exists.
     */
    OMV,

    /**
     * User-Generated Content. Fan uploads, lyric videos, live
     * recordings, reaction videos. Almost always the wrong answer for
     * "give me the studio version"; hard-reject unless no better
     * candidate is available.
     */
    UGC,

    /**
     * Official-channel upload that isn't a Topic auto-generation. Seen
     * on label channels and some artist-operated channels. Treated as
     * high-trust but below ATV, since the master can still differ.
     */
    OFFICIAL_SOURCE_MUSIC,

    /**
     * Podcast episode. YouTube Music returns these for music-adjacent
     * search queries; always exclude from music matching.
     */
    PODCAST_EPISODE;

    companion object {
        /**
         * Parses InnerTube's `musicVideoType` string into this enum.
         * Returns `null` for missing / blank / unrecognised values —
         * callers decide whether an unknown type blocks or demotes.
         */
        fun fromInnerTube(raw: String?): MusicVideoType? = when (raw) {
            "MUSIC_VIDEO_TYPE_ATV" -> ATV
            "MUSIC_VIDEO_TYPE_OMV" -> OMV
            "MUSIC_VIDEO_TYPE_UGC" -> UGC
            "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC" -> OFFICIAL_SOURCE_MUSIC
            "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" -> PODCAST_EPISODE
            else -> null
        }
    }
}
