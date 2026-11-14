package com.stash.core.data.db

import java.io.ByteArrayOutputStream

/**
 * Drops entries from a Preferences DataStore file's bytes without opening it as a DataStore: a
 * backup reads files that may belong to no DataStore in this process, and opening one for a file
 * the app's own instance also holds fails.
 *
 * The file is DataStore's `PreferenceMap` proto, `map<string, Value> preferences = 1`: a run of
 * length-delimited field-1 entries, each holding its key (field 1, a string) and value (field 2).
 * Every entry whose key is not dropped, and anything else in the file, is copied byte for byte.
 *
 * Strict: bytes it can't read as that proto (a truncated `.tmp` copy, a file that isn't one) are an
 * [IllegalArgumentException], so a caller that must not carry a dropped key leaves the file out.
 */
internal object PreferencesFileScrubber {

    /** [bytes] without the entries whose key is in [keys]. */
    fun scrub(bytes: ByteArray, keys: Set<String>): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val start = i
            val (tag, afterTag) = varint(bytes, i)
            val field = (tag ushr 3).toInt()
            i = afterTag
            var drop = false
            when ((tag and 7L).toInt()) {
                WIRE_VARINT -> i = varint(bytes, i).second
                WIRE_FIXED64 -> i += 8
                WIRE_LEN -> {
                    val (len, p) = varint(bytes, i)
                    require(len >= 0 && len <= bytes.size - p) { "length past the end" }
                    i = p + len.toInt()
                    if (field == 1) drop = keyOf(bytes, p, i) in keys
                }
                WIRE_FIXED32 -> i += 4
                else -> throw IllegalArgumentException("not a preferences file")
            }
            require(field > 0 && i <= bytes.size) { "not a preferences file" }
            if (!drop) out.write(bytes, start, i - start)
        }
        return out.toByteArray()
    }

    /** The key of the map entry in `bytes[from until to]`. */
    private fun keyOf(bytes: ByteArray, from: Int, to: Int): String? {
        var i = from
        var key: String? = null
        while (i < to) {
            val (tag, afterTag) = varint(bytes, i, to)
            i = afterTag
            when ((tag and 7L).toInt()) {
                WIRE_VARINT -> i = varint(bytes, i, to).second
                WIRE_FIXED64 -> i += 8
                WIRE_LEN -> {
                    val (len, p) = varint(bytes, i, to)
                    require(len >= 0 && len <= to - p) { "length past the entry" }
                    i = p + len.toInt()
                    if ((tag ushr 3).toInt() == 1) key = String(bytes, p, len.toInt(), Charsets.UTF_8)
                }
                WIRE_FIXED32 -> i += 4
                else -> throw IllegalArgumentException("not a preferences entry")
            }
            require(i <= to) { "field past the entry" }
        }
        return key
    }

    /** A base-128 varint at [at], and where the next field starts. */
    private fun varint(bytes: ByteArray, at: Int, end: Int = bytes.size): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = at
        while (true) {
            require(i < end && shift < 64) { "varint past the end" }
            val b = bytes[i++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5
}
