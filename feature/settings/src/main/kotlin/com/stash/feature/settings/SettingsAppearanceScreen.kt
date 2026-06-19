package com.stash.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.ThemeMode
import com.stash.core.ui.theme.StashBackground
import com.stash.core.ui.theme.StashBackgroundLight
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextPrimaryLight
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.core.ui.theme.StashTextSecondaryLight
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel

/**
 * Appearance category screen. Replaces the old RadioButton theme list
 * (SettingsScreen.kt:1089-1139) with three tappable T1 theme-preview thumbnails
 * (Dark / Light / Follow system). The underlying control is the SAME existing
 * callback: [SettingsViewModel.onThemeChanged].
 */
@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showBlurLayer by viewModel.showBlurLayerInAmoled.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Appearance", onBack = onBack, modifier = modifier) {
        SettingsSectionLabel("Theme")

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThemeThumbnail(
                    label = "Dark", selected = uiState.themeMode == ThemeMode.DARK,
                    darkPalette = true, followSystem = false,
                    onClick = { viewModel.onThemeChanged(ThemeMode.DARK) },
                    modifier = Modifier.weight(1f),
                )
                ThemeThumbnail(
                    label = "Light", selected = uiState.themeMode == ThemeMode.LIGHT,
                    darkPalette = false, followSystem = false,
                    onClick = { viewModel.onThemeChanged(ThemeMode.LIGHT) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThemeThumbnail(
                    label = "AMOLED", selected = uiState.themeMode == ThemeMode.AMOLED,
                    darkPalette = true, followSystem = false,
                    onClick = { viewModel.onThemeChanged(ThemeMode.AMOLED) },
                    modifier = Modifier.weight(1f),
                )
                ThemeThumbnail(
                    label = "Follow system", selected = uiState.themeMode == ThemeMode.SYSTEM,
                    darkPalette = false, followSystem = true,
                    onClick = { viewModel.onThemeChanged(ThemeMode.SYSTEM) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Text(
            text = "Follow system matches your device's day/night setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
        )

        if (uiState.themeMode == ThemeMode.AMOLED) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show blur effects", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("Disable for strict pure-black AMOLED savings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = showBlurLayer, onCheckedChange = viewModel::onShowBlurLayerInAmoledChanged)
            }
        }
    }
}

@Composable
private fun ThemeThumbnail(
    label: String,
    selected: Boolean,
    darkPalette: Boolean,
    followSystem: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentRing = MaterialTheme.colorScheme.primary
    val previewShape = RoundedCornerShape(12.dp)
    val selectionRing = if (selected) {
        Modifier.border(2.dp, accentRing, previewShape)
    } else {
        Modifier.border(1.dp, StashTheme.extendedColors.glassBorder, previewShape)
    }

    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(previewShape)
                .then(selectionRing),
        ) {
            if (followSystem) {
                // Vertical split reads as "both" — robust, no fragile diagonal clipping.
                Row(modifier = Modifier.fillMaxSize()) {
                    PalettePreview(
                        dark = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    PalettePreview(
                        dark = false,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                PalettePreview(dark = darkPalette, modifier = Modifier.fillMaxSize())
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(accentRing),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accentRing else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A tiny mock app screen rendered in one palette (dark or light). */
@Composable
private fun PalettePreview(
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (dark) StashBackground else StashBackgroundLight
    val textPrimary = if (dark) StashTextPrimary else StashTextPrimaryLight
    val textSecondary = if (dark) StashTextSecondary else StashTextSecondaryLight
    val accent = if (dark) MaterialTheme.colorScheme.primary else StashPurple

    Column(
        modifier = modifier
            .background(background)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Title bar
        Box(
            Modifier
                .height(6.dp)
                .fillMaxWidth(0.5f)
                .clip(RoundedCornerShape(3.dp))
                .background(textPrimary),
        )
        // Two thinner text bars
        Box(
            Modifier
                .height(4.dp)
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(2.dp))
                .background(textSecondary),
        )
        Box(
            Modifier
                .height(4.dp)
                .fillMaxWidth(0.65f)
                .clip(RoundedCornerShape(2.dp))
                .background(textSecondary),
        )
        Spacer(Modifier.weight(1f))
        // Progress pill
        Box(
            Modifier
                .height(5.dp)
                .fillMaxWidth(0.7f)
                .clip(CircleShape)
                .background(accent),
        )
    }
}
