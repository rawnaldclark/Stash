package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.output.HiResOutputController
import com.stash.core.media.output.SignalQuality
import com.stash.core.media.output.SignalVerdict
import com.stash.core.media.output.withService
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Exposes the live signal path to Now Playing. */
@HiltViewModel
class SignalPathViewModel @Inject constructor(
    controller: HiResOutputController,
) : ViewModel() {
    val verdict: StateFlow<SignalVerdict?> = controller.signalPath
}

object SignalPathBadge {
    /**
     * Short badge for the quality line: "Bit-perfect", "Hi-Res", or null when there's nothing to boast.
     *
     * Resampling is told only inside this sheet (headline "Resampled to 48 kHz by Android", and the
     * Android row), never on the quality line.
     */
    fun SignalVerdict.badge(): String? = when (quality) {
        SignalQuality.BIT_PERFECT -> "Bit-perfect"
        SignalQuality.RESAMPLED -> null
        SignalQuality.LOSSLESS -> if (headline.startsWith("Hi-Res")) "Hi-Res" else null
        SignalQuality.ENHANCED, SignalQuality.LOSSY -> null
    }
}

private fun SignalQuality.color(): Color = when (this) {
    SignalQuality.BIT_PERFECT -> Color(0xFF6EE7B7)
    SignalQuality.LOSSLESS -> Color(0xFF93C5FD)
    SignalQuality.ENHANCED -> Color(0xFFC4B5FD)
    SignalQuality.RESAMPLED -> Color(0xFFFCD34D)
    SignalQuality.LOSSY -> Color(0xFF9CA3AF)
}

/**
 * "What happens to this song on the way to your ears": source → Stash →
 * output → Android → device, each stage marked when it costs fidelity. The
 * honest answer the hi-fi players give (Roon calls it the signal path), so a
 * listener can see whether their 24/96 FLAC is reaching the DAC as 24/96 —
 * and what to change when it isn't.
 *
 * @param service the streaming service the song is coming from ("Qobuz",
 *   "Tidal"), named at the head of the Source line; null for a downloaded or
 *   local file, whose source isn't stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalPathSheet(
    onDismiss: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    service: String? = null,
    viewModel: SignalPathViewModel = hiltViewModel(),
) {
    val verdict by viewModel.verdict.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StashTheme.extendedColors.elevatedSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "Signal path",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(16.dp))
            val v = verdict?.withService(service)
            if (v == null) {
                Text(
                    text = "Start playing to see the path from file to DAC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(v.quality.color(), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = v.headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            v.stages.forEach { stage ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .semantics { stateDescription = if (stage.ok) "unaltered" else "alters the signal" },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(8.dp)
                            .background(
                                if (stage.ok) MaterialTheme.colorScheme.primary
                                else SignalQuality.RESAMPLED.color(),
                                CircleShape,
                            ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stage.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = StashTheme.extendedColors.textTertiary,
                        )
                        Text(
                            text = stage.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (onOpenSettings != null && v.quality == SignalQuality.RESAMPLED) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End)) {
                    Text("Hi-Res output settings")
                }
            }
        }
    }
}
