package com.stash.core.media.streaming

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.CloudProvider
import com.stash.core.model.CloudRef
import com.stash.data.cloud.auth.ProviderAuth
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.CloudEntry
import com.stash.data.cloud.provider.CloudFileBlockedException
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

    // ── Google Drive: no pre-signed link, the token rides in a request header ──

    private class DriveProvider : CloudStorageProvider {
        var calls = 0
        override val provider = CloudProvider.GOOGLE_DRIVE
        override val isConfigured = true
        override val auth: ProviderAuth get() = error("unused")
        override val linkTtlMs = 60_000L
        override suspend fun browse(accountId: Long, folderId: String?) = emptyList<CloudEntry>()
        override suspend fun scanPage(accountId: Long, root: ScanRoot, cursor: String?): ScanPage = error("unused")
        override suspend fun describeFolder(accountId: Long, folderId: String): CloudEntry? = null
        override suspend fun link(accountId: Long, itemId: String, priority: RequestPriority): CloudLink {
            calls++
            return CloudLink(
                "https://www.googleapis.com/drive/v3/files/$itemId?alt=media", 9_000, "md5x", System.currentTimeMillis(),
                headers = mapOf("Authorization" to "Bearer secret-token-$calls"),
            )
        }
    }

    private val drive = DriveProvider()
    private val driveResolver = CloudLinkResolver(mapOf(CloudProvider.GOOGLE_DRIVE to drive))
    private val driveRef = CloudRef(CloudProvider.GOOGLE_DRIVE, 4, "1AbCdEfGhIjK", "md5x")

    @Test
    fun `a drive link is opened with its bearer header through the authorized source, never in the uri`() {
        val opened = mutableListOf<DataSpec>()
        var plainUsed = false
        val source = CloudResolvingDataSource(
            driveResolver,
            authorizedHttpFactory = { http { opened += it; C.LENGTH_UNSET.toLong() } },
        ) { plainUsed = true; http { 1L } }

        val spec = DataSpec.Builder().setUri(Uri.parse(driveRef.toUri())).setPosition(1_000)
            .setHttpRequestHeaders(mapOf("X-Existing" to "1")).build()
        val length = source.open(spec)

        assertThat(plainUsed).isFalse()
        val sent = opened.single()
        assertThat(sent.httpRequestHeaders).containsEntry("Authorization", "Bearer secret-token-1")
        assertThat(sent.httpRequestHeaders).containsEntry("X-Existing", "1")
        assertThat(sent.uri.toString()).doesNotContain("secret")
        assertThat(sent.toString()).doesNotContain("secret")
        assertThat(source.uri.toString()).doesNotContain("secret")
        // No Content-Length from Drive: the length comes from the metadata size.
        assertThat(length).isEqualTo(8_000L)
        assertThat(CloudResolvingDataSource.CACHE_KEY_FACTORY.buildCacheKey(spec)).isEqualTo("gdrive:1AbCdEfGhIjK:md5x")
    }

    @Test
    fun `a dropbox link stays on the plain source with no extra headers`() {
        val opened = mutableListOf<DataSpec>()
        var authorizedUsed = false
        val source = CloudResolvingDataSource(resolver, authorizedHttpFactory = { authorizedUsed = true; http { 1L } }) {
            http { opened += it; 100L }
        }
        source.open(DataSpec.Builder().setUri(Uri.parse(ref.toUri())).build())
        assertThat(authorizedUsed).isFalse()
        assertThat(opened.single().httpRequestHeaders).isEmpty()
    }

    @Test
    fun `a rejected drive token is resolved again once`() {
        val opened = mutableListOf<DataSpec>()
        val source = CloudResolvingDataSource(driveResolver) {
            http { spec ->
                opened += spec
                if (spec.httpRequestHeaders["Authorization"] == "Bearer secret-token-1") {
                    throw HttpDataSource.InvalidResponseCodeException(401, "Unauthorized", null, emptyMap(), spec, ByteArray(0))
                }
                500L
            }
        }
        source.open(DataSpec.Builder().setUri(Uri.parse(driveRef.toUri())).build())
        assertThat(drive.calls).isEqualTo(2)
        assertThat(opened.last().httpRequestHeaders["Authorization"]).isEqualTo("Bearer secret-token-2")
    }

    @Test
    fun `a file google flagged as abusive says so and is not retried`() {
        val body = """{"error":{"code":403,"errors":[{"reason":"cannotDownloadAbusiveFile"}]}}""".toByteArray()
        val source = CloudResolvingDataSource(driveResolver) {
            http { spec -> throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), spec, body) }
        }
        val e = assertThrows(CloudFileBlockedException::class.java) {
            source.open(DataSpec.Builder().setUri(Uri.parse(driveRef.toUri())).build())
        }
        assertThat(e.message).isEqualTo("Google blocked this file")
        assertThat(drive.calls).isEqualTo(1)
    }

    @Test
    fun `the player never retries a blocked or changed cloud file, but still retries a dropped connection`() {
        val policy = CloudLoadErrorPolicy()
        fun retryDelay(e: IOException) = policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(1, DataSpec(Uri.parse(driveRef.toUri())), 0),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                e,
                /* errorCount = */ 2,
            ),
        )

        assertThat(retryDelay(CloudFileBlockedException("Google blocked this file"))).isEqualTo(C.TIME_UNSET)
        assertThat(retryDelay(CloudFileChangedException())).isEqualTo(C.TIME_UNSET)
        assertThat(retryDelay(IOException("wrapped", CloudFileBlockedException("x")))).isEqualTo(C.TIME_UNSET)
        assertThat(retryDelay(java.net.SocketTimeoutException())).isNotEqualTo(C.TIME_UNSET)
    }
}
