package com.stash.core.common

import org.junit.Assert.*
import org.junit.Test

class SoundCloudArtSizingTest {
    private val cover = "https://i1.sndcdn.com/artworks-example-large.jpg"
    @Test fun `large surfaces request high resolution for existing stored thumbnails`() {
        assertEquals(cover.replace("large", "t1080x1080"), ArtUrlSizer.forSize(cover, 1000))
        assertEquals(cover.replace("large", "t500x500"), ArtUrlSizer.forSize(cover, 300))
    }
    @Test fun `small rows and already large covers keep their cache URLs`() {
        assertEquals(cover, ArtUrlSizer.forSize(cover, 80))
        val large = cover.replace("large", "t1080x1080")
        assertEquals(large, ArtUrlSizer.forSize(large, 300))
    }
    @Test fun `caps are honored without downgrading stored images`() {
        assertEquals(cover.replace("large", "t500x500"), ArtUrlSizer.forSize(cover, 1000, 500))
        assertEquals(cover, ArtUrlSizer.forSize(cover, 1000, 100))
    }
    @Test fun `unknown hosts and suffixes are unchanged`() {
        val foreign = cover.replace("i1.sndcdn.com", "i1.sndcdn.com.evil.test")
        assertEquals(foreign, ArtUrlSizer.forSize(foreign, 1000))
        val unknown = cover.replace("large", "original")
        assertEquals(unknown, ArtUrlSizer.forSize(unknown, 1000))
    }
}
