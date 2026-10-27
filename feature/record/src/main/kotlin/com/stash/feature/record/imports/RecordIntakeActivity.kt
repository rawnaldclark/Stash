package com.stash.feature.record.imports

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Where a Spotify export opened with Stash (Files' "Open with", an email's attachment) or shared to it lands: an invisible screen
 * in the sending app's task. Its model ([RecordIntakeViewModel]) hands the export to Your record's import, which copies it at
 * once while this screen's grant to read it holds, and hands any other files of the share to Stash's window with a grant of
 * their own; this screen brings Stash's window forward (where the import sheet is showing) and goes once the copy is done. A
 * recreation keeps waiting: the model outlives it, and finishing early would end the grant mid-copy.
 *
 * Its own task affinity (none), so it never sits in Stash's task, where bringing Stash's window forward would clear it, and its
 * grant with it, mid-copy.
 */
@AndroidEntryPoint
class RecordIntakeActivity : ComponentActivity() {
    private val model: RecordIntakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touches go to the app underneath while the copy finishes (this window is see-through and lasts a moment)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        model.take(intent)
        lifecycleScope.launch { model.starts.collect { startActivity(it) } }
        lifecycleScope.launch {
            model.done.first { it }
            finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
