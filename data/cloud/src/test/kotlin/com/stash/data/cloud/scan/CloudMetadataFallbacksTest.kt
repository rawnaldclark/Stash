package com.stash.data.cloud.scan

import com.google.common.truth.Truth.assertThat
import com.stash.data.cloud.tags.AudioTags
import org.junit.Test

class CloudMetadataFallbacksTest {

    private fun resolve(file: String, vararg folders: String, tags: AudioTags = AudioTags.EMPTY) =
        CloudMetadataFallbacks.resolve(tags, file, folders.toList())

    @Test
    fun `tags win over the path`() {
        val r = resolve("01 x.flac", "Other", "Folder", tags = AudioTags(title = "T", artist = "A", album = "B", albumArtist = "AA"))
        assertThat(r).isEqualTo(ResolvedNames("T", "A", "B", "AA"))
    }

    @Test
    fun `untagged file under Artist slash Album`() {
        val r = resolve("03 - Karma Police.mp3", "Radiohead", "OK Computer")
        assertThat(r.title).isEqualTo("Karma Police")
        assertThat(r.album).isEqualTo("OK Computer")
        assertThat(r.artist).isEqualTo("Radiohead")
        assertThat(r.albumArtist).isEqualTo("Radiohead")
    }

    @Test
    fun `disc folders are skipped`() {
        val r = resolve("1-02 Song.flac", "Pink Floyd", "The Wall", "CD2")
        assertThat(r.album).isEqualTo("The Wall")
        assertThat(r.artist).isEqualTo("Pink Floyd")
        assertThat(r.title).isEqualTo("Song")
    }

    @Test
    fun `file directly in the picked folder gets no folder album or artist`() {
        val r = resolve("Track.m4a")
        assertThat(r.title).isEqualTo("Track")
        assertThat(r.album).isEmpty()
        assertThat(r.artist).isEqualTo(CloudMetadataFallbacks.UNKNOWN_ARTIST)
    }

    @Test
    fun `Artist - Title file names split when nothing else names the artist`() {
        val r = resolve("Daft Punk - One More Time.mp3")
        assertThat(r.artist).isEqualTo("Daft Punk")
        assertThat(r.title).isEqualTo("One More Time")
    }

    @Test
    fun `Artist - Title split is not trusted against a different folder artist`() {
        val r = resolve("Intro - Reprise.mp3", "Some Band", "Live")
        assertThat(r.artist).isEqualTo("Some Band")
        assertThat(r.title).isEqualTo("Intro - Reprise")
    }

    @Test
    fun `album folder only, artist from tags`() {
        val r = resolve("song.ogg", "Mixtape", tags = AudioTags(artist = "X"))
        assertThat(r.album).isEqualTo("Mixtape")
        assertThat(r.artist).isEqualTo("X")
        assertThat(r.title).isEqualTo("song")
    }

    @Test
    fun `track number stripping`() {
        val strip = CloudMetadataFallbacks::stripTrackNumber
        assertThat(strip("01 Song")).isEqualTo("Song")
        assertThat(strip("01. Song")).isEqualTo("Song")
        assertThat(strip("12 - Song")).isEqualTo("Song")
        assertThat(strip("1-02 Song")).isEqualTo("Song")
        assertThat(strip("7) Song")).isEqualTo("Song")
        assertThat(strip("1 Thing")).isEqualTo("1 Thing")
        assertThat(strip("2Pac Song")).isEqualTo("2Pac Song")
        assertThat(strip("1999")).isEqualTo("1999")
        assertThat(strip("Song")).isEqualTo("Song")
    }
}
