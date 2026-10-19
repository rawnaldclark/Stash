package com.stash.core.data.genre

import android.content.Context
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistGenreRepositoryTest {

    private fun row(name: String, status: String, genres: List<WeightedGenre> = emptyList()) =
        ArtistGenreEntity(name, WeightedGenreCodec.encode(genres), status, fetchedAt = 1L)

    private val hipHop = listOf(WeightedGenre("hip-hop", 1f))

    @Test fun `only tagged rows with genres reach the index`() {
        val index = ArtistGenreRepository.toIndex(
            listOf(
                row("Drake", ArtistGenreEntity.STATUS_TAGGED, hipHop),
                row("Local Rip", ArtistGenreEntity.STATUS_UNTAGGABLE),
                row("Broken", ArtistGenreEntity.STATUS_FAILED),
                ArtistGenreEntity("Garbled", "not json", ArtistGenreEntity.STATUS_TAGGED, 1L),
            ),
        )
        assertEquals(1, index.size)
        assertEquals(hipHop, index.genresFor("Drake"))
        assertEquals(hipHop, index.genresFor("drake feat. Rihanna"))
        assertNull(index.genresFor("Local Rip"))
    }

    @Test fun `rows written before rap was folded read like a fresh fetch`() {
        // As cached on the rig by an earlier build: "rap" kept next to "emo rap".
        val stale = listOf(WeightedGenre("emo rap", 1f), WeightedGenre("rap", 0.44f))
        val index = ArtistGenreRepository.toIndex(listOf(row("Death Plus", ArtistGenreEntity.STATUS_TAGGED, stale)))
        // "rap" is Hip-Hop now, and Hip-Hop is the umbrella of Emo Rap: only Emo Rap stays.
        assertEquals(listOf(WeightedGenre("emo rap", 1f)), index.genresFor("Death Plus"))
    }

    @Test fun `codec round-trips and reads junk as no genres`() {
        val genres = listOf(WeightedGenre("hip-hop", 1f), WeightedGenre("r&b", 0.5f))
        assertEquals(genres, WeightedGenreCodec.decode(WeightedGenreCodec.encode(genres)))
        assertEquals("[]", WeightedGenreCodec.encode(emptyList()))
        assertTrue(WeightedGenreCodec.decode("").isEmpty())
        assertTrue(WeightedGenreCodec.decode("{oops").isEmpty())
    }

    @Test fun `top genres update when the cache fills in`() = runTest {
        val rows = MutableStateFlow(listOf(row("Drake", ArtistGenreEntity.STATUS_TAGGED, hipHop)))
        val dao: ArtistGenreDao = mockk { every { observeAll() } returns rows }
        val repository = ArtistGenreRepository(dao, mockk<Context>(relaxed = true), backgroundScope)
        val artists = flowOf(listOf(ArtistWeight("Drake", 50), ArtistWeight("SZA", 50)))

        val before = repository.observeTopGenres(artists).first()
        assertEquals(0.5, before.coverage, 1e-9)
        assertEquals(listOf("Hip-Hop"), before.items.map { it.name })

        rows.value = rows.value + row("SZA", ArtistGenreEntity.STATUS_TAGGED, listOf(WeightedGenre("r&b", 1f)))
        val after = repository.observeTopGenres(artists).first { it.coverage > 0.5 }
        assertEquals(1.0, after.coverage, 1e-9)
        assertEquals(listOf("Hip-Hop", "R&B"), after.items.map { it.name })
    }

    @Test fun `collectors share one decoded index`() = runTest {
        val rows = MutableStateFlow(listOf(row("Drake", ArtistGenreEntity.STATUS_TAGGED, hipHop)))
        val dao: ArtistGenreDao = mockk { every { observeAll() } returns rows }
        val repository = ArtistGenreRepository(dao, mockk<Context>(relaxed = true), backgroundScope)

        val first = repository.genreIndex.first()
        val second = repository.genreIndex.first()

        assertEquals(first, second)
        // The DAO flow is built once, in the singleton, not once per collector.
        verify(exactly = 1) { dao.observeAll() }
    }

    @Test fun `the index ignores rows that don't change any genres`() {
        val tagged = listOf(row("Drake", ArtistGenreEntity.STATUS_TAGGED, hipHop))
        assertEquals(
            ArtistGenreRepository.toIndex(tagged),
            ArtistGenreRepository.toIndex(tagged + row("Nobody", ArtistGenreEntity.STATUS_UNTAGGABLE)),
        )
    }
}
