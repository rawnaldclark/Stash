package com.stash.data.download.files

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.prefs.StoragePreference
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A SAF tree in memory. Documents are mocks over maps; a document's URI is
 * derived from its path, so a document made again at a deleted one's name
 * gets the same URI, as on ExternalStorageProvider. Creating a name that
 * is already taken gets " (1)" added, as the real providers do. The caller
 * mocks [DocumentFile]'s statics (`mockkStatic(DocumentFile::class)`).
 */
internal class FakeSafTree(
    private val resolver: ContentResolver,
    storagePreference: StoragePreference,
) {
    val written = mutableMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()

    /** Writes to a document whose URI this matches fail part-way, as on a full card. */
    var failWritesTo: (String) -> Boolean = { false }
    private val dirs = mutableMapOf<String, MutableMap<String, DocumentFile>>()
    private val root = dir("")

    /** The tree's root URI, as the user's folder pick hands it over. */
    val treeUri: Uri = mockk()

    init {
        every { storagePreference.externalTreeUri } returns flowOf(treeUri)
        every { DocumentFile.fromTreeUri(any(), treeUri) } returns root
        every { resolver.openOutputStream(any<Uri>()) } answers {
            val uri = firstArg<Uri>().toString()
            if (failWritesTo(uri)) {
                return@answers object : ByteArrayOutputStream() {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        super.write(b, off, len)
                        written[uri] = toByteArray()
                        throw IOException("No space left on device")
                    }
                }
            }
            object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    written[uri] = toByteArray()
                }
            }
        }
    }

    fun uriOf(path: String): String =
        "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F" +
            path.replace("/", "%2F")

    fun exists(path: String): Boolean =
        dirs[path.substringBeforeLast('/', "")]?.containsKey(path.substringAfterLast('/')) == true

    fun seedDir(path: String): DocumentFile =
        if (path.isEmpty()) {
            root
        } else {
            path.split('/').fold(root) { cursor, segment -> cursor.findFile(segment) ?: cursor.createDirectory(segment)!! }
        }

    /** Seeds a file at [path] (creating its directories), returns its document. */
    fun seed(path: String, bytes: ByteArray): DocumentFile {
        // substringBeforeLast's default handles root-level files: without it
        // "song.opus" made its own directory named "song.opus".
        val dir = seedDir(path.substringBeforeLast('/', ""))
        val doc = dir.createFile("audio/flac", path.substringAfterLast('/'))!!
        written[uriOf(path)] = bytes
        return doc
    }

    private fun uri(path: String): Uri {
        val text = uriOf(path)
        return mockk { every { this@mockk.toString() } returns text }
    }

    private fun dir(path: String): DocumentFile {
        val children = linkedMapOf<String, DocumentFile>()
        dirs[path] = children
        val doc = mockk<DocumentFile>()
        every { doc.isDirectory } returns true
        every { doc.isFile } returns false
        every { doc.name } returns path.substringAfterLast('/')
        every { doc.uri } returns uri(path)
        every { doc.findFile(any()) } answers { children[firstArg()] }
        every { doc.listFiles() } answers { children.values.toTypedArray() }
        every { doc.createDirectory(any()) } answers {
            val name = firstArg<String>()
            dir(join(path, name)).also { children[name] = it }
        }
        every { doc.createFile(any(), any()) } answers {
            var name = secondArg<String>()
            if (name in children) name = "${name.substringBeforeLast('.')} (1).${name.substringAfterLast('.')}"
            file(join(path, name), children).also { children[name] = it }
        }
        return doc
    }

    private fun file(path: String, siblings: MutableMap<String, DocumentFile>): DocumentFile {
        val parent = path.substringBeforeLast('/', "")
        var name = path.substringAfterLast('/')
        val doc = mockk<DocumentFile>()
        // Like DocumentFile.renameTo, a rename changes the document's URI.
        val docUri = mockk<Uri> { every { this@mockk.toString() } answers { uriOf(join(parent, name)) } }
        every { doc.isDirectory } returns false
        every { doc.isFile } returns true
        every { doc.name } answers { name }
        every { doc.uri } returns docUri
        every { doc.delete() } answers {
            val text = uriOf(join(parent, name))
            siblings.remove(name)
            written.remove(text)
            deleted += text
            true
        }
        every { doc.renameTo(any()) } answers {
            val newName = firstArg<String>()
            if (newName in siblings) {
                false
            } else {
                written.remove(uriOf(join(parent, name)))?.let { written[uriOf(join(parent, newName))] = it }
                siblings.remove(name)
                siblings[newName] = doc
                name = newName
                true
            }
        }
        return doc
    }

    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
}
