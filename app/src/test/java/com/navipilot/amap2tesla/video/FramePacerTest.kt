package com.navipilot.amap2tesla.video

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FramePacerTest {
    @Test
    fun `twenty fps timing jitter does not discard every alternate frame`() {
        val pacer = FramePacer(20)
        val accepted = (0 until 20).count { index ->
            val millis = index * 50L - if (index % 2 == 1) 1 else 0
            pacer.shouldEncode(millis * 1_000_000)
        }
        assertThat(accepted).isEqualTo(20)
    }

    @Test
    fun `thirty fps remains thirty with alternating early callbacks`() {
        val pacer = FramePacer(30)
        val accepted = (0 until 30).count { index ->
            val nanos = index * 1_000_000_000L / 30 - if (index % 2 == 1) 1_000_000 else 0
            pacer.shouldEncode(nanos)
        }
        assertThat(accepted).isEqualTo(30)
    }

    @Test
    fun `sixty fps source is bounded to thirty encoded frames`() {
        val pacer = FramePacer(30)
        val accepted = (0 until 60).count { pacer.shouldEncode(it * 1_000_000_000L / 60) }
        assertThat(accepted).isEqualTo(30)
    }

    @Test
    fun `stall skips missed deadlines without a catchup burst`() {
        val pacer = FramePacer(30)
        assertThat(pacer.shouldEncode(0)).isTrue()
        assertThat(pacer.shouldEncode(200_000_000)).isTrue()
        assertThat(pacer.shouldEncode(201_000_000)).isFalse()
        assertThat(pacer.shouldEncode(202_000_000)).isFalse()
        assertThat(pacer.shouldEncode(233_333_333)).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive target is rejected`() {
        FramePacer(0)
    }
}
