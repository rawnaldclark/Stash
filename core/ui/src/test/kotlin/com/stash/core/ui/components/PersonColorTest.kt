package com.stash.core.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PersonColorTest {
    @Test fun `a person keeps one colour, and a room of ten spreads across the palette`() {
        assertThat(personColor("M7TAX7JN")).isEqualTo(personColor("M7TAX7JN"))
        val ids = listOf("M7TAX7JN", "Q2WERT5Y", "H8JK3LZX", "P4NB6VCM", "R9TY2UIO", "K5LM8NBV", "Z3XC7VBN", "A6SD9FGH", "W2ER4TYU", "E8RT6YUI")
        assertThat(ids.map(::personColor).toSet().size).isAtLeast(5)
    }
}
