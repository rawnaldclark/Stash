package com.stash.feature.settings.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R

/**
 * Every donation link the app shows, in one place. Mirrors `LINKS` in the
 * stashfm.app site (web/src/data/site.ts); if one changes there, change it here.
 */
object DonateLinks {
    const val KOFI = "https://ko-fi.com/rawnald"
    const val PAYPAL_RAWN = "https://www.paypal.me/rawnaldclark"
    const val SPONSORS = "https://github.com/sponsors/rawnaldclark"
    /** rawn's Stripe Payment Link in INR with UPI on, for supporters in India. */
    const val UPI = "https://buy.stripe.com/bJe4gz5uP8GP7z454D1gs00"
    const val PAYPAL_EVO = "https://www.paypal.com/paypalme/Paraliyzedevo"
}

/** One donate button: its label and the URL it opens. */
private data class DonateLink(@StringRes val label: Int, val url: String)

/**
 * The Settings "Donate" sheet: one card per person (avatar, name, role, links)
 * and a short thank-you. Pure presentation; [onOpenUrl] opens a link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateSheet(
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_donate_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_donate_sheet_thanks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            PersonCard(
                avatar = R.drawable.donate_avatar_rawn,
                name = "rawn",
                role = R.string.settings_donate_role_owner,
                links = listOf(
                    DonateLink(R.string.settings_donate_kofi, DonateLinks.KOFI),
                    DonateLink(R.string.settings_donate_paypal, DonateLinks.PAYPAL_RAWN),
                    DonateLink(R.string.settings_donate_sponsors, DonateLinks.SPONSORS),
                    DonateLink(R.string.settings_donate_upi, DonateLinks.UPI),
                ),
                hint = R.string.settings_donate_upi_hint,
                onOpenUrl = onOpenUrl,
            )
            PersonCard(
                avatar = R.drawable.donate_avatar_evo,
                name = "Evo (Paraliyzed_evo)",
                role = R.string.settings_donate_role_codev,
                links = listOf(DonateLink(R.string.settings_donate_paypal, DonateLinks.PAYPAL_EVO)),
                hint = null,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

/**
 * A glass card for one person. The first link is the filled primary action,
 * the rest are outlined, so each card has one clear "main" way to give.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonCard(
    @DrawableRes avatar: Int,
    name: String,
    @StringRes role: Int,
    links: List<DonateLink>,
    @StringRes hint: Int?,
    onOpenUrl: (String) -> Unit,
) {
    val ext = StashTheme.extendedColors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ext.glassBackground)
            .border(BorderStroke(1.dp, ext.glassBorder), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(role),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            links.forEachIndexed { i, link ->
                val label = stringResource(link.label)
                if (i == 0) {
                    Button(onClick = { onOpenUrl(link.url) }) { Text(label, style = MaterialTheme.typography.labelLarge) }
                } else {
                    OutlinedButton(onClick = { onOpenUrl(link.url) }) { Text(label, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        if (hint != null) {
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
