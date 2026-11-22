package com.stash.feature.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.stash.core.model.TuningMath
import java.util.Locale

/** The app's language, for Tuning's numbers (its digits and decimal mark; per-app languages included). */
@Composable
internal fun tuningLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Tuning on the Playback speed action: "A432" while it isn't 440, else null. */
@Composable
internal fun tuningMark(hz: Float): String? =
    if (TuningMath.isRetuned(hz)) stringResource(R.string.np_tuning_action_mark, TuningMath.number(hz, tuningLocale())) else null

/** The speed sheet's Tuning heading: "Tuning · Standard" or "Tuning · A = 432 Hz". */
@Composable
internal fun tuningHeading(hz: Float): String =
    if (TuningMath.isRetuned(hz)) stringResource(R.string.np_tuning_heading_hz, TuningMath.number(hz, tuningLocale()))
    else stringResource(R.string.np_tuning_heading_standard)

/** What the custom tuning field's text asks for, in words, under the field. */
@Composable
internal fun tuningEntryLine(entry: TuningMath.Entry): String {
    val locale = tuningLocale()
    return when (entry) {
        TuningMath.Entry.Empty -> stringResource(
            R.string.np_tuning_exact_range,
            TuningMath.plain(TuningMath.MIN_HZ.toDouble(), locale),
            TuningMath.plain(TuningMath.MAX_HZ.toDouble(), locale),
        )
        TuningMath.Entry.Invalid -> stringResource(
            R.string.np_tuning_exact_invalid,
            TuningMath.plain(432.0, locale),
            TuningMath.plain(431.5, locale),
        )
        is TuningMath.Entry.Value -> when {
            entry.clamped && entry.hz == TuningMath.MIN_HZ ->
                stringResource(R.string.np_tuning_exact_lowest, TuningMath.number(entry.hz, locale))
            entry.clamped -> stringResource(R.string.np_tuning_exact_highest, TuningMath.number(entry.hz, locale))
            !TuningMath.isRetuned(entry.hz) -> stringResource(R.string.np_tuning_cents_standard)
            TuningMath.isLower(entry.hz) ->
                stringResource(R.string.np_tuning_cents_lower, TuningMath.centsMagnitude(entry.hz, locale))
            else -> stringResource(R.string.np_tuning_cents_higher, TuningMath.centsMagnitude(entry.hz, locale))
        }
    }
}

/** The line about the playing song under Tuning's chips, or null when there's nothing to say. */
@Composable
internal fun songTuningText(line: SongTuningLine): String? {
    val locale = tuningLocale()
    return when (line) {
        SongTuningLine.None -> null
        is SongTuningLine.RecordedAt -> stringResource(R.string.np_tuning_song_recorded, TuningMath.plain(line.songHz, locale))
        is SongTuningLine.Shifted -> stringResource(
            if (line.lower) R.string.np_tuning_song_lower else R.string.np_tuning_song_higher,
            TuningMath.plain(line.songHz, locale),
            TuningMath.plain(line.cents, locale),
        )
        is SongTuningLine.AlreadyThere -> stringResource(R.string.np_tuning_song_already, TuningMath.number(line.hz, locale))
        SongTuningLine.NoPitch -> stringResource(R.string.np_tuning_song_no_pitch)
        SongTuningLine.NotMeasured -> stringResource(R.string.np_tuning_song_not_measured)
    }
}

/** What the output measures as, under the song's line. */
@Composable
internal fun outputTuningText(line: OutputTuningLine): String {
    val locale = tuningLocale()
    return if (line.onTarget) stringResource(R.string.np_tuning_output_on, TuningMath.plain(line.hz, locale))
    else stringResource(R.string.np_tuning_output_off, TuningMath.plain(line.hz, locale), TuningMath.plain(kotlin.math.abs(line.offCents), locale))
}
