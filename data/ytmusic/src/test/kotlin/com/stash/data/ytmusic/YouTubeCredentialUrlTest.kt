package com.stash.data.ytmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeCredentialUrlTest {

    @Test
    fun `https URLs on youtube_com and its subdomains may carry the credentials`() {
        listOf(
            "https://s.youtube.com/api/stats/playback?docid=abc",
            "https://music.youtube.com/api/stats/playback",
            "https://www.youtube.com/api/stats/playback",
            "https://m.youtube.com/api/stats/playback",
            "https://youtube.com/api/stats/playback",
            "https://S.YouTube.COM/api/stats/playback",
            "https://s.youtube.com:443/api/stats/playback",
        ).forEach { assertNotNull(it, YouTubeCredentialUrl.parse(it)) }
    }

    @Test
    fun `other hosts, schemes, ports and user info may not`() {
        listOf(
            null,
            "",
            "https://other.example/api/stats/playback",
            "http://s.youtube.com/api/stats/playback",
            "https://s.youtube.com.other.example/api/stats/playback",
            "https://youtube.com.other.example/",
            "https://notyoutube.com/",
            "https://youtube.co/",
            "https://other.example\\@s.youtube.com/",
            "https://other.example\\.youtube.com/",
            "https://other.example#@s.youtube.com/",
            "https://other.example?@s.youtube.com/",
            "https://user@s.youtube.com/",
            "https://:pass@s.youtube.com/",
            "https://s.youtube.com:8443/",
            "https://s.youtube.com:80/",
            "https://youtubei.googleapis.com/api/stats/playback",
            "https://127.0.0.1/",
            "https://[::1]/",
            "ftp://s.youtube.com/",
            "//s.youtube.com/",
            "s.youtube.com/api/stats/playback",
            "mailto:s@youtube.com",
        ).forEach { assertNull(it, YouTubeCredentialUrl.parse(it)) }
    }

    @Test
    fun `logs show only scheme and host`() {
        assertEquals(
            "https://other.example",
            YouTubeCredentialUrl.describeForLog("https://user:pass@other.example/path?q=1"),
        )
        assertEquals("an unparseable URL", YouTubeCredentialUrl.describeForLog("not a url"))
        assertEquals("an unparseable URL", YouTubeCredentialUrl.describeForLog(null))
    }
}
