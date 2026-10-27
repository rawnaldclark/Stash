package com.stash.feature.record.importer

import com.stash.feature.record.archive.JsText
import java.io.Reader

/** The text isn't JSON as JavaScript's `JSON.parse` reads it (or nests deeper than [JsonPull.MAX_DEPTH]). */
class JsonSyntaxError(message: String) : Exception(message)

/**
 * Reads JSON a token at a time, accepting exactly what JavaScript's `JSON.parse` accepts (ECMA-404, which the web's importer
 * reads every file with): no comments, no trailing commas, no single quotes, whitespace only space, tab, LF and CR, numbers
 * by the JSON grammar, strings with no raw control characters and only the JSON escapes, one value and nothing after it. A
 * history file of hundreds of megabytes is read in a buffer's worth at a time, and a string that isn't kept is never built.
 *
 * Duplicate keys are the caller's to resolve (`JSON.parse` keeps the last): the reader hands every one on.
 *
 * A leading byte-order mark is skipped, as the web's `TextDecoder` drops it before `JSON.parse` sees the text.
 */
internal class JsonPull(private val reader: Reader) {
    /** What comes next. */
    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, TRUE, FALSE, NULL, END }

    private val buf = CharArray(BUFFER)
    private var pos = 0
    private var limit = 0

    private var scopes = IntArray(16)
    private var depth = 0
    private var peeked: Token? = null

    init {
        push(EMPTY_DOCUMENT)
        if (fill(1) && buf[pos] == '﻿') pos++
    }

    /** The next token, without consuming it. */
    fun peek(): Token = peeked ?: doPeek().also { peeked = it }

    fun beginArray() = expect(Token.BEGIN_ARRAY).also { push(EMPTY_ARRAY) }

    fun endArray() = expect(Token.END_ARRAY).also { depth-- }

    fun beginObject() = expect(Token.BEGIN_OBJECT).also { push(EMPTY_OBJECT) }

    fun endObject() = expect(Token.END_OBJECT).also { depth-- }

    /** Inside an array or object: another element or member follows. */
    fun hasNext(): Boolean = peek().let { it != Token.END_ARRAY && it != Token.END_OBJECT && it != Token.END }

    /** Whether the last [nextName] or [nextString] was longer than it returned. */
    var truncated = false
        private set

    /** A member's name: its first [maxName] UTF-16 units ([truncated] says there were more: no name looked for is that long). */
    fun nextName(maxName: Int = MAX_NAME): String {
        expect(Token.NAME)
        return readString(maxName)
    }

    /** A string value's first [max] UTF-16 units, exactly as written; [truncated] says there were more (read and dropped). */
    fun nextString(max: Int): String {
        expect(Token.STRING)
        return readString(max)
    }

    /**
     * A string value as the web's `str` keeps it (export.ts): each control character (U+0000 to U+001F and U+007F) a space, trimmed
     * as JavaScript trims, cut to [max] UTF-16 units; null when nothing is left. Only [max] units are ever held, however long it is.
     */
    fun nextText(max: Int): String? {
        expect(Token.STRING)
        val out = StringBuilder(minOf(max, 64))
        var kept = 0 // out's length up to and including its last non-space
        var started = false
        var full = false
        var longer = false // a non-space after out was full: the trimmed text runs past max
        while (true) {
            val raw = nextStringChar()
            if (raw < 0) break
            val c = raw.toChar().let { if (it <= '\u001F' || it == '\u007F') ' ' else it }
            val space = JsText.isSpace(c)
            if (!started) {
                if (space) continue
                started = true
            }
            if (full) {
                if (!space) longer = true
                continue
            }
            out.append(c)
            if (!space) kept = out.length
            if (out.length == max) full = true
        }
        if (!started) return null
        if (!longer) out.setLength(kept)
        return out.toString()
    }

    /**
     * A number value, as JavaScript reads it (IEEE double, correctly rounded). One longer than [MAX_NUMBER] characters (no real
     * field is: a time played is a dozen digits) is read to its end, checked against the grammar, and comes out NaN, so the row
     * holding it is unreadable: the reader never builds a string a hostile file's digits make as long as it likes.
     */
    fun nextNumber(): Double {
        expect(Token.NUMBER)
        val s = StringBuilder(24)
        return if (scanNumber(s)) s.toString().toDouble() else Double.NaN
    }

    /** A number skipped: its grammar checked, nothing built. */
    private fun skipNumber() {
        scanNumber(null)
    }

    /** Reads a number by the JSON grammar into [into] (when given); false when it ran past [MAX_NUMBER] (what was kept is cut). */
    private fun scanNumber(into: StringBuilder?): Boolean {
        var n = 0L // a Long: a hostile literal can run past an Int before the import's byte budget stops it
        fun keep() {
            val c = take()
            if (into != null && n < MAX_NUMBER) into.append(c)
            n++
        }
        fun digits() {
            while (peekChar() in '0'.code..'9'.code) keep()
        }
        if (peekChar() == '-'.code) keep()
        when (val c = peekChar()) {
            '0'.code -> keep()
            in '1'.code..'9'.code -> digits()
            else -> fail("a number expected, not ${describe(c)}")
        }
        if (peekChar() == '.'.code) {
            keep()
            if (peekChar() !in '0'.code..'9'.code) fail("a digit expected after '.'")
            digits()
        }
        if (peekChar() == 'e'.code || peekChar() == 'E'.code) {
            keep()
            if (peekChar() == '+'.code || peekChar() == '-'.code) keep()
            if (peekChar() !in '0'.code..'9'.code) fail("a digit expected in the exponent")
            digits()
        }
        return n <= MAX_NUMBER
    }

    fun nextBoolean(): Boolean = when (peek()) {
        Token.TRUE -> { peeked = null; true }
        Token.FALSE -> { peeked = null; false }
        else -> fail("a boolean expected, not ${peek()}")
    }

    fun nextNull() = expect(Token.NULL)

    /** Skips the next value, whatever it is (a whole object or array with all inside it), building nothing. */
    fun skipValue() {
        var open = 0
        do {
            when (peek()) {
                Token.BEGIN_ARRAY -> { beginArray(); open++ }
                Token.BEGIN_OBJECT -> { beginObject(); open++ }
                Token.END_ARRAY -> { endArray(); open-- }
                Token.END_OBJECT -> { endObject(); open-- }
                Token.NAME, Token.STRING -> { peeked = null; skipString() }
                Token.NUMBER -> { peeked = null; skipNumber() }
                Token.TRUE, Token.FALSE, Token.NULL -> peeked = null
                Token.END -> fail("the text ended inside a value")
            }
        } while (open > 0)
    }

    /** The whole text was one value, with nothing but whitespace after it (`JSON.parse` refuses anything more). */
    fun endDocument() {
        if (peek() != Token.END) fail("more after the value: ${peek()}")
    }

    // ── tokens ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun expect(t: Token) {
        val p = peek()
        if (p != t) fail("$t expected, not $p")
        peeked = null
    }

    private fun push(scope: Int) {
        if (depth == MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
        if (depth == scopes.size) scopes = scopes.copyOf(depth * 2)
        scopes[depth++] = scope
    }

    private fun doPeek(): Token {
        val scope = scopes[depth - 1]
        when (scope) {
            EMPTY_ARRAY -> scopes[depth - 1] = NONEMPTY_ARRAY
            NONEMPTY_ARRAY -> when (val c = nextNonSpace()) {
                ']'.code -> { pos++; return Token.END_ARRAY }
                ','.code -> pos++
                else -> fail("',' or ']' expected, not ${describe(c)}")
            }
            EMPTY_OBJECT, NONEMPTY_OBJECT -> {
                scopes[depth - 1] = DANGLING_NAME
                if (scope == NONEMPTY_OBJECT) {
                    when (val c = nextNonSpace()) {
                        '}'.code -> { pos++; return Token.END_OBJECT }
                        ','.code -> pos++
                        else -> fail("',' or '}' expected, not ${describe(c)}")
                    }
                }
                return when (val c = nextNonSpace()) {
                    '"'.code -> { pos++; Token.NAME }
                    '}'.code -> if (scope == EMPTY_OBJECT) { pos++; Token.END_OBJECT } else fail("a name expected after ','")
                    else -> fail("a name expected, not ${describe(c)}")
                }
            }
            DANGLING_NAME -> {
                scopes[depth - 1] = NONEMPTY_OBJECT
                val c = nextNonSpace()
                if (c != ':'.code) fail("':' expected, not ${describe(c)}")
                pos++
            }
            EMPTY_DOCUMENT -> scopes[depth - 1] = NONEMPTY_DOCUMENT
            NONEMPTY_DOCUMENT -> {
                val c = nextNonSpace(eofOk = true)
                if (c < 0) return Token.END
                fail("more after the value: ${describe(c)}")
            }
        }
        return when (val c = nextNonSpace()) {
            '"'.code -> { pos++; Token.STRING }
            '{'.code -> { pos++; Token.BEGIN_OBJECT }
            '['.code -> { pos++; Token.BEGIN_ARRAY }
            ']'.code -> if (scope == EMPTY_ARRAY) { pos++; Token.END_ARRAY } else fail("a value expected after ','")
            't'.code -> literal("true", Token.TRUE)
            'f'.code -> literal("false", Token.FALSE)
            'n'.code -> literal("null", Token.NULL)
            '-'.code, in '0'.code..'9'.code -> Token.NUMBER
            else -> fail("a value expected, not ${describe(c)}")
        }
    }

    private fun literal(word: String, t: Token): Token {
        for (ch in word) {
            if (peekChar() != ch.code) fail("'$word' expected")
            pos++
        }
        return t
    }

    // ── strings ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The rest of a string after its opening quote, its first [max] units kept ([truncated]: more were read and dropped). */
    private fun readString(max: Int): String {
        val out = StringBuilder(minOf(max, 32))
        var over = false
        while (true) {
            val c = nextStringChar()
            if (c < 0) break
            if (over) continue
            if (out.length == max) over = true else out.append(c.toChar())
        }
        truncated = over
        return out.toString()
    }

    private fun skipString() {
        while (nextStringChar() >= 0) Unit
    }

    /** The string's next UTF-16 unit, escapes decoded; -1 at its closing quote (consumed). */
    private fun nextStringChar(): Int {
        if (!fill(1)) fail("the text ended inside a string")
        val c = buf[pos++]
        return when {
            c == '"' -> -1
            c == '\\' -> escape()
            c < ' ' -> fail("a control character inside a string")
            else -> c.code
        }
    }

    private fun escape(): Int {
        if (!fill(1)) fail("the text ended inside an escape")
        return when (val e = buf[pos++]) {
            '"' -> '"'.code
            '\\' -> '\\'.code
            '/' -> '/'.code
            'b' -> 8
            'f' -> 12
            'n' -> 10
            'r' -> 13
            't' -> 9
            'u' -> {
                if (!fill(4)) fail("the text ended inside a \\u escape")
                var v = 0
                repeat(4) {
                    val h = Character.digit(buf[pos], 16).takeIf { d -> d >= 0 && buf[pos].code < 128 } ?: fail("a bad \\u escape")
                    v = v * 16 + h
                    pos++
                }
                v
            }
            else -> fail("a bad escape '\\$e'")
        }
    }

    // ── characters ──────────────────────────────────────────────────────────────────────────────────────────────────

    private fun take(): Char = buf[pos++]

    /** The next character without consuming it, or -1 at the end of the text. */
    private fun peekChar(): Int = if (fill(1)) buf[pos].code else -1

    /** The next character that isn't JSON whitespace (left unconsumed); -1 at the end when [eofOk], else an error. */
    private fun nextNonSpace(eofOk: Boolean = false): Int {
        while (true) {
            if (!fill(1)) return if (eofOk) -1 else fail("the text ended early")
            when (buf[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return buf[pos].code
            }
        }
    }

    /** At least [n] characters buffered from [pos]; false when the text ends first. */
    private fun fill(n: Int): Boolean {
        if (limit - pos >= n) return true
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, limit - pos)
            limit -= pos
            pos = 0
        }
        while (limit < n) {
            val r = reader.read(buf, limit, buf.size - limit)
            if (r < 0) return false
            limit += r
        }
        return true
    }

    private fun describe(c: Int) = if (c < 0) "the end" else "'${c.toChar()}'"

    private fun fail(why: String): Nothing = throw JsonSyntaxError(why)

    companion object {
        /** Deeper than this is refused (a Spotify file is four deep). */
        const val MAX_DEPTH = 10_000

        /** The longest number literal read as a number (1,100 characters: far past any double's digits). */
        const val MAX_NUMBER = 1_100

        /** The longest member name read; longer ones are none this reader's callers look for. */
        const val MAX_NAME = 256

        private const val BUFFER = 16 * 1024

        private const val EMPTY_DOCUMENT = 0
        private const val NONEMPTY_DOCUMENT = 1
        private const val EMPTY_ARRAY = 2
        private const val NONEMPTY_ARRAY = 3
        private const val EMPTY_OBJECT = 4
        private const val DANGLING_NAME = 5
        private const val NONEMPTY_OBJECT = 6
    }
}
