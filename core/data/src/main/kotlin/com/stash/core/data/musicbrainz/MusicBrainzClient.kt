package com.stash.core.data.musicbrainz

import com.stash.data.ytmusic.model.SocialLink
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Client-direct MusicBrainz access. Returns the relations-bearing artist
 *  JsonObject (from `?inc=url-rels&fmt=json`) or null on failure/no-match. */
interface MusicBrainzClient {
    /** MBID lookup; null on 404/failure (caller then tries [searchByName]). */
    suspend fun lookupUrlRels(mbid: String): JsonObject?
    /** Name search that resolves an MBID (score+type gated) then fetches its
     *  url-rels; null if no confident match. */
    suspend fun searchByName(name: String): JsonObject?
}

private val HOST_KIND = linkedMapOf(
    "instagram.com" to "instagram", "twitter.com" to "x", "x.com" to "x",
    "tiktok.com" to "tiktok", "facebook.com" to "facebook",
    "youtube.com" to "youtube", "soundcloud.com" to "soundcloud", "bandcamp.com" to "bandcamp",
)
private val LUCENE = Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)""")

fun escapeLucene(name: String): String = name.replace(LUCENE) { "\\" + it.value }

/** Map an artist `relations` payload to ordered, de-duped social links.
 *  Host-based for platforms; `official homepage` type -> website. Skips ended. */
fun mapSocials(rels: JsonObject): List<SocialLink> {
    val out = LinkedHashMap<String, SocialLink>() // key = kind -> one link per platform, first wins
    val relations = rels["relations"]?.jsonArray ?: return emptyList()
    for (el in relations) {
        val rel = el.jsonObject
        if (rel["ended"]?.jsonPrimitive?.content == "true") continue
        val url = rel["url"]?.jsonObject?.get("resource")?.jsonPrimitive?.content ?: continue
        val type = rel["type"]?.jsonPrimitive?.content
        val kind = when {
            type == "official homepage" -> "website"
            else -> HOST_KIND.entries.firstOrNull { host(url).endsWith(it.key) }?.value
        } ?: continue
        // Dedupe by kind: an artist listing both twitter.com and x.com must not
        // yield two identical "x" icons. First relation for a kind wins.
        out.putIfAbsent(kind, SocialLink(kind, url))
    }
    return out.values.toList()
}

private fun host(url: String): String =
    url.substringAfter("://", url).substringBefore('/').removePrefix("www.").lowercase()
