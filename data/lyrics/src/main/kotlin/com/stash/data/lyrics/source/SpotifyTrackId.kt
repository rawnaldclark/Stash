package com.stash.data.lyrics.source

/** Normalises a stored Spotify reference (bare id, `spotify:track:` URI or open.spotify.com URL) to the 22-char id. */
object SpotifyTrackId {
    private val BARE = Regex("^[A-Za-z0-9]{22}$")
    private val URI = Regex("^spotify:track:([A-Za-z0-9]{22})$")
    private val URL = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?track/([A-Za-z0-9]{22})""")

    fun parse(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (BARE.matches(s)) return s
        return URI.find(s)?.groupValues?.get(1) ?: URL.find(s)?.groupValues?.get(1)
    }
}
