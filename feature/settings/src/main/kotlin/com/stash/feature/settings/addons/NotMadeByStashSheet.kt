package com.stash.feature.settings.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stash.core.addons.AddonSees
import com.stash.core.ui.components.sheetBarsFollow
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R

/**
 * The "Not made by Stash" sheet (add-ons plan phase 4 §2.7): what the user agrees to before an
 * add-on Stash doesn't run is added, and again only when it would see more than they agreed to
 * ([more]). Never for a new version, and never for an official add-on.
 *
 * It says where the add-on runs ([host], the address only), what its server will see (built from
 * what its manifest lists, [sees], plus the internet address every server sees) and what it never
 * gets. Nothing is added by opening it: only [onConfirm] adds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotMadeByStashSheet(
    name: String,
    host: String,
    sees: Set<AddonSees>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** The add-on is already added and now asks for more: the same facts, with "Allow". */
    more: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
        properties = sheetBarsFollow(StashTheme.extendedColors.elevatedSurface),
    ) {
        NotMadeByStashContent(name, host, sees, onConfirm, onDismiss, more)
    }
}

/** What the sheet says, without the sheet around it (so a test can read every line). */
@Composable
internal fun NotMadeByStashContent(
    name: String,
    host: String,
    sees: Set<AddonSees>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    more: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(if (more) R.string.settings_addons_sheet_more_title else R.string.settings_addons_not_made),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        // The address in bold: it is the one thing here that says who the user is trusting.
        val intro = stringResource(if (more) R.string.settings_addons_sheet_more_intro else R.string.settings_addons_sheet_runs_on, name, host)
        Text(
            text = buildAnnotatedString {
                val at = intro.indexOf(host)
                if (at < 0 || host.isEmpty()) {
                    append(intro)
                } else {
                    append(intro.substring(0, at))
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(host) }
                    append(intro.substring(at + host.length))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Fact(label = stringResource(R.string.settings_addons_sheet_sees_label), text = stringResource(seesText(sees)))
        Fact(label = stringResource(R.string.settings_addons_sheet_never_label), text = stringResource(R.string.settings_addons_sheet_never))
        Text(
            text = stringResource(R.string.settings_addons_sheet_trust),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (more) R.string.settings_addons_sheet_allow else R.string.settings_addons_sheet_add))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_addons_cancel))
            }
        }
    }
}

/** One of the sheet's two facts: what it is about in bold, then the sentence. */
@Composable
private fun Fact(label: String, text: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
