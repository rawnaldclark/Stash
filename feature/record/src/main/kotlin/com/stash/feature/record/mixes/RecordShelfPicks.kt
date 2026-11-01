package com.stash.feature.record.mixes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Home's "From your record" had mixes to show when its shelf last made them ([RecordMixesShelfViewModel] sets it):
 * the record card's menu says "your mixes stay" only when they do. Null until the shelf has made them once in this process
 * (it isn't composed yet, or is switched off).
 */
@Singleton
class RecordShelfPicks @Inject constructor() {
    private val state = MutableStateFlow<Boolean?>(null)

    val hasPicks: StateFlow<Boolean?> = state.asStateFlow()

    fun set(has: Boolean) {
        state.value = has
    }
}
