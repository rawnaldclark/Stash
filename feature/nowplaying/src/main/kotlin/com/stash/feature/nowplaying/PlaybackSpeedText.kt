package com.stash.feature.nowplaying

import java.util.Locale

/** YT Music's presets: 0.25x–2x in quarter steps. */
internal val SpeedPresets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** "1x", "1.25x": Locale.US, so comma-decimal locales don't render "1,25x". */
internal fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x"
    else "${"%.2f".format(Locale.US, speed).trimEnd('0').trimEnd('.')}x"

/** What the picker calls a speed: 1x is "Normal". */
internal fun speedLabel(speed: Float): String = if (speed == 1f) "Normal" else formatSpeed(speed)

private val CustomSpeedText = Regex("""\d*(\.\d{0,2})?""")

/**
 * The custom-speed field's text after a keystroke, or null to refuse it: digits and one decimal
 * point with up to two places (what the picker shows). A comma becomes a point, since a decimal
 * keypad in a comma-decimal locale types one.
 */
internal fun customSpeedInput(new: String): String? =
    new.replace(',', '.').takeIf { CustomSpeedText.matches(it) }

/** The speed [text] asks for, or null outside 0.1x–4x (what PlayerRepository accepts). */
internal fun parseCustomSpeed(text: String): Float? = text.toFloatOrNull()?.takeIf { it in 0.1f..4f }

/**
 * Columns for the preset chips: four when the widest chip fits a quarter of the row, else two, so
 * a large font size (or a narrow phone) gets roomier chips instead of clipped labels.
 */
internal fun speedPresetColumns(rowWidthPx: Int, widestChipPx: Int, gapPx: Int): Int =
    if (4 * widestChipPx + 3 * gapPx <= rowWidthPx) 4 else 2
