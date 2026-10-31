package com.stash.feature.record.imports

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * The intake screen's work ([RecordIntakeViewModel]): the export to the import, the others to Stash's window with a grant of their
 * own (only files the intake may read: forwarding one it may not would throw), a start the system refuses falling back to Stash's
 * window alone, a restore after the app's death never forwarding again, and the files' types asked off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordIntakeTest {
    private val zip = Uri.parse("content://com.android.providers.downloads.documents/document/1")
    private val mp3 = Uri.parse("content://media/external/audio/media/7")
    private val flac = Uri.parse("content://media/external/audio/media/8")
    private val contacts = Uri.parse("content://com.android.contacts/contacts/1")
    private val own = Uri.parse("content://com.stash.app.debug.fileprovider/cache/my_spotify_data.zip")
    private val file = Uri.parse("file:///sdcard/Download/my_spotify_data.zip")
    private val types = mapOf(zip to "application/zip", mp3 to "audio/mpeg", flac to "audio/flac", own to "application/zip", contacts to "vnd.android.cursor.item/contact")
    private val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName("com.stash.app.debug", "com.stash.app.MainActivity")

    private var executor: ExecutorCoroutineDispatcher? = null

    @After fun tearDown() {
        Dispatchers.resetMain()
        executor?.close()
    }

    private class Taken(val model: RecordIntakeViewModel, val brought: MutableList<List<Uri>>, val starts: MutableList<RecordIntake.Start>, val copy: CompletableJob, val saved: SavedStateHandle)

    private fun TestScope.model(
        io: CoroutineContext = StandardTestDispatcher(testScheduler),
        typeOf: (Uri) -> String? = { types[it] },
        readable: (Uri) -> Boolean = { it != contacts },
        saved: SavedStateHandle = SavedStateHandle(),
    ): Taken {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val copy = Job()
        val brought = mutableListOf<List<Uri>>()
        val m = RecordIntakeViewModel(
            bringShared = { brought += it; copy },
            classifier = ShareClassifier(typeOf = typeOf, nameOf = { it.lastPathSegment }, canRead = readable, io = io),
            ownPackage = "com.stash.app.debug",
            launchIntent = { launch },
            saved = saved,
        )
        val starts = mutableListOf<RecordIntake.Start>()
        backgroundScope.launch { m.starts.collect { starts += it } }
        return Taken(m, brought, starts, copy, saved)
    }

    private fun share(vararg uris: Uri) = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(*uris))

    @Test fun `the audio in a share goes to Stash's window with a grant of its own, the export to the import`() = runTest {
        val t = model()
        t.model.take(share(zip, mp3, own, file))
        runCurrent()

        assertThat(t.brought).containsExactly(listOf(zip))
        val f = t.starts.single().intent
        assertThat(f.component).isEqualTo(launch.component)
        assertThat(f.hasCategory(Intent.CATEGORY_LAUNCHER)).isFalse()
        assertThat(f.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(f.type).isEqualTo("*/*")
        assertThat(IntentCompat.getParcelableExtra(f, Intent.EXTRA_STREAM, Uri::class.java)).isEqualTo(mp3)
        // The grant goes with the intent: the flag, and the files in its ClipData
        assertThat(f.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(f.clipData!!.itemCount).isEqualTo(1)
        assertThat(f.clipData!!.getItemAt(0).uri).isEqualTo(mp3)
        assertThat(f.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Stash's window takes them straight to the library's import
        assertThat(f.getBooleanExtra(RecordIntake.EXTRA_FORWARDED, false)).isTrue()

        // The screen goes only once the export is copied
        assertThat(t.model.done.value).isFalse()
        t.copy.complete()
        runCurrent()
        assertThat(t.model.done.value).isTrue()
    }

    @Test fun `a file the intake was never granted is forwarded to no one`() = runTest {
        val t = model()
        // A sender (or a hostile app) naming the contacts provider among its streams
        t.model.take(share(mp3, contacts))
        runCurrent()

        val f = t.starts.single().intent
        assertThat(IntentCompat.getParcelableExtra(f, Intent.EXTRA_STREAM, Uri::class.java)).isEqualTo(mp3)
        assertThat(f.clipData!!.itemCount).isEqualTo(1)
    }

    @Test fun `a start the system refuses falls back to Stash's window alone, and never crashes`() = runTest {
        val t = model()
        t.model.take(share(zip, mp3))
        runCurrent()
        val started = mutableListOf<Intent>()

        RecordIntake.startSafely(t.starts.single()) { intent ->
            if (intent.clipData != null) throw SecurityException("no grant to pass on")
            started += intent
        }

        val fallback = started.single()
        assertThat(fallback.component).isEqualTo(launch.component)
        assertThat(fallback.clipData).isNull()
        assertThat(fallback.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0)
        // Refused even alone: still no crash
        RecordIntake.startSafely(t.starts.single()) { throw SecurityException("nothing at all") }
    }

    @Test fun `several other files go together, each in the grant`() = runTest {
        val t = model()
        t.model.take(share(mp3, flac))
        runCurrent()

        assertThat(t.brought).isEmpty()
        val f = t.starts.single().intent
        assertThat(f.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
        assertThat(IntentCompat.getParcelableArrayListExtra(f, Intent.EXTRA_STREAM, Uri::class.java)).containsExactly(mp3, flac).inOrder()
        assertThat((0 until f.clipData!!.itemCount).map { f.clipData!!.getItemAt(it).uri }).containsExactly(mp3, flac).inOrder()
        assertThat(t.model.done.value).isTrue()
    }

    @Test fun `an export alone brings Stash's window forward with nothing to hand on`() = runTest {
        val t = model()
        t.model.take(Intent(Intent.ACTION_VIEW).setDataAndType(zip, "application/zip"))
        runCurrent()

        assertThat(t.brought).containsExactly(listOf(zip))
        val f = t.starts.single().intent
        assertThat(f.getBooleanExtra(RecordIntake.EXTRA_FORWARDED, false)).isFalse()
        assertThat(f.clipData).isNull()
        assertThat(f.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0)
    }

    @Test fun `a recreated screen takes nothing again and waits for the same copy`() = runTest {
        val t = model()
        val intent = share(zip, mp3)
        t.model.take(intent)
        runCurrent()
        t.model.take(intent)
        runCurrent()

        assertThat(t.brought).hasSize(1)
        assertThat(t.starts).hasSize(1)
        t.copy.complete()
        runCurrent()
        assertThat(t.model.done.value).isTrue()
    }

    @Test fun `restored after the app died mid-copy - the export is brought again, nothing is forwarded again`() = runTest {
        // What the screen had saved: Stash's window brought forward with the audio, the export's copy not finished
        val t = model(saved = SavedStateHandle(mapOf(RecordIntakeViewModel.FORWARDED to true)))
        t.model.take(share(zip, mp3))
        runCurrent()

        assertThat(t.starts).isEmpty()
        assertThat(t.brought).containsExactly(listOf(zip))
        t.copy.complete()
        runCurrent()
        assertThat(t.model.done.value).isTrue()
        assertThat(t.saved.get<Boolean>(RecordIntakeViewModel.COPIED)).isTrue()
    }

    @Test fun `restored after the copy had finished - nothing is done again`() = runTest {
        val t = model(saved = SavedStateHandle(mapOf(RecordIntakeViewModel.FORWARDED to true, RecordIntakeViewModel.COPIED to true)))
        t.model.take(share(zip, mp3))
        runCurrent()

        assertThat(t.starts).isEmpty()
        assertThat(t.brought).isEmpty()
        assertThat(t.model.done.value).isTrue()
    }

    @Test fun `what it does is saved for a restore`() = runTest {
        val t = model()
        t.model.take(share(zip, mp3))
        runCurrent()
        assertThat(t.saved.get<Boolean>(RecordIntakeViewModel.FORWARDED)).isTrue()
        assertThat(t.saved.get<Boolean>(RecordIntakeViewModel.COPIED)).isNull()
        t.copy.complete()
        runCurrent()
        assertThat(t.saved.get<Boolean>(RecordIntakeViewModel.COPIED)).isTrue()
    }

    @Test fun `nothing it can take - Stash's window isn't brought up, and the screen goes`() = runTest {
        val t = model()
        t.model.take(share(file, own))
        runCurrent()

        assertThat(t.brought).isEmpty()
        assertThat(t.starts).isEmpty()
        assertThat(t.model.done.value).isTrue()
    }

    @Test fun `the files' types are asked off the main thread`() = runTest {
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "intake-io") }.asCoroutineDispatcher().also { executor = it }
        val threads = mutableListOf<String>()
        val t = model(io = io, typeOf = { synchronized(threads) { threads += Thread.currentThread().name.substringBefore(" @") }; types[it] })
        t.model.take(share(zip, mp3))
        val end = System.currentTimeMillis() + 5_000
        while (t.starts.isEmpty() && System.currentTimeMillis() < end) {
            runCurrent()
            Thread.sleep(10)
        }

        assertThat(t.starts).hasSize(1)
        assertThat(synchronized(threads) { threads.toSet() }).containsExactly("intake-io")
    }
}
