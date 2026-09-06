package com.stash.data.ytmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure enum/config assertions for [InnerTubeVariant] and the audio-variant
 * attempt order. Locks the proven-working IOS request shape (www host,
 * current version, numeric client-name id, keyless) so a future edit that
 * regresses any of those facts breaks the build rather than the fast lane.
 */
class InnerTubeVariantTest {

    @Test fun ios_uses_www_host_and_current_version() {
        val ios = InnerTubeVariant.IOS
        assertEquals("21.02.3", ios.clientVersion)
        assertEquals("https://www.youtube.com/youtubei/v1", ios.apiBase)
        assertEquals("5", ios.clientNameId)
        assertFalse(ios.sendsApiKey)
    }

    @Test fun web_remix_stays_on_music_host_and_sends_key() {
        val web = InnerTubeVariant.WEB_REMIX
        assertEquals("https://music.youtube.com/youtubei/v1", web.apiBase)
        assertEquals("67", web.clientNameId)
        assertTrue(web.sendsApiKey)
    }

    /**
     * ANDROID_VR is the client yt-dlp pins (`player_client=android_vr`) to get a
     * direct itag-251 URL with no PO token, no m3u8 manifest and no signature
     * solve — the URLs this app streams today are android_vr URLs, minted the
     * slow way through a Python process. It must be tried on the www host as
     * client 28, the same transport shape that made IOS work.
     */
    @Test fun android_vr_uses_www_host_as_client_28_keyless() {
        val vr = InnerTubeVariant.ANDROID_VR
        assertEquals("https://www.youtube.com/youtubei/v1", vr.apiBase)
        assertEquals("28", vr.clientNameId)
        assertFalse(vr.sendsApiKey)
    }

    @Test fun audio_variant_order_tries_android_vr_then_ios_then_the_apple_fallbacks() {
        assertEquals(
            listOf(InnerTubeVariant.ANDROID_VR, InnerTubeVariant.IOS, InnerTubeVariant.VISIONOS, InnerTubeVariant.IPADOS),
            InnerTubeClient.AUDIO_VARIANT_ORDER,
        )
    }
}
