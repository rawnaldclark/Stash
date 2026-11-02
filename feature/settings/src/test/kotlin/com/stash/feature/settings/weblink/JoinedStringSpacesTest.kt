package com.stash.feature.settings.weblink

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The Settings strings glued onto another (the link code, the mirror notes) keep the space at their
 * edge in every locale. Android trims an unquoted value's leading and trailing spaces, which ran the
 * phone's link prompt into the code: "ask you to check075 781".
 *
 * Reads the resource files as Android does for this: a value in double quotes is kept as it is,
 * any other is trimmed. (The unit test's working directory is the module.)
 *
 * And no string in any module, in any locale, has a space at its edge outside quotes: Android drops
 * it, so it's either a space someone meant (quote it) or a stray one (drop it). This module's
 * build.gradle.kts makes every module's strings inputs of its unit tests, so a change to any of
 * them reruns this.
 */
class JoinedStringSpacesTest {

    private val res = File("src/main/res")

    /** The repo's root: the first folder up from the module with the Gradle settings in it. */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Every module's strings.xml, in every locale: the modules settings.gradle.kts includes. */
    private val allStrings: List<File> by lazy {
        val settings = File(root, "settings.gradle.kts").readText()
        Regex("""include\(([^)]*)\)""").findAll(settings)
            .flatMap { call -> Regex(""""(:[\w:-]+)"""").findAll(call.groupValues[1]) }
            .map { File(root, it.groupValues[1].removePrefix(":").replace(':', '/') + "/src/main/res") }
            .flatMap { res -> res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty().asSequence() }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .toList()
    }

    /** Followed by the code (or another string): a trailing space. */
    private val beforeSomething = listOf(
        "settings_web_link_confirm_body_before_code",
        "settings_web_link_waiting_computer",
        "settings_web_link_code_label",
    )

    /** Appended to a direction or a song count: " · …". */
    private val afterSomething = listOf(
        "settings_mirror_likes_waiting_share",
        "settings_mirror_likes_choose_combine",
        "settings_mirror_plays_subtitle_note",
        "settings_mirror_playlist_readonly_web",
        "settings_mirror_playlist_shared_mix",
        "settings_picker_shared_mix",
    )

    private val locales: List<File> = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
        .orEmpty()
        .map { File(it, "strings.xml") }
        .filter { it.exists() }

    /** The value as Android reads it: verbatim inside quotes, trimmed otherwise. */
    private fun asRead(raw: String): String =
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw.substring(1, raw.length - 1) else raw.trim()

    private fun stringValue(xml: String, name: String): String? =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    /**
     * The value has a space (or tab) at its edge outside quotes, on the tag's own line: Android
     * drops it. A run of whitespace with a newline in it is the XML's layout, dropped as meant.
     */
    private fun dropsEdgeSpace(raw: String): Boolean {
        val lead = raw.takeWhile { it.isWhitespace() }
        val trail = raw.takeLastWhile { it.isWhitespace() }
        return (lead.isNotEmpty() && '\n' !in lead) || (raw.isNotBlank() && trail.isNotEmpty() && '\n' !in trail)
    }

    @Test fun `the strings before the link code end in a space`() {
        var checked = 0
        for (file in locales) {
            val xml = file.readText()
            for (name in beforeSomething) {
                val raw = stringValue(xml, name) ?: continue
                assertWithMessage("$name in ${file.parentFile.name}").that(asRead(raw)).endsWith(" ")
                checked++
            }
        }
        // English, French and Korean have all three.
        assertThat(checked).isAtLeast(9)
    }

    @Test fun `the mirror notes start with a space`() {
        var checked = 0
        for (file in locales) {
            val xml = file.readText()
            for (name in afterSomething) {
                val raw = stringValue(xml, name) ?: continue
                assertWithMessage("$name in ${file.parentFile.name}").that(asRead(raw)).startsWith(" ")
                checked++
            }
            val plural = Regex("""<plurals name="settings_mirror_playlists_chosen">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1) ?: continue
            Regex("""<item quantity="(\w+)">(.*?)</item>""").findAll(plural).forEach { item ->
                assertWithMessage("settings_mirror_playlists_chosen/${item.groupValues[1]} in ${file.parentFile.name}")
                    .that(asRead(item.groupValues[2])).startsWith(" ")
                checked++
            }
        }
        assertThat(checked).isAtLeast(18)
    }

    @Test fun `no string in any module has a space at its edge that Android would drop`() {
        val dropped = allStrings.flatMap { file ->
            val xml = file.readText()
            STRING_VALUE.findAll(xml).filter { dropsEdgeSpace(it.groupValues[3]) }.map { value ->
                val line = xml.substring(0, value.range.first).count { it == '\n' } + 1
                "${file.relativeTo(root).invariantSeparatorsPath}:$line \"${value.groupValues[3]}\""
            }.toList()
        }
        assertWithMessage("Android drops these edge spaces: quote the value to keep one (\" · Shared mix\"), or drop it")
            .that(dropped).isEmpty()
    }

    @Test fun `the edge space check reads every module's strings`() {
        // module/src/main/res/values-xx/strings.xml: the module is five folders up.
        val modules = allStrings.map { it.parentFile.parentFile.parentFile.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath }
        assertThat(modules.toSet()).containsAtLeast("app", "core/media", "feature/settings", "feature/nowplaying", "feature/search")
    }

    @Test fun `an edge space is one Android drops, not a quoted one or the XML's own layout`() {
        assertThat(dropsEdgeSpace(" · Shared mix")).isTrue()
        assertThat(dropsEdgeSpace("Connect ")).isTrue()
        assertThat(dropsEdgeSpace("\t· 3 chosen")).isTrue()
        assertThat(dropsEdgeSpace("\" · Shared mix\"")).isFalse()
        assertThat(dropsEdgeSpace("Connect")).isFalse()
        assertThat(dropsEdgeSpace("\n        A long one, laid out on its own lines.\n    ")).isFalse()
        assertThat(dropsEdgeSpace("")).isFalse()
    }

    private companion object {
        /** A <string> or a plural's or array's <item>, not <string-array> and not self-closed: its raw value is group 3. */
        val STRING_VALUE = Regex("""<(string|item)(\s[^>]*?)?(?<!/)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    }
}
