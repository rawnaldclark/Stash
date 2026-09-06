/*
 * Ported from YumaPlayer (MuwMix) / ArchiveTune (Rukamori), GPL-3.0.
 * Original source: moe.rukamori.archivetune.utils.potoken.ChallengeParser
 */
package com.stash.data.ytmusic.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

private val json = Json { ignoreUnknownKeys = true }

/**
 * Turns the raw `api/jnn/v1/Create` answer into the JSON object the page's
 * `runBotGuard()` expects.
 *
 * The answer is a JSON array whose challenge is either the first element
 * (a nested array) or, "scrambled", the second element: base64 of every
 * byte minus 97. The challenge array holds, by index: messageId,
 * interpreterJavascript, interpreterTrustedResourceUrl, interpreterHash,
 * program (base64), globalName, unknown, clientExperimentsStateBlob.
 */
fun parseCreateChallenge(rawResponse: String): String {
    val outer = json.parseToJsonElement(rawResponse).jsonArray
    val challenge =
        if (outer.size > 1 && outer[1].jsonPrimitive.isString) {
            json.parseToJsonElement(descramble(outer[1].jsonPrimitive.content)).jsonArray
        } else {
            outer[0].jsonArray
        }
    val program = challenge[4].jsonPrimitive.content
    val globalName = challenge[5].jsonPrimitive.content
    val interpreterJs = challenge[1].takeIf { it !is JsonNull }?.jsonArray?.firstOrNull { it.jsonPrimitive.isString }
    val interpreterUrl = challenge[2].takeIf { it !is JsonNull }?.jsonArray?.firstOrNull { it.jsonPrimitive.isString }
    return json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "program" to JsonPrimitive(program),
                "globalName" to JsonPrimitive(globalName),
                "interpreterJavascript" to JsonObject(
                    mapOf(
                        "privateDoNotAccessOrElseSafeScriptWrappedValue" to (interpreterJs ?: JsonNull),
                        "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to (interpreterUrl ?: JsonNull),
                    ),
                ),
            ),
        ),
    )
}

/**
 * Parses the raw `api/jnn/v1/GenerateIT` answer into the integrity token as
 * a JavaScript `Uint8Array(...)` literal and its lifetime in seconds.
 */
fun parseIntegrityToken(rawResponse: String): Pair<String, Long> {
    val arr = json.parseToJsonElement(rawResponse).jsonArray
    return base64ToJsUint8Array(arr[0].jsonPrimitive.content) to arr[1].jsonPrimitive.long
}

/** A plain identifier (video id, visitor id) as a JavaScript `Uint8Array(...)` literal. */
fun stringToJsUint8Array(identifier: String): String {
    val bytes = identifier.toByteArray(Charsets.UTF_8)
    return "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"
}

/** `Uint8Array.toString()` output (comma-separated bytes) → YouTube's URL-safe base64. */
fun commaSeparatedBytesToBase64(commaBytes: String): String =
    commaBytes
        .split(",")
        .map { it.trim().toInt().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace('+', '-')
        .replace('/', '_')

private fun descramble(base64Payload: String): String =
    base64ToByteArray(base64Payload).map { (it + 97).toByte() }.toByteArray().decodeToString()

private fun base64ToJsUint8Array(base64: String): String {
    val bytes = base64ToByteArray(base64)
    return "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"
}

private fun base64ToByteArray(base64: String): ByteArray {
    val normalised = base64.replace('-', '+').replace('_', '/').replace('.', '=')
    return (normalised.decodeBase64() ?: throw PoTokenException("Cannot decode base64: ${base64.take(40)}…")).toByteArray()
}

/** A recoverable minting failure (timeout, network, script error): callers proceed without a token. */
class PoTokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The system WebView cannot run BotGuard at all (JavaScript SyntaxErrors); minting stays off for the process. */
class BrokenWebViewException(message: String) : Exception(message)

internal fun classifyJsError(error: String): Exception =
    if (error.contains("SyntaxError")) BrokenWebViewException(error) else PoTokenException(error)
