package com.stash.core.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A newer Stash release with an APK to install: what the update sheet offers. */
data class AvailableUpdate(
    /** The release's tag ("v0.9.122"). */
    val tag: String,
    /** "0.9.122", for people. */
    val version: String,
    /** The release's APK (its `browser_download_url`). */
    val apkUrl: String,
    val sizeBytes: Long,
    /** The release notes, as plain text. */
    val notes: String,
)

/** Reading a GitHub release, and when to ask. Pure, so it can be tested off-device. */
object AppUpdates {

    /** Asked again this long after Later (owner decision 10-29: "ask again tomorrow"). */
    const val ASK_AGAIN_MS = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The update in a `releases/latest` response: its tag, and its `.apk` asset (the release workflow uploads one,
     * `Stash.apk`). Null without a tag or an APK, or for a draft or pre-release. [allowFile]: a debug build's test release
     * may name a `file:` APK on the phone.
     */
    fun parseRelease(body: String, allowFile: Boolean = false): AvailableUpdate? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (root.bool("draft") || root.bool("prerelease")) return null
        val tag = root.str("tag_name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val apk = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { a -> a.str("name")?.endsWith(".apk", ignoreCase = true) == true && a.str("browser_download_url")?.let { u -> u.startsWith("https://") || (allowFile && u.startsWith("file:")) } == true }
            ?: return null
        return AvailableUpdate(
            tag = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            apkUrl = apk.str("browser_download_url")!!,
            sizeBytes = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            notes = plainNotes(root.str("body").orEmpty()),
        )
    }

    /**
     * Whether to show the sheet for [tag] now: never shown for it, or Later was more than [ASK_AGAIN_MS] ago. A newer
     * release than the one put off asks at once.
     */
    fun shouldPrompt(tag: String, laterTag: String?, laterAt: Long, now: Long): Boolean =
        tag != laterTag || now - laterAt >= ASK_AGAIN_MS || now < laterAt

    /**
     * Release notes for reading in the app: Markdown marks taken off ("## What's new" → "What's new", "**Paste**" →
     * "Paste", "- item" → "• item", `[text](url)` → "text"), blank runs folded, HTML comments dropped.
     */
    fun plainNotes(md: String): String = md
        .replace("\r\n", "\n")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .joinToString("\n") { line ->
            line.trimEnd()
                .replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^\\s*[-*]\\s+"), "• ")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                .replace("**", "")
                .replace("`", "")
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** "171 MB". */
    fun sizeLabel(bytes: Long): String = if (bytes <= 0) "" else "${(bytes + 500_000) / 1_000_000} MB"

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull == "true"
}
