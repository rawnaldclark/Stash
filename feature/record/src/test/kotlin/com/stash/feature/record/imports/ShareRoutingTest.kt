package com.stash.feature.record.imports

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A share that reached Stash's window, routed in the app's own scope ([RecordShareRouter]) by the one rule the intake uses too
 * ([ShareClassifier]): a recreation of the window halfway drops nothing, a provider that never answers costs a bounded wait, a
 * share with no export goes to the library's import whole and once, and a file's name decides when its type says nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareRoutingTest {
    private val zip = Uri.parse("content://com.android.providers.downloads.documents/document/1")
    private val mp3 = Uri.parse("content://media/external/audio/media/7")
    private val flac = Uri.parse("content://com.example.cloud/files/live-take.flac")
    private val raw = Uri.parse("file:///sdcard/Music/demo.mp3")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "main-like") }.asCoroutineDispatcher()

    @After fun tearDown() {
        scope.cancel()
        main.close()
    }

    private class Library {
        val calls: MutableList<Pair<List<Uri>, String>> = Collections.synchronizedList(mutableListOf())
        fun take(uris: List<Uri>) {
            calls += uris to Thread.currentThread().name.substringBefore(" @")
        }
    }

    private fun router(typeOf: (Uri) -> String?, brought: MutableList<List<Uri>>, timeoutMs: Long = ShareClassifier.TIMEOUT_MS, nameOf: (Uri) -> String? = { it.lastPathSegment }) =
        RecordShareRouter(
            classifier = ShareClassifier(typeOf = typeOf, nameOf = nameOf, canRead = { true }, io = Dispatchers.IO, timeoutMs = timeoutMs),
            bringShared = { brought += it; Job() },
            scope = scope,
            main = main,
        )

    @Test(timeout = 20_000)
    fun `the window recreated while the types are asked - both halves still go where they belong`() = runBlocking<Unit> {
        val held = CountDownLatch(1)
        val brought = Collections.synchronizedList(mutableListOf<List<Uri>>())
        val library = Library()
        val r = router(typeOf = { uri -> held.await(10, TimeUnit.SECONDS); if (uri == zip) "application/zip" else "audio/mpeg" }, brought = brought)
        // The window's own scope routes the share, then goes (a rotation): nothing of it is needed any more
        val window = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        window.launch { r.route("*/*", listOf(zip, mp3), listOf(zip, mp3), library::take) }
        delay(50)
        window.cancel()

        held.countDown()
        waitUntil { library.calls.isNotEmpty() }

        assertThat(brought).containsExactly(listOf(zip))
        assertThat(library.calls.single().first).containsExactly(mp3)
        // On the main thread, as the window always called the library's import
        assertThat(library.calls.single().second).isEqualTo("main-like")
    }

    @Test(timeout = 20_000)
    fun `a provider whose type never comes is waited for briefly, its file taken as not an export`() = runBlocking<Unit> {
        val never = CountDownLatch(1)
        val brought = Collections.synchronizedList(mutableListOf<List<Uri>>())
        val library = Library()
        val r = router(typeOf = { uri -> if (uri == zip) { never.await(30, TimeUnit.SECONDS); "application/zip" } else "audio/mpeg" }, brought = brought, timeoutMs = 300)
        val began = System.currentTimeMillis()

        r.route("*/*", listOf(zip, mp3), listOf(zip, mp3), library::take)
        waitUntil { library.calls.isNotEmpty() }

        assertThat(System.currentTimeMillis() - began).isLessThan(5_000)
        assertThat(brought).isEmpty()
        assertThat(library.calls.single().first).containsExactly(zip, mp3).inOrder()
        never.countDown()
    }

    @Test(timeout = 20_000)
    fun `a share with no export goes to the library's import whole, once - every file of it, any scheme`() = runBlocking<Unit> {
        val brought = Collections.synchronizedList(mutableListOf<List<Uri>>())
        val library = Library()
        val r = router(typeOf = { "audio/mpeg" }, brought = brought)

        r.route("*/*", listOf(mp3, flac, raw), listOf(mp3, flac), library::take).join()
        delay(100)

        assertThat(brought).isEmpty()
        assertThat(library.calls).hasSize(1)
        assertThat(library.calls.single().first).containsExactly(mp3, flac, raw).inOrder()
    }

    @Test fun `the one rule - audio is audio, an export's types are its, a type that says nothing leaves it to the name`() {
        assertThat(RecordIntake.kindOf("audio/flac")).isEqualTo(RecordIntake.Kind.OTHER)
        assertThat(RecordIntake.kindOf("application/zip")).isEqualTo(RecordIntake.Kind.EXPORT)
        assertThat(RecordIntake.kindOf("application/x-zip-compressed")).isEqualTo(RecordIntake.Kind.EXPORT)
        assertThat(RecordIntake.kindOf("application/json; charset=utf-8")).isEqualTo(RecordIntake.Kind.EXPORT)
        for (t in listOf(null, "", "application/octet-stream", "*/*", "application/*", "application/x-zip", "binary/octet-stream")) {
            assertThat(RecordIntake.kindOf(t)).isEqualTo(RecordIntake.Kind.BY_NAME)
        }
        assertThat(RecordIntake.kindOf("image/png")).isEqualTo(RecordIntake.Kind.OTHER)
        assertThat(RecordIntake.kindOf("application/pdf")).isEqualTo(RecordIntake.Kind.OTHER)
        assertThat(RecordIntake.isExportName("my_spotify_data.ZIP")).isTrue()
        assertThat(RecordIntake.isExportName("StreamingHistory_music_0.json")).isTrue()
        assertThat(RecordIntake.isExportName("live-take.flac")).isFalse()
        assertThat(RecordIntake.isExportName(null)).isFalse()
    }

    @Test(timeout = 20_000)
    fun `an octet-stream zip goes to the import and an octet-stream FLAC to the library, by their names`() = runBlocking<Unit> {
        val gmailZip = Uri.parse("content://com.google.android.gm.sapi/attachment/42")
        val names = mapOf(gmailZip to "my_spotify_data.zip", flac to "live-take.flac")
        val c = ShareClassifier(typeOf = { "application/octet-stream" }, nameOf = { names[it] }, canRead = { true }, io = Dispatchers.IO)

        val split = c.split(listOf(gmailZip, flac), "*/*")

        assertThat(split.exports).containsExactly(gmailZip)
        assertThat(split.others).containsExactly(flac)
    }

    @Test(timeout = 20_000)
    fun `no type from the file - the share's own type decides, and a name that never comes means not an export`() = runBlocking<Unit> {
        val never = CountDownLatch(1)
        val zipShare = ShareClassifier(typeOf = { null }, nameOf = { null }, canRead = { true }, io = Dispatchers.IO)
        assertThat(zipShare.split(listOf(zip), "application/zip").exports).containsExactly(zip)
        val stuck = ShareClassifier(typeOf = { "application/octet-stream" }, nameOf = { never.await(30, TimeUnit.SECONDS); "x.zip" }, canRead = { true }, io = Dispatchers.IO, timeoutMs = 200)
        assertThat(stuck.split(listOf(zip), "*/*")).isEqualTo(ShareSplit(exports = emptyList(), others = listOf(zip)))
        never.countDown()
    }

    @Test(timeout = 20_000)
    fun `a file this app can't read is in neither half`() = runBlocking<Unit> {
        val contacts = Uri.parse("content://com.android.contacts/contacts/1")
        val c = ShareClassifier(typeOf = { "application/zip" }, nameOf = { null }, canRead = { it != contacts }, io = Dispatchers.IO)
        assertThat(c.split(listOf(zip, contacts), "*/*")).isEqualTo(ShareSplit(exports = listOf(zip), others = emptyList()))
    }

    private suspend fun waitUntil(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            delay(20)
        }
    }
}
