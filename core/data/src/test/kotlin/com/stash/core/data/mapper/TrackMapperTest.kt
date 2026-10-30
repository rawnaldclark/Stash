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
    @Test fun `SoundCloud identity and preview survive domain round trip`() {
        val entity = TrackEntity(
            title = "Remix", artist = "Artist", soundCloudId = "123",
            soundCloudPermalink = "https://soundcloud.com/artist/remix", soundCloudPreview = true,
            filePath = "/music/existing.flac", isDownloaded = true,
        )
        val restored = entity.toDomain().toEntity()
        assertEquals(entity.soundCloudId, restored.soundCloudId)
        assertEquals(entity.soundCloudPermalink, restored.soundCloudPermalink)
        assertEquals(true, restored.soundCloudPreview)
        assertEquals(entity.filePath, restored.filePath)
        assertEquals(true, restored.isDownloaded)
    }

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

    @Test
    fun `streamability fields survive round-trip`() {
        // v0.9.27: the library row's greyed-out predicate
        // (`isUnavailableForDisplay`) reads both `isStreamable` and
        // `isStreamableCheckedAt` off the domain `Track`. If the mapper
        // ever drops one of these, rows that should grey out would
        // render normally (and tap into a no-op play) or vice-versa.
        val entity = TrackEntity(
            title = "Unavailable Track",
            artist = "Artist",
            isStreamable = false,
            isStreamableCheckedAt = 1_700_000_000_000L,
        )

        val domain = entity.toDomain()
        assertEquals(false, domain.isStreamable)
        assertEquals(1_700_000_000_000L, domain.isStreamableCheckedAt)

        val roundTripped = domain.toEntity()
        assertEquals(false, roundTripped.isStreamable)
        assertEquals(1_700_000_000_000L, roundTripped.isStreamableCheckedAt)
    }

    @Test
    fun `null streamability checkedAt survives round-trip`() {
        // The tristate sentinel: null = "never checked", which the
        // `AvailabilityCheckWorker` queries to find rows that still
        // need an initial check.
        val entity = TrackEntity(title = "Legacy Row", artist = "Artist")

        val domain = entity.toDomain()
        assertNull(domain.isStreamableCheckedAt)
        assertEquals(false, domain.isStreamable)

        val roundTripped = domain.toEntity()
        assertNull(roundTripped.isStreamableCheckedAt)
        assertEquals(false, roundTripped.isStreamable)
    }
}
