package com.stash.core.ui.ambient

import android.graphics.Bitmap

/** The web reads its palette from the cover drawn into a 32x32 canvas (palette.ts loadPalette). */
private const val PALETTE_SAMPLE_PX = 32

/**
 * The web player's palette (palette.ts loadPalette) from a decoded cover: drawn into 32x32 (filtered),
 * then the hue-bucket vote of [ambientPaletteFromPixels]. A greyscale or unreadable cover (including a
 * hardware bitmap, whose pixels can't be read) gives [AmbientPalette.Default]. CPU work: call it off
 * the main thread.
 */
fun ambientPaletteFromBitmap(bitmap: Bitmap, key: String): AmbientPalette =
    try {
        val small = Bitmap.createScaledBitmap(bitmap, PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX, true)
        val px = IntArray(PALETTE_SAMPLE_PX * PALETTE_SAMPLE_PX)
        small.getPixels(px, 0, PALETTE_SAMPLE_PX, 0, 0, PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX)
        if (small !== bitmap) small.recycle()
        ambientPaletteFromPixels(px, key) ?: AmbientPalette.Default
    } catch (_: Exception) {
        AmbientPalette.Default
    }
