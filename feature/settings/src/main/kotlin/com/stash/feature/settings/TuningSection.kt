package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.TuningMath
import com.stash.core.ui.components.sheetBarsFollow
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsValueRow
import java.util.Locale

/**
 * Audio & Quality > Output > Tuning: the concert pitch everything Stash plays is retuned to, at the
 * same speed. One row showing the reference ("Standard", "A = 432 Hz"), opening a sheet with the
 * common references as chips, a slider over the whole range, and an exact number of hertz for any
 * value in between, with the offset in cents said in words as it changes.
 *
 * Numbers read in the app's language (its digits and decimal mark); the exact field takes any.
 * On a phone without the tuning engine the row is shown, greyed, as not available.
 */
@Composable
fun TuningRow(viewModel: TuningSettingsViewModel = hiltViewModel()) {
    val hz by viewModel.referenceHz.collectAsStateWithLifecycle()
    val available = viewModel.available
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    SettingsValueRow(
        title = stringResource(R.string.settings_tuning_title),
        value = if (available) tuningValue(hz) else stringResource(R.string.settings_tuning_value_standard),
        subtitle = when {
            !available -> stringResource(R.string.settings_tuning_unavailable)
            !TuningMath.isRetuned(hz) -> null
            TuningMath.isLower(hz) -> stringResource(R.string.settings_tuning_subtitle_lower)
            else -> stringResource(R.string.settings_tuning_subtitle_higher)
        },
        onClick = { sheetOpen = true },
        enabled = available,
    )

    if (sheetOpen && available) {
        TuningSheet(
            current = hz,
            onSet = viewModel::setReferenceHz,
            onDismiss = { sheetOpen = false },
        )
    }
}

