package com.stash.feature.search

import com.stash.core.common.power.BatterySaver
import kotlinx.coroutines.flow.MutableStateFlow

/** A [BatterySaver] set by hand, for tests: flip [isOn]`.value` to switch it. */
class FakeBatterySaver(on: Boolean = false) : BatterySaver {
    override val isOn: MutableStateFlow<Boolean> = MutableStateFlow(on)
}
