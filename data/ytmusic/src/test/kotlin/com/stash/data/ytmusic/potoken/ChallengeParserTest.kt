package com.stash.data.ytmusic.potoken

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.Base64

/**
 * The BotGuard challenge round-trip is pure string work: YouTube's
 * `api/jnn/v1/Create` answer (plain or "scrambled": base64 of every byte
 * minus 97) becomes the JSON object `runBotGuard()` expects, `GenerateIT`
 * yields an integrity token as bytes plus a lifetime, and minted tokens
 * travel as comma-separated bytes that must become URL-safe base64.
 */
class ChallengeParserTest {

    private val innerChallenge =
        """["msg-1",["var bg = 1;"],["https://www.google.com/js/bg/x.js"],"hash",""" +
            """"PROGRAM_B64","botguardGlobal",null,"blob"]"""

    @Test
    fun `a plain challenge becomes the runBotGuard input`() {
        val parsed = Json.parseToJsonElement(parseCreateChallenge("[$innerChallenge]")).jsonObject

        assertThat(parsed["program"]!!.jsonPrimitive.content).isEqualTo("PROGRAM_B64")
        assertThat(parsed["globalName"]!!.jsonPrimitive.content).isEqualTo("botguardGlobal")
        val interpreter = parsed["interpreterJavascript"]!!.jsonObject
        assertThat(interpreter["privateDoNotAccessOrElseSafeScriptWrappedValue"]!!.jsonPrimitive.content)
            .isEqualTo("var bg = 1;")
        assertThat(interpreter["privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"]!!.jsonPrimitive.content)
            .isEqualTo("https://www.google.com/js/bg/x.js")
    }

    @Test
    fun `a scrambled challenge is descrambled first`() {
        // Scrambling = every byte minus 97, base64-encoded, delivered as the SECOND element.
        val scrambled = Base64.getEncoder().encodeToString(
            innerChallenge.toByteArray(Charsets.UTF_8).map { (it - 97).toByte() }.toByteArray(),
        )

        val parsed = Json.parseToJsonElement(parseCreateChallenge("""[null,"$scrambled"]""")).jsonObject

        assertThat(parsed["program"]!!.jsonPrimitive.content).isEqualTo("PROGRAM_B64")
        assertThat(parsed["globalName"]!!.jsonPrimitive.content).isEqualTo("botguardGlobal")
    }

    @Test
    fun `the integrity token arrives as bytes with its lifetime`() {
        val (tokenU8, lifetime) = parseIntegrityToken("""["QUJD",43200]""")

        assertThat(tokenU8).isEqualTo("new Uint8Array([65,66,67])")
        assertThat(lifetime).isEqualTo(43200L)
    }

    @Test
    fun `minted bytes become url-safe base64 and identifiers become byte arrays`() {
        assertThat(commaSeparatedBytesToBase64("251,255,254")).isEqualTo("-__-")
        assertThat(stringToJsUint8Array("ab")).isEqualTo("new Uint8Array([97,98])")
    }
}
