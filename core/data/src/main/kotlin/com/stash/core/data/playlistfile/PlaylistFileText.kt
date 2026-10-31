package com.stash.core.data.playlistfile

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A playlist file's bytes as text. Exporters disagree on encodings: Apple Music writes UTF-16 with a byte-order mark (and old
 * Mac line endings), M3U8 and most CSVs are UTF-8, and an older player's .m3u is in the PC's own code page (Windows-1252, or
 * Windows-1251 on a Russian PC, where a Yandex Music listener's files come from).
 */
internal object PlaylistFileText {
    private val WINDOWS_1252: Charset = charsetOr("windows-1252")
    private val WINDOWS_1251: Charset = charsetOr("windows-1251")

    /**
     * [bytes] decoded, without its byte-order mark, every line ending a `\n`. [truncated]: [bytes] is only the start of the file,
     * so a UTF-8 character the cut split in two is dropped rather than taken as a sign of another encoding.
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false): String =
        decodeRaw(bytes, truncated).replace("\r\n", "\n").replace('\r', '\n')

    private fun decodeRaw(bytes: ByteArray, truncated: Boolean): String {
        val n = bytes.size
        if (n >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return utf16(bytes, 2, Charsets.UTF_16LE)
        if (n >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return utf16(bytes, 2, Charsets.UTF_16BE)
        utf16WithoutMark(bytes)?.let { return utf16(bytes, 0, it) }
        val start = if (n >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val end = maxOf(start, if (truncated) wholeUtf8End(bytes) else n)
        strictUtf8(bytes, start, end)?.let { return it }
        // Not UTF-8: a code page. Which one can't be read from the file, only guessed from its letters.
        return String(bytes, start, n - start, if (looksCyrillic(bytes)) WINDOWS_1251 else WINDOWS_1252)
    }

    /** UTF-16 from [from], an odd last byte (a cut file) left out. */
    private fun utf16(bytes: ByteArray, from: Int, charset: Charset): String =
        String(bytes, from, (bytes.size - from) and 1.inv(), charset)

    /** UTF-16 without a byte-order mark: text that is mostly ASCII has a zero in every other byte. */
    private fun utf16WithoutMark(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, SNIFF_BYTES) and 1.inv()
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

    private fun strictUtf8(bytes: ByteArray, from: Int, to: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, to - from))
            .toString()
    } catch (e: CharacterCodingException) {
        null
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
    private const val SNIFF_BYTES = 512
}
