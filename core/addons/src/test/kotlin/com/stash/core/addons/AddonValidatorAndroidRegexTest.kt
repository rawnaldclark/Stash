package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, which refuses some JVM-only syntax: `(?U)` in the validator's
 * initializer made every add-on call fail on a phone (seen on the Pixel 5, add-ons phase 2) while
 * every JVM test passed. The JVM can't run ICU, so this pins the behaviour and bans the syntax.
 */
class AddonValidatorAndroidRegexTest {

    @Test fun `unicode white space still collapses without the (?U) flag`() {
        val nbsp = ' '
        val em = ' '
        assertThat(AddonValidator.cleanText("a${nbsp}${nbsp}b${em}c \t d", 100)).isEqualTo("a b c d")
    }

    @Test fun `no main source uses regex syntax Android's ICU refuses`() {
        val banned = listOf("(?U)", "(?X)", "\\p{IsWhite_Space}", "\\h", "\\R")
        val src = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertThat(src).isNotEmpty()
        val hits = src.flatMap { f -> banned.filter { it in f.readText() }.map { "${f.name}: $it" } }
        assertThat(hits).isEmpty()
    }
}

/**
 * The app's shared OkHttp client speaks TLS only (`ConnectionSpec.MODERN_TLS`). On a debug build an
 * add-on under `wrangler dev` is plain http on localhost (SPEC.md 1.1): the client must add cleartext
 * for that, and only for that (seen on the Pixel 5: UnknownServiceException on the first manifest).
 */
class HttpAddonClientCleartextTest {

    private val tlsOnly = okhttp3.OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS)).build()

    private val host = object : AddonRateGate, AddonTrust, AddonInstallSigner {
        override suspend fun admit(key: String, paced: Boolean) = true
        override suspend fun onAnswer(key: String) = Unit
        override suspend fun onRateLimited(key: String, retryAfterMs: Long?) = Unit
        override suspend fun onFailure(key: String) = Unit
        override fun isFirstParty(base: String) = false
        override suspend fun sign(builder: okhttp3.Request.Builder, payload: String) = false
        override fun adoptServerTime(response: okhttp3.Response) = false
    }

    @Test fun `a debug host reaches a localhost add-on over http through a TLS-only shared client`() = kotlinx.coroutines.runBlocking<Unit> {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"stash":1,"id":"x","name":"X","version":"1","kind":"server","resources":["search"]}"""),
        )
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            val dev = HttpAddonClient(tlsOnly, host, host, host, object : AddonHostOptions { override val allowLocalHttp = true })
            assertThat(dev.manifest(server.url("/").toString())).isInstanceOf(AddonResult.Ok::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a release host never asks http at all`() = kotlinx.coroutines.runBlocking<Unit> {
        val strict = HttpAddonClient(tlsOnly, host, host, host)
        val r = strict.manifest("http://127.0.0.1:9/") as AddonResult.Failed
        assertThat(r.failure).isEqualTo(AddonFailure.Network)
    }
}
