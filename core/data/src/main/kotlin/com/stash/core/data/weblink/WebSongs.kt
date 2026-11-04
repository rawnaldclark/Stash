package com.stash.core.data.weblink

import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.isScannedSong

/**
 * One rule for which of this phone's songs the web player can play, and the sync-v1 §2.1 Song it gets for them. The handoff
 * (`HandoffDocsBuilder`), the mirror (`RoomMirrorLibrary`) and a browser playing this phone's queue (Remote v2,
 * `WebCastRemote`) all ask here, so a song is never playable in one and left out in another (spec 2026-11-03 §5).
 *
 * The browser finds a song the way "Continue from Pixel" does: YouTube by its id, lossless by ISRC, the rest by its words. So a
 * song plays on the web unless a file on this phone, or an account the web can't reach, is its only source:
 * - a SoundCloud song ("SoundCloud songs stay on the phone", #744);
 * - a cloud song (the user's own Drive / OneDrive / Dropbox);
 * - an All Songs file (a scan: no library row, never looked up online);
 * - an imported local file, or a download, with none of an ISRC, a Spotify id or a YouTube id.
 * A downloaded song with those ids plays: the browser streams the same song, the file never leaves the phone.
 */
object WebSongs {
    /**
     * [t] as a wire song, marked `phoneOnly` when the web can't play it ([playsOnWeb] false). Null without a title or an artist:
     * the web can neither find nor show such a song.
     */
    fun songOf(t: Track): WireSong? {
        if (t.title.isBlank() || t.artist.isBlank()) return null
        // The library file leaves SoundCloud out altogether, so its Song is built here, with no ids a web source could
        // mistake for another upload (the mirror's bytes for it, unchanged).
        if (t.source == MusicSource.SOUNDCLOUD) {
            return WireSong(t.title, t.artist, album = t.album, durationMs = t.durationMs, isrc = t.isrc, phoneOnly = true)
        }
        val song = WebLibraryFile.song(t, addedAt = null) ?: return null
        // No ISRC, Spotify or YouTube id (what the web can play from), and a file on this phone is its source.
        val noIds = song.isrc == null && song.spotifyId == null && song.refs.isNullOrEmpty()
        val phoneOnly = (noIds && (t.source == MusicSource.LOCAL || t.isDownloaded)) || t.isScannedSong || t.source == MusicSource.CLOUD
        return WireSong.of(song, phoneOnly)
    }

    /** Whether the web player can play [t] (it travels to a browser and isn't marked `phoneOnly`). */
    fun playsOnWeb(t: Track): Boolean = songOf(t)?.phoneOnly == false
}
