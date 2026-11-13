package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The add-on media client ([HttpAddonClient.mediaClient]) and the add-on API client held to the
 * base rules on the wire (add-ons plan phase 4 §2.6; review-882 B1, M1 and M2). Everything that
 * fetches a URL an add-on handed over goes through the media client: the player, the downloader
 * and the DASH downloader (their own wiring tests are `AddonFetchWiringTest` in `:data:download`
 * and `AddonPlaybackWiringTest` in `:core:media`).
 *
 * "No connection is attempted" is measured, not inferred: an [EventListener] on the app's shared
 * client (every add-on client is built from it) records each DNS lookup and each connection
 * attempt. A failed call alone proves nothing: a connection to `192.168.1.1` also fails on a
 * machine with no route to it.
 */
class AddonMediaClientTest {

    /** Every DNS lookup and connection attempt of any client built from [shared]. */
    private class Wire : EventListener() {
        val lookups = CopyOnWriteArrayList<String>()
        val connections = CopyOnWriteArrayList<InetSocketAddress>()

        override fun dnsStart(call: Call, domainName: String) {
            lookups += domainName
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connections += inetSocketAddress
            // An attempt on anything but this test's own servers is the failure being tested for:
            // don't wait out a connect timeout to report it.
            if (!inetSocketAddress.address.isLoopbackAddress) call.cancel()
        }

        val offLoopback get() = connections.filterNot { it.address.isLoopbackAddress }
    }

    private val wire = Wire()
    private val appInterceptorSaw = CopyOnWriteArrayList<String>()

    /** The phone's resolver: names an add-on could hand over, and where they point. */
    private val resolver = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
            "media.attacker.example", "api.attacker.example" -> listOf(InetAddress.getByAddress(hostname, byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))
            // A name that does not resolve at all: an ordinary failure, not the filter's refusal.
            "gone.attacker.example" -> throw UnknownHostException("no such host")
            "cgnat.attacker.example" -> listOf(InetAddress.getByAddress(hostname, byteArrayOf(100, 64, 0, 7)))
            // The user's own proxy, on their own network (here: this machine), and two honest public hosts.
            "proxy.corp.example" -> listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
            "media.public.example", "api.public.example" -> listOf(InetAddress.getByAddress(hostname, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            else -> Dns.SYSTEM.lookup(hostname)
        }
    }

    private val shared: OkHttpClient = OkHttpClient.Builder()
        .dns(resolver)
        .eventListener(wire)
        .addInterceptor(Interceptor { chain -> appInterceptorSaw += "app"; chain.proceed(chain.request()) })
        .build()

    /** A debug host: the test's own servers are plain http on 127.0.0.1, which only such a host may call. */
    private val host = object : AddonHostOptions {
        override val allowLocalHttp = true
        override val userAgent = "Stash/9.9.9-test"
    }

    private val a = MockWebServer()
    private val b = MockWebServer()
    private lateinit var client: HttpAddonClient
    private lateinit var media: OkHttpClient

    @Before fun setUp() {
        a.start(InetAddress.getByName("127.0.0.1"), 0)
        b.start(InetAddress.getByName("127.0.0.1"), 0)
        client = HttpAddonClient(shared, OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons, host, AddonMediaRefusals { stream, why -> refusedStreams += stream; refusedWhy += why })
        media = client.mediaClient
    }

    /** What the media client's guard reported as refused ([AddonMediaRefusals]). */
    private val refusedStreams = CopyOnWriteArrayList<String>()

    /** What each of those refusals was ([MediaRefusal]), in the same order. */
    private val refusedWhy = CopyOnWriteArrayList<MediaRefusal>()

    @After fun tearDown() {
        a.shutdown()
        b.shutdown()
    }

    /** Never `server.url()`: its host is the machine's reverse name for 127.0.0.1, which is not always a development host. */
    private fun at(server: MockWebServer, path: String) = "http://127.0.0.1:${server.port}$path"

    private fun get(url: String, client: OkHttpClient = media) = client.newCall(Request.Builder().url(url).build()).execute()

    private fun failure(url: String, client: OkHttpClient = media): IOException =
        try {
            get(url, client).close()
            throw AssertionError("the fetch went through")
        } catch (e: IOException) {
            e
        }

    private fun redirect(location: String) = MockResponse().setResponseCode(302).setHeader("Location", location)

    // ------------------------------------------------------------------------------------------ the first URL

