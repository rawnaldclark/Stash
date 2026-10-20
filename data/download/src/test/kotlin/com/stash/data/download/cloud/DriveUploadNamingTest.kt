package com.stash.data.download.cloud

import com.google.common.truth.Truth.assertThat
import com.stash.data.cloud.upload.UploadPlace
import org.junit.Test

/** Save to Google Drive names songs exactly as the phone's Artist/Album layout does. */
class DriveUploadNamingTest {
    private val naming = DriveUploadNaming()

    @Test fun `artist and album folders with the downloader's slugs`() {
        assertThat(naming.place("Nina Simone", "Pastel Blues", "Sinnerman", "flac"))
            .isEqualTo(UploadPlace(listOf("nina-simone", "pastel-blues"), "sinnerman.flac"))
    }

    @Test fun `no album goes to singles, like on the phone`() {
        assertThat(naming.place("Drake", "", "Hotline Bling", "opus").folders).containsExactly("drake", "singles").inOrder()
    }

    @Test fun `names that slug to nothing get the downloader's placeholders`() {
        val place = naming.place("!!!", "???", "…", "mp3")
        assertThat(place.folders).containsExactly("unknown-artist", "unknown-album").inOrder()
        assertThat(place.fileName).isEqualTo("untitled.mp3")
    }
}
