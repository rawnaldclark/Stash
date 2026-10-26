package com.stash.feature.record.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * [JsonPull] accepts exactly what JavaScript's `JSON.parse` accepts: each text below went through node's `JSON.parse` (node 24),
 * and the Boolean is whether it parsed. Then the reader's own behaviour: values, the bounded text the web's `str` keeps, skipping.
 */
class JsonPullTest {
    private val jsonParse = listOf(
        "[]" to true,
        "{}" to true,
        "[1,]" to false,
        "[1 2]" to false,
        "{\"a\":1,}" to false,
        "{\"a\" 1}" to false,
        "{a:1}" to false,
        "[01]" to false,
        "[-0]" to true,
        "[-]" to false,
        "[1.]" to false,
        "[.5]" to false,
        "[1e5]" to true,
        "[1E+5]" to true,
        "[1e]" to false,
        "[+1]" to false,
        "[0x10]" to false,
        "[NaN]" to false,
        "[Infinity]" to false,
        "[true,false,null]" to true,
        "[tru]" to false,
        "[truex]" to false,
        "[nul]" to false,
        "\"\\u00e9\"" to true,
        "\"\\ud800\"" to true,
        "\"\\x41\"" to false,
        "\"\\u12\"" to false,
        "\"a\u0009b\"" to false,
        "\"a\u007fb\"" to true,
        "\"a\u0085b\"" to true,
        "[\"\\/\"]" to true,
        "  [1]  " to true,
        "[1] x" to false,
        "[1]\u000a\u000a" to true,
        "//c\u000a[1]" to false,
        "[1]/*c*/" to false,
        "\u00a0[1]" to false,
        "[1,\u000a2]" to true,
        "{\"a\":{\"b\":[1,{\"c\":null}]}}" to true,
        "[[[[[]]]]]" to true,
        "\"\"" to true,
        "\"\\\"\"" to true,
        "{\"a\":1,\"a\":2}" to true,
        "[1,,2]" to false,
        "[,1]" to false,
        "{,}" to false,
        "{\"a\":}" to false,
        "[\"a\",]" to false,
        "1" to true,
        "-1.5e-3" to true,
        "\"unterminated" to false,
        "[\"a\\\"]" to false,
        "[\"\\u0000\"]" to true,
        "[\"\\uDEAD\\uBEEF\"]" to true,
        "[1]]" to false,
        "{\"a\":1}}" to false,
        "[\"\u2028\"]" to true,
        "[ 1 ]" to true,
        "\u0009\u000d\u000a[1]\u0009\u000d\u000a" to true,
        "[1e999]" to true,
        "[123456789012345678901234567890]" to true,
        "{\"\":0}" to true,
        "\u000b[1]" to false,
        "\u000c[1]" to false,
        "[1]\u0000" to false,
        "[\"\\U0041\"]" to false,
        "[\"\\u004G\"]" to false,
        "[1.5e+]" to false,
        "[-01]" to false,
        "[0.0e0]" to true,
        "['a']" to false,
        "[\u000a]" to true,
        "{\u000a}" to true,
        "{\"a\":[}" to false,
        "[{]" to false,
        "[\"a\"\u000a,\"b\"]" to true,
        "{\"a\":1 \"b\":2}" to false,
        "true" to true,
        "nulll" to false,
        "\"\\b\\f\\n\\r\\t\"" to true,
        "" to false,
        " " to false,
        "[\"\u0001\"]" to false,
    )

    /** Reads the whole text as one value and nothing after it, as JSON.parse does. */
    private fun parses(text: String): Boolean = try {
        JsonPull(StringReader(text)).apply { skipValue(); endDocument() }
        true
    } catch (e: JsonSyntaxError) {
        false
    }

    @Test fun `it takes what JSON dot parse takes, and refuses what it refuses`() {
        for ((text, ok) in jsonParse) assertEquals(text, ok, parses(text))
    }

    @Test fun `a leading byte order mark is skipped, as TextDecoder drops it`() {
        assertTrue(parses("\uFEFF[1]"))
        assertFalse(parses("\uFEFF\uFEFF[1]"))
    }

    @Test fun `values come out as JavaScript reads them`() {
        val j = JsonPull(StringReader("{\"s\":\"a\\u00e9\\/b\",\"n\":-1.5e2,\"t\":true,\"f\":false,\"z\":null,\"big\":1e999,\"o\":{\"x\":[1,2]}}"))
        j.beginObject()
        assertEquals("s", j.nextName())
        assertEquals("a\u00e9/b", j.nextString(100))
        assertEquals("n", j.nextName())
        assertEquals(-150.0, j.nextNumber(), 0.0)
        assertEquals("t", j.nextName())
        assertTrue(j.nextBoolean())
        assertEquals("f", j.nextName())
        assertFalse(j.nextBoolean())
        assertEquals("z", j.nextName())
        j.nextNull()
        assertEquals("big", j.nextName())
        assertEquals(Double.POSITIVE_INFINITY, j.nextNumber(), 0.0)
        assertEquals("o", j.nextName())
        j.skipValue()
        j.endObject()
        j.endDocument()
    }

    @Test fun `a long string comes out cut, and says so`() {
        val j = JsonPull(StringReader("[\"abcdef\",\"abc\"]"))
        j.beginArray()
        assertEquals("abc", j.nextString(3))
        assertTrue(j.truncated)
        assertEquals("abc", j.nextString(3))
        assertFalse(j.truncated)
        j.endArray()
    }

    @Test fun `text is cleaned, trimmed and cut as the web's str does`() {
        fun text(json: String, max: Int = 5): String? = JsonPull(StringReader(json)).nextText(max)
        assertEquals("a b", text("\"\\u0000a\\tb\\u007f \""))
        assertNull(text("\"  \\u00a0\\u2028 \""))
        assertEquals("abcde", text("\"  abcdefgh\""))
        // The cut keeps inner spaces when the text runs on past it, and trims them when only spaces follow
        assertEquals("ab   ", text("\"ab   x\""))
        assertEquals("ab", text("\"ab      \""))
        // U+0085 is not a control character the web replaces, nor a space JavaScript trims
        assertEquals("\u0085a", text("\"\u0085a\""))
        // Cut through a surrogate pair, as slice does
        assertEquals("abcd\uD83D", text("\"abcd\uD83D\uDE00\""))
    }

    @Test fun `nesting deeper than the limit is refused, not a crash`() {
        val deep = "[".repeat(JsonPull.MAX_DEPTH + 1) + "]".repeat(JsonPull.MAX_DEPTH + 1)
        assertThrows(JsonSyntaxError::class.java) { JsonPull(StringReader(deep)).skipValue() }
    }

    @Test fun `a long file is read through a small buffer`() {
        val rows = (0 until 20_000).joinToString(",", "[", "]") { "{\"i\":$it,\"pad\":\"${"x".repeat(20)}\"}" }
        val j = JsonPull(StringReader(rows))
        var n = 0
        j.beginArray()
        while (j.hasNext()) {
            j.beginObject()
            while (j.hasNext()) {
                if (j.nextName() == "i") assertEquals(n.toDouble(), j.nextNumber(), 0.0) else j.skipValue()
            }
            j.endObject()
            n++
        }
        j.endArray()
        j.endDocument()
        assertEquals(20_000, n)
    }
}
