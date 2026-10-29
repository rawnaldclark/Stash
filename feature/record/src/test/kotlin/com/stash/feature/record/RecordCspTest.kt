package com.stash.feature.record

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** The bundled page's policy lets it show the library's covers, which come from its own origin (`/art/`). */
class RecordCspTest {
    @Test fun `the bundled embed's img-src allows 'self' and no other host than the lookups'`() {
        val html = File("src/main/assets/record/embed.html").readText().replace("&#39;", "'")
        val csp = Regex("""Content-Security-Policy"\s+content="([^"]+)"""").find(html)!!.groupValues[1]
        val img = csp.split(';').map(String::trim).single { it.startsWith("img-src ") }.split(' ').drop(1)
        assertThat(img).contains("'self'")
        assertThat(img.filter { it.startsWith("https://") })
            .containsExactly("https://cdn-images.dzcdn.net", "https://e-cdns-images.dzcdn.net", "https://*.mzstatic.com")
        assertThat(csp).contains("connect-src 'none'")
    }
}
