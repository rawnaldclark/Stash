package com.stash.data.cloud.provider.dropbox

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class DropboxJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `get_temporary_link metadata has no tag and still parses as a file`() {
        // The shape Dropbox answered on a real account (2026-10-19), ids and link shortened.
        val body = """
            {"metadata": {"name": "reckoner.flac", "path_lower": "/music/radiohead/in rainbows/reckoner.flac",
             "path_display": "/Music/Radiohead/In Rainbows/reckoner.flac", "id": "id:abc",
             "client_modified": "2026-10-20T02:20:00Z", "server_modified": "2026-10-20T02:20:00Z",
             "rev": "015a1b2c3d4e5f600000001", "size": 60599521, "is_downloadable": true,
             "content_hash": "e3b0c442"},
             "link": "https://uc1234.dl.dropboxusercontent.com/cd/0/get/x/file"}
        """.trimIndent()

        val t = json.decodeFromString(DropboxProvider.TemporaryLinkJson.serializer(), body)

        assertThat(t.link).startsWith("https://")
        assertThat(t.metadata.tag).isEqualTo("file")
        assertThat(t.metadata.size).isEqualTo(60599521L)
        assertThat(t.metadata.rev).isEqualTo("015a1b2c3d4e5f600000001")
    }

    @Test
    fun `list_folder entries keep their tag`() {
        val body = """{"entries": [{".tag": "folder", "name": "Music", "id": "id:m", "path_lower": "/music"}],
            "cursor": "c", "has_more": false}"""
        val page = json.decodeFromString(DropboxProvider.ListFolderJson.serializer(), body)
        assertThat(page.entries.single().tag).isEqualTo("folder")
    }
}
