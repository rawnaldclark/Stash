package com.stash.data.cloud.net

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.CloudProvider
import com.stash.core.model.CloudRef
import com.stash.data.cloud.CloudLibrary
import com.stash.data.cloud.auth.ProviderAuth
import com.stash.data.cloud.playback.CloudLinkCache
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.CloudEntry
import com.stash.data.cloud.provider.CloudLink
import com.stash.data.cloud.provider.CloudStorageProvider
import com.stash.data.cloud.provider.ScanPage
import com.stash.data.cloud.provider.ScanRoot
import com.stash.data.cloud.provider.dropbox.DropboxProvider
import com.stash.data.cloud.provider.onedrive.OneDriveProvider
import com.stash.data.cloud.scan.CloudFiles
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Test

class RangeFetcherAndLinksTest {

    private val server = MockWebServer().apply { start() }
    private val fetcher = RangeFetcher(OkHttpClient(), maxSkipBytes = 100)
    private val file = ByteArray(1000) { (it % 256).toByte() }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `206 answers are returned as is, with a Range header and no auth`() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(file.copyOfRange(10, 20))))
        val bytes = fetcher.fetch(server.url("/f").toString(), 10, 10)
        assertThat(bytes).isEqualTo(file.copyOfRange(10, 20))
        val req = server.takeRequest()
        assertThat(req.getHeader("Range")).isEqualTo("bytes=10-19")
        assertThat(req.getHeader("Authorization")).isNull()
    }

    @Test
    fun `a 200 whole-file answer is skipped to the offset`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(file)))
        assertThat(fetcher.fetch(server.url("/f").toString(), 50, 5)).isEqualTo(file.copyOfRange(50, 55))
    }

    @Test(expected = java.io.IOException::class)
    fun `a 200 answer to a far tail read fails instead of downloading the song`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(file)))
        fetcher.fetch(server.url("/f").toString(), 900, 50)
    }

    @Test(expected = LinkExpiredException::class)
    fun `an expired link is reported as such`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        fetcher.fetch(server.url("/f").toString(), 0, 10)
    }

    // ── Link cache / resolver ────────────────────────────────────────────

    private class CountingProvider(override val linkTtlMs: Long) : CloudStorageProvider {
        var calls = 0
        var now = 0L
        override val provider = CloudProvider.DROPBOX
        override val isConfigured = true
        override val auth: ProviderAuth get() = error("unused")
        override suspend fun browse(accountId: Long, folderId: String?) = emptyList<CloudEntry>()
        override suspend fun scanPage(accountId: Long, root: ScanRoot, cursor: String?) = error("unused") as ScanPage
        override suspend fun describeFolder(accountId: Long, folderId: String): CloudEntry? = null
        override suspend fun link(accountId: Long, itemId: String, priority: RequestPriority): CloudLink {
            delay(10)
            calls++
            return CloudLink("https://dl/$itemId/$calls", 123, "rev", now)
        }
    }

    @Test
    fun `links are cached per file for the provider's ttl and single-flight`() = runTest {
        val p = CountingProvider(linkTtlMs = 1_000)
        var clock = 0L
        val resolver = CloudLinkResolver(mapOf(CloudProvider.DROPBOX to p), CloudLinkCache { clock })
        val ref = CloudRef(CloudProvider.DROPBOX, 1, "id:abc", "rev")

        val urls = (1..4).map { async { resolver.resolve(ref).url } }.awaitAll()
        assertThat(urls.toSet()).hasSize(1)
        assertThat(p.calls).isEqualTo(1)

        clock = 999
        resolver.resolve(ref)
        assertThat(p.calls).isEqualTo(1)
        clock = 1_000
        p.now = 1_000
        resolver.resolve(ref)
        assertThat(p.calls).isEqualTo(2)

        resolver.invalidate(ref)
        resolver.resolve(ref)
        assertThat(p.calls).isEqualTo(3)
        resolver.resolve(ref, fresh = true)
        assertThat(p.calls).isEqualTo(4)
    }

    @Test
    fun `cache key ignores the version and URL, separates accounts`() {
        val a = CloudLinkCache.keyOf(CloudRef(CloudProvider.DROPBOX, 1, "id:x", "r1"))
        assertThat(a).isEqualTo(CloudLinkCache.keyOf(CloudRef(CloudProvider.DROPBOX, 1, "id:x", "r2")))
        assertThat(a).isNotEqualTo(CloudLinkCache.keyOf(CloudRef(CloudProvider.DROPBOX, 2, "id:x", "r1")))
    }

    // ── Small pure helpers ───────────────────────────────────────────────

    @Test
    fun `dropbox relative folders`() {
        assertThat(DropboxProvider.relativeFolders("/Music", "/music/Artist/Album/01.flac")).containsExactly("Artist", "Album").inOrder()
        assertThat(DropboxProvider.relativeFolders("/Music", "/Music/01.flac")).isEmpty()
        assertThat(DropboxProvider.relativeFolders("", "/A/B/x.mp3")).containsExactly("A", "B").inOrder()
        assertThat(DropboxProvider.relativeFolders("/Music", "/Other/x.mp3")).isEmpty()
    }

    @Test
    fun `onedrive paging links never leave graph`() {
        assertThat(OneDriveProvider.requireGraph("https://graph.microsoft.com/v1.0/me/drive/items/x/delta?token=1")).isNotEmpty()
        listOf("http://graph.microsoft.com/v1.0/x", "https://graph.microsoft.com.evil.example/x", "https://evil.example/x").forEach {
            runCatching { OneDriveProvider.requireGraph(it) }.let { r -> assertThat(r.isFailure).isTrue() }
        }
    }

    @Test
    fun `onedrive item paths`() {
        val item = OneDriveProvider.ItemJson(id = "1", name = "Rips", parentReference = OneDriveProvider.ParentJson("p", "/drive/root:/Music"))
        assertThat(OneDriveProvider.pathOf(item)).isEqualTo("/Music/Rips")
        val top = OneDriveProvider.ItemJson(id = "2", name = "Music", parentReference = OneDriveProvider.ParentJson("r", "/drive/root:"))
        assertThat(OneDriveProvider.pathOf(top)).isEqualTo("/Music")
    }

    @Test
    fun `audio, cover and format rules`() {
        assertThat(CloudFiles.isAudio("a.FLAC", null)).isTrue()
        assertThat(CloudFiles.isAudio("a.mp4", "video/mp4")).isFalse()
        assertThat(CloudFiles.isAudio("noext", "audio/mpeg")).isTrue()
        assertThat(CloudFiles.isAudio("cover.jpg", "image/jpeg")).isFalse()
        assertThat(CloudFiles.coverRank("Cover.JPG")).isEqualTo(0)
        assertThat(CloudFiles.coverRank("folder.png")).isEqualTo(1)
        assertThat(CloudFiles.coverRank("scan.jpg")).isNull()
        assertThat(CloudFiles.formatFor("a.m4a", "alac")).isEqualTo("alac")
        assertThat(CloudFiles.formatFor("a.m4a", "aac")).isEqualTo("m4a")
        assertThat(CloudFiles.formatFor("a.oga", "vorbis")).isEqualTo("ogg")
    }

    @Test
    fun `picked folders may not overlap`() {
        assertThat(CloudLibrary.overlaps("/Music", "/music/Rips")).isTrue()
        assertThat(CloudLibrary.overlaps("/Music/Rips", "/Music")).isTrue()
        assertThat(CloudLibrary.overlaps("/Music", "/Musicals")).isFalse()
        assertThat(CloudLibrary.overlaps("", "/Anything")).isTrue()
    }
}
