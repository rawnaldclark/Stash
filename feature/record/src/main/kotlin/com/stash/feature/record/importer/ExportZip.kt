package com.stash.feature.record.importer

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** It isn't a zip this reader can open (zip.ts `ZipError`): counted as unreadable, never a crash. */
class ZipError(message: String) : IOException(message)

/**
 * One file of an export: its path (a zip's entries are prefixed with the zip's own name and `!/`, as the web names them), its
 * size as the zip says, and how to read it. [broken] is a zip inside that couldn't be opened: counted as unreadable.
 */
internal class ExportEntry(
    val name: String,
    val size: Long,
    val broken: Boolean = false,
    private val opener: () -> InputStream,
) {
    /** The file's bytes, streamed. Throws [IOException] (a [ZipError] among them) when it can't be read. */
    fun open(): InputStream = opener()
}

/**
 * The web's zip reader (stash-player `src/lib/spotify/zip.ts`), on the phone: the central directory, then each entry read where
 * it lies, stored (0) or deflated (8), Zip64 sizes and offsets, zips inside zips (up to [MAX_DEPTH] deep). Entries are listed
 * in the central directory's order, as the web lists them, so plays come in the web's order too. Nothing is read whole: an
 * entry is a stream, a zip stored inside a zip is read in place, and one deflated inside is inflated to a file in [scratch]
 * (deleted by [close]), never into memory. Encrypted entries and other methods are refused; CRCs aren't checked (a damaged
 * deflate stream fails by itself, and the JSON reader after it refuses garbage).
 */
internal class ExportZip(private val scratch: File) : Closeable {
    private val open = ArrayList<RandomAccessFile>()
    private val temps = ArrayList<File>()

    /** Every file in [file] (nested zips opened), contents unread. Throws [ZipError] when it isn't a readable zip. */
    fun entries(file: File, prefix: String): List<ExportEntry> = entriesOf(fileSource(file), prefix, 0)

    override fun close() {
        open.forEach { runCatching { it.close() } }
        open.clear()
        temps.forEach { it.delete() }
        temps.clear()
    }

    /** Bytes to read from: a file, or part of one (a zip stored inside a zip). */
    private class Source(val raf: RandomAccessFile, val start: Long, val size: Long) {
        fun bytes(from: Long, to: Long): ByteArray {
            val end = minOf(to, size)
            if (from < 0 || from > end) throw ZipError("broken zip")
            val n = end - from
            if (n > Int.MAX_VALUE) throw ZipError("zip too large")
            val out = ByteArray(n.toInt())
            synchronized(raf) {
                raf.seek(start + from)
                raf.readFully(out)
            }
            return out
        }

        fun within(from: Long, n: Long): Source = Source(raf, start + from, minOf(n, maxOf(0, size - from)))

