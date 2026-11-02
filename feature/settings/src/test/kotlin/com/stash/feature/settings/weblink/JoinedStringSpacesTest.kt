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
 */
class JoinedStringSpacesTest {

    private val res = File("src/main/res")

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
}
