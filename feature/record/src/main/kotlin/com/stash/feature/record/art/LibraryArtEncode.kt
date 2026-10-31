package com.stash.feature.record.art

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.min

/**
 * A library cover as the record shows it: the middle square of the picture, at most [PX] a side, written as a JPEG at
 * [QUALITY]. Written from the decoded pixels alone, so nothing of the source file (its EXIF, its other segments, bytes that
 * were never a picture) ever reaches the page.
 */
internal object LibraryArtEncode {
    /** A side, at most (the lookups' covers are 600 too). */
    const val PX = 600

    const val QUALITY = 85

    /** The middle square of [src], scaled down to [px] a side when bigger. */
    fun square(src: Bitmap, px: Int = PX): Bitmap {
        val side = min(src.width, src.height)
        val cropped = if (src.width == src.height) src else Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val target = min(side, px)
        return if (target == side) cropped else Bitmap.createScaledBitmap(cropped, target, target, true)
    }

    /** [src] squared and written to [out], whole or not at all (a temporary file, then an atomic move). */
    fun write(src: Bitmap, out: File, px: Int = PX): Boolean {
        if (src.width <= 0 || src.height <= 0) return false
        val dir = out.parentFile ?: return false
        dir.mkdirs()
        val tmp = File(dir, "${out.name}.${UUID.randomUUID()}$TMP")
        try {
            val ok = FileOutputStream(tmp).use { square(src, px).compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!ok || tmp.length() == 0L) return false
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            tmp.delete()
        }
    }

    const val TMP = ".tmp"
}
