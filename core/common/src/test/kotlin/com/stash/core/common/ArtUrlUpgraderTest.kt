package com.stash.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * YouTube generates `hqdefault.jpg` for every video and the bigger variants
 * only for some — probed over 150 covers of a real library on 2026-09-10,
 * `sddefault` is there for 89% and `maxresdefault` for 82%. That used to
 * force the upgrader down to `hqdefault`, because a 404 draws nothing.
 *
 * `ArtFallbackInterceptor` removes that constraint: the upgrader can aim at
 * the best variant and a miss walks back down. So aim high, because
 * `hqdefault` is not merely small, it is the WRONG SHAPE — YouTube pads the
 * 16:9 frame into 4:3, and measured on a real thumbnail that is 45 black
 * rows top and bottom, 24% of the square the UI crops. `maxresdefault` is a
 * clean 1280x720 with no bars, and it is never a stock placeholder: across
 * 150 covers every one was a real 1280x720 frame, 24 KB at the smallest,
 * with no repeated images. Videos without it simply 404.
 */
class ArtUrlUpgraderTest {

    @Test
    fun `sddefault is rewritten up to maxresdefault`() {
        assertEquals(
            "https://i.ytimg.com/vi/_uofQD-N6UI/maxresdefault.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/_uofQD-N6UI/sddefault.jpg"),
        )
    }

    @Test
    fun `maxresdefault is already the target and passes through`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/maxresdefault.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/abc123/maxresdefault.jpg"),
        )
    }

    @Test
    fun `small variants still upgrade, now to maxresdefault, and the downscale query is stripped`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/maxresdefault.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/abc123/default.jpg?sqp=xyz&rs=abc"),
        )
        assertEquals(
            "https://i.ytimg.com/vi_webp/abc123/maxresdefault.webp",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi_webp/abc123/mqdefault.webp"),
        )
    }

    @Test
    fun `hqdefault is upgraded and the downscale query is stripped`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/maxresdefault.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/abc123/hqdefault.jpg?sqp=x"),
        )
    }

    @Test
    fun `other hosts and null are untouched by the ytimg rule`() {
        assertNull(ArtUrlUpgrader.upgrade(null))
        assertEquals("https://static.qobuz.com/images/covers/x.jpg", ArtUrlUpgrader.upgrade("https://static.qobuz.com/images/covers/x.jpg"))
    }

    // Last.fm serves album art under `/i/u/<size>/<hash>.<ext>` on TWO hosts
    // (`lastfm.freetls.fastly.net` and `lastfm-img.freetls.fastly.net`). The
    // API hands back 300x300 PNGs; probed on a real library 2026-09-09, 3 in 4
    // originals are 600px or larger and the CDN serves any of them 770 wide as
    // a JPEG that weighs LESS than the 300px PNG (median 85 KB vs 185 KB).
    @Test
    fun `lastfm art on the lastfm-img host is upgraded to 770 wide jpeg`() {
        assertEquals(
            "https://lastfm-img.freetls.fastly.net/i/u/770x0/a1e2a5b1e851d66bc10112a4dbceb750.jpg",
            ArtUrlUpgrader.upgrade("https://lastfm-img.freetls.fastly.net/i/u/300x300/a1e2a5b1e851d66bc10112a4dbceb750.png"),
        )
    }

    @Test
    fun `lastfm art on the plain lastfm host is upgraded the same way`() {
        assertEquals(
            "https://lastfm.freetls.fastly.net/i/u/770x0/f93cfd7cdcea45b29987f964751aa8bd.jpg",
            ArtUrlUpgrader.upgrade("https://lastfm.freetls.fastly.net/i/u/300x300/f93cfd7cdcea45b29987f964751aa8bd.png"),
        )
        assertEquals(
            "https://lastfm.freetls.fastly.net/i/u/770x0/abc.jpg",
            ArtUrlUpgrader.upgrade("https://lastfm.freetls.fastly.net/i/u/174s/abc.png"),
        )
    }

    @Test
    fun `an already upgraded lastfm url passes through unchanged`() {
        val done = "https://lastfm-img.freetls.fastly.net/i/u/770x0/a1e2a5b1e851d66bc10112a4dbceb750.jpg"
        assertEquals(done, ArtUrlUpgrader.upgrade(done))
    }

    // The 770-wide variants are generated on demand: probed over 200 covers of
    // a real library, 770x0.jpg missed 6 hashes and 770x0.png missed 5, but NO
    // hash missed both. So a jpg miss steps to png, and a png miss steps back
    // to the exact URL the API handed us — a cover can never come out worse
    // than it is today, which is the whole point (a 404 draws nothing).
    @Test
    fun `a missing lastfm jpeg falls back to the png of the same size`() {
        assertEquals(
            "https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.png",
            ArtUrlUpgrader.artFallback("https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.jpg"),
        )
    }

    @Test
    fun `a missing 770 png falls back to the size the api actually advertises`() {
        assertEquals(
            "https://lastfm.freetls.fastly.net/i/u/300x300/abc.png",
            ArtUrlUpgrader.artFallback("https://lastfm.freetls.fastly.net/i/u/770x0/abc.png"),
        )
    }

    @Test
    fun `the last rung and every non-lastfm url have no fallback`() {
        assertNull(ArtUrlUpgrader.artFallback("https://lastfm.freetls.fastly.net/i/u/300x300/abc.png"))
        assertNull(ArtUrlUpgrader.artFallback("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertNull(ArtUrlUpgrader.artFallback(null))
    }

    // 82% of a real library's thumbnails have maxresdefault, 89% have
    // sddefault, and hqdefault is the only one every video is guaranteed.
    // So the walk gives up resolution one step at a time and always lands
    // on something rather than an empty tile.
    @Test
    fun `a missing maxres steps to sddefault, then to the guaranteed hqdefault`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/sddefault.jpg",
            ArtUrlUpgrader.artFallback("https://i.ytimg.com/vi/abc123/maxresdefault.jpg"),
        )
        assertEquals(
            "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            ArtUrlUpgrader.artFallback("https://i.ytimg.com/vi/abc123/sddefault.jpg"),
        )
        assertNull(ArtUrlUpgrader.artFallback("https://i.ytimg.com/vi/abc123/hqdefault.jpg"))
    }

    @Test
    fun `the webp thumbnail path walks too`() {
        assertEquals(
            "https://i.ytimg.com/vi_webp/abc123/sddefault.webp",
            ArtUrlUpgrader.artFallback("https://i.ytimg.com/vi_webp/abc123/maxresdefault.webp"),
        )
    }

    @Test
    fun `hq720 from search results keeps its full frame and loses the downscale query`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/hq720.jpg",
            ArtUrlUpgrader.upgrade("https://i.ytimg.com/vi/abc123/hq720.jpg?sqp=x&rs=y"),
        )
    }

    // The large variants ArtUrlSizer asks for on Now Playing step down to what was stored.

    @Test
    fun `a missing lastfm original steps to the 770 jpeg, then on down the usual walk`() {
        assertEquals(
            "https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.jpg",
            ArtUrlUpgrader.artFallback("https://lastfm-img.freetls.fastly.net/i/u/ar0/abc.jpg"),
        )
    }

    @Test
    fun `a missing qobuz max steps to 600, and 600 is the last rung`() {
        assertEquals(
            "https://static.qobuz.com/images/covers/25/40/0060254794025_600.jpg",
            ArtUrlUpgrader.artFallback("https://static.qobuz.com/images/covers/25/40/0060254794025_max.jpg"),
        )
        assertNull(ArtUrlUpgrader.artFallback("https://static.qobuz.com/images/covers/25/40/0060254794025_600.jpg"))
    }

    @Test
    fun `a missing spotify original steps to 640, and 640 is the last rung`() {
        assertEquals(
            "https://i.scdn.co/image/ab67616d0000b273abc",
            ArtUrlUpgrader.artFallback("https://i.scdn.co/image/ab67616d000082c1abc"),
        )
        assertNull(ArtUrlUpgrader.artFallback("https://i.scdn.co/image/ab67616d0000b273abc"))
    }

    @Test
    fun `a missing large itunes cover steps to 600, and 600 is the last rung`() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/x/600x600bb.jpg",
            ArtUrlUpgrader.artFallback("https://is1-ssl.mzstatic.com/image/thumb/x/1200x1200bb.jpg"),
        )
        assertNull(ArtUrlUpgrader.artFallback("https://is1-ssl.mzstatic.com/image/thumb/x/600x600bb.jpg"))
    }

    @Test
    fun `banner keeps a header photo's own shape, 1440 wide`() {
        val square = "https://yt3.googleusercontent.com/AbC_d-e=w1024-h1024-p-l90-rj"
        assertEquals("https://yt3.googleusercontent.com/AbC_d-e=w1440-l90-rj", ArtUrlUpgrader.banner(square))
        val bare = "https://lh3.googleusercontent.com/XyZ"
        assertEquals("https://lh3.googleusercontent.com/XyZ=w1440-l90-rj", ArtUrlUpgrader.banner(bare))
    }

    @Test
    fun `banner leaves other hosts to upgrade`() {
        val spotify = "https://i.scdn.co/image/ab67616d00004851abc"
        assertEquals(ArtUrlUpgrader.upgrade(spotify), ArtUrlUpgrader.banner(spotify))
        assertEquals(null, ArtUrlUpgrader.banner(null))
    }
}
