package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.NoUserAddons
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.data.download.lossless.tidal.OwnTidalServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Settings › Lossless sources' routing rows follow the play order (add-ons plan phase 4 §2.8), so
 * that screen and Settings › Add-ons can never disagree about what is asked first.
 */
class RoutingRowsOrderTest {
    private val login = MutableStateFlow(true)
    private val tidalServer = MutableStateFlow<String?>(null)
    private val store: QbdlxCredentialStore = mockk {
        every { hasLogin } returns login
        every { connectedEmailFlow } returns MutableStateFlow("me@example.com")
    }
    private val config: LosslessConfigFetcher = mockk {
        every { tidalRelay } returns MutableStateFlow<TidalRelay?>(TidalRelay("https://tidal.stash.example", null))
    }
    private val prefs: LosslessSourcePreferences = mockk { every { bitchordEndpoint } returns tidalServer }

    private val order = MutableStateFlow(PlayOrder.Order.AUTOMATIC)
    private val off = MutableStateFlow(emptySet<String>())
    private val addons = MutableStateFlow(emptyList<InstalledAddon>())

    private val availability = LosslessAvailability(store, config, OwnTidalServer(NoUserAddons, prefs), order, off, addons)

    private fun addon(id: String, name: String, resources: List<String> = listOf("search", "stream"), firstParty: Boolean = false) = InstalledAddon(
        base = "https://$id.example/secret-path/",
        manifest = Manifest(
            stash = 1, id = id, name = name, version = "1", kind = AddonKind.SERVER, resources = resources,
            stream = StreamCapabilities(cors = false, manifests = listOf("none")),
        ),
        userAdded = !firstParty,
        firstParty = firstParty,
    )

    @Test fun `with no add-on and nothing saved - Your Qobuz, then Tidal, as before`() = runTest {
        val rows = availability.routingRows.first()
        assertThat(rows.map { it.id }).containsExactly("qobuz", "tidal").inOrder()
        assertThat(rows.map { it.label }).containsExactly("Your Qobuz account", "Tidal").inOrder()
        assertThat(rows.last().detail).isEqualTo("configured")
    }

    @Test fun `an add-on that plays songs gets a row, by its name and never its address`() = runTest {
        addons.value = listOf(
            addon("hifi", "Hi-Fi Relay"),
            addon("words", "Words", resources = listOf("lyrics")), // no place in the order
            addon("tidal", "Tidal add-on", firstParty = true),      // the Tidal row is its row
        )
        order.value = PlayOrder.effective(null, PlayOrder.keysOf(addons.value))
        val rows = availability.routingRows.first()
        assertThat(rows.map { it.label }).containsExactly("Your Qobuz account", "Tidal", "Hi-Fi Relay").inOrder()
        assertThat(rows.joinToString { it.label + it.detail }).doesNotContain("secret-path")
    }

    @Test fun `the rows follow a saved order`() = runTest {
        addons.value = listOf(addon("hifi", "Hi-Fi Relay"))
        order.value = PlayOrder.effective(listOf("addon:hifi", "tidal", "qobuz"), PlayOrder.keysOf(addons.value))
        assertThat(availability.routingRows.first().map { it.id }).containsExactly("addon:hifi", "tidal", "qobuz").inOrder()
    }

    @Test fun `Tidal switched off in Add-ons says so here too`() = runTest {
        off.value = setOf(PlayOrder.TIDAL)
        val tidal = availability.routingRows.first().single { it.id == "tidal" }
        assertThat(tidal.detail).isEqualTo("switched off")
        assertThat(tidal.state).isEqualTo(RoutingState.NOT_CONFIGURED)
    }

    @Test fun `the own-server note is for the automatic order only`() = runTest {
        login.value = false
        tidalServer.value = "https://tidal.mine.example"
        assertThat(availability.routingRows.first().single { it.id == "tidal" }.detail).isEqualTo("first above CD")
        // Once the user has dragged the order, it is the order: no claim about leading above CD.
        order.value = PlayOrder.effective(listOf("qobuz", "tidal"), emptyList())
        assertThat(availability.routingRows.first().single { it.id == "tidal" }.detail).isEqualTo("configured")
    }
}
