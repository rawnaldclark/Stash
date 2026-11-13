package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The base rules (add-ons plan phase 4 §2.6, review N7) on the URL rule: an add-on the user added
 * (`publicOnly`) may name only public DNS hosts, in its link and in every URL it hands back.
 */
class AddonValidatorBaseRuleTest {

    private val release = AddonValidator.Options(allowLocalHttp = false, publicOnly = true)
    private val debug = AddonValidator.Options(allowLocalHttp = true, publicOnly = true)
    private val defaults = AddonValidator.Options(allowLocalHttp = false, publicOnly = false)

    private val local = listOf(
        "https://192.168.1.10/manifest.json",
        "https://10.0.0.1/x.flac",
        "https://127.0.0.1/manifest.json",
        "https://127.1/manifest.json",
        "https://0x7f.0.0.1/manifest.json",
        "https://2130706433/manifest.json",
        "https://localhost/manifest.json",
        "https://router.localhost/manifest.json",
        "https://nas/manifest.json",
        "https://nas.local/manifest.json",
        "https://box.internal/manifest.json",
        "https://hub.home.arpa/manifest.json",
        "https://printer.lan/manifest.json",
        "https://localhost./manifest.json",
    )

    @Test fun `local addresses and names are refused for an add-on the user added`() {
        for (u in local) {
            assertWithMessage(u).that(AddonValidator.isAllowedUrl(u, release)).isFalse()
            assertWithMessage(u).that(AddonValidator.isLocalAddressUrl(u, release)).isTrue()
        }
    }

    @Test fun `IPv6 literals never pass, with or without the base rules`() {
        for (u in listOf("https://[fd00::1]/manifest.json", "https://[::1]/manifest.json", "https://[2001:db8::1]/m.json")) {
            assertWithMessage(u).that(AddonValidator.isAllowedUrl(u, release)).isFalse()
            assertWithMessage(u).that(AddonValidator.isAllowedUrl(u, defaults)).isFalse()
        }
    }

    @Test fun `public names pass`() {
        for (u in listOf(
            "https://addon.example.org/tok/manifest.json",
            "https://stash-addon-tidal.someone.workers.dev/v1/abc/manifest.json",
            "https://cdn.example.com:8443/a.flac?sig=1",
            "https://Example.COM/manifest.json",
            "https://1password.com/x",
        )) {
            assertWithMessage(u).that(AddonValidator.isAllowedUrl(u, release)).isTrue()
            assertWithMessage(u).that(AddonValidator.isLocalAddressUrl(u, release)).isFalse()
        }
    }

    @Test fun `debug builds keep http and https on loopback only`() {
        for (u in listOf("http://127.0.0.1:8787/manifest.json", "https://127.0.0.1:8787/manifest.json", "http://localhost:8787/x", "http://[::1]:8787/x")) {
            assertWithMessage(u).that(AddonValidator.isAllowedUrl(u, debug)).isTrue()
            assertWithMessage(u).that(AddonValidator.isAllowedUrl(u, release)).isFalse()
        }
        // The exception is loopback only: the home network stays refused in a debug build too.
        assertThat(AddonValidator.isAllowedUrl("https://192.168.1.10/manifest.json", debug)).isFalse()
        assertThat(AddonValidator.isAllowedUrl("http://192.168.1.10/manifest.json", debug)).isFalse()
    }

    @Test fun `the signed config's defaults keep today's rule`() {
        // The owner's own choices (phase 4 §2.6 "Not affected"): https with any host.
        assertThat(AddonValidator.isAllowedUrl("https://10.0.0.1/x.flac", defaults)).isTrue()
        assertThat(AddonValidator.isLocalAddressUrl("https://10.0.0.1/x.flac", defaults)).isFalse()
    }

    @Test fun `an unofficial add-on's stream on a private literal is refused, and the reason says so`() {
        val body = Json.parseToJsonElement(
            """{"url":"https://10.0.0.1/x.flac","manifest":"none","tier":"lossless","cors":false,"codec":"flac"}""",
        )
        val e = assertThrows(AddonContractException::class.java) { AddonValidator.streamInfo(body, release) }
        assertThat(e.path).isEqualTo("/url")
        assertThat(e.reason).isEqualTo(AddonEclipse.PRIVATE_ADDRESS)
        // A default's answer may (its CDN is the owner's choice).
        assertThat(AddonValidator.streamInfo(body, defaults).url).isEqualTo("https://10.0.0.1/x.flac")
    }

    @Test fun `artwork on a private address takes the whole v1 answer down`() {
        val body = Json.parseToJsonElement(
            """{"tracks":[{"id":"1","title":"T","artist":"A","artwork":[{"url":"https://192.168.0.1/a.jpg"}]}]}""",
        )
        assertThrows(AddonContractException::class.java) { AddonValidator.searchResponse(body, release) }
    }

    @Test fun `host check spells`() {
        assertThat(AddonValidator.isPublicHost("example.org")).isTrue()
        assertThat(AddonValidator.isPublicHost("a-b.c-d.example")).isTrue()
        assertThat(AddonValidator.isPublicHost("example")).isFalse()
        assertThat(AddonValidator.isPublicHost("1.2.3.4")).isFalse()
        assertThat(AddonValidator.isPublicHost("example.123")).isFalse()
        assertThat(AddonValidator.isPublicHost("a..b")).isFalse()
        assertThat(AddonValidator.isPublicHost("my.local")).isFalse()
        assertThat(AddonValidator.isPublicHost("home.arpa")).isFalse()
    }
}
