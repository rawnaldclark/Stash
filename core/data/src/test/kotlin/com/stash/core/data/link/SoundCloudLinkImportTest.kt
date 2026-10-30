package com.stash.core.data.link

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.weblink.handoff.WireSong
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SoundCloudLinkImportTest {
    private fun song(id: String, preview: Boolean = false) = SoundCloudLinkSong(id,
        "https://soundcloud.com/artist/song-$id", "Same title", "Artist", 180000, null, null, preview)
    private fun preview(vararg songs: SoundCloudLinkSong) = LinkPreview(LinkKind.PLAYLIST, "Set", "Artist", null,
        "SoundCloud", songs.map { WireSong(it.title, it.artist) }, "custom_link_soundcloud_playlist_7", soundCloudSongs = songs.toList())

    @Test fun `real link entry appends missing upload ids and retains custom order`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java).build()
        try {
            val repo = mockk<MusicRepository>(relaxed = true)
            val service = SoundCloudLinkImport(mockk(), mockk(), db, repo)
            val entry = LinkImporter(mockk(), mockk(), mockk(), mockk(), db.playlistDao(), repo, service)
            val first = entry.add(preview(song("101"), song("102")), false) { _, _ -> }
            val a = db.trackDao().findBySoundCloudId("101")!!.id
            val b = db.trackDao().findBySoundCloudId("102")!!.id
            db.playlistOrderDao().move(first.playlistId, b, a, null)
            val second = entry.add(preview(song("101"), song("102"), song("103")), false) { _, _ -> }
            val c = db.trackDao().findBySoundCloudId("103")!!.id
            assertEquals(first.playlistId, second.playlistId)
            assertEquals(1, second.songsAdded)
            assertEquals(listOf(b, a, c), db.playlistOrderDao().memberIdsInOrder(first.playlistId))
        } finally { db.close() }
    }

    @Test fun `all-preview import never enables downloading`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java).build()
        try {
            val repo = mockk<MusicRepository>(relaxed = true)
            SoundCloudLinkImport(mockk(), mockk(), db, repo).add(preview(song("101", true)), true) { _, _ -> }
            coVerify(exactly = 0) { repo.setPlaylistDownload(any(), any()) }
        } finally { db.close() }
    }

    @Test fun `cancelled import leaves no partial playlist or tracks`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java).build()
        try {
            val service = SoundCloudLinkImport(mockk(), mockk(), db, mockk(relaxed = true))
            try {
                service.add(preview(song("101"), song("102")), false) { done, _ -> if (done == 2) throw CancellationException() }
                fail("cancellation swallowed")
            } catch (_: CancellationException) { }
            assertNull(db.playlistDao().findBySourceId("custom_link_soundcloud_playlist_7"))
            assertNull(db.trackDao().findBySoundCloudId("101"))
        } finally { db.close() }
    }

    @Test fun `paste parser recognizes SoundCloud forms and refuses hostile hosts`() {
        for (url in listOf("https://soundcloud.com/artist/song", "https://soundcloud.com/artist/sets/set",
            "https://soundcloud.com/artist/likes", "https://soundcloud.com/artist", "https://on.soundcloud.com/abc")) {
            assertTrue(MusicLinks.parse(url) is MusicLink.SoundCloud)
            assertEquals(url, MusicLinks.findUrl("Listen: $url"))
        }
        assertNull(MusicLinks.parse("https://soundcloud.com.evil.example/artist/song"))
        assertNull(MusicLinks.parse("https://user:password@soundcloud.com/artist/song"))
    }
}
