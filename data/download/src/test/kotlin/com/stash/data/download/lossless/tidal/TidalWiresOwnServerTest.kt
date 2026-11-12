package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import com.stash.core.addons.UserAddons
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.bitchord.LegacyRelayWire
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.TidalRelay
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * The user's own Tidal server read from the sealed entry (add-ons plan phase 4 §2.10) gives the
 * Tidal rung today's routes: the legacy wire to that server with its token, ahead of Stash's
 * relay, named "(your own server)" in diagnostics. Before the move, the old keys give the same.
 */
class TidalWiresOwnServerTest {

    private class Users(list: List<UserAddonEntry>) : UserAddons {
        override val entries: StateFlow<List<UserAddonEntry>> = MutableStateFlow(list)
    }

    private val stash = TidalRelay("https://relay.stash.example", "cfg-token")
    private val config: LosslessConfigFetcher = mockk { every { tidalRelay } returns MutableStateFlow(stash) }

    private fun prefs(endpoint: String? = null, token: String? = null): LosslessSourcePreferences = mockk {
        every { bitchordEndpoint } returns MutableStateFlow(endpoint)
        coEvery { bitchordEndpointNow() } returns endpoint
        coEvery { bitchordAccessTokenNow() } returns token
    }

    private fun wires(own: OwnTidalServer) =
        TidalWires(config, FixedRegistry(), mockk<LegacyRelayWire>(relaxed = true), mockk(relaxed = true), TidalDebugSettings.off(), own)

    private val sealed = UserAddonEntry("https://tidal.mine.example/path", "own-token", kind = UserAddonKind.OWN_TIDAL_SERVER)

    @Test fun `the sealed server is the route, with its token, ahead of Stash's relay`() = runBlocking<Unit> {
        val w = wires(OwnTidalServer(Users(listOf(sealed)), prefs()))
        val route = w.route() as TidalWires.Route.Relay
        assertThat(route.relay).isEqualTo(TidalRelay("https://tidal.mine.example/path", "own-token", userOwned = true))
        assertThat(w.describe()).isEqualTo("legacy relay (your own server)")
        assertThat(w.ownServerSet()).isTrue()
        withTimeout(5_000) { w.configured.first { it } }
    }

    @Test fun `before the move, the old keys give the same route`() = runBlocking<Unit> {
        val w = wires(OwnTidalServer(Users(emptyList()), prefs("https://tidal.mine.example/path", "own-token")))
        assertThat((w.route() as TidalWires.Route.Relay).relay)
            .isEqualTo(TidalRelay("https://tidal.mine.example/path", "own-token", userOwned = true))
    }

    @Test fun `with none, Stash's relay, and a link add-on is never taken for the own server`() = runBlocking<Unit> {
        val link = UserAddonEntry("https://tidal.mine.example/manifest.json", "t")
        val w = wires(OwnTidalServer(Users(listOf(link)), prefs()))
        assertThat((w.route() as TidalWires.Route.Relay).relay).isEqualTo(stash)
        assertThat(w.describe()).isEqualTo("legacy relay")
        assertThat(w.ownServerSet()).isFalse()
    }

    /**
     * review-882 M6. The sealed list reads empty until its first load, and after the move the old
     * key is gone: `endpoint` must not say "no server" in between, or a play or download in a
     * process's first moments is ordered as if the user had none.
     */
    @Test fun `endpoint's first value waits for the sealed store's first read`() = runBlocking<Unit> {
        val state = MutableStateFlow<List<UserAddonEntry>>(emptyList())
        val firstRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        val coldStart = object : UserAddons {
            override val entries: StateFlow<List<UserAddonEntry>> = state
            override suspend fun loadedEntries(): List<UserAddonEntry> {
                firstRead.await()
                return state.value
            }
        }
        val own = OwnTidalServer(coldStart, prefs())
        val first = async { own.endpoint.first() }
        repeat(3) { yield() }
        // Not "null, then the server": nothing at all until the store has been read.
        assertThat(first.isCompleted).isFalse()
        state.value = listOf(sealed)
        firstRead.complete(Unit)
        assertThat(withTimeout(5_000) { first.await() }).isEqualTo("https://tidal.mine.example/path")
    }

    @Test fun `endpoint follows the server being removed`() = runBlocking<Unit> {
        val state = MutableStateFlow(listOf(sealed))
        val users = object : UserAddons {
            override val entries: StateFlow<List<UserAddonEntry>> = state
        }
        val own = OwnTidalServer(users, prefs())
        assertThat(own.endpoint.first()).isEqualTo("https://tidal.mine.example/path")
        state.value = emptyList()
        assertThat(withTimeout(5_000) { own.endpoint.first { it == null } }).isNull()
    }

    /** review-882 J2: Remove is called from a `viewModelScope.launch` with no handler; a throw there is a crash. */
    @Test fun `removing the own server when the list cannot be saved is false, throws nothing and the server stays`() = runBlocking<Unit> {
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns MutableStateFlow<String?>(null)
            coEvery { bitchordEndpointNow() } returns null
            coEvery { bitchordAccessTokenNow() } returns null
            coEvery { clearBitchordServer() } returns Unit
        }
        val keystoreDown = OwnTidalServer(Users(listOf(sealed)), prefs) {
            throw com.stash.data.download.lossless.addon.EncryptedUserAddons.UnreadableException()
        }
        assertThat(keystoreDown.remove()).isFalse()
        assertThat(keystoreDown.now()).isEqualTo(OwnTidalServer.Server("https://tidal.mine.example/path", "own-token"))

        val working = OwnTidalServer(Users(listOf(sealed)), prefs) {}
        assertThat(working.remove()).isTrue()
        io.mockk.coVerify(exactly = 2) { prefs.clearBitchordServer() }
    }

    /**
     * review-882 round 2, n3: the plain keys are cleared first. The sealed entry removed and the
     * keys left behind was "false" after doing half: the next start moved the server back in.
     */
    @Test fun `removing the own server clears the plain keys first, and removes no sealed entry when they do not clear`() = runBlocking<Unit> {
        val steps = mutableListOf<String>()
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns MutableStateFlow<String?>(null)
            coEvery { bitchordEndpointNow() } returns null
            coEvery { bitchordAccessTokenNow() } returns null
            coEvery { clearBitchordServer() } answers { steps += "plain keys" }
        }
        assertThat(OwnTidalServer(Users(listOf(sealed)), prefs) { steps += "sealed entry" }.remove()).isTrue()
        assertThat(steps).containsExactly("plain keys", "sealed entry").inOrder()

        steps.clear()
        coEvery { prefs.clearBitchordServer() } throws java.io.IOException("the preferences file could not be written")
        assertThat(OwnTidalServer(Users(listOf(sealed)), prefs) { steps += "sealed entry" }.remove()).isFalse()
        assertThat(steps).isEmpty()
    }

    @Test fun `the own server's toString names neither its address nor its token`() {
        val s = OwnTidalServer.Server("https://tidal.mine.example/secret-path", "own-token").toString()
        assertThat(s).doesNotContain("secret-path")
        assertThat(s).doesNotContain("own-token")
        assertThat(sealed.toString()).doesNotContain("own-token")
        assertThat(sealed.toString()).doesNotContain("tidal.mine")
    }
}
