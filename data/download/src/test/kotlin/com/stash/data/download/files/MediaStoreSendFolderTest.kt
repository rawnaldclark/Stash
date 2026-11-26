package com.stash.data.download.files

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** [MediaStoreSendFolder] against a MediaStore of its own: what it leaves in the folder, and what it makes of no answer. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MediaStoreSendFolderTest {

    /**
     * MediaStore's Downloads, reduced to what the folder asks of it. A file published under a name that is taken (by
     * this app's, or by one in [othersNames], which this app can't see) is renamed "name (1).ext", as MediaStore does.
     */
    class FakeDownloads : ContentProvider() {
        class Row(val id: Long, var name: String, val path: String?, var pending: Boolean, val file: File)

        val rows = LinkedHashMap<Long, Row>()
        val othersNames = HashSet<String>()
        /** Listings of the folder go unanswered (a null cursor), as when MediaStore can't be reached. */
        var unanswered = false
        private var next = 1L
        private lateinit var dir: File

        override fun onCreate(): Boolean {
            dir = File(context!!.cacheDir, "downloads-${System.nanoTime()}").apply { mkdirs() }
            return true
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = next++
            rows[id] = Row(
                id = id,
                name = values!!.getAsString(MediaStore.Downloads.DISPLAY_NAME),
                path = values.getAsString(MediaStore.Downloads.RELATIVE_PATH),
                pending = values.getAsInteger(MediaStore.Downloads.IS_PENDING) == 1,
                file = File(dir, "$id"),
            )
            return ContentUris.withAppendedId(uri, id)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows[idOf(uri)] ?: return 0
            if (values?.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0 && row.pending) {
                row.pending = false
                if (row.name in othersNames || rows.values.any { it !== row && !it.pending && it.name == row.name }) {
                    row.name = row.name.substringBeforeLast('.') + " (1)." + row.name.substringAfterLast('.')
                }
            }
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            if (rows.remove(idOf(uri)) != null) 1 else 0

        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? {
            val trashedOnly = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED) == MediaStore.MATCH_ONLY
            val path = queryArgs?.getStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.firstOrNull()
            return answer(uri, projection, path, trashedOnly)
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            answer(uri, projection, selectionArgs?.firstOrNull(), trashedOnly = false)

        private fun answer(uri: Uri, projection: Array<out String>?, path: String?, trashedOnly: Boolean): Cursor? {
            val id = idOf(uri)
            if (id == null && unanswered) return null
            val columns = projection ?: arrayOf(MediaStore.Downloads._ID)
            val matching = when {
                id != null -> listOfNotNull(rows[id])
                trashedOnly -> emptyList() // nothing in the Trash here
                else -> rows.values.filter { it.path == path }
            }
            return MatrixCursor(columns).apply {
                for (row in matching) {
                    addRow(
                        columns.map { column ->
                            when (column) {
                                MediaStore.Downloads._ID -> row.id
                                MediaStore.Downloads.DISPLAY_NAME -> row.name
                                MediaStore.Downloads.SIZE -> row.file.length()
                                MediaStore.Downloads.IS_PENDING -> if (row.pending) 1 else 0
                                else -> null
                            }
                        },
                    )
                }
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val row = rows[idOf(uri)] ?: throw java.io.FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(row.file.apply { if (!exists()) createNewFile() }, ParcelFileDescriptor.parseMode(mode))
        }

        private fun idOf(uri: Uri): Long? = uri.lastPathSegment?.toLongOrNull()

        fun named(name: String) = rows.values.filter { it.name == name }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeDownloads
    private lateinit var folder: MediaStoreSendFolder

    @Before fun setUp() {
        store = Robolectric.setupContentProvider(FakeDownloads::class.java, MediaStore.AUTHORITY)
        folder = MediaStoreSendFolder(context.contentResolver)
    }

    @Test fun `a song is written whole, under the name it was really given`() {
        val first = folder.write("1.flac", ByteArray(10) { 1 }.inputStream()) { true }
        assertThat(first?.name).isEqualTo("1.flac")
        assertThat(first?.bytes).isEqualTo(10L)
        store.othersNames += "2.flac"
        assertThat(folder.write("2.flac", ByteArray(10) { 2 }.inputStream()) { true }?.name).isEqualTo("2 (1).flac")
        assertThat(folder.files().map { it.name }).containsExactly("1.flac", "2 (1).flac")
    }

    @Test fun `an empty song is no copy, and leaves nothing in the folder`() {
        assertThat(folder.write("1.flac", ByteArray(0).inputStream()) { true }).isNull()
        assertThat(store.rows).isEmpty()
    }

    @Test fun `the list is written under its own name`() {
        folder.writeList("""{"songs":[]}""")
        assertThat(store.named(SongSender.LIST_NAME)).hasSize(1)
        assertThat(folder.readList()).isEqualTo("""{"songs":[]}""")
    }

    @Test fun `a list that can't have its own name is taken back, and the send told`() {
        // Another copy of Stash's list is there, which this app can't see.
        store.othersNames += SongSender.LIST_NAME
        val e = runCatching { folder.writeList("""{"songs":[]}""") }.exceptionOrNull()
        assertThat(e).isInstanceOf(ListInTheWayException::class.java)
        assertThat(store.rows).isEmpty()
        assertThat(folder.readList()).isNull()
    }

    @Test fun `a folder that doesn't answer is not an empty one`() {
        folder.write("1.flac", ByteArray(10).inputStream()) { true }
        store.unanswered = true
        assertThat(runCatching { folder.files() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { folder.trashed() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        store.unanswered = false
        assertThat(folder.files().map { it.name }).containsExactly("1.flac")
    }
}
