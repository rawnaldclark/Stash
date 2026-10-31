package com.stash.feature.record

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.feature.record.archive.RecordSource
import com.stash.feature.record.card.RecordCard
import java.util.Locale

/** The switch's ground when on: a field a step lighter than the record's ground (theme.css `--wp-field` on the dark sleeve). */
private val SwitchField = Color(0x1FFFFFFF)

/** The switch's outline when off, the page's own pills' line (theme.css `--wp-line-2`). */
private val SwitchLine = Color(0x33FFFFFF)

/**
 * The sources the record stacks, a switch for each one there is (stash-player `src/routes/spotify/+page.svelte`'s `sy-srcs`,
 * which sits in the header right above the record): its dot, its name and its listens, filled when on, outlined when off. The
 * one source with plays left on stays on ([onFlip] says so). Over the record's dark ground in both themes, as its own pills
 * are; a row that scrolls sideways when the words are long.
 */
@Composable
internal fun RecordSourceBar(switches: SourceSwitches, onFlip: (RecordSource) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.record_sources_label)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in switches.sources) {
            SourceSwitch(source = s, on = switches.on[s], plays = switches.counts[s], last = switches.isLast(s), onFlip = { onFlip(s) })
        }
    }
}

@Composable
private fun SourceSwitch(source: RecordSource, on: Boolean, plays: Int, last: Boolean, onFlip: () -> Unit) {
    val name = stringResource(source.label)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val spoken = stringResource(
        R.string.record_source_described,
        name,
        pluralStringResource(R.plurals.record_plays, plays, RecordCard.count(plays.toLong(), locale)),
    )
    val stays = stringResource(R.string.record_source_one_stays)
    val short = remember(plays, locale) { compact(plays, locale) }
    Surface(
        shape = CircleShape,
        color = if (on) SwitchField else Color.Transparent,
        border = if (on) null else BorderStroke(1.dp, SwitchLine),
        modifier = Modifier
            .height(32.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onFlip() })
            .semantics {
                contentDescription = spoken
                if (last) stateDescription = stays
            },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp).clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.align(Alignment.CenterVertically).size(8.dp).dot(on))
            val style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 13.sp)
            Text(name, style = style.copy(fontWeight = FontWeight.SemiBold), color = if (on) StashTextPrimary else StashTextSecondary, maxLines = 1)
            Text(short, style = style.tabular(), color = StashTextSecondary, maxLines = 1)
        }
    }
}

/** The dot: the accent when on, a ring when off. */
private fun Modifier.dot(on: Boolean): Modifier =
    if (on) background(StashPurple, CircleShape) else border(1.5.dp, StashTextSecondary, CircleShape)

private fun TextStyle.tabular(): TextStyle = copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

/** The source's name, as On Record's credit shows it (brand names, never translated). */
private val RecordSource.label: Int
    get() = when (this) {
        RecordSource.SPOTIFY -> R.string.record_source_spotify
        RecordSource.STASH -> R.string.record_source_stash
        RecordSource.LASTFM -> R.string.record_source_lastfm
    }

/** The page's `SHORT`: "333", "12.3K", "1.2M" (compact, one decimal at most), in the app's language. */
internal fun compact(n: Int, locale: Locale): String =
    CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).apply { maximumFractionDigits = 1 }.format(n.toLong())
