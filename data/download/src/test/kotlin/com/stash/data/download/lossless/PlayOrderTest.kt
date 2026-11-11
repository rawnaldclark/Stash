package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.InstalledAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The play order (add-ons plan phase 4 §2.8): one order for both chains, automatic until the first drag. */
class PlayOrderTest {

    private fun addon(id: String, userAdded: Boolean = false, firstParty: Boolean = false, resources: List<String> = listOf("search", "stream")) =
        InstalledAddon(
            base = "https://$id.example/",
            manifest = Manifest(
                stash = 1, id = id, name = id, version = "1", kind = AddonKind.SERVER, resources = resources,
                stream = StreamCapabilities(cors = false, manifests = listOf("none")),
            ),
            userAdded = userAdded,
            firstParty = firstParty,
        )

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        val state = MutableStateFlow(list)
        override val addons: StateFlow<List<InstalledAddon>> get() = state
    }

    private class Saved(initial: List<String>? = null) {
        val flow = MutableStateFlow(initial)
        suspend fun save(keys: List<String>?) { flow.value = keys }
    }

    private fun order(saved: Saved, registry: Registry) = PlayOrder(saved.flow, saved::save, registry)

    // ------------------------------------------------------------------------------------------ automatic

    @Test fun `with nothing saved - Your Qobuz, Tidal, the user's add-ons as added, then the defaults`() = runTest {
        // The registry's own order: the user's first, then the defaults.
        val registry = Registry(listOf(addon("mine", userAdded = true), addon("second", userAdded = true), addon("builtin")))
        val now = order(Saved(), registry).now()
        assertThat(now.automatic).isTrue()
        // Owner decision Q3: a new add-on goes AFTER Stash's own lossless sources.
        assertThat(now.keys).containsExactly("qobuz", "tidal", "addon:mine", "addon:second", "addon:builtin").inOrder()
        assertThat(now.tidalBeforeQobuz).isFalse()
    }

    @Test fun `the first-party Tidal add-on is the Tidal row, and a lyrics-only add-on has no place`() = runTest {
        val registry = Registry(listOf(addon("words", userAdded = true, resources = listOf("lyrics")), addon("tidal", firstParty = true)))
        assertThat(order(Saved(), registry).now().keys).containsExactly("qobuz", "tidal").inOrder()
    }

    @Test fun `no add-on on, nothing saved - the automatic order`() = runTest {
        assertThat(order(Saved(), Registry(emptyList())).now()).isEqualTo(PlayOrder.Order.AUTOMATIC)
    }

    // ------------------------------------------------------------------------------------------ saved

    @Test fun `a saved order is the order, and an add-on it doesn't know yet goes last`() = runTest {
        val registry = Registry(listOf(addon("mine", userAdded = true), addon("new", userAdded = true)))
        val now = order(Saved(listOf("addon:mine", "tidal", "qobuz")), registry).now()
        assertThat(now.automatic).isFalse()
        assertThat(now.keys).containsExactly("addon:mine", "tidal", "qobuz", "addon:new").inOrder()
        assertThat(now.tidalBeforeQobuz).isTrue()
    }

    @Test fun `a saved order keeps the place of an add-on that is switched off`() = runTest {
        // Off: not in the registry. Its key stays where the user put it, for when it is back on.
        val registry = Registry(listOf(addon("other", userAdded = true)))
        val saved = Saved(listOf("addon:mine", "qobuz", "tidal", "addon:other"))
        val playOrder = order(saved, registry)
        assertThat(playOrder.now().keys).containsExactly("addon:mine", "qobuz", "tidal", "addon:other").inOrder()
        registry.state.value = listOf(addon("mine", userAdded = true), addon("other", userAdded = true))
        assertThat(playOrder.order.first().keys.first()).isEqualTo("addon:mine")
    }

    @Test fun `a saved order that lost a named row gets it back at the top`() {
        assertThat(PlayOrder.effective(listOf("addon:a", "tidal"), listOf("addon:a")).keys)
            .containsExactly("qobuz", "addon:a", "tidal").inOrder()
    }

    @Test fun `removing an add-on forgets its place, and does nothing while automatic`() = runTest {
        val saved = Saved(listOf("addon:mine", "qobuz", "tidal"))
        val playOrder = order(saved, Registry(emptyList()))
        playOrder.forget("addon:mine")
        assertThat(saved.flow.value).containsExactly("qobuz", "tidal").inOrder()

        val automatic = Saved()
        order(automatic, Registry(emptyList())).forget("addon:mine")
        assertThat(automatic.flow.value).isNull()
    }

    // ------------------------------------------------------------------------------------------ a drag

    @Test fun `a drag moves the row among the rows on screen and leaves the hidden ones where they are`() {
        // Your Qobuz isn't on screen (no account connected); the user drags the add-on above Tidal.
        val order = listOf("qobuz", "tidal", "addon:mine")
        val shown = listOf("tidal", "addon:mine")
        assertThat(PlayOrder.moved(order, shown, "addon:mine", 0)).containsExactly("qobuz", "addon:mine", "tidal").inOrder()
    }

    @Test fun `a drag to the same place, or of a row that isn't shown, moves nothing`() {
        val order = listOf("qobuz", "tidal", "addon:mine")
        assertThat(PlayOrder.moved(order, order, "tidal", 1)).isNull()
        assertThat(PlayOrder.moved(order, order, "addon:gone", 0)).isNull()
        assertThat(PlayOrder.moved(order, order, "tidal", 7)).isNull()
    }

    @Test fun `a drag across several rows is one move`() {
        val order = listOf("qobuz", "tidal", "addon:a", "addon:b")
        assertThat(PlayOrder.moved(order, order, "addon:b", 0)).containsExactly("addon:b", "qobuz", "tidal", "addon:a").inOrder()
        assertThat(PlayOrder.moved(order, order, "qobuz", 3)).containsExactly("tidal", "addon:a", "addon:b", "qobuz").inOrder()
    }

    // ------------------------------------------------------------------------------------------ both chains read one order

    @Test fun `the download sources map onto the same keys the stream rungs use`() {
        assertThat(PlayOrder.keyOfSource("qbdlx_qobuz")).isEqualTo(PlayOrder.QOBUZ)
        assertThat(PlayOrder.keyOfSource("tidal_bitchord")).isEqualTo(PlayOrder.TIDAL)
        assertThat(PlayOrder.keyOfSource("addon:mine")).isEqualTo(PlayOrder.addonKey("mine"))
        assertThat(PlayOrder.keyOfSource("lucida")).isNull()
    }

    @Test fun `sorting keeps what the order doesn't name last, in the order given`() {
        val order = PlayOrder.Order(listOf("addon:b", "qobuz", "tidal"), automatic = false)
        assertThat(order.sorted(listOf("x", "tidal", "y", "addon:b", "qobuz")) { it }).containsExactly("addon:b", "qobuz", "tidal", "x", "y").inOrder()
    }

    @Test fun `the switches name the default add-ons that are off by id, and Tidal by its key`() = runTest {
        val off = MutableStateFlow(setOf("tidal", "addon:builtin"))
        val switches = PlayOrderSwitches(off) { key, isOff -> off.value = if (isOff) off.value + key else off.value - key }
        assertThat(switches.isOff(PlayOrder.TIDAL)).isTrue()
        assertThat(switches.defaultsOff.first()).containsExactly("builtin")
        switches.setOn(PlayOrder.TIDAL, true)
        assertThat(switches.isOff(PlayOrder.TIDAL)).isFalse()
    }
}
