package com.stash.core.data.mapper

import com.stash.core.data.db.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip tests for [TrackEntity.toDomain] / [com.stash.core.model.Track.toEntity].
 *
 * The matcher upgrade (Phase 1) threads Spotify's per-master `isrc` and
 * `explicit` flag from the API layer through Room into the domain `Track`.
 * These tests pin the contract so a future edit that drops either field
 * from the mapper fails loudly instead of silently producing wrong-version
 * downloads.
 */
class TrackMapperTest {

    @Test
    fun `entity round-trip preserves isrc and explicit`() {
        val entity = TrackEntity(
            title = "Smooth Criminal",
            artist = "Michael Jackson",
            isrc = "USSM11200115",
            explicit = false,
        )

        val domain = entity.toDomain()
        assertEquals("USSM11200115", domain.isrc)
        assertEquals(false, domain.explicit)

        val roundTripped = domain.toEntity()
        assertEquals("USSM11200115", roundTripped.isrc)
        assertEquals(false, roundTripped.explicit)
    }

    @Test
    fun `null isrc and explicit survive round-trip`() {
        val entity = TrackEntity(title = "Unknown Song", artist = "Unknown Artist")

        val domain = entity.toDomain()

        assertNull("legacy rows should round-trip with null isrc", domain.isrc)
        assertNull("legacy rows should round-trip with null explicit", domain.explicit)
    }

    @Test
    fun `explicit true survives round-trip`() {
        val entity = TrackEntity(
            title = "Explicit Song",
            artist = "Artist",
            explicit = true,
        )

        assertEquals(true, entity.toDomain().explicit)
        assertEquals(true, entity.toDomain().toEntity().explicit)
    }
}
