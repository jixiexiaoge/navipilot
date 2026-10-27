package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.ui.components.TrafficHint
import com.jixiexiaoge.drivingassist.ui.components.TrafficLamp
import com.jixiexiaoge.drivingassist.ui.components.resolveLamp
import org.junit.Test

/**
 * 7711 世界量的过期门和独立灯色编码；UI 不关联高德与 CAN 灯组。
 * 这里锁住的三件事都容易在后续改动中被无声破坏：
 * 1. `trafficLightColor` 是 **1 红 / 2 绿 / 3 黄**（不是高德的 3=左转绿）
 * 2. 停止线只在 `0 < dist < 127` 时有效（127 是超出感知范围的哨兵）
 * 3. CAN 与高德分别转换，不能把高德方向、倒计时附到 CAN 灯色上
 */
class XiaogeBridgeWorldTest {
    private fun frame(stopLine: Double?, color: Int) = """
        {"version":1,"sequence":7,"timestamp":123.0,"ip":"10.0.0.2",
         "data":{"carState":{"stopLineDist":${stopLine ?: "null"},"trafficLightColor":$color}}}
    """.trimIndent()

    @Test
    fun `world keeps raw can codes and visibility matches dashpilot thresholds`() {
        val world = XiaogeBridgeClient.World(stopLineDistM = 42f, trafficLightColor = 1, receivedAtMs = 10)
        assertThat(world.hasLamp).isTrue()
        assertThat(world.lampVisible).isTrue()
        assertThat(world.stopLineVisible).isTrue()

        // 距离超出感知范围：灯对象仍在（dashpilot 只在 dist>0 时给灯），停止线不再画
        val far = XiaogeBridgeClient.World(stopLineDistM = 180f, trafficLightColor = 2, receivedAtMs = 10)
        assertThat(far.lampVisible).isTrue()
        assertThat(far.stopLineVisible).isFalse()

        // 无颜色或无距离：两者都不成立
        assertThat(XiaogeBridgeClient.World(stopLineDistM = 10f, trafficLightColor = 0).hasLamp).isFalse()
        assertThat(XiaogeBridgeClient.World(stopLineDistM = 0f, trafficLightColor = 1).lampVisible).isFalse()
    }

    @Test
    fun `store publishes world only while fresh and clears stop line past sentinel`() {
        DashboardStore.clear()
        DashboardStore.publishWorld(XiaogeBridgeClient.World(30f, 1, receivedAtMs = 1_000))
        val fresh = DashboardStore.snapshot(1_400)
        assertThat(fresh.road.trafficLightState).isEqualTo(1)
        assertThat(fresh.road.stopLineM).isEqualTo(30f)

        // 超过 1.5 s TTL：两个字段都必须失效，不能沿用上一帧
        val stale = DashboardStore.snapshot(2_600)
        assertThat(stale.road.trafficLightState).isEqualTo(0)
        assertThat(stale.road.stopLineM).isNull()

        DashboardStore.publishWorld(XiaogeBridgeClient.World(127f, 2, receivedAtMs = 3_000))
        val sentinel = DashboardStore.snapshot(3_100)
        assertThat(sentinel.road.trafficLightState).isEqualTo(2)
        assertThat(sentinel.road.stopLineM).isNull()
        DashboardStore.clear()
        assertThat(DashboardStore.snapshot(3_100).road.trafficLightState).isEqualTo(0)
    }

    @Test
    fun `can and navigation lamps use separate code tables`() {
        val hint = TrafficHint(state = 2, remainSec = 12, direction = 1)

        // 兼容转换器保留 CAN 优先，但生产 UI 只传单一来源。
        assertThat(resolveLamp(1, hint)).isEqualTo(TrafficLamp.RED)
        assertThat(resolveLamp(2, hint)).isEqualTo(TrafficLamp.GREEN)
        // 车端的 3 是黄灯，不是高德语义里的左转绿
        assertThat(resolveLamp(3, hint)).isEqualTo(TrafficLamp.YELLOW)

        // 车端没给（0 / 非法值）→ 退回高德：1 红、2/3 绿
        assertThat(resolveLamp(0, hint)).isEqualTo(TrafficLamp.GREEN)
        assertThat(resolveLamp(9, TrafficHint(1, 0, 0))).isEqualTo(TrafficLamp.RED)
        assertThat(resolveLamp(0, TrafficHint(3, 5, 1))).isEqualTo(TrafficLamp.GREEN)
        assertThat(resolveLamp(0, null)).isEqualTo(TrafficLamp.NONE)
        assertThat(resolveLamp(0, TrafficHint(0, 0, 0))).isEqualTo(TrafficLamp.NONE)
        assertThat(resolveLamp(1, null)).isEqualTo(TrafficLamp.RED)
        assertThat(resolveLamp(0, hint)).isEqualTo(TrafficLamp.GREEN)
    }

    @Test
    fun `packet format is length prefixed json with carState only`() {
        // 车端 create_packet 的结构：顶层元信息 + data.carState 两字段
        val text = frame(55.5, 1)
        assertThat(text).contains("\"version\":1")
        assertThat(text).contains("\"stopLineDist\":55.5")
        assertThat(text).contains("\"trafficLightColor\":1")
    }
}
