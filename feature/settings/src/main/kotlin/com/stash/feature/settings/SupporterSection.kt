package com.stash.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.supporter.SupporterRepository
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R

/** The Ko-fi page the supporter copy points at (the same one Home and About open). */
private const val KOFI_URL = com.stash.feature.settings.components.DonateLinks.KOFI

/**
 * Under the "Lossless today" line: "Unlimited lossless for supporters" (opens the claim sheet)
 * or, once the relays report unlimited, "Supporter · unlimited lossless". Nothing when no owner
 * relay is configured.
 */
@Composable
internal fun SupporterSection(viewModel: SupporterViewModel = hiltViewModel()) {
    val row by viewModel.row.collectAsStateWithLifecycle()
    val claim by viewModel.claim.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }

    when (val r = row) {
        SupporterRow.Hidden -> return
        is SupporterRow.Supporter -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_supporter_active),
                style = MaterialTheme.typography.bodySmall,
                color = StashTheme.extendedColors.success,
                modifier = Modifier.weight(1f),
            )
            // Only when this device holds the code (a supporter elsewhere has nothing to remove).
            if (r.hasToken) TextButton(onClick = viewModel::removeCode) { Text(stringResource(R.string.settings_supporter_remove_from_device)) }
        }
        is SupporterRow.Offer -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable(role = Role.Button) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_supporter_offer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = StashTheme.extendedColors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    // The relays confirmed: close (the row has already flipped).
    LaunchedEffect(claim.done) {
        if (claim.done) {
            open = false
            viewModel.reset()
        }
    }

    if (open && row is SupporterRow.Offer) {
        SupporterClaimSheet(
            ui = claim,
            hasToken = (row as SupporterRow.Offer).hasToken,
            onSendCode = viewModel::sendCode,
            onResendCode = viewModel::resendCode,
            onUnlock = viewModel::unlock,
            onStartOver = viewModel::startOver,
            onRemoveCode = viewModel::removeCode,
            onDismiss = {
                open = false
                viewModel.reset()
            },
        )
    }
}

/**
 * The claim: one sentence, the Ko-fi email → "Email me a code" → the 6-digit code → "Unlock".
 * Field text lives here (synchronous state for the controlled fields); the view model only sees
 * what is sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupporterClaimSheet(
    ui: SupporterClaimUi,
    hasToken: Boolean,
    onSendCode: (String) -> Unit,
    onResendCode: () -> Unit,
    onUnlock: (String) -> Unit,
    onStartOver: () -> Unit,
    onRemoveCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var email by rememberSaveable { mutableStateOf(ui.email) }
    var code by rememberSaveable { mutableStateOf("") }
    // A new code request (or starting over) clears the last code typed.
    LaunchedEffect(ui.step) { code = "" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.settings_supporter_sheet_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                stringResource(R.string.settings_supporter_sheet_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 12.dp))

            when (ui.step) {
                SupporterClaimUi.Step.Email -> {
                    val send = { if (!ui.busy && email.isNotBlank()) onSendCode(email) }
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.take(254) },
                        label = { Text(stringResource(R.string.settings_supporter_kofi_email_label)) },
                        singleLine = true,
                        enabled = !ui.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Footer(ui)
                    Button(onClick = send, enabled = !ui.busy && email.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_supporter_email_code_button))
                    }
                }
                SupporterClaimUi.Step.Code -> {
                    val unlock = { if (!ui.busy && code.length == SupporterRepository.CODE_LENGTH) onUnlock(code) }
                    Text(
                        stringResource(R.string.settings_supporter_code_sent_desc, ui.email),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(SupporterRepository.CODE_LENGTH) },
                        label = { Text(stringResource(R.string.settings_supporter_code_label)) },
                        singleLine = true,
                        enabled = !ui.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { unlock() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Footer(ui)
                    Button(
                        onClick = unlock,
                        enabled = !ui.busy && code.length == SupporterRepository.CODE_LENGTH,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_supporter_unlock_button)) }
                    // A code that didn't work (wrong, expired or used up) is replaced from here.
                    TextButton(
                        onClick = {
                            code = ""
                            onResendCode()
                        },
                        enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_supporter_resend_code_button)) }
                    TextButton(onClick = onStartOver, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_supporter_different_email_button))
                    }
                }
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = { runCatching { uriHandler.openUri(KOFI_URL) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_supporter_donate_button))
                }
                // A stored code the relays don't honour (revoked, or not yet confirmed).
                if (hasToken) {
                    TextButton(onClick = onRemoveCode, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_supporter_remove_code_button))
                    }
                }
            }
        }
    }
}

/** The error / status line and the busy bar between a field and its button. */
@Composable
private fun Footer(ui: SupporterClaimUi) {
    ui.message?.let {
        Spacer(Modifier.padding(top = 8.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            // "Code accepted…" and "A new code is on its way." are not errors.
            color = if (ui.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (ui.busy) {
        Spacer(Modifier.padding(top = 8.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Spacer(Modifier.padding(top = 12.dp))
}
