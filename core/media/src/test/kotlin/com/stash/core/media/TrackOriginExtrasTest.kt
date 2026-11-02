package com.stash.core.media

import android.os.Bundle
import com.stash.core.model.MusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Track Now Playing rebuilds from a MediaItem keeps the song's real origin
 * ([putTrackOrigin] on every item, read back by [rebuiltTrackSource]). It used to call every
 * non-cloud, non-SoundCloud item with a row id a Spotify song, and a like or a listen then saved
 * a YouTube stream as a green Spotify row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackOriginExtrasTest {

    private fun extras(source: MusicSource, spotifyUri: String? = null) =
        Bundle().apply { putTrackOrigin(source, spotifyUri) }

    @Test fun `a YouTube stream rebuilt from its item is a YouTube song`() {
        assertEquals(MusicSource.YOUTUBE, rebuiltTrackSource(extras(MusicSource.YOUTUBE), "Ikn6Z2fxMs4"))
        // A radio or artist-page item carrying the default (Spotify) with only a video id.
        assertEquals(MusicSource.YOUTUBE, rebuiltTrackSource(extras(MusicSource.SPOTIFY), "Ikn6Z2fxMs4"))
    }

    @Test fun `a Spotify song downloaded under a video stays a Spotify song`() {
        val item = extras(MusicSource.SPOTIFY, spotifyUri = "spotify:track:1")
        assertEquals("spotify:track:1", item.getString(EXTRA_TRACK_SPOTIFY_URI))
        assertEquals(MusicSource.SPOTIFY, rebuiltTrackSource(item, "Ikn6Z2fxMs4"))
    }

    @Test fun `a local file, a cloud song and a SoundCloud upload keep their own origin`() {
        assertEquals(MusicSource.LOCAL, rebuiltTrackSource(extras(MusicSource.LOCAL), null))
        assertEquals(MusicSource.CLOUD, rebuiltTrackSource(extras(MusicSource.CLOUD), null))
        val soundCloud = extras(MusicSource.SOUNDCLOUD).apply { putString(EXTRA_SOUNDCLOUD_ID, "123") }
        assertEquals(MusicSource.SOUNDCLOUD, rebuiltTrackSource(soundCloud, null))
    }

    @Test fun `an item from before this build (no origin) is Spotify's only with a Spotify id`() {
        assertEquals(MusicSource.YOUTUBE, rebuiltTrackSource(Bundle(), "Ikn6Z2fxMs4"))
        assertEquals(MusicSource.YOUTUBE, rebuiltTrackSource(null, "Ikn6Z2fxMs4"))
        assertEquals(MusicSource.SPOTIFY, rebuiltTrackSource(Bundle().apply { putString(EXTRA_TRACK_SPOTIFY_URI, "spotify:track:1") }, null))
    }

    @Test fun `a blank Spotify id is not carried`() {
        assertFalse(extras(MusicSource.SPOTIFY, spotifyUri = " ").containsKey(EXTRA_TRACK_SPOTIFY_URI))
    }
}
