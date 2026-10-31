package com.stash.feature.record.archive

import com.stash.core.model.weblink.JsString

/**
 * JavaScript's string rules where the web's and this port's text must come out the same, character for character: an id made
 * from a title cut differently would be another play. Kotlin strings are UTF-16 like JavaScript's, so lengths and cuts (which
 * can split a surrogate pair, as `slice` does) already agree; only trimming and whitespace need spelling out (core:model's
 * [JsString], shared with the song key and the credit's cut).
 */
internal object JsText {
    /**
     * JavaScript's `\s` and what `trim()` removes: WhiteSpace (tab, VT, FF, space, NBSP, BOM and every Zs) and LineTerminator
     * (LF, CR, LS, PS). Java's `\s` is ASCII only and `String.trim()` cuts every char up to U+0020, so neither will do.
     */
    fun isSpace(c: Char): Boolean = JsString.isSpace(c)

    /** The same set as a regex class, for patterns ported from the web (`\s` there). */
    const val SPACE = JsString.SPACE

    /** `String.prototype.trim`. */
    fun trim(s: String): String = JsString.trim(s)

    /** `s.slice(0, max)`: the first [max] UTF-16 units. */
    fun slice(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}
