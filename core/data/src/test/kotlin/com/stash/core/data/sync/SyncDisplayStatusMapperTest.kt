package com.stash.core.data.sync

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.SyncState
import org.junit.Test

class SyncDisplayStatusMapperTest {

    @Test
    fun `cancelled state maps to Cancelled display status`() {
        val entity = SyncHistoryEntity(
            status = SyncState.CANCELLED,
            errorMessage = "Cancelled",
            tracksDownloaded = 3,
        )
        assertThat(entity.toDisplayStatus()).isEqualTo(SyncDisplayStatus.Cancelled)
    }
}
