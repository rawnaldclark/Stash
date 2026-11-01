package com.stash.core.data.playlistfile

import java.nio.charset.Charset

/**
 * A playlist file's bytes as text. Exporters disagree on encodings: Apple Music writes UTF-16 with a byte-order mark (and old
 * Mac line endings), M3U8 and most CSVs are UTF-8, and an older player's .m3u is in the PC's own code page (Windows-1252, or
 * Windows-1251 on a Russian PC, where a Yandex Music listener's files come from).
 */
internal object PlaylistFileText {
    private val WINDOWS_1252: Charset = charsetOr("windows-1252")
    private val WINDOWS_1251: Charset = charsetOr("windows-1251")

    /** Windows-1252's characters for the bytes 0x80–0xFF: what a stray byte in UTF-8 text most likely meant. */
    private val WINDOWS_1252_HIGH: String = String(ByteArray(128) { (it + 0x80).toByte() }, WINDOWS_1252)

    /** How many bytes [isMedia] looks at. */
    const val SNIFF_BYTES = 4 * 1024

    /**
     * [bytes] decoded, without its byte-order mark, every line ending a `\n`. [truncated]: [bytes] is only the start of the file,
     * so a UTF-8 character the cut split in two is dropped rather than taken as a sign of another encoding. [utf8]: the file is
     * UTF-8 by its kind (an .m3u8), whatever stray bytes it holds.
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false, utf8: Boolean = false): String {
        val text = decodeRaw(bytes, truncated, utf8)
        return if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text
    }

    /**
     * An audio, video, image, archive or PDF file picked by mistake, known from its first bytes: never read as a playlist (an
     * MP3 or a video can be large, and its bytes as text are never songs).
     */
    fun isMedia(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, bytes.size, SNIFF_BYTES)
        fun at(offset: Int, vararg signature: Int) =
            n >= offset + signature.size && signature.indices.all { (bytes[offset + it].toInt() and 0xFF) == signature[it] }
        fun at(offset: Int, ascii: String) = at(offset, *IntArray(ascii.length) { ascii[it].code })
        return at(0, "ID3") || at(4, "ftyp") || at(0, "RIFF") || at(0, "OggS") || at(0, "fLaC") || at(0, "%PDF") ||
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x89, 0x50, 0x4E, 0x47) || at(0, 0xFF, 0xD8, 0xFF)
    }

    private fun decodeRaw(bytes: ByteArray, truncated: Boolean, utf8: Boolean): String {
        val n = bytes.size
        if (n >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return utf16(bytes, 2, Charsets.UTF_16LE)
        if (n >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return utf16(bytes, 2, Charsets.UTF_16BE)
        utf16WithoutMark(bytes)?.let { return utf16(bytes, 0, it) }
        val start = if (n >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val end = maxOf(start, if (truncated) wholeUtf8End(bytes) else n)
        var valid = 0
        var invalid = 0
        var i = start
        while (i < end) {
            val length = utf8Length(bytes, i, end)
            if (length == 0) invalid++ else if (length > 1) valid++
            i += maxOf(length, 1)
        }
        if (invalid == 0) return String(bytes, start, end - start, Charsets.UTF_8)
        // A Russian PC's code page: Cyrillic letters throughout, hardly any of them forming UTF-8 by chance.
        if (!utf8 && valid < invalid && looksCyrillic(bytes)) return String(bytes, start, n - start, WINDOWS_1251)
        // UTF-8 where it is well formed, any other byte as Windows-1252 has it: a stray "é" in a hand-edited UTF-8 list can't
        // turn every "Björk" into "BjÃ¶rk", and a Windows-1252 file (no well-formed UTF-8 in it) reads exactly as before.
        return mixed(bytes, start, end)
    }

    /**
     * The length of the well-formed UTF-8 character at [i] (1 for ASCII), or 0 when the byte there starts none: a stray
     * continuation, an overlong form, a surrogate, past U+10FFFF, or cut short before [end].
     */
    private fun utf8Length(b: ByteArray, i: Int, end: Int): Int {
        val c = b[i].toInt() and 0xFF
        val length = when (c) {
            in 0x00..0x7F -> return 1
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> return 0
        }
        if (i + length > end) return 0
        for (k in 1 until length) if ((b[i + k].toInt() and 0xC0) != 0x80) return 0
        val c1 = b[i + 1].toInt() and 0xFF
        if ((c == 0xE0 && c1 < 0xA0) || (c == 0xED && c1 > 0x9F) || (c == 0xF0 && c1 < 0x90) || (c == 0xF4 && c1 > 0x8F)) return 0
        return length
    }

    /** UTF-8 where it is well formed; each byte where it isn't, as Windows-1252 has it. */
    private fun mixed(b: ByteArray, from: Int, to: Int): String {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val c = b[i].toInt() and 0xFF
            val length = utf8Length(b, i, to)
            when (length) {
                0 -> out.append(WINDOWS_1252_HIGH[c - 0x80])
                1 -> out.append(c.toChar())
                else -> {
                    var cp = c and (0xFF shr (length + 1))
                    for (k in 1 until length) cp = (cp shl 6) or (b[i + k].toInt() and 0x3F)
                    out.appendCodePoint(cp)
                }
            }
            i += maxOf(length, 1)
        }
        return out.toString()
    }

    /** UTF-16 from [from], an odd last byte (a cut file) left out. */
    private fun utf16(bytes: ByteArray, from: Int, charset: Charset): String =
        String(bytes, from, (bytes.size - from) and 1.inv(), charset)

    /** UTF-16 without a byte-order mark: text that is mostly ASCII has a zero in every other byte. */
    private fun utf16WithoutMark(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, UTF16_SNIFF_BYTES) and 1.inv()
        if (n < 4) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until n) {
            if (bytes[i] != ZERO) continue
            if (i % 2 == 0) evenZeros++ else oddZeros++
        }
        val pairs = n / 2
        return when {
            oddZeros * 10 >= pairs * 7 && evenZeros * 10 < pairs -> Charsets.UTF_16LE
            evenZeros * 10 >= pairs * 7 && oddZeros * 10 < pairs -> Charsets.UTF_16BE
            else -> null
        }
    }

    /** Where [bytes] ends without a UTF-8 character cut in two. */
    private fun wholeUtf8End(bytes: ByteArray): Int {
        val n = bytes.size
        var i = n - 1
        while (i >= 0 && n - i <= 4 && (bytes[i].toInt() and 0xC0) == 0x80) i--
        if (i < 0 || n - i > 4) return n
        val lead = bytes[i].toInt() and 0xFF
        val length = when {
            lead >= 0xF0 -> 4
            lead >= 0xE0 -> 3
            lead >= 0xC0 -> 2
            else -> 1
        }
        return if (n - i < length) i else n
    }

    /**
     * Windows-1251 rather than Windows-1252: a quarter or more of the letters are past ASCII. Cyrillic in 1251 is nearly all high
     * bytes; Western text in 1252 has an accented letter here and there ("Beyoncé", "Sigur Rós").
     */
    private fun looksCyrillic(bytes: ByteArray): Boolean {
        var letters = 0
        var high = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c >= 0xC0) {
                high++
                letters++
            } else if (c in 0x41..0x5A || c in 0x61..0x7A) {
                letters++
            }
        }
        return letters > 0 && high * 4 >= letters
    }

    private fun charsetOr(name: String): Charset = try {
        Charset.forName(name)
    } catch (e: Exception) {
        Charsets.ISO_8859_1
    }

    private const val ZERO: Byte = 0
    private const val UTF16_SNIFF_BYTES = 512
}
