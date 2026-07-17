package com.stash.data.download.files

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOrganizerSlugsTest {

    @Test
    fun `slugify preserves existing ASCII behavior`() {
        assertEquals(
            "acdc-back-in-black-live",
            FileOrganizerSlugs.slugify("AC/DC: Back in Black! (Live)"),
        )
    }

    @Test
    fun `slugify keeps Cyrillic names non-empty and filesystem-safe`() {
        val slug = FileOrganizerSlugs.slugify("Кино — Группа крови")

        assertEquals("кино-группа-крови", slug)
        assertFalse(slug.isEmpty())
        assertTrue(slug.matches(Regex("[\\p{L}\\p{N}-]+")))
    }

    @Test
    fun `slugify keeps Japanese titles non-empty and filesystem-safe`() {
        val slug = FileOrganizerSlugs.slugify("夜に駆ける / YOASOBI")

        assertEquals("夜に駆ける-yoasobi", slug)
        assertFalse(slug.isEmpty())
        assertTrue(slug.matches(Regex("[\\p{L}\\p{N}-]+")))
    }

    @Test
    fun `slugify truncates supplementary-plane letters at complete code points`() {
        val slug = FileOrganizerSlugs.slugify("a" + "𐐀".repeat(60))

        assertEquals(60, slug.codePointCount(0, slug.length))
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(slug))
    }
}
