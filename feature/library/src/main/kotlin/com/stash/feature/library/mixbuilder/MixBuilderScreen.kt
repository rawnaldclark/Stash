package com.stash.feature.library.mixbuilder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.R
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import kotlin.math.roundToInt

/** Era presets offered in the builder: label + (startYear, endYear). "Any" = null/null. */
private val ERA_PRESETS: List<Triple<String, Int?, Int?>> = listOf(
    Triple("Any", null, null),
    Triple("70s", 1970, 1979),
    Triple("80s", 1980, 1989),
    Triple("90s", 1990, 1999),
    Triple("2000s", 2000, 2009),
    Triple("2010s", 2010, 2019),
)

/**
 * Full-screen Mix Builder.
 *
 * Lets the user name a mix and pick its genre / mood / era ingredients plus a
 * discovery level, then save it. All controls are wired through
 * [MixBuilderViewModel]; on save we navigate back via the [saved] one-shot.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MixBuilderScreen(
    onBack: () -> Unit,
    viewModel: MixBuilderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate back once the recipe is persisted.
    LaunchedEffect(Unit) { viewModel.saved.collect { onBack() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.isEditing) stringResource(R.string.title_edit_mix) else stringResource(R.string.title_create_mix),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ── 1) Name ────────────────────────────────────────────────────
            TextField(
                value = state.form.name,
                onValueChange = viewModel::setName,
                placeholder = {
                    Text(
                        text = stringResource(R.string.label_mix_name_optional),
                        style = MaterialTheme.typography.titleMedium,
                        color = StashTheme.extendedColors.textTertiary,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    unfocusedIndicatorColor = Color.White.copy(alpha = 0.06f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                ),
            )

            // ── 2) Moods ───────────────────────────────────────────────────
            EyebrowLabel(stringResource(R.string.label_moods))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.moods.forEach { key ->
                    MixChip(
                        label = moodLabel(key),
                        icon = MoodEmblems[key],
                        selected = state.form.moodKeys.contains(key),
                        onClick = { viewModel.toggleMood(key) },
                    )
                }
            }

            // ── 3) Genres ──────────────────────────────────────────────────
            EyebrowLabel(stringResource(R.string.label_genres))
            state.families.forEachIndexed { index, family ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                Text(
                    text = family.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    family.genres.forEach { tag ->
                        MixChip(
                            label = tag.replaceFirstChar { it.uppercase() },
                            selected = state.form.genreTags.contains(tag),
                            onClick = { viewModel.toggleGenre(tag) },
                        )
                    }
                }
            }

            // ── 4) Era ─────────────────────────────────────────────────────
            EyebrowLabel(stringResource(R.string.label_era_optional))
            EraSelector(
                startYear = state.form.eraStartYear,
                onEraSelected = viewModel::setEra,
            )

            // ── 5) Discovery level ─────────────────────────────────────────
            EyebrowLabel(stringResource(R.string.label_discovery_level))
            Slider(
                value = state.form.discoveryRatio,
                onValueChange = viewModel::setDiscoveryRatio,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.label_familiar),
                    style = MaterialTheme.typography.labelSmall,
                    color = StashTheme.extendedColors.textTertiary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.label_brand_new),
                    style = MaterialTheme.typography.labelSmall,
                    color = StashTheme.extendedColors.textTertiary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "~${(state.form.discoveryRatio * 100).roundToInt()}% fresh streaming",
                style = MaterialTheme.typography.labelSmall,
                color = StashTheme.extendedColors.textTertiary,
            )

            // ── 6) Summary ─────────────────────────────────────────────────
            Spacer(Modifier.height(22.dp))
            GlassCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (state.form.isValid) state.form.displayName else stringResource(R.string.label_untitled_mix),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildSummary(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 7) Save CTA ────────────────────────────────────────────────
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = if (state.isEditing) stringResource(R.string.action_save) else stringResource(R.string.action_create_mix_btn),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            // Bottom padding for nav bar clearance.
            Spacer(Modifier.height(80.dp))
        }
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────

/**
 * Era preset selector: a flow row of chips. "Any" clears the era; the rest map
 * to a decade's [start, end] range.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EraSelector(
    startYear: Int?,
    onEraSelected: (start: Int?, end: Int?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ERA_PRESETS.forEach { (label, start, end) ->
            val selected = if (start == null) startYear == null else startYear == start
            MixChip(
                label = label,
                selected = selected,
                onClick = { onEraSelected(start, end) },
            )
        }
    }
}

/**
 * Uppercase eyebrow label that introduces each section of the builder.
 */
@Composable
private fun EyebrowLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        ),
        color = StashTheme.extendedColors.textTertiary,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

/**
 * Glass selection chip used for moods, genres, and era presets.
 *
 * Selected chips fill with a soft purple wash + brighter purple border + purple
 * content; unselected chips are transparent with a faint white hairline border
 * and tertiary-text content.
 */
@Composable
private fun MixChip(
    label: String,
    icon: ImageVector? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val contentColor = if (selected) primary else StashTheme.extendedColors.textTertiary
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) primary.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f),
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = contentColor,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

/** Meta line for the summary card: ingredients · era · discovery. */
@Composable
private fun buildSummary(state: MixBuilderUiState): String {
    val form = state.form
    val tags = (form.genreTags + form.moodKeys)
        .map { it.replaceFirstChar { c -> c.uppercase() } }
    val ingredients = if (tags.isEmpty()) stringResource(R.string.label_no_ingredients) else tags.joinToString(", ")

    val era = when {
        form.eraStartYear == null -> stringResource(R.string.label_any_era)
        form.eraEndYear != null -> "${form.eraStartYear}–${form.eraEndYear}"
        else -> "${form.eraStartYear}+"
    }

    val freshPct = (form.discoveryRatio * 100).roundToInt()
    return "$ingredients · $era · ~$freshPct% new"
}
