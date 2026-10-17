package com.stash.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** All Songs ids stay out of every other id space, and a scanned file's format comes from its URI. */
class ScannedIdsTest {

    @Test fun `scanned ids sit below every library, radio and search id`() {
        assertThat(isScannedTrackId(scannedTrackId(1L))).isTrue()
        assertThat(isScannedTrackId(scannedTrackId(Int.MAX_VALUE.toLong() * 4))).isTrue()
        // Library rows, a radio/search videoId.hashCode() of either sign, the "no id" 0.
        assertThat(isScannedTrackId(42L)).isFalse()
        assertThat(isScannedTrackId(0L)).isFalse()
        assertThat(isScannedTrackId(Int.MIN_VALUE.toLong())).isFalse()
        assertThat(isScannedTrackId(Int.MAX_VALUE.toLong())).isFalse()
        assertThat(Track(id = scannedTrackId(7L), title = "t", artist = "a").isScannedSong).isTrue()
        assertThat(Track(id = 7L, title = "t", artist = "a").isScannedSong).isFalse()
    }

    @Test fun `distinct rows get distinct ids`() {
        assertThat(scannedTrackId(1L)).isNotEqualTo(scannedTrackId(2L))
    }

    @Test fun `the extension comes from the encoded document id`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/"
        assertThat(scannedFileExtension(tree + "primary%3AMusic%2FAlbum%2F01%20Song.FLAC")).isEqualTo("flac")
        assertThat(scannedFileExtension(tree + "primary%3AMusic%2Fa.b.opus")).isEqualTo("opus")
        assertThat(scannedFileExtension(tree + "primary%3AMusic%2FA%2B%20B.m4a")).isEqualTo("m4a")
    }

    @Test fun `an opaque document id or a dotted folder carries no extension`() {
        assertThat(scannedFileExtension("content://com.android.providers.downloads.documents/tree/downloads/document/msf%3A1234")).isNull()
        assertThat(scannedFileExtension("content://x/tree/primary%3AMy.Music/document/primary%3AMy.Music%2Fsong")).isNull()
    }
}
