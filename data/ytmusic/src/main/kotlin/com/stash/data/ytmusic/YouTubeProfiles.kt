package com.stash.data.ytmusic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One profile of a signed-in Google account on YouTube Music (#664): the account's own ([pageId]
 * null) or one of its Brand Accounts, each its own library.
 *
 * @property authUser The index of the Google account that owns it in the browser session (from its
 *   sign-in link); a Brand page id is only valid with its owner's index.
 */
data class YouTubeProfile(
    val name: String,
    val handle: String?,
    val photoUrl: String?,
    val pageId: String?,
    val authUser: Int,
    val selected: Boolean,
)

/**
 * Reads `account/accounts_list`. Its shape differs by client and over time (a multi-page menu of
 * `accountItem`s, or a channel switcher of `accountItemRenderer`s), so every account item anywhere
 * in the response is collected, and each field is read in both its `runs` and `simpleText` forms.
 * A Brand Account carries `pageIdToken.pageId` among its `selectActiveIdentityEndpoint` tokens; the
 * account's own profile has none.
 */
object YouTubeProfiles {
    fun parse(response: JsonObject): List<YouTubeProfile> {
        val items = mutableListOf<JsonObject>()
        collect(response, items)
        return items.mapNotNull(::profile).distinctBy { it.pageId ?: "own:${it.authUser}" }
    }

    private fun collect(element: JsonElement, into: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if ((key == "accountItem" || key == "accountItemRenderer") && value is JsonObject) into += value
                else collect(value, into)
            }
            is JsonArray -> element.forEach { collect(it, into) }
            else -> Unit
        }
    }

    private fun profile(item: JsonObject): YouTubeProfile? {
        val name = text(item["accountName"]) ?: return null
        val tokens = (item.obj("serviceEndpoint")?.obj("selectActiveIdentityEndpoint")?.get("supportedTokens") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }.orEmpty()
        val pageId = tokens.firstNotNullOfOrNull { it.obj("pageIdToken")?.str("pageId") }
            ?.takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) }
        val signinUrl = tokens.firstNotNullOfOrNull { it.obj("accountSigninToken")?.str("signinUrl") }
        val authUser = signinUrl?.let { AUTHUSER.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val photo = (item.obj("accountPhoto")?.get("thumbnails") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.str("url") }?.lastOrNull()
        return YouTubeProfile(
            name = name,
            handle = text(item["channelHandle"]) ?: text(item["accountByline"]),
            photoUrl = photo,
            pageId = pageId,
            authUser = authUser,
            selected = (item["isSelected"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private val AUTHUSER = Regex("""[?&]authuser=(\d+)""")

    /** `{"simpleText": …}` or `{"runs": [{"text": …}, …]}`. */
    private fun text(element: JsonElement?): String? {
        val o = element as? JsonObject ?: return null
        o.str("simpleText")?.let { return it.trim().ifEmpty { null } }
        return (o["runs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("text") }
            ?.joinToString("")?.trim()?.ifEmpty { null }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
