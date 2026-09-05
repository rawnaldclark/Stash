package com.stash.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * YouTube generates `hqdefault.jpg` for every video, but `sddefault.jpg` and
 * `maxresdefault.jpg` only for some (about 1 in 12 library rows 404'd on
 * `sddefault` when probed on 2026-09-05, and the loader draws nothing for a
 * 404 — black art). The upgrader must therefore only ever emit the variant
 * that exists for every video.
 */
class ArtUrlUpgraderTest {

    @Test
    fun `sddefault is rewritten to the always-available hqdefault`() {
        assertEquals(
            "https://i.ytimg.com/vi/_uofQD-N6UI/hqdefault.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/_uofQD-N6UI/sddefault.jpg"),
        )
    }

    @Test
    fun `maxresdefault is rewritten to hqdefault too`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/abc123/maxresdefault.jpg"),
        )
    }

    @Test
    fun `small variants still upgrade, now to hqdefault, and the downscale query is stripped`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/abc123/default.jpg?sqp=xyz&rs=abc"),
        )
        assertEquals(
            "https://i.ytimg.com/vi_webp/abc123/hqdefault.webp",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi_webp/abc123/mqdefault.webp"),
        )
    }

    @Test
    fun `hqdefault passes through unchanged apart from the query strip`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/abc123/hqdefault.jpg?sqp=x"),
        )
    }

    @Test
    fun `other hosts and null are untouched by the ytimg rule`() {
        assertNull(ArtUrlUpgrader.upgrade(null))
        assertEquals("https://static.qobuz.com/images/covers/x.jpg", ArtUrlUpgrader.upgrade("https://static.qobuz.com/images/covers/x.jpg"))
    }
}
