package com.jixiexiaoge.drivingassist.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaneCountObservationTest {
    @Test
    fun `opaque four classes retain argmax without inventing an index offset or more classes`() {
        assertThat(decodeLaneClass(floatArrayOf(0f, 0.1f, 0.8f, 0.1f), 0)).isEqualTo(2)
        assertThat(decodeLaneClass(floatArrayOf(0f, 0f, 0f, 1f), 0)).isEqualTo(3)
        assertThat(decodeLaneClass(floatArrayOf(1f, 0f, 0f, 0f), 0)).isEqualTo(0)
    }

    @Test
    fun `nonfinite empty tied and incomplete scores are unknown`() {
        listOf(floatArrayOf(0f, Float.NaN, 1f, 0f), floatArrayOf(0f, 0f, Float.POSITIVE_INFINITY, 0f),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 1f, 0f), floatArrayOf(1f)).forEach {
            assertThat(decodeLaneClass(it, 0)).isEqualTo(-1)
        }
        assertThat(decodeLaneClass(FloatArray(4), -1)).isEqualTo(-1)
        assertThat(decodeLaneClass(FloatArray(4), Int.MAX_VALUE)).isEqualTo(-1)
    }

    @Test
    fun `total belongs to the same left right observation and never uses historical median`() {
        assertThat(laneCountTotal(2, 2)).isEqualTo(3)
        assertThat(laneCountTotal(1, 2)).isEqualTo(2)
        assertThat(laneCountTotal(0, 2)).isEqualTo(-1)
        assertThat(laneCountTotal(4, 2)).isEqualTo(-1)
        assertThat(laneCountTotal(3, 3)).isEqualTo(5)
    }
}
