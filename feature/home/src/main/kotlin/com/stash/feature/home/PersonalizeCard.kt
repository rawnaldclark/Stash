package com.stash.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.GlassCard

/**
 * Cold-start state for Home (spec §5): shown when there aren't enough discovery
 * sections to fill the screen — a brand-new or thin-library user. Prompts them
 * to connect/sync so discovery can be tailored, instead of rendering a near-blank
 * Home.
 */
@Composable
fun PersonalizeCard(
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Personalize your Home",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Connect a service and run a sync so Stash can tailor daily discovery to your taste.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onConnect) {
                Text("Get started")
            }
        }
    }
}
