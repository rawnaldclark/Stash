package com.stash.feature.record.archive

/**
 * JavaScript's string rules where the web's and this port's text must come out the same, character for character: an id made
 * from a title cut differently would be another play. Kotlin strings are UTF-16 like JavaScript's, so lengths and cuts (which
 * can split a surrogate pair, as `slice` does) already agree; only trimming and whitespace need spelling out.
 */
internal object JsText {
    /**
     * JavaScript's `\s` and what `trim()` removes: WhiteSpace (tab, VT, FF, space, NBSP, BOM and every Zs) and LineTerminator
     * (LF, CR, LS, PS). Java's `\s` is ASCII only and `String.trim()` cuts every char up to U+0020, so neither will do.
     */
    fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    /** The same set as a regex class, for patterns ported from the web (`\s` there). */
    const val SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

    /** `String.prototype.trim`. */
    fun trim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isSpace(s[start])) start++
        while (end > start && isSpace(s[end - 1])) end--
        return s.substring(start, end)
    }

    /** `s.slice(0, max)`: the first [max] UTF-16 units. */
    fun slice(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}
