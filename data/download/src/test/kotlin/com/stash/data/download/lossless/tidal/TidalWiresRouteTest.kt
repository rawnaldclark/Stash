package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.NoUserAddons
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.bitchord.LegacyRelayWire
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.data.download.lossless.relay.appVersionProvider
import com.stash.data.download.lossless.relay.supporter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * [TidalWires]: which server, over which wire, a Tidal lookup goes to (plan §5.4), and what the
 * diagnostics bundle says about it. review-881 M13 found no test of `route()` or `describe()`, and
 * a "release builds honour nothing" test that used a settings object with no store at all.
 */
class TidalWiresRouteTest {

    private val ownEndpoint = MutableStateFlow<String?>(null)
    private val configRelay = MutableStateFlow<TidalRelay?>(TidalRelay(RELAY, "relay-token"))
    private val registry = FixedRegistry()

    private fun manifest(id: String = "tidal", resources: List<String> = listOf("search", "resolve", "stream", "download", "quota")) = Manifest(
        stash = 1, id = id, name = "Tidal", version = "2.0.0", kind = AddonKind.SERVER,
        resources = resources,
        stream = StreamCapabilities(cors = false, manifests = listOf("none", "dash"), tiers = listOf("high", "lossless", "hires")),
    )

    private val firstPartyTidal = InstalledAddon(base = ADDON_BASE, manifest = manifest(), firstParty = true)

