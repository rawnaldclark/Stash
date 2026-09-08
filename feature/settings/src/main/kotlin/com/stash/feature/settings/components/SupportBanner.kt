package com.stash.feature.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashCyan
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashPurpleLight

/**
 * A premium gradient Support card for the Settings hub. Renders a one-line pitch
 * and two actions (Donate / Star). Pure presentation — the hub screen wires the
 * actual donate/star URL handlers via [onDonate] and [onStar].
 */
@Composable
fun SupportBanner(
    onDonate: () -> Unit,
    onDonateCoDev: () -> Unit,
    onStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        StashPurple.copy(alpha = 0.16f),
                        StashCyan.copy(alpha = 0.06f),
                    ),
                ),
            )
            .border(
                BorderStroke(1.dp, StashPurpleLight.copy(alpha = 0.28f)),
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Support Stash",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "If Stash replaced a subscription for you, consider supporting the project.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            var showDonateMenu by remember { mutableStateOf(false) }

            Box(modifier = Modifier.weight(1f)) {
                Button(
                    onClick = { showDonateMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Donate", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(
                    expanded = showDonateMenu,
                    onDismissRequest = { showDonateMenu = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(text = "rawnaldclark (rawn)", style = MaterialTheme.typography.bodyMedium)
                                Text(text = "Owner, main dev", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            showDonateMenu = false
                            onDonate()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(text = "Paraliyzed_evo", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Co-dev — makes the beta builds",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = {
                            showDonateMenu = false
                            onDonateCoDev()
                        },
                    )
                }
            }
            OutlinedButton(
                onClick = onStar,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Star", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
