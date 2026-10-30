package com.stash.data.soundcloud

import org.junit.Assert.*
import org.junit.Test

class SoundCloudLinksTest {
    @Test fun supportedForms() {
        mapOf("artist/song" to SoundCloudLink.Kind.TRACK, "artist/sets/set" to SoundCloudLink.Kind.SET,
            "artist/likes" to SoundCloudLink.Kind.LIKES, "artist" to SoundCloudLink.Kind.PROFILE).forEach { (path, kind) ->
            assertEquals(kind, SoundCloudLinks.parse("https://soundcloud.com/$path?si=tracking")?.kind)
        }
        assertEquals(SoundCloudLink.Kind.SHORT, SoundCloudLinks.parse("https://on.soundcloud.com/abcdef")?.kind)
    }
    @Test fun refusesUntrustedLocations() {
        listOf("http://soundcloud.com/a/b", "https://soundcloud.com.evil.test/a/b", "https://user:secret@soundcloud.com/a/b",
            "https://soundcloud.com:8443/a/b", "https://soundcloud.com/search", "https://on.soundcloud.com/a/b").forEach {
            assertNull(it, SoundCloudLinks.parse(it))
        }
    }
}
