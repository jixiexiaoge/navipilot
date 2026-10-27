package com.jixiexiaoge.drivingassist.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OvertakeLabelsTest {
    @Test
    fun `side pair accounts for gap panel padding and scaled minimum metric width`() {
        assertThat(overtakeSidesUseTwoColumns(568f, 1f)).isFalse()
        assertThat(overtakeSidesUseTwoColumns(599f, 1f)).isFalse()
        assertThat(overtakeSidesUseTwoColumns(600f, 1f)).isTrue()
        assertThat(overtakeSidesUseTwoColumns(600f, 0.8f)).isTrue()
        assertThat(overtakeSidesUseTwoColumns(879f, 1.5f)).isFalse()
        assertThat(overtakeSidesUseTwoColumns(880f, 1.5f)).isTrue()
    }

    @Test
    fun `marking codes are preserved and unverified shapes remain neutral`() {
        // Given / When / Then
        assertThat(laneMarkingLabel(null)).contains("未知")
        assertThat(laneMarkingLabel(21)).contains("黄色实线")
        assertThat(laneMarkingLabel(10)).contains("白色虚线")
        assertThat(laneMarkingLabel(-1)).contains("未报告")
        for (code in listOf(2, 5, 38, 99, -2)) {
            assertThat(laneMarkingLabel(code)).contains("类型未确认")
            assertThat(laneMarkingLabel(code)).contains(code.toString())
        }
    }
}
