package com.stash.feature.record.imports

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.importer.MemoryArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Only a content URI is ever read, whichever way it came (picked, shared, opened with Stash): a `file:` URI could name anything on
 * the phone (`/dev/zero`, the app's own database through a path), and no other scheme is a file another app hands over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ContentOnlyIntakeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `only content URIs are taken`() {
        assertThat(ImportIntake.takes(Uri.parse("content://com.android.providers.downloads.documents/document/42"))).isTrue()
        assertThat(ImportIntake.takes(Uri.parse("CONTENT://com.example.files/my_spotify_data.zip"))).isTrue()
        assertThat(ImportIntake.takes(Uri.parse("file:///dev/zero"))).isFalse()
        assertThat(ImportIntake.takes(Uri.parse("file:///data/data/com.stash.app/databases/stash.db"))).isFalse()
        assertThat(ImportIntake.takes(Uri.parse("/sdcard/Download/my_spotify_data.zip"))).isFalse()
        assertThat(ImportIntake.takes(Uri.parse("https://example.com/my_spotify_data.zip"))).isFalse()
    }

    @Test fun `a file URI picked or shared is never opened, and the content ones beside it are`() = runTest {
        val opened = mutableListOf<Uri>()
        var launches = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val imports = RecordImports(
            store = RecordImportStore(MemoryPrefs()),
            archive = MemoryArchive(),
            ownSpan = { null to null },
            intake = ImportIntake(tmp.newFolder()),
            scratch = tmp.newFolder(),
            sourceOf = { uri ->
                opened += uri
                IntakeSource("my_spotify_data.zip", 2) { "[]".byteInputStream() }
            },
            launch = { launches++ },
            cancelJob = {},
            jobAlive = { false },
            scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            now = { 0L },
            zone = ZoneOffset.UTC,
            io = dispatcher,
        )
        runCurrent()

        assertThat(imports.bringShared(listOf(Uri.parse("file:///dev/zero")))).isNull()
        imports.bring(listOf(Uri.parse("file:///dev/zero")))
        runCurrent()
        assertThat(opened).isEmpty()
        assertThat(imports.state.value).isEqualTo(ImportState.Idle)
        assertThat(launches).isEqualTo(0)

        val content = Uri.parse("content://com.android.providers.downloads.documents/document/42")
        imports.bringShared(listOf(Uri.parse("file:///dev/zero"), content))
        runCurrent()
        assertThat(opened).containsExactly(content)
    }
}
