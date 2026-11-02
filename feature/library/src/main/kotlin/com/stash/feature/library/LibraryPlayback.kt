package com.stash.feature.library

import com.stash.core.model.Track

/**
 * The play rule shared by the artist and album pages and the Library's artist and album cards.
 *
 * What they list follows the Download switch (the repository applies it: every song in Stream
 * only, the downloaded ones in Download). What can PLAY from that list follows the connection,
 * the way a playlist does: online, every song, streaming what isn't downloaded; offline, only
 * songs with a file on the phone. A queue that mixes in songs that can't be reached stops after
 * a few failures, so offline the others are left out rather than queued.
 */
internal fun playableNow(tracks: List<Track>, online: Boolean): List<Track> =
    if (online) tracks else tracks.filter { it.isOnPhone() }

/** The song has a file on the phone, so it plays with no connection. */
internal fun Track.isOnPhone(): Boolean = filePath != null

/** A tap on one song that needs a connection, made offline. */
internal const val SONG_NEEDS_CONNECTION = "No internet connection, and this song isn't downloaded."

/** Play or Shuffle on an artist, offline, when none of their listed songs is downloaded. */
internal const val ARTIST_NEEDS_CONNECTION = "None of this artist's songs are downloaded. Connect to play them."

/** Play or Shuffle on an album, offline, when none of its listed songs is downloaded. */
internal const val ALBUM_NEEDS_CONNECTION = "None of this album's songs are downloaded. Connect to play them."
