package com.stash.feature.record

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextSecondary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Your record's two tabs (stash-player `src/routes/spotify/+page.svelte`): the record (the default) and its Mixes. */
enum class RecordTab(val key: String) {
    RECORD("record"), MIXES("mixes");

    companion object {
        fun of(key: String?): RecordTab? = entries.firstOrNull { it.key == key }
    }
}

/** The tab picked last, kept on this phone (the web keeps it in the browser, `stash.spotify.tab`). */
@Singleton
class RecordTabStore internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    var last: RecordTab
        get() = RecordTab.of(prefs.getString(KEY, null)) ?: RecordTab.RECORD
        set(v) {
            prefs.edit().putString(KEY, v.key).apply()
        }

    private companion object {
        /** Your record's own preferences (the import's meta and the sources' switches live there too). */
        const val FILE = "your_record"
        const val KEY = "record_tab"
    }
}

/** The control's ground, a field a step lighter than the record's (the source switches' own, theme.css `--wp-field`). */
private val TabsField = Color(0x1FFFFFFF)

/** The tab shown: a pill a step lighter again. */
private val TabOn = Color(0x29FFFFFF)

/**
 * The record's slim header (the web's `sy-bar`): "The record | Mixes", a segmented control, and on the Mixes tab a way out (the
 * record's own page has its ×). On the record's dark ground in both themes.
 */
@Composable
internal fun RecordTabsBar(tab: RecordTab, onPick: (RecordTab) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f, fill = false).height(36.dp).clip(CircleShape).background(TabsField).padding(3.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (t in RecordTab.entries) {
                val on = t == tab
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(CircleShape)
                        .background(if (on) TabOn else Color.Transparent)
                        .selectable(selected = on, role = Role.Tab, onClick = { if (!on) onPick(t) })
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (t == RecordTab.RECORD) R.string.record_tab_record else R.string.record_tab_mixes),
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) StashTextPrimary else StashTextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.weight(1f))
        // Always laid out, so the tabs never move; only the Mixes tab shows it (the record's page has its own ×)
        val mixes = tab == RecordTab.MIXES
        val close = stringResource(R.string.record_close)
        IconButton(
            onClick = onClose,
            enabled = mixes,
            modifier = Modifier.alpha(if (mixes) 1f else 0f).then(if (mixes) Modifier.semantics { contentDescription = close } else Modifier.clearAndSetSemantics { }),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = StashTextPrimary, modifier = Modifier.size(24.dp))
        }
    }
}
