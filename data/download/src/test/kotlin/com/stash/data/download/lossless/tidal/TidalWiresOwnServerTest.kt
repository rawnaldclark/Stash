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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun `the own server's toString names neither its address nor its token`() {
        val s = OwnTidalServer.Server("https://tidal.mine.example/secret-path", "own-token").toString()
        assertThat(s).doesNotContain("secret-path")
        assertThat(s).doesNotContain("own-token")
        assertThat(sealed.toString()).doesNotContain("own-token")
        assertThat(sealed.toString()).doesNotContain("tidal.mine")
    }
}
