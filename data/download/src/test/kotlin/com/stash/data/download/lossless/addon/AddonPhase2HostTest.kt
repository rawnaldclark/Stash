package com.stash.data.download.lossless.addon

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.DefaultAddonEntry
import com.stash.core.addons.DefaultAddons
import com.stash.core.addons.UserAddonEntry
import com.stash.data.download.lossless.relay.LosslessConfig
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.data.download.lossless.tidal.TidalDebugSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Add-ons phase 2 on the host side: the signed config's `default_addons` (the D9 switch), the
 * trust it extends, the debug instruments' origin rule, and the two stores (kept manifests, the
 * user's encrypted add-ons).
 */
class AddonPhase2HostTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = Files.createTempDirectory("addons-p2").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun config(defaults: String): LosslessConfig =
        json.decodeFromString(LosslessConfig.serializer(), """{"updated_at":1,"default_addons":$defaults}""")

    @Test fun `default_addons - https manifest links with boolean flags, in order`() {
        val list = config(
            """[
              {"manifest":"https://tidal.example.workers.dev/v1/tok/manifest.json","enabled":true,"first_party":true},
              {"manifest":"https://other.example/manifest.json"}
            ]""",
        ).defaultAddonEntries()
        assertThat(list).containsExactly(
            DefaultAddonEntry("https://tidal.example.workers.dev/v1/tok/manifest.json", enabled = true, firstParty = true),
            DefaultAddonEntry("https://other.example/manifest.json", enabled = true, firstParty = false),
        ).inOrder()
    }

    @Test fun `default_addons - a bad entry is dropped alone, never the config`() {
        val list = config(
            """[
              {"manifest":"http://plain.example/manifest.json"},
              {"manifest":"https://q.example/manifest.json?x=1"},
              {"manifest":"https://f.example/manifest.json#a"},
              {"manifest":"https://user:pw@c.example/manifest.json"},
              {"manifest":"https://notmanifest.example/addon.json"},
              {"manifest":"https://flag.example/manifest.json","enabled":"yes"},
              {"manifest":"https://fp.example/manifest.json","first_party":1},
              {"manifest":42},
              "https://string.example/manifest.json",
              {"manifest":"https://ok.example/manifest.json","enabled":false},
              {"manifest":"https://ok.example/manifest.json"}
            ]""",
        ).defaultAddonEntries()
        assertThat(list).containsExactly(DefaultAddonEntry("https://ok.example/manifest.json", enabled = false, firstParty = false))
    }

    @Test fun `default_addons - absent, not a list, or more than 16`() {
        assertThat(json.decodeFromString(LosslessConfig.serializer(), """{"updated_at":1}""").defaultAddonEntries()).isEmpty()
        assertThat(config("""{"manifest":"https://a.example/manifest.json"}""").defaultAddonEntries()).isEmpty()
        val many = (1..20).joinToString(",", "[", "]") { """{"manifest":"https://a$it.example/manifest.json"}""" }
        assertThat(config(many).defaultAddonEntries()).hasSize(LosslessConfig.MAX_DEFAULT_ADDONS)
    }

    private class Defaults(list: List<DefaultAddonEntry>) : DefaultAddons {
        override val entries: StateFlow<List<DefaultAddonEntry>> = MutableStateFlow(list)
    }

    @Test fun `first-party trust - an enabled first_party default by its base, nothing else`() {
        val relay = MutableStateFlow<TidalRelay?>(null)
        val config = mockk<LosslessConfigFetcher> { every { tidalRelay } returns relay }
        val trust = SignedConfigAddonTrust(
            config,
            Defaults(
                listOf(
                    DefaultAddonEntry("https://tidal.example.workers.dev/v1/tok/manifest.json", enabled = true, firstParty = true),
                    DefaultAddonEntry("https://third.example/manifest.json", enabled = true, firstParty = false),
                    DefaultAddonEntry("https://off.example/manifest.json", enabled = false, firstParty = true),
                ),
            ),
        )
        assertThat(trust.isFirstParty("https://tidal.example.workers.dev/v1/tok/")).isTrue()
        assertThat(trust.isFirstParty("https://TIDAL.example.workers.dev:443/v1/tok/")).isTrue()
        assertThat(trust.isFirstParty("https://tidal.example.workers.dev/v1/other/")).isFalse()
        assertThat(trust.isFirstParty("https://third.example/")).isFalse()
        assertThat(trust.isFirstParty("https://off.example/")).isFalse()
        // The relay rule still stands beside it; a user's own server never counts.
        relay.value = TidalRelay("https://relay.stash.example", null)
        assertThat(trust.isFirstParty("https://relay.stash.example/addon/")).isTrue()
        relay.value = TidalRelay("https://mine.example", null, userOwned = true)
        assertThat(trust.isFirstParty("https://mine.example/")).isFalse()
    }

    @Test fun `debug override - origins only, https or local http`() {
        assertThat(TidalDebugSettings.normaliseOrigin("http://127.0.0.1:8797")).isEqualTo("http://127.0.0.1:8797")
        assertThat(TidalDebugSettings.normaliseOrigin(" https://staging.example/ ")).isEqualTo("https://staging.example")
        assertThat(TidalDebugSettings.normaliseOrigin("http://localhost:8797")).isEqualTo("http://localhost:8797")
        assertThat(TidalDebugSettings.normaliseOrigin("http://staging.example")).isNull()
        assertThat(TidalDebugSettings.normaliseOrigin("https://staging.example/v1/tok")).isNull()
        assertThat(TidalDebugSettings.normaliseOrigin("https://u:p@staging.example")).isNull()
        assertThat(TidalDebugSettings.normaliseOrigin("https://staging.example?x=1")).isNull()
    }

    @Test fun `a release build honours nothing - the config's relay and no stand-in add-on`() {
        val off = TidalDebugSettings.off()
        val relay = TidalRelay("https://relay.stash.example", "tok")
        assertThat(off.relay(relay)).isEqualTo(relay)
        assertThat(off.addonEntry(relay)).isNull()
        assertThat(off.state.value).isEqualTo(TidalDebugSettings.State())
    }

    @Test fun `kept manifests - read back, and forgotten when their entry goes`() = runBlocking {
        val store = DataStoreAddonManifestStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "m.preferences_pb") })
        store.write("aa", """{"id":"tidal"}""")
        store.write("bb", """{"id":"other"}""")
        assertThat(store.read("aa")).isEqualTo("""{"id":"tidal"}""")
        store.retain(setOf("aa"))
        assertThat(store.read("aa")).isNotNull()
        assertThat(store.read("bb")).isNull()
    }

    /** Not encryption: a reversible scramble that proves the plaintext never reaches the file. */
    private object Scramble : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private object Broken : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray): ByteArray = throw java.security.GeneralSecurityException("key lost")
    }

    @Test fun `user add-ons - sealed on disk, read back by the next run, unreadable reads as none`() = runBlocking {
        val file = File(dir, "u.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
        val users = EncryptedUserAddons(ds, Scramble)
        users.put(UserAddonEntry("https://mine.example/secret-path/manifest.json", token = "hdr-token"))
        users.put(UserAddonEntry("https://two.example/manifest.json"))
        users.setEnabled("https://two.example/manifest.json", false)
        assertThat(users.entries.value.map { it.manifestUrl to it.enabled }).containsExactly(
            "https://mine.example/secret-path/manifest.json" to true,
            "https://two.example/manifest.json" to false,
        ).inOrder()

        val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
        assertThat(onDisk).doesNotContain("secret-path")
        assertThat(onDisk).doesNotContain("hdr-token")

        val nextRun = EncryptedUserAddons(ds, Scramble)
        withTimeout(5_000) { nextRun.entries.first { it.size == 2 } }
        assertThat(nextRun.entries.value.first().token).isEqualTo("hdr-token")

        val lostKey = EncryptedUserAddons(ds, Broken)
        lostKey.remove("nothing") // waits for the load
        assertThat(lostKey.entries.value).isEmpty()
    }
}
