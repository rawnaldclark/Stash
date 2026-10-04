package com.stash.data.download.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extension an imported file may be given, from what its provider claims or what its content is. */
class ImportedAudioTypeTest {

    @Test
    fun `every type it can return can end a library file name`() {
        ImportedAudioType.EXTENSIONS.forEach { assertTrue(it, FileOrganizer.isSafeFormat(it)) }
    }

    @Test
    fun `audio display names give their extension in lower case`() {
        assertEquals("mp3", ImportedAudioType.forDisplayName("Radiohead - Idioteque.mp3"))
        assertEquals("mp3", ImportedAudioType.forDisplayName("SONG.MP3"))
        assertEquals("flac", ImportedAudioType.forDisplayName("a.b.c.FLAC"))
        assertEquals("opus", ImportedAudioType.forDisplayName("voice.opus"))
        assertEquals("oga", ImportedAudioType.forDisplayName("x.oga"))
        assertEquals("mka", ImportedAudioType.forDisplayName("song.mka"))
        assertEquals("amr", ImportedAudioType.forDisplayName("memo.AMR"))
        assertEquals("awb", ImportedAudioType.forDisplayName("memo.awb"))
    }

    @Test
    fun `anything else in a display name gives nothing`() {
        listOf(
            null,
            "",
            "no extension",
            "notes.html",
            "song.apk",
            "song.mp3/../x",
            "a.mp3/b",
            "a.mp3\\b",
            "../..",
            "..",
            ".",
            "song.",
            "song.mp3 ",
            " song.mp3.",
            "song.mp3\u0000",
            "song.mp3\u0000.png",
            "song.ｍｐ３",
            "song.mp３",
            "song.ＭＰ３",
            "song." + "a".repeat(300),
            "song.mp3/",
            "song.wma",
            "song.ac3",
            "clip.mkv",
            "clip.3gp",
        ).forEach { assertNull("'$it'", ImportedAudioType.forDisplayName(it)) }
    }

    @Test
    fun `audio MIME types give their extension`() {
        assertEquals("mp3", ImportedAudioType.forMime("audio/mpeg"))
        assertEquals("m4a", ImportedAudioType.forMime("audio/mp4"))
        assertEquals("m4a", ImportedAudioType.forMime("AUDIO/X-M4A"))
        assertEquals("flac", ImportedAudioType.forMime("audio/flac; charset=binary"))
        assertEquals("ogg", ImportedAudioType.forMime("application/ogg"))
        assertEquals("wav", ImportedAudioType.forMime("audio/x-wav"))
        assertEquals("webm", ImportedAudioType.forMime("audio/webm"))
        assertEquals("mka", ImportedAudioType.forMime("audio/x-matroska"))
        assertEquals("amr", ImportedAudioType.forMime("audio/amr"))
        assertEquals("awb", ImportedAudioType.forMime("AUDIO/AMR-WB"))
    }

    @Test
    fun `other MIME types give nothing`() {
        listOf(
            null, "", "audio/*", "audio", "text/html", "application/octet-stream",
            "application/vnd.android.package-archive", "video/mp4", "audio/x-ms-wma",
            "audio/mpeg/../x", "audio/flac/x",
            "video/x-matroska", "video/webm", "audio/3gpp", "video/3gpp", "audio/ac3", "audio/amr-wb+",
        ).forEach { assertNull("'$it'", ImportedAudioType.forMime(it)) }
    }

    @Test
    fun `an audio MIME type wins over the display name, and either one is enough`() {
        assertEquals("mp3", ImportedAudioType.fromClaims("audio/mpeg", "song.mp3/../x"))
        assertEquals("flac", ImportedAudioType.fromClaims("audio/flac", "song.mp3"))
        assertEquals("mp3", ImportedAudioType.fromClaims("application/octet-stream", "song.mp3"))
        assertEquals("mp3", ImportedAudioType.fromClaims(null, "song.mp3"))
        assertNull(ImportedAudioType.fromClaims("text/html", "notes.html"))
        assertNull(ImportedAudioType.fromClaims(null, null))
    }

    @Test
    fun `a provider's video type never counts`() {
        assertNull(ImportedAudioType.fromClaims("video/x-matroska", null))
        assertNull(ImportedAudioType.fromClaims("video/webm", "clip"))
        assertNull(ImportedAudioType.fromClaims("video/x-matroska", "clip.mkv"))
    }

    @Test
    fun `the content's container type gives audio types, and Matroska or WebM without video`() {
        assertEquals("flac", ImportedAudioType.fromContent("audio/flac", null))
        assertEquals("mka", ImportedAudioType.fromContent("audio/x-matroska", null))
        assertEquals("m4a", ImportedAudioType.fromContent("audio/mp4", null))
        assertEquals("amr", ImportedAudioType.fromContent("audio/amr", null))
        assertEquals("awb", ImportedAudioType.fromContent("audio/amr-wb", null))
        assertEquals("mka", ImportedAudioType.fromContent("video/x-matroska", null))
        assertEquals("webm", ImportedAudioType.fromContent("video/webm", null))
        assertEquals("webm", ImportedAudioType.fromContent("VIDEO/WEBM", null))
    }

    @Test
    fun `content with video, or of any other type, gives nothing`() {
        assertNull(ImportedAudioType.fromContent("video/x-matroska", "yes"))
        assertNull(ImportedAudioType.fromContent("video/webm", "yes"))
        listOf(null, "", "video/mp4", "video/3gpp", "audio/3gpp", "audio/ac3", "text/html").forEach {
            assertNull("'$it'", ImportedAudioType.fromContent(it, null))
        }
    }
}
