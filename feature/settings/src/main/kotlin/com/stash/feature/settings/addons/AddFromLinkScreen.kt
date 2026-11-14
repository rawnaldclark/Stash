package com.stash.feature.settings.addons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.addons.Trust
import com.stash.data.download.lossless.addon.AddonLinks
import com.stash.feature.settings.R
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.weblink.QrScannerScreen

/**
 * Add from a link (add-ons plan phase 4 §2.1, §2.2). A link comes in three ways: pasted (the field,
 * or Paste, which reads the clipboard once, on the tap), shared to Stash from another app
 * ([initialLink], read as the screen opens), or scanned from a code (Scan). Stash reads the add-on
 * and shows what it is: its name and version, the address it runs on (never the rest of the link,
 * which can hold a token), what Stash will use from it, and where it lands in the play order.
 *
 * Nothing is added until Add is tapped. For an add-on Stash doesn't run, Add opens the "Not made
 * by Stash" sheet first, and only "Add anyway" adds.
 *
 * [replacingKey]: this is Replace link, from an add-on's page: the new link must be that add-on's.
 */
@Composable
fun AddFromLinkScreen(
    onBack: () -> Unit,
    /** Added (or replaced): back to the list, which shows the new row. */
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
    initialLink: String? = null,
    replacingKey: String? = null,
    viewModel: AddFromLinkViewModel = hiltViewModel(),
) {
    // What the screen keeps belongs to the link it was opened with. Another link shared to Stash while
    // it is open comes to this same screen (StashScaffold opens it single top): Navigation keeps its
    // entry, with the entry's saved state and view model, and draws the screen again from that saved
    // state. The first link's field and its "already read" mark came back, and the new link was never
    // read. Under its own key the new link starts afresh; the same link, after a rotation or a return
    // to Stash, gets its state back.
    key(initialLink) {
        AddFromLinkFor(onBack, onAdded, modifier, initialLink, replacingKey, viewModel)
    }
}

/** [AddFromLinkScreen] for the one link it was opened with, [initialLink]. */
@Composable
private fun AddFromLinkFor(
    onBack: () -> Unit,
    onAdded: () -> Unit,
    modifier: Modifier,
    initialLink: String?,
    replacingKey: String?,
    viewModel: AddFromLinkViewModel,
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val lands by viewModel.lands.collectAsStateWithLifecycle()
    // The field's own text, written in the same frame as each key: never a value that waits on the view model.
    var link by rememberSaveable { mutableStateOf(initialLink.orEmpty()) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var clipboardEmpty by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    // A shared link (or an official add-on's Add) goes straight to its preview, once.
    var readInitial by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialLink) {
        if (!readInitial && !initialLink.isNullOrBlank()) {
            readInitial = true
            // The view model is the entry's: it can still hold, or be reading, the link shared before this one.
            viewModel.reset()
            viewModel.read(initialLink, replacingKey)
        }
    }
    LaunchedEffect(step) { if (step == AddFromLinkViewModel.Step.Added) onAdded() }

    Box(modifier = modifier.fillMaxSize()) {
        AddFromLinkContent(
            step = step,
            link = link,
            replacing = replacingKey != null,
            lands = lands,
            clipboardEmpty = clipboardEmpty,
            onBack = onBack,
            onLinkChange = {
                link = it
                clipboardEmpty = false
                viewModel.reset()
            },
            onRead = {
                focusManager.clearFocus()
                viewModel.read(link, replacingKey)
            },
            onPaste = {
                // Read once, on this tap: the clipboard is never looked at otherwise.
                val pasted = clipboard.getText()?.text?.trim().orEmpty()
                if (pasted.isEmpty()) {
                    clipboardEmpty = true
                } else {
                    clipboardEmpty = false
                    link = pasted
                    focusManager.clearFocus()
                    viewModel.reset()
                    viewModel.read(pasted, replacingKey)
                }
            },
            onScan = {
                focusManager.clearFocus()
                scanning = true
            },
            onAdd = { preview, token, agreed -> viewModel.add(preview, token, agreed) },
            onCancel = {
                link = ""
                viewModel.reset()
            },
        )
        if (scanning) {
            BackHandler { scanning = false }
            QrScannerScreen(
                onCode = { code ->
                    scanning = false
                    link = code.trim()
                    clipboardEmpty = false
                    viewModel.reset()
                    viewModel.read(code.trim(), replacingKey)
                },
                onClose = { scanning = false },
            )
        }
    }
}

