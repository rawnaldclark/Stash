package com.stash.core.model.weblink

/**
 * A credit's first artist, as Stash on the web cuts it: "A, B feat. C" is "A", "A & B" stays one name. The cut of stash-player
 * `src/lib/radio/station.ts` `primaryArtist` (and of the Worker's `worker/art.ts` `primary`, which differs only in what it
 * gives back when the cut leaves nothing), with JavaScript's classes spelled out so the phone's ICU and the JVM agree with V8:
 * `\s` is [JsString.SPACE] and `/i` without `u` folds ASCII letters only ([JsString.ascii]: "wıth" with a dotless i is no
 * "with" there, though Kotlin's `IGNORE_CASE` would take it).
 */
object Credits {
    private const val S = JsString.SPACE

    /** station.ts's split: `\s*(?:[,;]|\s(?:feat\.?|ft\.?|featuring|with)\s)\s*`, case-insensitive. */
    private val SPLIT = Regex(
        "$S*(?:[,;]|$S(?:${JsString.ascii("feat")}\\.?|${JsString.ascii("ft")}\\.?|${JsString.ascii("featuring")}|${JsString.ascii("with")})$S)$S*",
    )

    /** `credit.split(SPLIT)[0].trim()`: the words before the first split, trimmed; may be empty (", Someone"). */
    fun firstCredit(credit: String): String {
        val m = SPLIT.find(credit) ?: return JsString.trim(credit)
        return JsString.trim(credit.substring(0, m.range.first))
    }

    /** station.ts `primaryArtist`: [firstCredit], else the whole credit trimmed. */
    fun primary(credit: String): String = firstCredit(credit).ifEmpty { JsString.trim(credit) }
}
