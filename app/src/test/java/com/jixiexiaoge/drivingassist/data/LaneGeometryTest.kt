package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaneGeometryTest {
    private fun curve(y: Float) = mapOf("x" to listOf(0f, 5f, 15f, 30f), "y" to List(4) { y })
    private fun model(frame: Long = 1) = mapOf<String, Any?>(
        "frameId" to frame, "frameAge" to 0,
        "laneLines" to listOf(curve(-5.25f), curve(-1.75f), curve(1.75f), curve(5.25f)),
        "roadEdges" to listOf(curve(-5.25f), curve(8.75f)),
        "laneLineProbs" to List(4) { 0.95f }, "laneLineStds" to List(4) { 0.1f },
        "roadEdgeStds" to listOf(0.1f, 0.1f),
    )
    private fun telemetry(values: Map<String, Any?> = model(), time: Long = 1000, state: String = "off") =
        CompactTelemetryStore().accept(listOf(
            CompactFrame("modelV2", 1, values, 0),
            CompactFrame("lateralPlan", 1, mapOf("laneChangeState" to state), 0),
        ), time)

    @Test
    fun `same longitudinal section produces signed ordered width and edge space`() {
        // Given / When
        val geometry = measureLaneGeometry(telemetry())
        // Then
        assertThat(geometry.widthM).isEqualTo(3.5f)
        assertThat(geometry.leftSpaceM).isEqualTo(3.5f)
        assertThat(geometry.rightSpaceM).isEqualTo(7f)
        assertThat(geometry.provisionalIndex).isEqualTo(2)
        assertThat(geometry.rightWidthBands).isEqualTo(2)
    }

    @Test
    fun `adjacent lane widths come from the outer lines and respect their confidence`() {
        // Given：4 条线 −5.25 / −1.75 / 1.75 / 5.25，每条间距都是 3.5 m
        val geometry = measureLaneGeometry(telemetry())
        // Then：左、本、右三段都应是 3.5
        assertThat(geometry.leftLaneWidthM).isEqualTo(3.5f)
        assertThat(geometry.widthM).isEqualTo(3.5f)
        assertThat(geometry.rightLaneWidthM).isEqualTo(3.5f)

        // 外侧线置信度不足 → 对应一侧不给值，绝不拿模型外推的线当车道宽度
        val weakOuter = measureLaneGeometry(
            telemetry(model() + ("laneLineProbs" to listOf(0.5f, 0.95f, 0.95f, 0.4f))),
        )
        assertThat(weakOuter.leftLaneWidthM).isNull()
        assertThat(weakOuter.rightLaneWidthM).isNull()
        assertThat(weakOuter.widthM).isEqualTo(3.5f)

        // 外侧线离得太远（> 5 m）通常是路肩，不当作相邻车道
        val shoulder = measureLaneGeometry(
            telemetry(model() + ("laneLines" to listOf(curve(-9.25f), curve(-1.75f), curve(1.75f), curve(5.25f)))),
        )
        assertThat(shoulder.leftLaneWidthM).isNull()
    }

    @Test
    fun `distinct increasing frames across three hundred milliseconds confirm only width bands`() {
        // Given
        val tracker = LaneGeometryTracker()
        // When / Then
        val first = telemetry()
        assertThat(tracker.update(first).provisionalIndex).isNull()
        repeat(5) { assertThat(tracker.update(first.copy(observedAtMs = 1100)).provisionalIndex).isNull() }
        assertThat(tracker.update(telemetry(model(2), 1150)).provisionalIndex).isNull()
        val confirmed = tracker.update(telemetry(model(3), 1300))
        assertThat(confirmed.status).isEqualTo(LaneGeometryStatus.Consistent)
        assertThat(confirmed.provisionalIndex).isEqualTo(2)
        assertThat(tracker.update(telemetry(model(3), 1400)).provisionalIndex).isNull()
    }

    @Test
    fun `missing stale maneuvering and unusable inner lines withdraw estimation`() {
        // Given / When / Then
        assertThat(measureLaneGeometry(null).status).isEqualTo(LaneGeometryStatus.Missing)
        assertThat(measureLaneGeometry(telemetry().copy(observedAtMs = 1750)).status).isEqualTo(LaneGeometryStatus.Expired)
        assertThat(measureLaneGeometry(telemetry(state = "laneChangeStarting")).status).isEqualTo(LaneGeometryStatus.Maneuvering)
        // 内线本身不可用：概率不足 / 标准差 NaN / 帧龄过大 / 缺字段 / 线数不足 → 整条 Invalid
        listOf(
            model() + ("laneLineProbs" to listOf(0.9f, 0.2f, 0.9f, 0.9f)),
            model() + ("laneLineStds" to listOf(0.1f, Float.NaN, 0.1f, 0.1f)),
            model() + ("frameAge" to 3),
            model() - "laneLineStds",
            model() + ("laneLines" to listOf(curve(-5f))),
        ).forEach { assertThat(measureLaneGeometry(telemetry(it)).status).isEqualTo(LaneGeometryStatus.Invalid) }
        assertThat(measureLaneGeometry(telemetry(state = "unknown")).provisionalIndex).isNull()
    }

    @Test
    fun `an unusable left edge does not discard a reliable right edge or ego width`() {
        // Given：内线可信但路缘不可用（标准差超标 / 路缘交叉）
        listOf(
            model() + ("roadEdgeStds" to listOf(0.8f, 0.1f)),
            model() + ("roadEdges" to listOf(curve(0f), curve(8.75f))),
        ).forEach {
            val value = measureLaneGeometry(telemetry(it))
            assertThat(value.status).isEqualTo(LaneGeometryStatus.Stabilizing)
            assertThat(value.widthM).isEqualTo(3.5f)
            assertThat(value.leftSpaceM).isNull()
            assertThat(value.rightSpaceM).isEqualTo(7f)
            assertThat(value.rightWidthBands).isEqualTo(2)
            assertThat(value.provisionalIndex).isNull()
        }
    }

    @Test
    fun `one missing or invalid edge is independent from the other side`() {
        listOf(
            model() + ("roadEdgeStds" to listOf(0.1f, Float.NaN)),
            model() + ("roadEdges" to listOf(curve(-5.25f), null)),
            model() + ("roadEdges" to listOf(curve(-5.25f), curve(0f))),
        ).forEach {
            val value = measureLaneGeometry(telemetry(it))
            assertThat(value.leftWidthBands).isEqualTo(1)
            assertThat(value.leftSpaceM).isEqualTo(3.5f)
            assertThat(value.rightWidthBands).isNull()
            assertThat(value.rightSpaceM).isNull()
            assertThat(value.provisionalIndex).isNull()
        }
    }

    @Test
    fun `a diverging merge or shoulder withdraws only the affected side`() {
        val divergent = model() + ("roadEdges" to listOf(
            mapOf("x" to listOf(0f, 5f, 15f, 30f), "y" to listOf(-5.25f, -5.25f, -8.75f, -8.75f)),
            curve(8.75f),
        ))
        listOf(divergent, model() + ("roadEdges" to listOf(curve(-6.75f), curve(8.75f)))).forEach {
            val value = measureLaneGeometry(telemetry(it))
            assertThat(value.leftWidthBands).isNull()
            assertThat(value.rightWidthBands).isEqualTo(2)
            assertThat(value.provisionalIndex).isNull()
        }
        val wrongOuter = model() + ("laneLines" to listOf(
            curve(1.75f), curve(-1.75f), curve(1.75f), curve(5.25f),
        ))
        assertThat(measureLaneGeometry(telemetry(wrongOuter)).leftWidthBands).isNull()
    }

    @Test
    fun `absent or unknown lateral plan still reports ego lane width`() {
        // Given：车端某些版本不播 lateralPlan（或只给未知状态）
        val withoutLateral = CompactTelemetryStore().accept(listOf(CompactFrame("modelV2", 1, model(), 0)), 1000)
        // When / Then：宽度照给，但不承诺「左起第几带」
        val missing = measureLaneGeometry(withoutLateral)
        assertThat(missing.status).isEqualTo(LaneGeometryStatus.WidthOnly)
        assertThat(missing.widthM).isEqualTo(3.5f)
        assertThat(missing.leftLaneWidthM).isEqualTo(3.5f)
        assertThat(missing.rightLaneWidthM).isEqualTo(3.5f)
        assertThat(missing.provisionalIndex).isNull()

        val unknown = measureLaneGeometry(telemetry(state = "unknown"))
        assertThat(unknown.status).isEqualTo(LaneGeometryStatus.WidthOnly)
        assertThat(unknown.widthM).isEqualTo(3.5f)
    }

    @Test
    fun `shoulder residual inconsistent far geometry and absent outer lines cannot confirm bands`() {
        // Given / When / Then
        listOf(
            model() + ("roadEdges" to listOf(curve(-6.75f), curve(8.75f))),
            model() + ("laneLineProbs" to listOf(0.1f, 0.95f, 0.95f, 0.95f)),
            model() + ("roadEdges" to listOf(mapOf("x" to listOf(0f, 5f, 15f, 30f),
                "y" to listOf(-5.25f, -5.25f, -8.75f, -8.75f)), curve(8.75f))),
        ).forEach {
            val value = measureLaneGeometry(telemetry(it))
            assertThat(value.widthM).isEqualTo(3.5f)
            assertThat(value.provisionalIndex).isNull()
        }
    }

    @Test
    fun `invalid or nonmonotonic arrays never extrapolate or guess geometry`() {
        // Given / When / Then
        listOf(
            mapOf("x" to listOf(0f, 5f, 5f, 30f), "y" to List(4) { -1.75f }),
            mapOf("x" to listOf(0f, 5f), "y" to listOf(-1.75f)),
            mapOf("x" to listOf(20f, 30f), "y" to listOf(-1.75f, -1.75f)),
        ).forEach { broken ->
            val value = model() + ("laneLines" to listOf(curve(-5.25f), broken, curve(1.75f), curve(5.25f)))
            assertThat(measureLaneGeometry(telemetry(value)).status).isEqualTo(LaneGeometryStatus.Invalid)
        }
    }

    @Test
    fun `expiry disconnect and stream gaps reset previously consistent history`() {
        // Given
        val tracker = LaneGeometryTracker()
        tracker.update(telemetry(model(1), 1000))
        tracker.update(telemetry(model(2), 1150))
        assertThat(tracker.update(telemetry(model(3), 1300)).provisionalIndex).isEqualTo(2)
        // When / Then
        assertThat(tracker.update(null).provisionalIndex).isNull()
        assertThat(tracker.update(telemetry(model(4), 1500)).provisionalIndex).isNull()
        assertThat(tracker.update(telemetry(model(5), 1900)).provisionalIndex).isNull()
        assertThat(tracker.update(telemetry(model(6), 2000).copy(observedAtMs = 2750)).status)
            .isEqualTo(LaneGeometryStatus.Expired)
    }
}
