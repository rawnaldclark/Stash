package com.stash.feature.settings.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.equalizer.NamedPreset
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme

/** Frequency labels for the 5 EQ bands: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz. */
private val BAND_LABELS = listOf("60", "230", "910", "3.6k", "14k")

/**
 * Full-screen Equalizer screen.
 *
 * Shows a master toggle, a 5-band EQ with live curve preview, preset chips, a
 * bass-boost slider, and a pre-amp slider. All controls are wired through
 * [EqualizerViewModel].
 */
@Composable
fun EqualizerScreen(
    onNavigateBack: () -> Unit,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreatePresetDialog by remember { mutableStateOf(false) }

    if (showCreatePresetDialog) {
        CreatePresetDialog(
            onConfirm = { name ->
                viewModel.onSaveCurrentPreset(name)
                showCreatePresetDialog = false
            },
            onDismiss = { showCreatePresetDialog = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        EqHeader(
            enabled = state.enabled,
            onToggle = viewModel::onToggle,
            onBack = onNavigateBack,
        )

        Spacer(Modifier.height(12.dp))

        // ── 5-Band EQ card ─────────────────────────────────────────────────
        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (state.enabled) 1f else 0.5f),
            ) {
                SectionLabel("5-Band EQ")
                Spacer(Modifier.height(10.dp))

                EqCurvePreview(
                    gainsDb = state.gainsDb,
                    bassBoostDb = state.bassBoostDb,
                )

                Spacer(Modifier.height(12.dp))

                BandSliderRow(
                    gainsDb = state.gainsDb,
                    enabled = state.enabled,
                    onBandChanged = viewModel::onBandChanged,
                )

                Spacer(Modifier.height(14.dp))

                PresetChipRow(
                    allPresets = state.allPresets,
                    activeId = state.activePresetId,
                    onPresetSelected = viewModel::onPresetSelected,
                    onSavePresetClick = { showCreatePresetDialog = true },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Bass Boost card ────────────────────────────────────────────────
        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (state.enabled) 1f else 0.5f),
            ) {
                SectionLabel("Bass Boost")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Slider(
                        value = state.bassBoostDb,
                        onValueChange = viewModel::onBassBoostChanged,
                        valueRange = 0f..15f,
                        enabled = state.enabled,
                        modifier = Modifier.weight(1f),
                        colors = eqSliderColors(),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "+%.1f dB".format(state.bassBoostDb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 52.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Pre-amp card ───────────────────────────────────────────────────
        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (state.enabled) 1f else 0.5f),
            ) {
                SectionLabel("Pre-amp")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Slider(
                        value = state.preampDb,
                        onValueChange = viewModel::onPreampChanged,
                        valueRange = -12f..12f,
                        enabled = state.enabled,
                        modifier = Modifier.weight(1f),
                        colors = eqSliderColors(),
                    )
                    Spacer(Modifier.width(8.dp))
                    val preampSign = if (state.preampDb >= 0f) "+" else ""
                    Text(
                        text = "$preampSign%.1f dB".format(state.preampDb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 52.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }

        // Bottom padding for nav bar clearance
        Spacer(Modifier.height(80.dp))
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────

/**
 * Header row: back arrow + "Equalizer" title + master on/off Switch.
 */
@Composable
private fun EqHeader(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = "Equalizer",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/**
 * Canvas-drawn live EQ curve preview.
 *
 * Plots a smoothed line through the 5 band gain values (and an implicit
 * bass-boost contribution at the low end) using cosine interpolation.
 * The curve is drawn in the theme primary color with a subtle shaded fill.
 */
@Composable
private fun EqCurvePreview(
    gainsDb: FloatArray,
    bassBoostDb: Float,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val fillColor = primaryColor.copy(alpha = 0.15f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // dB range for the display: -12 to +12 (we clamp to this for rendering)
        val dbRange = 12f

        // Build a points list: band index 0..4 → x positions spaced across width.
        // We add the bass boost as an upward nudge on band 0.
        val gains = FloatArray(5) { i ->
            val raw = gainsDb.getOrElse(i) { 0f } + if (i == 0) bassBoostDb * 0.5f else 0f
            raw.coerceIn(-dbRange, dbRange)
        }

        // x positions: spread bands across full width with equal spacing
        val bandX = FloatArray(5) { i -> w * (i + 0.5f) / 5f }

        // Build a smooth path using cosine interpolation between band points.
        // We sample 64 points across the width for a smooth curve.
        val samples = 64
        val path = Path()
        val fillPath = Path()

        fun sampleY(xNorm: Float): Float {
            // xNorm is 0..1 across width; map to fractional band index
            val bandPos = xNorm * 4f   // 0..4
            val lo = bandPos.toInt().coerceIn(0, 3)
            val hi = (lo + 1).coerceIn(0, 4)
            val t = bandPos - lo
            // Cosine interpolation for smoothness
            val tSmooth = (1f - kotlin.math.cos(t * Math.PI.toFloat())) / 2f
            val gainDb = gains[lo] * (1f - tSmooth) + gains[hi] * tSmooth
            return midY - (gainDb / dbRange) * (h * 0.45f)
        }

        for (i in 0..samples) {
            val xNorm = i.toFloat() / samples
            val x = xNorm * w
            val y = sampleY(xNorm)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        // Draw fill first, then stroke on top
        drawPath(fillPath, color = fillColor)
        drawPath(
            path = path,
            color = primaryColor,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Draw band tick marks at the bottom
        bandX.forEach { bx ->
            drawLine(
                color = primaryColor.copy(alpha = 0.3f),
                start = Offset(bx, h - 6.dp.toPx()),
                end = Offset(bx, h),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // Draw centre (0 dB) reference line
        drawLine(
            color = primaryColor.copy(alpha = 0.15f),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/**
 * Row of 5 vertical sliders, one per EQ band.
 *
 * Each slider is rendered horizontally then rotated 270° to stand vertically.
 * Above each slider the current dB value is shown; below it is the frequency label.
 */
@Composable
private fun BandSliderRow(
    gainsDb: FloatArray,
    enabled: Boolean,
    onBandChanged: (Int, Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BAND_LABELS.forEachIndexed { i, label ->
            val gainDb = gainsDb.getOrElse(i) { 0f }
            val dbSign = if (gainDb >= 0f) "+" else ""
            val dbText = "$dbSign${gainDb.toInt()}"

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                // Current dB value above the slider
                Text(
                    text = dbText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )

                // Vertical slider: rotate the standard horizontal Slider 270°
                Box(
                    modifier = Modifier
                        .height(120.dp)
                        .width(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Slider(
                        value = (gainDb + 12f) / 24f, // normalise -12..+12 → 0..1
                        onValueChange = { norm -> onBandChanged(i, norm * 24f - 12f) },
                        enabled = enabled,
                        modifier = Modifier
                            .width(120.dp)
                            .graphicsLayer { rotationZ = 270f },
                        colors = eqSliderColors(),
                    )
                }

                // Frequency label below the slider
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Horizontally scrollable row of [FilterChip]s for preset selection, plus a
 * trailing "+" chip to trigger saving the current settings as a new preset.
 */
@Composable
private fun PresetChipRow(
    allPresets: List<NamedPreset>,
    activeId: String,
    onPresetSelected: (String) -> Unit,
    onSavePresetClick: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(allPresets, key = { it.id }) { preset ->
            val selected = preset.id == activeId
            FilterChip(
                selected = selected,
                onClick = { onPresetSelected(preset.id) },
                label = {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
        item {
            FilterChip(
                selected = false,
                onClick = onSavePresetClick,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Save preset",
                            modifier = Modifier
                                .height(16.dp)
                                .width(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        }
    }
}

/**
 * Small uppercase section label matching the style used in Settings cards.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        ),
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Modal dialog to name and save the current EQ settings as a custom preset.
 */
@Composable
fun CreatePresetDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = "Save Preset",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────

@Composable
private fun eqSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    disabledActiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
)
