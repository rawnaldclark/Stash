package com.stash.data.download.lossless.relay

import com.stash.core.data.supporter.SupporterRepository
import com.stash.core.common.AppVersionProvider
import io.mockk.coEvery
import io.mockk.mockk

/** A [SupporterRepository] holding [token] (null = no supporter), for [RelaySigner] in tests. */
internal fun supporter(token: () -> String? = { null }): SupporterRepository = mockk {
    coEvery { tokenNow() } answers { token() }
}

internal fun appVersionProvider(code: Int = 157): AppVersionProvider = object : AppVersionProvider {
    override val versionName = "test"
    override val versionCode = code
}
