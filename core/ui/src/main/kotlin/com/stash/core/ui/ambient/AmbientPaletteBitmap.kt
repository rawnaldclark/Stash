package com.stash.core.ui.ambient

import android.graphics.Bitmap

/** The web reads its palette from the cover drawn into a 32x32 canvas (palette.ts loadPalette). */
private const val PALETTE_SAMPLE_PX = 32

/**
 * The cover's palette (palette.ts loadPalette plus the app's neutral rule) from a decoded cover:
 * drawn into 32x32 (filtered), then [readCoverPalette]. A colourful cover gives its colour, a
 * colourless one the neutral grey; an image with nothing readable in it (fully transparent, or a
 * hardware bitmap, whose pixels can't be read) gives [AmbientPalette.Default]. CPU work: call it off
 * the main thread.
 */
fun ambientPaletteFromBitmap(bitmap: Bitmap, key: String): AmbientPalette =
    try {
        val small = Bitmap.createScaledBitmap(bitmap, PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX, true)
        val px = IntArray(PALETTE_SAMPLE_PX * PALETTE_SAMPLE_PX)
        small.getPixels(px, 0, PALETTE_SAMPLE_PX, 0, 0, PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX)
        if (small !== bitmap) small.recycle()
        readCoverPalette(px, key) ?: AmbientPalette.Default
    } catch (_: Exception) {
        AmbientPalette.Default
    }
