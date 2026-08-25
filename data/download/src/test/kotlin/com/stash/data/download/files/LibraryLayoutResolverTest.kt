package com.stash.data.download.files

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.LibraryLayout
import org.junit.Test

/**
 * Pure-JVM coverage of the folder-structure resolver behind #198/#104.
 * Every path-writer (downloads, library move, reorganize, lyrics sidecars)
 * and every path-reader (SAF index lookups) shares these semantics, so the
 * assertions here pin down the exact on-disk contract.
 */
class LibraryLayoutResolverTest {

    // ── ARTIST_ALBUM (historical default) ────────────────────────────────

    @Test fun `artist album keeps historical nested layout`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.ARTIST_ALBUM, "Nina Simone", "Pastel Blues", "Sinnerman", null,
        )
        assertThat(loc.segments).containsExactly("nina-simone", "pastel-blues").inOrder()
        assertThat(loc.baseName).isEqualTo("sinnerman")
        assertThat(loc.dirKey).isEqualTo("nina-simone/pastel-blues")
    }

    @Test fun `artist album blank album falls back to singles`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.ARTIST_ALBUM, "Drake", "", "Hotline Bling", null,
        )
        assertThat(loc.segments).containsExactly("drake", "singles").inOrder()
    }

    @Test fun `artist album filename has no artist prefix`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.ARTIST_ALBUM, "Artist", "Album", "Title", null,
        )
        assertThat(loc.baseName).isEqualTo("title")
    }

    // ── SINGLE_FOLDER (#104) ─────────────────────────────────────────────

    @Test fun `single folder puts track directly in root with artist prefix`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.SINGLE_FOLDER, "Kanye West", "DONDA", "Off The Grid", null,
        )
        assertThat(loc.segments).isEmpty()
        assertThat(loc.dirKey).isEmpty()
        assertThat(loc.baseName).isEqualTo("kanye-west-off-the-grid")
    }

    @Test fun `single folder artist prefix prevents same-title collisions`() {
        val a = LibraryLayoutResolver.resolve(LibraryLayout.SINGLE_FOLDER, "Artist A", "X", "Intro", null)
        val b = LibraryLayoutResolver.resolve(LibraryLayout.SINGLE_FOLDER, "Artist B", "Y", "Intro", null)
        assertThat(a.baseName).isNotEqualTo(b.baseName)
    }

    @Test fun `playlist name is ignored in single folder mode`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.SINGLE_FOLDER, "A", "B", "C", "Road Trip",
        )
        assertThat(loc.segments).isEmpty()
        assertThat(loc.baseName).isEqualTo("a-c")
    }

    // ── PLAYLIST (#198) ──────────────────────────────────────────────────

    @Test fun `playlist files under playlist folder with artist prefix`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.PLAYLIST, "Daft Punk", "Discovery", "Aerodynamic", "Road Trip 2026",
        )
        assertThat(loc.segments).containsExactly("road-trip-2026")
        assertThat(loc.baseName).isEqualTo("daft-punk-aerodynamic")
    }

    @Test fun `playlist without playlist name falls back to artist album`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.PLAYLIST, "Drake", "Views", "Weston Road Flows", null,
        )
        assertThat(loc.segments).containsExactly("drake", "views").inOrder()
        // Fallback keeps the historical bare-title filename so pre-existing
        // nested downloads stay addressable without a rename.
        assertThat(loc.baseName).isEqualTo("weston-road-flows")
    }

    @Test fun `playlist with emoji-only name falls back to artist album`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.PLAYLIST, "A", "B", "C", "♪♪♪",
        )
        assertThat(loc.segments).containsExactly("a", "b").inOrder()
        assertThat(loc.baseName).isEqualTo("c")
    }

    @Test fun `playlist with blank name falls back to artist album`() {
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.PLAYLIST, "A", "B", "C", "   ",
        )
        assertThat(loc.segments).containsExactly("a", "b").inOrder()
    }

    // ── Slug parity with FileOrganizerSlugs ──────────────────────────────

    @Test fun `unicode names slug consistently across layouts`() {
        val nested = LibraryLayoutResolver.resolve(
            LibraryLayout.ARTIST_ALBUM, "Кино — Группа крови", "Альбом", "Песня", null,
        )
        val flat = LibraryLayoutResolver.resolve(
            LibraryLayout.SINGLE_FOLDER, "Кино — Группа крови", "Альбом", "Песня", null,
        )
        assertThat(nested.segments.first())
            .isEqualTo(FileOrganizerSlugs.slugify("Кино — Группа крови"))
        assertThat(flat.baseName).startsWith(nested.segments.first())
    }

    @Test fun `long inputs are truncated by the shared slug rules`() {
        val longTitle = "x".repeat(200)
        val loc = LibraryLayoutResolver.resolve(
            LibraryLayout.ARTIST_ALBUM, "a", "b", longTitle, null,
        )
        assertThat(loc.baseName.length).isAtMost(60)
    }

    // ── lookupCandidates (reconciliation / adoption probing) ─────────────

    @Test fun `candidates probe current layout first then legacy then flat`() {
        val candidates = LibraryLayoutResolver.lookupCandidates(
            LibraryLayout.PLAYLIST, "Artist", "Album", "Title", "My Playlist",
            knownFormats = listOf("flac"),
        )
        assertThat(candidates.map { it.dirKey }).containsExactly(
            "my-playlist",      // current PLAYLIST location
            "artist/album",     // legacy nested location
            "",                 // flat root
        ).inOrder()
        assertThat(candidates[0].candidateFileNames).containsExactly("artist-title.flac")
        assertThat(candidates[1].candidateFileNames).containsExactly("title.flac")
        assertThat(candidates[2].candidateFileNames).containsExactly("artist-title.flac")
    }

    @Test fun `candidates for artist album skip duplicate legacy entry but keep flat fallback`() {
        val candidates = LibraryLayoutResolver.lookupCandidates(
            LibraryLayout.ARTIST_ALBUM, "Artist", "Album", "Title", null,
            knownFormats = emptyList(),
        )
        assertThat(candidates.map { it.dirKey }).containsExactly("artist/album", "").inOrder()
        // Empty knownFormats probes the bare base names only.
        assertThat(candidates[0].candidateFileNames).containsExactly("title")
    }

    @Test fun `candidates generate one filename per known format when format unknown`() {
        val candidates = LibraryLayoutResolver.lookupCandidates(
            LibraryLayout.SINGLE_FOLDER, "A", "B", "T", null,
            knownFormats = listOf("opus", "m4a"),
        )
        // Primary (flat root) comes first; the legacy nested entry follows.
        assertThat(candidates.first().dirKey).isEmpty()
        assertThat(candidates.first().candidateFileNames)
            .containsExactly("a-t.opus", "a-t.m4a")
            .inOrder()
    }
}
