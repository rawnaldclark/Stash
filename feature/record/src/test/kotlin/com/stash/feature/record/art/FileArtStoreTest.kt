package com.stash.feature.record.art

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The covers' file: kept across visits, written whole, the newest kept when there are too many, anything odd skipped. */
class FileArtStoreTest {
    @get:Rule val dir = TemporaryFolder()

    private val cover = "https://cdn-images.dzcdn.net/images/cover/f7/500x500-000000-80-0-0.jpg"

    private fun store(file: File, max: Int = FileArtStore.MAX_ENTRIES) = FileArtStore(file, max, Dispatchers.Unconfined)

    @Test fun `what is kept is read back by the next visit`() = runTest {
        val f = File(dir.root, "art.tsv")
        val a = store(f)
        a.set("al:frank ocean|blonde", ArtHit(cover, 10))
        a.set("ar:nobody", ArtHit(null, 20))
        a.flush()
        val b = store(f)
        assertThat(b.get("al:frank ocean|blonde")).isEqualTo(ArtHit(cover, 10))
        assertThat(b.get("ar:nobody")).isEqualTo(ArtHit(null, 20))
        assertThat(b.get("ar:somebody")).isNull()
        assertThat(File(f.path + ".tmp").exists()).isFalse()
    }

    @Test fun `nothing is written until a flush, and a flush with nothing new writes nothing`() = runTest {
        val f = File(dir.root, "art.tsv")
        val a = store(f)
        a.flush()
        assertThat(f.exists()).isFalse()
        a.set("ar:x", ArtHit(null, 1))
        assertThat(f.exists()).isFalse()
        a.flush()
        assertThat(f.exists()).isTrue()
    }

    @Test fun `too many, the newest are kept`() = runTest {
        val f = File(dir.root, "art.tsv")
        val a = store(f, max = 3)
        for (i in 1..5) a.set("ar:$i", ArtHit(null, i.toLong()))
        a.flush()
        val b = store(f, max = 3)
        assertThat((1..5).map { b.get("ar:$it") != null }).containsExactly(false, false, true, true, true).inOrder()
    }

    @Test fun `a broken file is an empty store, a broken line is skipped, an address the record can't show is none`() = runTest {
        val f = File(dir.root, "art.tsv")
        f.writeText("ar:ok\t5\t$cover\nnot a line\nar:bad\tnot-a-time\t$cover\nar:elsewhere\t6\thttps://evil.example/x.jpg\n\t7\t$cover\n")
        val b = store(f)
        assertThat(b.get("ar:ok")).isEqualTo(ArtHit(cover, 5))
        assertThat(b.get("ar:bad")).isNull()
        assertThat(b.get("ar:elsewhere")).isEqualTo(ArtHit(null, 6))
        assertThat(store(File(dir.root, "missing.tsv")).get("ar:ok")).isNull()
    }

    @Test fun `a key or address with a tab or a line break is never written`() = runTest {
        val f = File(dir.root, "art.tsv")
        val a = store(f)
        a.set("ar:a\tb", ArtHit(null, 1))
        a.set("ar:c", ArtHit("https://cdn-images.dzcdn.net/x\n.jpg", 1))
        a.set("ar:d", ArtHit(null, 2))
        a.flush()
        assertThat(f.readLines()).containsExactly("ar:d\t2\t")
    }
}
