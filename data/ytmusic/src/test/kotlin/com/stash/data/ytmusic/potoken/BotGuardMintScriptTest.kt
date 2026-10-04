package com.stash.data.ytmusic.potoken

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The script a mint runs in the BotGuard page. The identifier (a video id or
 * the visitor id) reaches it only as the bytes of a `Uint8Array` literal; the
 * page answers under a request number the engine picked.
 */
class BotGuardMintScriptTest {

    private val identifiers = listOf(
        "dQw4w9WgXcQ",
        "CgtWaXNpdG9yRGF0YQ%3D%3D",
        "a\"b",
        "a'b",
        "a\\b",
        "a\nb",
        "a+b",
        "a`b",
        "a b",
    )

    @Test
    fun `the identifier enters the script only as a byte array`() {
        identifiers.forEach { id ->
            val script = mintScript(request = 42L, identifier = id, bridge = "Bridge")

            assertThat(script).doesNotContain(id)
            assertThat(script).contains("obtainPoToken(${stringToJsUint8Array(id)})")
        }
    }

    @Test
    fun `the answer comes back under the request number`() {
        val script = mintScript(request = 42L, identifier = "a\"b", bridge = "Bridge")

        assertThat(script).contains("Bridge.onMintOk(42, u8.join(\",\"))")
        assertThat(script).contains("Bridge.onMintErr(42, e + ")
        assertThat(Regex("""onMint(Ok|Err)\(""").findAll(script).count()).isEqualTo(3)
        assertThat(Regex("""onMint(Ok|Err)\(42, """).findAll(script).count()).isEqualTo(3)
    }
}