/** [AddFromLinkScreen] without its view model, the clipboard or the camera, for tests. */
@Composable
internal fun AddFromLinkContent(
    step: AddFromLinkViewModel.Step,
    link: String,
    replacing: Boolean,
    lands: AddFromLinkViewModel.Lands,
    clipboardEmpty: Boolean,
    onBack: () -> Unit,
    onLinkChange: (String) -> Unit,
    onRead: () -> Unit,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onAdd: (preview: AddonLinks.Preview, token: String?, agreed: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(if (replacing) R.string.settings_addons_replace_link else R.string.settings_addons_link_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(if (replacing) R.string.settings_addons_replace_desc else R.string.settings_addons_add_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        SettingsGroupCard(
            rows = listOf(
                {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = onLinkChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_addons_link_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onRead() }),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = onPaste, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.settings_addons_paste))
                            }
                            OutlinedButton(onClick = onScan, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.settings_addons_scan))
                            }
                        }
                        if (clipboardEmpty) {
                            Text(
                                text = stringResource(R.string.settings_addons_clipboard_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (step) {
                            AddFromLinkViewModel.Step.Idle, AddFromLinkViewModel.Step.Added ->
                                Button(onClick = onRead, enabled = link.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.settings_addons_link_check))
                                }
                            AddFromLinkViewModel.Step.Reading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.settings_addons_link_checking),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            is AddFromLinkViewModel.Step.Refused -> {
                                Text(
                                    text = stringResource(refusalText(step.reason)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                // The same link can be tried again ("Couldn't reach that add-on") without editing it first.
                                Button(onClick = onRead, enabled = link.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.settings_addons_link_check))
                                }
                            }
                            is AddFromLinkViewModel.Step.Ready -> Unit
                        }
                    }
                },
            ),
        )
        if (step is AddFromLinkViewModel.Step.Ready) {
            PreviewCard(preview = step.preview, refused = step.refused, lands = lands, onAdd = onAdd, onCancel = onCancel)
        }
        PageEndSpace()
    }
}

/** The preview's password field. */
internal const val ADDON_PASSWORD_FIELD_TAG = "addon-password-field"

/**
 * What Add would add (§2.1): the add-on's name and version, its mark and the address it runs on,
 * what Stash will use from it, where it goes in the play order, a password field when it asks for
 * one, and Add.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(
    preview: AddonLinks.Preview,
    /** Why the last Add was refused, said above the buttons. */
    refused: AddonLinks.Refusal?,
    lands: AddFromLinkViewModel.Lands,
    onAdd: (AddonLinks.Preview, String?, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // The password's own text, written in the same frame as each key. Kept for the same link: when Add
    // finds the add-on is no longer Official the card is drawn again for the sheet, and the password
    // typed for it stays (review-885 round 2, N8).
    var token by remember(preview.manifestUrl) { mutableStateOf("") }
    var sheet by rememberSaveable(preview.manifestUrl) { mutableStateOf(false) }
    val official = preview.trust == Trust.FIRST_PARTY || preview.trust == Trust.OFFICIAL
    SettingsGroupCard(
        rows = listOf(
            {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AddonAvatar(preview.name)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = preview.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = stringResource(R.string.settings_addons_version, preview.version),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (official) stringResource(R.string.settings_addons_official) else stringResource(R.string.settings_addons_not_made_host, preview.host),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (preview.uses.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_addons_preview_uses),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            preview.uses.forEach { UsePill(stringResource(useText(it))) }
                        }
                    }
                    if (preview.playsSongs && preview.replacesKey == null) {
                        Text(
                            text = stringResource(
                                when (lands) {
                                    AddFromLinkViewModel.Lands.AFTER_TIDAL -> R.string.settings_addons_preview_after_tidal
                                    AddFromLinkViewModel.Lands.AFTER_OTHERS -> R.string.settings_addons_preview_after_others
                                    AddFromLinkViewModel.Lands.ALONE -> R.string.settings_addons_preview_alone
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (preview.needsToken) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            modifier = Modifier.fillMaxWidth().testTag(ADDON_PASSWORD_FIELD_TAG),
                            label = { Text(stringResource(R.string.settings_addons_password_label)) },
                            supportingText = { Text(stringResource(R.string.settings_addons_password_desc)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        )
                    }
                    if (refused != null) {
                        Text(
                            text = stringResource(refusalText(refused)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                // An add-on Stash doesn't run: the sheet first, and only "Add anyway" adds.
                                if (preview.needsConsent) sheet = true else onAdd(preview, token.takeIf { it.isNotBlank() }, false)
                            },
                            // A password it asks for is typed first: Add without one could only be refused.
                            enabled = !preview.needsToken || token.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(if (preview.replacesKey != null) R.string.settings_addons_replace else R.string.settings_addons_add))
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.settings_addons_cancel))
                        }
                    }
                }
            },
        ),
    )
    if (sheet) {
        NotMadeByStashSheet(
            name = preview.name,
            host = preview.host,
            sees = preview.sees,
            onConfirm = {
                sheet = false
                onAdd(preview, token.takeIf { it.isNotBlank() }, true)
            },
            onDismiss = { sheet = false },
            replacing = preview.replacesKey != null,
        )
    }
}