    @Test fun `a URL on the user's own network is refused before any lookup or connection`() {
        for (url in listOf(
            "https://192.168.1.1/a.flac",
            "https://10.0.0.5/seg-1.m4s",
            "https://nas.lan/a.flac",
            "https://router.local/a.flac",
            "https://localhost.localdomain.internal/a.flac",
            "https://nas/a.flac",
        )) {
            assertThat(failure(url)).isInstanceOf(PrivateAddressException::class.java)
        }
        assertThat(wire.lookups).isEmpty()
        assertThat(wire.connections).isEmpty()
    }

    @Test fun `a URL that is not https, or carries credentials, is refused before any connection`() {
        assertThat(failure("http://cdn.example.org/a.flac")).isInstanceOf(RefusedAddonUrlException::class.java)
        assertThat(failure("https://user:pw@cdn.example.org/a.flac")).isInstanceOf(RefusedAddonUrlException::class.java)
        // An IPv6 literal is no host by the URL rule at all, on anyone's network.
        assertThat(failure("https://[fe80::1]/a.flac")).isInstanceOf(RefusedAddonUrlException::class.java)
        assertThat(wire.lookups).isEmpty()
        assertThat(wire.connections).isEmpty()
    }

    @Test fun `a name that resolves to the user's own network is refused by DNS, and nothing is connected`() {
        assertThat(failure("https://media.attacker.example/a.flac")).isInstanceOf(UnknownHostException::class.java)
        assertThat(failure("https://cgnat.attacker.example/a.flac")).isInstanceOf(UnknownHostException::class.java)
        assertThat(wire.lookups).containsExactly("media.attacker.example", "cgnat.attacker.example")
        assertThat(wire.connections).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ redirects

    @Test fun `a redirect to a private address is refused, and no second connection is attempted`() {
        for (target in listOf("https://192.168.1.1/x.flac", "https://192.168.1.50:8443/x.flac", "https://nas.lan/x.flac", "//10.0.0.5/x.flac")) {
            a.enqueue(redirect(target))
            assertThat(failure(at(a, "/song.flac"))).isInstanceOf(IOException::class.java)
        }
        assertThat(a.requestCount).isEqualTo(4)
        // The only peer ever dialled is the add-on's own (loopback) media host.
        assertThat(wire.connections).isNotEmpty()
        assertThat(wire.offLoopback).isEmpty()
        assertThat(wire.lookups).doesNotContain("nas.lan")
    }

    @Test fun `a redirect to a name that resolves privately is refused, and no second connection is attempted`() {
        a.enqueue(redirect("https://media.attacker.example/x.flac"))
        assertThat(failure(at(a, "/song.flac"))).isInstanceOf(UnknownHostException::class.java)
        assertThat(wire.lookups).contains("media.attacker.example")
        assertThat(wire.offLoopback).isEmpty()
    }

    @Test fun `a redirect from https down to http is refused`() {
        a.enqueue(redirect("http://cdn.example.org/x.flac"))
        assertThat(failure(at(a, "/song.flac"))).isInstanceOf(RefusedAddonUrlException::class.java)
        assertThat(wire.offLoopback).isEmpty()
    }

    @Test fun `an allowed redirect is followed by hand, with Stash's User-Agent on every hop and no credentials on another origin`() {
        a.enqueue(redirect(at(b, "/cdn/song.flac")))
        b.enqueue(MockResponse().setBody("FLAC"))
        val request = Request.Builder().url(at(a, "/song.flac")).header("Authorization", "Bearer s3cret").header("Range", "bytes=0-").build()
        media.newCall(request).execute().use { response ->
            assertThat(response.body!!.string()).isEqualTo("FLAC")
            // The player reads the final URL to resolve what a DASH manifest names against.
            assertThat(response.request.url.toString()).isEqualTo(at(b, "/cdn/song.flac"))
        }
        val first = a.takeRequest()
        val second = b.takeRequest()
        assertThat(first.getHeader("User-Agent")).isEqualTo("Stash/9.9.9-test")
        assertThat(second.getHeader("User-Agent")).isEqualTo("Stash/9.9.9-test")
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer s3cret")
        assertThat(second.getHeader("Authorization")).isNull()
        assertThat(second.getHeader("Range")).isEqualTo("bytes=0-")
    }

    @Test fun `no more than five hops`() {
        repeat(AddonMediaGuard.MAX_REDIRECTS + 1) { a.enqueue(redirect(at(a, "/again"))) }
        assertThat(failure(at(a, "/song.flac"))).isInstanceOf(ProtocolException::class.java)
        assertThat(a.requestCount).isEqualTo(AddonMediaGuard.MAX_REDIRECTS + 1)
    }

    @Test fun `the app's own interceptors are not on the media client`() {
        a.enqueue(MockResponse().setBody("FLAC"))
        get(at(a, "/song.flac")).close()
        assertThat(appInterceptorSaw).isEmpty()
        // And the shared client itself does carry it: the check above is not vacuous.
        a.enqueue(MockResponse().setBody("FLAC"))
        get(at(a, "/song.flac"), shared).close()
        assertThat(appInterceptorSaw).containsExactly("app")
    }

    // ------------------------------------------------------------------------------------------ refusals are reported (review-882 round 2, F7)

    @Test fun `the guard's own refusals are reported, each under the stream's first URL`() {
        failure("https://192.168.1.1/a.flac")
        failure("https://media.attacker.example/a.flac")
        a.enqueue(redirect("https://10.0.0.5/x.flac"))
        failure(at(a, "/one.flac"))
        a.enqueue(redirect("https://media.attacker.example/x.flac"))
        failure(at(a, "/two.flac"))
        failure("http://cdn.example.org/a.flac")

        assertThat(refusedStreams).containsExactly(
            "https://192.168.1.1/a.flac",
            "https://media.attacker.example/a.flac",
            at(a, "/one.flac"),
            at(a, "/two.flac"),
            "http://cdn.example.org/a.flac",
        ).inOrder()
        // A URL refused for itself holds on any network; a name with only private answers is
        // what this network's resolver said (review-882 round 3, R3).
        assertThat(refusedWhy).containsExactly(
            MediaRefusal.URL,
            MediaRefusal.NO_PUBLIC_ADDRESS,
            MediaRefusal.URL,
            MediaRefusal.NO_PUBLIC_ADDRESS,
            MediaRefusal.URL,
        ).inOrder()
    }

    @Test fun `a refused request that says which stream it belongs to is reported under that stream`() {
        // A DASH segment on the user's own network, tagged with its manifest's URL.
        val segment = Request.Builder().url("https://10.0.0.5/seg-1.m4s")
            .tag(AddonStreamTag::class.java, AddonStreamTag("https://media.example.org/m.mpd")).build()
        assertThrows(PrivateAddressException::class.java) { media.newCall(segment).execute().close() }
        assertThat(refusedStreams).containsExactly("https://media.example.org/m.mpd")
        assertThat(refusedWhy).containsExactly(MediaRefusal.URL)
    }

    @Test fun `an ordinary failed fetch is not a refusal`() {
        a.enqueue(MockResponse().setResponseCode(404))
        get(at(a, "/gone.flac")).close()
        a.enqueue(MockResponse().setBody("FLAC"))
        get(at(a, "/here.flac")).close()
        repeat(AddonMediaGuard.MAX_REDIRECTS + 1) { a.enqueue(redirect(at(a, "/again"))) }
        assertThat(failure(at(a, "/loop.flac"))).isInstanceOf(ProtocolException::class.java)
        // A name that does not resolve at all is the network's failure, not the filter's.
        val unknown = failure("https://gone.attacker.example/a.flac")
        assertThat(unknown).isInstanceOf(UnknownHostException::class.java)
        assertThat(unknown).isNotInstanceOf(NoPublicAddressException::class.java)

        assertThat(refusedStreams).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ behind a proxy

    /** Behind a proxy OkHttp resolves the proxy, never the target: the guard resolves the target itself first. */
    @Test fun `behind a proxy, a name that resolves privately is refused before the proxy hears of it`() = runBlocking<Unit> {
        val proxied = shared.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", b.port))).build()
        val behindProxy = HttpAddonClient(proxied, OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons, host)

        // A stream or a file (the media client).
        assertThat(failure("https://media.attacker.example/a.flac", behindProxy.mediaClient)).isInstanceOf(UnknownHostException::class.java)
        // An API call of an add-on the user added (the API client).
        val manifest = behindProxy.manifest("https://api.attacker.example/", publicOnly = true)
        assertThat((manifest as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)

        // The proxy never heard of either, and OkHttp itself dialled and looked up nothing.
        assertThat(b.requestCount).isEqualTo(0)
        assertThat(wire.connections).isEmpty()
        assertThat(wire.lookups).isEmpty()

        // What the guard lets through does go to the proxy (so the refusals above were the guard's).
        b.enqueue(MockResponse().setBody("VIA PROXY"))
        get("http://127.0.0.1:${a.port}/song.flac", behindProxy.mediaClient).use { assertThat(it.body!!.string()).isEqualTo("VIA PROXY") }
        assertThat(b.requestCount).isEqualTo(1)
        assertThat(a.requestCount).isEqualTo(0)
    }

    /**
     * review-882 round 2, F1. A proxy named by host (what Java's and Android's proxy selectors hand
     * out) is resolved by OkHttp through the client's own DNS, which is the add-on filter: a work
     * proxy on the user's own network has only private answers, so every call failed before the
     * proxy heard of it. A strict (release) host here: on a debug host the proxy's loopback
     * address would pass the filter anyway.
     */
    @Test fun `a proxy named by host is reached, and what is refused behind it is still refused`() = runBlocking<Unit> {
        val named = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.corp.example", b.port))
        val selector = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(named)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val clients = listOf(
            HttpAddonClient(shared.newBuilder().proxy(named).build(), OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons),
            HttpAddonClient(shared.newBuilder().proxySelector(selector).build(), OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons),
        )
        for (behindProxy in clients) {
            val heard = b.requestCount
            wire.lookups.clear()

            // A public host: the proxy is asked for a tunnel to it (and says no, which ends the call here).
            b.enqueue(MockResponse().setResponseCode(403))
            failure("https://media.public.example/a.flac", behindProxy.mediaClient)
            assertThat(b.takeRequest(5, TimeUnit.SECONDS)!!.requestLine).startsWith("CONNECT media.public.example:443")
            // The same for an API call of an add-on the user added.
            b.enqueue(MockResponse().setResponseCode(403))
            assertThat((behindProxy.manifest("https://api.public.example/", publicOnly = true) as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
            assertThat(b.takeRequest(5, TimeUnit.SECONDS)!!.requestLine).startsWith("CONNECT api.public.example:443")
            // OkHttp itself only ever asked for the proxy.
            assertThat(wire.lookups.toSet()).containsExactly("proxy.corp.example")
            assertThat(b.requestCount).isEqualTo(heard + 2)

            // Still refused before the proxy hears: a name that resolves privately, and an add-on
            // that names the proxy's own host as its media host.
            assertThat(failure("https://media.attacker.example/a.flac", behindProxy.mediaClient)).isInstanceOf(NoPublicAddressException::class.java)
            assertThat(failure("https://proxy.corp.example/a.flac", behindProxy.mediaClient)).isInstanceOf(NoPublicAddressException::class.java)
            val api = behindProxy.manifest("https://proxy.corp.example/", publicOnly = true)
            assertThat((api as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
            assertThat(b.requestCount).isEqualTo(heard + 2)
        }
        assertThat(wire.offLoopback).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ the API client

    private fun userAdded(base: String) = InstalledAddon(
        base = base,
        manifest = Manifest(stash = 1, id = "stranger", name = "Stranger", version = "1", kind = AddonKind.SERVER, resources = listOf("search")),
        userAdded = true,
    )

    /** review-882 M1: no test showed a user-added add-on's API call refused by DNS. */
    @Test fun `an API call of an add-on the user added is refused by DNS when its name resolves privately`() = runBlocking<Unit> {
        val r = client.search(userAdded("https://api.attacker.example/tok/"), "daft punk")
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
        assertThat(wire.lookups).containsExactly("api.attacker.example")
        assertThat(wire.connections).isEmpty()
    }

    @Test fun `an API redirect to a private address or a privately resolving name is not followed`() = runBlocking<Unit> {
        val logged = CopyOnWriteArrayList<String>()
        client.logHook = { logged += it }
        val addon = userAdded("http://127.0.0.1:${a.port}/tok/")

        a.enqueue(redirect("https://192.168.1.1/search?q=x"))
        assertThat((client.search(addon, "x") as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
        assertThat(logged.last()).isEqualTo("stranger search: redirect refused (private address)")

        a.enqueue(redirect("https://api.attacker.example/search?q=x"))
        assertThat((client.search(addon, "x") as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)

        assertThat(a.requestCount).isEqualTo(2)
        assertThat(wire.offLoopback).isEmpty()
    }
}
