package com.stash.feature.search

import com.stash.data.ytmusic.model.SocialLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportUrlTest {

    @Test fun `bandcamp kind wins over website`() {
        val url = supportUrl(
            listOf(
                SocialLink("website", "https://pjharvey.net"),
                SocialLink("bandcamp", "https://pjharvey.bandcamp.com"),
            ),
        )
        assertEquals("https://pjharvey.bandcamp.com", url)
    }

    @Test fun `bandcamp detected by url even under another kind`() {
        val url = supportUrl(listOf(SocialLink("store", "https://x.bandcamp.com/merch")))
        assertEquals("https://x.bandcamp.com/merch", url)
    }

    @Test fun `website is the fallback`() {
        val url = supportUrl(
            listOf(
                SocialLink("instagram", "https://instagram.com/x"),
                SocialLink("website", "https://artist.example"),
            ),
        )
        assertEquals("https://artist.example", url)
    }

    @Test fun `null when neither exists — chip hides`() {
        assertNull(supportUrl(listOf(SocialLink("instagram", "https://instagram.com/x"))))
        assertNull(supportUrl(emptyList()))
    }
}
