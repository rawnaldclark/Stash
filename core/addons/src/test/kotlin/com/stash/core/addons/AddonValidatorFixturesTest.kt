package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * The spec's fixtures (stash-addon-spec `fixtures/`, copied to `src/test/resources/addon-fixtures`
 * at 341004d) through [AddonValidator]: every valid one passes, and every invalid one is rejected
 * at the JSON pointer `invalid/expect.json` names, except the two whose only fault is text over
 * its cap, which a host cuts (SPEC.md 1.1 rule 2) rather than rejects.
 *
 * `account` and `package-ref` are phone add-on shapes (phase 3): not checked here.
 */
class AddonValidatorFixturesTest {

    private val json = Json

    private val root: File = File(
        requireNotNull(javaClass.classLoader?.getResource("addon-fixtures/valid")) { "fixtures not on the test classpath" }.toURI(),
    ).parentFile

    private val validators: Map<String, (JsonElement) -> Any> = mapOf(
        "manifest" to { v -> AddonValidator.manifest(v) },
        "search-response" to { v -> AddonValidator.searchResponse(v) },
        "resolve-response" to { v -> AddonValidator.resolveResponse(v) },
        "stream-info" to { v -> AddonValidator.streamInfo(v) },
        "lyrics" to { v -> AddonValidator.lyrics(v) },
        "album" to { v -> AddonValidator.album(v) },
        "artist" to { v -> AddonValidator.artist(v) },
        "catalog-page" to { v -> AddonValidator.catalogPage(v) },
        "catalog-genres" to { v -> AddonValidator.catalogGenres(v) },
        "error" to { v -> AddonValidator.errorInfo(v) },
        "quota" to { v -> AddonValidator.quota(v) },
    )

    /** Invalid by the schema (the checker fails them), but the host's answer is to cut the text. */
    private val hostCut = setOf("artist/listeners-text-too-long.json", "catalog-genres/genre-id-too-long.json")

    /**
     * Valid by the schema, but against SPEC.md 8.2's semantic rule (which the spec's own checker,
     * `checker/src/semantics.mjs`, also applies): its `signIn.domain` "spotify.com" is not in its
     * `domains`, nor is `*.spotify.com`. A spec fixture to fix; the host is right to reject it.
     */
    private val specFixtureDefects = setOf("manifest/spotify-phone.json")

    private fun read(f: File): JsonElement = json.parseToJsonElement(f.readText())

    @Test fun `every valid fixture passes`() {
        var checked = 0
        for (dir in File(root, "valid").listFiles()!!.filter { it.isDirectory }) {
            val validate = validators[dir.name] ?: continue
            for (f in dir.listFiles()!!.filter { it.name.endsWith(".json") }) {
                if ("${dir.name}/${f.name}" in specFixtureDefects) continue
                try {
                    validate(read(f))
                } catch (e: AddonContractException) {
                    throw AssertionError("valid/${dir.name}/${f.name} rejected at '${e.path}': ${e.reason}", e)
                }
                checked++
            }
        }
        assertThat(checked).isAtLeast(37)
    }

    @Test fun `every invalid fixture is rejected at the expected path`() {
        val expect = read(File(root, "invalid/expect.json")) as JsonObject
        var checked = 0
        for ((name, pathElement) in expect) {
            if (name in hostCut) continue
            val validate = validators[name.substringBefore('/')] ?: continue
            val path = pathElement.jsonPrimitive.content
            val e = runCatching { validate(read(File(root, "invalid/$name"))) }.exceptionOrNull()
            assertWithMessage("invalid/$name was accepted").that(e).isInstanceOf(AddonContractException::class.java)
            assertWithMessage("invalid/$name: ${(e as AddonContractException).reason}").that(e.path).isEqualTo(path)
            checked++
        }
        assertThat(checked).isAtLeast(45)
    }

    @Test fun `every invalid fixture is named in expect json`() {
        val expect = (read(File(root, "invalid/expect.json")) as JsonObject).keys
        val onDisk = File(root, "invalid").listFiles()!!.filter { it.isDirectory }.flatMap { dir ->
            dir.listFiles()!!.filter { it.name.endsWith(".json") }.map { "${dir.name}/${it.name}" }
        }
        assertThat(expect).containsExactlyElementsIn(onDisk)
    }

    @Test fun `a sign-in cookie domain outside the add-on's domains is rejected`() {
        val e = runCatching { AddonValidator.manifest(read(File(root, "valid/manifest/spotify-phone.json"))) }.exceptionOrNull()
        assertThat((e as AddonContractException).path).isEqualTo("/phone/signIn/domain")
    }

    @Test fun `text over its cap is cut, not rejected`() {
        val artist = AddonValidator.artist(read(File(root, "invalid/artist/listeners-text-too-long.json")))
        assertThat(artist.listenersText!!.codePointCount(0, artist.listenersText!!.length)).isEqualTo(60)

        val genres = AddonValidator.catalogGenres(read(File(root, "invalid/catalog-genres/genre-id-too-long.json")))
        assertThat(genres.genres.single().id).hasLength(AddonValidator.GENRE_ID)
    }

    @Test fun `an error body the host can't read falls back to its status`() {
        // SPEC.md 7: a body that breaks the error shape is read by its HTTP status.
        val e = runCatching { AddonValidator.errorInfo(read(File(root, "invalid/error/bare-string.json"))) }.exceptionOrNull()
        assertThat(e).isInstanceOf(AddonContractException::class.java)
        assertThat(com.stash.addons.contract.ErrorCode.of(429, null)).isEqualTo(com.stash.addons.contract.ErrorCode.RATE_LIMITED)
        assertThat(com.stash.addons.contract.ErrorCode.of(503, "some_new_code")).isEqualTo(com.stash.addons.contract.ErrorCode.UNAVAILABLE)
    }
}
