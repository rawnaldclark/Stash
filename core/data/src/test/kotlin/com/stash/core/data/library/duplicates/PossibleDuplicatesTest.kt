package com.stash.core.data.library.duplicates

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.files.LocalFileOps
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Library Health → Possible duplicates ([PossibleDuplicates]) on a real database: the uncertain
 * pairs are offered with the best row to keep first, "Not a duplicate" is remembered, a Merge keeps
 * the row the user picks, and the extra file goes only after the rows are merged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PossibleDuplicatesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var f: DuplicateFixtures
    private lateinit var duplicates: PossibleDuplicates

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        f = DuplicateFixtures(db)
        duplicates = PossibleDuplicates(db, TrackMerger(db), LocalFileOps(context))
    }

    @After fun tearDown() = db.close()

    @Test fun `two uploads of one song are offered with the downloaded row first`() = runBlocking {
        val downloaded = f.song(youtubeId = "handVideo01", spotifyUri = "spotify:track:so", file = "/music/so.flac", format = "flac")
        val other = f.song(youtubeId = "otherVideo1", plays = 9)
        f.member(f.playlist("Liked", type = PlaylistType.LIKED_SONGS), other, position = 0)

        val pair = duplicates.find().single()

        assertEquals(DuplicateKind.TWO_UPLOADS, pair.kind)
        assertEquals("downloaded beats more plays", downloaded, pair.keeper.id)
        assertEquals(other, pair.other.id)
        assertFalse(pair.removesAFile)
        assertTrue(pair.mayKeep(pair.keeper))
        assertFalse("the song's only file stays", pair.mayKeep(pair.other))
    }

    @Test fun `different version words of one length are offered, a much longer one is not`() = runBlocking {
        f.song(youtubeId = "studioVideo", file = "/music/studio.flac")
        f.song(title = "Starting Over (Live)", youtubeId = "liveVideo01", lengthMs = 309_000L)
        f.song(title = "Starting Over (Extended Mix)", youtubeId = "extended001", lengthMs = 420_000L)

        val pair = duplicates.find().single()

        assertEquals(DuplicateKind.VERSION_WORDS, pair.kind)
        assertEquals("Starting Over (Live)", pair.other.title)
    }

    @Test fun `explicit against clean and a flagged row are never offered`() = runBlocking {
        f.song(youtubeId = "explicit001", explicit = true, file = "/music/e.flac")
        f.song(youtubeId = "clean000001", explicit = false)
        f.song(title = "Other", youtubeId = "otherOne001", file = "/music/o.flac")
        f.song(title = "Other", youtubeId = "otherTwo001", flagged = true)

        assertTrue(duplicates.find().isEmpty())
    }

    @Test fun `not a duplicate is remembered and the pair never comes back`() = runBlocking {
        val a = f.song(youtubeId = "handVideo01", file = "/music/so.flac")
        val b = f.song(youtubeId = "otherVideo1")
        assertEquals(1, duplicates.find().size)

        duplicates.dismiss(b, a)

        assertTrue(duplicates.find().isEmpty())
        assertTrue("a new reader of the same library agrees", PossibleDuplicates(db, TrackMerger(db), LocalFileOps(context)).find().isEmpty())
        assertNotNull(f.row(a)); assertNotNull(f.row(b))
    }

    @Test fun `merging two files keeps the chosen row and deletes the other file after the merge`() = runBlocking {
        val flac = tmp.newFile("starting-over.flac").apply { writeBytes(ByteArray(64)) }
        val opus = tmp.newFile("starting-over.opus").apply { writeBytes(ByteArray(32)) }
        val keep = f.song(youtubeId = "handVideo01", file = flac.path, format = "flac")
        val go = f.stub(file = opus.path)
        val mix = f.playlist("Daily Discover", type = PlaylistType.STASH_MIX)
        f.member(mix, go, position = 2)
        val pair = duplicates.find().single()
        assertEquals(DuplicateKind.TWO_FILES, pair.kind)
        assertTrue(pair.removesAFile)

        val result = duplicates.merge(keeperId = keep, otherId = go)

        assertEquals(PossibleDuplicates.MergeResult.Merged(keptId = keep, removedId = go, removedFile = true), result)
        assertNull(f.row(go))
        assertFalse("the extra copy is gone", opus.exists())
        assertTrue("the kept file is untouched", flac.exists())
        assertEquals(2, f.link(mix, keep)!!.position)
        assertTrue(duplicates.find().isEmpty())
    }

    @Test fun `the user may keep the other row when both hold a file`() = runBlocking {
        val first = tmp.newFile("a.flac").apply { writeBytes(ByteArray(8)) }
        val second = tmp.newFile("b.flac").apply { writeBytes(ByteArray(8)) }
        val suggested = f.song(youtubeId = "videoOne001", file = first.path, format = "flac", plays = 5)
        val chosen = f.song(youtubeId = "videoTwo001", file = second.path, format = "flac")

        val result = duplicates.merge(keeperId = chosen, otherId = suggested)

        assertTrue(result is PossibleDuplicates.MergeResult.Merged)
        assertNull(f.row(suggested))
        assertEquals(5, f.row(chosen)!!.playCount)
        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test fun `a merge that would lose the song's only file is refused`() = runBlocking {
        val file = tmp.newFile("only.flac").apply { writeBytes(ByteArray(8)) }
        val bare = f.song(youtubeId = "bareVideo01")
        val withFile = f.song(youtubeId = "fileVideo01", file = file.path)

        assertEquals(PossibleDuplicates.MergeResult.Refused, duplicates.merge(keeperId = bare, otherId = withFile))
        assertTrue(file.exists())
        assertNotNull(f.row(withFile))
    }

    @Test fun `a file another row still plays from is kept`() = runBlocking {
        val shared = tmp.newFile("shared.flac").apply { writeBytes(ByteArray(8)) }
        val keepFile = tmp.newFile("keep.flac").apply { writeBytes(ByteArray(8)) }
        val keep = f.song(youtubeId = "videoOne001", file = keepFile.path)
        val go = f.song(youtubeId = "videoTwo001", file = shared.path)
        f.song(title = "Some Import", file = shared.path, source = com.stash.core.model.MusicSource.LOCAL)

        val result = duplicates.merge(keeperId = keep, otherId = go) as PossibleDuplicates.MergeResult.Merged

        assertFalse(result.removedFile)
        assertTrue(shared.exists())
    }
}
