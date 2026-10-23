package com.stash.feature.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A setting with several choices, shown as one row: the title, and the current
 * choice on the right. Tapping it opens a [SettingsChoiceSheet]. Keeps a screen
 * of quality settings to one line each instead of a stack of radio lists.
 */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = StashTheme.extendedColors.textTertiary,
        )
    }
}

/** One option in a [SettingsChoiceSheet]. */
data class SettingsChoice<T>(
    val value: T,
    val title: String,
    val subtitle: String? = null,
    val enabled: Boolean = true,
)

/**
 * The bottom sheet behind a [SettingsValueRow]: a title, the options as radio
 * rows (each with its cost, e.g. a size), and an optional note under them.
 * Picking an option selects it and closes the sheet.
 *
 * @param header Optional content above the options (e.g. a live status line).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsChoiceSheet(
    title: String,
    choices: List<SettingsChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    footer: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = SettingsRowPadH + 5.dp, vertical = 4.dp),
            )
            if (header != null) {
                Column(modifier = Modifier.padding(horizontal = SettingsRowPadH + 5.dp, vertical = 4.dp)) {
                    header()
                }
            }
            Column(modifier = Modifier.selectableGroup().padding(horizontal = 5.dp)) {
                choices.forEach { choice ->
                    SettingsPickerRow(
                        selected = choice.value == selected,
                        title = choice.title,
                        subtitle = choice.subtitle,
                        onClick = {
                            if (choice.enabled) {
                                onSelect(choice.value)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.alpha(if (choice.enabled) 1f else 0.4f),
                    )
                }
            }
            if (footer != null) {
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = SettingsRowPadH + 5.dp, vertical = 8.dp),
                )
            }
        }
    }
}
