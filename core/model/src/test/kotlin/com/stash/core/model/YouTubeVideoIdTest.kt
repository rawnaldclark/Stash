package com.stash.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YouTubeVideoIdTest {

    @Test
    fun `real video ids are valid`() {
        listOf("dQw4w9WgXcQ", "mw3kSNIxjqo", "V-uIp-WuD60", "zOPv_LFISTM", "___________", "-----------")
            .forEach { assertThat(YouTubeVideoId.isValid(it)).isTrue() }
    }

    @Test
    fun `anything else is not`() {
        listOf(
            null,
            "",
            "dQw4w9WgXc", // 10
            "dQw4w9WgXcQQ", // 12
            "../../../xx", // 11 characters, with separators
            "..%2F..%2Fx",
            "a%(title)ss",
            "dQw4w9WgXc/",
            "dQw4w9WgXc\\",
            "dQw4w9WgXc.",
            "dQw4w9WgXc ",
            " dQw4w9WgXc",
            "dQw4w9WgXc\n",
            "dQw4w9WgXc\u0000",
            "dQw4w9WgXc&",
            "dQw4w9WgXc#",
            "dQw4w9WgXc?",
            "dQw4w9WgXcé", // a letter outside ASCII
            "dQw4w9WgXc３", // fullwidth digit
            "dQw4w9WgXcＱ", // fullwidth letter
        ).forEach { assertThat(YouTubeVideoId.isValid(it)).isFalse() }
    }

    @Test
    fun `watch URLs are built only from valid ids`() {
        assertThat(YouTubeVideoId.watchUrl("dQw4w9WgXcQ")).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertThat(YouTubeVideoId.watchUrl("../x")).isNull()
        assertThat(YouTubeVideoId.watchUrl(null)).isNull()
    }

    @Test
    fun `the id of a YouTube watch link`() {
        assertThat(YouTubeVideoId.fromWatchUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(YouTubeVideoId.fromWatchUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(YouTubeVideoId.fromWatchUrl("https://youtube.com/watch?list=PL1&v=dQw4w9WgXcQ&t=3")).isEqualTo("dQw4w9WgXcQ")
        assertThat(YouTubeVideoId.fromWatchUrl("HTTPS://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(YouTubeVideoId.fromWatchUrl("https://www.youtube.com:443/watch?v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
    }

    /** Not YouTube watch links with a valid id. */
    private val notWatchLinks = listOf(
        null,
        "",
        "dQw4w9WgXcQ",
        "--x",
        "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://other.example/watch?v=dQw4w9WgXcQ",
        "https://www.youtube.com.other.example/watch?v=dQw4w9WgXcQ",
        "https://user@www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://other.example\\@www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://www.youtube.com:8443/watch?v=dQw4w9WgXcQ",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ",
        "https://www.youtube.com/watch?v=../x",
        "https://www.youtube.com/watch?v=a%25(title)s",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&v=V-uIp-WuD60",
        "https://www.youtube.com/watch",
        "file:///a/b",
    )

    @Test
    fun `anything that isn't a YouTube watch link with a valid id has no id`() {
        notWatchLinks.forEach { assertThat(YouTubeVideoId.fromWatchUrl(it)).isNull() }
    }

    @Test
    fun `a watch link is rebuilt from its id, a YouTube Music one on music_youtube_com`() {
        mapOf(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ" to "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://MUSIC.youtube.com:443/watch?list=RDAMVM1&v=dQw4w9WgXcQ&t=3" to
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ&list=PL1" to "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ" to "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ" to "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        ).forEach { (link, rebuilt) -> assertThat(YouTubeVideoId.rebuildWatchUrl(link)).isEqualTo(rebuilt) }
    }

    @Test
    fun `anything that isn't a YouTube watch link with a valid id isn't rebuilt`() {
        notWatchLinks.forEach { assertThat(YouTubeVideoId.rebuildWatchUrl(it)).isNull() }
    }
}
