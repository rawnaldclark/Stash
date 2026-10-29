package com.stash.core.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {

    private fun release(extra: String = "", assets: String = APK) =
        """{"tag_name":"v0.9.122","name":"Stash 0.9.122","draft":false,"prerelease":false$extra,
           "body":"## What's new\r\n- **Paste links** from [Spotify](https://x)\r\n\r\n\r\n\r\n- Sync tab","assets":[$assets]}"""

    @Test
    fun `a release gives its tag, apk, size and plain notes`() {
        val u = AppUpdates.parseRelease(release())!!
        assertEquals("v0.9.122", u.tag)
        assertEquals("0.9.122", u.version)
        assertEquals("https://github.com/rawnaldclark/Stash/releases/download/v0.9.122/Stash.apk", u.apkUrl)
        assertEquals(171_411_798L, u.sizeBytes)
        assertEquals("What's new\n• Paste links from Spotify\n\n• Sync tab", u.notes)
    }

    @Test
    fun `no apk, a draft or a pre-release is no update`() {
        assertNull(AppUpdates.parseRelease(release(assets = """{"name":"notes.txt","browser_download_url":"https://x/notes.txt"}""")))
        assertNull(AppUpdates.parseRelease(release(extra = ""","draft":true""").replace("\"draft\":false,", "")))
        assertNull(AppUpdates.parseRelease("""{"tag_name":"v1","prerelease":true,"assets":[$APK]}"""))
        assertNull(AppUpdates.parseRelease("not json"))
    }

    @Test
    fun `later asks again a day on, and a newer release at once`() {
        val day = AppUpdates.ASK_AGAIN_MS
        assertTrue(AppUpdates.shouldPrompt("v2", laterTag = null, laterAt = 0, now = 10))
        assertFalse(AppUpdates.shouldPrompt("v2", laterTag = "v2", laterAt = 1_000, now = 1_000 + day - 1))
        assertTrue(AppUpdates.shouldPrompt("v2", laterTag = "v2", laterAt = 1_000, now = 1_000 + day))
        assertTrue(AppUpdates.shouldPrompt("v3", laterTag = "v2", laterAt = 1_000, now = 1_001))
        // A clock set back: ask rather than stay quiet for good.
        assertTrue(AppUpdates.shouldPrompt("v2", laterTag = "v2", laterAt = 5_000, now = 1_000))
    }

    @Test
    fun `size in megabytes`() {
        assertEquals("171 MB", AppUpdates.sizeLabel(171_411_798))
        assertEquals("", AppUpdates.sizeLabel(0))
    }

    private companion object {
        const val APK = """{"name":"Stash.apk","size":171411798,
            "browser_download_url":"https://github.com/rawnaldclark/Stash/releases/download/v0.9.122/Stash.apk"}"""
    }
}
