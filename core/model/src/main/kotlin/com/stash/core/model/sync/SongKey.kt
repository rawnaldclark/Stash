package com.stash.core.model.sync

import java.text.Normalizer

/**
 * What track identity looks at: a song's words, its ISRC, and (for a song that already has one) its stored key. Sync, the
 * library-file importer and the mirror all match songs through this, exactly as Stash on the web does.
 */
interface SongIdentity {
    val title: String
    val artist: String
    val isrc: String?

    /** A key the song was stored under (`isrc:…` or `title|artist`); null to work it out from the fields. */
    val key: String? get() = null
}

/** A plain [SongIdentity]. Not a data class on purpose: two songs with the same words are two values (the merge pairs by identity). */
class SongRef(
    override val title: String,
    override val artist: String,
    override val isrc: String? = null,
    override val key: String? = null,
) : SongIdentity {
    override fun toString() = "SongRef($title · $artist${isrc?.let { " · $it" } ?: ""})"
}

/**
 * Track identity of sync-v1 (stash-player `docs/sync-v1.md` "Track identity"): a port of the web player's `src/lib/identity.ts`,
 * which is also the `stash-web-library` file's rule. Both run the same vectors (`src/test/resources/sync/identity-vectors.json`,
 * a byte-for-byte copy of stash-player `src/lib/sync/fixtures/identity-vectors.json`).
 *
 * JavaScript parity notes: Java's `\p{L}`, `\p{N}` and `\p{M}` are the Unicode general categories JavaScript's `u` flag uses;
 * `lowercase()` is `toLowerCase(Locale.ROOT)`, which, like JavaScript's, applies the final-sigma rule. Java's `\s` is ASCII only, so the
 * whitespace class is spelled out to be JavaScript's `\s` exactly (it matches U+FEFF and not U+0085).
 */
object SongKey {
    private const val JS_SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

    /** A bracketed credit that starts with feat. / ft. / featuring, as a whole word. Everything else in brackets is identity. */
    private val FEAT_BRACKET = Regex(
        "$JS_SPACE*[(\\[]$JS_SPACE*(?:featuring|feat|ft)(?![\\p{L}\\p{N}])\\.?[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val MARKS = Regex("\\p{M}")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** NFKD, combining marks stripped, lower-cased, a bracketed featuring credit dropped, every run of non-letters/digits one space, trimmed. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(MARKS, "")
            .lowercase()
            .replace(FEAT_BRACKET, "")
            .replace(NON_WORD, " ")
            .trim(' ')

    /** `isrc:<ISRC upper>` when there is an ISRC (a non-empty one), else `fold(title)|fold(artist)`. */
    fun descriptorKey(title: String, artist: String, isrc: String?): String =
        if (!isrc.isNullOrEmpty()) "isrc:${isrc.uppercase()}" else textKey(title, artist)

    fun descriptorKey(s: SongIdentity): String = descriptorKey(s.title, s.artist, s.isrc)

    /** The song's words alone: `fold(title)|fold(artist)`, whatever its ISRC. */
    fun textKey(title: String, artist: String): String = "${fold(title)}|${fold(artist)}"

    fun textKey(s: SongIdentity): String = textKey(s.title, s.artist)

    /** The stored key, else [descriptorKey]. */
    fun keyOf(s: SongIdentity): String = s.key?.takeIf { it.isNotEmpty() } ?: descriptorKey(s)

    /** The ISRC, upper-cased, from the field or a stored `isrc:` key; null when there is none. */
    fun isrcOf(s: SongIdentity): String? {
        s.isrc?.uppercase()?.takeIf { it.isNotEmpty() }?.let { return it }
        val k = keyOf(s)
        return if (k.startsWith("isrc:")) k.substring(5).takeIf { it.isNotEmpty() } else null
    }

    /** One song: the same key, or the same words when at most one of the two has an ISRC. Two different ISRCs are two recordings. */
    fun sameSong(a: SongIdentity, b: SongIdentity): Boolean {
        if (keyOf(a) == keyOf(b)) return true
        if (textKey(a) != textKey(b)) return false
        return isrcOf(a) == null || isrcOf(b) == null
    }
}

/** A lookup by [SongKey.sameSong] that stays O(1) for a big library: own key first, then the words with a compatible ISRC. */
class SongIndex<T> {
    private class Entry<T>(val isrc: String?, val value: T)

    private val byKey = HashMap<String, T>()
    private val byText = HashMap<String, MutableList<Entry<T>>>()

    fun find(s: SongIdentity): T? {
        byKey[SongKey.keyOf(s)]?.let { return it }
        val isrc = SongKey.isrcOf(s)
        return byText[SongKey.textKey(s)]?.firstOrNull { it.isrc == null || isrc == null }?.value
    }

    fun has(s: SongIdentity): Boolean = find(s) != null

    fun add(s: SongIdentity, value: T) {
        byKey.putIfAbsent(SongKey.keyOf(s), value)
        byText.getOrPut(SongKey.textKey(s)) { mutableListOf() }.add(Entry(SongKey.isrcOf(s), value))
    }

    companion object {
        fun <T> of(entries: Iterable<Pair<SongIdentity, T>>): SongIndex<T> = SongIndex<T>().apply { entries.forEach { (s, v) -> add(s, v) } }
    }
}
