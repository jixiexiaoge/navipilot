package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import org.junit.Assert.assertThrows
import org.junit.Test

class AmapCruiseStateTest {
    @Test
    fun `map speed uses independent position freshness with valid reported zero`() {
        // Given
        val tracker = NaviSourceTracker()
        val fields = CarrotManFields()
        // When / Then
        assertThat(buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 1000).mapSpeedKph).isNull()
        listOf(0.0, 80.0, 300.0).forEach { speed ->
            tracker.updatePosition(NaviPositionSample(30.0, 120.0, speed, null, 1000), mapMatched = true)
            assertThat(buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 11000).mapSpeedKph)
                .isEqualTo(speed)
        }
        tracker.update("guidance_current", "vehicle", nowMs = 11001)
        listOf(
            buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 11001),
            buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 999),
            buildAmapNavigationDisplay(fields, tracker.snapshot(), "OTHER", 1000),
        ).forEach { assertThat(it.mapSpeedKph).isNull() }
        listOf(null, -1.0, 301.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { speed ->
            tracker.updatePosition(NaviPositionSample(30.0, 120.0, speed, null, 1000), mapMatched = true)
            assertThat(buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 1000).mapSpeedKph).isNull()
        }
    }

    @Test
    fun `cruise codes remain separate and unknown codes stay unknown`() {
        // Given / When
        val lamps = parseAmapCruiseLamps(
            """[{"status":0,"dir":1,"countdown":12},{"status":1,"dir":2,"countdown":0},{"status":2,"dir":3,"countdown":4},{"status":99,"dir":99,"countdown":5}]"""
        )
        // Then
        assertThat(lamps).containsExactly(
            AmapCruiseLamp(0, 1, 12), AmapCruiseLamp(1, 2, 0),
            AmapCruiseLamp(2, 3, 4), AmapCruiseLamp(99, 99, 5),
        ).inOrder()
    }

    @Test
    fun `invalid syntax types nonfinite fractional and missing fields fail explicitly`() {
        // Given / When / Then
        listOf(
            "", "{}", "null", "[null]", "[{}]",
            """[{"status":0,"dir":1}]""",
            """[{"status":"0","dir":1,"countdown":12}]""",
            """[{"status":0.5,"dir":1,"countdown":12}]""",
            """[{"status":0,"dir":NaN,"countdown":12}]""",
            """[{"status":0,"dir":1,"countdown":null}]""",
            """[{"status":0,"dir":1,"countdown":-1}]""",
            """[{"status":0,"dir":1,"countdown":3601}]""",
            """[{"status":2147483648,"dir":1,"countdown":12}]""",
            """[{"status":0,"dir":1,"countdown":1e1}]""",
            """[{"status":0,"status":1,"dir":1,"countdown":12}]""",
            """[{'status':0,'dir':1,'countdown':12}]""",
            """[{"status":0,"dir":1,"countdown":12},]""",
            """[{"status":0,"dir":1,"countdown":12}] []""",
        ).forEach { raw ->
            assertThrows("Must reject $raw", Exception::class.java) { parseAmapCruiseLamps(raw) }
        }
    }

    @Test
    fun `payload and multi lamp counts are bounded`() {
        // Given
        val light = """{"status":0,"dir":1,"countdown":3600}"""
        // When / Then
        assertThat(parseAmapCruiseLamps(List(16) { light }.joinToString(",", "[", "]"))).hasSize(16)
        assertThrows(IllegalArgumentException::class.java) {
            parseAmapCruiseLamps(List(17) { light }.joinToString(",", "[", "]"))
        }
        assertThrows(IllegalArgumentException::class.java) { parseAmapCruiseLamps(" ".repeat(16_385)) }
        assertThrows(IllegalArgumentException::class.java) {
            parseAmapCruiseLamps("""[{"status":0,"dir":1,"countdown":1,"extra":"${"灯".repeat(6000)}"}]""")
        }
        assertThat(parseAmapCruiseLamps("[]")).isEmpty()
    }

    @Test
    fun `cruise has independent TTL does not renew navigation and is hidden for other sources`() {
        // Given
        val tracker = NaviSourceTracker()
        val lamps = listOf(AmapCruiseLamp(0, 1, 12))
        tracker.updateCruiseLights(lamps, 1000)
        val fields = CarrotManFields()
        // When / Then
        assertThat(tracker.snapshot().updatedAt).isEmpty()
        val display = buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 11000)
        assertThat(display.state).isEqualTo(AmapDisplayState.Idle)
        assertThat(display.cruiseLamps).isEqualTo(lamps)
        assertThat(display.guideDetails).isNull()
        listOf(
            buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 11001),
            buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 999),
            buildAmapNavigationDisplay(fields, tracker.snapshot(), "OTHER", 1000),
        ).forEach { assertThat(it.cruiseLamps).isEmpty() }
        tracker.update("guidance_current", nowMs = 11001)
        assertThat(buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 11001).cruiseLamps).isEmpty()
    }

    @Test
    fun `fresh navigating route takes precedence while expired route can show fresh cruise`() {
        // Given
        val tracker = NaviSourceTracker().apply { update("guidance_current", nowMs = 1000) }
        val fields = CarrotManFields(isNavigating = true, nTBTTurnType = 1, nTBTDist = 100)
        tracker.updateCruiseLights(listOf(AmapCruiseLamp(1, 2, 10)), 11000)
        // When / Then
        val fresh = buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 11000)
        assertThat(fresh.state).isEqualTo(AmapDisplayState.Fresh)
        assertThat(fresh.cruiseLamps).isEmpty()
        val expired = buildAmapNavigationDisplay(fields, tracker.snapshot(), "AMAP", 11001)
        assertThat(expired.state).isEqualTo(AmapDisplayState.Expired)
        assertThat(expired.cruiseLamps).containsExactly(AmapCruiseLamp(1, 2, 10))
        assertThat(expired.current).isNull()
    }
}
