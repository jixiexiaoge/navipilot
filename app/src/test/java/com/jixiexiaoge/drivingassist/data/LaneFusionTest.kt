package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.vision.LaneResult
import com.jixiexiaoge.drivingassist.vision.LaneObservationSession
import org.junit.Test

class LaneFusionTest {
    private val tracker = LaneFusionTracker()

    @Test
    fun `road gate requires a fresh explicit highway class even for a presumed ramp`() {
        val fresh = com.jixiexiaoge.drivingassist.navigation.AmapDisplayState.Fresh
        listOf(0, 6).forEach {
            assertThat(com.jixiexiaoge.drivingassist.navigation.isLaneGeometryRoadEligible(fresh, it)).isTrue()
        }
        listOf(null, -1, 1, 2, 3, 4, 5, 7, 8, 9, 10).forEach {
            assertThat(com.jixiexiaoge.drivingassist.navigation.isLaneGeometryRoadEligible(fresh, it)).isFalse()
        }
        com.jixiexiaoge.drivingassist.navigation.AmapDisplayState.entries.filter { it != fresh }.forEach { state ->
            listOf(0, 6).forEach {
                assertThat(com.jixiexiaoge.drivingassist.navigation.isLaneGeometryRoadEligible(state, it)).isFalse()
            }
        }
    }

    private fun vision(time: Long, left: Int = 2, right: Int = 2, session: Long = 1, host: String = "test",
        route: Long = 1, frame: Long = time, model: String = "day") = LaneResult(
        model, left, right, left + right - 1, 10, 10f, frame, 964, 604, 100,
        time + 100_000, time, host, route, session,
    )

    private fun telemetry(time: Long, left: Int = 2, right: Int = 2, state: String = "off",
        frame: Long = time, modelTime: Long = time): CompactTelemetry {
        fun curve(y: Float) = mapOf("x" to listOf(0f, 5f, 15f, 30f), "y" to List(4) { y })
        val values = mapOf<String, Any?>(
            "frameId" to frame, "frameAge" to 0,
            "laneLines" to listOf(curve(-5.25f), curve(-1.75f), curve(1.75f), curve(5.25f)),
            "roadEdges" to listOf(curve(-1.75f - (left - 1) * 3.5f), curve(1.75f + (right - 1) * 3.5f)),
            "laneLineProbs" to List(4) { 0.95f }, "laneLineStds" to List(4) { 0.1f },
            "roadEdgeStds" to listOf(0.1f, 0.1f),
        )
        return CompactTelemetry(mapOf(
            "modelV2" to CompactServiceSample(CompactFrame("modelV2", 1, values, 0),
                modelTime, modelTime + 750, modelTime + 100_000),
            "lateralPlan" to CompactServiceSample(
                CompactFrame("lateralPlan", 1, mapOf("laneChangeState" to state), 0),
                time, time + 500, time + 100_000),
        ), time)
    }

    private fun update(time: Long, result: LaneResult? = vision(time), data: CompactTelemetry? = telemetry(time)) =
        tracker.update(result, data, "test", 1, time, time + 100_000)

    private fun sharedEstimate(result: LaneResult, time: Long) =
        com.jixiexiaoge.drivingassist.navigation.ModelLaneCountSource.estimate(
            result, time, time + 100_000,
            LaneObservationSession(result.sessionId, result.targetHost, result.routeGeneration))

    private fun confirmed(): LaneFusionEstimate {
        assertThat(update(1000).state).isEqualTo(LaneFusionState.Stabilizing)
        assertThat(update(1300).state).isEqualTo(LaneFusionState.Stabilizing)
        return update(1600)
    }

    private fun withoutEdge(time: Long, missing: Int): CompactTelemetry {
        val data = telemetry(time)
        val model = data.services.getValue("modelV2")
        val values = model.frame.values + ("roadEdgeStds" to
            if (missing == 0) listOf(0.9f, 0.1f) else listOf(0.1f, 0.9f))
        return data.copy(services = data.services + ("modelV2" to model.copy(
            frame = model.frame.copy(values = values))))
    }

