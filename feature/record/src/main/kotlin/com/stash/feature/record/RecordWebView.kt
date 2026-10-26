package com.stash.feature.record

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
import com.stash.feature.record.bridge.RecordPort

/** The record's address: the bundled build, served by [recordAssetLoader], in the app's mode (bridge protocol v1). */
internal const val RECORD_URL = "https://appassets.androidplatform.net/record/embed.html?embed=stash-app"

/** The only origin the record's page has, and the only one that gets [BRIDGE]. */
internal const val RECORD_ORIGIN = "https://appassets.androidplatform.net"

/** The bridge object's name in the page (`window.StashHost`). */
internal const val BRIDGE = "StashHost"

/**
 * The bundled record over https on the asset loader's own domain, never `file://`: `/record/` is `assets/record/` (the
 * fork's build), `/fonts/` is `assets/record-fonts/` (the faces theme.css loads from the site root). Nothing else of the
 * APK's assets is reachable, and a path that climbs (`..`) is refused.
 */
internal fun recordAssetLoader(context: Context): WebViewAssetLoader {
    val assets = WebViewAssetLoader.AssetsPathHandler(context)
    fun under(dir: String) = WebViewAssetLoader.PathHandler { path ->
        if (path.split('/').any { it == ".." || it == "." }) null else assets.handle("$dir/$path")
    }
    return WebViewAssetLoader.Builder()
        .addPathHandler("/record/", under("record"))
        .addPathHandler("/fonts/", under("record-fonts"))
        .build()
}

/** True when this phone's WebView can carry the bridge (Android System WebView 74 or newer). */
internal fun bridgeSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

/** A reply proxy as the host's port. */
private class ProxyPort(private val proxy: JavaScriptReplyProxy) : RecordPort {
    override fun post(message: String) = proxy.postMessage(message)
}

/**
 * The record's WebView: JavaScript and DOM storage on (the record keeps its own settings there), file and content access off,
 * mixed content never, no extra windows. A link that leaves the record opens in the browser. Every message from the page's
 * main frame goes to [onMessage] with the page's reply proxy.
 *
 * Made on a [android.content.MutableContextWrapper] by [RecordViewModel], which keeps it across configuration changes and
 * points the wrapper at whichever Activity shows it.
 *
 * [onGone]: the page's renderer died; this view can't be used again ([release] then only destroys it).
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class RecordWebView(
    context: Context,
    onMessage: (String, RecordPort) -> Unit,
    onResponsive: (Boolean) -> Unit,
    private val onGone: () -> Unit,
) : WebView(context) {
    private var rendererGone = false
    private val assetLoader = recordAssetLoader(context)

    init {
        setBackgroundColor(RECORD_GROUND_ARGB)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        // Before the first load, so StashHost is there before the page's scripts run
        WebViewCompat.addWebMessageListener(this, BRIDGE, setOf(RECORD_ORIGIN)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame || sourceOrigin.toString() != RECORD_ORIGIN) return@addWebMessageListener
            message.data?.let { onMessage(it, ProxyPort(replyProxy)) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(
                this,
                object : WebViewRenderProcessClient() {
                    override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) = onResponsive(false)

                    override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) = onResponsive(true)
                },
            )
        }
        webViewClient = Client()
        loadUrl(RECORD_URL)
    }

    /** Asks the page's script for a sign of life: [onAlive] runs (main thread) once it answers, never if it is stuck. */
    fun probe(onAlive: () -> Unit) {
        if (!rendererGone) evaluateJavascript("0") { onAlive() }
    }

    /** Stops the page and frees the view, for good. */
    fun release() {
        if (!rendererGone) {
            WebViewCompat.removeWebMessageListener(this, BRIDGE)
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
        }
        destroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "https" && url.host == Uri.parse(RECORD_ORIGIN).host) return false
            if (request.isForMainFrame && url.scheme in EXTERNAL_SCHEMES) openOutside(view.context, url)
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "the record's renderer is gone (crashed: ${detail.didCrash()})")
            rendererGone = true
            onGone()
            return true
        }
    }

    private companion object {
        const val TAG = "YourRecord"
        val EXTERNAL_SCHEMES = setOf("https", "http", "mailto")

        fun openOutside(context: Context, url: Uri) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no app opens ${url.scheme} links")
            }
        }
    }
}
