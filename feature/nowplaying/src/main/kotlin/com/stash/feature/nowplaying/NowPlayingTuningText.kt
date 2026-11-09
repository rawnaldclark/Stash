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
