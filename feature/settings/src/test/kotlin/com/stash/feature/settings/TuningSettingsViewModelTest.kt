package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.tuning.TuningController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/** The exact-Hz field's Set: clamps to the range, keeps one decimal, stores nothing that isn't a number. */
class TuningSettingsViewModelTest {

    private val tuning = mockk<TuningController>(relaxUnitFun = true) {
        every { referenceHz } returns MutableStateFlow(440f)
        every { available } returns true
    }
    private val vm = TuningSettingsViewModel(tuning)

    @Test fun `an exact value in range is stored as typed`() {
        assertThat(vm.setExact("431.5")).isEqualTo(431.5f)
        verify { tuning.setReferenceHz(431.5f) }
    }

    @Test fun `a comma decimal reads as a point`() {
        assertThat(vm.setExact("431,5")).isEqualTo(431.5f)
        verify { tuning.setReferenceHz(431.5f) }
    }

    @Test fun `out-of-range values clamp to 392 and 494`() {
        assertThat(vm.setExact("500")).isEqualTo(494f)
        verify { tuning.setReferenceHz(494f) }
        assertThat(vm.setExact("43")).isEqualTo(392f)
        verify { tuning.setReferenceHz(392f) }
    }

    @Test fun `more than one decimal is rounded to one`() {
        assertThat(vm.setExact("431.55")).isEqualTo(431.6f)
        verify { tuning.setReferenceHz(431.6f) }
    }

    @Test fun `empty or not a number stores nothing`() {
        assertThat(vm.setExact("")).isNull()
        assertThat(vm.setExact(".")).isNull()
        assertThat(vm.setExact("abc")).isNull()
        verify(exactly = 0) { tuning.setReferenceHz(any()) }
    }

    @Test fun `presets and the slider go through sanitized`() {
        vm.setReferenceHz(432f)
        verify { tuning.setReferenceHz(432f) }
        vm.setReferenceHz(431.97f)
        verify { tuning.setReferenceHz(432f) }
    }

    @Test fun `mirrors the controller`() {
        assertThat(vm.referenceHz.value).isEqualTo(440f)
        assertThat(vm.available).isTrue()
    }
}
