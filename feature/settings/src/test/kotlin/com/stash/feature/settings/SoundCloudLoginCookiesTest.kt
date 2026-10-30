package com.stash.feature.settings

import com.stash.feature.settings.components.SoundCloudLoginCookies
import org.junit.Assert.*
import org.junit.Test

class SoundCloudLoginCookiesTest {
    @Test fun `mobile host-only cookie completes login`() {
        val result = SoundCloudLoginCookies.token("https://m.soundcloud.com/discover") {
            if (it == "https://m.soundcloud.com") "other=1; oauth_token=mobile-token; last=2" else null
        }
        assertEquals("mobile-token", result)
    }

    @Test fun `active origin wins over stale root cookie`() {
        assertEquals("mobile", SoundCloudLoginCookies.token("https://m.soundcloud.com/") {
            if (it == "https://m.soundcloud.com") "oauth_token=mobile" else "oauth_token=old"
        })
    }

    @Test fun `root cookie remains supported after mobile redirect`() {
        assertEquals("root", SoundCloudLoginCookies.token("https://m.soundcloud.com/") {
            if (it == "https://soundcloud.com") "oauth_token=root" else null
        })
    }

    @Test fun `untrusted or insecure pages never read credentials`() {
        for (page in listOf(null, "https://accounts.google.com/", "https://soundcloud.com.evil.test/",
            "http://m.soundcloud.com/", "https://user@m.soundcloud.com/", "https://m.soundcloud.com:8443/")) {
            assertNull(SoundCloudLoginCookies.token(page) { error("Must not read cookies here") })
        }
    }

    @Test fun `empty cookies and similarly named cookies do not complete login`() {
        assertNull(SoundCloudLoginCookies.token("https://soundcloud.com/") {
            "oauth_refresh_token=refresh; oauth_token=; not_oauth_token=other"
        })
    }

    @Test fun `login cleanup covers host-only and domain cookies`() {
        val removals = SoundCloudLoginCookies.removals
        assertTrue(removals.any { it.first == "https://m.soundcloud.com" && !it.second.contains("domain=") })
        assertTrue(removals.any { it.first == "https://soundcloud.com" && it.second.contains("domain=.soundcloud.com") })
        assertTrue(removals.all { it.second.contains("Max-Age=0") })
    }
}
