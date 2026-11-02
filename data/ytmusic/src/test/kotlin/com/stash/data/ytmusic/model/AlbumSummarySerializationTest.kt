package com.stash.data.ytmusic.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumSummarySerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `old cached json without source decodes to YOUTUBE`() {
        val legacy = """{"id":"MPRE1","title":"Loveless","artist":"MBV","thumbnailUrl":null,"year":"1991"}"""
        val decoded = json.decodeFromString<AlbumSummary>(legacy)
        assertEquals(AlbumSource.YOUTUBE, decoded.source)
    }

    @Test fun `qobuz source round-trips`() {
        val a = AlbumSummary("123", "Loveless", "MBV", null, "1991", AlbumSource.QOBUZ)
        assertEquals(a, json.decodeFromString<AlbumSummary>(json.encodeToString(a)))
    }

    @Test fun `an artist-page card cached before it kept YouTube's label decodes without one`() {
        val legacy = """{"id":"MPRE1","title":"You Made Me Realise","artist":"","thumbnailUrl":null,"year":"1988"}"""
        assertNull(json.decodeFromString<AlbumSummary>(legacy).releaseType)
        val labelled = AlbumSummary("MPRE1", "You Made Me Realise", "", null, "1988", releaseType = "EP")
        assertEquals(labelled, json.decodeFromString<AlbumSummary>(json.encodeToString(labelled)))
    }
}
