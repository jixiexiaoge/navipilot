package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomaticDrivingModeTest {
    @Test
    fun `distinct broadcasts spanning two seconds are required for each scene`() {
        // Given / When / Then
        listOf(0 to DrivingMode.HIGHWAY_EAP, 6 to DrivingMode.ELEVATED_NOA, 9 to DrivingMode.REGULAR_NOO).forEach { (type, mode) ->
            val detector = AutomaticDrivingMode()
            assertThat(detector.update(type, 1000, 1, 1000)).isNull()
            repeat(10) { assertThat(detector.update(type, 1000, 1, 1500)).isNull() }
            assertThat(detector.update(type, 2000, 1, 2000)).isNull()
            assertThat(detector.update(type, 3000, 1, 3000)).isEqualTo(mode)
            assertThat(detector.update(type, 3000, 1, 3100)).isEqualTo(mode)
            assertThat(detector.update(if (type == 0) 6 else 0, 4000, 1, 4000)).isNull()
        }
    }

    @Test
    fun `rapid packets cannot bypass elapsed threshold and road jitter restarts confirmation`() {
        // Given
        val detector = AutomaticDrivingMode()
        // When / Then
        (1000L..1500L step 100).forEach { assertThat(detector.update(0, it, 1, it)).isNull() }
        assertThat(detector.update(6, 1600, 1, 1600)).isNull()
        assertThat(detector.update(0, 1700, 1, 1700)).isNull()
        assertThat(detector.update(0, 2700, 1, 2700)).isNull()
        assertThat(detector.update(0, 3700, 1, 3700)).isEqualTo(DrivingMode.HIGHWAY_EAP)
    }

    @Test
    fun `invalid missing future expired and route changed evidence withdraws old scene`() {
        // Given / When / Then
        listOf<Int?>(null, -1, 10, 99).forEach { road ->
            val detector = ready()
            assertThat(detector.update(road, 4000, 1, 4000)).isNull()
            assertThat(detector.mode).isNull()
        }
        assertThat(ready().update(0, null, 1, 4000)).isNull()
        assertThat(ready().update(0, 3000, 1, 13001)).isNull()
        assertThat(ready().update(0, 4000, 1, 3999)).isNull()
        assertThat(ready().update(0, 4000, 2, 4000)).isNull()
        assertThat(ready().update(0, 2000, 1, 4000)).isNull()
        assertThat(ready().update(0, 13001, 1, 13001)).isNull()
    }

    private fun ready() = AutomaticDrivingMode().apply {
        update(0, 1000, 1, 1000)
        update(0, 2000, 1, 2000)
        update(0, 3000, 1, 3000)
    }

    @Test
    fun `scene evidence reports distinct packets and receipt span not UI elapsed time`() {
        // Given
        val detector = AutomaticDrivingMode()
        // When / Then
        detector.update(6, 1000, 1, 1000)
        assertThat(detector.evidence).isEqualTo(DrivingSceneEvidence(DrivingMode.ELEVATED_NOA, 1, 0))
        repeat(20) { detector.update(6, 1000, 1, 5000) }
        assertThat(detector.evidence).isEqualTo(DrivingSceneEvidence(DrivingMode.ELEVATED_NOA, 1, 0))
        detector.update(6, 2000, 1, 5000)
        assertThat(detector.evidence).isEqualTo(DrivingSceneEvidence(DrivingMode.ELEVATED_NOA, 2, 1000))
        detector.update(6, 3000, 1, 5000)
        assertThat(detector.mode).isEqualTo(DrivingMode.ELEVATED_NOA)
        detector.update(null, 4000, 1, 5000)
        assertThat(detector.evidence).isNull()
    }

    @Test
    fun `contradictory same timestamp cannot preserve confirmed scene or evidence`() {
        // Given
        val detector = ready()
        // When
        detector.update(6, 3000, 1, 3100)
        // Then
        assertThat(detector.mode).isNull()
        assertThat(detector.evidence).isNull()
        assertThat(detector.update(6, 4000, 1, 4000)).isNull()
        assertThat(detector.evidence?.samples).isEqualTo(1)
    }

    @Test
    fun `wall clock rollback withdraws scene even when the old receipt still appears fresh`() {
        // Given
        val detector = ready()
        detector.update(0, 3000, 1, 5000)
        // When
        val mode = detector.update(0, 3000, 1, 4000)
        // Then
        assertThat(mode).isNull()
        assertThat(detector.evidence?.samples).isEqualTo(1)
    }
}
