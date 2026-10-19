package com.stash.core.data.genre

import android.content.Context
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistGenreRepositoryTest {

    private fun row(name: String, status: String, genres: List<WeightedGenre> = emptyList()) =
        ArtistGenreEntity(name, WeightedGenreCodec.encode(genres), status, fetchedAt = 1L)

    @Test fun `only tagged rows with genres reach the map`() {
        val map = ArtistGenreRepository.toGenreMap(
            listOf(
                row("Drake", ArtistGenreEntity.STATUS_TAGGED, listOf(WeightedGenre("hip-hop", 1f))),
                row("Local Rip", ArtistGenreEntity.STATUS_UNTAGGABLE),
                row("Broken", ArtistGenreEntity.STATUS_FAILED),
                ArtistGenreEntity("Garbled", "not json", ArtistGenreEntity.STATUS_TAGGED, 1L),
            ),
        )
        assertEquals(mapOf("Drake" to listOf(WeightedGenre("hip-hop", 1f))), map)
    }

    @Test fun `codec round-trips and reads junk as no genres`() {
        val genres = listOf(WeightedGenre("hip-hop", 1f), WeightedGenre("r&b", 0.5f))
        assertEquals(genres, WeightedGenreCodec.decode(WeightedGenreCodec.encode(genres)))
        assertEquals("[]", WeightedGenreCodec.encode(emptyList()))
        assertTrue(WeightedGenreCodec.decode("").isEmpty())
        assertTrue(WeightedGenreCodec.decode("{oops").isEmpty())
    }

    @Test fun `top genres update when the cache fills in`() = runTest {
        val rows = MutableStateFlow(listOf(row("Drake", ArtistGenreEntity.STATUS_TAGGED, listOf(WeightedGenre("hip-hop", 1f)))))
        val dao: ArtistGenreDao = mockk { every { observeAll() } returns rows }
        val repository = ArtistGenreRepository(dao, mockk<Context>(relaxed = true))
        val artists = flowOf(listOf(ArtistWeight("Drake", 50), ArtistWeight("SZA", 50)))

        val before = repository.observeTopGenres(artists).first()
        assertEquals(0.5, before.coverage, 1e-9)
        assertEquals(listOf("Hip-Hop"), before.items.map { it.name })

        rows.value = rows.value + row("SZA", ArtistGenreEntity.STATUS_TAGGED, listOf(WeightedGenre("r&b", 1f)))
        val after = repository.observeTopGenres(artists).first()
        assertEquals(1.0, after.coverage, 1e-9)
        assertEquals(listOf("Hip-Hop", "R&B"), after.items.map { it.name })
    }

    @Test fun `an unchanged cache doesn't re-emit the same result`() = runTest {
        val tagged = listOf(row("Drake", ArtistGenreEntity.STATUS_TAGGED, listOf(WeightedGenre("hip-hop", 1f))))
        val dao: ArtistGenreDao = mockk {
            // An untaggable row arriving changes the table but not the genres.
            every { observeAll() } returns flowOf(tagged, tagged + row("Nobody", ArtistGenreEntity.STATUS_UNTAGGABLE))
        }
        val repository = ArtistGenreRepository(dao, mockk<Context>(relaxed = true))

        val maps = repository.genresByArtist.take(2).toList()
        assertEquals(1, maps.size)
        assertFalse(maps.single().isEmpty())
    }
}
