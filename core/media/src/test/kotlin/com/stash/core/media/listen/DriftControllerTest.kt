package com.stash.core.media.listen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriftControllerTest {
    private fun speedOf(r: DriftResult) = (r.action as DriftAction.Speed).speed

    @Test fun `under 40 ms nothing happens`() {
        assertThat(DriftController().onTick(errorMs = 39, nowMs = 0).action).isEqualTo(DriftAction.None)
        assertThat(DriftController().onTick(errorMs = -39, nowMs = 0).action).isEqualTo(DriftAction.None)
    }

    @Test fun `40 ms to 1 s nudges the speed by error over 2000, capped at 3 percent`() {
        assertThat(speedOf(DriftController().onTick(50, 0))).isWithin(1e-6f).of(0.975f)  // ahead → slow down
        assertThat(speedOf(DriftController().onTick(-50, 0))).isWithin(1e-6f).of(1.025f) // behind → speed up
        assertThat(speedOf(DriftController().onTick(100, 0))).isWithin(1e-6f).of(0.97f)  // 0.05 capped at 0.03
        assertThat(speedOf(DriftController().onTick(1_000, 0))).isWithin(1e-6f).of(0.97f)
    }

    @Test fun `a correction keeps going until the error is under 20 ms, then returns to normal speed once`() {
        val d = DriftController()
        d.onTick(100, 0)
        assertThat(speedOf(d.onTick(30, 1_000))).isWithin(1e-6f).of(0.985f)
        assertThat(speedOf(d.onTick(10, 2_000))).isWithin(1e-6f).of(1f)
        assertThat(d.onTick(10, 3_000).action).isEqualTo(DriftAction.None)
    }

    @Test fun `over 1 s it seeks`() {
        assertThat(DriftController().onTick(1_001, 0).action).isEqualTo(DriftAction.Seek)
        assertThat(DriftController().onTick(-5_000, 0).action).isEqualTo(DriftAction.Seek)
    }

    @Test fun `drifting is reported only after 10 s above 250 ms, and clears when it recovers`() {
        val d = DriftController()
        assertThat(d.onTick(300, 0).drifting).isFalse()
        assertThat(d.onTick(300, 9_999).drifting).isFalse()
        assertThat(d.onTick(300, 10_000).drifting).isTrue()
        assertThat(d.onTick(100, 11_000).drifting).isFalse()
    }
}
