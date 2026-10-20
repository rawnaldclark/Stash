package com.stash.data.cloud.provider.onedrive

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class OneDriveJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `either download link name is read`() {
        val graph = """{"id":"A!1","size":10,"@microsoft.graph.downloadUrl":"https://a.example/x"}"""
        val legacy = """{"id":"A!1","size":10,"@content.downloadUrl":"https://b.example/y"}"""
        val none = """{"id":"A!1","size":10}"""
        assertThat(json.decodeFromString(OneDriveProvider.ItemJson.serializer(), graph).link).isEqualTo("https://a.example/x")
        assertThat(json.decodeFromString(OneDriveProvider.ItemJson.serializer(), legacy).link).isEqualTo("https://b.example/y")
        assertThat(json.decodeFromString(OneDriveProvider.ItemJson.serializer(), none).link).isNull()
    }
}
