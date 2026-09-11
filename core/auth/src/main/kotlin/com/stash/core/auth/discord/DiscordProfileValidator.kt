package com.stash.core.auth.discord

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Validates a scraped Discord account token and returns the profile JSON,
 * via the same oauth2/authorize GET trick the RPC connect flow uses (avoids
 * hitting /users/@me directly — see issue #16). Used by
 * TokenManagerImpl.connectDiscordWithToken; the ongoing presence-posting
 * client (core:data's DiscordRpcClient) is a separate concern that only
 * needs the CLIENT_ID constant, duplicated there rather than shared, to
 * avoid pulling this whole file across the module boundary for one constant.
 */
object DiscordProfileValidator {
    private const val CLIENT_ID = "934292861724270632"

    suspend fun validateAndFetchProfile(userToken: String): JsonObject {
        val client = OkHttpClient()
        val req = Request.Builder().url("https://discord.com/api/v9/oauth2/authorize?client_id=$CLIENT_ID")
        DiscordHeaders.apply(req, token = userToken, bearer = false)
        // Closed on the rejected-token path too, or each bad paste leaks a connection.
        return client.newCall(req.build()).await().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Invalid Discord token")
            val bodyString = res.body?.string() ?: throw IllegalStateException("Empty response body")
            val body = Json.decodeFromString<JsonObject>(bodyString)
            body["user"] as? JsonObject ?: throw IllegalStateException("No user in authorize response")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
    }
}