/** The app's language, for numbers (per-app languages included). */
@Composable
internal fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** "Standard" at 440, else "A = 432 Hz" (one decimal only when it isn't whole). */
@Composable
internal fun tuningValue(hz: Float): String =
    if (TuningMath.isRetuned(hz)) stringResource(R.string.settings_tuning_value_hz, TuningMath.number(hz, appLocale()))
    else stringResource(R.string.settings_tuning_value_standard)

/** "31.8 cents lower", "15.7 cents higher", or standard said as such. */
@Composable
internal fun tuningCentsLine(hz: Float): String {
    val locale = appLocale()
    return when {
        !TuningMath.isRetuned(hz) -> stringResource(R.string.settings_tuning_cents_standard)
        TuningMath.isLower(hz) -> stringResource(R.string.settings_tuning_cents_lower, TuningMath.centsMagnitude(hz, locale))
        else -> stringResource(R.string.settings_tuning_cents_higher, TuningMath.centsMagnitude(hz, locale))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TuningSheet(current: Float, onSet: (Float) -> Unit, onDismiss: () -> Unit) {
    val locale = appLocale()
    // What the sheet shows: follows a slider drag live, stored when the drag ends (each crossing of
    // 440 would otherwise rebuild the stream mid-drag). Saved, so a half-typed value survives a
    // rotation; only a new stored value (from here or elsewhere) replaces it.
    var draft by rememberSaveable { mutableFloatStateOf(current) }
    var text by rememberSaveable { mutableStateOf(TuningMath.number(current, locale)) }
    var shown by rememberSaveable { mutableFloatStateOf(current) }
    LaunchedEffect(current) {
        if (current != shown) {
            shown = current
            draft = current
            text = TuningMath.number(current, locale)
        }
    }
    fun commit(hz: Float) {
        val v = TuningMath.sanitize(hz)
        draft = v
        text = TuningMath.number(v, locale)
        onSet(v)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
        properties = sheetBarsFollow(StashTheme.extendedColors.elevatedSurface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH + 5.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.settings_tuning_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                text = tuningValue(draft),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = tuningCentsLine(draft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TuningMath.PRESETS.forEach { preset ->
                    FilterChip(
                        selected = draft == preset,
                        onClick = { commit(preset) },
                        label = { Text(presetLabel(preset), style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            val sliderName = stringResource(R.string.settings_tuning_slider)
            val sliderValue = tuningValue(draft)
            Slider(
                value = draft,
                onValueChange = { v ->
                    draft = TuningMath.sanitize(v)
                    text = TuningMath.number(draft, locale)
                },
                onValueChangeFinished = { commit(draft) },
                valueRange = TuningMath.MIN_HZ..TuningMath.MAX_HZ,
                modifier = Modifier.semantics {
                    contentDescription = sliderName
                    stateDescription = sliderValue
                },
            )

            TuningExactField(
                text = text,
                onTextChange = { text = it },
                onSubmit = ::commit,
                current = draft,
            )

            TextButton(
                onClick = { commit(TuningMath.STANDARD_HZ) },
                enabled = TuningMath.isRetuned(draft),
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.settings_tuning_reset, TuningMath.number(TuningMath.STANDARD_HZ, locale))) }

            Text(
                text = stringResource(R.string.settings_tuning_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun presetLabel(hz: Float): String {
    val locale = appLocale()
    val n = TuningMath.number(hz, locale)
    return when (hz) {
        440f -> stringResource(R.string.settings_tuning_preset_standard, n)
        444f -> stringResource(R.string.settings_tuning_preset_528, n, TuningMath.plain(528.0, locale))
        415f -> stringResource(R.string.settings_tuning_preset_baroque, n)
        else -> n
    }
}

/**
 * The exact-Hz field and its Set button: digits (any script's) with at most one decimal place,
 * anything past the range clamped to its end (and said so before Set), anything that isn't a
 * number refused. [onSubmit] gets the value to store. The caller owns [text], updated
 * synchronously. A typed value other than [current] previews its offset in cents; one equal to it
 * leaves that to the line above the field (said once), and shows the range instead.
 */
@Composable
internal fun TuningExactField(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    current: Float? = null,
) {
    val locale = appLocale()
    val entry = TuningMath.parse(text)
    val value = (entry as? TuningMath.Entry.Value)?.hz
    val range = stringResource(
        R.string.settings_tuning_exact_range,
        TuningMath.plain(TuningMath.MIN_HZ.toDouble(), locale),
        TuningMath.plain(TuningMath.MAX_HZ.toDouble(), locale),
    )
    val supporting = when (entry) {
        TuningMath.Entry.Empty -> range
        TuningMath.Entry.Invalid -> stringResource(
            R.string.settings_tuning_exact_invalid,
            TuningMath.plain(432.0, locale),
            TuningMath.plain(431.5, locale),
        )
        is TuningMath.Entry.Value -> when {
            entry.clamped && entry.hz == TuningMath.MIN_HZ ->
                stringResource(R.string.settings_tuning_exact_lowest, TuningMath.number(entry.hz, locale))
            entry.clamped -> stringResource(R.string.settings_tuning_exact_highest, TuningMath.number(entry.hz, locale))
            entry.hz == current -> range
            else -> tuningCentsLine(entry.hz)
        }
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { new -> TuningMath.hzInput(new)?.let(onTextChange) },
            label = { Text(stringResource(R.string.settings_tuning_exact_label)) },
            suffix = { Text(stringResource(R.string.settings_tuning_hz_suffix)) },
            singleLine = true,
            isError = entry is TuningMath.Entry.Invalid,
            supportingText = { Text(supporting) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { value?.let(onSubmit) }),
            modifier = Modifier.weight(1f).testTag(TUNING_EXACT_FIELD_TAG),
        )
        TextButton(onClick = { value?.let(onSubmit) }, enabled = value != null) {
            Text(stringResource(R.string.settings_tuning_set))
        }
    }
}

internal const val TUNING_EXACT_FIELD_TAG = "tuning_exact_hz"
