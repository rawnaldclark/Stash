/*
 * Ported from YumaPlayer (MuwMix) / ArchiveTune (Rukamori), GPL-3.0.
 * Original source: moe.rukamori.archivetune.utils.potoken.BotGuardTokenGenerator
 */
package com.stash.data.ytmusic.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Mints proof-of-origin tokens by running YouTube's BotGuard challenge in a
 * headless [WebView]: `api/jnn/v1/Create` hands out the program, the page
 * (`assets/po_token.html`) runs it, `GenerateIT` turns the result into an
 * integrity token, and the minter that token unlocks stamps one token per
 * identifier — the visitor id (session token, `pot=` on stream URLs) and
 * each video id (player token, `serviceIntegrityDimensions`).
 *
 * One engine serves a visitor session until the integrity token expires
 * (~50 min) or the session changes; player tokens are cached per video
 * (LRU 200). [preWarm] boots it ahead of the first play; [release] drops the
 * WebView (~50 MB) while the app is in the background. Every failure is a
 * `null` — the caller proceeds without a token and the tail probe decides.
 */
@Singleton
class BotGuardPoTokenMinter @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
) : PoTokenMinter {

    private val httpClient = okHttpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    private val mutex = Mutex()
    @Volatile private var permanentlyBroken = false
    private var engine: BotGuardEngine? = null
    private var engineSessionId: String? = null
    private var cachedSessionToken: String? = null
    private var engineReady = false

    private val playerTokenCache: LinkedHashMap<String, String> =
        object : LinkedHashMap<String, String>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > PLAYER_TOKEN_CACHE_SIZE
        }

    override suspend fun preWarm(sessionId: String) {
        if (permanentlyBroken || sessionId.isBlank()) return
        try {
            withTimeout(COLD_START_TIMEOUT_MS) { getOrCreateEngine(sessionId, forceNewEngine = false) }
            Log.i(TAG, "pre-warm complete")
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "pre-warm timed out after ${COLD_START_TIMEOUT_MS}ms")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.w(TAG, "pre-warm failed (non-fatal): ${e.message}")
        }
    }

    override suspend fun mint(videoId: String, sessionId: String): PoTokenPair? {
        if (permanentlyBroken || sessionId.isBlank()) return null
        val cached = mutex.withLock {
            if (!isEngineReadyForSession(sessionId)) return@withLock null
            val player = playerTokenCache[videoId] ?: return@withLock null
            val session = cachedSessionToken ?: return@withLock null
            PoTokenPair(playerToken = player, sessionToken = session)
        }
        if (cached != null) return cached

        val cold = mutex.withLock { !isEngineReadyForSession(sessionId) }
        val timeout = if (cold) COLD_START_TIMEOUT_MS else WARM_TIMEOUT_MS
        return try {
            withTimeout(timeout) {
                val result = mintInternal(videoId, sessionId, forceNewEngine = false)
                mutex.withLock { playerTokenCache[videoId] = result.playerToken }
                result
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "mint timed out after ${timeout}ms — proceeding without a token")
            mutex.withLock { destroyEngine() }
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: BrokenWebViewException) {
            Log.e(TAG, "WebView cannot run BotGuard; minting off for this process: ${e.message}")
            permanentlyBroken = true
            null
        } catch (e: Exception) {
            Log.w(TAG, "mint failed: ${e.message}")
            null
        }
    }

    override suspend fun release() {
        mutex.withLock {
            if (engine != null) {
                Log.d(TAG, "releasing engine (app backgrounded)")
                destroyEngine()
            }
        }
    }

    private suspend fun mintInternal(videoId: String, sessionId: String, forceNewEngine: Boolean): PoTokenPair {
        val (eng, sessionToken, wasNew) = getOrCreateEngine(sessionId, forceNewEngine)
        val playerToken = try {
            eng.mint(videoId)
        } catch (e: Throwable) {
            if (e is CancellationException || wasNew) throw e
            Log.w(TAG, "mint failed, retrying with a fresh engine: ${e.message}")
            return mintInternal(videoId, sessionId, forceNewEngine = true)
        }
        return PoTokenPair(playerToken = playerToken, sessionToken = sessionToken)
    }

    private suspend fun getOrCreateEngine(sessionId: String, forceNewEngine: Boolean): Triple<BotGuardEngine, String, Boolean> =
        mutex.withLock {
            val needsNew = forceNewEngine || !isEngineReadyForSession(sessionId)
            if (needsNew) {
                withContext(Dispatchers.Main) { engine?.close() }
                engine = null
                engineSessionId = null
                cachedSessionToken = null
                engineReady = false
                playerTokenCache.clear()
                val newEngine = BotGuardEngine.create(context, httpClient)
                val newSessionToken = try {
                    newEngine.mint(sessionId)
                } catch (error: Throwable) {
                    withContext(NonCancellable + Dispatchers.Main) { newEngine.close() }
                    throw error
                }
                engine = newEngine
                engineSessionId = sessionId
                cachedSessionToken = newSessionToken
                engineReady = true
            }
            Triple(requireNotNull(engine), requireNotNull(cachedSessionToken), needsNew)
        }

    private fun isEngineReadyForSession(sessionId: String): Boolean =
        engineReady && engineSessionId == sessionId && cachedSessionToken != null && engine?.isExpired == false

    private suspend fun destroyEngine() {
        withContext(Dispatchers.Main) { engine?.close() }
        engine = null
        engineSessionId = null
        cachedSessionToken = null
        engineReady = false
    }

    /** One headless WebView running BotGuard; bridge methods are called from the page. */
    private class BotGuardEngine private constructor(
        private val webView: WebView,
        private val httpClient: OkHttpClient,
        private val readySignal: CancellableContinuation<BotGuardEngine>,
    ) {
        private val scope = MainScope()
        private val closed = AtomicBoolean(false)
        private val readyCompleted = AtomicBoolean(false)
        private val pendingMints = Collections.synchronizedMap(HashMap<String, CancellableContinuation<String>>())
        private lateinit var expiry: Instant
        val isExpired: Boolean get() = Instant.now().isAfter(expiry)

        fun startBootstrap() {
            scope.launch(exceptionHandler) {
                val html = withContext(Dispatchers.IO) {
                    webView.context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                }
                val patched = html.replaceFirst("</script>", "\n$JS_BRIDGE.onPageLoaded()</script>")
                webView.loadDataWithBaseURL("https://www.youtube.com", patched, "text/html", "utf-8", null)
            }
        }

        @JavascriptInterface
        fun onPageLoaded() {
            postToBotGuard(CREATE_URL, "[ \"$REQUEST_KEY\" ]") { body ->
                val challengeJson = parseCreateChallenge(body)
                webView.evaluateJavascript(
                    """
                    try {
                        var data = $challengeJson;
                        runBotGuard(data).then(function(r) {
                            this.webPoSignalOutput = r.webPoSignalOutput;
                            $JS_BRIDGE.onBotGuardReady(r.botguardResponse);
                        }, function(e) {
                            $JS_BRIDGE.onFatalError(e + "\n" + e.stack);
                        });
                    } catch(e) { $JS_BRIDGE.onFatalError(e + "\n" + e.stack); }
                    """.trimIndent(),
                    null,
                )
            }
        }

        @JavascriptInterface
        fun onBotGuardReady(botguardResponse: String) {
            postToBotGuard(GENERATE_IT_URL, "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]") { body ->
                try {
                    val (tokenU8, lifetimeSec) = parseIntegrityToken(body)
                    expiry = Instant.now().plusSeconds(lifetimeSec).minus(10, ChronoUnit.MINUTES)
                    webView.evaluateJavascript(
                        """
                        try {
                            this.integrityToken = $tokenU8;
                            createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() {
                                $JS_BRIDGE.onMinterReady();
                            }).catch(function(e) {
                                $JS_BRIDGE.onFatalError(e + "\n" + (e.stack || ''));
                            });
                        } catch(e) { $JS_BRIDGE.onFatalError(e + "\n" + e.stack); }
                        """.trimIndent(),
                        null,
                    )
                } catch (e: Exception) {
                    signalError(PoTokenException("GenerateIT parse failed: ${e.message}", e))
                }
            }
        }

        @JavascriptInterface
        fun onMinterReady() {
            Log.d(TAG, "minter ready")
            resumeReady(this)
        }

        @JavascriptInterface
        fun onFatalError(error: String) {
            Log.e(TAG, "fatal JS error: ${error.take(300)}")
            signalError(classifyJsError(error))
        }

        suspend fun mint(identifier: String): String = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                pendingMints[identifier] = cont
                cont.invokeOnCancellation {
                    synchronized(pendingMints) {
                        if (pendingMints[identifier] === cont) pendingMints.remove(identifier)
                    }
                }
                val u8Arg = stringToJsUint8Array(identifier)
                webView.evaluateJavascript(
                    """
                    try {
                        obtainPoToken($u8Arg).then(function(u8) {
                            $JS_BRIDGE.onMintOk("$identifier", u8.join(","));
                        }).catch(function(e) {
                            $JS_BRIDGE.onMintErr("$identifier", e + "\n" + (e.stack || ''));
                        });
                    } catch(e) { $JS_BRIDGE.onMintErr("$identifier", e + "\n" + e.stack); }
                    """.trimIndent(),
                    null,
                )
            }
        }

        @JavascriptInterface
        fun onMintOk(identifier: String, csvBytes: String) {
            val base64 = commaSeparatedBytesToBase64(csvBytes)
            pendingMints.remove(identifier)?.let { if (it.isActive) it.resume(base64) }
        }

        @JavascriptInterface
        fun onMintErr(identifier: String, error: String) {
            Log.w(TAG, "mint failed for $identifier: ${error.take(200)}")
            pendingMints.remove(identifier)?.let { if (it.isActive) it.resumeWithException(classifyJsError(error)) }
        }

        private val exceptionHandler = CoroutineExceptionHandler { _, t -> signalError(t) }

        private fun signalError(error: Throwable) {
            close()
            resumeReadyWithException(error)
        }

        private fun resumeReady(engine: BotGuardEngine) {
            if (!readyCompleted.compareAndSet(false, true)) return
            if (readySignal.isActive) runCatching { readySignal.resume(engine) }
        }

        private fun resumeReadyWithException(error: Throwable) {
            if (!readyCompleted.compareAndSet(false, true)) return
            if (readySignal.isActive) runCatching { readySignal.resumeWithException(error) }
        }

        private fun postToBotGuard(url: String, jsonBody: String, onSuccess: (String) -> Unit) {
            scope.launch(exceptionHandler) {
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody())
                    .headers(
                        mapOf(
                            "User-Agent" to WV_USER_AGENT,
                            "Accept" to "application/json",
                            "Content-Type" to "application/json+protobuf",
                            "x-goog-api-key" to API_KEY,
                            "x-user-agent" to "grpc-web-javascript/0.1",
                        ).toHeaders(),
                    )
                    .build()
                val (code, body) = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
                }
                if (code != 200) signalError(PoTokenException("BotGuard HTTP $code from $url")) else onSuccess(body)
            }
        }

        @MainThread
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            scope.cancel()
            val continuations = synchronized(pendingMints) { pendingMints.values.toList().also { pendingMints.clear() } }
            continuations.forEach { it.cancel(CancellationException("BotGuard engine closed")) }
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.removeAllViews()
            webView.destroy()
        }

        companion object {
            @SuppressLint("SetJavaScriptEnabled")
            suspend fun create(context: Context, httpClient: OkHttpClient): BotGuardEngine =
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        lateinit var engine: BotGuardEngine
                        val wv = WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.userAgentString = WV_USER_AGENT
                            settings.blockNetworkLoads = true
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                    if (m.message().contains("Uncaught")) {
                                        engine.signalError(
                                            BrokenWebViewException("\"${m.message()}\", ${m.sourceId()} (${m.lineNumber()})"),
                                        )
                                    }
                                    return super.onConsoleMessage(m)
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()})")
                                    engine.signalError(PoTokenException("WebView renderer process gone"))
                                    return true
                                }
                            }
                        }
                        engine = BotGuardEngine(wv, httpClient, cont)
                        cont.invokeOnCancellation { wv.post(engine::close) }
                        wv.addJavascriptInterface(engine, JS_BRIDGE)
                        engine.startBootstrap()
                    }
                }
        }
    }

    private companion object {
        const val TAG = "BotGuardPoToken"
        const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        const val WV_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        const val JS_BRIDGE = "BotGuardBridge"
        /** First mint: WebView boot + BotGuard bootstrap. */
        const val COLD_START_TIMEOUT_MS = 45_000L
        /** A warm engine only has to mint. */
        const val WARM_TIMEOUT_MS = 5_000L
        const val PLAYER_TOKEN_CACHE_SIZE = 200
    }
}
