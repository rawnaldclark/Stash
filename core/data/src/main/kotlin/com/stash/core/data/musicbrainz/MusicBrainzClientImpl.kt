package com.stash.core.data.musicbrainz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-direct MusicBrainz client. No proxy: MusicBrainz rate-limits per IP
 * with no API key, so per-device residential IPs are naturally compliant. A
 * simple on-device gate keeps requests ~1s apart (their published limit).
 */
@Singleton
class MusicBrainzClientImpl @Inject constructor(
    private val http: OkHttpClient,
) : MusicBrainzClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val rateGate = Mutex()
    @Volatile private var lastCallMs = 0L

    override suspend fun lookupUrlRels(mbid: String): JsonObject? = runCatching {
        get("artist/$mbid?inc=url-rels&fmt=json")
    }.getOrNull()

    override suspend fun searchByName(name: String): JsonObject? = runCatching {
        val query = URLEncoder.encode("artist:\"${escapeLucene(name)}\"", "UTF-8")
        val body = get("artist?query=$query&fmt=json") ?: return null
        val first = body["artists"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val score = first["score"]?.jsonPrimitive?.int ?: return null
        val type = first["type"]?.jsonPrimitive?.content
        if (score < 95 || type !in setOf("Person", "Group")) return null
        val mbid = first["id"]?.jsonPrimitive?.content ?: return null
        lookupUrlRels(mbid)
    }.getOrNull()

    /** GET [path] under the MB base with our UA + rate gate; null on non-2xx. */
    private suspend fun get(path: String): JsonObject? {
        throttle()
        val req = Request.Builder()
            .url("$BASE$path")
            .header("User-Agent", USER_AGENT)
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
            }
        }
    }

    /** Serialize + space calls ≥ MIN_INTERVAL_MS apart. */
    private suspend fun throttle() = rateGate.withLock {
        val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastCallMs)
        if (wait > 0) delay(wait)
        lastCallMs = System.currentTimeMillis()
    }

    private companion object {
        const val BASE = "https://musicbrainz.org/ws/2/"
        const val USER_AGENT = "Stash/1.0 ( https://github.com/rawnaldclark/Stash )"
        const val MIN_INTERVAL_MS = 1000L
    }
}
