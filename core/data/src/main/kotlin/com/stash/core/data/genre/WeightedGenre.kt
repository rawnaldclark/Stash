package com.stash.core.data.genre

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One genre of an artist, as cached in `artist_genres`.
 *
 * @property genre the canonical, lower-case genre key from [GenreNormalizer] ("hip-hop", "r&b",
 *   "indie pop"). Display text comes from [GenreNormalizer.displayName], so a change to how genres
 *   are shown never needs the cache rewritten.
 * @property weight how strongly Last.fm's listeners tag the artist with it, 0..1, where the
 *   artist's strongest genre is 1.
 */
@Serializable
data class WeightedGenre(
    @SerialName("g") val genre: String,
    @SerialName("w") val weight: Float,
)

/**
 * The small JSON form `artist_genres.genres` stores: `[{"g":"hip-hop","w":1.0},…]`, `[]` for none.
 *
 * JSON rather than a hand-rolled separator format so a future genre containing any character
 * still round-trips, and so a pulled database stays readable.
 */
object WeightedGenreCodec {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WeightedGenre.serializer())

    /** Encodes [genres] for storage. */
    fun encode(genres: List<WeightedGenre>): String = json.encodeToString(serializer, genres)

    /**
     * Decodes a stored value. A blank or unreadable value (a hand-edited row, a future format)
     * reads as no genres rather than crashing the screen that shows them.
     */
    fun decode(stored: String): List<WeightedGenre> {
        if (stored.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyList())
    }
}