    @Test
    fun `left edge alone confirms a display band without inventing a total or wire lane`() {
        val session = LaneObservationSession(1, "test", 1)
        var result = LaneFusionEstimate()
        listOf(1000L, 1300L, 1600L).forEach {
            result = tracker.update(null, withoutEdge(it, 1), "test", 1, it, it + 100_000, session)
        }
        assertThat(result.edge?.leftIndex).isEqualTo(2)
        assertThat(result.edge?.rightIndex).isNull()
        assertThat(result.edge?.arLeft).isNull()
        assertThat(result.current).isNull()
        assertThat(result.count).isNull()
        assertThat(result.state).isEqualTo(LaneFusionState.GeometryOnly)
        val ended = tracker.update(null, telemetry(1700), "test", 1, 1700, 101700, null)
        assertThat(ended.edge).isNull()
    }

    @Test
    fun `right edge fallback is right based and needs fresh confirmation when the side changes`() {
        listOf(1000L, 1300L, 1600L).forEach { update(it, data = withoutEdge(it, 0)) }
        val right = update(1700, data = withoutEdge(1700, 0))
        assertThat(right.edge?.rightIndex).isEqualTo(2)
        assertThat(right.edge?.leftIndex).isNull()
        assertThat(right.current).isNull()
        val left = update(1800, data = withoutEdge(1800, 1))
        assertThat(left.edge?.rightIndex).isNull()
        assertThat(left.edge?.leftIndex).isNull()
    }

    @Test
    fun `AR saturation does not erase geometric bands or become an exact AR count`() {
        var result = LaneFusionEstimate()
        listOf(1000L, 1300L, 1600L).forEach {
            result = update(it, vision(it, 3, 2), telemetry(it, 3, 2))
        }
        assertThat(result.edge?.leftIndex).isEqualTo(3)
        assertThat(result.edge?.arLeft).isNull()
        assertThat(result.edge?.arRight).isEqualTo(2)
        assertThat(result.state).isEqualTo(LaneFusionState.Limited)
        assertThat(result.current).isNull()
    }

    @Test
    fun `AR disagreement stays explicit while geometry remains display only`() {
        listOf(1000L, 1300L, 1600L).forEach { update(it, vision(it, 1, 2)) }
        val result = update(1700, vision(1700, 1, 2))
        assertThat(result.edge?.leftIndex).isEqualTo(2)
        assertThat(result.edge?.arLeft).isEqualTo(1)
        assertThat(result.state).isEqualTo(LaneFusionState.Conflict)
        assertThat(result.current).isNull()
    }

    @Test
    fun `geometry confirmation cannot use polling stale lateral state rollback or a previous video session`() {
        repeat(6) { assertThat(update(1000, data = withoutEdge(1000, 1)).edge?.leftIndex).isNull() }
        update(1300, data = withoutEdge(1300, 1))
        assertThat(update(1600, data = withoutEdge(1600, 1)).edge?.leftIndex).isEqualTo(2)
        val newSession = LaneObservationSession(2, "test", 1, 1650)
        assertThat(tracker.update(null, telemetry(1600), "test", 1, 1700, 101700, newSession).edge).isNull()
        assertThat(update(1800, data = telemetry(1800, state = "unknown")).edge).isNull()
        assertThat(update(1900, vision(1900, session = 3), withoutEdge(1900, 1)).edge?.leftIndex).isNull()
        assertThat(update(1800).edge).isNull()
        assertThat(update(3000, vision(3000), telemetry(1900)).edge).isNull()
    }

    @Test
    fun `three advancing paired observations across six hundred milliseconds produce an estimate`() {
        val result = confirmed()
        assertThat(result.state).isEqualTo(LaneFusionState.Estimated)
        assertThat(result.count).isEqualTo(3)
        assertThat(result.current).isEqualTo(2)
        assertThat(result.sourceWallMs).isEqualTo(101600)
        assertThat(result.sourceMonoMs).isEqualTo(1600)
        assertThat(result.expiresMonoMs).isEqualTo(2100)
    }