        /** [n] bytes from [from], as a stream (each read seeks: an entry's stream and the next one's can interleave). */
        fun stream(from: Long, n: Long): InputStream = object : InputStream() {
            private var at = from
            private val end = minOf(from + n, size)

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (at >= end) return -1
                val take = minOf(len.toLong(), end - at).toInt()
                val got = synchronized(raf) {
                    raf.seek(start + at)
                    raf.read(b, off, take)
                }
                if (got < 0) return -1
                at += got
                return got
            }
        }
    }

    private fun fileSource(file: File): Source {
        val raf = RandomAccessFile(file, "r")
        open += raf
        return Source(raf, 0, raf.length())
    }

    private fun entriesOf(src: Source, prefix: String, depth: Int): List<ExportEntry> {
        if (src.size < 22) throw ZipError("not a zip")
        // The end record is in the last 22 + 65,535 bytes (its comment is at most 64 KiB)
        val tailStart = maxOf(0L, src.size - (22 + 0xffff))
        val tail = src.bytes(tailStart, src.size)
        var e = -1
        for (i in tail.size - 22 downTo 0) if (u32(tail, i) == EOCD) { e = i; break }
        if (e < 0) throw ZipError("not a zip")
        var count = u16(tail, e + 10).toLong()
        var cdSize = u32(tail, e + 12)
        var cdOff = u32(tail, e + 16)
        if (count == 0xffffL || cdSize == 0xffffffffL || cdOff == 0xffffffffL) {
            val l = e - 20
            if (l < 0 || u32(tail, l) != EOCD64_LOCATOR) throw ZipError("broken zip64")
            val recOff = u64(tail, l + 8)
            val rec = src.bytes(recOff, recOff + 56)
            if (rec.size < 56 || u32(rec, 0) != EOCD64) throw ZipError("broken zip64")
            count = u64(rec, 32)
            cdSize = u64(rec, 40)
            cdOff = u64(rec, 48)
        }
        if (count > MAX_ENTRIES || cdOff + cdSize > src.size) throw ZipError("broken zip")
        val cd = src.bytes(cdOff, cdOff + cdSize)

        class Raw(val name: String, val flags: Int, val method: Int, val csize: Long, val usize: Long, val loc: Long)
        val raws = ArrayList<Raw>()
        var p = 0
        for (i in 0 until count) {
            if (p + 46 > cd.size || u32(cd, p) != CEN) throw ZipError("broken zip")
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            var csize = u32(cd, p + 20)
            var usize = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            var loc = u32(cd, p + 42)
            if (p + 46 + nameLen > cd.size) throw ZipError("broken zip")
            // TextDecoder's UTF-8: a bad byte is U+FFFD, never an error
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8).replace('\\', '/')
            // Zip64 extra field: the 0xffffffff values, in this order
            var x = p + 46 + nameLen
            val xEnd = x + extraLen
            while (x + 4 <= xEnd && x + 4 <= cd.size) {
                val id = u16(cd, x)
                val len = u16(cd, x + 2)
                if (id == 0x0001) {
                    var q = x + 4
                    if (usize == 0xffffffffL) { usize = u64(cd, q); q += 8 }
                    if (csize == 0xffffffffL) { csize = u64(cd, q); q += 8 }
                    if (loc == 0xffffffffL) loc = u64(cd, q)
                }
                x += 4 + len
            }
            p = xEnd + commentLen
            if (name.endsWith('/')) continue // a folder
            raws += Raw(prefix + name, flags, method, csize, usize, loc)
        }

        val out = ArrayList<ExportEntry>(raws.size)
        for (r in raws) {
            // Where the data starts: after the local header's own name and extra (their lengths can differ from the central copy's)
            val dataStart = {
                if (r.flags and 1 != 0) throw ZipError("encrypted zip")
                val lh = src.bytes(r.loc, r.loc + 30)
                if (lh.size < 30 || u32(lh, 0) != LOC) throw ZipError("broken zip")
                r.loc + 30 + u16(lh, 26) + u16(lh, 28)
            }
            val read = {
                if (r.usize > MAX_ENTRY) throw ZipError("entry too large")
                val start = dataStart()
                when (r.method) {
                    0 -> src.stream(start, r.csize)
                    8 -> inflating(src.stream(start, r.csize), r.usize)
                    else -> throw ZipError("unsupported zip method")
                }
            }
            if (depth < MAX_DEPTH && ExportText.isZipName(r.name)) {
                // A zip inside: its own entries instead of it (Spotify's export zip, or a folder of them zipped again)
                try {
                    val inner = if (r.method == 0) src.within(dataStart(), r.csize) else inflateToFile(read())
                    out += entriesOf(inner, "${r.name}!/", depth + 1)
                } catch (e: Exception) {
                    out += ExportEntry(r.name, r.usize, broken = true, opener = read)
                }
                continue
            }
            out += ExportEntry(r.name, r.usize, opener = read)
        }
        return out
    }

    /** A deflated zip inside a zip, inflated to a scratch file (the web inflates it into memory; a phone shouldn't). */
    private fun inflateToFile(input: InputStream): Source {
        scratch.mkdirs()
        val f = File.createTempFile("nested", ".zip", scratch)
        temps += f
        input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        return fileSource(f)
    }

    /**
     * Raw deflate, as the web's `DecompressionStream('deflate-raw')`, refusing more than the entry says it holds (when it says)
     * or [MAX_ENTRY]. A zero byte is fed after the data, as java.util.zip's own ZipFile does: the inflater may want one past the
     * end of a raw stream.
     */
    private fun inflating(compressed: InputStream, expected: Long): InputStream {
        val fed = SequenceInputStream(compressed, ByteArrayInputStream(ByteArray(1)))
        val inflater = Inflater(true)
        val inflated = object : InflaterInputStream(fed, inflater, 64 * 1024) {
            override fun close() {
                super.close()
                inflater.end()
            }
        }
        return Capped(inflated, if (expected > 0) minOf(expected, MAX_ENTRY) else MAX_ENTRY)
    }

    /** Fails once more than [max] bytes come out (zip.ts: "entry larger than it says"). */
    private class Capped(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var n = 0L

        override fun read(): Int {
            val c = super.read()
            if (c >= 0 && ++n > max) throw ZipError("entry larger than it says")
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) {
                n += r
                if (n > max) throw ZipError("entry larger than it says")
            }
            return r
        }
    }

    companion object {
        /** One entry inflated is at most this (zip.ts `MAX_ENTRY`; Spotify splits history into files of about 12 MB). */
        const val MAX_ENTRY = 512L * 1024 * 1024

        /** Zips inside zips, at most this deep (zip.ts `MAX_DEPTH`). */
        const val MAX_DEPTH = 3

        private const val MAX_ENTRIES = 20_000

        private const val EOCD = 0x06054b50L
        private const val EOCD64_LOCATOR = 0x07064b50L
        private const val EOCD64 = 0x06064b50L
        private const val CEN = 0x02014b50L
        private const val LOC = 0x04034b50L

        private fun u16(b: ByteArray, o: Int): Int {
            if (o + 2 > b.size) throw ZipError("broken zip")
            return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(b: ByteArray, o: Int): Long {
            if (o + 4 > b.size) throw ZipError("broken zip")
            return (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
        }

        private fun u64(b: ByteArray, o: Int): Long {
            val lo = u32(b, o)
            val hi = u32(b, o + 4)
            // zip.ts refuses what isn't a safe integer in JavaScript (2^53)
            if (hi >= (1L shl 21)) throw ZipError("zip too large")
            return lo + hi * (1L shl 32)
        }
    }
}
