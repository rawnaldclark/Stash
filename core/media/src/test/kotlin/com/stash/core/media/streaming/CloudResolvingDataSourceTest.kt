package com.stash.core.media.streaming

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.CloudProvider
import com.stash.core.model.CloudRef
import com.stash.data.cloud.auth.ProviderAuth
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.CloudEntry
import com.stash.data.cloud.provider.CloudLink
import com.stash.data.cloud.provider.CloudStorageProvider
import com.stash.data.cloud.provider.ScanPage
import com.stash.data.cloud.provider.ScanRoot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * `stash-cloud://` items: the link comes from the provider (cached), the bytes from the link with
 * the caller's range; a refused link is re-resolved once at the same position; a missing
 * Content-Length is filled from the provider's file size; the cache key never holds the URL.
 */
@RunWith(RobolectricTestRunner::class)
class CloudResolvingDataSourceTest {

    private class FakeProvider : CloudStorageProvider {
        var calls = 0
        override val provider = CloudProvider.DROPBOX
        override val isConfigured = true
        override val auth: ProviderAuth get() = error("unused")
        override val linkTtlMs = 60_000L
        override suspend fun browse(accountId: Long, folderId: String?) = emptyList<CloudEntry>()
        override suspend fun scanPage(accountId: Long, root: ScanRoot, cursor: String?): ScanPage = error("unused")
        override suspend fun describeFolder(accountId: Long, folderId: String): CloudEntry? = null
        override suspend fun link(accountId: Long, itemId: String, priority: RequestPriority): CloudLink {
            calls++
            return CloudLink("https://dl.example/$itemId/$calls", 5_000, "rev1", System.currentTimeMillis())
        }
    }

    private val provider = FakeProvider()
    private val resolver = CloudLinkResolver(mapOf(CloudProvider.DROPBOX to provider))
    private val ref = CloudRef(CloudProvider.DROPBOX, 3, "id:song", "rev1")

    private fun http(onOpen: (DataSpec) -> Long): HttpDataSource {
        val h = mockk<HttpDataSource>(relaxed = true)
        val spec = slot<DataSpec>()
        every { h.open(capture(spec)) } answers { onOpen(spec.captured) }
        return h
    }

    @Test
    fun `opens the provider's link at the asked position, length from metadata when unset`() {
        val opened = mutableListOf<DataSpec>()
        val source = CloudResolvingDataSource(resolver) { http { opened += it; C.LENGTH_UNSET.toLong() } }

        val length = source.open(DataSpec.Builder().setUri(Uri.parse(ref.toUri())).setPosition(1_000).build())

        assertThat(opened.single().uri.toString()).isEqualTo("https://dl.example/id:song/1")
        assertThat(opened.single().position).isEqualTo(1_000L)
        assertThat(length).isEqualTo(4_000L)
        assertThat(source.uri.toString()).isEqualTo("https://dl.example/id:song/1")
    }

    @Test
    fun `a refused link is resolved again and reopened at the same position`() {
        val opened = mutableListOf<DataSpec>()
        val source = CloudResolvingDataSource(resolver) {
            http { spec ->
                opened += spec
                if (spec.uri.toString().endsWith("/1")) {
                    throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), spec, ByteArray(0))
                }
                2_000L
            }
        }

        val length = source.open(DataSpec.Builder().setUri(Uri.parse(ref.toUri())).setPosition(3_000).build())

        assertThat(provider.calls).isEqualTo(2)
        assertThat(opened.map { it.uri.toString() }).containsExactly("https://dl.example/id:song/1", "https://dl.example/id:song/2").inOrder()
        assertThat(opened.last().position).isEqualTo(3_000L)
        assertThat(length).isEqualTo(2_000L)
    }

    @Test
    fun `a second open (a seek) reuses the cached link`() {
        val source = CloudResolvingDataSource(resolver) { http { 100L } }
        source.open(DataSpec.Builder().setUri(Uri.parse(ref.toUri())).build())
        source.close()
        source.open(DataSpec.Builder().setUri(Uri.parse(ref.toUri())).setPosition(50).build())
        assertThat(provider.calls).isEqualTo(1)
    }

    @Test
    fun `server errors other than a refused link surface`() {
        val source = CloudResolvingDataSource(resolver) {
            http { spec -> throw HttpDataSource.InvalidResponseCodeException(500, "x", null, emptyMap(), spec, ByteArray(0)) }
        }
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            source.open(DataSpec.Builder().setUri(Uri.parse(ref.toUri())).build())
        }
    }

    @Test
    fun `anything that is not a cloud ref fails instead of going online`() {
        val source = CloudResolvingDataSource(resolver) { http { 1L } }
        assertThrows(IOException::class.java) { source.open(DataSpec(Uri.parse("stash-cloud://missing"))) }
        assertThat(cloudPlaybackUri(null).toString()).isEqualTo("stash-cloud://missing")
        assertThat(cloudPlaybackUri("stash-resolve://track/1").toString()).isEqualTo("stash-cloud://missing")
        assertThat(cloudPlaybackUri(ref.toUri()).toString()).isEqualTo(ref.toUri())
    }

    @Test
    fun `a file changed since the scan fails instead of mixing old cached bytes with new ones`() {
        val source = CloudResolvingDataSource(resolver) { http { 100L } }
        val stale = ref.copy(version = "rev0") // the provider now reports rev1
        assertThrows(CloudFileChangedException::class.java) {
            source.open(DataSpec.Builder().setUri(Uri.parse(stale.toUri())).build())
        }
    }

    @Test
    fun `cache key is provider, item and version`() {
        val key = CloudResolvingDataSource.CACHE_KEY_FACTORY.buildCacheKey(DataSpec(Uri.parse(ref.toUri())))
        assertThat(key).isEqualTo("dropbox:id:song:rev1")
    }
}
