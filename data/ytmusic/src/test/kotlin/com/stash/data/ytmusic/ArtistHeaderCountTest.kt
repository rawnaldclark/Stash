package com.stash.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The artist header's listener count: YouTube's monthly audience, read as a number, and never the
 * subscriber count beside it (Sonic Youth, 2026-11-01: "290K" subscribers, "574K monthly audience").
 */
class ArtistHeaderCountTest {

    private fun header(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val subscribers = """"subscriptionButton":{"subscribeButtonRenderer":{"subscriberCountText":{"runs":[{"text":"290K"}]}}}"""

    @Test fun `the monthly audience is read, not the subscriber count beside it`() {
        val h = header("""{$subscribers,"monthlyListenerCount":{"runs":[{"text":"574K monthly audience"}]}}""")
        assertEquals(574_000L, parseMonthlyAudience(h))
    }

    @Test fun `a header with subscribers only has no monthly audience`() {
        assertNull(parseMonthlyAudience(header("""{$subscribers}""")))
    }

    @Test fun `the number split from its words in separate runs still reads`() {
        val h = header("""{"monthlyListenerCount":{"runs":[{"text":"1.2M"},{"text":" monthly audience"}]}}""")
        assertEquals(1_200_000L, parseMonthlyAudience(h))
    }

    @Test fun `simpleText is read too`() {
        val h = header("""{"monthlyListenerCount":{"simpleText":"12.5K monthly audience"}}""")
        assertEquals(12_500L, parseMonthlyAudience(h))
    }

    @Test fun `text that does not start with a number is no number`() {
        val h = header("""{"monthlyListenerCount":{"runs":[{"text":"Monthly audience unavailable"}]}}""")
        assertNull(parseMonthlyAudience(h))
    }

    @Test fun `compact counts in YouTube's English`() {
        assertEquals(574_000L, parseCompactCount("574K monthly audience"))
        assertEquals(1_200_000L, parseCompactCount("1.2M monthly audience"))
        assertEquals(3_000_000_000L, parseCompactCount("3B"))
        assertEquals(1_234L, parseCompactCount("1,234 monthly audience"))
        assertEquals(999L, parseCompactCount("999"))
        // A lower-case scale letter counts the same.
        assertEquals(42_000L, parseCompactCount("42k"))
    }

    @Test fun `a plain number is not scaled by the word after it`() {
        // "574 monthly": the M of "monthly" is a word, not a million.
        assertEquals(574L, parseCompactCount("574 monthly audience"))
        assertEquals(7L, parseCompactCount("7 Million fans"))
    }

    @Test fun `no leading number, no count`() {
        assertNull(parseCompactCount(""))
        assertNull(parseCompactCount("monthly audience"))
        assertNull(parseCompactCount("K"))
    }
}