    @Test
    fun `polling repeated observations cannot accumulate confirmations or renew receipts`() {
        repeat(8) { assertThat(update(1000).current).isNull() }
        update(1300)
        val result = update(1600)
        val repeated = update(1700, vision(1600), telemetry(1600))
        assertThat(repeated).isEqualTo(result)
        assertThat(repeated.sourceMonoMs).isEqualTo(1600)
    }

    @Test
    fun `one faster stream cannot confirm frozen sibling observations`() {
        update(1000)
        assertThat(update(1300, vision(1300), telemetry(1300, frame = 1000, modelTime = 1000)).current).isNull()
        assertThat(update(1600, vision(1600), telemetry(1600, frame = 1000, modelTime = 1000)).current).isNull()
    }

    @Test
    fun `conflicting geometry immediately withdraws an established estimate`() {
        confirmed()
        val conflict = update(1700, data = telemetry(1700, left = 1, right = 3))
        assertThat(conflict.state).isEqualTo(LaneFusionState.Conflict)
        assertThat(conflict.current).isNull()
        assertThat(conflict.geometryIndex).isEqualTo(1)
        assertThat(update(1800).state).isEqualTo(LaneFusionState.Stabilizing)
    }

    @Test
    fun `opaque upper count category never becomes an exact lane index even if widths agree`() {
        val limited = update(1000, vision(1000, 3, 2), telemetry(1000, 3, 2))
        assertThat(limited.state).isEqualTo(LaneFusionState.Limited)
        assertThat(limited.current).isNull()
        assertThat(limited.geometryCount).isEqualTo(4)
        val sixLanes = update(1300, vision(1300, 3, 3), telemetry(1300, 3, 4))
        assertThat(sixLanes.state).isEqualTo(LaneFusionState.Limited)
        assertThat(sixLanes.count).isNull()
    }

    @Test
    fun `missing geometry or vision never silently supplies a vehicle lane`() {
        assertThat(update(1000, data = null).state).isEqualTo(LaneFusionState.VisionOnly)
        assertThat(update(1300, result = null).state).isEqualTo(LaneFusionState.GeometryOnly)
        assertThat(update(1600, result = null, data = null).state).isEqualTo(LaneFusionState.Missing)
    }

    @Test
    fun `all lane change stages withdraw and require new confirmation afterwards`() {
        confirmed()
        listOf("preLaneChange", "laneChangeStarting", "laneChangeFinishing").forEachIndexed { i, stage ->
            val moving = update(1700L + i * 100, data = telemetry(1700L + i * 100, state = stage))
            assertThat(moving.state).isEqualTo(LaneFusionState.Maneuvering)
            assertThat(moving.current).isNull()
        }
        assertThat(update(2000).current).isNull()
    }

    @Test
    fun `separate receipt skew and missing lateral state prevent fusion`() {
        val skew = update(1400, vision(1000), telemetry(1400))
        assertThat(skew.state).isEqualTo(LaneFusionState.Unsynchronized)
        val data = telemetry(1700)
        val noLateral = data.copy(services = data.services - "lateralPlan")
        assertThat(update(1700, data = noLateral).current).isNull()
    }

    @Test
    fun `expiry stale AR and future clocks withdraw rather than retain a successful estimate`() {
        confirmed()
        assertThat(update(2100, vision(1600), telemetry(1600)).current).isNull()
        assertThat(update(3000, vision(1600), telemetry(3000)).current).isNull()
        assertThat(update(3300, vision(3400), telemetry(3300)).current).isNull()
    }

