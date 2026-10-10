package com.stash.core.data.weblink

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** JSON for the sync wire: unknown fields ignored (sync-v1 §1), absent optionals left out rather than sent as null. */
internal val SyncJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * OkHttp client for the stash-sync Worker ([WebLinkConfig.baseUrl]): the phone talks to it directly, the browser only through
 * the player. The base client's interceptors stay (its logging redacts `Authorization`), its timeouts don't: the pairing
 * long-polls hold a request up to 25 s. A plain-http base (a debug build pointed at `wrangler dev`) also allows cleartext,
 * which the debug network security config permits for that one host only.
 */
@Singleton
class SyncApiClient @Inject constructor(okHttpClient: OkHttpClient, private val config: WebLinkConfig) : SyncApi {
    private val http: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(LONG_POLL_READ_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(LONG_POLL_READ_S + 10, TimeUnit.SECONDS)
        .apply { if (config.cleartext) connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)) }
        .build()

    override suspend fun pairLabel(pairId: String) =
        call("GET", "/v1/pair/$pairId/label", PairLabelInfo.serializer()).notNull()

    override suspend fun pairAnswer(pairId: String, body: PairAnswerBody) =
        unit("POST", "/v1/pair/$pairId/answer", body = SyncJson.encodeToString(PairAnswerBody.serializer(), body))

    override suspend fun pairReply(pairId: String, auth: DeviceAuth) =
        call("GET", "/v1/pair/$pairId/reply", PairReplyInfo.serializer(), auth = auth, emptyOk = true)

    override suspend fun createSpace(auth: DeviceAuth, body: CreateSpaceBody) =
        call("POST", "/v1/spaces", SpaceCreated.serializer(), auth, SyncJson.encodeToString(CreateSpaceBody.serializer(), body))
            .notNull()

    override suspend fun join(auth: DeviceAuth, spaceId: String, body: JoinBody) =
        call("POST", "/v1/spaces/$spaceId/devices", Joined.serializer(), auth, SyncJson.encodeToString(JoinBody.serializer(), body))
            .notNull()

    override suspend fun space(auth: DeviceAuth, spaceId: String) =
        call("GET", "/v1/spaces/$spaceId", SpaceInfo.serializer(), auth).notNull()

    override suspend fun putMyLabel(auth: DeviceAuth, spaceId: String, labelCt: SyncEnvelope) =
        unit("PUT", "/v1/spaces/$spaceId/devices/me/label", auth, SyncJson.encodeToString(LabelBody.serializer(), LabelBody(labelCt)))

    override suspend fun removeDevice(auth: DeviceAuth, spaceId: String, deviceId: String) =
        unit("DELETE", "/v1/spaces/$spaceId/devices/$deviceId", auth)

    override suspend fun deleteSpace(auth: DeviceAuth, spaceId: String) =
        unit("DELETE", "/v1/spaces/$spaceId", auth)

    override suspend fun key(auth: DeviceAuth, spaceId: String, epoch: Int) =
        call("GET", "/v1/spaces/$spaceId/key/$epoch", KeyEnvelopeInfo.serializer(), auth).notNull()

    override suspend fun rotate(auth: DeviceAuth, spaceId: String, body: RotateBody) =
        call("POST", "/v1/spaces/$spaceId/rotate", Rotated.serializer(), auth, SyncJson.encodeToString(RotateBody.serializer(), body))
            .notNull()

    override suspend fun config(auth: DeviceAuth, spaceId: String) =
        call("GET", "/v1/spaces/$spaceId/slots/config", ConfigSlot.serializer(), auth, emptyOk = true)

    override suspend fun putSlot(auth: DeviceAuth, spaceId: String, slot: String, env: SyncEnvelope) =
        call("PUT", "/v1/spaces/$spaceId/slots/$slot", SlotWritten.serializer(), auth, SyncJson.encodeToString(SlotBody.serializer(), SlotBody(env)))
            .notNull()

    override suspend fun nowSlots(auth: DeviceAuth, spaceId: String) =
        call("GET", "/v1/spaces/$spaceId/slots/now", NowSlots.serializer(), auth).notNull()

    override suspend fun queueSlot(auth: DeviceAuth, spaceId: String, deviceId: String) =
        call("GET", "/v1/spaces/$spaceId/slots/queue/$deviceId", StoredSlot.serializer(), auth).notNull()

    // -------------------------------------------------------------------------------------------- plumbing

    private suspend fun unit(method: String, path: String, auth: DeviceAuth? = null, body: String? = null): SyncResult<Unit> =
        when (val r = exchange(method, path, auth, body)) {
            is Raw.Answer -> if (r.code in 200..299) SyncResult.Ok(Unit) else readError(r.code, r.body, r.retryAfter)
            is Raw.Failed -> SyncResult.Unreachable(r.cause)
        }

    /** A JSON answer read with [serializer]; with [emptyOk], 204 is `Ok(null)` (a long-poll still pending, a slot not set). */
    private suspend fun <T : Any> call(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        auth: DeviceAuth? = null,
        body: String? = null,
        emptyOk: Boolean = false,
    ): SyncResult<T?> = when (val r = exchange(method, path, auth, body)) {
        is Raw.Failed -> SyncResult.Unreachable(r.cause)
        is Raw.Answer -> when {
            r.code == 204 && emptyOk -> SyncResult.Ok(null)
            r.code in 200..299 -> try {
                SyncResult.Ok(SyncJson.decodeFromString(serializer, r.body))
            } catch (e: IllegalArgumentException) {
                // A 2xx we can't read: a captive portal or a proxy answered, not the Worker.
                SyncResult.Unreachable("unreadable answer")
            }
            else -> readError(r.code, r.body, r.retryAfter)
        }
    }

    private sealed interface Raw {
        class Answer(val code: Int, val body: String, val retryAfter: String?) : Raw
        class Failed(val cause: String?) : Raw
    }

    private suspend fun exchange(method: String, path: String, auth: DeviceAuth?, body: String?): Raw {
        val req = Request.Builder()
            .url(config.baseUrl + path)
            .header("Accept", "application/json")
            .apply { if (auth != null) header("Authorization", auth.header) }
            .method(method, body?.toRequestBody(JSON) ?: if (method == "POST" || method == "PUT") EMPTY else null)
            .build()
        return try {
            http.newCall(req).awaitResponse { code, text, headers -> Raw.Answer(code, text, headers["Retry-After"]) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Raw.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    private companion object {
        /** The Worker holds a long-poll up to 25 s; leave room for the network on top. */
        const val LONG_POLL_READ_S = 40L
        val JSON = "application/json".toMediaType()
        val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}

/** Reads `{ "error": { "code", "message" }, … }`; anything else is code [SyncErrorCode.HTTP]. */
internal fun readError(status: Int, text: String, retryAfter: String?): SyncResult.Error {
    val root = try {
        SyncJson.parseToJsonElement(text).jsonObject
    } catch (e: IllegalArgumentException) {
        null
    }
    val err = root?.get("error") as? JsonObject
    val code = (err?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: SyncErrorCode.HTTP
    val message = (err?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return SyncResult.Error(status, code, message, root, retryAfter?.trim()?.toLongOrNull())
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> SyncResult<T?>.notNull(): SyncResult<T> = when (this) {
    is SyncResult.Ok -> if (value == null) SyncResult.Unreachable("empty answer") else this as SyncResult<T>
    is SyncResult.Error -> this
    is SyncResult.Unreachable -> this
}

/**
 * Runs the call on OkHttp's thread and suspends until it answers (as the share client's `await`), passing the headers too
 * (`Retry-After`). Cancelling the caller cancels the call at once, also mid long-poll.
 */
private suspend fun <T> Call.awaitResponse(read: (code: Int, body: String, headers: Headers) -> T): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) =
                cont.resumeWith(runCatching { response.use { read(it.code, it.body?.string().orEmpty(), it.headers) } })
        })
    }
