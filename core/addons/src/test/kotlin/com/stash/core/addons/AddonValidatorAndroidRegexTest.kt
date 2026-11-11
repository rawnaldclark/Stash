package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, which refuses some JVM-only syntax: `(?U)` in the validator's
 * initializer made every add-on call fail on a phone (seen on the Pixel 5, add-ons phase 2) while
 * every JVM test passed. The JVM can't run ICU, so this pins the behaviour and bans the syntax.
 */
class AddonValidatorAndroidRegexTest {

    @Test fun `unicode white space still collapses without the (?U) flag`() {
        val nbsp = ' '
        val em = ' '
        assertThat(AddonValidator.cleanText("a${nbsp}${nbsp}b${em}c \t d", 100)).isEqualTo("a b c d")
    }

    @Test fun `no main source uses regex syntax Android's ICU refuses`() {
        val banned = listOf("(?U)", "(?X)", "\\p{IsWhite_Space}", "\\h", "\\R")
        val src = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertThat(src).isNotEmpty()
        val hits = src.flatMap { f -> banned.filter { it in f.readText() }.map { "${f.name}: $it" } }
        assertThat(hits).isEmpty()
    }
}
