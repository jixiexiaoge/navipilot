package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DashboardRoadTest {
    @Test
    fun `plot preserves actual model coordinates and expires without fallback`() {
        val curve = mapOf("x" to listOf(0f, 20f, 80f), "y" to listOf(-1f, -2f, -3f))
        val telemetry = telemetry(mapOf("position" to curve))
        val road = telemetry.dashboardRoad()
        assertThat(road.curves).hasSize(1)
        assertThat(road.curves.first().points[1]).isEqualTo(DashboardRoadPoint(20f, -2f))
        assertThat(telemetry.copy(observedAtMs = 850).dashboardRoad().curves).isEmpty()
        assertThat(telemetry.copy(observedAtMs = 99).dashboardRoad().curves).isEmpty()
        assertThat(buildDashboardSnapshot(telemetry, 850).road.curves).isEmpty()
    }

    @Test
    fun `invalid coordinates do not fabricate a road or discard independent valid curves`() {
        val valid = mapOf("x" to listOf(0f, 20f), "y" to listOf(1f, 2f))
        listOf(
            mapOf("x" to listOf(0f, 0f), "y" to listOf(1f, 2f)),
            mapOf("x" to listOf(0f, 20f), "y" to listOf(Float.NaN, 2f)),
            mapOf("x" to listOf(0f, 20f), "y" to listOf(1f)),
            mapOf("x" to List(65) { it.toFloat() }, "y" to List(65) { 1f }),
        ).forEach { invalid ->
            val road = telemetry(mapOf("position" to invalid, "roadEdges" to listOf(valid, valid))).dashboardRoad()
            assertThat(road.curves.map { it.label }).containsExactly("模型左路缘", "模型右路缘")
        }
    }

    @Test
    fun `line probabilities keep verified left right indices without inventing missing lines`() {
        val curve = mapOf("x" to listOf(0f, 20f), "y" to listOf(1f, 2f))
        val road = telemetry(mapOf(
            "laneLines" to List(4) { curve }, "laneLineProbs" to listOf(0.1f, 0.8f, 0.9f, 0.2f),
        )).dashboardRoad()
        assertThat(road.curves[1].label).isEqualTo("左内边界")
        assertThat(road.curves[1].probability).isEqualTo(0.8f)
        assertThat(road.curves[2].label).isEqualTo("右内边界")
        assertThat(road.curves[2].probability).isEqualTo(0.9f)
    }

    @Test
    fun `scene clips by interpolation without extending a short model curve`() {
        val curve = DashboardRoadCurve("model", listOf(
            DashboardRoadPoint(10f, 1f), DashboardRoadPoint(30f, 3f), DashboardRoadPoint(70f, 7f),
        ), null)
        assertThat(curve.window()!!.points).containsExactly(
            DashboardRoadPoint(10f, 1f), DashboardRoadPoint(30f, 3f), DashboardRoadPoint(50f, 5f),
        ).inOrder()
        assertThat(curve.window(71f, 80f)).isNull()
        assertThat(curve.window(0f, 9f)).isNull()
    }

    @Test
    fun `lane band requires confident noncrossing curves and uses only their shared range`() {
        val left = DashboardRoadCurve("左内边界", listOf(
            DashboardRoadPoint(0f, -2f), DashboardRoadPoint(40f, -1f),
        ), 0.9f)
        val right = DashboardRoadCurve("右内边界", listOf(
            DashboardRoadPoint(10f, 2f), DashboardRoadPoint(60f, 3f),
        ), 0.8f)
        val road = DashboardRoad(listOf(left, right))
        val band = road.laneBand()!!
        assertThat(band.first.points.first()).isEqualTo(DashboardRoadPoint(10f, -1.75f))
        assertThat(band.second.points.last().forwardM).isEqualTo(40f)
        listOf(null, 0.69f).forEach { confidence ->
            assertThat(road.copy(curves = listOf(left.copy(probability = confidence), right)).laneBand()).isNull()
        }
        val crossing = right.copy(points = listOf(
            DashboardRoadPoint(10f, 2f), DashboardRoadPoint(20f, -5f), DashboardRoadPoint(60f, 3f),
        ))
        assertThat(road.copy(curves = listOf(left, crossing)).laneBand()).isNull()
        assertThat(DashboardRoad().laneBand()).isNull()
    }

    @Test
    fun `lane marking codes expire with car state independently of model curves`() {
        val input = telemetry(mapOf("position" to mapOf("x" to listOf(0f, 20f), "y" to listOf(1f, 2f))))
        val car = CompactServiceSample(CompactFrame("carState", 1,
            mapOf("leftLaneLine" to 21, "rightLaneLine" to 99)), 100, 300)
        val road = input.copy(services = input.services + ("carState" to car)).dashboardRoad()
        assertThat(road.leftLineCode).isEqualTo(21)
        assertThat(road.rightLineCode).isNull()
        val expired = input.copy(services = input.services + ("carState" to car), observedAtMs = 300).dashboardRoad()
        assertThat(expired.leftLineCode).isNull()
        assertThat(expired.curves).isNotEmpty()
    }

    private fun telemetry(model: Map<String, Any?>) = CompactTelemetry(mapOf(
        "modelV2" to CompactServiceSample(CompactFrame("modelV2", 1, model), 100, 850),
    ), 200)
}
