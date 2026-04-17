package com.stash.core.data.cache

import com.stash.core.data.db.entity.ArtistProfileCacheEntity
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Helpers that build a ready-to-insert [ArtistProfileCacheEntity] from a
 * synthetic [ArtistProfile] — keeps [ArtistCacheTest] readable without
 * duplicating the `Json.encodeToString` boilerplate across every `@Test`.
 *
 * The [Json] instance mirrors the one inside [ArtistCache] so the two
 * sides round-trip identically (lenient on unknown keys).
 */
internal object ArtistCacheEntityFixtures {

    private val json = Json { ignoreUnknownKeys = true }

    fun serialized(
        artistId: String,
        name: String,
        fetchedAt: Long,
    ): ArtistProfileCacheEntity {
        val profile = ArtistProfile(
            id = artistId,
            name = name,
            avatarUrl = null,
            subscribersText = null,
            popular = emptyList(),
            albums = emptyList(),
            singles = emptyList(),
            related = emptyList(),
        )
        return ArtistProfileCacheEntity(
            artistId = artistId,
            json = json.encodeToString(profile),
            fetchedAt = fetchedAt,
        )
    }
}
