package com.stash.data.cloud.tags

import java.io.ByteArrayOutputStream

/** Tiny builders for hand-made audio container bytes. Only what the parsers read is real. */
internal object Fixtures {

    fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()

    fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
    fun ByteArrayOutputStream.u16be(v: Int) { u8(v shr 8); u8(v) }
    fun ByteArrayOutputStream.u24be(v: Int) { u8(v shr 16); u8(v shr 8); u8(v) }
    fun ByteArrayOutputStream.u32be(v: Long) { u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt()) }
    fun ByteArrayOutputStream.u32le(v: Long) { u8(v.toInt()); u8((v shr 8).toInt()); u8((v shr 16).toInt()); u8((v shr 24).toInt()) }
    fun ByteArrayOutputStream.u64le(v: Long) { u32le(v and 0xFFFFFFFFL); u32le(v ushr 32) }
    fun ByteArrayOutputStream.zeros(n: Int) = write(ByteArray(n))

    fun syncsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())

    val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5)
    val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 9, 9)

    // ── ID3v2 ────────────────────────────────────────────────────────────

    fun textFrame23(id: String, text: String, utf16: Boolean = false): ByteArray {
        val payload = bytes {
            if (utf16) { u8(1); write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); write(text.toByteArray(Charsets.UTF_16LE)) }
            else { u8(0); write(text.toByteArray(Charsets.ISO_8859_1)) }
        }
        return bytes { ascii(id); u32be(payload.size.toLong()); u16be(0); write(payload) }
    }

    fun textFrame24(id: String, text: String): ByteArray {
        val payload = bytes { u8(3); write(text.toByteArray(Charsets.UTF_8)) }
        return bytes { ascii(id); write(syncsafe(payload.size)); u16be(0); write(payload) }
    }

    fun apic(major: Int, type: Int, image: ByteArray, mime: String = "image/jpeg"): ByteArray {
        val payload = bytes { u8(0); ascii(mime); u8(0); u8(type); ascii("desc"); u8(0); write(image) }
        return bytes {
            ascii("APIC")
            if (major == 4) write(syncsafe(payload.size)) else u32be(payload.size.toLong())
            u16be(0); write(payload)
        }
    }

    fun id3(major: Int, vararg frames: ByteArray, padding: Int = 32): ByteArray {
        val body = bytes { frames.forEach { write(it) }; zeros(padding) }
        return bytes { ascii("ID3"); u8(major); u8(0); u8(0); write(syncsafe(body.size)); write(body) }
    }

    /** MPEG-1 Layer III 128 kbps 44.1 kHz stereo frames (417 bytes each), first one a Xing header when [xingFrames] set. */
    fun mp3Frames(count: Int, xingFrames: Long? = null): ByteArray = bytes {
        repeat(count) { i ->
            val frame = ByteArray(417)
            frame[0] = 0xFF.toByte(); frame[1] = 0xFB.toByte(); frame[2] = 0x90.toByte(); frame[3] = 0x00
            if (i == 0 && xingFrames != null) {
                val x = 4 + 32
                "Xing".forEachIndexed { k, c -> frame[x + k] = c.code.toByte() }
                frame[x + 7] = 0x01 // frames flag
                frame[x + 8] = (xingFrames shr 24).toByte(); frame[x + 9] = (xingFrames shr 16).toByte()
                frame[x + 10] = (xingFrames shr 8).toByte(); frame[x + 11] = xingFrames.toByte()
            }
            write(frame)
        }
    }

    // ── FLAC ─────────────────────────────────────────────────────────────

    fun flacBlock(type: Int, body: ByteArray, last: Boolean) = bytes {
        u8((if (last) 0x80 else 0) or type); u24be(body.size); write(body)
    }

    fun streamInfo(rate: Int, bits: Int, channels: Int, totalSamples: Long) = bytes {
        u16be(4096); u16be(4096); u24be(0); u24be(0)
        // 20 bits rate | 3 bits channels-1 | 5 bits bps-1 | 36 bits samples
        u8(rate shr 12); u8(rate shr 4)
        u8(((rate and 0xF) shl 4) or ((channels - 1) shl 1) or ((bits - 1) shr 4))
        u8((((bits - 1) and 0xF) shl 4) or ((totalSamples shr 32).toInt() and 0xF))
        u32be(totalSamples and 0xFFFFFFFFL)
        zeros(16) // md5
    }

    fun vorbisComments(vararg kv: String) = bytes {
        val vendor = "test".toByteArray()
        u32le(vendor.size.toLong()); write(vendor)
        u32le(kv.size.toLong())
        kv.forEach { val b = it.toByteArray(Charsets.UTF_8); u32le(b.size.toLong()); write(b) }
    }

    fun pictureBlock(type: Int, mime: String, data: ByteArray) = bytes {
        u32be(type.toLong()); u32be(mime.length.toLong()); ascii(mime); u32be(0); zeros(16); u32be(data.size.toLong()); write(data)
    }

    // ── MP4 ──────────────────────────────────────────────────────────────

    fun box(type: String, vararg children: ByteArray) = bytes {
        val body = bytes { children.forEach { write(it) } }
        u32be((8 + body.size).toLong()); ascii(type); write(body)
    }

    fun fullBox(type: String, body: ByteArray) = box(type, bytes { u32be(0) }, body)

    fun ilstText(key: String, value: String) =
        box(key, box("data", bytes { u32be(1); u32be(0); write(value.toByteArray(Charsets.UTF_8)) }))

    fun ilstPair(key: String, n: Int, total: Int) =
        box(key, box("data", bytes { u32be(0); u32be(0); u16be(0); u16be(n); u16be(total); u16be(0) }))

    fun ilstCover(image: ByteArray, typeCode: Int = 13) =
        box("covr", box("data", bytes { u32be(typeCode.toLong()); u32be(0); write(image) }))

    fun mvhd(timescale: Long, duration: Long) = fullBox("mvhd", bytes { u32be(0); u32be(0); u32be(timescale); u32be(duration); zeros(80) })

    fun stsd(entryType: String, rate: Int, sampleSize: Int, extra: ByteArray = ByteArray(0)) = fullBox(
        "stsd",
        bytes {
            u32be(1)
            write(box(entryType, bytes { zeros(6); u16be(1); zeros(8); u16be(2); u16be(sampleSize); u16be(0); u16be(0); u32be(rate.toLong() shl 16) }, extra))
        },
    )

    // ── Ogg ──────────────────────────────────────────────────────────────

    /** One Ogg page carrying [packets] (each must be < 255*255 bytes); lacing done here. */
    fun oggPage(serial: Long, seq: Long, granule: Long, vararg packets: ByteArray, headerType: Int = 0) = bytes {
        val lacing = bytes {
            packets.forEach { p ->
                var left = p.size
                while (left >= 255) { u8(255); left -= 255 }
                u8(left)
            }
        }
        ascii("OggS"); u8(0); u8(headerType); u64le(granule); u32le(serial); u32le(seq); u32le(0)
        u8(lacing.size); write(lacing)
        packets.forEach { write(it) }
    }
}
