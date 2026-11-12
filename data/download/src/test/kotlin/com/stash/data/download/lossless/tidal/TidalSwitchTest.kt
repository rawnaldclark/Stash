package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import com.stash.core.addons.UserAddons
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.PlayOrderSwitches
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
 * Tidal's switch on its row in Settings › Add-ons (add-ons plan phase 4 §2.8): off, the rung has no
 * route at all. It must not fall through from the user's own server to Stash's relay, and it must
 * not leave the player thinking a Tidal server is there.
 */
class TidalSwitchTest {

    private class Users(list: List<UserAddonEntry>) : UserAddons {
        override val entries: StateFlow<List<UserAddonEntry>> = MutableStateFlow(list)
    }

    private val stash = TidalRelay("https://relay.stash.example", "cfg-token")
    private val config: LosslessConfigFetcher = mockk { every { tidalRelay } returns MutableStateFlow(stash) }

    private val prefs: LosslessSourcePreferences = mockk {
        every { bitchordEndpoint } returns MutableStateFlow(null)
        coEvery { bitchordEndpointNow() } returns null
        coEvery { bitchordAccessTokenNow() } returns null
    }

    private val off = MutableStateFlow(emptySet<String>())
    private val switches = PlayOrderSwitches(off) { key, isOff -> off.value = if (isOff) off.value + key else off.value - key }

    private fun wires(entries: List<UserAddonEntry> = emptyList()) = TidalWires(
        config, FixedRegistry(), mockk<LegacyRelayWire>(relaxed = true), mockk(relaxed = true), TidalDebugSettings.off(),
        OwnTidalServer(Users(entries), prefs), switches,
    )

    @Test fun `switched off, Stash's Tidal has no route, and back on it has`() = runBlocking<Unit> {
        val w = wires()
        assertThat((w.route() as TidalWires.Route.Relay).relay).isEqualTo(stash)
        withTimeout(5_000) { w.configured.first { it } }

        switches.setOn(PlayOrder.TIDAL, false)
        assertThat(w.route()).isNull()
        assertThat(w.current { 0L }).isNull()
        assertThat(w.describe()).isEqualTo("off (switched off in Add-ons)")
        withTimeout(5_000) { w.configured.first { !it } }
        // Its row stays on screen for as long as a server is known, so the switch can be flipped back.
        assertThat(w.known.first()).isTrue()
        assertThat((w.routeIfOn() as TidalWires.Route.Relay).relay).isEqualTo(stash)

        switches.setOn(PlayOrder.TIDAL, true)
        assertThat(w.route()).isNotNull()
        withTimeout(5_000) { w.configured.first { it } }
    }

    @Test fun `switched off with the user's own server set, it does not fall through to Stash's relay`() = runBlocking<Unit> {
        val own = UserAddonEntry("https://tidal.mine.example/path", "own-token", kind = UserAddonKind.OWN_TIDAL_SERVER)
        val w = wires(listOf(own))
        assertThat((w.route() as TidalWires.Route.Relay).relay.userOwned).isTrue()
        switches.setOn(PlayOrder.TIDAL, false)
        assertThat(w.route()).isNull()
        assertThat(w.route(ownServer = false)).isNull()
    }

    @Test fun `another row's switch leaves Tidal on`() = runBlocking<Unit> {
        val w = wires()
        switches.setOn("addon:builtin", false)
        assertThat(w.route()).isNotNull()
    }
}
