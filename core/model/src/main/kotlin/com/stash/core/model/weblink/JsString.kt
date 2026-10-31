package com.stash.core.model.weblink

/**
 * JavaScript's whitespace rules, where a port of the web player's text must come out the same character for character. Kotlin
 * strings are UTF-16 like JavaScript's, so lengths and cuts already agree; only whitespace needs spelling out.
 */
object JsString {
    /**
     * JavaScript's `\s` as a regex class: WhiteSpace (tab, VT, FF, space, NBSP, BOM and every Zs) and LineTerminator (LF, CR, LS,
     * PS). Java's `\s` is ASCII only (and Android's ICU `\s` is Unicode's White_Space, which takes U+0085 and leaves U+FEFF), so
     * neither will do.
     */
    const val SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

    /** One unit of [SPACE]. */
    fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    /** `String.prototype.trim` (Java's `String.trim()` cuts every char up to U+0020 instead). */
    fun trim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isSpace(s[start])) start++
        while (end > start && isSpace(s[end - 1])) end--
        return s.substring(start, end)
    }

    /**
     * A regex class for [word] as JavaScript's `/i` without `u` matches it: each ASCII letter in either case, nothing else.
     * Kotlin's `IGNORE_CASE` adds Unicode case folding (U+212A KELVIN SIGN is a `k`, U+017F LONG S an `s`, U+0131 DOTLESS I an
     * `i`), which JavaScript's never does: a non-ASCII character never matches an ASCII one there.
     */
    fun ascii(word: String): String = buildString {
        for (c in word) {
            when (c) {
                in 'a'..'z', in 'A'..'Z' -> append('[').append(c.uppercaseChar()).append(c.lowercaseChar()).append(']')
                in '0'..'9' -> append(c)
                else -> append('\\').append(c)
            }
        }
    }
}
