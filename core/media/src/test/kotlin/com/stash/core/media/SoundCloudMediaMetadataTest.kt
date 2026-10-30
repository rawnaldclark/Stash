package com.stash.core.media

import android.os.Bundle
import com.stash.core.model.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SoundCloudMediaMetadataTest {
    private val track = Track(id = 7, title = "Remix", artist = "Artist", soundCloudId = "123",
        soundCloudPermalink = "https://soundcloud.com/artist/remix", soundCloudPreview = true)

    @Test fun `media metadata carries upload identity and preview state`() {
        val extras = Bundle().apply { putSoundCloudMetadata(track) }
        assertEquals("123", extras.getString(EXTRA_SOUNDCLOUD_ID))
        assertTrue(extras.getBoolean(EXTRA_SOUNDCLOUD_PREVIEW))
    }

    @Test fun `full local copy is not mislabeled as the remote preview`() {
        val extras = Bundle().apply { putSoundCloudMetadata(track.copy(isDownloaded = true)) }
        assertFalse(extras.getBoolean(EXTRA_SOUNDCLOUD_PREVIEW))
        assertTrue(extras.getBoolean(EXTRA_SOUNDCLOUD_LOCAL))
    }
}
