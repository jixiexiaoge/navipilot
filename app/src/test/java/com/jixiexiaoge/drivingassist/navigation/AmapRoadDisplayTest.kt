package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import org.junit.Test

class AmapRoadDisplayTest {
    private val road = AmapCruiseRoad(1000, "Road", 50.0, 90.0, 999, 300.0, 80)
    private val section = AmapSectionDisplay(1000, 1, AmapSectionPhase.Inside, 80, 0.0, 1000.0, "原始起点", "原始终点")

    @Test
    fun `cruise roads have independent TTL and require idle selected source even if route expired`() {
        val sources = NaviSourceTracker().apply { updateCruiseRoad(road) }
        val idle = CarrotManFields()
        assertThat(buildAmapNavigationDisplay(idle, sources.snapshot(), "AMAP", 11000).cruiseRoad).isEqualTo(road)
        listOf(999L, 11001L).forEach {
            assertThat(buildAmapNavigationDisplay(idle, sources.snapshot(), "AMAP", it).cruiseRoad).isNull()
        }
        assertThat(buildAmapNavigationDisplay(idle, sources.snapshot(), "OTHER", 1500).cruiseRoad).isNull()
        assertThat(buildAmapNavigationDisplay(idle.copy(isNavigating = true), sources.snapshot(), "AMAP", 1500).cruiseRoad).isNull()
        sources.update("route", nowMs = 11001)
        assertThat(buildAmapNavigationDisplay(idle, sources.snapshot(), "AMAP", 11001).cruiseRoad).isNull()
        sources.stopNavigation()
        assertThat(sources.snapshot().cruiseRoad).isNull()
    }

    @Test
    fun `source switching and rollback permanently withdraw read only caches without changing protocol clocks`() {
        val sources = NaviSourceTracker().apply {
            updateCruiseRoad(road)
            updateSectionDisplay(section)
            update("route", nowMs = 1000)
        }
        val clocks = sources.snapshot().updatedAt
        sources.sampleReadOnly(1500, true)
        val rollback = sources.sampleReadOnly(1499, true)
        assertThat(rollback.cruiseRoad).isNull()
        assertThat(rollback.sectionDisplay).isNull()
        assertThat(rollback.updatedAt).isEqualTo(clocks)
        assertThat(sources.sampleReadOnly(1500, true).cruiseRoad).isNull()
        sources.updateCruiseRoad(road)
        sources.updateSectionDisplay(section)
        sources.sampleReadOnly(1600, false)
        assertThat(sources.sampleReadOnly(1700, true).cruiseRoad).isNull()
        assertThat(sources.snapshot().sectionDisplay).isNull()
        assertThat(sources.snapshot().updatedAt).isEqualTo(clocks)
    }

    @Test
    fun `section own TTL route and stop boundaries do not borrow fresh guide clocks`() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        val sources = NaviSourceTracker().apply { updateSectionDisplay(section) }
        assertThat(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 11000).sectionDetails).isEqualTo(section)
        sources.update("guidance_current", nowMs = 11001)
        listOf(999L, 11001L).forEach {
            assertThat(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", it).sectionDetails).isNull()
        }
        assertThat(buildAmapNavigationDisplay(fields.copy(noaRouteGeneration = 2), sources.snapshot(), "AMAP", 1200).sectionDetails).isNull()
        assertThat(buildAmapNavigationDisplay(fields, sources.snapshot(), "OTHER", 1200).sectionDetails).isNull()
        assertThat(buildAmapNavigationDisplay(fields.copy(isNavigating = false), sources.snapshot(), "AMAP", 1200).sectionDetails).isNull()
        sources.resetGuideDisplay(1200)
        assertThat(sources.snapshot().sectionDisplay).isNull()
        sources.updateSectionDisplay(section)
        sources.stopNavigation()
        assertThat(sources.snapshot().sectionDisplay).isNull()
    }

    @Test
    fun `phase cannot be inferred from average speed text or arbitrary camera codes`() {
        assertThat(amapSectionPhase(10001, 8, false)).isEqualTo(AmapSectionPhase.Approaching)
        assertThat(amapSectionPhase(10001, 9, true)).isEqualTo(AmapSectionPhase.Ended)
        assertThat(amapSectionPhase(12110, null, true)).isEqualTo(AmapSectionPhase.Inside)
        assertThat(amapSectionPhase(12110, 8, false)).isNull()
        assertThat(amapSectionPhase(10001, 7, true)).isNull()
    }

    @Test
    fun `heading has eight compass points and rejects unknown nonfinite and out of range`() {
        listOf(0.0 to "北", 22.5 to "东北", 90.0 to "东", 135.0 to "东南",
            180.0 to "南", 225.0 to "西南", 270.0 to "西", 315.0 to "西北",
            359.9 to "北").forEach { (degree, label) ->
            assertThat(amapHeadingLabel(degree)).startsWith("$label · ")
            assertThat(amapHeadingLabel(degree)).endsWith("°")
        }
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 360.0).forEach {
            assertThat(amapHeadingLabel(it)).isNull()
        }
    }

    @Test
    fun `map speed and bearing never borrow phone data or guidance freshness`() {
        val sources = NaviSourceTracker()
        val fields = CarrotManFields(isNavigating = true)
        sources.updatePosition(NaviPositionSample(30.0, 120.0, 50.0, 90.0, 1000), false)
        val phoneOnly = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 1000)
        assertThat(phoneOnly.mapSpeedKph).isNull()
        assertThat(phoneOnly.mapHeadingDeg).isNull()
        sources.updatePosition(NaviPositionSample(30.0, 120.0, 0.0, 0.0, 1000), true)
        val fresh = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 11000)
        assertThat(fresh.mapSpeedKph).isEqualTo(0.0)
        assertThat(fresh.mapHeadingDeg).isEqualTo(0.0)
        sources.update("guidance_current", nowMs = 11001)
        val stale = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 11001)
        assertThat(stale.mapSpeedKph).isNull()
        assertThat(stale.mapHeadingDeg).isNull()
        sources.updatePosition(NaviPositionSample(30.0, 120.0, Double.NaN, 360.0, 1000), true)
        val invalid = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 1000)
        assertThat(invalid.mapSpeedKph).isNull()
        assertThat(invalid.mapHeadingDeg).isNull()
    }
}
