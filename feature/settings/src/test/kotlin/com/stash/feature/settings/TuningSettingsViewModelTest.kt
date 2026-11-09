package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.tuning.TuningController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * What the Tuning sheet stores goes through sanitized. (The exact field's parsing, clamping and
 * refusals are TuningExactFieldTest's, as drawn.)
 */
class TuningSettingsViewModelTest {

    private val tuning = mockk<TuningController>(relaxUnitFun = true) {
        every { referenceHz } returns MutableStateFlow(440f)
        every { available } returns true
    }
    private val vm = TuningSettingsViewModel(tuning)

    @Test fun `presets, the slider and Set store a sanitized value`() {
        vm.setReferenceHz(432f)
        verify { tuning.setReferenceHz(432f) }
        vm.setReferenceHz(431.97f)
        verify { tuning.setReferenceHz(432f) }
        vm.setReferenceHz(600f)
        verify { tuning.setReferenceHz(494f) }
    }

    @Test fun `mirrors the controller`() {
        assertThat(vm.referenceHz.value).isEqualTo(440f)
        assertThat(vm.available).isTrue()
    }
}
