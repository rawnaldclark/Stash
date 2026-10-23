package com.stash.core.auth.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** #664: the picked YouTube profile, kept in the YouTube token's scope field. */
class YouTubeIdentityTest {
    @Test fun `round-trips through the token's scope`() {
        val brand = YouTubeIdentity("112233445566778899001", authUser = 1)
        assertEquals(brand, YouTubeIdentity.decode(brand.encode()))
        assertEquals(YouTubeIdentity(), YouTubeIdentity.decode(YouTubeIdentity().encode()))
    }

    @Test fun `an old or odd scope is the account's own profile`() {
        assertEquals(YouTubeIdentity(), YouTubeIdentity.decode(""))
        assertEquals(YouTubeIdentity(), YouTubeIdentity.decode("pageId=abc;authuser=-3"))
    }
}
