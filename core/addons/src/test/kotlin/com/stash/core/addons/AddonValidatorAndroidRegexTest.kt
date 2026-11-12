package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, which refuses some JVM-only syntax: `(?U)` in the validator's
 * initializer made every add-on call fail on a phone (seen on the Pixel 5, add-ons phase 2) while
 * every JVM test passed.
 *
 * **What this can and cannot prove** (review-881 M13). No JVM runs ICU's regex engine, so no unit
 * test can show that a pattern compiles on Android: only a phone can (the add-on client reading
 * one manifest loads every pattern in `AddonValidator`). What a JVM test can do is read the
 * sources: the scan below finds every regex literal in `:core:addons` and fails on a construct
 * `java.util.regex` takes and ICU does not. It is a rule about syntax, checked on every build; it
 * is not a compile on ICU.
 */
class AddonValidatorAndroidRegexTest {

    /** Pins what replaced `(?U)\s+`: the same white space, in syntax both engines share. Says nothing about Android. */
    @Test fun `on the JVM, unicode white space still collapses without the (?U) flag`() {
        val nbsp = ' '
        val em = ' '
        assertThat(AddonValidator.cleanText("a${nbsp}${nbsp}b${em}c \t d", 100)).isEqualTo("a b c d")
    }

    @Test fun `every regex in the module keeps to the syntax both engines share`() {
        val patterns = regexLiterals()
        // The scan found the validator's patterns (it has well over a dozen): an empty scan proves nothing.
        assertThat(patterns.size).isAtLeast(12)
        assertThat(patterns.map { it.second }).contains(WHITESPACE_AS_WRITTEN)
        val hits = patterns.flatMap { (file, source) -> androidTrouble(source).map { "$file: $it in ${source.take(60)}" } }
        assertThat(hits).isEmpty()
    }

    @Test fun `the scan knows the constructs ICU refuses`() {
        // Patterns as they are written in a Kotlin source file: B is one backslash of regex, two characters in the file.
        val b = "\\\\"
        assertThat(androidTrouble("(?U)${b}s+")).isNotEmpty()
        assertThat(androidTrouble("(?iu)abc")).isNotEmpty()
        assertThat(androidTrouble("(?d:a)")).isNotEmpty()
        assertThat(androidTrouble("(?X)a")).isNotEmpty()
        assertThat(androidTrouble("${b}p{IsWhite_Space}")).isNotEmpty()
        assertThat(androidTrouble("${b}p{javaLowerCase}")).isNotEmpty()
        assertThat(androidTrouble("${b}h+")).isNotEmpty()
        assertThat(androidTrouble("a${b}Rb")).isNotEmpty()
        // What the validator uses is fine.
        assertThat(androidTrouble(WHITESPACE_AS_WRITTEN)).isEmpty()
        assertThat(androidTrouble("^(?i)https://[A-Za-z0-9.${b}-]+(:[0-9]{1,5})?")).isEmpty()
        assertThat(androidTrouble("(?<year>${b}d{4})-(?:${b}d{2})")).isEmpty()
    }

    private companion object {
        /** `AddonValidator`'s white-space pattern, as its source line spells it (each backslash doubled). */
        const val WHITESPACE_AS_WRITTEN = "[\\\\s\\\\u0085\\\\p{Z}]+"

        /** `Regex("...")`, `"...".toRegex()` and `Pattern.compile("...")` literals in main sources: (file, pattern as written). */
        fun regexLiterals(): List<Pair<String, String>> {
            // A double-quoted Kotlin string literal: its text between the quotes, escapes as written.
            val literal = "\"((?:[^\"\\\\]|\\\\.)*)\""
            val forms = listOf(
                Regex("Regex\\(\\s*$literal"),
                Regex("$literal\\s*\\.toRegex\\("),
                Regex("Pattern\\.compile\\(\\s*$literal"),
            )
            val src = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            assertThat(src).isNotEmpty()
            return src.flatMap { f ->
                val text = f.readText()
                // A raw-string pattern would slip past the literal above: none is allowed, so none is missed.
                assertThat(text.contains("Regex(\"\"\"") || text.contains("\"\"\".toRegex(")).isFalse()
                forms.flatMap { form -> form.findAll(text).map { f.name to it.groupValues[1] }.toList() }
            }
        }

        /**
         * What in [source] (a pattern as written in a Kotlin source file, backslashes doubled)
         * `java.util.regex` takes and Android's ICU engine refuses:
         *  - an inline flag other than `i`, `m`, `s`, `x` (ICU has no `U`, `u`, `d` or `X`);
         *  - `\p{Is...}` and `\p{java...}` property names;
         *  - `\h` and `\R`, banned here since the first version of this guard.
         */
        fun androidTrouble(source: String): List<String> = buildList {
            Regex("\\(\\?([A-Za-z]*)(?:-([A-Za-z]*))?[:)]").findAll(source).forEach { m ->
                val flags = m.groupValues[1] + m.groupValues[2]
                if (flags.any { it !in "imsx" }) add("inline flag (?$flags)")
            }
            if (Regex("\\\\\\\\[pP]\\{(Is|java)").containsMatchIn(source)) add("a Java-only property name")
            Regex("\\\\\\\\([hR])").findAll(source).forEach { add("backslash " + it.groupValues[1]) }
        }
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