    private fun wires(debug: TidalDebugSettings = TidalDebugSettings.off()): TidalWires {
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns ownEndpoint
            coEvery { bitchordEndpointNow() } answers { ownEndpoint.value }
            coEvery { bitchordAccessTokenNow() } returns "own-token"
        }
        val config: LosslessConfigFetcher = mockk {
            every { tidalRelay } returns configRelay
            every { relayKey } returns MutableStateFlow("k-secret")
            every { refreshedAt } returns MutableStateFlow(0L)
            coEvery { installId() } returns "install-1"
        }
        val quota = LosslessQuota.inMemory()
        val http = OkHttpClient()
        val addonClient = HttpAddonClient(http, TestAddonHost(), TestAddonHost(), TestAddonHost(), LocalHttp)
        val legacy = LegacyRelayWire(http, RelaySigner(config, supporter(), appVersionProvider()), quota)
        return TidalWires(config, registry, legacy, AddonV1Wire(addonClient, quota), debug, OwnTidalServer(NoUserAddons, prefs))
    }

    // ------------------------------------------------------------------------------------------ route()

    @Test fun `the order - the user's own server, then the Tidal add-on, then the config's relay, then nothing`() = runBlocking<Unit> {
        val wires = wires()
        ownEndpoint.value = OWN
        registry.state.value = listOf(firstPartyTidal)

        // 1. The user's own server, with its own token, on the legacy wire: even with the add-on loaded.
        val own = wires.route() as TidalWires.Route.Relay
        assertThat(own.relay).isEqualTo(TidalRelay(OWN, "own-token", userOwned = true))

        // 2. Without it: the first-party Tidal add-on, on the v1 wire.
        ownEndpoint.value = null
        assertThat((wires.route() as TidalWires.Route.Addon).addon).isSameInstanceAs(firstPartyTidal)
        assertThat(wires.current { 0L }!!.kind).isEqualTo(TidalWire.Kind.V1)

        // 3. The entry removed from the signed config (the registry drops the add-on): back to the relay.
        registry.state.value = emptyList()
        val relay = wires.route() as TidalWires.Route.Relay
        assertThat(relay.relay).isEqualTo(TidalRelay(RELAY, "relay-token"))
        assertThat(relay.relay.userOwned).isFalse()
        assertThat(wires.current { 0L }!!.kind).isEqualTo(TidalWire.Kind.LEGACY)

        // 4. No relay in the config either: the rung is off.
        configRelay.value = null
        assertThat(wires.route()).isNull()
        assertThat(wires.current { 0L }).isNull()
    }

    @Test fun `ownServer false skips the user's own server`() = runBlocking<Unit> {
        val wires = wires()
        ownEndpoint.value = OWN
        assertThat((wires.route(ownServer = false) as TidalWires.Route.Relay).relay.userOwned).isFalse()
        registry.state.value = listOf(firstPartyTidal)
        assertThat(wires.route(ownServer = false)).isInstanceOf(TidalWires.Route.Addon::class.java)
    }

    @Test fun `a Tidal add-on that can't search and stream does not take the rung`() = runBlocking<Unit> {
        val wires = wires()
        for (resources in listOf(listOf("search", "resolve", "quota"), listOf("stream", "download"), listOf("lyrics"))) {
            registry.state.value = listOf(InstalledAddon(base = ADDON_BASE, manifest = manifest(resources = resources), firstParty = true))
            assertThat(wires.tidalAddon()).isNull()
            assertThat((wires.route() as TidalWires.Route.Relay).relay.base).isEqualTo(RELAY)
        }
    }

    @Test fun `only Stash's own add-on with the id tidal takes the rung`() = runBlocking<Unit> {
        val wires = wires()
        // A link the user added that calls itself "tidal", and a first-party add-on with another id.
        registry.state.value = listOf(
            InstalledAddon(base = "https://stranger.example/", manifest = manifest(), userAdded = true),
            InstalledAddon(base = "https://other.example/", manifest = manifest(id = "other"), firstParty = true),
        )
        assertThat(wires.tidalAddon()).isNull()
        assertThat((wires.route() as TidalWires.Route.Relay).relay.base).isEqualTo(RELAY)
    }

    // ------------------------------------------------------------------------------------------ the debug instruments

    /**
     * A store holding a forced wire and an override origin, written by a build that honours them, and
     * read back by it. In memory, and every settings object follows it on the test's own dispatcher:
     * nothing here waits in real time (a 5 s wait on a real store's IO thread timed out on CI,
     * review-888 round 2).
     */
    private suspend fun TestScope.storedInstruments(): MemoryPreferencesStore {
        val store = MemoryPreferencesStore()
        val debugBuild = TidalDebugSettings(store, honored = true, scope = backgroundScope)
        debugBuild.setWire(TidalDebugSettings.Wire.LEGACY)
        assertThat(debugBuild.setOrigin("https://staging.example")).isEqualTo("https://staging.example")
        runCurrent()
        // Proof that the store holds both and that an honouring build reads them.
        assertThat(debugBuild.state.value).isEqualTo(TidalDebugSettings.State(TidalDebugSettings.Wire.LEGACY, "https://staging.example"))
        return store
    }

    @Test fun `a debug build honours a stored forced wire and override origin`() = runTest {
        val debug = TidalDebugSettings(storedInstruments(), honored = true, scope = backgroundScope)
        runCurrent()
        assertThat(debug.state.value).isEqualTo(TidalDebugSettings.State(TidalDebugSettings.Wire.LEGACY, "https://staging.example"))
        val wires = wires(debug)
        registry.state.value = listOf(firstPartyTidal)
        // Forced legacy: the add-on is passed over, and the override's origin stands in for the config's relay.
        val relay = (wires.route() as TidalWires.Route.Relay).relay
        assertThat(relay).isEqualTo(TidalRelay("https://staging.example", "relay-token"))
        assertThat(wires.describe()).isEqualTo("legacy relay (forced, debug server override)")
    }

    @Test fun `a release build ignores the same stored values - the config decides`() = runTest {
        val store = storedInstruments()
        val release = TidalDebugSettings(store, honored = false, scope = backgroundScope)
        val wires = wires(release)
        registry.state.value = listOf(firstPartyTidal)
        // It has read the store (all there is to run has run), and still says the defaults.
        runCurrent()
        assertThat(store.reads).isAtLeast(2)
        assertThat(release.state.value).isEqualTo(TidalDebugSettings.State())
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
        assertThat(wires.ownerRelay()).isEqualTo(TidalRelay(RELAY, "relay-token"))
        assertThat(release.relay(configRelay.value)).isEqualTo(configRelay.value)
        assertThat(release.addonEntry(configRelay.value)).isNull()
        assertThat(wires.describe()).isEqualTo("v1 add-on")
        registry.state.value = emptyList()
        assertThat((wires.route() as TidalWires.Route.Relay).relay.base).isEqualTo(RELAY)
        assertThat(wires.describe()).isEqualTo("legacy relay")
    }

    @Test fun `a release build stores nothing when asked to set an instrument`() = runTest {
        val store = MemoryPreferencesStore()
        val release = TidalDebugSettings(store, honored = false, scope = backgroundScope)
        release.setWire(TidalDebugSettings.Wire.V1)
        assertThat(release.setOrigin("https://staging.example")).isNull()
        assertThat(store.values.value.asMap()).isEmpty()
    }

    /**
     * A read of the store that fails used to end the settings' flow (`catch` emitted the defaults and
     * completed): a debug build then ignored what was set until the process died. Now the store is
     * read again after a pause, followed from then on, and the failure is said once.
     */
    @Test fun `a store that can't be read is read again and followed from then on, said once`() = runTest {
        val store = MemoryPreferencesStore(storedInstruments().values.value).apply { failReads = 2 }
        val said = mutableListOf<String>()
        val debug = TidalDebugSettings(store, honored = true, scope = backgroundScope, warn = { said += it })
        runCurrent()
        assertThat(debug.state.value).isEqualTo(TidalDebugSettings.State()) // unreadable: nothing set, for now

        advanceTimeBy(TidalDebugSettings.retryDelayMs(0))
        runCurrent()
        assertThat(debug.state.value).isEqualTo(TidalDebugSettings.State()) // the second read fails too

        advanceTimeBy(TidalDebugSettings.retryDelayMs(1))
        runCurrent()
        assertThat(debug.state.value).isEqualTo(TidalDebugSettings.State(TidalDebugSettings.Wire.LEGACY, "https://staging.example"))
        // And followed: a change made now shows.
        debug.setWire(TidalDebugSettings.Wire.V1)
        runCurrent()
        assertThat(debug.state.value.wire).isEqualTo(TidalDebugSettings.Wire.V1)
        assertThat(said).hasSize(1)
        assertThat(said.single()).doesNotContain("staging")
    }

    // ------------------------------------------------------------------------------------------ describe()

    @Test fun `the diagnostics line is one of four phrases and never a base or a token`() = runBlocking<Unit> {
        val wires = wires()
        val seen = mutableListOf<String>()

        registry.state.value = listOf(firstPartyTidal)
        seen += wires.describe().also { assertThat(it).isEqualTo("v1 add-on") }

        registry.state.value = emptyList()
        seen += wires.describe().also { assertThat(it).isEqualTo("legacy relay") }

        ownEndpoint.value = OWN
        seen += wires.describe().also { assertThat(it).isEqualTo("legacy relay (your own server)") }

        ownEndpoint.value = null
        configRelay.value = null
        seen += wires.describe().also { assertThat(it).isEqualTo("off") }

        for (line in seen) {
            for (secret in listOf("example", "https", "token", "tok-", "/v1/")) assertThat(line).doesNotContain(secret)
        }
        // And the route's own text, which can reach a log, names neither.
        configRelay.value = TidalRelay(RELAY, "relay-token")
        assertThat(wires.route().toString()).isEqualTo("Relay(userOwned=false)")
    }

    private companion object {
        const val OWN = "https://mine.example/secret-path"
        const val RELAY = "https://relay.stash.example"
        const val ADDON_BASE = "https://tidal.example.workers.dev/v1/tok-123/"
    }
}
