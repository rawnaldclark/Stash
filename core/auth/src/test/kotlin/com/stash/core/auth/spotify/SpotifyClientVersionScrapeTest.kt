package com.stash.core.auth.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * The client-token endpoint rejects outdated `client_version` strings with
 * HTTP 400, so Stash reads the live version off the open.spotify.com shell.
 * On 2026-09-06 the shell stopped inlining the config as plain JSON: it now
 * ships base64 inside `<script id="appServerConfig" type="text/plain">`, and
 * the old regex missed it on every install (a Pixel 6 logged "Could not
 * scrape client version" and sent a version from build 1.2.87).
 */
class SpotifyClientVersionScrapeTest {

    @Test fun `reads the version from the base64 appServerConfig block`() {
        val config = """{"appName":"web_player_prototype","market":"US","isAnonymous":true,""" +
            """"clientVersion":"1.3.1.45.ga024903a-development","correlationId":"acaa4ad9"}"""
        val shell = """<html><script id="featureFlags" type="text/plain">eyJlbmFibGVTaG93cyI6dHJ1ZX0=</script>""" +
            """<script id="appServerConfig" type="text/plain">${Base64.getEncoder().encodeToString(config.toByteArray())}</script>""" +
            """<script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.981dd70a.js"></script></html>"""

        assertEquals("1.3.1.45.ga024903a-development", SpotifyAuthManager.extractClientVersion(shell))
    }

    @Test fun `still reads a plain-JSON config`() {
        val shell = """<script>window.__config = {"clientVersion":"1.2.87.311.g2db0c2c4","market":"US"}</script>"""
        assertEquals("1.2.87.311.g2db0c2c4", SpotifyAuthManager.extractClientVersion(shell))
    }

    @Test fun `yields null when neither shape carries a version`() {
        val shell = """<script id="appServerConfig" type="text/plain">${Base64.getEncoder().encodeToString("{\"market\":\"US\"}".toByteArray())}</script>"""
        assertNull(SpotifyAuthManager.extractClientVersion(shell))
    }
}
