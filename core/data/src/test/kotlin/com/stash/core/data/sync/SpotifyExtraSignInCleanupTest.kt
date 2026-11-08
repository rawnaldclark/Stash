package com.stash.core.data.sync

import com.stash.core.auth.TokenManager
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.data.db.dao.SpotifyAccountDao
import com.stash.core.data.db.entity.SpotifyAccountEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extra Spotify sign-ins whose account row is gone are forgotten at app start; the rest stay. */
class SpotifyExtraSignInCleanupTest {

    private val tokenStore: EncryptedTokenStore = mockk()
    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val dao: SpotifyAccountDao = mockk()

    private fun row(id: Long, userId: String) = SpotifyAccountEntity(id = id, userId = userId, label = userId, addedAt = id)

    private val cleanup = SpotifyExtraSignInCleanup(tokenStore, tokenManager, dao)

    @Test fun `a sign-in with no account row is forgotten, one with a row stays`() = runTest {
        coEvery { tokenStore.spotifyExtraRowIds() } returns setOf(1L, 2L, 7L)
        coEvery { dao.getAll() } returns listOf(row(2, "sam"), row(3, "alex"))

        val cleared = cleanup.clearOrphans()

        assertEquals(setOf(1L, 7L), cleared)
        coVerify(exactly = 1) { tokenManager.clearSpotifyExtra(1L) }
        coVerify(exactly = 1) { tokenManager.clearSpotifyExtra(7L) }
        coVerify(exactly = 0) { tokenManager.clearSpotifyExtra(2L) }
        coVerify(exactly = 0) { tokenManager.clearSpotifyExtra(3L) }
    }

    @Test fun `with no stored sign-in, the database isn't read and nothing is cleared`() = runTest {
        coEvery { tokenStore.spotifyExtraRowIds() } returns emptySet()

        assertEquals(emptySet<Long>(), cleanup.clearOrphans())
        coVerify(exactly = 0) { dao.getAll() }
        coVerify(exactly = 0) { tokenManager.clearSpotifyExtra(any()) }
    }

    @Test fun `every sign-in with its row keeps it`() = runTest {
        coEvery { tokenStore.spotifyExtraRowIds() } returns setOf(2L)
        coEvery { dao.getAll() } returns listOf(row(2, "sam"))

        assertEquals(emptySet<Long>(), cleanup.clearOrphans())
        coVerify(exactly = 0) { tokenManager.clearSpotifyExtra(any()) }
    }

    @Test fun `the sign-ins are read before the rows, so one being added is never taken for an orphan`() = runTest {
        // Adding writes the row first, then its sign-in: read the other way round, a sign-in saved
        // between the two reads would have no row in the older read.
        coEvery { tokenStore.spotifyExtraRowIds() } returns setOf(4L)
        coEvery { dao.getAll() } returns listOf(row(4, "sam"))

        cleanup.clearOrphans()

        coVerifyOrder {
            tokenStore.spotifyExtraRowIds()
            dao.getAll()
        }
    }

    @Test fun `an unreadable database clears nothing`() = runTest {
        coEvery { tokenStore.spotifyExtraRowIds() } returns setOf(1L)
        coEvery { dao.getAll() } throws IllegalStateException("database locked")

        val thrown = runCatching { cleanup.clearOrphans() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        coVerify(exactly = 0) { tokenManager.clearSpotifyExtra(any()) }
    }
}
