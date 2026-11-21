package com.stash.data.download.lossless.tidal

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stash.core.addons.DefaultAddonEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Anti-leech step 3, the app's side: [TidalInstallPass] against a stand-in for the add-on's `/register` routes. */
class TidalInstallPassTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: MemoryPreferencesStore
    private val entries = MutableStateFlow<List<DefaultAddonEntry>>(emptyList())

    /** What the stand-in Keystore hands back: null for a phone that can't attest. */
    private var chain: List<String>? = listOf("bGVhZg==", "cm9vdA==")
    private val challenges = mutableListOf<ByteArray>()
    private var now = 1_000_000L

    private val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(40) { it.toByte() })
    private val pass = "eyJ2IjoxfQ.c2lnbmF0dXJl"

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = MemoryPreferencesStore()
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private val origin get() = server.url("/").toString().removeSuffix("/")
    private val manifest get() = "$origin/v1/ACCESS/manifest.json"

    private fun pass(firstParty: Boolean = true): TidalInstallPass {
        entries.value = listOf(DefaultAddonEntry(manifest, enabled = true, firstParty = firstParty))
        return TidalInstallPass(store, OkHttpClient(), { c -> challenges += c; chain }, "Stash/test", { now })
            .also { it.bind(scope, entries) }
    }

    private fun json(code: Int, body: String, retryAfter: Long? = null) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
        .apply { if (retryAfter != null) setHeader("Retry-After", retryAfter.toString()) }

    private fun challengeAnswer() = json(200, """{"challenge":"$challenge","expiresAt":1}""")
    private fun passAnswer(p: String = pass) = json(200, """{"pass":"$p","install":"abc"}""")
    private fun refusal(reason: String) = json(403, """{"error":{"code":"proof_refused","message":"no","reason":"$reason"}}""")

    private suspend fun until(what: () -> Boolean) = withTimeout(3_000) { while (!what()) delay(10) }

    @Test fun `attestation gets this install its pass, kept and put in the face's link`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(passAnswer())
        val p = pass()
        p.ensure()

        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", first.method)
        assertEquals("/register/challenge", first.path)
        assertEquals("Stash/test", first.getHeader("User-Agent"))
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/register", second.path)
        val sent = Json.parseToJsonElement(second.body.readUtf8()).jsonObject
        assertEquals("android", sent["app"]!!.jsonPrimitive.content)
        val proof = sent["proof"]!!.jsonObject
        assertEquals("android-key", proof["kind"]!!.jsonPrimitive.content)
        assertEquals(challenge, proof["challenge"]!!.jsonPrimitive.content)
        assertEquals(listOf("bGVhZg==", "cm9vdA=="), proof["chain"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The Keystore was asked over the challenge's own bytes.
        assertTrue(challenges.single().contentEquals(Base64.getUrlDecoder().decode(challenge)))

        val held = p.held.value!!
        assertEquals(origin, held.origin)
        assertEquals(pass, held.pass)
        assertNull(p.checkPage.value)
        assertEquals(pass, store.values.value[stringPreferencesKey("pass")])
        assertEquals("$origin/v1/$pass/manifest.json", TidalInstallPass.withPass(manifest, held))
        // Held: nothing more is asked.
        p.ensure()
        assertEquals(2, server.requestCount)
    }

    @Test fun `a kept pass is read back and nothing is asked`() = runBlocking {
        store.values.value = preferencesOf(stringPreferencesKey("origin") to origin, stringPreferencesKey("pass") to pass)
        val p = pass()
        p.ensure()
        assertEquals(HeldPass(origin, pass), p.held.value)
        assertEquals(0, server.requestCount)
    }

    @Test fun `no first-party Tidal entry, nothing is asked`() = runBlocking {
        val p = pass(firstParty = false)
        p.ensure()
        assertEquals(0, server.requestCount)
        assertNull(p.held.value)
    }

    @Test fun `an add-on without the check (404) leaves the access token, and is asked again next time`() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        server.enqueue(json(404, """{"error":"not_found"}"""))
        val p = pass()
        p.ensure()
        p.ensure()
        assertEquals(2, server.requestCount)
        assertNull(p.held.value)
        assertNull(p.checkPage.value)
    }

    @Test fun `a phone that can't attest is offered the check`() = runBlocking {
        chain = null
        server.enqueue(challengeAnswer())
        val p = pass()
        p.ensure()
        assertEquals("$origin/register", p.checkPage.value)
        assertEquals(1, server.requestCount)
        // While the check waits, attestation is not tried again.
        p.ensure()
        assertEquals(1, server.requestCount)
    }

    @Test fun `refused attestation offers the check`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(refusal("attest_signer"))
        val p = pass()
        p.ensure()
        assertEquals("$origin/register", p.checkPage.value)
        assertNull(p.held.value)
    }

    @Test fun `a spent challenge is not the phone's fault - no check, a fresh try after a minute`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(refusal("challenge_used"))
        val p = pass()
        p.ensure()
        assertNull(p.checkPage.value)
        p.ensure()
        assertEquals(2, server.requestCount) // backed off
        now += 61_000
        server.enqueue(challengeAnswer())
        server.enqueue(passAnswer())
        p.ensure()
        assertEquals(pass, p.held.value?.pass)
    }

    @Test fun `a 403 that is not the add-on's verdict is a plain failure`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(json(403, """{"error":{"code":"forbidden","message":"no"}}"""))
        val p = pass()
        p.ensure()
        assertNull(p.checkPage.value)
        assertNull(p.held.value)
        p.ensure()
        assertEquals(2, server.requestCount) // backed off for RETRY_MS
        now += TidalInstallPass.RETRY_MS
        server.enqueue(json(404, "{}"))
        p.ensure()
        assertEquals(3, server.requestCount)
    }

    @Test fun `a full network today backs off until the add-on says`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"x"}}""", retryAfter = 7200))
        val p = pass()
        p.ensure()
        now += 3600_000
        p.ensure()
        assertEquals(2, server.requestCount)
        now += 3600_000
        server.enqueue(json(404, "{}"))
        p.ensure()
        assertEquals(3, server.requestCount)
    }

    @Test fun `a malformed challenge is not signed`() = runBlocking {
        server.enqueue(json(200, """{"challenge":"short"}"""))
        val p = pass()
        p.ensure()
        assertTrue(challenges.isEmpty())
        assertNull(p.checkPage.value)
    }

    @Test fun `a pass of the wrong shape is not kept`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(passAnswer("not a pass/../x"))
        val p = pass()
        p.ensure()
        assertNull(p.held.value)
    }

    @Test fun `the solved check gets the pass`() = runBlocking {
        chain = null
        server.enqueue(challengeAnswer())
        val p = pass()
        p.ensure()
        server.enqueue(passAnswer())
        assertEquals(CheckOutcome.Passed, p.registerWithTurnstile("TOKEN.x-y_z"))
        server.takeRequest()
        val sent = Json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals("android", sent["app"]!!.jsonPrimitive.content)
        assertEquals("turnstile", sent["proof"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("TOKEN.x-y_z", sent["proof"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals(HeldPass(origin, pass), p.held.value)
        assertNull(p.checkPage.value)
    }

    @Test fun `the check's failures say why`() = runBlocking {
        chain = null
        server.enqueue(challengeAnswer())
        val p = pass()
        p.ensure()
        server.enqueue(json(429, "{}", retryAfter = 100))
        val busy = p.registerWithTurnstile("T") as CheckOutcome.Failed
        assertTrue(busy.message, busy.message.startsWith("Too many phones"))
        server.enqueue(refusal("turnstile"))
        assertTrue((p.registerWithTurnstile("T") as CheckOutcome.Failed).message.contains("didn't go through"))
        server.enqueue(json(503, "{}"))
        assertTrue((p.registerWithTurnstile("T") as CheckOutcome.Failed).message.contains("can't add phones"))
        assertNull(p.held.value)
        assertEquals("$origin/register", p.checkPage.value)
    }

    @Test fun `a refused pass is let go and attestation runs again, once per refusal`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(passAnswer())
        val p = pass()
        p.ensure()
        val base = "$origin/v1/$pass/"
        server.enqueue(challengeAnswer())
        server.enqueue(passAnswer("bmV3.cGFzcw"))
        p.refused(base)
        p.refused(base) // the stream refused with the search: the same refusal
        until { p.held.value?.pass == "bmV3.cGFzcw" }
        assertEquals(4, server.requestCount)
        assertNull(p.checkPage.value)
    }

    @Test fun `a pass refused again within the hour gets the check, not another attestation`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(passAnswer())
        val p = pass()
        p.ensure()
        server.enqueue(challengeAnswer())
        server.enqueue(passAnswer("bmV3.cGFzcw"))
        p.refused("$origin/v1/$pass/")
        until { p.held.value?.pass == "bmV3.cGFzcw" }
        now += 60_000
        p.refused("$origin/v1/bmV3.cGFzcw/")
        until { p.checkPage.value != null }
        assertNull(p.held.value)
        assertNull(store.values.value[stringPreferencesKey("pass")])
        assertEquals(4, server.requestCount)
    }

    @Test fun `a refusal of the access token is not the pass's`() = runBlocking {
        server.enqueue(challengeAnswer())
        server.enqueue(passAnswer())
        val p = pass()
        p.ensure()
        p.refused("$origin/v1/ACCESS/")
        delay(100)
        assertEquals(pass, p.held.value?.pass)
    }

    @Test fun `a pass from another add-on is dropped, never sent`() = runBlocking {
        store.values.value = preferencesOf(stringPreferencesKey("origin") to "https://old.example", stringPreferencesKey("pass") to pass)
        server.enqueue(json(404, "{}"))
        val p = pass()
        p.ensure()
        assertNull(p.held.value)
        assertEquals(manifest, TidalInstallPass.withPass(manifest, HeldPass("https://old.example", pass)))
    }

    @Test fun `links, passes and the hand-back`() {
        assertEquals("https://addon.example", TidalInstallPass.v1Origin("https://Addon.example/v1/tok/manifest.json"))
        assertEquals("http://127.0.0.1:8787", TidalInstallPass.v1Origin("http://127.0.0.1:8787/v1/tok/manifest.json"))
        assertNull(TidalInstallPass.v1Origin("https://addon.example/manifest.json"))
        assertNull(TidalInstallPass.v1Origin("https://addon.example/x/v1/tok/manifest.json"))
        val held = HeldPass("https://addon.example", pass)
        assertEquals("https://addon.example/v1/$pass/manifest.json", TidalInstallPass.withPass("https://addon.example/v1/tok/manifest.json", held))
        assertEquals("https://other.example/v1/tok/manifest.json", TidalInstallPass.withPass("https://other.example/v1/tok/manifest.json", held))
        assertFalse(held.toString().contains(pass))

        assertTrue(TidalInstallPass.passOk(pass))
        assertFalse(TidalInstallPass.passOk("a.b.c"))
        assertFalse(TidalInstallPass.passOk("a/b.c"))
        assertFalse(TidalInstallPass.passOk(".x"))
        assertFalse(TidalInstallPass.passOk("a".repeat(401) + ".b"))

        val page = "https://addon.example/register"
        assertEquals("abc.DEF-_", TidalInstallPass.turnstileToken(page, "https://addon.example/register/done#turnstile=abc.DEF-_"))
        assertEquals("a+b/c=", TidalInstallPass.turnstileToken(page, "https://addon.example/register/done#turnstile=a%2Bb%2Fc%3D"))
        assertNull(TidalInstallPass.turnstileToken(page, "https://evil.example/register/done#turnstile=abc"))
        assertNull(TidalInstallPass.turnstileToken(page, "https://addon.example/register/other#turnstile=abc"))
        assertNull(TidalInstallPass.turnstileToken(page, "https://addon.example/register/done#other=abc"))
        assertNull(TidalInstallPass.turnstileToken(page, "https://addon.example/register/done#turnstile="))
        assertNull(TidalInstallPass.turnstileToken(page, "https://addon.example/register/done#turnstile=a%2"))
        assertNull(TidalInstallPass.turnstileToken(page, "https://addon.example/register/done#turnstile=a%zz"))
        assertNull(TidalInstallPass.turnstileToken(page, "https://addon.example/register/done#turnstile=a%20b"))
        assertNull(TidalInstallPass.turnstileToken(page, "https://addon.example/register/done#turnstile=" + "a".repeat(2049)))
        assertTrue(TidalInstallPass.isHandBack(page, "https://addon.example/register/done"))
        assertFalse(TidalInstallPass.isHandBack(page, "http://addon.example/register/done"))
        assertNotNull(TidalInstallPass.turnstileToken(page, "https://ADDON.example:443/register/done#turnstile=x"))
    }
}