    @Test
    fun `host route session and model changes reset temporal evidence`() {
        confirmed()
        assertThat(update(1700, vision(1700, host = "other")).current).isNull()
        assertThat(update(1800, vision(1800, route = 2)).current).isNull()
        assertThat(update(1900, vision(1900, session = 2)).state).isEqualTo(LaneFusionState.Stabilizing)
        assertThat(update(2200, vision(2200, session = 2, model = "night")).current).isNull()
    }

    @Test
    fun `clock rollback and frame rollback cannot inherit confirmation`() {
        confirmed()
        assertThat(update(1500).current).isNull()
        assertThat(update(1700).state).isEqualTo(LaneFusionState.Stabilizing)
        assertThat(update(2000, data = telemetry(2000, frame = 1)).current).isNull()
        assertThat(update(2300, vision(2300, frame = 1000)).current).isNull()
    }

    @Test
    fun `oldest genuine included source receipt is retained and wall clock absence is rejected`() {
        update(1000)
        update(1300)
        val result = update(1600, data = telemetry(1600, modelTime = 1550))
        assertThat(result.state).isEqualTo(LaneFusionState.Stabilizing)
        val next = update(1900, data = telemetry(1900, modelTime = 1850))
        assertThat(next.state).isEqualTo(LaneFusionState.Estimated)
        assertThat(next.sourceWallMs).isEqualTo(101850)
        val data = telemetry(2000)
        val unknownWall = data.copy(services = data.services.mapValues { (_, s) -> s.copy(receivedAtWallMs = 0) })
        assertThat(update(2000, data = unknownWall).state).isEqualTo(LaneFusionState.Unsynchronized)
    }

    @Test
    fun `shared source rejects retained geometry across a target boundary`() {
        val source = com.jixiexiaoge.drivingassist.navigation.ModelLaneCountSource
        try {
            source.observe(null, null, -1, eligibleRoad = true, nowMonoMs = 900)
            source.observe(telemetry(1000), "test", 1, eligibleRoad = true, nowMonoMs = 1000)
            assertThat(sharedEstimate(vision(1000), 1000).current).isNull()
            listOf(1100L, 1400L, 1700L).forEach {
                source.observe(telemetry(it), "test", 1, eligibleRoad = true, nowMonoMs = it)
                sharedEstimate(vision(it), it)
            }
            assertThat(sharedEstimate(vision(1700), 1700).state).isEqualTo(LaneFusionState.Estimated)
            source.observe(telemetry(1700), "other", 1, eligibleRoad = true, nowMonoMs = 1800)
            assertThat(sharedEstimate(vision(1800, host = "other"), 1800).current).isNull()
            source.observe(telemetry(1700), "other", 1, eligibleRoad = true, nowMonoMs = 1900)
            assertThat(sharedEstimate(vision(1900, host = "other"), 1900).current).isNull()
            source.observe(telemetry(2000), "other", 1, eligibleRoad = true, nowMonoMs = 2000)
            assertThat(sharedEstimate(vision(2000, host = "other"), 2000).state)
                .isEqualTo(LaneFusionState.Stabilizing)
        } finally {
            source.observe(null, null, -1, eligibleRoad = false, nowMonoMs = 3000)
        }
    }

    @Test
    fun `shared source disables fusion outside highway and elevated road types`() {
        val source = com.jixiexiaoge.drivingassist.navigation.ModelLaneCountSource
        try {
            source.observe(telemetry(1000), "test", 1, eligibleRoad = false, nowMonoMs = 1000)
            val blocked = sharedEstimate(vision(1000), 1000)
            assertThat(blocked.state).isEqualTo(LaneFusionState.RoadTypeBlocked)
            assertThat(source.latest()).isNull()
            source.observe(telemetry(1300), "test", 1, eligibleRoad = true, nowMonoMs = 1300)
            assertThat(sharedEstimate(vision(1300), 1300).state)
                .isEqualTo(LaneFusionState.Stabilizing)
        } finally {
            source.observe(null, null, -1, eligibleRoad = false, nowMonoMs = 4000)
        }
    }
}
