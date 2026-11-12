package com.stash.data.download.lossless.addon

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonTrust
import com.stash.core.addons.BlockReason
import com.stash.core.addons.DefaultAddonEntry
import com.stash.core.addons.DefaultAddons
import com.stash.core.addons.Trust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/**
 * The trust table (add-ons plan phase 4 §1.6, §1.9): first-party, official, not made by Stash and
 * turned off by Stash, decided only by the signed config and the signed index, and compared as
 * URLs (host case and default ports never matter). Over the publisher's `valid` fixture.
 */
class SignedIndexAddonCatalogTest {

    private lateinit var server: MockWebServer
    private val dir: File = Files.createTempDirectory("addon-catalog").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun fixture(path: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("addon-index-fixtures/$path")).use { it.readBytes() }

    @Before fun setUp() { server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) } }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private class Defaults(list: List<DefaultAddonEntry>) : DefaultAddons {
        override val entries: StateFlow<List<DefaultAddonEntry>> = MutableStateFlow(list)
    }

    private val tidalBase = "https://tidal.example.workers.dev/v1/tok/"

    /** First-party as the signed config decides it: here, the Tidal add-on's base. */
    private val firstParty = object : AddonTrust {
        override fun isFirstParty(base: String) = SignedConfigAddonTrust.sameBase(base, tidalBase)
    }

    private val defaults = Defaults(
        listOf(
            DefaultAddonEntry("${tidalBase}manifest.json", firstParty = true),
            DefaultAddonEntry("https://other-default.example/manifest.json"),
            // A default on an origin the index blocks: a block never reaches a default.
            DefaultAddonEntry("https://broken.example:8443/d/manifest.json"),
            DefaultAddonEntry("https://switched-off-in-config.example/manifest.json", enabled = false),
        ),
    )

    /** A catalog over the fixture [case] (null: no index at all), for an app at [build]. */
    private fun catalog(case: String? = "valid", build: Long = 160): SignedIndexAddonCatalog = runBlocking {
        val fetcher = AddonIndexFetcher(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir, "${case ?: "none"}-$build.preferences_pb") }, OkHttpClient(), false,
        )
        if (case != null) {
            fetcher.publicKeyB64 = String(fixture("pubkey.b64")).trim()
            fetcher.configUrl = "http://127.0.0.1:${server.port}/index.json"
            server.enqueue(MockResponse().setBody(Buffer().write(fixture("$case/index.json"))))
            server.enqueue(MockResponse().setBody(Buffer().write(fixture("$case/index.json.sig"))))
            check(fetcher.refresh())
        }
        SignedIndexAddonCatalog(fetcher, defaults, firstParty, build)
    }

    @Test fun `first-party and the config's defaults`() {
        val c = catalog()
        assertThat(c.trustOf(tidalBase, "tidal")).isEqualTo(Trust.FIRST_PARTY)
        assertThat(c.trustOf("https://TIDAL.example.workers.dev:443/v1/tok/", null)).isEqualTo(Trust.FIRST_PARTY)
        // Any enabled default is Official; being listed is not being first-party.
        assertThat(c.trustOf("https://other-default.example/", null)).isEqualTo(Trust.OFFICIAL)
        assertThat(c.trustOf("https://Other-Default.example:443/", "anything")).isEqualTo(Trust.OFFICIAL)
        // One the config switched off is no default.
        assertThat(c.trustOf("https://switched-off-in-config.example/", null)).isEqualTo(Trust.UNOFFICIAL)
        // Another path on the first-party origin is not the first-party add-on.
        assertThat(c.trustOf("https://tidal.example.workers.dev/v1/other/", null)).isEqualTo(Trust.UNOFFICIAL)
    }

    @Test fun `official by the signed index, compared as URLs`() {
        val c = catalog()
        assertThat(c.trustOf("https://addons.example.org/radio/", "example-radio")).isEqualTo(Trust.OFFICIAL)
        assertThat(c.trustOf("https://ADDONS.example.org:443/radio/", null)).isEqualTo(Trust.OFFICIAL)
        assertThat(c.trustOf("https://sixty.example.org:8443/", "org.example.sixty")).isEqualTo(Trust.OFFICIAL)
        // Not the listed manifest: another path, another port, plain http, or no final slash.
        assertThat(c.trustOf("https://addons.example.org/other/", null)).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.trustOf("https://addons.example.org/", null)).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.trustOf("https://sixty.example.org/", null)).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.trustOf("http://addons.example.org/radio/", null)).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.trustOf("https://addons.example.org/radio", null)).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.trustOf("https://somewhere-else.example/", "example-radio")).isEqualTo(Trust.UNOFFICIAL)
    }

    @Test fun `turned off by Stash - by origin, or by origin and id`() {
        val c = catalog()
        // An id on an origin: that add-on only.
        assertThat(c.trustOf("https://bad.example/x/", "bad-addon")).isEqualTo(Trust.BLOCKED)
        assertThat(c.blockReason("https://bad.example/x/", "bad-addon")).isEqualTo(BlockReason.HARMFUL)
        assertThat(c.trustOf("https://BAD.example:443/any/path/", "bad-addon")).isEqualTo(Trust.BLOCKED)
        assertThat(c.trustOf("https://bad.example/x/", "another")).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.trustOf("https://bad.example/x/", null)).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.blockReason("https://bad.example/x/", null)).isNull()
        // A whole origin: every add-on on it, id known or not. The port is part of an origin.
        assertThat(c.trustOf("https://broken.example:8443/a/", "anything")).isEqualTo(Trust.BLOCKED)
        assertThat(c.trustOf("https://broken.example:8443/a/", null)).isEqualTo(Trust.BLOCKED)
        assertThat(c.blockReason("https://broken.example:8443/a/", null)).isEqualTo(BlockReason.BROKEN)
        assertThat(c.trustOf("https://broken.example/a/", "anything")).isEqualTo(Trust.UNOFFICIAL)
        // Something calling itself "tidal" somewhere else.
        assertThat(c.blockReason("https://fake-stash.example/", "tidal")).isEqualTo(BlockReason.IMPERSONATION)
    }

    @Test fun `a block never reaches a default or anything first-party`() {
        val c = catalog()
        assertThat(c.trustOf("https://broken.example:8443/d/", "anything")).isEqualTo(Trust.OFFICIAL)
        assertThat(c.blockReason("https://broken.example:8443/d/", "anything")).isNull()
        assertThat(c.blockReason(tidalBase, "tidal")).isNull()
    }

    @Test fun `with no index nothing is official beyond the config, and nothing is blocked`() {
        val c = catalog(case = null)
        assertThat(c.trustOf(tidalBase, "tidal")).isEqualTo(Trust.FIRST_PARTY)
        assertThat(c.trustOf("https://other-default.example/", null)).isEqualTo(Trust.OFFICIAL)
        assertThat(c.trustOf("https://addons.example.org/radio/", "example-radio")).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.trustOf("https://bad.example/x/", "bad-addon")).isEqualTo(Trust.UNOFFICIAL)
        assertThat(c.official.value).isEmpty()
        assertThat(c.described("tidal")).isNull()
    }

    @Test fun `the official list - what a user can add on this phone, and what the index says of a default`() = runBlocking<Unit> {
        val current = catalog(build = 160)
        val offered = withTimeout(5_000) { current.official.first { it.isNotEmpty() } }
        // The default (Tidal) is not something to add: it is already on. The other two are, on Android at build 160.
        assertThat(offered.map { it.id }).containsExactly("example-radio", "org.example.sixty").inOrder()
        assertThat(offered.first().manifestUrl).isEqualTo("https://addons.example.org/radio/manifest.json")
        assertThat(current.described("tidal")!!.summary).isEqualTo("Lossless and Hi-Res")
        // An older build is not offered what needs a newer one.
        val older = catalog(build = 159)
        assertThat(withTimeout(5_000) { older.official.first { it.isNotEmpty() } }.map { it.id }).containsExactly("org.example.sixty")
    }
}
