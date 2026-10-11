package com.stash.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ArtUrlSizer.forSize] for each host the app stores covers from, with the variants measured on
 * 2026-10-10. Two rules hold everywhere: a URL that is already big enough is never touched (rows
 * keep their URL and cache entry), and an unknown or unparseable URL passes through unchanged.
 */
class ArtUrlSizerTest {

    private fun sized(url: String?, px: Int) = ArtUrlSizer.forSize(url, px)

    // ── Google: YouTube Music album art, channel and playlist art ───────────────────────────

    @Test
    fun `a 60px youtube music cover asks for the 1024 tier on now playing`() {
        assertEquals(
            "https://yt3.googleusercontent.com/abc=w1024-h1024-l90-rj",
            sized("https://yt3.googleusercontent.com/abc=w60-h60-l90-rj", 950),
        )
    }

    @Test
    fun `a 544px lh3 cover keeps its other options when sized up`() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1280-h1280-s-l90-rj",
            sized("https://lh3.googleusercontent.com/abc=w544-h544-s-l90-rj", 1080),
        )
    }

    @Test
    fun `google targets above the top tier are capped at 1600`() {
        assertEquals("https://lh3.googleusercontent.com/abc=w1600-h1600", sized("https://lh3.googleusercontent.com/abc=w544-h544", 4000))
    }

    @Test
    fun `a google cover already big enough is untouched`() {
        val url = "https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj"
        assertEquals(url, sized(url, 1024))
        assertEquals(url, sized(url, 132))
    }

    @Test
    fun `ggpht square tokens size up, and s0 (the original) is never touched`() {
        assertEquals("https://yt3.ggpht.com/abc=s512-c-k-no", sized("https://yt3.ggpht.com/abc=s88-c-k-no", 300))
        assertEquals("https://yt3.ggpht.com/abc=s0", sized("https://yt3.ggpht.com/abc=s0", 1200))
    }

    @Test
    fun `a google url without a size token gets one, as a jpeg`() {
        assertEquals("https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj", sized("https://lh3.googleusercontent.com/abc", 900))
        assertEquals("https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj", sized("https://lh3.googleusercontent.com/abc=rj", 900))
    }

    @Test
    fun `other googleusercontent hosts (drive, docs) are not covers and are left alone`() {
        val url = "https://doc-0s-8c-docs.googleusercontent.com/docs/securesc/abc"
        assertEquals(url, sized(url, 1024))
    }

    // ── YouTube video thumbnails ─────────────────────────────────────────────────────────────

    @Test
    fun `a downscaled hqdefault goes to maxres with the query stripped on a big surface`() {
        assertEquals(
            "https://i.ytimg.com/vi/DWuAn6C8Mfc/maxresdefault.jpg",
            sized("https://i.ytimg.com/vi/DWuAn6C8Mfc/hqdefault.jpg?sqp=-oay&rs=AMz", 950),
        )
    }

    @Test
    fun `a downscaled thumbnail in a row is left alone (400x225 covers a 132px row)`() {
        val url = "https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=-oay&rs=AMz"
        assertEquals(url, sized(url, 132))
    }

    @Test
    fun `hq720 behind a downscale query just loses the query on a big surface`() {
        assertEquals("https://i.ytimg.com/vi/abc/hq720.jpg", sized("https://i.ytimg.com/vi/abc/hq720.jpg?sqp=x", 700))
    }

    @Test
    fun `a clean hq720 is youtube's full frame and is never rewritten`() {
        val url = "https://i.ytimg.com/vi/abc/hq720.jpg"
        assertEquals(url, sized(url, 1440))
    }

    @Test
    fun `maxres is the largest youtube serves and passes through`() {
        val url = "https://i.ytimg.com/vi/abc/maxresdefault.jpg"
        assertEquals(url, sized(url, 1400))
    }

    @Test
    fun `an unknown ytimg file is left alone`() {
        val url = "https://i.ytimg.com/vi/abc/frame0.jpg"
        assertEquals(url, sized(url, 1400))
    }

    // ── Spotify ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `spotify 640 asks for the original on now playing`() {
        assertEquals(
            "https://i.scdn.co/image/ab67616d000082c1db216ca805faf5fe35df4ee6",
            sized("https://i.scdn.co/image/ab67616d0000b273db216ca805faf5fe35df4ee6", 950),
        )
    }

    @Test
    fun `spotify picks the smallest tier that covers the target`() {
        assertEquals("https://i.scdn.co/image/ab67616d0000b273x", sized("https://i.scdn.co/image/ab67616d00004851x", 500))
        assertEquals("https://i.scdn.co/image/ab67616d00001e02x", sized("https://i.scdn.co/image/ab67616d00004851x", 200))
    }

    @Test
    fun `spotify on the spotifycdn host sizes the same way`() {
        assertEquals(
            "https://image-cdn-fa.spotifycdn.com/image/ab67616d000082c1abc",
            sized("https://image-cdn-fa.spotifycdn.com/image/ab67616d00001e02abc", 1024),
        )
    }

    @Test
    fun `a spotify 640 row and non-album spotify images are untouched`() {
        assertEquals("https://i.scdn.co/image/ab67616d0000b273x", sized("https://i.scdn.co/image/ab67616d0000b273x", 300))
        assertEquals("https://i.scdn.co/image/ab6761610000e5ebx", sized("https://i.scdn.co/image/ab6761610000e5ebx", 1024))
    }

    // ── Qobuz ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `qobuz 600 goes to max on now playing`() {
        assertEquals(
            "https://static.qobuz.com/images/covers/25/40/0060254794025_max.jpg",
            sized("https://static.qobuz.com/images/covers/25/40/0060254794025_600.jpg", 950),
        )
    }

    @Test
    fun `qobuz picks the smallest fixed tier that covers the target, and never org`() {
        assertEquals("https://static.qobuz.com/images/covers/a/b/x_300.jpg", sized("https://static.qobuz.com/images/covers/a/b/x_230.jpg", 260))
        assertEquals("https://static.qobuz.com/images/covers/a/b/x_max.jpg", sized("https://static.qobuz.com/images/covers/a/b/x_max.jpg", 4000))
        assertEquals("https://static.qobuz.com/images/covers/a/b/x_org.jpg", sized("https://static.qobuz.com/images/covers/a/b/x_org.jpg", 4000))
    }

    @Test
    fun `a qobuz 600 in a grid stays 600`() {
        val url = "https://static.qobuz.com/images/covers/a/b/x_600.jpg"
        assertEquals(url, sized(url, 495))
    }

    // ── Last.fm ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `lastfm 770 goes to the original jpeg on now playing`() {
        assertEquals(
            "https://lastfm-img.freetls.fastly.net/i/u/ar0/c9ebed7e766b45dabedaa8ee759b5181.jpg",
            sized("https://lastfm-img.freetls.fastly.net/i/u/770x0/c9ebed7e766b45dabedaa8ee759b5181.jpg", 950),
        )
    }

    @Test
    fun `lastfm small sizes go to 770 jpeg, which is lighter than the 300 png`() {
        assertEquals(
            "https://lastfm.freetls.fastly.net/i/u/770x0/abc.jpg",
            sized("https://lastfm.freetls.fastly.net/i/u/174s/abc.png", 500),
        )
    }

    @Test
    fun `lastfm 770 in a grid and the original are untouched`() {
        val grid = "https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.jpg"
        assertEquals(grid, sized(grid, 495))
        val orig = "https://lastfm-img.freetls.fastly.net/i/u/ar0/abc.jpg"
        assertEquals(orig, sized(orig, 3000))
    }

    // ── iTunes / Apple Music ─────────────────────────────────────────────────────────────────

    @Test
    fun `itunes 100x100bb goes to 1200 on now playing`() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/dd/634904032463.png/1200x1200bb.jpg",
            sized("https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/dd/634904032463.png/100x100bb.jpg", 1024),
        )
    }

    @Test
    fun `itunes keeps its crop suffix and sizes to the smallest covering tier`() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/x/300x300bb-60.jpg",
            sized("https://is1-ssl.mzstatic.com/image/thumb/x/60x60bb-60.jpg", 200),
        )
    }

    // ── JioSaavn ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `jiosaavn tops out at 500`() {
        assertEquals(
            "https://c.saavncdn.com/217/AiSh-Vol-4-Hindi-2020-500x500.jpg",
            sized("https://c.saavncdn.com/217/AiSh-Vol-4-Hindi-2020-150x150.jpg", 950),
        )
        val max = "https://c.saavncdn.com/217/AiSh-Vol-4-Hindi-2020-500x500.jpg"
        assertEquals(max, sized(max, 950))
    }

    // ── Deezer ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `deezer goes to the 1000 cover_xl`() {
        assertEquals(
            "https://e-cdns-images.dzcdn.net/images/cover/0a1b2c/1000x1000-000000-80-0-0.jpg",
            sized("https://e-cdns-images.dzcdn.net/images/cover/0a1b2c/250x250-000000-80-0-0.jpg", 950),
        )
    }

    // ── Everything else ──────────────────────────────────────────────────────────────────────

    @Test
    fun `null, blank and non-positive sizes pass through`() {
        assertNull(sized(null, 1024))
        assertEquals("", sized("", 1024))
        val url = "https://lh3.googleusercontent.com/abc=w60-h60"
        assertEquals(url, sized(url, 0))
        assertEquals(url, sized(url, -5))
    }

    @Test
    fun `unknown hosts, local files and content uris pass through`() {
        listOf(
            "https://example.com/cover_600.jpg",
            "https://tracker.example/x.jpg",
            "/data/user/0/com.stash.app/files/art/abc.jpg",
            "file:///sdcard/Music/cover.jpg",
            "content://media/external/audio/albumart/12",
        ).forEach { assertEquals(it, sized(it, 1024)) }
    }

    @Test
    fun `malformed urls pass through rather than throw`() {
        listOf(
            "https://",
            "https://lh3.googleusercontent.com",
            "https://lh3.googleusercontent.com/abc=w99999999999-h99999999999",
            "https://static.qobuz.com/images/covers/a/b/x_99999999999.jpg",
            "https://lastfm.freetls.fastly.net/i/u/99999999999x0/abc.jpg",
            "https://i.scdn.co/image/ab67616d0000",
            "not a url at all",
        ).forEach { assertEquals(it, sized(it, 1024)) }
    }

    // ── storedPx: the floor a sized Last.fm cover must not fall below ─────────────────────────

    @Test
    fun `storedPx is the stored lastfm size, and null where a bigger variant cannot be smaller`() {
        assertEquals(770, ArtUrlSizer.storedPx("https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.jpg"))
        assertEquals(300, ArtUrlSizer.storedPx("https://lastfm.freetls.fastly.net/i/u/300x300/abc.png"))
        assertNull(ArtUrlSizer.storedPx("https://lastfm.freetls.fastly.net/i/u/ar0/abc.jpg"))
        assertNull(ArtUrlSizer.storedPx("https://static.qobuz.com/images/covers/a/b/x_600.jpg"))
        assertNull(ArtUrlSizer.storedPx("https://lh3.googleusercontent.com/abc=w544-h544"))
        assertNull(ArtUrlSizer.storedPx(null))
        assertNull(ArtUrlSizer.storedPx("/data/local/cover.jpg"))
    }

    // ── The 90% rule: a stored URL about as big as the target is left alone ───────────────────

    @Test
    fun `a stored 1024 is kept for a 1078px request and sized up for 1440`() {
        val url = "https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj"
        assertEquals(url, sized(url, 1078)) // Pixel 5 screen width: 95%
        assertEquals("https://lh3.googleusercontent.com/abc=w1600-h1600-l90-rj", sized(url, 1440)) // 71%
    }

    @Test
    fun `a stored 512 is kept for a 520px grid cell`() {
        val url = "https://lh3.googleusercontent.com/abc=w512-h512"
        assertEquals(url, sized(url, 520))
    }

    @Test
    fun `lastfm 770 and qobuz 600 are still sized up for a 948px cover`() {
        assertEquals("https://lastfm.freetls.fastly.net/i/u/ar0/abc.jpg", sized("https://lastfm.freetls.fastly.net/i/u/770x0/abc.jpg", 948))
        assertEquals("https://static.qobuz.com/images/covers/a/b/x_max.jpg", sized("https://static.qobuz.com/images/covers/a/b/x_600.jpg", 948))
    }

    // ── Upgrade only, also for Google URLs without a size token (served at 512) ───────────────

    @Test
    fun `a google url without a size token is never rewritten smaller`() {
        listOf(
            "https://lh3.googleusercontent.com/abc",
            "https://lh3.googleusercontent.com/abc=rj",
        ).forEach {
            assertEquals(it, sized(it, 132))
            assertEquals(it, sized(it, 500))
        }
    }

    // ── Non-square Google boxes keep their shape ─────────────────────────────────────────────

    @Test
    fun `a non-square google crop is scaled, not squared`() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1600-h666-p-l90-rj",
            sized("https://lh3.googleusercontent.com/abc=w540-h225-p-l90-rj", 1440),
        )
    }

    // ── Data Saver ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the cap applies only with data saver restricting the app on a metered network`() {
        assertEquals(ArtUrlSizer.DATA_SAVER_MAX_PX, ArtUrlSizer.maxVariantPx(dataSaverRestricting = true, metered = true))
        assertEquals(ArtUrlSizer.NO_CAP, ArtUrlSizer.maxVariantPx(dataSaverRestricting = true, metered = false))
        assertEquals(ArtUrlSizer.NO_CAP, ArtUrlSizer.maxVariantPx(dataSaverRestricting = false, metered = true))
        assertEquals(ArtUrlSizer.NO_CAP, ArtUrlSizer.maxVariantPx(dataSaverRestricting = false, metered = false))
    }

    @Test
    fun `under data saver the unbounded originals are never picked`() {
        val cap = ArtUrlSizer.DATA_SAVER_MAX_PX
        val qobuz = "https://static.qobuz.com/images/covers/a/b/x_600.jpg"
        assertEquals(qobuz, ArtUrlSizer.forSize(qobuz, 948, cap))
        val spotify = "https://i.scdn.co/image/ab67616d0000b273abc"
        assertEquals(spotify, ArtUrlSizer.forSize(spotify, 948, cap))
        val lastFm = "https://lastfm.freetls.fastly.net/i/u/770x0/abc.jpg"
        assertEquals(lastFm, ArtUrlSizer.forSize(lastFm, 948, cap))
    }

    @Test
    fun `under data saver bounded hosts still size up, to at most 1200`() {
        val cap = ArtUrlSizer.DATA_SAVER_MAX_PX
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj",
            ArtUrlSizer.forSize("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 1440, cap),
        )
        assertEquals(
            "https://static.qobuz.com/images/covers/a/b/x_600.jpg",
            ArtUrlSizer.forSize("https://static.qobuz.com/images/covers/a/b/x_230.jpg", 948, cap),
        )
        assertEquals(
            "https://lastfm.freetls.fastly.net/i/u/770x0/abc.jpg",
            ArtUrlSizer.forSize("https://lastfm.freetls.fastly.net/i/u/300x300/abc.png", 948, cap),
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/x/1200x1200bb.jpg",
            ArtUrlSizer.forSize("https://is1-ssl.mzstatic.com/image/thumb/x/100x100bb.jpg", 1440, cap),
        )
        assertEquals(
            "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
            ArtUrlSizer.forSize("https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=x", 948, cap),
        )
    }

    // ── forSizeGuaranteed: Android Auto's tiles, which can't fall back ───────────────────────

    @Test
    fun `guaranteed sizing upgrades only to variants every cover has`() {
        val auto = 512
        assertEquals(
            "https://yt3.googleusercontent.com/abc=w512-h512-l90-rj",
            ArtUrlSizer.forSizeGuaranteed("https://yt3.googleusercontent.com/abc=w60-h60-l90-rj", auto),
        )
        assertEquals(
            "https://static.qobuz.com/images/covers/a/b/x_600.jpg",
            ArtUrlSizer.forSizeGuaranteed("https://static.qobuz.com/images/covers/a/b/x_230.jpg", auto),
        )
        assertEquals(
            "https://i.scdn.co/image/ab67616d0000b273abc",
            ArtUrlSizer.forSizeGuaranteed("https://i.scdn.co/image/ab67616d00001e02abc", auto),
        )
    }

    @Test
    fun `guaranteed sizing keeps the stored url where the bigger variant may be missing`() {
        val ytSqp = "https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=x"
        assertEquals(ytSqp, ArtUrlSizer.forSizeGuaranteed(ytSqp, 512)) // maxres: 18% missing
        val lastFm = "https://lastfm.freetls.fastly.net/i/u/300x300/abc.png"
        assertEquals(lastFm, ArtUrlSizer.forSizeGuaranteed(lastFm, 512)) // 770 jpg: a few % missing
        val qobuz = "https://static.qobuz.com/images/covers/a/b/x_600.jpg"
        assertEquals(qobuz, ArtUrlSizer.forSizeGuaranteed(qobuz, 1024)) // _max
        assertNull(ArtUrlSizer.forSizeGuaranteed(null, 512))
    }
}
