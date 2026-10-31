package com.stash.feature.record.importer

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Zips written by hand, for the reader's edge cases java.util.zip won't write: Zip64 records on a small file, an entry flagged
 * encrypted, one that inflates to more than its directory record says, directory records that share their data.
 */
internal object TestZip {
    class Entry(
        val name: String,
        val data: ByteArray,
        val deflate: Boolean = false,
        /** General purpose flags (bit 0: encrypted). */
        val flags: Int = 0,
        /** The size the directory claims, when it lies. */
        val claimSize: Long? = null,
    )

    fun entry(name: String, text: String, deflate: Boolean = false) = Entry(name, text.toByteArray(), deflate)

    /**
     * [entries] in a zip. [zip64]: every directory record's sizes and offset in a Zip64 extra field, and the end record pointing
     * at a Zip64 end record. [aliases]: more directory records (name to index) pointing at an earlier entry's data.
     */
    fun build(entries: List<Entry>, zip64: Boolean = false, aliases: List<Pair<String, Int>> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        class Written(val e: Entry, val name: ByteArray, val crc: Long, val csize: Long, val offset: Long)
        val written = ArrayList<Written>()
        for (e in entries) {
            val body = if (e.deflate) deflate(e.data) else e.data
            val crc = CRC32().apply { update(e.data) }.value
            val name = e.name.toByteArray(Charsets.UTF_8)
            val offset = out.size().toLong()
            out.u32(0x04034b50).u16(20).u16(e.flags).u16(if (e.deflate) 8 else 0).u16(0).u16(0)
                .u32(crc).u32(body.size.toLong()).u32(e.data.size.toLong()).u16(name.size).u16(0)
            out.write(name)
            out.write(body)
            written += Written(e, name, crc, body.size.toLong(), offset)
        }
        val records = written.map { it to it.name } + aliases.map { (n, i) -> written[i] to n.toByteArray(Charsets.UTF_8) }
        val cdOff = out.size().toLong()
        for ((w, name) in records) {
            val usize = w.e.claimSize ?: w.e.data.size.toLong()
            val extra = if (zip64) ByteArrayOutputStream().u16(0x0001).u16(24).u64(usize).u64(w.csize).u64(w.offset).toByteArray() else ByteArray(0)
            out.u32(0x02014b50).u16(45).u16(if (zip64) 45 else 20).u16(w.e.flags).u16(if (w.e.deflate) 8 else 0).u16(0).u16(0)
                .u32(w.crc)
                .u32(if (zip64) 0xffffffffL else w.csize)
                .u32(if (zip64) 0xffffffffL else usize)
                .u16(name.size).u16(extra.size).u16(0).u16(0).u16(0).u32(0)
                .u32(if (zip64) 0xffffffffL else w.offset)
            out.write(name)
            out.write(extra)
        }
        val cdSize = out.size() - cdOff
        val count = records.size.toLong()
        if (zip64) {
            val recOff = out.size().toLong()
            out.u32(0x06064b50).u64(44).u16(45).u16(45).u32(0).u32(0).u64(count).u64(count).u64(cdSize).u64(cdOff)
            out.u32(0x07064b50).u32(0).u64(recOff).u32(1)
            out.u32(0x06054b50).u16(0).u16(0).u16(0xffff).u16(0xffff).u32(0xffffffffL).u32(0xffffffffL).u16(0)
        } else {
            out.u32(0x06054b50).u16(0).u16(0).u16(count.toInt()).u16(count.toInt()).u32(cdSize).u32(cdOff).u16(0)
        }
        return out.toByteArray()
    }

    /** Raw deflate, as a zip holds it. */
    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(v: Int) = apply {
        write(v and 0xff)
        write((v ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.u32(v: Long) = apply {
        u16((v and 0xffff).toInt())
        u16(((v ushr 16) and 0xffff).toInt())
    }

    private fun ByteArrayOutputStream.u32(v: Int) = u32(v.toLong() and 0xffffffffL)

    private fun ByteArrayOutputStream.u64(v: Long) = apply {
        u32(v and 0xffffffffL)
        u32(v ushr 32)
    }
}
