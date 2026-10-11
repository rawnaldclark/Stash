package com.stash.core.ui.util

import coil3.size.Dimension
import coil3.size.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The size the mapper hands [com.stash.core.common.ArtUrlSizer]: the long side of the request's
 * resolved target. The URL arithmetic itself is tested in `ArtUrlSizerTest`.
 */
class ArtSizeMapperTest {

    @Test
    fun `a square target is its side`() {
        assertEquals(1024, ArtSizeMapper.targetPx(Size(1024, 1024)))
    }

    @Test
    fun `a non-square target is its long side`() {
        assertEquals(946, ArtSizeMapper.targetPx(Size(946, 300)))
    }

    @Test
    fun `one known side is enough`() {
        assertEquals(132, ArtSizeMapper.targetPx(Size(Dimension(132), Dimension.Undefined)))
    }

    @Test
    fun `an original-size request has nothing to size to`() {
        assertNull(ArtSizeMapper.targetPx(Size.ORIGINAL))
    }
}
