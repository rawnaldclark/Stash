package com.stash.core.model.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareLinksTest {
    private val base = ShareConfig.BASE_URL

    @Test fun `mix url and parse round-trip`() {
        assertThat(ShareLinks.mixUrl("Kx7Qa2pL")).isEqualTo("$base/m/Kx7Qa2pL")
        assertThat(ShareLinks.parse("$base/m/Kx7Qa2pL")).isEqualTo(ShareLinks.Parsed.Mix("Kx7Qa2pL"))
    }

    @Test fun `track url carries every field and parses back`() {
        val t = SharedTrack("Song & Dance", "Aphex Twin", "Drukqs", 125_000, "GBBPW0100025", "abc", "xyz")
        val url = ShareLinks.trackUrl(t)
        assertThat(url).startsWith("$base/t?t=Song+%26+Dance&a=Aphex+Twin")
        assertThat(ShareLinks.parse(url)).isEqualTo(ShareLinks.Parsed.Track(t))
    }

    @Test fun `legacy stash track links still parse`() {
        val legacy = "stash://track?t=Avril+14th&a=Aphex+Twin&s=https%3A%2F%2Fopen.spotify.com%2Ftrack%2Fabc&y=xyz"
        assertThat(ShareLinks.parse(legacy)).isEqualTo(
            ShareLinks.Parsed.Track(SharedTrack("Avril 14th", "Aphex Twin", spotifyId = "abc", youtubeId = "xyz")),
        )
    }

    @Test fun `foreign hosts, bad ids and missing fields are rejected`() {
        assertThat(ShareLinks.parse("https://evil.example/m/Kx7Qa2pL")).isNull()
        assertThat(ShareLinks.parse("$base/m/short")).isNull()
        assertThat(ShareLinks.parse("$base/t?a=OnlyArtist")).isNull()
        assertThat(ShareLinks.parse(null)).isNull()
        assertThat(ShareLinks.parse("not a url")).isNull()
        assertThat(ShareLinks.parse("$base/t?t=%zz&a=A")).isNull()
    }

    @Test fun `hostile or sloppy links are bounded, normalised or rejected`() {
        val long = "x".repeat(5000)
        val t = (ShareLinks.parse("$base/t?t=$long&a=A") as ShareLinks.Parsed.Track).track
        assertThat(t.title).hasLength(500)
        assertThat(ShareLinks.parse("HTTPS://STASH-SHARE.RAWNALDCLARK.WORKERS.DEV/m/Kx7Qa2pL/")).isEqualTo(ShareLinks.Parsed.Mix("Kx7Qa2pL"))
        assertThat(ShareLinks.parse("stash:track?t=a&a=b")).isNull()
        assertThat(ShareLinks.parse("$base/t?t=+&a=A")).isNull()
        val round = SharedTrack("C++ é", "Artist", durationMs = null)
        assertThat(ShareLinks.parse(ShareLinks.trackUrl(round))).isEqualTo(ShareLinks.Parsed.Track(round))
        assertThat((ShareLinks.parse("$base/t?t=T&a=A&d=-5") as ShareLinks.Parsed.Track).track.durationMs).isNull()
    }

    @Test fun `cover allowlist matches hosts exactly or as a subdomain, https only`() {
        assertThat(ShareConfig.isAllowedCover("https://i.scdn.co/image/a")).isTrue()
        assertThat(ShareConfig.isAllowedCover("https://x.i.ytimg.com/vi/b.jpg")).isTrue()
        assertThat(ShareConfig.isAllowedCover("https://evil.example/a.jpg")).isFalse()
        assertThat(ShareConfig.isAllowedCover("https://i.scdn.co.evil.example/a")).isFalse()
        assertThat(ShareConfig.isAllowedCover("http://i.scdn.co/a")).isFalse()
        assertThat(ShareConfig.isAllowedCover("not a url")).isFalse()
        assertThat(ShareConfig.isAllowedCover(null)).isFalse()
    }

    @Test fun `links are built on stashfm-app and the old workers-dev links still open`() {
        assertThat(ShareConfig.BASE_URL).isEqualTo("https://stashfm.app")
        assertThat(ShareLinks.mixUrl("Kx7Qa2pL")).isEqualTo("https://stashfm.app/m/Kx7Qa2pL")
        assertThat(ShareLinks.trackUrl(SharedTrack("T", "A"))).isEqualTo("https://stashfm.app/t?t=T&a=A")
        val old = "https://stash-share.rawnaldclark.workers.dev"
        assertThat(ShareLinks.parse("$old/m/Kx7Qa2pL")).isEqualTo(ShareLinks.Parsed.Mix("Kx7Qa2pL"))
        assertThat(ShareLinks.parse("$old/l/K7QA2PXM")).isEqualTo(ShareLinks.Parsed.Room("K7QA2PXM"))
        assertThat(ShareLinks.parse("$old/t?t=T&a=A")).isEqualTo(ShareLinks.Parsed.Track(SharedTrack("T", "A")))
        assertThat(ShareLinks.parse("https://www.stashfm.app/m/Kx7Qa2pL")).isNull() // the apex only, like the manifest
    }

    @Test fun `api calls try stashfm-app first, then the old host, and links open from both`() {
        assertThat(ShareConfig.API_BASE_URLS)
            .containsExactly("https://stashfm.app", "https://stash-share.rawnaldclark.workers.dev").inOrder()
        assertThat(ShareConfig.HOSTS).containsExactly("stashfm.app", "stash-share.rawnaldclark.workers.dev")
    }

    @Test fun `short song link builds and parses on either host`() {
        assertThat(ShareLinks.trackShortUrl("Ab3xY9qk")).isEqualTo("https://stashfm.app/t/Ab3xY9qk")
        assertThat(ShareLinks.parse(ShareLinks.trackShortUrl("Ab3xY9qk"))).isEqualTo(ShareLinks.Parsed.TrackRef("Ab3xY9qk"))
        assertThat(ShareLinks.parse("https://stash-share.rawnaldclark.workers.dev/t/Ab3xY9qk"))
            .isEqualTo(ShareLinks.Parsed.TrackRef("Ab3xY9qk"))
        assertThat(ShareLinks.parse("HTTPS://STASHFM.APP/t/Ab3xY9qk/")).isEqualTo(ShareLinks.Parsed.TrackRef("Ab3xY9qk"))
        // A share sheet may tack a query on; the id is still the path.
        assertThat(ShareLinks.parse("$base/t/Ab3xY9qk?si=x")).isEqualTo(ShareLinks.Parsed.TrackRef("Ab3xY9qk"))
    }

    @Test fun `short song links with a bad id or a foreign host are rejected, never thrown`() {
        assertThat(ShareLinks.parse("$base/t/Ab3xY9q")).isNull() // 7
        assertThat(ShareLinks.parse("$base/t/Ab3xY9qkZ")).isNull() // 9
        assertThat(ShareLinks.parse("$base/t/Ab3x-9qk")).isNull()
        assertThat(ShareLinks.parse("$base/t/Ab3xY9qk/extra")).isNull()
        assertThat(ShareLinks.parse("$base/t/%zz")).isNull()
        assertThat(ShareLinks.parse("$base/t/")).isNull()
        assertThat(ShareLinks.parse("http://stashfm.app/t/Ab3xY9qk")).isNull()
        assertThat(ShareLinks.parse("https://evil.example/t/Ab3xY9qk")).isNull()
        assertThat(ShareLinks.parse("stash://track/Ab3xY9qk")).isNull()
    }

    @Test fun `the long song link and the legacy scheme are unchanged on the new host`() {
        val t = SharedTrack("Teardrop", "Massive Attack", "Mezzanine", 330_000, "GBAAA9800174", "sp1", "yt1")
        assertThat(ShareLinks.parse(ShareLinks.trackUrl(t))).isEqualTo(ShareLinks.Parsed.Track(t))
        assertThat(ShareLinks.parse("stash://track?t=Teardrop&a=Massive+Attack&y=yt1"))
            .isEqualTo(ShareLinks.Parsed.Track(SharedTrack("Teardrop", "Massive Attack", youtubeId = "yt1")))
    }

    @Test fun `deezer's image cdn is a cover host`() {
        assertThat(ShareConfig.isAllowedCover("https://cdn-images.dzcdn.net/images/cover/abc/1000x1000-000000-80-0-0.jpg")).isTrue()
        assertThat(ShareConfig.isAllowedCover("https://e-cdns-images.dzcdn.net/images/cover/abc/1000x1000-000000-80-0-0.jpg")).isTrue()
    }

    @Test fun `room url and parse round-trip, lower case and a trailing slash accepted`() {
        assertThat(ShareLinks.roomUrl("K7QA2PXM")).isEqualTo("$base/l/K7QA2PXM")
        assertThat(ShareLinks.parse("$base/l/K7QA2PXM")).isEqualTo(ShareLinks.Parsed.Room("K7QA2PXM"))
        assertThat(ShareLinks.parse("$base/l/k7qa2pxm/")).isEqualTo(ShareLinks.Parsed.Room("K7QA2PXM"))
    }

    @Test fun `room codes that are the wrong length, use ambiguous characters or come from another host are rejected`() {
        assertThat(ShareLinks.parse("$base/l/K7QA2PX")).isNull()
        assertThat(ShareLinks.parse("$base/l/K7QA2P")).isNull() // the old 6-character length
        assertThat(ShareLinks.parse("$base/l/K7QA2PXO")).isNull() // no O in the alphabet
        assertThat(ShareLinks.parse("$base/l/K7QA2PX1")).isNull() // no 1 either
        assertThat(ShareLinks.parse("https://evil.example/l/K7QA2PXM")).isNull()
    }
}
