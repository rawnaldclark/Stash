package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Quota
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.AddonRateGate
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.NoFirstPartyAddons
import com.stash.core.addons.UnsignedAddons
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.tidal.LocalHttp
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * What an add-on's page asks when it opens (review-885 M10): whether the add-on is paused, and what
 * is left of its allowance. Both are asked from the screen, so both must leave its thread, and the
 * allowance is asked outside the add-on's breaker: a look at a page is never what pauses its plays.
 */
class AddonOverviewPageCallsTest {

    private lateinit var server: MockWebServer
    @Volatile private var quotaAnswer: () -> MockResponse = { MockResponse().setResponseCode(500).setBody("{}") }

    /** Everything the add-on's breaker was asked or told. */
    private val gateCalls = CopyOnWriteArrayList<String>()

    private val gate = object : AddonRateGate {
        override suspend fun admit(key: String, paced: Boolean): Boolean { gateCalls += "admit"; return true }
        override suspend fun onAnswer(key: String) { gateCalls += "answer" }
        override suspend fun onRateLimited(key: String, retryAfterMs: Long?) { gateCalls += "rate-limited" }
        override suspend fun onFailure(key: String) { gateCalls += "failure" }
    }

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path.orEmpty().endsWith("/quota")) quotaAnswer() else MockResponse().setResponseCode(404)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun addon() = InstalledAddon(
        base = "http://127.0.0.1:${server.port}/mine/",
        manifest = Manifest(
            stash = 1, id = "mine", name = "Mine", version = "1", kind = AddonKind.SERVER, resources = listOf("search", "stream", "quota"),
            stream = StreamCapabilities(cors = false, manifests = listOf("none"), tiers = listOf("lossless")),
        ),
    )

    private val row = AddonOverview.Row(
        key = "addon:mine", kind = AddonOverview.Kind.ADDON, name = "Mine", host = "127.0.0.1", mark = AddonOverview.Mark.OFFICIAL,
        status = AddonOverview.Status.WORKING, on = true, canSwitch = true, canRemove = false, playsSongs = true, metered = true,
    )

    private val limiterThreads = CopyOnWriteArrayList<Thread>()
    private val limiter: AggregatorRateLimiter = mockk {
        coEvery { stateOf(any()) } coAnswers {
            limiterThreads += Thread.currentThread()
            RateLimitState(tokensAvailable = 1.0, msUntilNextToken = 0L, isCircuitBroken = true, msUntilUnblock = 1_000L, recentFailures = 3)
        }
    }

    private fun overview(
        client: HttpAddonClient = HttpAddonClient(OkHttpClient(), gate, NoFirstPartyAddons, UnsignedAddons, LocalHttp),
    ): AddonOverview {
        val registry: InTreeAddonRegistry = mockk(relaxed = true) { every { addons } returns MutableStateFlow(listOf(addon())) }
        return AddonOverview(
            users = mockk(relaxed = true), registry = registry, catalog = mockk(relaxed = true), index = mockk(relaxed = true),
            prefs = mockk(relaxed = true), playOrder = mockk(relaxed = true), switches = mockk(relaxed = true),
            availability = mockk(relaxed = true), wires = mockk(relaxed = true), own = mockk(relaxed = true),
            client = client, limiter = limiter, links = mockk(relaxed = true),
        )
    }

    @Test fun `an allowance that fails to read never counts against the add-on's plays`() = runBlocking<Unit> {
        val overview = overview()
        // A broken `/quota`, looked at more often than the breaker forgives failures.
        repeat(4) { assertThat(overview.quota(row)).isNull() }
        assertThat(server.requestCount).isAtLeast(4)
        // The breaker was neither asked nor told: nothing a page does can pause the add-on.
        assertThat(gateCalls).isEmpty()
    }

    @Test fun `an allowance is read, and it is asked for even while the add-on's plays are paused`() = runBlocking<Unit> {
        quotaAnswer = { MockResponse().setBody("""{"used":3,"limit":10,"resetsAt":1893456000}""") }
        val quota = overview().quota(row)
        assertThat(quota?.used).isEqualTo(3)
        assertThat(quota?.limit).isEqualTo(10)
        assertThat(gateCalls).isEmpty()
    }

    @Test fun `the page's two questions leave the thread of the screen that asks them`() {
        val screen = Executors.newSingleThreadExecutor { r -> Thread(r, "the-screen") }
        try {
            lateinit var caller: Thread
            lateinit var after: Thread
            // Where the add-on is asked is where its answer is read: the client reads on the thread that calls it.
            val clientThreads = CopyOnWriteArrayList<Thread>()
            // Relaxed: the overview also reads the client's own lists as it is built.
            val client: HttpAddonClient = mockk(relaxed = true) {
                coEvery { quota(any(), circuit = false) } coAnswers {
                    clientThreads += Thread.currentThread()
                    AddonResult.Ok(Quota(used = 3, limit = 10, resetsAt = 1_893_456_000L))
                }
            }
            val overview = overview(client)
            runBlocking(screen.asCoroutineDispatcher()) {
                caller = Thread.currentThread()
                assertThat(overview.paused(row)).isTrue()
                assertThat(overview.quota(row)?.used).isEqualTo(3)
                after = Thread.currentThread()
            }
            // By identity: a test run renames threads while a coroutine is on them.
            assertThat(limiterThreads).hasSize(1)
            assertThat(limiterThreads.single()).isNotSameInstanceAs(caller)
            assertThat(clientThreads).hasSize(1)
            assertThat(clientThreads.single()).isNotSameInstanceAs(caller)
            assertThat(after).isSameInstanceAs(caller)
        } finally {
            screen.shutdownNow()
        }
    }
}
