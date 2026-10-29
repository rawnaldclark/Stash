package com.stash.feature.record

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebViewAssetLoader
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** The record's origin answers everything itself: what a handler serves, else a 404, never the network. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<String>()
    private val art = WebViewAssetLoader.PathHandler { path ->
        asked += path
        if (path == "v1/${"a".repeat(32)}.jpg") WebResourceResponse("image/jpeg", null, 200, "OK", emptyMap(), ByteArrayInputStream(byteArrayOf(1))) else null
    }
    private val loader = recordAssetLoader(context, art)

    private fun get(url: String) = recordResponse(loader, Uri.parse(url))

    @Test fun `a path nothing serves, or a handler refuses, is a 404 with an empty body, never null`() {
        for (url in listOf(
            "https://appassets.androidplatform.net/art/v1/${"0".repeat(32)}.jpg",
            "https://appassets.androidplatform.net/x",
            "https://appassets.androidplatform.net/databases/stash.db",
            "https://appassets.androidplatform.net/record/../databases/stash.db",
            "https://appassets.androidplatform.net/record/%2e%2e/x",
            "https://appassets.androidplatform.net/fonts/missing.woff2",
            "http://appassets.androidplatform.net/art/v1/${"a".repeat(32)}.jpg",
        )) {
            val r = get(url)
            assertThat(r).isNotNull()
            assertThat(r!!.statusCode).isEqualTo(404)
            assertThat(r.data.readBytes()).isEmpty()
        }
    }

    @Test fun `art reaches its handler by the path after art`() {
        val r = get("https://appassets.androidplatform.net/art/v1/${"a".repeat(32)}.jpg")!!
        assertThat(r.statusCode).isEqualTo(200)
        assertThat(asked).contains("v1/${"a".repeat(32)}.jpg")
    }

    @Test fun `another host is left to the WebView`() {
        assertThat(get("https://cdn-images.dzcdn.net/images/cover/x/600x600.jpg")).isNull()
    }
}
