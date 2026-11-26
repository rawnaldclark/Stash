package com.stash.data.download.files

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.files.SendMemory.Sent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [SendMemory]: the songs the computer has, known from the copies it takes, kept between sends and restarts. */
class SendMemoryTest {

    @get:Rule val dir = TemporaryFolder()

    private val file get() = dir.root.resolve("send_to_computer.json")
    private fun memory() = SendMemory(file)

    @Test fun `nothing is known at first, nor from a file that can't be read`() {
        assertThat(memory().onComputer()).isEmpty()
        file.writeText("{ not json")
        assertThat(memory().onComputer()).isEmpty()
        assertThat(memory().sending()).isEmpty()
    }

    @Test fun `a song is on the computer once its copy goes, but not when it goes to the Trash`() {
        val m = memory()
        m.startSending(mapOf("1.flac" to Sent(1, 101), "2.flac" to Sent(2, 102), "3 (1).flac" to Sent(3, 103)))
        // 1 taken by the computer, 2 put in the Trash on the phone, 3 still there.
        assertThat(m.taken(present = setOf("3 (1).flac", SongSender.LIST_NAME), trashed = setOf(102))).isEqualTo(1)
        assertThat(m.onComputer()).containsExactly(1L)
        // Looked at again with nothing new: nothing more.
        assertThat(m.taken(present = setOf("3 (1).flac"), trashed = setOf(102))).isEqualTo(0)
        // 3 taken too.
        assertThat(m.taken(present = emptySet(), trashed = setOf(102))).isEqualTo(1)
        assertThat(m.onComputer()).containsExactly(1L, 3L)
    }

    @Test fun `a copy found in the Trash is not taken, even once the Trash is emptied`() {
        val m = memory()
        m.startSending(mapOf("1.flac" to Sent(1, 101), "2.flac" to Sent(2, 102)))
        // 1 taken by the computer, 2 put in the Trash on the phone.
        assertThat(m.taken(present = emptySet(), trashed = setOf(102))).isEqualTo(1)
        // The Trash emptied (or purged after 30 days): 2's copy is gone for good, and still was not taken.
        assertThat(m.taken(present = emptySet(), trashed = emptySet())).isEqualTo(0)
        assertThat(m.onComputer()).containsExactly(1L)
        assertThat(memory().onComputer()).containsExactly(1L)
        assertThat(memory().sending().keys).containsExactly("1.flac")
    }

    @Test fun `what was kept for a library since swapped by a restore is not trusted`() {
        val before = memory()
        before.startSending(mapOf("1.flac" to Sent(1, 101), "2.flac" to Sent(2, 102)))
        before.taken(present = setOf("2.flac"), trashed = emptySet())
        assertThat(memory().onComputer()).containsExactly(1L)

        // A restore swapped the library in: its row ids are other songs.
        val after = SendMemory(file, library = 1L)
        assertThat(after.onComputer()).isEmpty()
        assertThat(after.sending()).isEmpty()
        after.startSending(mapOf("5.flac" to Sent(5, 105)))
        after.taken(present = emptySet(), trashed = emptySet())
        assertThat(SendMemory(file, library = 1L).onComputer()).containsExactly(5L)
        assertThat(memory().onComputer()).isEmpty()
    }

    @Test fun `a memory from before libraries were told apart belongs to the library never swapped`() {
        file.writeText("""{"on_computer":[7],"sending":{"8.flac":{"track":8,"media":108}}}""")
        assertThat(memory().onComputer()).containsExactly(7L)
        assertThat(memory().sending()).containsExactly("8.flac", Sent(8, 108))
        assertThat(SendMemory(file, library = 1L).onComputer()).isEmpty()
    }

    @Test fun `an older send's file of the same name in the Trash does not hide a song taken`() {
        val m = memory()
        m.startSending(mapOf("1.flac" to Sent(1, 201)))
        assertThat(m.taken(present = emptySet(), trashed = setOf(101))).isEqualTo(1)
        assertThat(m.onComputer()).containsExactly(1L)
    }

    @Test fun `a file whose id is not known counts as taken once gone`() {
        val m = memory()
        m.startSending(mapOf("1.flac" to Sent(1, null)))
        assertThat(m.taken(present = emptySet(), trashed = setOf(101))).isEqualTo(1)
    }

    @Test fun `what is known outlives the app, and a send over keeps what was taken`() {
        memory().startSending(mapOf("1.flac" to Sent(1, 101), "2.flac" to Sent(2, 102)))
        // The app closed while the computer took song 1; opened again, it is still known which file is which.
        val again = memory()
        assertThat(again.sending().keys).containsExactly("1.flac", "2.flac")
        assertThat(again.taken(present = setOf("2.flac"), trashed = emptySet())).isEqualTo(1)
        again.doneSending()
        // Song 2's copy is cleared by the phone after that: not taken.
        assertThat(again.taken(present = emptySet(), trashed = emptySet())).isEqualTo(0)
        val later = memory()
        assertThat(later.onComputer()).containsExactly(1L)
        assertThat(later.sending()).isEmpty()
    }

    @Test fun `forgetting sends every song again, and leaves a send in the folder known`() {
        val m = memory()
        m.startSending(mapOf("1.flac" to Sent(1, 101), "2.flac" to Sent(2, 102)))
        m.taken(present = setOf("2.flac"), trashed = emptySet())
        m.forget()
        assertThat(m.onComputer()).isEmpty()
        assertThat(memory().onComputer()).isEmpty()
        assertThat(m.sending().keys).containsExactly("1.flac", "2.flac")
    }

    @Test fun `a new send replaces the last one's files`() {
        val m = memory()
        m.startSending(mapOf("1.flac" to Sent(1, 101)))
        m.startSending(mapOf("2.flac" to Sent(2, 102)))
        assertThat(m.sending()).containsExactly("2.flac", Sent(2, 102))
        assertThat(m.taken(present = emptySet(), trashed = emptySet())).isEqualTo(1)
        assertThat(m.onComputer()).containsExactly(2L)
    }
}
