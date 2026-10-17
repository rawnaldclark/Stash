package com.stash.data.download.lossless.relay

import com.stash.core.data.supporter.SupporterRepository
import io.mockk.coEvery
import io.mockk.mockk

/** A [SupporterRepository] holding [token] (null = no supporter), for [RelaySigner] in tests. */
internal fun supporter(token: () -> String? = { null }): SupporterRepository = mockk {
    coEvery { tokenNow() } answers { token() }
}
