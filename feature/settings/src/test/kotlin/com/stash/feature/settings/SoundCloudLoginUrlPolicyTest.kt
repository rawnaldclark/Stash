package com.stash.feature.settings

import com.stash.feature.settings.components.isAllowedSoundCloudLoginUrl
import org.junit.Assert.*
import org.junit.Test

class SoundCloudLoginUrlPolicyTest {
    @Test fun `known HTTPS authentication providers are allowed`() {
        for (url in listOf("https://soundcloud.com/signin", "https://secure.soundcloud.com/connect",
            "https://accounts.google.com/", "https://appleid.apple.com/", "https://www.facebook.com/"))
            assertTrue(url, isAllowedSoundCloudLoginUrl(url))
    }
    @Test fun `foreign hosts local files cleartext credentials and unusual ports are refused`() {
        for (url in listOf("https://soundcloud.com.evil.example/", "file:///sdcard/a", "javascript:alert(1)",
            "http://soundcloud.com/signin", "https://user:pass@soundcloud.com/", "https://soundcloud.com:8443/",
            "https://127.0.0.1/")) assertFalse(url, isAllowedSoundCloudLoginUrl(url))
    }
}
